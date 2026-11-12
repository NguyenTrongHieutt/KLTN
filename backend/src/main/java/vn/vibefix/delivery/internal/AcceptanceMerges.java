package vn.vibefix.delivery.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.delivery.internal.Decisions.Check;
import vn.vibefix.delivery.internal.Handovers.CodeRecord;
import vn.vibefix.delivery.internal.Handovers.Handover;
import vn.vibefix.identity.Administrators;
import vn.vibefix.job.JobBusy;
import vn.vibefix.job.JobBusy.BusyJob;
import vn.vibefix.job.MilestoneHandovers;
import vn.vibefix.job.MilestoneHandovers.HandoverJob;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.operations.ExternalOperations;
import vn.vibefix.operations.OperationCompleted;
import vn.vibefix.operations.OperationRequest;
import vn.vibefix.operations.OperationStatus;
import vn.vibefix.operations.OperationTicket;
import vn.vibefix.repo.HandoverBranches;
import vn.vibefix.repo.HandoverBranches.Unavailable;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.IdGenerator;
import vn.vibefix.timers.DeadlineOutcome;

/**
 * A Code Job's acceptance (FR-33, FR-35, AD-5, AD-28): effect first, transition second. "Nghiệm thu giai
 * đoạn" (or Auto-acceptance) reserves the {@code ACCEPTANCE} busy marker for the handover and requests
 * {@value #TYPE} in one transaction; nothing is accepted yet. The operation merges exactly the Handover
 * Commit, and its resolving transaction releases the marker, then applies M5 with the release through
 * {@link Decisions}. GitHub's 409 at that merge (the handover branch moved, D9) resolves as M3 instead, with
 * {@value #HEAD_MOVED} to every Administrator. A failure after the last attempt releases the marker and leaves
 * the Milestone Submitted; the daily reconciliation reads failed merges back and resolves any it finds merged
 * (SG-29).
 */
@Component
class AcceptanceMerges {

	static final String TYPE = "delivery.acceptance_merge";

	/** The Administrators' alert when an acceptance merge answered 409 and M3 applied (FR-33, R25, R30). */
	static final String HEAD_MOVED = "repo.acceptance_head_moved";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final Logger log = LoggerFactory.getLogger(AcceptanceMerges.class);

	/** One acceptance waiting for its merge, or how it ended. */
	record Acceptance(String operationKey, UUID handoverId, UUID jobId, @Nullable UUID customerId, boolean auto,
			List<Check> checks, String status) {
	}

	private final JdbcClient jdbc;

	private final JobBusy busy;

	private final ExternalOperations operations;

	private final MilestoneHandovers jobs;

	private final HandoverBranches branches;

	private final Handovers handovers;

	private final AuditLog auditLog;

	private final Notifications notifications;

	private final Administrators administrators;

	private final IdGenerator ids;

	private final Clock clock;

	/** Looked up on use: {@link Decisions} requests merges here. */
	private final ObjectProvider<Decisions> decisions;

	AcceptanceMerges(JdbcClient jdbc, JobBusy busy, ExternalOperations operations, MilestoneHandovers jobs,
			HandoverBranches branches, Handovers handovers, AuditLog auditLog, Notifications notifications,
			Administrators administrators, IdGenerator ids, Clock clock, ObjectProvider<Decisions> decisions) {
		this.jdbc = jdbc;
		this.busy = busy;
		this.operations = operations;
		this.jobs = jobs;
		this.branches = branches;
		this.handovers = handovers;
		this.auditLog = auditLog;
		this.notifications = notifications;
		this.administrators = administrators;
		this.ids = ids;
		this.clock = clock;
		this.decisions = decisions;
	}

	/**
	 * Reserves {@code ACCEPTANCE} on {@code version} and requests the merge, in the caller's transaction.
	 * @throws ApiException {@code JOB_BUSY}; {@code STALE}; {@code CONFLICT JOB_FROZEN}
	 */
	void request(Handover handover, CodeRecord code, @Nullable UUID customerId, long version, List<Check> checks,
			boolean auto) {
		OperationTicket ticket = this.operations.request(new OperationRequest(TYPE, this.ids.newId(),
				Map.of("jobId", handover.jobId().toString(), "handoverId", handover.id().toString(), "commit", code.commit(),
						"pull", code.pullNumber(), "number", handover.number()),
				null, null, 0));
		String key = ticket.key();
		this.busy.reserve(handover.jobId(), JobBusy.Purpose.ACCEPTANCE, handover.id().toString(), version, key);
		this.jdbc.sql("""
				INSERT INTO delivery.handover_acceptance (operation_key, handover_id, job_id, product_id, customer_account_id, auto,
				    checks, status, requested_at)
				VALUES (:key, :handover, :job, :product, :customer, :auto, CAST(:checks AS JSONB), 'MERGING', :now)
				""")
			.param("key", key)
			.param("handover", handover.id())
			.param("job", handover.jobId())
			.param("product", handover.productId())
			.param("customer", customerId)
			.param("auto", auto)
			.param("checks", JSON.writeValueAsString(checks))
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
		AuditActor actor = customerId == null ? AuditActor.system() : AuditActor.user(customerId);
		this.auditLog.record(AuditEntry.of(actor, "delivery.acceptance_requested", AuditObject.of("delivery.handover",
				handover.id())).forJob(handover.jobId()));
	}

	/**
	 * Auto-acceptance of a Code Job (FR-35): the same merge, by the System. A busy Job re-arms the
	 * Deadline, and nothing is reserved then. Only {@link AutoAcceptance} calls it, in the Deadline's
	 * transaction, with the Job row locked and not frozen, so {@code reserve} checks what this read.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	DeadlineOutcome requestAutomatically(Handover handover) {
		CodeRecord code = this.handovers.code(handover.id()).orElse(null);
		BusyJob job = this.busy.job(handover.jobId()).orElse(null);
		if (code == null || job == null || !"ACTIVE".equals(job.state())) {
			return DeadlineOutcome.OBSOLETE;
		}
		if (job.busy().isPresent()) {
			return DeadlineOutcome.REARM;
		}
		request(handover, code, null, job.version(), List.of(), true);
		return DeadlineOutcome.FIRED;
	}

	/** The acceptance a merge operation serves. */
	@Transactional(readOnly = true)
	Optional<Acceptance> find(String operationKey) {
		return this.jdbc.sql("""
				SELECT operation_key, handover_id, job_id, customer_account_id, auto, checks::text AS checks, status
				FROM delivery.handover_acceptance WHERE operation_key = :key
				""").param("key", operationKey).query((rs, n) -> new Acceptance(rs.getString("operation_key"),
						rs.getObject("handover_id", UUID.class), rs.getObject("job_id", UUID.class),
						rs.getObject("customer_account_id", UUID.class), rs.getBoolean("auto"),
						JSON.readValue(rs.getString("checks"), new TypeReference<List<Check>>() {
						}), rs.getString("status")))
			.optional();
	}

	/** The latest acceptance of the handover, for its Delivery Report. */
	@Transactional(readOnly = true)
	Optional<String> latestStatus(UUID handoverId) {
		return this.jdbc.sql("""
				SELECT status FROM delivery.handover_acceptance WHERE handover_id = :id ORDER BY seq DESC LIMIT 1
				""").param("id", handoverId).query(String.class).optional();
	}

	/**
	 * The resolving transaction of a verified merge: the marker goes, then M5 with the release and the
	 * decision. An M5 that refuses leaves the acceptance failed, for the reconciliation to try again.
	 * Resolving twice changes nothing.
	 */
	@Transactional
	void merged(String operationKey) {
		Acceptance acceptance = merging(operationKey).orElse(null);
		if (acceptance == null) {
			return;
		}
		this.busy.release(acceptance.jobId(), operationKey);
		resolve(operationKey, accept(acceptance) ? "DONE" : "FAILED");
	}

	/**
	 * The merge answered 409 (a precondition violation, FR-33): in one resolving transaction the marker goes,
	 * M3 voids the handover as the System, the acceptance is {@code VOIDED}, and every Administrator gets
	 * {@value #HEAD_MOVED}. M3 refused rolls all of it back, so the operation merges again later: it waits while
	 * the Job is frozen, which is how M3 is refused under the marker. Resolving twice changes nothing.
	 * @return {@code false} when M3 was refused and nothing was written
	 */
	@Transactional
	boolean headMoved(String operationKey) {
		Acceptance acceptance = merging(operationKey).orElse(null);
		if (acceptance == null) {
			return true;
		}
		this.busy.release(acceptance.jobId(), operationKey);
		Handover handover = this.handovers.find(acceptance.handoverId()).orElseThrow();
		if (!this.jobs.voidHandover(handover.jobId(), handover.milestoneId(), handover.id())) {
			TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
			log.info("Acceptance {} met a moved handover branch but M3 was refused; nothing was resolved", operationKey);
			return false;
		}
		resolve(operationKey, "VOIDED");
		List<Recipient> admins = this.administrators.activeIds().stream().map(id -> new Recipient(id, Audience.ADMIN)).toList();
		if (!admins.isEmpty()) {
			String job = this.jobs.job(handover.jobId()).map(HandoverJob::title).orElse("");
			// One alert per handover: a handover is voided once.
			this.notifications.send(new NotificationRequest(HEAD_MOVED, handover.id(), admins,
					Map.of("n", String.valueOf(handover.number()), "job", job), "/quan-tri/cong-viec/" + handover.jobId(), null,
					null));
		}
		return true;
	}

	@ApplicationModuleListener
	void onOperationCompleted(OperationCompleted completed) {
		completed(completed);
	}

	/**
	 * The operation ended. A failure after the last attempt releases the marker and leaves the Milestone
	 * Submitted; a success that ran no resolution (after a read-back) is resolved here.
	 */
	@Transactional
	void completed(OperationCompleted completed) {
		if (!TYPE.equals(completed.type()) || merging(completed.key()).isEmpty()) {
			return;
		}
		if (completed.status() == OperationStatus.SUCCEEDED) {
			merged(completed.key());
			return;
		}
		Acceptance acceptance = merging(completed.key()).orElseThrow();
		this.busy.release(acceptance.jobId(), completed.key());
		resolve(completed.key(), "FAILED");
		this.auditLog.record(AuditEntry.of(AuditActor.system(), "delivery.acceptance_merge_failed",
				AuditObject.of("delivery.handover", acceptance.handoverId())).forJob(acceptance.jobId()));
	}

	/** The daily read-back of failed merges (SG-29): one GitHub shows merged is accepted now. */
	@Transactional
	void reconcile() {
		for (Acceptance acceptance : this.jdbc.sql("""
				SELECT a.operation_key FROM delivery.handover_acceptance a JOIN delivery.handover h ON h.id = a.handover_id
				WHERE a.status = 'FAILED' AND h.status = 'SUBMITTED'
				""").query(String.class).list().stream().map(this::find).flatMap(Optional::stream).toList()) {
			CodeRecord code = this.handovers.code(acceptance.handoverId()).orElse(null);
			try {
				if (code == null || !this.branches.merged(acceptance.jobId(), code.pullNumber())) {
					continue;
				}
			}
			catch (Unavailable unavailable) {
				log.warn("Failed merge {} was not read back: {}", acceptance.operationKey(), unavailable.getMessage());
				continue;
			}
			if (!accept(acceptance)) {
				continue;
			}
			resolve(acceptance.operationKey(), "DONE");
			this.auditLog.record(AuditEntry.of(AuditActor.system(), "delivery.acceptance_reconciled",
					AuditObject.of("delivery.handover", acceptance.handoverId())).forJob(acceptance.jobId()));
		}
	}

	private boolean accept(Acceptance acceptance) {
		Handover handover = this.handovers.find(acceptance.handoverId()).orElseThrow();
		return this.decisions.getObject().acceptMerged(handover, acceptance.customerId(), acceptance.checks(), acceptance.auto());
	}

	private Optional<Acceptance> merging(String operationKey) {
		return find(operationKey).filter(acceptance -> "MERGING".equals(acceptance.status()));
	}

	private void resolve(String operationKey, String status) {
		this.jdbc.sql("""
				UPDATE delivery.handover_acceptance SET status = :status, resolved_at = :now WHERE operation_key = :key
				""").param("status", status).param("now", Timestamp.from(this.clock.instant())).param("key", operationKey).update();
	}

	@Component
	@ConditionalOnBooleanProperty(name = "vibefix.scheduling.enabled", matchIfMissing = true)
	static class Reconciliation {

		private final AcceptanceMerges merges;

		Reconciliation(AcceptanceMerges merges) {
			this.merges = merges;
		}

		@Scheduled(cron = "${vibefix.delivery.reconcile-cron:0 30 4 * * *}", zone = "Asia/Ho_Chi_Minh")
		void run() {
			this.merges.reconcile();
		}

	}

}
