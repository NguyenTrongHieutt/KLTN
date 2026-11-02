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
import vn.vibefix.job.CrewPicks;
import vn.vibefix.job.CrewPicks.Assignment;
import vn.vibefix.matching.internal.CrewApplications.CrewApplication;
import vn.vibefix.matching.internal.CrewApplications.EndReason;
import vn.vibefix.matching.internal.CrewApplications.Status;
import vn.vibefix.matching.internal.SourceContributors.Contribution;
import vn.vibefix.notification.Todos;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;

/**
 * The picked person's commitment signature, "Ký và tham gia" (FR-81, FR-18, AD-41, A-03, A-04, Story 21.5): N-5, the
 * NDA's and the commitment's versions, and for a Code Job N-23 (acknowledged once per account and notice version) are
 * checked first; then {@code job.activateCrew} takes the Job row lock and re-checks every rule, and only then is the
 * application locked and {@code JOINED} and the NDA and commitment signature recorded ({@code matching.nda_signature},
 * with both versions and the time), all in one transaction, so a refused activation, a signature after J8 included,
 * leaves no signature behind. The Source Repository's contributors were read before the transaction.
 */
@Component
class CrewSignatures {

	private final JdbcClient jdbc;

	private final CrewApplications applications;

	private final CrewCommitments commitments;

	private final CrewPicks picks;

	private final Notices notices;

	private final Todos todos;

	private final AuditLog auditLog;

	private final Clock clock;

	CrewSignatures(JdbcClient jdbc, CrewApplications applications, CrewCommitments commitments, CrewPicks picks,
			Notices notices, Todos todos, AuditLog auditLog, Clock clock) {
		this.jdbc = jdbc;
		this.applications = applications;
		this.commitments = commitments;
		this.picks = picks;
		this.notices = notices;
		this.todos = todos;
		this.auditLog = auditLog;
		this.clock = clock;
	}

	/**
	 * Signs the NDA and the commitment, and the person joins the Crew. The caller checked the GitHub link (FR-80).
	 * @param contribution what {@link SourceContributors} read before this transaction (A-03)
	 * @throws ApiException {@code NOT_FOUND} without a pick; {@code GUARD_FAILED PICK_ENDED} for one that ended;
	 * {@code VALIDATION} with {@code PUBLIC_CODE_NOT_ACKNOWLEDGED}, {@code NDA_VERSION_OUTDATED},
	 * {@code COMMITMENT_VERSION_OUTDATED} or {@code COMMIT_EMAIL_NOT_ACKNOWLEDGED}; and {@code job.activateCrew}'s
	 * refusals
	 */
	@Transactional
	void sign(UUID accountId, UUID jobId, String ndaVersion, String commitmentVersion, boolean publicCodeAcknowledged,
			boolean commitEmailAcknowledged, Contribution contribution) {
		CrewApplication pick = this.applications.pickOf(jobId, accountId)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (pick.status() != Status.PICKED || pick.crewRowId() == null) {
			throw new ApiException(ErrorCode.GUARD_FAILED, MatchingReason.PICK_ENDED);
		}
		Instant now = this.clock.instant();
		// "Ký trước {date}" holds even if the Deadline worker runs late.
		if (pick.signDueAt() != null && !now.isBefore(pick.signDueAt())) {
			throw new ApiException(ErrorCode.GUARD_FAILED, MatchingReason.PICK_ENDED);
		}
		if (!publicCodeAcknowledged) {
			throw new ApiException(ErrorCode.VALIDATION, MatchingReason.PUBLIC_CODE_NOT_ACKNOWLEDGED);
		}
		if (!NdaSignatures.NDA_VERSION.equals(ndaVersion)) {
			throw new ApiException(ErrorCode.VALIDATION, MatchingReason.NDA_VERSION_OUTDATED);
		}
		if (!CrewCommitments.COMMITMENT_VERSION.equals(commitmentVersion)) {
			throw new ApiException(ErrorCode.VALIDATION, MatchingReason.COMMITMENT_VERSION_OUTDATED);
		}
		Assignment assignment = this.picks.assignment(jobId, accountId)
			.orElseThrow(() -> new ApiException(ErrorCode.GUARD_FAILED, MatchingReason.PICK_ENDED));
		if (assignment.codeJob()) {
			boolean known = this.notices.acknowledged(accountId, Notices.COMMIT_EMAIL, Notices.COMMIT_EMAIL_VERSION);
			if (!known && !commitEmailAcknowledged) {
				throw new ApiException(ErrorCode.VALIDATION, MatchingReason.COMMIT_EMAIL_NOT_ACKNOWLEDGED);
			}
		}
		this.picks.activateCrew(jobId, pick.crewRowId(), CrewCommitments.job(contribution), now);
		CrewApplication application = this.applications.lock(pick.id()).orElseThrow();
		if (application.status() != Status.PICKED
				|| !this.applications.endPick(pick.id(), Status.JOINED, EndReason.SIGNED)) {
			throw new ApiException(ErrorCode.STALE);
		}
		if (assignment.codeJob()) {
			this.notices.acknowledge(accountId, Notices.COMMIT_EMAIL, Notices.COMMIT_EMAIL_VERSION);
		}
		// One NDA signature per Job and person: a former selected Mentor who later joins the Crew already holds a row, which
		// keeps its NDA version and time and gains the commitment's version.
		this.jdbc.sql("""
				INSERT INTO matching.nda_signature (job_id, account_id, nda_version, commitment_version, signed_at)
				VALUES (:job, :account, :nda, :commitment, :now)
				ON CONFLICT (job_id, account_id) DO UPDATE
				    SET commitment_version = coalesce(matching.nda_signature.commitment_version, EXCLUDED.commitment_version)
				""")
			.param("job", jobId)
			.param("account", accountId)
			.param("nda", ndaVersion)
			.param("commitment", commitmentVersion)
			.param("now", Timestamp.from(now))
			.update();
		this.todos.closeAll(CrewCommitments.SIGN_TODO, pick.id());
		this.auditLog.record(AuditEntry.of(AuditActor.user(accountId), "matching.commitment_signed",
				AuditObject.of("matching.nda_signature", jobId)).forJob(jobId));
		this.commitments.refreshReview(jobId);
	}

}
