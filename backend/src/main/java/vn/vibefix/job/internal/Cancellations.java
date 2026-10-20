package vn.vibefix.job.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.job.internal.ChangeRequests.Party;
import vn.vibefix.job.internal.JobLifecycle.Planned;
import vn.vibefix.job.internal.Jobs.Job;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.notification.TodoRequest;
import vn.vibefix.notification.Todos;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.IdGenerator;
import vn.vibefix.timers.DeadlineRequest;
import vn.vibefix.timers.Deadlines;

/**
 * Cancellation by agreement (FR-40, AD-5, AD-7): either side proposes cancelling the Job or its
 * remaining Milestones with a reason; the other side confirms on the loaded Job version within the copied
 * days, and one cascade cancels the Planned Milestones (M6), the Held one with its full refund (M7), and
 * ends the Job: with Accepted work through Closing (J8, then J9 to Cancelled for an Assessment Job),
 * without it straight to Cancelled (J10). An unanswered proposal tells its proposer; any other outcome
 * is a Dispute's. Every command takes the Job row lock first (AD-4).
 */
@Component
class Cancellations {

	/** The cascade's input that marks J8 and J9 as a cancellation's. */
	static final String INPUT = "cancellation";

	static final String PROPOSED = "job.cancellation_proposed";

	static final String UNANSWERED = "job.cancellation_unanswered";

	static final String ANSWER = "job.answer_cancellation";

	static final int DEFAULT_DAYS = 5;

	static final Set<JobState> CANCELLABLE = Set.of(JobState.AGREEMENT, JobState.STARTING, JobState.ACTIVE);

	private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("dd/MM");

	private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

	enum Scope {
		JOB, REMAINING
	}

	record Proposal(UUID id, UUID jobId, Scope scope, String reason, Party proposer, String status, Instant answerBy,
			@Nullable Instant unansweredAt, Instant createdAt, @Nullable Instant decidedAt) {
	}

	private final JdbcClient jdbc;

	private final Jobs jobs;

	private final JobLifecycle lifecycle;

	private final ClosureProposals closures;

	private final Deadlines deadlines;

	private final Notifications notifications;

	private final Todos todos;

	private final AuditLog auditLog;

	private final IdGenerator ids;

	private final Clock clock;

	private final StallDeadlines stalls;

	Cancellations(JdbcClient jdbc, Jobs jobs, JobLifecycle lifecycle, ClosureProposals closures, Deadlines deadlines,
			Notifications notifications, Todos todos, AuditLog auditLog, IdGenerator ids, Clock clock,
			StallDeadlines stalls) {
		this.stalls = stalls;
		this.jdbc = jdbc;
		this.jobs = jobs;
		this.lifecycle = lifecycle;
		this.closures = closures;
		this.deadlines = deadlines;
		this.notifications = notifications;
		this.todos = todos;
		this.auditLog = auditLog;
		this.ids = ids;
		this.clock = clock;
	}

	/**
	 * @throws ApiException {@code STALE STATE_CHANGED}; {@code CONFLICT CANCELLATION_OPEN} or
	 * {@code CLOSURE_OPEN}; {@code VALIDATION}
	 */
	@Transactional
	Proposal propose(UUID jobId, UUID account, Party party, long version, Scope scope, String reason) {
		Job job = locked(jobId, version);
		if (!CANCELLABLE.contains(job.state()) || job.mentorId() == null) {
			throw new ApiException(ErrorCode.STALE, JobReason.STATE_CHANGED);
		}
		if (open(jobId).isPresent()) {
			throw new ApiException(ErrorCode.CONFLICT, JobReason.CANCELLATION_OPEN);
		}
		if (this.closures.open(jobId).isPresent()) {
			throw new ApiException(ErrorCode.CONFLICT, JobReason.CLOSURE_OPEN);
		}
		String text = reason.strip();
		if (text.isEmpty() || text.length() > 2000) {
			throw new ApiException(ErrorCode.VALIDATION);
		}
		Instant now = this.clock.instant();
		Instant answerBy = now.plus(Duration.ofDays(days(jobId)));
		UUID id = this.ids.newId();
		this.jdbc.sql("""
				INSERT INTO job.cancellation_proposal (id, job_id, scope, reason, proposer, proposer_account_id, status, answer_by,
				    created_at)
				VALUES (:id, :job, :scope, :reason, :proposer, :account, 'OPEN', :answerBy, :now)
				""")
			.param("id", id)
			.param("job", jobId)
			.param("scope", scope.name())
			.param("reason", text)
			.param("proposer", party.name())
			.param("account", account)
			.param("answerBy", Timestamp.from(answerBy))
			.param("now", Timestamp.from(now))
			.update();
		this.deadlines.schedule(new DeadlineRequest(CancellationAnswer.KEY, id, id.toString(), jobId, answerBy));
		audit(account, "job.cancellation_proposed", id, jobId);
		Recipient other = party == Party.CUSTOMER ? new Recipient(Objects.requireNonNull(job.mentorId()), Audience.MENTOR)
				: new Recipient(job.customerId(), Audience.CUSTOMER);
		String date = DAY_MONTH.format(answerBy.atZone(VIETNAM));
		String link = "/cong-viec/" + jobId;
		this.notifications.send(new NotificationRequest(PROPOSED, id, List.of(other), Map.of("job", job.title(), "date", date),
				link, jobId, null));
		this.todos.open(new TodoRequest(ANSWER, other.accountId(), other.audience(), id, Map.of("job", job.title(), "date", date),
				link, answerBy, jobId));
		return find(id).orElseThrow();
	}

	/**
	 * The other side confirms: the cancellation applies.
	 * @throws ApiException {@code STALE STATE_CHANGED}; {@code CONFLICT NO_CANCELLATION_OPEN};
	 * {@code FORBIDDEN CANCELLATION_PROPOSER}; the cascade's refusals
	 */
	@Transactional
	Proposal confirm(UUID jobId, UUID account, Party party, long version) {
		Job job = locked(jobId, version);
		Proposal proposal = open(jobId).orElseThrow(() -> new ApiException(ErrorCode.CONFLICT, JobReason.NO_CANCELLATION_OPEN));
		if (proposal.proposer() == party) {
			throw new ApiException(ErrorCode.FORBIDDEN, JobReason.CANCELLATION_PROPOSER);
		}
		List<Planned> cascade = cascade(job);
		ApiException refusal = this.lifecycle.refusal(jobId, Trigger.USER, cascade);
		if (refusal != null) {
			throw refusal;
		}
		this.lifecycle.tryApplyCascade(jobId, Trigger.USER, cascade)
			.orElseThrow(() -> new IllegalStateException("The cancellation of " + jobId + " passed its checks but did not apply"));
		this.jdbc.sql("""
				UPDATE job.cancellation_proposal SET status = 'CONFIRMED', confirmed_by = :account, decided_at = :now WHERE id = :id
				""").param("id", proposal.id()).param("account", account).param("now", Timestamp.from(this.clock.instant())).update();
		this.todos.closeAll(ANSWER, proposal.id());
		audit(account, "job.cancellation_confirmed", proposal.id(), jobId);
		return find(proposal.id()).orElseThrow();
	}

	/**
	 * The proposer withdraws.
	 * @throws ApiException {@code CONFLICT NO_CANCELLATION_OPEN}; {@code FORBIDDEN CANCELLATION_PROPOSER}
	 */
	@Transactional
	Proposal withdraw(UUID jobId, UUID account, Party party) {
		this.jobs.lock(jobId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		Proposal proposal = open(jobId).orElseThrow(() -> new ApiException(ErrorCode.CONFLICT, JobReason.NO_CANCELLATION_OPEN));
		if (proposal.proposer() != party) {
			throw new ApiException(ErrorCode.FORBIDDEN, JobReason.CANCELLATION_PROPOSER);
		}
		this.jdbc.sql("UPDATE job.cancellation_proposal SET status = 'WITHDRAWN', decided_at = :now WHERE id = :id")
			.param("id", proposal.id())
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
		this.todos.closeAll(ANSWER, proposal.id());
		audit(account, "job.cancellation_withdrawn", proposal.id(), jobId);
		return find(proposal.id()).orElseThrow();
	}

	/**
	 * One side cancels alone after the Agreement or Starting deadline passed (FR-40): M6, M7 with the full
	 * refund, and J10, kept as a confirmed proposal marked alone.
	 * @throws ApiException {@code STALE STATE_CHANGED}; {@code GUARD_FAILED DEADLINE_NOT_PASSED};
	 * {@code CONFLICT CANCELLATION_OPEN}; {@code VALIDATION}; the cascade's refusals
	 */
	@Transactional
	Proposal cancelAlone(UUID jobId, UUID account, Party party, long version, String reason) {
		Job job = locked(jobId, version);
		if (!Set.of(JobState.AGREEMENT, JobState.STARTING).contains(job.state()) || !this.stalls.passed(jobId)) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.DEADLINE_NOT_PASSED);
		}
		if (open(jobId).isPresent()) {
			throw new ApiException(ErrorCode.CONFLICT, JobReason.CANCELLATION_OPEN);
		}
		String text = reason.strip();
		if (text.isEmpty() || text.length() > 2000) {
			throw new ApiException(ErrorCode.VALIDATION);
		}
		List<Planned> cascade = cascade(job);
		ApiException refusal = this.lifecycle.refusal(jobId, Trigger.USER, cascade);
		if (refusal != null) {
			throw refusal;
		}
		this.lifecycle.tryApplyCascade(jobId, Trigger.USER, cascade)
			.orElseThrow(() -> new IllegalStateException("The cancellation of " + jobId + " passed its checks but did not apply"));
		UUID id = this.ids.newId();
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO job.cancellation_proposal (id, job_id, scope, reason, proposer, proposer_account_id, status, answer_by,
				    confirmed_by, created_at, decided_at, alone)
				VALUES (:id, :job, 'JOB', :reason, :proposer, :account, 'CONFIRMED', :now, :account, :now, :now, true)
				""")
			.param("id", id)
			.param("job", jobId)
			.param("reason", text)
			.param("proposer", party.name())
			.param("account", account)
			.param("now", now)
			.update();
		audit(account, "job.cancellation_alone", id, jobId);
		return find(id).orElseThrow();
	}

	/** Whether one side may cancel alone now. */
	boolean aloneAllowed(Job job) {
		return Set.of(JobState.AGREEMENT, JobState.STARTING).contains(job.state()) && this.stalls.passed(job.id());
	}

	/** The copied days passed: the proposer is told once; the proposal stays open (FR-40). */
	@Transactional
	boolean unanswered(UUID proposalId) {
		Proposal proposal = find(proposalId).orElse(null);
		if (proposal == null || !"OPEN".equals(proposal.status()) || proposal.unansweredAt() != null) {
			return false;
		}
		Job job = this.jobs.lock(proposal.jobId()).orElseThrow();
		this.jdbc.sql("UPDATE job.cancellation_proposal SET unanswered_at = :now WHERE id = :id")
			.param("id", proposalId)
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
		Recipient proposer = proposal.proposer() == Party.CUSTOMER ? new Recipient(job.customerId(), Audience.CUSTOMER)
				: new Recipient(Objects.requireNonNull(job.mentorId()), Audience.MENTOR);
		this.notifications.send(new NotificationRequest(UNANSWERED, proposalId, List.of(proposer), Map.of("job", job.title()),
				"/cong-viec/" + job.id(), job.id(), null));
		return true;
	}

	Optional<Proposal> open(UUID jobId) {
		return this.jdbc.sql("SELECT * FROM job.cancellation_proposal WHERE job_id = :job AND status = 'OPEN'")
			.param("job", jobId)
			.query(Cancellations::proposal)
			.optional();
	}

	Optional<Proposal> latest(UUID jobId) {
		return this.jdbc.sql("SELECT * FROM job.cancellation_proposal WHERE job_id = :job ORDER BY created_at DESC, id LIMIT 1")
			.param("job", jobId)
			.query(Cancellations::proposal)
			.optional();
	}

	Optional<Proposal> find(UUID proposalId) {
		return this.jdbc.sql("SELECT * FROM job.cancellation_proposal WHERE id = :id")
			.param("id", proposalId)
			.query(Cancellations::proposal)
			.optional();
	}

	/** Whether a Milestone is held, so the confirmation refunds it (the dialog's explanation). */
	boolean held(UUID jobId) {
		return Closures.held(this.jdbc, jobId);
	}

	/** M6 for each Planned Milestone, M7 for the Held one, then J8 and J9, or J10. */
	private List<Planned> cascade(Job job) {
		List<Planned> cascade = new ArrayList<>();
		boolean accepted = false;
		for (Map<String, Object> row : this.jdbc.sql("""
				SELECT id, state FROM job.milestone WHERE job_id = :job AND removed_at IS NULL ORDER BY position, created_at, id
				""").param("job", job.id()).query().listOfRows()) {
			UUID milestone = (UUID) row.get("id");
			String state = (String) row.get("state");
			if ("PLANNED".equals(state)) {
				cascade.add(Planned.milestone(Transition.M6, milestone, Map.of(), Trigger.USER));
			}
			else if (Set.of("FUNDED", "REVISION_REQUESTED", "SUBMITTED").contains(state)) {
				cascade.add(Planned.milestone(Transition.M7, milestone, Map.of(), Trigger.USER));
			}
			else if ("ACCEPTED".equals(state) || "SETTLED".equals(state)) {
				accepted = true;
			}
		}
		if (accepted) {
			cascade.add(new Planned(Transition.J8, null, Map.of(INPUT, true), Trigger.USER));
			if (!job.code()) {
				cascade.add(new Planned(Transition.J9, null, Map.of(INPUT, true), Trigger.SYSTEM));
			}
		}
		else {
			cascade.add(Planned.job(Transition.J10, Trigger.USER));
		}
		return cascade;
	}

	private int days(UUID jobId) {
		Integer days = this.jdbc.sql("SELECT (rules ->> 'CANCELLATION_CONFIRMATION_DAYS')::int FROM job.job WHERE id = :id")
			.param("id", jobId)
			.query(Integer.class)
			.optional()
			.orElse(null);
		return days == null ? DEFAULT_DAYS : days;
	}

	private Job locked(UUID jobId, long version) {
		Job job = this.jobs.lock(jobId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (job.version() != version) {
			throw new ApiException(ErrorCode.STALE, JobReason.STATE_CHANGED);
		}
		return job;
	}

	private void audit(UUID account, String action, UUID proposalId, UUID jobId) {
		this.auditLog.record(AuditEntry.of(AuditActor.user(account), action,
				AuditObject.of("job.cancellation_proposal", proposalId)).forJob(jobId));
	}

	private static Proposal proposal(ResultSet rs, int n) throws SQLException {
		Timestamp unanswered = rs.getTimestamp("unanswered_at");
		Timestamp decided = rs.getTimestamp("decided_at");
		return new Proposal(rs.getObject("id", UUID.class), rs.getObject("job_id", UUID.class),
				Scope.valueOf(rs.getString("scope")), rs.getString("reason"), Party.valueOf(rs.getString("proposer")),
				rs.getString("status"), rs.getTimestamp("answer_by").toInstant(), unanswered == null ? null : unanswered.toInstant(),
				rs.getTimestamp("created_at").toInstant(), decided == null ? null : decided.toInstant());
	}

}
