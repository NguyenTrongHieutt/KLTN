package vn.vibefix.job.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.job.internal.ClosureProposals.Party;
import vn.vibefix.job.internal.ClosureProposals.Proposal;
import vn.vibefix.job.internal.JobLifecycle.Planned;
import vn.vibefix.job.internal.Jobs.Job;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Todos;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;

/**
 * Answering a closure (FR-37, FR-67, AD-4, AD-23): the Customer or the Mentor proposes, confirms, or
 * declines, on the Job version they loaded, under the Job row lock. A closure is confirmed when the
 * other side confirms a person's proposal, when both confirm the System's, or when its Deadline is due;
 * then J8, M6 for every Planned Milestone, and for an Assessment Job J9 apply as one cascade. A decline
 * keeps the Job Active and tells the proposer, or for the System's the other side.
 */
@Component
class Closures {

	static final String DECLINED = "job.closure_declined";

	private final JdbcClient jdbc;

	private final Jobs jobs;

	private final JobLifecycle lifecycle;

	private final ClosureProposals proposals;

	private final Notifications notifications;

	private final Todos todos;

	private final AuditLog auditLog;

	private final Clock clock;

	Closures(JdbcClient jdbc, Jobs jobs, JobLifecycle lifecycle, ClosureProposals proposals, Notifications notifications,
			Todos todos, AuditLog auditLog, Clock clock) {
		this.jdbc = jdbc;
		this.jobs = jobs;
		this.lifecycle = lifecycle;
		this.proposals = proposals;
		this.notifications = notifications;
		this.todos = todos;
		this.auditLog = auditLog;
		this.clock = clock;
	}

	/**
	 * @throws ApiException {@code STALE STATE_CHANGED}; {@code GUARD_FAILED MILESTONE_HELD};
	 * {@code CONFLICT CLOSURE_OPEN}
	 */
	@Transactional
	Proposal propose(UUID jobId, UUID account, Party party, long version) {
		Job job = locked(jobId, version);
		if (job.state() != JobState.ACTIVE) {
			throw new ApiException(ErrorCode.STALE, JobReason.STATE_CHANGED);
		}
		if (held(this.jdbc, jobId)) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.MILESTONE_HELD);
		}
		if (this.proposals.open(jobId).isPresent()) {
			throw new ApiException(ErrorCode.CONFLICT, JobReason.CLOSURE_OPEN);
		}
		Proposal proposal = this.proposals.propose(job, account, party);
		audit(account, "job.closure_proposed", proposal.id(), jobId);
		return proposal;
	}

	/**
	 * Confirms the open proposal; the closure applies once it is confirmed.
	 * @throws ApiException {@code STALE STATE_CHANGED}; {@code CONFLICT NO_CLOSURE_OPEN} or
	 * {@code CLOSURE_ANSWERED}; the cascade's refusals, {@code GUARD_FAILED MILESTONE_HELD} among them
	 */
	@Transactional
	Proposal confirm(UUID jobId, UUID account, Party party, long version) {
		Job job = locked(jobId, version);
		Proposal proposal = this.proposals.open(jobId)
			.orElseThrow(() -> new ApiException(ErrorCode.CONFLICT, JobReason.NO_CLOSURE_OPEN));
		if (proposal.confirmedBy(party)) {
			throw new ApiException(ErrorCode.CONFLICT, JobReason.CLOSURE_ANSWERED);
		}
		this.jdbc.sql("UPDATE job.closure_proposal SET " + (party == Party.CUSTOMER ? "customer" : "mentor")
				+ "_confirmed_at = :now WHERE id = :id")
			.param("id", proposal.id())
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
		this.todos.close(ClosureProposals.ANSWER, account, proposal.id());
		audit(account, "job.closure_confirmed", proposal.id(), jobId);
		Proposal now = this.proposals.find(proposal.id()).orElseThrow();
		if (now.customerConfirmedAt() != null && now.mentorConfirmedAt() != null) {
			close(job, now, Trigger.USER);
		}
		return this.proposals.find(proposal.id()).orElseThrow();
	}

	/**
	 * Declines the open proposal; the Job stays Active.
	 * @throws ApiException {@code STALE STATE_CHANGED}; {@code CONFLICT NO_CLOSURE_OPEN}
	 */
	@Transactional
	Proposal decline(UUID jobId, UUID account, Party party, long version) {
		Job job = locked(jobId, version);
		Proposal proposal = this.proposals.open(jobId)
			.orElseThrow(() -> new ApiException(ErrorCode.CONFLICT, JobReason.NO_CLOSURE_OPEN));
		if (!proposal.automatic() && account.equals(proposal.proposerId())) {
			throw new ApiException(ErrorCode.CONFLICT, JobReason.CLOSURE_ANSWERED);
		}
		this.jdbc.sql("""
				UPDATE job.closure_proposal SET status = 'DECLINED', declined_by = :by, decided_at = :now WHERE id = :id
				""")
			.param("id", proposal.id())
			.param("by", account)
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
		this.todos.closeAll(ClosureProposals.ANSWER, proposal.id());
		Party other = party == Party.CUSTOMER ? Party.MENTOR : Party.CUSTOMER;
		this.notifications.send(new NotificationRequest(DECLINED, proposal.id(), List.of(ClosureProposals.recipient(job, other)),
				Map.of("job", job.title()), "/cong-viec/" + job.id(), job.id(), null));
		audit(account, "job.closure_declined", proposal.id(), jobId);
		return this.proposals.find(proposal.id()).orElseThrow();
	}

	/**
	 * The System's proposal confirmed by its Deadline (FR-67).
	 * @return whether the closure applied; nothing is written otherwise
	 */
	@Transactional
	boolean autoConfirm(UUID proposalId) {
		Proposal proposal = this.proposals.find(proposalId).filter(found -> "OPEN".equals(found.status())).orElse(null);
		if (proposal == null || !proposal.automatic()) {
			return false;
		}
		Job job = this.jobs.lock(proposal.jobId()).orElse(null);
		if (job == null || this.lifecycle.refusal(job.id(), Trigger.AUTO, cascade(job, Trigger.AUTO)) != null) {
			return false;
		}
		close(job, proposal, Trigger.AUTO);
		return true;
	}

	/** Whether a Milestone of the Job is held: Funded, Revision Requested, or Submitted (FR-37). */
	static boolean held(JdbcClient jdbc, UUID jobId) {
		return jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM job.milestone WHERE job_id = :job AND removed_at IS NULL
				    AND state IN ('FUNDED', 'REVISION_REQUESTED', 'SUBMITTED'))
				""").param("job", jobId).query(Boolean.class).single();
	}

	private void close(Job job, Proposal proposal, Trigger trigger) {
		List<Planned> cascade = cascade(job, trigger);
		ApiException refusal = this.lifecycle.refusal(job.id(), trigger, cascade);
		if (refusal != null) {
			throw refusal;
		}
		this.lifecycle.tryApplyCascade(job.id(), trigger, cascade)
			.orElseThrow(() -> new IllegalStateException("The closure of " + job.id() + " passed its checks but did not apply"));
		this.jdbc.sql("UPDATE job.closure_proposal SET status = 'CONFIRMED', decided_at = :now WHERE id = :id")
			.param("id", proposal.id())
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
		this.todos.closeAll(ClosureProposals.ANSWER, proposal.id());
	}

	/** J8, M6 for every Planned Milestone, and J9 for an Assessment Job. */
	private List<Planned> cascade(Job job, Trigger trigger) {
		List<Planned> cascade = new ArrayList<>(List.of(Planned.job(Transition.J8, trigger)));
		this.jdbc.sql("""
				SELECT id FROM job.milestone WHERE job_id = :job AND removed_at IS NULL AND state = 'PLANNED'
				ORDER BY position, created_at, id
				""")
			.param("job", job.id())
			.query(UUID.class)
			.list()
			.forEach(milestone -> cascade.add(Planned.milestone(Transition.M6, milestone, Map.of(), Trigger.SYSTEM)));
		if (!job.code()) {
			cascade.add(Planned.job(Transition.J9, Trigger.SYSTEM));
		}
		return cascade;
	}

	private Job locked(UUID jobId, long version) {
		Job job = this.jobs.lock(jobId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (job.version() != version) {
			throw new ApiException(ErrorCode.STALE, JobReason.STATE_CHANGED);
		}
		return job;
	}

	private void audit(@Nullable UUID account, String action, UUID proposalId, UUID jobId) {
		this.auditLog.record(AuditEntry.of(account == null ? AuditActor.system() : AuditActor.user(account), action,
				AuditObject.of("job.closure_proposal", proposalId)).forJob(jobId));
	}

}
