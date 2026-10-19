package vn.vibefix.matching.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.identity.Notices;
import vn.vibefix.job.JobPosts;
import vn.vibefix.job.JobPosts.JobPostState;
import vn.vibefix.job.JobSelection;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;

/**
 * The selected Mentor's NDA signature (FR-15): the text version and the time are recorded as
 * {@code matching}'s evidence, and J4 applies in the same transaction, so a refused J4 leaves no
 * signature. A Code Job needs N-23 acknowledged first, once per account and version (FR-18).
 */
@Component
class NdaSignatures {

	/** The NDA text's version; its legal wording waits for OQ5, and {@code vi.json} shows the same version. */
	static final String NDA_VERSION = "1.0";

	private final JdbcClient jdbc;

	private final JobPosts posts;

	private final JobSelection selection;

	private final Notices notices;

	private final AuditLog auditLog;

	private final Clock clock;

	NdaSignatures(JdbcClient jdbc, JobPosts posts, JobSelection selection, Notices notices, AuditLog auditLog,
			Clock clock) {
		this.jdbc = jdbc;
		this.posts = posts;
		this.selection = selection;
		this.notices = notices;
		this.auditLog = auditLog;
		this.clock = clock;
	}

	/**
	 * Signs the NDA for the Job the Mentor was selected for, and applies J4.
	 * @param commitEmailAcknowledged N-23 ticked on this signature, needed for a Code Job unless
	 * acknowledged before
	 * @throws ApiException {@code VALIDATION} with {@code NDA_VERSION_OUTDATED} or
	 * {@code COMMIT_EMAIL_NOT_ACKNOWLEDGED}; as {@link JobSelection#confirm}
	 */
	@Transactional
	void sign(UUID mentorId, UUID jobId, String ndaVersion, boolean commitEmailAcknowledged) {
		JobPostState post = this.posts.post(jobId)
			.filter(found -> mentorId.equals(found.mentorId()))
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (!"SELECTING".equals(post.state())) {
			throw new ApiException(ErrorCode.STALE);
		}
		if (!NDA_VERSION.equals(ndaVersion)) {
			throw new ApiException(ErrorCode.VALIDATION, MatchingReason.NDA_VERSION_OUTDATED);
		}
		if (post.codeJob()) {
			boolean known = this.notices.acknowledged(mentorId, Notices.COMMIT_EMAIL, Notices.COMMIT_EMAIL_VERSION);
			if (!known && !commitEmailAcknowledged) {
				throw new ApiException(ErrorCode.VALIDATION, MatchingReason.COMMIT_EMAIL_NOT_ACKNOWLEDGED);
			}
			this.notices.acknowledge(mentorId, Notices.COMMIT_EMAIL, Notices.COMMIT_EMAIL_VERSION);
		}
		Instant now = this.clock.instant();
		this.jdbc.sql("""
				INSERT INTO matching.nda_signature (job_id, account_id, nda_version, signed_at)
				VALUES (:job, :account, :version, :now)
				""")
			.param("job", post.jobId())
			.param("account", mentorId)
			.param("version", ndaVersion)
			.param("now", Timestamp.from(now))
			.update();
		this.auditLog.record(AuditEntry.of(AuditActor.user(mentorId), "matching.nda_signed",
				AuditObject.of("job.job", jobId)).forJob(jobId));
		this.selection.confirm(jobId, mentorId, ndaVersion, now);
	}

}
