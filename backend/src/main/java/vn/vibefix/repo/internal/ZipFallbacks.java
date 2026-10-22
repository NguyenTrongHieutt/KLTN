package vn.vibefix.repo.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import vn.vibefix.job.JobBusy;
import vn.vibefix.job.JobBusy.BusyJob;
import vn.vibefix.job.JobCompletion;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.operations.ExternalOperations;
import vn.vibefix.operations.OperationRequest;
import vn.vibefix.repo.FinalMergeAuthorizationEnded;
import vn.vibefix.repo.internal.FinalMergeAuthorizations.Authorization;
import vn.vibefix.repo.internal.FinalMerges.FinalMerge;
import vn.vibefix.scanning.SensitiveChanges.SensitiveChange;
import vn.vibefix.shared.ProcessedEvents;

/**
 * The ZIP Fallback (FR-38, FR-20, SG-15): whenever the Final Merge of a Code Job in Closing cannot happen,
 * the Customer still gets the code. It starts when the authorization ends {@code ZIP_FALLBACK} (a
 * conflict, a gone repository, a refusal, a failed guard), {@code DEADLINE} (nothing authorized, a request
 * no owner approved, or Sensitive Changes not confirmed by the FR-37 deadline), or {@code DECLINED} (the
 * Customer chose the ZIP). {@value #BUILD} stores the ZIP; its resolving transaction applies J9 (Closed,
 * or Cancelled after a cancellation) and tells both sides ({@value #READY}).
 */
@Component
class ZipFallbacks {

	static final String BUILD = "repo.zip_fallback";

	static final String READY = "repo.zip_fallback_ready";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final Set<String> STARTS = Set.of("ZIP_FALLBACK", "DEADLINE", "DECLINED");

	/**
	 * One fallback.
	 * @param reason why the merge could not happen, as N-17 says it
	 */
	record ZipFallback(UUID jobId, UUID authorizationId, UUID productId, String reason, String status,
			@Nullable UUID archiveFileId, List<SensitiveChange> sensitive, @Nullable Instant readyAt) {
	}

	private final JdbcClient jdbc;

	private final FinalMergeAuthorizations authorizations;

	private final FinalMerges merges;

	private final JobBusy busy;

	private final JobCompletion completion;

	private final ExternalOperations operations;

	private final Notifications notifications;

	private final ProcessedEvents processedEvents;

	private final Clock clock;

	ZipFallbacks(JdbcClient jdbc, FinalMergeAuthorizations authorizations, FinalMerges merges, JobBusy busy,
			JobCompletion completion, ExternalOperations operations, Notifications notifications,
			ProcessedEvents processedEvents, Clock clock) {
		this.jdbc = jdbc;
		this.authorizations = authorizations;
		this.merges = merges;
		this.busy = busy;
		this.completion = completion;
		this.operations = operations;
		this.notifications = notifications;
		this.processedEvents = processedEvents;
		this.clock = clock;
	}

	@ApplicationModuleListener
	void onAuthorizationEnded(FinalMergeAuthorizationEnded event) {
		if (STARTS.contains(event.reason())
				&& this.processedEvents.markProcessed("repo.ZipFallbacks.onAuthorizationEnded", event.eventId())) {
			start(event.authorizationId(), event.reason());
		}
	}

	/** Starts the fallback for a Code Job still Closing; one already started changes nothing. */
	@Transactional
	void start(UUID authorizationId, String ended) {
		Authorization authorization = this.authorizations.find(authorizationId).orElse(null);
		BusyJob job = authorization == null ? null : this.busy.job(authorization.jobId()).orElse(null);
		if (authorization == null || job == null || !job.code() || !"CLOSING".equals(job.state())) {
			return;
		}
		int created = this.jdbc.sql("""
				INSERT INTO repo.zip_fallback (job_id, authorization_id, product_id, reason, status, created_at)
				VALUES (:job, :authorization, :product, :reason, 'BUILDING', :now)
				ON CONFLICT DO NOTHING
				""").param("job", job.jobId()).param("authorization", authorizationId).param("product", job.productId())
			.param("reason", reason(authorization, ended)).param("now", Timestamp.from(this.clock.instant())).update();
		if (created == 1) {
			this.operations.request(new OperationRequest(BUILD, job.jobId(), Map.of("jobId", job.jobId().toString(),
					"customerId", job.customerId().toString()), null, null, 0));
			this.authorizations.audit("repo.zip_fallback_started", authorizationId, job.jobId());
		}
	}

	/** The ZIP is stored: J9, and both sides told. */
	@Transactional
	boolean ready(UUID jobId, UUID archiveFileId, List<SensitiveChange> sensitive) {
		int updated = this.jdbc.sql("""
				UPDATE repo.zip_fallback SET status = 'READY', archive_file_id = :file, sensitive = CAST(:sensitive AS JSONB),
				    ready_at = :now
				WHERE job_id = :job AND status = 'BUILDING'
				""").param("file", archiveFileId).param("sensitive", JSON.writeValueAsString(sensitive))
			.param("now", Timestamp.from(this.clock.instant())).param("job", jobId).update();
		if (updated == 0) {
			return false;
		}
		this.completion.finish(jobId, JobCompletion.Finish.ZIP_FALLBACK, null);
		BusyJob job = this.busy.job(jobId).orElseThrow();
		List<Recipient> both = new ArrayList<>(List.of(new Recipient(job.customerId(), Audience.CUSTOMER)));
		if (job.mentorId() != null) {
			both.add(new Recipient(job.mentorId(), Audience.MENTOR));
		}
		this.notifications.send(new NotificationRequest(READY,
				java.util.UUID.nameUUIDFromBytes((jobId + ":zip_fallback").getBytes(java.nio.charset.StandardCharsets.UTF_8)), both,
				Map.of("job", job.title()), FinalMergeAuthorizations.link(jobId), jobId, null));
		return true;
	}

	@Transactional(readOnly = true)
	Optional<ZipFallback> find(UUID jobId) {
		return this.jdbc.sql("""
				SELECT job_id, authorization_id, product_id, reason, status, archive_file_id, sensitive::text AS sensitive,
				    ready_at
				FROM repo.zip_fallback WHERE job_id = :job
				""").param("job", jobId).query((rs, n) -> {
					Timestamp ready = rs.getTimestamp("ready_at");
					return new ZipFallback(rs.getObject("job_id", UUID.class), rs.getObject("authorization_id", UUID.class),
							rs.getObject("product_id", UUID.class), rs.getString("reason"), rs.getString("status"),
							rs.getObject("archive_file_id", UUID.class),
							JSON.readValue(rs.getString("sensitive"), new TypeReference<List<SensitiveChange>>() {
							}), ready == null ? null : ready.toInstant());
				}).optional();
	}

	/** N-17's reason: the Final Merge's own refusal, else why the authorization ended. */
	private String reason(Authorization authorization, String ended) {
		FinalMerge merge = this.merges.find(authorization.id()).orElse(null);
		if ("DECLINED".equals(ended)) {
			return "DECLINED";
		}
		if ("DEADLINE".equals(ended)) {
			if (merge != null) {
				return "CONFIRMATION_MISSED";
			}
			return authorization.requestSeenAt() != null || authorization.requestRefusedAt() != null
					? "REQUEST_NOT_APPROVED" : "NOT_AUTHORIZED";
		}
		String refusal = merge == null ? null : merge.refusal();
		return refusal != null && Set.of("TRIAL_CONFLICT", "SOURCE_NOT_PUBLIC", "J9_GUARD", "PUSH_REFUSED", "MERGE_REFUSED")
			.contains(refusal) ? refusal : "MERGE_REFUSED";
	}

}
