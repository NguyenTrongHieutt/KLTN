package vn.vibefix.dispute.internal;

import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.dispute.internal.Disputes.Dispute;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;

/**
 * Who opens a Dispute's Evidence Package (FR-53, FR-63, AD-8, AD-20, AD-31): only the assignee, under the
 * grant their take opened, until 30 days after the decision. Every view of a tab is written to the Audit
 * Log. The tabs {@code chat} and {@code work} serve under the grant are audited by those modules.
 */
@Component
class EvidencePackage {

	/** The tabs {@code dispute} assembles from the owning modules' queries. */
	enum Tab {

		AGREEMENT, CRITERIA, DECISIONS, PAYMENTS, DRIFT, AUDIT

	}

	private final Disputes disputes;

	private final DisputeDecisions decisions;

	private final AuditLog auditLog;

	EvidencePackage(Disputes disputes, DisputeDecisions decisions, AuditLog auditLog) {
		this.disputes = disputes;
		this.decisions = decisions;
		this.auditLog = auditLog;
	}

	/**
	 * The Dispute, once the view of {@code tab} by {@code administrator} is audited.
	 * @throws ApiException {@code NOT_FOUND} unless they may read the Package now
	 */
	@Transactional
	Dispute view(UUID disputeId, UUID administrator, Tab tab) {
		Dispute dispute = this.disputes.find(disputeId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (!this.decisions.evidenceReadable(disputeId, administrator)) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		this.auditLog.record(AuditEntry.of(AuditActor.user(administrator),
				"dispute.evidence_viewed." + tab.name().toLowerCase(Locale.ROOT), AuditObject.of("dispute.dispute", disputeId))
			.forJob(dispute.jobId()));
		return dispute;
	}

}
