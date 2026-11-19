package vn.vibefix.delivery.internal;

import java.util.Objects;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.vibefix.delivery.HandoverEvidenceReaders;
import vn.vibefix.delivery.internal.HandoverScreenshots.Screenshot;
import vn.vibefix.files.FileReadPolicy;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;

/**
 * Who may read a handover's screenshot (FR-32, AD-20): before "Gửi bàn giao" its uploader only; once
 * attached, everyone who reads the Delivery Report, that is the Job's Customer, Mentor, and Crew. An
 * Administrator holds no role in a Job, so the Job's readers refuse one; an attached image is then asked of
 * {@link HandoverEvidenceReaders}, where the assignee of a case whose Evidence Package shows the handover reads
 * its images while the case lets them (FR-53, FR-84, AD-31, AD-40, Story 24.3b). That answer is a check, not a
 * grant, and an upload no handover took yet never reaches it.
 */
@Component
class HandoverScreenshotFiles implements FileReadPolicy {

	/** Looked up on use: the access check reads {@code job}, which reads {@code files}. */
	private final ObjectProvider<HandoverScreenshots> screenshots;

	private final ObjectProvider<DeliveryAccess> access;

	/** The port the cases' module answers, looked up on use like the others; absent, it answers nobody. */
	private final ObjectProvider<HandoverEvidenceReaders> caseReaders;

	private final JdbcClient jdbc;

	HandoverScreenshotFiles(ObjectProvider<HandoverScreenshots> screenshots, ObjectProvider<DeliveryAccess> access,
			ObjectProvider<HandoverEvidenceReaders> caseReaders, JdbcClient jdbc) {
		this.screenshots = screenshots;
		this.access = access;
		this.caseReaders = caseReaders;
		this.jdbc = jdbc;
	}

	@Override
	public String purpose() {
		return HandoverScreenshots.PURPOSE;
	}

	@Override
	public boolean mayRead(UUID fileId, Caller caller) {
		Screenshot screenshot = this.screenshots.getObject().find(fileId).orElse(null);
		if (screenshot == null || caller.accountId() == null) {
			return false;
		}
		UUID handoverId = screenshot.handoverId();
		if (handoverId == null) {
			return Objects.equals(screenshot.accountId(), caller.accountId());
		}
		try {
			this.access.getObject().reader(screenshot.jobId(), caller);
			return true;
		}
		catch (ApiException refused) {
			return caller.isAdministrator() && caseReader(screenshot.jobId(), handoverId, fileId, caller);
		}
	}

	/** Whether an Administrator's case shows the handover now, asked with the handover's Job and Milestone. */
	private boolean caseReader(UUID jobId, UUID handoverId, UUID fileId, Caller caller) {
		HandoverEvidenceReaders readers = this.caseReaders.getIfAvailable();
		if (readers == null) {
			return false;
		}
		UUID milestone = this.jdbc.sql("SELECT milestone_id FROM delivery.handover WHERE id = :id AND job_id = :job")
			.param("id", handoverId)
			.param("job", jobId)
			.query(UUID.class)
			.optional()
			.orElse(null);
		return milestone != null && readers.mayRead(jobId, milestone, fileId, caller);
	}

}
