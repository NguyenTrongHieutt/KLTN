package vn.vibefix.repo.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
import vn.vibefix.identity.Administrators;
import vn.vibefix.job.JobBusy;
import vn.vibefix.job.JobBusy.BusyJob;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
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
 * <p>
 * A copy the runner's run lost ({@link HandoverCopies.Refusal#RUN_FAILED},
 * {@link HandoverCopies.Refusal#EXCHANGE_REFUSED} or {@link HandoverCopies.Refusal#PATH_LIST_UNREADABLE}) also
 * alerts every Administrator once, {@value #RUN_FAILED_ALERT}, with the run's page on GitHub (R30, AD-46, Story
 * 23.8b): a run on the runner is linked for an Administrator alone. A handover refused
 * {@link HandoverCopies.Refusal#WORKFLOW_FILES_CHANGED} is the Mentor's own to fix (N-16) and alerts nobody.
 */
@Component
class JobHandoverCopies implements HandoverCopies {

	static final String TYPE = "repo.handover_copy";

	/** The alert every Administrator gets when a copy run failed or timed out (AD-46, R30, Story 23.8b). */
	static final String RUN_FAILED_ALERT = "github.runner_run_failed";

	/**
	 * What the alert says instead of a Milestone's number for a copy of Story 23.8a, which recorded none
	 * ({@code V202611141610} leaves those rows at 0).
	 */
	static final String UNKNOWN_MILESTONE = "chưa rõ";

	/** The refusals that are the runner's run going wrong; N-16's workflow change is the handover's own content. */
	private static final Set<Refusal> ALERTED = Set.of(Refusal.RUN_FAILED, Refusal.EXCHANGE_REFUSED,
			Refusal.PATH_LIST_UNREADABLE);

	private static final String COLUMNS = """
			operation_key, submission_id, job_id, milestone_number, from_sha, handover_commit, pull_title, pull_body,
			status, refusal, correlation_id, dispatched_at, run_status, run_conclusion, pull_number
			""";

	/** One copy, as its row holds it. */
	record Copy(String operationKey, UUID submissionId, UUID jobId, int milestoneNumber, String fromSha,
			String handoverCommit, String pullTitle, String pullBody, String status, @Nullable Refusal refusal,
			@Nullable UUID correlationId, @Nullable Instant dispatchedAt, @Nullable String runStatus,
			@Nullable String runConclusion, @Nullable Integer pullNumber) {

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

	private final JobBusy busy;

	private final Administrators administrators;

	private final Notifications notifications;

	private final IdGenerator ids;

	private final Clock clock;

	JobHandoverCopies(JdbcClient jdbc, ExternalOperations operations, ActionsRuns runs, AuditLog auditLog,
			ApplicationEventPublisher events, JobBusy busy, Administrators administrators, Notifications notifications,
			IdGenerator ids, Clock clock) {
		this.jdbc = jdbc;
		this.operations = operations;
		this.runs = runs;
		this.auditLog = auditLog;
		this.events = events;
		this.busy = busy;
		this.administrators = administrators;
		this.notifications = notifications;
		this.ids = ids;
		this.clock = clock;
	}

	@Override
	public void start(Request request) {
		OperationTicket ticket = this.operations.request(new OperationRequest(TYPE, request.submissionId(),
				Map.of("submissionId", request.submissionId().toString(), "jobId", request.jobId().toString()), null, null, 0));
		this.jdbc.sql("""
				INSERT INTO repo.handover_copy (operation_key, submission_id, job_id, milestone_number, from_sha,
				    handover_commit, pull_title, pull_body, status, requested_at)
				VALUES (:key, :submission, :job, :milestone, :from, :commit, :title, :body, 'RUNNING', :now)
				ON CONFLICT (operation_key) DO NOTHING
				""")
			.param("key", ticket.key())
			.param("submission", request.submissionId())
			.param("job", request.jobId())
			.param("milestone", request.milestoneNumber())
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

	@Override
	@Transactional(readOnly = true)
	public Optional<RunState> runState(UUID submissionId) {
		return bySubmission(submissionId).map(copy -> new RunState(copy.runStatus(), copy.runConclusion()));
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
		if (refusal != null && ALERTED.contains(refusal)) {
			alertAdministrators(copy, refusal);
		}
	}

	/**
	 * R30, AD-46: a copy run that failed, timed out, was refused a token, or brought back no readable path list
	 * tells every Administrator once, in the transaction that ended the copy, and names the run's page on GitHub --
	 * the only place a runner run is ever linked (EXPERIENCE.md's Actions run status row). A run GitHub never showed
	 * is named by its correlation instead, which the Audit Log holds.
	 */
	private void alertAdministrators(Copy copy, Refusal refusal) {
		List<Recipient> admins = this.administrators.activeIds()
			.stream()
			.map(admin -> new Recipient(admin, Audience.ADMIN))
			.toList();
		if (admins.isEmpty()) {
			return;
		}
		String job = this.busy.job(copy.jobId()).map(BusyJob::title).orElse(copy.jobId().toString());
		// V202611141610: a copy of Story 23.8a kept no Milestone, and its 0 reads as unknown, never as "Giai đoạn 0".
		String milestone = copy.milestoneNumber() > 0 ? String.valueOf(copy.milestoneNumber()) : UNKNOWN_MILESTONE;
		this.notifications.send(new NotificationRequest(RUN_FAILED_ALERT, copy.submissionId(), admins,
				Map.of("job", job, "n", milestone, "run", run(copy), "reason", refusal.name()), "/quan-tri", copy.jobId(),
				null));
	}

	/** The run's page on GitHub, or its correlation when GitHub showed none; "-" before anything was dispatched. */
	private String run(Copy copy) {
		UUID correlation = copy.correlationId();
		if (correlation == null) {
			return "-";
		}
		return this.runs.correlation(correlation)
			.map(found -> found.runHtmlUrl() == null ? correlation.toString() : found.runHtmlUrl())
			.orElseGet(correlation::toString);
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
				rs.getObject("job_id", UUID.class), rs.getInt("milestone_number"), rs.getString("from_sha"),
				rs.getString("handover_commit"), rs.getString("pull_title"), rs.getString("pull_body"), rs.getString("status"),
				refusal == null ? null : Refusal.valueOf(refusal), rs.getObject("correlation_id", UUID.class),
				dispatched == null ? null : dispatched.toInstant(), rs.getString("run_status"), rs.getString("run_conclusion"),
				rs.getObject("pull_number", Integer.class));
	}

}
