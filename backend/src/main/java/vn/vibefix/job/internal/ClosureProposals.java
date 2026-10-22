package vn.vibefix.job.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.vibefix.identity.MentorDirectory;
import vn.vibefix.identity.MentorDirectory.MentorCard;
import vn.vibefix.job.internal.Jobs.Job;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.notification.TodoRequest;
import vn.vibefix.notification.Todos;
import vn.vibefix.job.ClosureProposalChanged;
import vn.vibefix.shared.IdGenerator;
import vn.vibefix.timers.DeadlineRequest;
import vn.vibefix.timers.Deadlines;

/**
 * Closure proposals as rows (FR-37, FR-67): the System's after the last acceptance, with its auto-confirm
 * Deadline, or a person's. Each tells whoever must answer ({@code job.closure_proposed}) and opens
 * {@code job.answer_closure} for them, at tier 1 for the System's (it decides itself if ignored) and
 * tier 2 for a person's. No transition runs here, so the steps that propose need no lifecycle.
 */
@Component
class ClosureProposals {

	static final String UNANSWERED = "job.closure_unanswered";

	static final String PROPOSED = "job.closure_proposed";

	static final String ANSWER = "job.answer_closure";

	static final int DEFAULT_AUTOMATIC_DAYS = 7;

	static final int DEFAULT_CONFIRMATION_DAYS = 5;

	private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("dd/MM");

	private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

	enum Party {
		CUSTOMER, MENTOR
	}

	/** One closure proposal; {@code proposerId} is empty for the System's. */
	record Proposal(UUID id, UUID jobId, String kind, @Nullable UUID proposerId, String status,
			@Nullable Instant customerConfirmedAt, @Nullable Instant mentorConfirmedAt, @Nullable UUID declinedBy,
			@Nullable Instant decidedAt, @Nullable Instant autoConfirmAt, Instant answerBy, Instant createdAt) {

		boolean automatic() {
			return "AUTOMATIC".equals(this.kind);
		}

		boolean confirmedBy(Party party) {
			return party == Party.CUSTOMER ? this.customerConfirmedAt != null : this.mentorConfirmedAt != null;
		}

	}

	static final String COLUMNS = """
			id, job_id, kind, proposer_account_id, status, customer_confirmed_at, mentor_confirmed_at, declined_by, decided_at,
			auto_confirm_at, answer_by, created_at
			""";

	private final JdbcClient jdbc;

	private final MentorDirectory mentors;

	private final Notifications notifications;

	private final Todos todos;

	private final Deadlines deadlines;

	private final IdGenerator ids;

	private final ApplicationEventPublisher events;

	private final Clock clock;

	ClosureProposals(JdbcClient jdbc, MentorDirectory mentors, Notifications notifications, Todos todos, Deadlines deadlines,
			IdGenerator ids, ApplicationEventPublisher events, Clock clock) {
		this.jdbc = jdbc;
		this.events = events;
		this.mentors = mentors;
		this.notifications = notifications;
		this.todos = todos;
		this.deadlines = deadlines;
		this.ids = ids;
		this.clock = clock;
	}

	/**
	 * FR-67: after the last acceptance the System proposes closure to both sides, auto-confirmed after
	 * the Job's copied {@code AUTOMATIC_CLOSURE_DAYS} unless one declines. Nothing when a proposal is open
	 * or a Milestone is still to be done.
	 */
	void proposeAutomatically(Job job, UUID causeId) {
		if (open(job.id()).isPresent() || remaining(job.id())) {
			return;
		}
		Instant now = this.clock.instant();
		Instant confirmAt = now.plus(Duration.ofDays(days(job.id(), "AUTOMATIC_CLOSURE_DAYS", DEFAULT_AUTOMATIC_DAYS)));
		UUID id = insert(job.id(), "AUTOMATIC", null, confirmAt, confirmAt, now);
		this.notifications.send(new NotificationRequest(PROPOSED, causeId,
				List.of(new Recipient(job.customerId(), Audience.CUSTOMER),
						new Recipient(Objects.requireNonNull(job.mentorId()), Audience.MENTOR)),
				Map.of("job", job.title(), "proposer", "vibefix"), "/cong-viec/" + job.id(), job.id(), null));
		answer(job, Party.CUSTOMER, id, confirmAt, 1);
		answer(job, Party.MENTOR, id, confirmAt, 1);
		this.deadlines.schedule(new DeadlineRequest(ClosureAutoConfirm.KEY, id, id.toString(), job.id(), confirmAt));
	}

	/** FR-37: a person proposes; the other side answers within the copied {@code CLOSURE_CONFIRMATION_DAYS}. */
	Proposal propose(Job job, UUID account, Party party) {
		Instant now = this.clock.instant();
		Instant answerBy = now.plus(Duration.ofDays(days(job.id(), "CLOSURE_CONFIRMATION_DAYS", DEFAULT_CONFIRMATION_DAYS)));
		UUID id = insert(job.id(), "PROPOSED", account, null, answerBy, now);
		this.jdbc.sql("UPDATE job.closure_proposal SET " + (party == Party.CUSTOMER ? "customer" : "mentor")
				+ "_confirmed_at = :now WHERE id = :id").param("id", id).param("now", Timestamp.from(now)).update();
		Party other = party == Party.CUSTOMER ? Party.MENTOR : Party.CUSTOMER;
		String proposer = party == Party.CUSTOMER ? "Khách hàng"
				: this.mentors.cards(List.of(account)).stream().findFirst().map(MentorCard::displayName).orElse("Người hướng dẫn");
		this.notifications.send(new NotificationRequest(PROPOSED, id, List.of(recipient(job, other)),
				Map.of("job", job.title(), "proposer", proposer), "/cong-viec/" + job.id(), job.id(), null));
		answer(job, other, id, answerBy, 2);
		this.deadlines.schedule(new DeadlineRequest(ClosureAnswer.KEY, id, id.toString(), job.id(), answerBy));
		return find(id).orElseThrow();
	}

	/**
	 * FR-39: the other side did not answer a person's proposal in time; its proposer is told once and may
	 * open a Dispute. The proposal stays open.
	 * @return whether it applied
	 */
	boolean unanswered(Job job, UUID proposalId) {
		Proposal proposal = find(proposalId).orElse(null);
		if (proposal == null || proposal.automatic() || !"OPEN".equals(proposal.status())) {
			return false;
		}
		int marked = this.jdbc.sql("""
				UPDATE job.closure_proposal SET unanswered_at = :now WHERE id = :id AND unanswered_at IS NULL
				""").param("id", proposalId).param("now", Timestamp.from(this.clock.instant())).update();
		if (marked == 0) {
			return false;
		}
		Party proposer = job.customerId().equals(proposal.proposerId()) ? Party.CUSTOMER : Party.MENTOR;
		this.notifications.send(new NotificationRequest(UNANSWERED, proposalId, List.of(recipient(job, proposer)),
				Map.of("job", job.title()), "/cong-viec/" + job.id(), job.id(), null));
		return true;
	}

	/** The proposal was declined: {@code repo} ends a Final Merge authorization it opened (AD-10). */
	void declined(Proposal proposal) {
		this.events.publishEvent(new ClosureProposalChanged(this.ids.newId(), proposal.jobId(), proposal.id(),
				ClosureProposalChanged.Status.DECLINED, proposal.automatic()));
	}

	/** Whether the proposal went unanswered past its date (FR-39). */
	boolean wentUnanswered(UUID proposalId) {
		return this.jdbc.sql("SELECT unanswered_at IS NOT NULL FROM job.closure_proposal WHERE id = :id")
			.param("id", proposalId)
			.query(Boolean.class)
			.optional()
			.orElse(false);
	}

	Optional<Proposal> open(UUID jobId) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM job.closure_proposal WHERE job_id = :job AND status = 'OPEN'")
			.param("job", jobId)
			.query(ClosureProposals::proposal)
			.optional();
	}

	/** The open proposal, else the latest one. */
	Optional<Proposal> latest(UUID jobId) {
		return this.jdbc.sql("SELECT " + COLUMNS
				+ " FROM job.closure_proposal WHERE job_id = :job ORDER BY (status = 'OPEN') DESC, created_at DESC LIMIT 1")
			.param("job", jobId)
			.query(ClosureProposals::proposal)
			.optional();
	}

	Optional<Proposal> find(UUID id) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM job.closure_proposal WHERE id = :id")
			.param("id", id)
			.query(ClosureProposals::proposal)
			.optional();
	}

	/** Whether a Milestone is still to be done: Planned, Funded, Revision Requested, or Submitted. */
	boolean remaining(UUID jobId) {
		return this.jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM job.milestone WHERE job_id = :job AND removed_at IS NULL
				    AND state IN ('PLANNED', 'FUNDED', 'REVISION_REQUESTED', 'SUBMITTED'))
				""").param("job", jobId).query(Boolean.class).single();
	}

	static Recipient recipient(Job job, Party party) {
		return party == Party.CUSTOMER ? new Recipient(job.customerId(), Audience.CUSTOMER)
				: new Recipient(Objects.requireNonNull(job.mentorId()), Audience.MENTOR);
	}

	static String dayMonth(Instant at) {
		return DAY_MONTH.format(at.atZone(VIETNAM));
	}

	private void answer(Job job, Party party, UUID proposalId, Instant by, int tier) {
		Recipient recipient = recipient(job, party);
		this.todos.open(new TodoRequest(ANSWER, recipient.accountId(), recipient.audience(), proposalId,
				Map.of("job", job.title(), "date", dayMonth(by)), "/cong-viec/" + job.id(), by, job.id(), tier));
	}

	private UUID insert(UUID jobId, String kind, @Nullable UUID proposer, @Nullable Instant autoConfirmAt, Instant answerBy,
			Instant now) {
		UUID id = this.ids.newId();
		this.jdbc.sql("""
				INSERT INTO job.closure_proposal (id, job_id, kind, proposer_account_id, status, auto_confirm_at, answer_by,
				    created_at)
				VALUES (:id, :job, :kind, :proposer, 'OPEN', :confirmAt, :answerBy, :now)
				""")
			.param("id", id)
			.param("job", jobId)
			.param("kind", kind)
			.param("proposer", proposer)
			.param("confirmAt", autoConfirmAt == null ? null : Timestamp.from(autoConfirmAt))
			.param("answerBy", Timestamp.from(answerBy))
			.param("now", Timestamp.from(now))
			.update();
		this.events.publishEvent(new ClosureProposalChanged(this.ids.newId(), jobId, id, ClosureProposalChanged.Status.OPEN,
				"AUTOMATIC".equals(kind)));
		return id;
	}

	private int days(UUID jobId, String rule, int fallback) {
		Integer days = this.jdbc.sql("SELECT (rules ->> :rule)::int FROM job.job WHERE id = :id")
			.param("rule", rule)
			.param("id", jobId)
			.query(Integer.class)
			.optional()
			.orElse(null);
		return days == null ? fallback : days;
	}

	private static Proposal proposal(ResultSet rs, int n) throws SQLException {
		return new Proposal(rs.getObject("id", UUID.class), rs.getObject("job_id", UUID.class), rs.getString("kind"),
				rs.getObject("proposer_account_id", UUID.class), rs.getString("status"), instant(rs, "customer_confirmed_at"),
				instant(rs, "mentor_confirmed_at"), rs.getObject("declined_by", UUID.class), instant(rs, "decided_at"),
				instant(rs, "auto_confirm_at"), Objects.requireNonNull(instant(rs, "answer_by")),
				Objects.requireNonNull(instant(rs, "created_at")));
	}

	private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp value = rs.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}

}
