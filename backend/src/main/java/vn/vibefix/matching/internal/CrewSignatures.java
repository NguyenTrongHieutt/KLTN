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
import vn.vibefix.job.Crews;
import vn.vibefix.job.Crews.Assignment;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;

/**
 * A Crew member's "Ký và xác nhận" (FR-75, FR-18, AD-41 as revised by R-1): N-5 and, for a Code Job,
 * N-23 are required; the first confirmation on the Job records the NDA signature, and every later row
 * of the same Student on that Job reuses it; then {@code job} records the confirmation, all in one
 * transaction, so a refused confirmation leaves no signature behind.
 */
@Component
class CrewSignatures {

	private final JdbcClient jdbc;

	private final Crews crews;

	private final Notices notices;

	private final AuditLog auditLog;

	private final Clock clock;

	CrewSignatures(JdbcClient jdbc, Crews crews, Notices notices, AuditLog auditLog, Clock clock) {
		this.jdbc = jdbc;
		this.crews = crews;
		this.notices = notices;
		this.auditLog = auditLog;
		this.clock = clock;
	}

	/**
	 * @throws ApiException {@code NOT_FOUND} without an assignment; {@code VALIDATION} with
	 * {@code PUBLIC_CODE_NOT_ACKNOWLEDGED}, {@code NDA_VERSION_OUTDATED}, or
	 * {@code COMMIT_EMAIL_NOT_ACKNOWLEDGED}; and {@code job}'s refusals
	 */
	@Transactional
	void confirm(UUID studentId, UUID jobId, int versionNo, String ndaVersion, boolean publicCodeAcknowledged,
			boolean commitEmailAcknowledged) {
		Assignment assignment = this.crews.assignment(jobId, studentId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (!publicCodeAcknowledged) {
			throw new ApiException(ErrorCode.VALIDATION, MatchingReason.PUBLIC_CODE_NOT_ACKNOWLEDGED);
		}
		if (!NdaSignatures.NDA_VERSION.equals(ndaVersion)) {
			throw new ApiException(ErrorCode.VALIDATION, MatchingReason.NDA_VERSION_OUTDATED);
		}
		if (assignment.codeJob()) {
			boolean known = this.notices.acknowledged(studentId, Notices.COMMIT_EMAIL, Notices.COMMIT_EMAIL_VERSION);
			if (!known && !commitEmailAcknowledged) {
				throw new ApiException(ErrorCode.VALIDATION, MatchingReason.COMMIT_EMAIL_NOT_ACKNOWLEDGED);
			}
			this.notices.acknowledge(studentId, Notices.COMMIT_EMAIL, Notices.COMMIT_EMAIL_VERSION);
		}
		Instant signedAt = this.jdbc.sql("SELECT signed_at FROM matching.nda_signature WHERE job_id = :job AND account_id = :id")
			.param("job", jobId)
			.param("id", studentId)
			.query(Timestamp.class)
			.optional()
			.map(Timestamp::toInstant)
			.orElse(null);
		if (signedAt == null) {
			signedAt = this.clock.instant();
			this.jdbc.sql("""
					INSERT INTO matching.nda_signature (job_id, account_id, nda_version, signed_at)
					VALUES (:job, :id, :version, :at)
					""")
				.param("job", jobId)
				.param("id", studentId)
				.param("version", ndaVersion)
				.param("at", Timestamp.from(signedAt))
				.update();
			this.auditLog.record(AuditEntry.of(AuditActor.user(studentId), "matching.nda_signed",
					AuditObject.of("matching.nda_signature", jobId)).forJob(jobId));
		}
		this.crews.confirm(jobId, studentId, versionNo, signedAt);
	}

}
