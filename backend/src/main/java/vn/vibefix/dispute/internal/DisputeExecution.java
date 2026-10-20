package vn.vibefix.dispute.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.dispute.internal.DisputeDecisions.Decision;
import vn.vibefix.dispute.internal.DisputeDecisions.Execution;
import vn.vibefix.dispute.internal.Disputes.Dispute;
import vn.vibefix.identity.Administrators;
import vn.vibefix.job.JobFreezes;
import vn.vibefix.job.JobFreezes.DisputedJob;
import vn.vibefix.job.MilestoneSettlements;
import vn.vibefix.job.MilestoneSettlements.Next;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.payment.Releases;
import vn.vibefix.timers.Deadlines;

/**
 * A decision's persisted execution (AD-31): (1) the merge, which no Job reaches yet; (2) M8 with the
 * release and the refund in one transaction, refused unless they sum to what is releasable; (3) the
 * decided next state; (4) only then the unfreeze of the Job and its Deadlines. Each step commits on its
 * own and is recorded; a failing step makes it {@code EXECUTION_FAILED}, keeps the Job frozen, and tells
 * every Administrator; a retry runs the steps left. The last step decides the Dispute and tells both sides.
 */
@Component
class DisputeExecution {

	static final String FAILED = "dispute.execution_failed";

	static final String DECIDED = "dispute.dispute_decided";

	private final JdbcClient jdbc;

	private final Disputes disputes;

	private final DisputeDecisions decisions;

	private final MilestoneSettlements settlements;

	private final Releases releases;

	private final JobFreezes freezes;

	private final Deadlines deadlines;

	private final Notifications notifications;

	private final Administrators administrators;

	private final AuditLog auditLog;

	private final TransactionTemplate transactions;

	private final Clock clock;

	DisputeExecution(JdbcClient jdbc, Disputes disputes, DisputeDecisions decisions, MilestoneSettlements settlements,
			Releases releases, JobFreezes freezes, Deadlines deadlines, Notifications notifications,
			Administrators administrators, AuditLog auditLog, PlatformTransactionManager transactionManager, Clock clock) {
		this.jdbc = jdbc;
		this.disputes = disputes;
		this.decisions = decisions;
		this.settlements = settlements;
		this.releases = releases;
		this.freezes = freezes;
		this.deadlines = deadlines;
		this.notifications = notifications;
		this.administrators = administrators;
		this.auditLog = auditLog;
		this.transactions = new TransactionTemplate(transactionManager);
		this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.clock = clock;
	}

	/** Runs the steps left, each in its own transaction; returns the execution as it ends. */
	Execution run(UUID disputeId) {
		Dispute dispute = this.disputes.find(disputeId).orElseThrow();
		Decision decision = this.decisions.decision(disputeId).orElseThrow();
		Execution execution = this.decisions.execution(disputeId).orElseThrow();
		for (int step = execution.step() + 1; step <= 4; step++) {
			int current = step;
			try {
				this.transactions.executeWithoutResult(status -> {
					step(current, dispute, decision);
					record(disputeId, current, current == 4 ? "DONE" : "RUNNING", null);
				});
			}
			catch (RuntimeException failure) {
				String reason = failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage());
				this.transactions.executeWithoutResult(status -> failed(dispute, current - 1, reason));
				break;
			}
		}
		return this.decisions.execution(disputeId).orElseThrow();
	}

	private void step(int step, Dispute dispute, Decision decision) {
		switch (step) {
			case 1 -> {
				// The merge: Epic 8 brings Handover Commits, so no decision merges yet.
			}
			case 2 -> {
				if (decision.milestoneId() != null) {
					UUID cause = this.settlements.settle(dispute.jobId(), decision.milestoneId(), decision.releaseVnd(),
							decision.refundVnd());
					this.releases.settle(dispute.jobId(), decision.milestoneId(), cause, decision.releaseVnd(),
							decision.refundVnd());
				}
			}
			case 3 -> this.settlements.next(dispute.jobId(), Next.valueOf(decision.nextState()));
			case 4 -> {
				this.freezes.unfreeze(dispute.jobId());
				this.deadlines.unfreeze(dispute.jobId());
				this.jdbc.sql("UPDATE dispute.dispute SET status = 'DECIDED', decided_at = :now WHERE id = :id")
					.param("id", dispute.id())
					.param("now", Timestamp.from(this.clock.instant()))
					.update();
				this.auditLog.record(AuditEntry.of(AuditActor.system(), "dispute.dispute_decided",
						AuditObject.of("dispute.dispute", dispute.id())).forJob(dispute.jobId()));
				DisputedJob job = this.freezes.lock(dispute.jobId()).orElseThrow();
				List<Recipient> sides = new ArrayList<>(List.of(new Recipient(job.customerId(), Audience.CUSTOMER)));
				if (job.mentorId() != null) {
					sides.add(new Recipient(job.mentorId(), Audience.MENTOR));
				}
				this.notifications.send(new NotificationRequest(DECIDED, dispute.id(), sides, Map.of("job", job.title()),
						"/cong-viec/" + dispute.jobId(), dispute.jobId(), null));
			}
			default -> throw new IllegalArgumentException("No step " + step);
		}
	}

	private void record(UUID disputeId, int step, String status, String failure) {
		this.jdbc.sql("UPDATE dispute.execution SET status = :status, step = :step, failure = :failure, updated_at = :now WHERE dispute_id = :id")
			.param("id", disputeId)
			.param("status", status)
			.param("step", step)
			.param("failure", failure)
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
	}

	private void failed(Dispute dispute, int done, String reason) {
		record(dispute.id(), done, "EXECUTION_FAILED", reason.length() > 1000 ? reason.substring(0, 1000) : reason);
		this.auditLog.record(AuditEntry.of(AuditActor.system(), FAILED, AuditObject.of("dispute.dispute", dispute.id()))
			.forJob(dispute.jobId()));
		List<Recipient> admins = this.administrators.activeIds().stream().map(id -> new Recipient(id, Audience.ADMIN)).toList();
		if (!admins.isEmpty()) {
			this.notifications.send(new NotificationRequest(FAILED, UUID.nameUUIDFromBytes((dispute.id() + ":" + done).getBytes()),
					admins, Map.of("step", String.valueOf(done + 1)), "/quan-tri/tranh-chap/" + dispute.id(), dispute.jobId(), null));
		}
	}

}
