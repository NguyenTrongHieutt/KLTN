package vn.vibefix.dispute.internal;

import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.dispute.internal.SplitResolutions.Resolution;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;

/**
 * Who opens a Split Resolution's Split Evidence Package (FR-84, FR-63, AD-8, AD-19, AD-40): only the assignee, under the grant
 * their take opened, until a withdrawal or 30 days after the decision. Every view of a tab is written to the Audit Log. It is
 * assembled on demand from the owning modules' queries, each of which holds to the grant and audits its own read too.
 */
@Component
class SplitEvidencePackage {

	/** The tabs of the Package (EXPERIENCE.md's Split Resolution decision). */
	enum Tab {

		VERSIONS, WORK_ITEMS, COMMITS, CREW_CHAT, PERSON_LINKS

	}

	private final SplitResolutions resolutions;

	private final SplitResolutionDecisions decisions;

	private final AuditLog auditLog;

	SplitEvidencePackage(SplitResolutions resolutions, SplitResolutionDecisions decisions, AuditLog auditLog) {
		this.resolutions = resolutions;
		this.decisions = decisions;
		this.auditLog = auditLog;
	}

	/**
	 * The case, once the view of {@code tab} by {@code administrator} is audited.
	 * @throws ApiException {@code NOT_FOUND} unless they may read the Package now
	 */
	@Transactional
	Resolution view(UUID resolutionId, UUID administrator, Tab tab) {
		Resolution resolution = this.resolutions.find(resolutionId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (!this.decisions.evidenceReadable(resolution, administrator)) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		this.auditLog.record(AuditEntry
			.of(AuditActor.user(administrator), "dispute.split_evidence_viewed." + tab.name().toLowerCase(Locale.ROOT),
					AuditObject.of("dispute.split_resolution", resolutionId))
			.forJob(resolution.jobId()));
		return resolution;
	}

}
