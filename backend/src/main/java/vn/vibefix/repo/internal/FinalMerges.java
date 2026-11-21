package vn.vibefix.repo.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import vn.vibefix.github.ActionsRuns;
import vn.vibefix.job.JobBusy;
import vn.vibefix.job.JobBusy.BusyJob;
import vn.vibefix.job.JobCompletion;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.notification.TodoRequest;
import vn.vibefix.notification.Todos;
import vn.vibefix.operations.ExternalOperations;
import vn.vibefix.operations.OperationCompleted;
import vn.vibefix.operations.OperationRequest;
import vn.vibefix.operations.OperationStatus;
import vn.vibefix.operations.OperationTicket;
import vn.vibefix.repo.internal.FinalMergeAuthorizations.Authorization;
import vn.vibefix.scanning.SensitiveChanges.SensitiveChange;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.IdGenerator;
import vn.vibefix.timers.Deadlines;

/**
 * The Final Merge (FR-37, AD-5, AD-11, AD-44, AD-46, R1, Story 23.9a), one per Job. Once the Job is Closing and its
 * row {@code REQUESTED}, {@value #CHECK} reads the preconditions, the Job's Sensitive Changes and the two
 * {@code .github/workflows/} trees; a clean one is {@code READY} for the Customer's confirmation (N-18). Since Story
 * 24.5b the check is queued only once every Mentor and Crew grant reads back revoked, and a check that defers records
 * what it waits for and when it looks again, which the Customer's closing card names. The
 * confirmation reserves the {@code FINAL_MERGE} busy marker and requests {@value #MERGE}, which dispatches the
 * published {@code vibefix.yml} on the Customer's own GitHub Actions ({@code RUNNING}), waits for the run (its
 * completion or its timeout wakes it, Story 24.5a) and reads
 * {@code vibefix/final-{jobId}} back; its resolving transaction is {@code DONE}: the row is spent, the marker
 * released, J9 applied, and both sides get {@value #BRANCH_READY}. vibefix opens no pull request and writes nothing
 * in a Customer repository: the Customer merges the branch themselves on GitHub.
 * <p>
 * A refusal that is not transient ({@code REFUSED}, with its named reason) leaves the closing choice open (Story
 * 23.10c): the row stays {@code REQUESTED} with its FR-37 deadline, so the Job still ends -- by the Customer's own
 * code receipt, or as {@code UNCONFIRMED} at the deadline. Since Story 23.10a the Customer keeps the self-download
 * path beside a Final Merge that is out or that failed with its reason, until the branch reads back: a code receipt
 * that lands first releases this marker, so a run still out ends {@code Obsolete}.
 */
@Component
class FinalMerges {

	static final String CHECK = "repo.final_merge_check";

	static final String MERGE = "repo.final_merge";

	/** The branch the run pushes is read back, and both sides hear it (FR-48, UX-DR116). */
	static final String BRANCH_READY = "repo.final_branch_ready";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String COLUMNS = """
			authorization_id, job_id, product_id, status, intermediary_sha, source_branch, sensitive::text AS sensitive,
			workflows::text AS workflows, refusal, confirmed_at, operation_key, branch, correlation_id, dispatched_at,
			run_status, run_conclusion, merged_at, waiting_for, next_check_at
			""";

	/**
	 * What a {@code CHECKING} row waits for (Story 24.5b), which the Customer's closing card names: the team's
	 * revocation to read back, the Customer's fix of a Source block on CUS-4, or a dispute to end.
	 */
	enum Wait {

		REVOCATION, CUSTOMER, FROZEN

	}

	/**
	 * One Final Merge, as its row holds it.
	 * @param workflows the paths under {@code .github/workflows/} that differ between the Source Repository's default
	 * branch and the Intermediary's merge commit; a non-empty list is N-18's workflow-file state and blocks the run,
	 * because a run's {@code GITHUB_TOKEN} can never push those files (deferred-work 626)
	 * @param branch {@code vibefix/final-{jobId}}, once the run was dispatched for it
	 * @param mergedAt when vibefix read the branch back
	 * @param waitingFor what a {@code CHECKING} row's check last deferred for; it stays until the next attempt records
	 * another wait or clears it, through a retry after a GitHub error too, so a {@code nextCheckAt} already past means
	 * the check is due or retrying, and the Customer's view then shows no wait
	 * @param nextCheckAt when the check looks again; none for a revocation not yet read back, which requests the
	 * check itself
	 */
	record FinalMerge(UUID authorizationId, UUID jobId, UUID productId, String status, @Nullable String intermediarySha,
			@Nullable String sourceBranch, List<SensitiveChange> sensitive, List<String> workflows, @Nullable String refusal,
			@Nullable Instant confirmedAt, @Nullable String operationKey, @Nullable String branch,
			@Nullable UUID correlationId, @Nullable Instant dispatchedAt, @Nullable String runStatus,
			@Nullable String runConclusion, @Nullable Instant mergedAt, @Nullable Wait waitingFor,
			@Nullable Instant nextCheckAt) {

		/** Whether the run dispatched last is over without a branch: a failure, or vibefix stopped waiting for it. */
		boolean runSpent() {
			return this.runConclusion != null && !"success".equals(this.runConclusion);
		}

	}

	/** The one branch a Job's Final Merge run pushes, never forced and never onto an existing branch (AD-11). */
	static String branch(UUID jobId) {
		return "vibefix/final-" + jobId;
	}

	private final JdbcClient jdbc;

	private final FinalMergeAuthorizations authorizations;

	private final JobBusy busy;

	private final JobCompletion completion;

	private final ExternalOperations operations;

	private final ActionsRuns runs;

	private final Todos todos;

	private final Notifications notifications;

	private final IdGenerator ids;

	private final Clock clock;

	/** Story 24.5a: each run's {@value ActionsRunWakes#KEY}, scheduled with its dispatch. */
	private final Deadlines deadlines;

	FinalMerges(JdbcClient jdbc, FinalMergeAuthorizations authorizations, JobBusy busy, JobCompletion completion,
			ExternalOperations operations, ActionsRuns runs, Todos todos, Notifications notifications, IdGenerator ids,
			Clock clock, Deadlines deadlines) {
		this.deadlines = deadlines;
		this.jdbc = jdbc;
		this.authorizations = authorizations;
		this.busy = busy;
		this.completion = completion;
		this.operations = operations;
		this.runs = runs;
		this.todos = todos;
		this.notifications = notifications;
		this.ids = ids;
		this.clock = clock;
	}

	/**
	 * Starts the check once the Job is Closing (J8, and each Customer read of JOB-1): the row is {@code CHECKING},
	 * waiting on {@code REVOCATION}, and the check is requested at once when every team grant already reads back
	 * revoked; otherwise the read-back of the last one requests it ({@link #teamRevoked}). An existing row is queued
	 * again only from {@code REVOCATION}, so this read every few seconds never wakes or re-requests a check that
	 * waits for the Customer or for a dispute (Story 24.5b). It reads first without a lock and takes the lock only
	 * when the row is absent or waits on {@code REVOCATION}, so a read while the run is out never waits on a writer
	 * of the row, such as the branch read-back.
	 */
	@Transactional
	void prepare(UUID jobId) {
		Authorization open = this.authorizations.openOf(jobId).orElse(null);
		FinalMerge existing = open == null ? null : find(open.id()).orElse(null);
		if (open == null || (existing != null && existing.waitingFor() != Wait.REVOCATION)) {
			return;
		}
		Authorization authorization = this.authorizations.lockOpen(jobId).orElse(null);
		BusyJob job = this.busy.job(jobId).orElse(null);
		if (authorization == null || job == null || !"CLOSING".equals(job.state())) {
			return;
		}
		this.jdbc.sql("""
				INSERT INTO repo.final_merge (authorization_id, job_id, product_id, status, waiting_for, created_at)
				VALUES (:id, :job, :product, 'CHECKING', 'REVOCATION', :now)
				ON CONFLICT DO NOTHING
				""").param("id", authorization.id()).param("job", jobId).param("product", authorization.productId())
			.param("now", now()).update();
		queue(authorization);
	}

	/**
	 * A Mentor's or Crew member's grant read back as revoked (FR-21, R17), in that read-back's transaction: on a
	 * Closing Job whose check waits on {@code REVOCATION}, the last one queues the check. J10, a member who left
	 * mid-Job, and every read-back but the last change nothing (Story 24.5b).
	 */
	@Transactional
	void teamRevoked(UUID jobId) {
		Authorization authorization = this.authorizations.lockOpen(jobId).orElse(null);
		BusyJob job = authorization == null ? null : this.busy.job(jobId).orElse(null);
		if (job == null || !"CLOSING".equals(job.state())) {
			return;
		}
		queue(authorization);
	}

	/**
	 * The check deferred until {@code nextCheckAt}, waiting for {@code wait}, which the Customer's closing card names.
	 * A revocation that read back while the check looked is queued at once: the check's own read came before the
	 * last read-back committed, and that read-back found no wait to clear, so the wake lands on this attempt.
	 */
	@Transactional
	void waiting(UUID authorizationId, Wait wait, Instant nextCheckAt) {
		FinalMerge merge = find(authorizationId).orElse(null);
		Authorization authorization = merge == null ? null
				: this.authorizations.lockOpen(merge.jobId()).filter(found -> found.id().equals(authorizationId)).orElse(null);
		this.jdbc.sql("""
				UPDATE repo.final_merge SET waiting_for = :wait, next_check_at = :at
				WHERE authorization_id = :id AND status = 'CHECKING'
				""").param("wait", wait.name()).param("at", Timestamp.from(nextCheckAt)).param("id", authorizationId)
			.update();
		if (authorization != null) {
			queue(authorization);
		}
	}

	/**
	 * Requests the check of a {@code CHECKING} row that waits on {@code REVOCATION} once every team grant reads back
	 * revoked, and wakes it: the request joins a check already deferred, which is then due at once. The wait is
	 * cleared. The caller holds {@link FinalMergeAuthorizations#lockOpen}.
	 */
	private void queue(Authorization authorization) {
		UUID jobId = authorization.jobId();
		FinalMerge merge = find(authorization.id()).orElse(null);
		if (merge == null || !"CHECKING".equals(merge.status()) || merge.waitingFor() != Wait.REVOCATION
				|| !revoked(jobId)) {
			return;
		}
		int cleared = this.jdbc.sql("""
				UPDATE repo.final_merge SET waiting_for = NULL, next_check_at = NULL
				WHERE authorization_id = :id AND status = 'CHECKING' AND waiting_for = 'REVOCATION'
				""").param("id", authorization.id()).update();
		if (cleared == 0) {
			return;
		}
		OperationTicket ticket = this.operations.request(new OperationRequest(CHECK, authorization.id(),
				Map.of("jobId", jobId.toString(), "authorizationId", authorization.id().toString()), null, null, 0));
		this.operations.wake(ticket.id());
		this.authorizations.audit("repo.final_merge_check_requested", authorization.id(), jobId);
	}

	/**
	 * The check passed: the Customer may confirm, unless {@code workflows} names a file the run can never push, which
	 * is N-18's workflow-file state. It waits for nothing any more.
	 */
	@Transactional
	void ready(UUID authorizationId, String intermediarySha, String sourceBranch, List<SensitiveChange> sensitive,
			List<String> workflows) {
		this.jdbc.sql("""
				UPDATE repo.final_merge SET status = 'READY', intermediary_sha = :sha, source_branch = :branch,
				    sensitive = CAST(:sensitive AS JSONB), workflows = CAST(:workflows AS JSONB), checked_at = :now,
				    waiting_for = NULL, next_check_at = NULL
				WHERE authorization_id = :id AND status = 'CHECKING'
				""").param("sha", intermediarySha).param("branch", sourceBranch)
			.param("sensitive", JSON.writeValueAsString(sensitive)).param("workflows", JSON.writeValueAsString(workflows))
			.param("now", now()).param("id", authorizationId).update();
	}

	/**
	 * A refusal that is not transient ({@code REFUSED}, with its named reason: {@code WORKFLOW_FILES_CHANGED},
	 * {@code BRANCH_EXISTS}, {@code EXCHANGE_REFUSED}, {@code ACTIONS_QUOTA}, {@code RUN_FAILED}, {@code J9_GUARD}):
	 * the marker goes, if the Final Merge held it, and the closing choice's row stays open with it. The choice is
	 * the Customer's again, so {@value FinalMergeAuthorizations#TODO} is re-opened under the same deadline --
	 * {@link #confirm} closed it when the run started -- and the refusal is audited.
	 * <p>
	 * The row stays open on purpose (Story 23.10c): ending it here would strand the Job in {@code CLOSING} for good,
	 * now that nothing listens for that ending -- {@link FinalMergeDeadline} answers {@code OBSOLETE} for a row that
	 * is no longer {@code REQUESTED}, and AD-5 lets nothing else reach J9. Left {@code REQUESTED}, the self-download
	 * card stays offered with this reason and the FR-37 deadline keeps running, so the Job ends either way. The
	 * refusal is still final for the merge itself: {@link CodeReceipts#choice} stops offering it.
	 */
	@Transactional
	void refuse(UUID authorizationId, String refusal) {
		FinalMerge merge = find(authorizationId).orElse(null);
		if (merge == null || "DONE".equals(merge.status())) {
			return;
		}
		this.jdbc.sql("""
				UPDATE repo.final_merge SET status = 'REFUSED', refusal = :refusal, waiting_for = NULL, next_check_at = NULL
				WHERE authorization_id = :id
				""").param("refusal", refusal).param("id", authorizationId).update();
		if (merge.operationKey() != null) {
			this.busy.release(merge.jobId(), merge.operationKey());
		}
		reopenChoice(authorizationId, merge.jobId());
		this.authorizations.audit("repo.final_merge_refused", authorizationId, merge.jobId());
	}

	/**
	 * The closing choice is open again, so its to-do is too: the Customer has the self-download left and the same
	 * FR-37 deadline to take it before. One open to-do per row, as {@code FinalMergeAuthorizations.closing} keeps it.
	 */
	private void reopenChoice(UUID authorizationId, UUID jobId) {
		Authorization authorization = this.authorizations.find(authorizationId).filter(Authorization::open).orElse(null);
		UUID customer = authorization == null ? null : authorization.customerId();
		String title = this.busy.job(jobId).map(BusyJob::title).orElse(null);
		if (customer == null || title == null) {
			return;
		}
		this.todos.closeAll(FinalMergeAuthorizations.TODO, authorizationId);
		this.todos.open(new TodoRequest(FinalMergeAuthorizations.TODO, customer, Audience.CUSTOMER, authorizationId,
				Map.of("job", title), FinalMergeAuthorizations.link(jobId), authorization.deadlineAt(), jobId));
	}

	/**
	 * "Chép mã vào dự án của tôi" after "Tôi đã xem và muốn chép mã vào dự án của tôi." (N-18): the
	 * {@code FINAL_MERGE} marker on the loaded version, and the run requested. The to-do closes.
	 * @throws ApiException {@code CONFLICT FINAL_MERGE_NOT_READY}; {@code CONFLICT FINAL_MERGE_CONFIRMED};
	 * {@code CONFLICT WORKFLOW_FILES_CHANGED}; the marker's refusals
	 */
	@Transactional
	FinalMerge confirm(BusyJob job, UUID customerId) {
		Authorization authorization = this.authorizations.openOf(job.jobId())
			.orElseThrow(() -> new ApiException(ErrorCode.CONFLICT, RepoReason.FINAL_MERGE_NOT_READY));
		FinalMerge merge = find(authorization.id())
			.orElseThrow(() -> new ApiException(ErrorCode.CONFLICT, RepoReason.FINAL_MERGE_NOT_READY));
		if (confirmed(merge)) {
			throw new ApiException(ErrorCode.CONFLICT, RepoReason.FINAL_MERGE_CONFIRMED);
		}
		if (!"READY".equals(merge.status())) {
			throw new ApiException(ErrorCode.CONFLICT, RepoReason.FINAL_MERGE_NOT_READY);
		}
		if (!merge.workflows().isEmpty()) {
			// N-18's workflow-file state: GitHub never lets the run push these files, so nothing is dispatched.
			throw new ApiException(ErrorCode.CONFLICT, RepoReason.WORKFLOW_FILES_CHANGED);
		}
		OperationTicket ticket = this.operations.request(new OperationRequest(MERGE, this.ids.newId(),
				Map.of("jobId", job.jobId().toString(), "authorizationId", authorization.id().toString()), null, null, 0));
		this.busy.reserve(job.jobId(), JobBusy.Purpose.FINAL_MERGE, authorization.id().toString(), job.version(), ticket.key());
		this.jdbc.sql("""
				UPDATE repo.final_merge SET status = 'CONFIRMED', confirmed_at = :now, confirmed_by = :by, operation_key = :key
				WHERE authorization_id = :id AND status = 'READY'
				""").param("now", now()).param("by", customerId).param("key", ticket.key()).param("id", merge.authorizationId())
			.update();
		this.todos.closeAll(FinalMergeAuthorizations.TODO, authorization.id());
		this.authorizations.audit("repo.final_merge_confirmed", authorization.id(), job.jobId());
		return find(authorization.id()).orElseThrow();
	}

	/** Whether the Customer already set the run going, so the closing choice counts as made (Story 23.10a). */
	static boolean confirmed(FinalMerge merge) {
		return List.of("CONFIRMED", "RUNNING", "DONE").contains(merge.status());
	}

	/** The reasons the closure section can word (UX-DR116); the run's own failure stands for every other code. */
	private static final List<String> REASONS = List.of("WORKFLOW_FILES_CHANGED", "ACTIONS_QUOTA", "EXCHANGE_REFUSED",
			"RUN_FAILED", "BRANCH_EXISTS", "J9_GUARD");

	/**
	 * What the Customer reads of an operation that failed for good: its own code when the closure section words it,
	 * else {@code RUN_FAILED}, so a vibefix-side transient ({@code GITHUB_UNAVAILABLE}, {@code GITHUB_HTTP_502}) is
	 * never shown as a reason nobody worded.
	 */
	private static String reason(@Nullable String errorCode) {
		return errorCode != null && REASONS.contains(errorCode) ? errorCode : "RUN_FAILED";
	}

	/**
	 * The run dispatched on the Customer's own Actions under {@code correlation} (AD-44, AD-46), with its one
	 * {@value ActionsRunWakes#KEY} Deadline (Story 24.5a).
	 * @return when it was recorded dispatched, or none when the Final Merge ended meanwhile
	 */
	@Transactional
	@Nullable Instant dispatched(FinalMerge merge, UUID correlation, String branch) {
		Instant now = this.clock.instant();
		int updated = this.jdbc.sql("""
				UPDATE repo.final_merge SET status = 'RUNNING', branch = :branch, correlation_id = :correlation,
				    dispatched_at = :now, run_status = NULL, run_conclusion = NULL
				WHERE authorization_id = :id AND status IN ('CONFIRMED', 'RUNNING')
				""")
			.param("branch", branch)
			.param("correlation", correlation)
			.param("now", Timestamp.from(now))
			.param("id", merge.authorizationId())
			.update();
		if (updated == 0) {
			return null;
		}
		this.authorizations.audit("repo.final_merge_dispatched", merge.authorizationId(), merge.jobId());
		this.deadlines.schedule(ActionsRunWakes.timeout(MERGE, correlation, merge.jobId(), now));
		return now;
	}

	/**
	 * The operation key of the Final Merge that runs under {@code correlation} and has read no conclusion of its run
	 * yet, so it waits for that run (Story 24.5a).
	 */
	@Transactional(readOnly = true)
	Optional<String> waitingOn(UUID correlation) {
		return this.jdbc.sql("""
				SELECT operation_key FROM repo.final_merge
				WHERE correlation_id = :correlation AND status = 'RUNNING' AND run_conclusion IS NULL
				  AND operation_key IS NOT NULL
				""").param("correlation", correlation).query(String.class).optional();
	}

	/** The run as last read: its status, and its conclusion once it completed or vibefix stopped waiting for it. */
	@Transactional
	void runRead(FinalMerge merge, @Nullable String status, @Nullable String conclusion) {
		this.jdbc.sql("""
				UPDATE repo.final_merge SET run_status = :status, run_conclusion = :conclusion
				WHERE authorization_id = :id AND status = 'RUNNING' AND correlation_id = :correlation
				""")
			.param("status", status)
			.param("conclusion", conclusion)
			.param("id", merge.authorizationId())
			.param("correlation", merge.correlationId())
			.update();
	}

	/**
	 * The resolving transaction of a branch read back (FR-37, R1): {@code DONE}, the row spent, the marker released,
	 * J9, and both sides told that the branch is theirs to merge on GitHub.
	 */
	@Transactional
	boolean branchReady(UUID authorizationId, String operationKey, String branch) {
		FinalMerge merge = find(authorizationId)
			.filter(found -> "CONFIRMED".equals(found.status()) || "RUNNING".equals(found.status()))
			.orElse(null);
		if (merge == null) {
			return false;
		}
		this.jdbc.sql("""
				UPDATE repo.final_merge SET status = 'DONE', branch = :branch, merged_at = :now WHERE authorization_id = :id
				""").param("branch", branch).param("now", now()).param("id", authorizationId).update();
		this.authorizations.used(authorizationId);
		this.completion.finish(merge.jobId(), JobCompletion.Finish.MERGED, operationKey);
		BusyJob job = this.busy.job(merge.jobId()).orElseThrow();
		List<Recipient> both = new ArrayList<>(List.of(new Recipient(job.customerId(), Audience.CUSTOMER)));
		if (job.mentorId() != null) {
			both.add(new Recipient(job.mentorId(), Audience.MENTOR));
		}
		this.notifications.send(new NotificationRequest(BRANCH_READY,
				FinalMergeAuthorizations.cause(authorizationId, "branch-ready"), both,
				Map.of("job", job.title(), "branch", branch), FinalMergeAuthorizations.link(job.jobId()), job.jobId(), null));
		this.authorizations.audit("repo.final_branch_ready", authorizationId, merge.jobId());
		return true;
	}

	@ApplicationModuleListener
	void onOperationCompleted(OperationCompleted completed) {
		completed(completed);
	}

	/**
	 * A run that failed after its last attempt (AD-6) ends the Final Merge with its named reason, so the marker goes
	 * and the closing choice stays open with it; a run still out is given up first, so it can no longer exchange and
	 * push afterwards. A check that failed is dropped, and the next look at JOB-1 starts another.
	 */
	@Transactional
	void completed(OperationCompleted completed) {
		if (completed.status() != OperationStatus.FAILED) {
			return;
		}
		if (MERGE.equals(completed.type())) {
			this.jdbc.sql("""
					SELECT authorization_id FROM repo.final_merge WHERE operation_key = :key AND status IN ('CONFIRMED', 'RUNNING')
					""").param("key", completed.key()).query(UUID.class).optional().flatMap(this::find).ifPresent(merge -> {
						if (merge.correlationId() != null && merge.runConclusion() == null) {
							// Still queued or in progress on the Customer's Actions: closed and cancelled, never exchanged.
							this.runs.close(merge.correlationId());
						}
						refuse(merge.authorizationId(), reason(completed.errorCode()));
					});
		}
		else if (CHECK.equals(completed.type())) {
			this.jdbc.sql("DELETE FROM repo.final_merge WHERE authorization_id = :id AND status = 'CHECKING'")
				.param("id", UUID.fromString(completed.key().substring(CHECK.length() + 1)))
				.update();
		}
	}

	@Transactional(readOnly = true)
	Optional<FinalMerge> find(UUID authorizationId) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM repo.final_merge WHERE authorization_id = :id")
			.param("id", authorizationId)
			.query(FinalMerges::merge)
			.optional();
	}

	/** The Intermediary head J9's guard expects (SG-9): after the last handover merge, else the J7 commit. */
	@Transactional(readOnly = true)
	String expectedHead(UUID jobId, String approvedSha) {
		return this.jdbc.sql("""
				SELECT head_after FROM repo.handover_merge WHERE job_id = :job ORDER BY merged_at DESC, pull_number DESC LIMIT 1
				""").param("job", jobId).query(String.class).optional().orElse(approvedSha);
	}

	/**
	 * Every Mentor's and Crew member's access read back as revoked (FR-21, R17). The Customer's read of the Intermediary
	 * Repository is not counted: it lasts until the repositories are deleted (R5).
	 */
	@Transactional(readOnly = true)
	boolean revoked(UUID jobId) {
		return this.jdbc.sql("""
				SELECT NOT EXISTS (SELECT 1 FROM repo.job_grant WHERE job_id = :job AND role <> 'CUSTOMER' AND status <> 'REVOKED')
				""")
			.param("job", jobId)
			.query(Boolean.class)
			.single();
	}

	private Timestamp now() {
		return Timestamp.from(this.clock.instant());
	}

	private static FinalMerge merge(ResultSet rs, int n) throws SQLException {
		Timestamp confirmed = rs.getTimestamp("confirmed_at");
		Timestamp dispatched = rs.getTimestamp("dispatched_at");
		Timestamp merged = rs.getTimestamp("merged_at");
		String waitingFor = rs.getString("waiting_for");
		Timestamp nextCheck = rs.getTimestamp("next_check_at");
		return new FinalMerge(rs.getObject("authorization_id", UUID.class), rs.getObject("job_id", UUID.class),
				rs.getObject("product_id", UUID.class), rs.getString("status"), rs.getString("intermediary_sha"),
				rs.getString("source_branch"),
				JSON.readValue(rs.getString("sensitive"), new TypeReference<List<SensitiveChange>>() {
				}), JSON.readValue(rs.getString("workflows"), new TypeReference<List<String>>() {
				}), rs.getString("refusal"), confirmed == null ? null : confirmed.toInstant(), rs.getString("operation_key"),
				rs.getString("branch"), rs.getObject("correlation_id", UUID.class),
				dispatched == null ? null : dispatched.toInstant(), rs.getString("run_status"), rs.getString("run_conclusion"),
				merged == null ? null : merged.toInstant(), waitingFor == null ? null : Wait.valueOf(waitingFor),
				nextCheck == null ? null : nextCheck.toInstant());
	}

	/** Who confirmed, for the erasure. */
	static void erase(JdbcClient jdbc, UUID accountId) {
		jdbc.sql("UPDATE repo.final_merge SET confirmed_by = NULL WHERE confirmed_by = :id").param("id", accountId).update();
	}

}
