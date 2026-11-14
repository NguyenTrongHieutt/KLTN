package vn.vibefix.repo.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.github.ActionsRuns;
import vn.vibefix.operations.ExternalOperations;
import vn.vibefix.operations.OperationCompleted;
import vn.vibefix.operations.OperationRequest;
import vn.vibefix.operations.OperationStatus;
import vn.vibefix.operations.OperationTicket;
import vn.vibefix.repo.HandoverCopies;
import vn.vibefix.repo.HandoverCopyFinished;
import vn.vibefix.shared.IdGenerator;

/**
 * {@link HandoverCopies} on {@code repo.handover_copy} (FR-32, AD-11, AD-44, AD-46, Story 23.8a): "Gửi bàn giao"
 * requests {@value #TYPE} in the transaction that keeps the pending submission; no handover exists yet, and no busy
 * marker is held, so the team keeps working while the runner copies. {@link HandoverCopyOperation} dispatches the
 * runner's copy, polls it, reads its path report and the branch back, and opens the System's pull request; its
 * resolving transaction publishes {@link HandoverCopyFinished}, which {@code delivery} turns into M2 or into the
 * submission's refusal. A copy whose operation ends without resolving (the attempts ran out, or the Job moved on)
 * is finished {@link HandoverCopies.Refusal#RUN_FAILED} here, so no submission waits for ever.
 */
@Component
class JobHandoverCopies implements HandoverCopies {

	static final String TYPE = "repo.handover_copy";

	private static final String COLUMNS = """
			operation_key, submission_id, job_id, from_sha, handover_commit, pull_title, pull_body, status, refusal,
			correlation_id, dispatched_at, run_status, run_conclusion, pull_number
			""";

	/** One copy, as its row holds it. */
	record Copy(String operationKey, UUID submissionId, UUID jobId, String fromSha, String handoverCommit,
			String pullTitle, String pullBody, String status, @Nullable Refusal refusal, @Nullable UUID correlationId,
			@Nullable Instant dispatchedAt, @Nullable String runStatus, @Nullable String runConclusion,
			@Nullable Integer pullNumber) {

		/** The branch the run pushes, which becomes the handover's own. */
		String branch() {
			return HandoverCopies.branch(this.submissionId);
		}

		/** Whether the run dispatched last is over without a copy: a failure, or vibefix stopped waiting for it. */
		boolean runSpent() {
			return this.runConclusion != null && !"success".equals(this.runConclusion);
		}

	}

	private final JdbcClient jdbc;

	private final ExternalOperations operations;

	private final ActionsRuns runs;

	private final AuditLog auditLog;

	private final ApplicationEventPublisher events;

	private final IdGenerator ids;

	private final Clock clock;

	JobHandoverCopies(JdbcClient jdbc, ExternalOperations operations, ActionsRuns runs, AuditLog auditLog,
			ApplicationEventPublisher events, IdGenerator ids, Clock clock) {
		this.jdbc = jdbc;
		this.operations = operations;
		this.runs = runs;
		this.auditLog = auditLog;
		this.events = events;
		this.ids = ids;
		this.clock = clock;
	}

	@Override
	public void start(Request request) {
		OperationTicket ticket = this.operations.request(new OperationRequest(TYPE, request.submissionId(),
				Map.of("submissionId", request.submissionId().toString(), "jobId", request.jobId().toString()), null, null, 0));
		this.jdbc.sql("""
				INSERT INTO repo.handover_copy (operation_key, submission_id, job_id, from_sha, handover_commit, pull_title,
				    pull_body, status, requested_at)
				VALUES (:key, :submission, :job, :from, :commit, :title, :body, 'RUNNING', :now)
				ON CONFLICT (operation_key) DO NOTHING
				""")
			.param("key", ticket.key())
			.param("submission", request.submissionId())
			.param("job", request.jobId())
			.param("from", request.fromSha())
			.param("commit", request.handoverCommit())
			.param("title", request.title())
			.param("body", request.body())
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
		this.auditLog.record(AuditEntry
			.of(AuditActor.system(), "repo.handover_copy_requested", AuditObject.of("repo.handover_copy", request.submissionId()))
			.forJob(request.jobId()));
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<Result> result(UUID submissionId) {
		return bySubmission(submissionId).filter(copy -> !"RUNNING".equals(copy.status())).map(copy -> {
			boolean copied = "DONE".equals(copy.status());
			ActionsRuns.Report report = copied && copy.correlationId() != null
					? this.runs.report(copy.correlationId()).orElse(null) : null;
			return new Result(copy.submissionId(), copy.jobId(), copied, copy.refusal(), copy.pullNumber(),
					report == null ? null : report.paths(), report == null ? 0 : report.count());
		});
	}

	/** The copy one operation serves. */
	@Transactional(readOnly = true)
	Optional<Copy> find(String operationKey) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM repo.handover_copy WHERE operation_key = :key")
			.param("key", operationKey)
			.query(JobHandoverCopies::copy)
			.optional();
	}

	@Transactional(readOnly = true)
	Optional<Copy> bySubmission(UUID submissionId) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM repo.handover_copy WHERE submission_id = :id")
			.param("id", submissionId)
			.query(JobHandoverCopies::copy)
			.optional();
	}

	/** The run dispatched on the runner under {@code correlation} (AD-44, AD-46). */
	@Transactional
	boolean dispatched(Copy copy, UUID correlation) {
		int updated = this.jdbc.sql("""
				UPDATE repo.handover_copy SET correlation_id = :correlation, dispatched_at = :now, run_status = NULL,
				    run_conclusion = NULL
				WHERE operation_key = :key AND status = 'RUNNING'
				""")
			.param("correlation", correlation)
			.param("now", Timestamp.from(this.clock.instant()))
			.param("key", copy.operationKey())
			.update();
		if (updated == 1) {
			this.auditLog.record(AuditEntry
				.of(AuditActor.system(), "repo.handover_copy_dispatched",
						AuditObject.of("repo.handover_copy", copy.submissionId()))
				.forJob(copy.jobId()));
		}
		return updated == 1;
	}

	/** The run as last read: its status, and its conclusion once it completed or vibefix stopped waiting for it. */
	@Transactional
	void runRead(Copy copy, @Nullable String status, @Nullable String conclusion) {
		this.jdbc.sql("""
				UPDATE repo.handover_copy SET run_status = :status, run_conclusion = :conclusion
				WHERE operation_key = :key AND status = 'RUNNING' AND correlation_id = :correlation
				""")
			.param("status", status)
			.param("conclusion", conclusion)
			.param("key", copy.operationKey())
			.param("correlation", copy.correlationId())
			.update();
	}

	/** The System's pull request, opened once the branch read back at the Handover Commit. */
	@Transactional
	void pullOpened(Copy copy, int number) {
		this.jdbc.sql("UPDATE repo.handover_copy SET pull_number = :pull WHERE operation_key = :key AND status = 'RUNNING'")
			.param("pull", number)
			.param("key", copy.operationKey())
			.update();
	}

	/**
	 * The resolving transaction: the copy ends and {@code delivery} is told. Resolving twice changes nothing.
	 * @param refusal {@code null} for a copy that pushed and opened its pull request
	 */
	@Transactional
	void finish(String operationKey, @Nullable Refusal refusal) {
		Copy copy = find(operationKey).filter(found -> "RUNNING".equals(found.status())).orElse(null);
		if (copy == null) {
			return;
		}
		this.jdbc.sql("""
				UPDATE repo.handover_copy SET status = :status, refusal = :refusal, finished_at = :now
				WHERE operation_key = :key AND status = 'RUNNING'
				""")
			.param("status", refusal == null ? "DONE" : "REFUSED")
			.param("refusal", refusal == null ? null : refusal.name())
			.param("now", Timestamp.from(this.clock.instant()))
			.param("key", operationKey)
			.update();
		this.auditLog.record(AuditEntry
			.of(AuditActor.system(), refusal == null ? "repo.handover_copied" : "repo.handover_copy_refused",
					AuditObject.of("repo.handover_copy", copy.submissionId()))
			.forJob(copy.jobId()));
		this.events.publishEvent(new HandoverCopyFinished(this.ids.newId(), copy.jobId(), copy.submissionId(),
				refusal == null, refusal));
	}

	/** A copy whose operation ended without resolving: the submission hears a named reason. */
	@ApplicationModuleListener
	void onOperationCompleted(OperationCompleted completed) {
		if (!TYPE.equals(completed.type()) || completed.status() == OperationStatus.SUCCEEDED) {
			return;
		}
		finish(completed.key(), Refusal.RUN_FAILED);
	}

	private static Copy copy(ResultSet rs, int n) throws SQLException {
		String refusal = rs.getString("refusal");
		Timestamp dispatched = rs.getTimestamp("dispatched_at");
		return new Copy(rs.getString("operation_key"), rs.getObject("submission_id", UUID.class),
				rs.getObject("job_id", UUID.class), rs.getString("from_sha"), rs.getString("handover_commit"),
				rs.getString("pull_title"), rs.getString("pull_body"), rs.getString("status"),
				refusal == null ? null : Refusal.valueOf(refusal), rs.getObject("correlation_id", UUID.class),
				dispatched == null ? null : dispatched.toInstant(), rs.getString("run_status"), rs.getString("run_conclusion"),
				rs.getObject("pull_number", Integer.class));
	}

}
