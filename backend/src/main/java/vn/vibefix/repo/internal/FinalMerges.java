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
import vn.vibefix.github.MergeApp.PullRequest;
import vn.vibefix.job.JobBusy;
import vn.vibefix.job.JobBusy.BusyJob;
import vn.vibefix.job.JobCompletion;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
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

/**
 * The Final Merge (FR-37, AD-5, AD-11, SG-15), one per bound authorization. Once the Job is Closing and
 * its authorization {@code BOUND}, {@value #CHECK} reads the preconditions, the Job's Sensitive Changes,
 * and the trial merge; a clean one is {@code READY} for the Customer's confirmation (N-18). The
 * confirmation reserves the {@code FINAL_MERGE} busy marker and requests {@value #MERGE}, whose resolving
 * transaction sets the authorization {@code USED}, releases the marker, and applies J9. A conflict or a
 * refusal that is not transient ends the authorization {@code ZIP_FALLBACK} (Story 8.9).
 */
@Component
class FinalMerges {

	static final String CHECK = "repo.final_merge_check";

	static final String MERGE = "repo.final_merge";

	static final String COMPLETED = "repo.final_merge_completed";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String COLUMNS = """
			authorization_id, job_id, product_id, status, intermediary_sha, source_branch, sensitive::text AS sensitive,
			workflows::text AS workflows, refusal, confirmed_at, operation_key, branch, pull_number, pull_url, merged_at
			""";

	/** One Final Merge. */
	record FinalMerge(UUID authorizationId, UUID jobId, UUID productId, String status, @Nullable String intermediarySha,
			@Nullable String sourceBranch, List<SensitiveChange> sensitive, List<String> workflows, @Nullable String refusal,
			@Nullable Instant confirmedAt, @Nullable String operationKey, @Nullable String branch,
			@Nullable Integer pullNumber, @Nullable String pullUrl, @Nullable Instant mergedAt) {
	}

	private final JdbcClient jdbc;

	private final FinalMergeAuthorizations authorizations;

	private final JobBusy busy;

	private final JobCompletion completion;

	private final ExternalOperations operations;

	private final Todos todos;

	private final Notifications notifications;

	private final IdGenerator ids;

	private final Clock clock;

	FinalMerges(JdbcClient jdbc, FinalMergeAuthorizations authorizations, JobBusy busy, JobCompletion completion,
			ExternalOperations operations, Todos todos, Notifications notifications, IdGenerator ids, Clock clock) {
		this.jdbc = jdbc;
		this.authorizations = authorizations;
		this.busy = busy;
		this.completion = completion;
		this.operations = operations;
		this.todos = todos;
		this.notifications = notifications;
		this.ids = ids;
		this.clock = clock;
	}

	/**
	 * Starts the check once the Job is Closing and its authorization {@code BOUND}; one already checked or
	 * checking changes nothing.
	 */
	@Transactional
	void prepare(UUID jobId) {
		Authorization authorization = this.authorizations.openOf(jobId).filter(found -> "BOUND".equals(found.status()))
			.orElse(null);
		BusyJob job = this.busy.job(jobId).orElse(null);
		if (authorization == null || job == null || !"CLOSING".equals(job.state())) {
			return;
		}
		int created = this.jdbc.sql("""
				INSERT INTO repo.final_merge (authorization_id, job_id, product_id, status, created_at)
				VALUES (:id, :job, :product, 'CHECKING', :now)
				ON CONFLICT DO NOTHING
				""").param("id", authorization.id()).param("job", jobId).param("product", authorization.productId())
			.param("now", now()).update();
		if (created == 1) {
			this.operations.request(new OperationRequest(CHECK, authorization.id(),
					Map.of("jobId", jobId.toString(), "authorizationId", authorization.id().toString()), null, null, 0));
			this.authorizations.audit("repo.final_merge_check_requested", authorization.id(), jobId);
		}
	}

	/** The check passed: the Customer may confirm. */
	@Transactional
	void ready(UUID authorizationId, String intermediarySha, String sourceBranch, List<SensitiveChange> sensitive,
			List<String> workflows) {
		this.jdbc.sql("""
				UPDATE repo.final_merge SET status = 'READY', intermediary_sha = :sha, source_branch = :branch,
				    sensitive = CAST(:sensitive AS JSONB), workflows = CAST(:workflows AS JSONB), checked_at = :now
				WHERE authorization_id = :id AND status = 'CHECKING'
				""").param("sha", intermediarySha).param("branch", sourceBranch)
			.param("sensitive", JSON.writeValueAsString(sensitive)).param("workflows", JSON.writeValueAsString(workflows))
			.param("now", now()).param("id", authorizationId).update();
	}

	/**
	 * A trial-merge conflict ({@code CONFLICT}) or a refusal that is not transient ({@code REFUSED}): the
	 * marker goes, if the merge held it, and the authorization ends {@code ZIP_FALLBACK} (Story 8.9).
	 */
	@Transactional
	void fallback(UUID authorizationId, String status, String refusal) {
		FinalMerge merge = find(authorizationId).orElse(null);
		if (merge == null || "MERGED".equals(merge.status())) {
			return;
		}
		this.jdbc.sql("UPDATE repo.final_merge SET status = :status, refusal = :refusal WHERE authorization_id = :id")
			.param("status", status).param("refusal", refusal).param("id", authorizationId).update();
		if (merge.operationKey() != null) {
			this.busy.release(merge.jobId(), merge.operationKey());
		}
		this.authorizations.end(authorizationId, "ZIP_FALLBACK");
	}

	/**
	 * "Hợp nhất vào dự án" after "Tôi đã xem và đồng ý hợp nhất." (N-18): the {@code FINAL_MERGE} marker on
	 * the loaded version, and the merge requested. The to-do closes.
	 * @throws ApiException {@code CONFLICT FINAL_MERGE_NOT_READY}; the marker's refusals
	 */
	@Transactional
	FinalMerge confirm(BusyJob job, UUID customerId) {
		Authorization authorization = this.authorizations.openOf(job.jobId())
			.filter(found -> "BOUND".equals(found.status()))
			.orElseThrow(() -> new ApiException(ErrorCode.CONFLICT, RepoReason.FINAL_MERGE_NOT_READY));
		FinalMerge merge = find(authorization.id()).filter(found -> "READY".equals(found.status()))
			.orElseThrow(() -> new ApiException(ErrorCode.CONFLICT, RepoReason.FINAL_MERGE_NOT_READY));
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

	/** The pushed branch and its pull request, kept so a retry reuses them. */
	@Transactional
	void pulled(UUID authorizationId, String branch, PullRequest pull) {
		this.jdbc.sql("""
				UPDATE repo.final_merge SET branch = :branch, pull_number = :number, pull_url = :url WHERE authorization_id = :id
				""").param("branch", branch).param("number", pull.number()).param("url", pull.url()).param("id", authorizationId)
			.update();
	}

	/**
	 * The resolving transaction of a merge read back (FR-37): {@code MERGED}, the authorization
	 * {@code USED}, the marker released, J9, and both sides told.
	 */
	@Transactional
	boolean merged(UUID authorizationId, String operationKey) {
		FinalMerge merge = find(authorizationId).filter(found -> "CONFIRMED".equals(found.status())).orElse(null);
		if (merge == null) {
			return false;
		}
		this.jdbc.sql("UPDATE repo.final_merge SET status = 'MERGED', merged_at = :now WHERE authorization_id = :id")
			.param("now", now()).param("id", authorizationId).update();
		this.authorizations.used(authorizationId);
		this.completion.finish(merge.jobId(), JobCompletion.Finish.MERGED, operationKey);
		BusyJob job = this.busy.job(merge.jobId()).orElseThrow();
		List<Recipient> both = new ArrayList<>(List.of(new Recipient(job.customerId(), Audience.CUSTOMER)));
		if (job.mentorId() != null) {
			both.add(new Recipient(job.mentorId(), Audience.MENTOR));
		}
		this.notifications.send(new NotificationRequest(COMPLETED, FinalMergeAuthorizations.cause(authorizationId, "merged"),
				both, Map.of("job", job.title()), FinalMergeAuthorizations.link(job.jobId()), job.jobId(), null));
		this.authorizations.audit("repo.final_merge_completed", authorizationId, merge.jobId());
		return true;
	}

	@ApplicationModuleListener
	void onOperationCompleted(OperationCompleted completed) {
		completed(completed);
	}

	/**
	 * A merge that failed after its last attempt (AD-6) releases the marker and is {@code READY} again, so
	 * the Customer may confirm again; a check that failed is dropped, and the next look at JOB-1 starts
	 * another.
	 */
	@Transactional
	void completed(OperationCompleted completed) {
		if (completed.status() != OperationStatus.FAILED) {
			return;
		}
		if (MERGE.equals(completed.type())) {
			this.jdbc.sql("""
					SELECT authorization_id FROM repo.final_merge WHERE operation_key = :key AND status = 'CONFIRMED'
					""").param("key", completed.key()).query(UUID.class).optional().flatMap(this::find).ifPresent(merge -> {
						this.busy.release(merge.jobId(), completed.key());
						this.jdbc.sql("""
								UPDATE repo.final_merge SET status = 'READY', operation_key = NULL, confirmed_at = NULL,
								    confirmed_by = NULL
								WHERE authorization_id = :id
								""").param("id", merge.authorizationId()).update();
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

	/** Every Mentor's and Crew member's access read back as revoked (FR-21). */
	@Transactional(readOnly = true)
	boolean revoked(UUID jobId) {
		return this.jdbc.sql("SELECT NOT EXISTS (SELECT 1 FROM repo.job_grant WHERE job_id = :job AND status <> 'REVOKED')")
			.param("job", jobId)
			.query(Boolean.class)
			.single();
	}

	private Timestamp now() {
		return Timestamp.from(this.clock.instant());
	}

	private static FinalMerge merge(ResultSet rs, int n) throws SQLException {
		Timestamp confirmed = rs.getTimestamp("confirmed_at");
		Timestamp merged = rs.getTimestamp("merged_at");
		return new FinalMerge(rs.getObject("authorization_id", UUID.class), rs.getObject("job_id", UUID.class),
				rs.getObject("product_id", UUID.class), rs.getString("status"), rs.getString("intermediary_sha"),
				rs.getString("source_branch"),
				JSON.readValue(rs.getString("sensitive"), new TypeReference<List<SensitiveChange>>() {
				}), JSON.readValue(rs.getString("workflows"), new TypeReference<List<String>>() {
				}), rs.getString("refusal"), confirmed == null ? null : confirmed.toInstant(), rs.getString("operation_key"),
				rs.getString("branch"), rs.getObject("pull_number", Integer.class), rs.getString("pull_url"),
				merged == null ? null : merged.toInstant());
	}

	/** Who confirmed, for the erasure. */
	static void erase(JdbcClient jdbc, UUID accountId) {
		jdbc.sql("UPDATE repo.final_merge SET confirmed_by = NULL WHERE confirmed_by = :id").param("id", accountId).update();
	}

}
