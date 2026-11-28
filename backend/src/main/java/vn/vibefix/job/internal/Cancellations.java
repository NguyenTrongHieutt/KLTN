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
import vn.vibefix.timers.DeadlineOutcome;
import vn.vibefix.timers.DeadlineRequest;
import vn.vibefix.timers.Deadlines;

/**
 * Cancellation by agreement (FR-40, AD-5, AD-7): either side proposes cancelling the Job or its
 * remaining Milestones with a reason; the other side confirms on the loaded Job version within the copied
 * days, and one cascade cancels the Planned Milestones (M6), the Held one with its full refund (M7), and
 * ends the Job: with Accepted work through Closing (J8, then J9 to Cancelled for an Assessment Job),
 * without it straight to Cancelled (J10). An unanswered proposal tells its proposer; any other outcome
 * is a Dispute's. Every command takes the Job row lock first (AD-4).
 *
 * <p>In a Self-mentored Job with a Held Milestone and no counted member, nobody is there to confirm, so the proposal
 * command decides at once (R49, AD-47 exceptions 1 and 6): the cancellation applies on the Customer's word alone when
 * no member was active since that Milestone was funded, and is refused otherwise ({@link NoMember}).
 *
 * <p>In a Self-mentored Job the Customer is the Mentor, so nobody is "the other side" (AD-47 exception 6, Story 25.7).
 * With no Held Milestone the Customer's proposal command cancels at once, and the active members are told by the
 * cascade's own notice. With a Held Milestone and members who count, the proposal waits for the agreement of every
 * member who was active when it was made ({@link ProposalAnswers}, R56): each answers once through {@link #answer};
 * the cancellation applies when all who still count have agreed, a refusal ends it {@code REFUSED}, and no answer by
 * the date tells the Customer. The confirm command does not exist there.
 */
@Component
class Cancellations {

	/** The cascade's input that marks J8 and J9 as a cancellation's. */
	static final String INPUT = "cancellation";

	static final String PROPOSED = "job.cancellation_proposed";

	static final String UNANSWERED = "job.cancellation_unanswered";

	/** A Self-mentored Job's members are asked to agree to the Customer's cancellation (UX-DR130). */
	static final String AWAITING_MEMBERS = "job.cancellation_awaiting_members";

	/** A member of a Self-mentored Job refused the Customer's cancellation. */
	static final String REFUSED = "job.cancellation_refused";

	static final String ANSWER = "job.answer_cancellation";

	static final int DEFAULT_DAYS = 5;

	static final Set<JobState> CANCELLABLE = Set.of(JobState.AGREEMENT, JobState.STARTING, JobState.ACTIVE);

	private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("dd/MM");

	private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

	enum Scope {
		JOB, REMAINING
	}

	/**
	 * What decides the cancellation of a Self-mentored Job with a Held Milestone and no counted member (R49):
	 * {@code ALONE}, nobody was active since the Milestone was funded, so the Customer cancels alone with the full
	 * refund; {@code CREW_WAS_ACTIVE}, a member was, so the Customer recruits again or opens a Dispute.
	 */
	enum NoMember {
		ALONE, CREW_WAS_ACTIVE
	}

	/** @param alone applied on one side's word alone: after a stall, or in a Self-mentored Job with nobody to ask */
	record Proposal(UUID id, UUID jobId, Scope scope, String reason, Party proposer, String status, Instant answerBy,
			@Nullable Instant unansweredAt, Instant createdAt, @Nullable Instant decidedAt, boolean alone) {
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

	private final CrewService crews;

	private final ProposalAnswers answers;

	Cancellations(JdbcClient jdbc, Jobs jobs, JobLifecycle lifecycle, ClosureProposals closures, Deadlines deadlines,
			Notifications notifications, Todos todos, AuditLog auditLog, IdGenerator ids, Clock clock,
			StallDeadlines stalls, CrewService crews, ProposalAnswers answers) {
		this.answers = answers;
		this.stalls = stalls;
		this.crews = crews;
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
	 * Proposes the cancellation to the other side. In a Self-mentored Job with a Held Milestone and no counted member
	 * it is decided here instead ({@link NoMember}): applied at once and kept as a confirmed proposal marked alone, or
	 * refused.
	 * @throws ApiException {@code STALE STATE_CHANGED}; {@code CONFLICT CANCELLATION_OPEN} or
	 * {@code CLOSURE_OPEN}; {@code VALIDATION}; {@code GUARD_FAILED CREW_WAS_ACTIVE}; for a cancellation applied at once,
	 * the cascade's refusals
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
		NoMember noMember = noMember(job);
		if (noMember == NoMember.CREW_WAS_ACTIVE) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.CREW_WAS_ACTIVE);
		}
		if (noMember == NoMember.ALONE) {
			return applyAlone(job, account, party, scope, text);
		}
		// AD-47 exception 6 (B-06): with no Held Milestone the Customer of a Self-mentored Job cancels alone, at once.
		if (job.selfMentored() && !held(jobId)) {
			return applyAlone(job, account, party, scope, text);
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
		String date = DAY_MONTH.format(answerBy.atZone(VIETNAM));
		String link = "/cong-viec/" + jobId;
		if (job.selfMentored()) {
			// The other side is the members who count now (R56), never the proposer's own account (B-05 a).
			List<Recipient> members = this.answers.ask(jobId, ProposalAnswers.Kind.CANCELLATION, id)
				.stream()
				.map(member -> new Recipient(member, Audience.CREW_ACTIVE))
				.toList();
			this.notifications.send(new NotificationRequest(AWAITING_MEMBERS, id, members,
					Map.of("job", job.title(), "date", date), link, jobId, null));
			for (Recipient member : members) {
				this.todos.open(new TodoRequest(ANSWER, member.accountId(), member.audience(), id,
						Map.of("job", job.title(), "date", date), link, answerBy, jobId));
			}
			return find(id).orElseThrow();
		}
		Recipient other = party == Party.CUSTOMER ? new Recipient(Objects.requireNonNull(job.mentorId()), Audience.MENTOR)
				: new Recipient(job.customerId(), Audience.CUSTOMER);
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
		Job job = this.jobs.lock(jobId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		// In a Self-mentored Job the members answer through their own command (B-04, B-11): this one is not there,
		// whatever version the request names.
		if (job.selfMentored()) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		if (job.version() != version) {
			throw new ApiException(ErrorCode.STALE, JobReason.STATE_CHANGED);
		}
		Proposal proposal = open(jobId).orElseThrow(() -> new ApiException(ErrorCode.CONFLICT, JobReason.NO_CANCELLATION_OPEN));
		if (proposal.proposer() == party) {
			throw new ApiException(ErrorCode.FORBIDDEN, JobReason.CANCELLATION_PROPOSER);
		}
		applyAgreed(job, proposal, account);
		audit(account, "job.cancellation_confirmed", proposal.id(), jobId);
		return find(proposal.id()).orElseThrow();
	}

	/** The cascade of an agreed proposal, which then stands {@code CONFIRMED}. The caller holds the Job lock. */
	private void applyAgreed(Job job, Proposal proposal, @Nullable UUID account) {
		List<Planned> cascade = cascade(job);
		ApiException refusal = this.lifecycle.refusal(job.id(), Trigger.USER, cascade);
		if (refusal != null) {
			throw refusal;
		}
		this.lifecycle.tryApplyCascade(job.id(), Trigger.USER, cascade)
			.orElseThrow(() -> new IllegalStateException("The cancellation of " + job.id() + " passed its checks but did not apply"));
		int confirmed = this.jdbc.sql("""
				UPDATE job.cancellation_proposal SET status = 'CONFIRMED', confirmed_by = :account, decided_at = :now
				WHERE id = :id AND status = 'OPEN'
				""").param("id", proposal.id()).param("account", account).param("now", Timestamp.from(this.clock.instant())).update();
		if (confirmed != 1) {
			// Every caller read the open proposal under the Job lock, so this cannot be: nothing may apply without it.
			throw new IllegalStateException("The cancellation proposal " + proposal.id() + " was no longer open");
		}
		this.todos.closeAll(ANSWER, proposal.id());
	}

	/**
	 * A member left the Self-mentored Job or was locked (R56): they no longer count for its open proposal, so their
	 * to-do closes, and when those who still count have all agreed the cancellation applies now. When the Job refuses
	 * the cascade for the moment (a freeze, the busy marker), a Deadline of the proposal's own kind is due at once: it
	 * waits out a freeze and comes again while the Job is busy, whether or not the proposal's date has passed
	 * ({@link #unanswered}). Nothing in a normal Job.
	 */
	@Transactional
	void memberGone(UUID jobId, UUID memberId) {
		Job job = this.jobs.lock(jobId).orElse(null);
		Proposal proposal = job == null || !job.selfMentored() ? null : open(jobId).orElse(null);
		if (proposal == null) {
			return;
		}
		this.todos.close(ANSWER, memberId, proposal.id());
		if (!this.answers.tally(jobId, proposal.id()).agreedByAll()) {
			return;
		}
		if (this.lifecycle.refusal(jobId, Trigger.USER, cascade(job)) == null) {
			applyAgreed(job, proposal, null);
			this.auditLog.record(AuditEntry.of(AuditActor.system(), "job.cancellation_agreed_by_all",
					AuditObject.of("job.cancellation_proposal", proposal.id())).forJob(jobId));
			return;
		}
		this.deadlines.schedule(new DeadlineRequest(CancellationAnswer.KEY, proposal.id(), proposal.id() + ":agreed:" + memberId,
				jobId, this.clock.instant()));
	}

	/**
	 * A member of a Self-mentored Job answers the Customer's cancellation (AD-47 exception 6, R56, B-04): once, and only a
	 * member who was asked and still counts. When every such member has agreed the cancellation applies, with the full
	 * refund of the Held Milestone; a refusal ends the proposal, and the Customer is told.
	 * @param proposalId the proposal the member was shown, the open one
	 * @throws ApiException {@code NOT_FOUND} in a normal Job; {@code CONFLICT NO_CANCELLATION_OPEN}; {@code STALE} when
	 * another proposal is open than the one shown; {@code FORBIDDEN NOT_ASKED}; {@code CONFLICT PROPOSAL_ANSWERED}; the
	 * cascade's refusals, the freeze among them, which leave the answer unrecorded
	 */
	@Transactional
	Proposal answer(UUID jobId, UUID account, UUID proposalId, boolean agree) {
		Job job = this.jobs.lock(jobId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (!job.selfMentored()) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		Proposal proposal = open(jobId).orElseThrow(() -> new ApiException(ErrorCode.CONFLICT, JobReason.NO_CANCELLATION_OPEN));
		if (!proposal.id().equals(proposalId)) {
			throw new ApiException(ErrorCode.STALE, JobReason.STATE_CHANGED);
		}
		if (!this.answers.asked(jobId, proposalId, account)) {
			throw new ApiException(ErrorCode.FORBIDDEN, JobReason.NOT_ASKED);
		}
		if (!this.answers.record(proposalId, account, agree)) {
			throw new ApiException(ErrorCode.CONFLICT, JobReason.PROPOSAL_ANSWERED);
		}
		this.todos.close(ANSWER, account, proposalId);
		this.auditLog.record(AuditEntry.of(AuditActor.user(account), agree ? "job.cancellation_agreed" : "job.cancellation_refused",
				AuditObject.of("job.cancellation_proposal", proposalId)).forJob(jobId));
		if (!agree) {
			this.jdbc.sql("UPDATE job.cancellation_proposal SET status = 'REFUSED', decided_at = :now WHERE id = :id")
				.param("id", proposalId)
				.param("now", Timestamp.from(this.clock.instant()))
				.update();
			this.todos.closeAll(ANSWER, proposalId);
			this.notifications.send(new NotificationRequest(REFUSED, proposalId,
					List.of(new Recipient(job.customerId(), Audience.CUSTOMER)), Map.of("job", job.title()), "/cong-viec/" + jobId,
					jobId, null));
		}
		else if (this.answers.tally(jobId, proposalId).agreedByAll()) {
			applyAgreed(job, proposal, account);
		}
		return find(proposalId).orElseThrow();
	}

	/** Where the members' answers to a Self-mentored Job's proposal stand; {@code null} for a normal Job's proposal. */
	ProposalAnswers.@Nullable Tally tally(Job job, Proposal proposal) {
		return job.selfMentored() && !proposal.alone() ? this.answers.tally(job.id(), proposal.id()) : null;
	}

	/** Whether the member is asked about the open proposal, still counts, and has not answered. */
	boolean mayAnswer(Job job, Proposal proposal, UUID account) {
		return job.selfMentored() && "OPEN".equals(proposal.status()) && this.answers.asked(job.id(), proposal.id(), account)
				&& this.answers.answerOf(proposal.id(), account) == null;
	}

	/** The member's own answer to the proposal, if they gave one. */
	@Nullable String answerOf(Proposal proposal, UUID account) {
		return this.answers.answerOf(proposal.id(), account);
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
		return applyAlone(job, account, party, Scope.JOB, text);
	}

	/** The cascade on one side's word alone, kept as a confirmed proposal marked alone. The caller holds the Job lock. */
	private Proposal applyAlone(Job job, UUID account, Party party, Scope scope, String reason) {
		UUID jobId = job.id();
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
				VALUES (:id, :job, :scope, :reason, :proposer, :account, 'CONFIRMED', :now, :account, :now, :now, true)
				""")
			.param("id", id)
			.param("job", jobId)
			.param("scope", scope.name())
			.param("reason", reason)
			.param("proposer", party.name())
			.param("account", account)
			.param("now", now)
			.update();
		audit(account, "job.cancellation_alone", id, jobId);
		return find(id).orElseThrow();
	}

	/**
	 * Who decides the cancellation of a Self-mentored Job whose Held Milestone has no counted member now (R49; AD-4's
	 * guard of M7's alone-cancel), or {@code null} where the usual rules apply: a normal Job, no Held Milestone, or a
	 * member who is active. The command reads it under the Job lock; the view reads it unlocked, to say what the page
	 * offers.
	 */
	@Nullable NoMember noMember(Job job) {
		if (!job.selfMentored() || job.mentorId() == null || !CANCELLABLE.contains(job.state())) {
			return null;
		}
		// A Held Milestone always records when it was funded (the table's milestone_funded_check).
		Instant funded = this.jdbc.sql("""
				SELECT min(funded_at) FROM job.milestone WHERE job_id = :job AND removed_at IS NULL
				    AND state IN ('FUNDED', 'REVISION_REQUESTED', 'SUBMITTED')
				""").param("job", job.id()).query(Timestamp.class).optional().map(Timestamp::toInstant).orElse(null);
		if (funded == null || !this.crews.countedActive(job.id()).isEmpty()) {
			return null;
		}
		return this.crews.wouldBeParties(job.id(), funded, this.clock.instant()).isEmpty() ? NoMember.ALONE
				: NoMember.CREW_WAS_ACTIVE;
	}

	/** Whether one side may cancel alone now. */
	boolean aloneAllowed(Job job) {
		return Set.of(JobState.AGREEMENT, JobState.STARTING).contains(job.state()) && this.stalls.passed(job.id());
	}

	/**
	 * The copied days passed: the proposer is told once; the proposal stays open (FR-40). In a Self-mentored Job an
	 * agreement that is complete among the members who still count applies instead (R56), at the date or whenever a
	 * Deadline {@link #memberGone} scheduled comes, also after the date and after the Customer was told; if the Job is
	 * busy just then the Deadline comes again (AD-28), and for any other refusal, which no known state gives, the
	 * Customer is not told a falsehood: the proposal waits as it is.
	 */
	@Transactional
	DeadlineOutcome unanswered(UUID proposalId) {
		UUID jobId = find(proposalId).map(Proposal::jobId).orElse(null);
		Job job = jobId == null ? null : this.jobs.lock(jobId).orElse(null);
		// Read again under the Job lock: an answer or a withdrawal may have ended it since.
		Proposal proposal = job == null ? null : find(proposalId).orElse(null);
		if (proposal == null || !"OPEN".equals(proposal.status())) {
			return DeadlineOutcome.OBSOLETE;
		}
		if (job.selfMentored() && this.answers.tally(job.id(), proposalId).agreedByAll()) {
			ApiException refusal = this.lifecycle.refusal(job.id(), Trigger.USER, cascade(job));
			if (refusal == null) {
				applyAgreed(job, proposal, null);
				this.auditLog.record(AuditEntry.of(AuditActor.system(), "job.cancellation_agreed_by_all",
						AuditObject.of("job.cancellation_proposal", proposalId)).forJob(job.id()));
				return DeadlineOutcome.FIRED;
			}
			return refusal.code() == ErrorCode.JOB_BUSY ? DeadlineOutcome.REARM : DeadlineOutcome.OBSOLETE;
		}
		// Told once, and only once the date has come: a Deadline scheduled by a leave may run before it.
		if (proposal.unansweredAt() != null || this.clock.instant().isBefore(proposal.answerBy())) {
			return DeadlineOutcome.OBSOLETE;
		}
		this.jdbc.sql("UPDATE job.cancellation_proposal SET unanswered_at = :now WHERE id = :id")
			.param("id", proposalId)
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
		Recipient proposer = proposal.proposer() == Party.CUSTOMER ? new Recipient(job.customerId(), Audience.CUSTOMER)
				: new Recipient(Objects.requireNonNull(job.mentorId()), Audience.MENTOR);
		this.notifications.send(new NotificationRequest(UNANSWERED, proposalId, List.of(proposer), Map.of("job", job.title()),
				"/cong-viec/" + job.id(), job.id(), null));
		return DeadlineOutcome.FIRED;
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
				rs.getTimestamp("created_at").toInstant(), decided == null ? null : decided.toInstant(), rs.getBoolean("alone"));
	}

}
