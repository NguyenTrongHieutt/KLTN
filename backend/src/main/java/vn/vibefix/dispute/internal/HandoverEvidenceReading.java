package vn.vibefix.dispute.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.delivery.HandoverEvidenceReaders;
import vn.vibefix.dispute.internal.SplitResolutions.Resolution;
import vn.vibefix.shared.Caller;

/**
 * {@code delivery}'s port, answered from the cases (FR-53, FR-63, FR-84, AD-8, AD-20, AD-31, AD-40, Story 24.3b): an
 * Administrator sees a handover's image when they are the assignee of a Dispute on its Job whose Evidence Package
 * they may read now ({@link DisputeDecisions#evidenceReadable}), or of a Split Resolution on its Milestone whose Split
 * Evidence Package they may read now ({@link SplitResolutionDecisions#evidenceReadable}). The Dispute's package shows
 * every handover of the Job, a split's only its own Milestone's. Each yes is audited as the case's view of that
 * image, naming the case and the stored file, in the same transaction. It is a check, not a grant: nothing is stored for it, so it ends with the case's
 * own reading period, a takeover or a withdrawal.
 */
@Component
class HandoverEvidenceReading implements HandoverEvidenceReaders {

	/** The audit object of the image read: the row {@code files} keeps for every stored file. */
	static final String STORED_FILE = "files.stored_file";

	private final JdbcClient jdbc;

	private final DisputeDecisions disputes;

	private final SplitResolutions resolutions;

	private final SplitResolutionDecisions splits;

	private final AuditLog auditLog;

	HandoverEvidenceReading(JdbcClient jdbc, DisputeDecisions disputes, SplitResolutions resolutions,
			SplitResolutionDecisions splits, AuditLog auditLog) {
		this.jdbc = jdbc;
		this.disputes = disputes;
		this.resolutions = resolutions;
		this.splits = splits;
		this.auditLog = auditLog;
	}

	@Override
	@Transactional
	public boolean mayRead(UUID jobId, UUID milestoneId, UUID fileId, Caller caller) {
		if (!caller.isAdministrator()) {
			return false;
		}
		UUID administrator = caller.requireAccountId();
		List<UUID> disputes = this.jdbc.sql("""
				SELECT id FROM dispute.dispute WHERE job_id = :job AND assigned_admin_id = :admin ORDER BY opened_at, id
				""").param("job", jobId).param("admin", administrator).query(UUID.class).list();
		for (UUID dispute : disputes) {
			if (this.disputes.evidenceReadable(dispute, administrator)) {
				read(administrator, "dispute.evidence_viewed.handover_image", "dispute.dispute", dispute, fileId, jobId);
				return true;
			}
		}
		List<UUID> resolutions = this.jdbc.sql("""
				SELECT id FROM dispute.split_resolution
				WHERE job_id = :job AND milestone_id = :milestone AND assigned_admin_id = :admin ORDER BY raised_at, id
				""").param("job", jobId).param("milestone", milestoneId).param("admin", administrator).query(UUID.class).list();
		for (UUID id : resolutions) {
			Resolution resolution = this.resolutions.find(id).orElse(null);
			if (resolution != null && this.splits.evidenceReadable(resolution, administrator)) {
				read(administrator, "dispute.split_evidence_viewed.handover_image", "dispute.split_resolution", id, fileId,
						jobId);
				return true;
			}
		}
		return false;
	}

	/** The read, as the case's view of the image: the case first, then the stored file, so each image is its own entry. */
	private void read(UUID administrator, String action, String objectType, UUID caseId, UUID fileId, UUID jobId) {
		this.auditLog.record(AuditEntry
			.of(AuditActor.user(administrator), action, AuditObject.of(objectType, caseId),
					AuditObject.of(STORED_FILE, fileId))
			.forJob(jobId));
	}

}
