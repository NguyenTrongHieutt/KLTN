package vn.vibefix.delivery.internal;

import java.util.Objects;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import vn.vibefix.delivery.internal.HandoverScreenshots.Screenshot;
import vn.vibefix.files.FileReadPolicy;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;

/**
 * Who may read a handover's screenshot (FR-32, AD-20): before "Gửi bàn giao" its uploader only; once
 * attached, everyone who reads the Delivery Report, that is the Job's Customer, Mentor, and Crew.
 */
@Component
class HandoverScreenshotFiles implements FileReadPolicy {

	/** Looked up on use: the access check reads {@code job}, which reads {@code files}. */
	private final ObjectProvider<HandoverScreenshots> screenshots;

	private final ObjectProvider<DeliveryAccess> access;

	HandoverScreenshotFiles(ObjectProvider<HandoverScreenshots> screenshots, ObjectProvider<DeliveryAccess> access) {
		this.screenshots = screenshots;
		this.access = access;
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
		if (screenshot.handoverId() == null) {
			return Objects.equals(screenshot.accountId(), caller.accountId());
		}
		try {
			this.access.getObject().reader(screenshot.jobId(), caller);
			return true;
		}
		catch (ApiException refused) {
			return false;
		}
	}

}
