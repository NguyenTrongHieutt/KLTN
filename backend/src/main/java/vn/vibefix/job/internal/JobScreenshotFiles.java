package vn.vibefix.job.internal;

import java.util.UUID;

import org.springframework.stereotype.Component;

import vn.vibefix.files.FileReadPolicy;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.Role;

/**
 * Who may read a Job's screenshot (FR-9, AD-20): its Customer. Story 3.2 adds approved Mentors while
 * the Job is published.
 */
@Component
class JobScreenshotFiles implements FileReadPolicy {

	private final Jobs jobs;

	JobScreenshotFiles(Jobs jobs) {
		this.jobs = jobs;
	}

	@Override
	public String purpose() {
		return Jobs.SCREENSHOT_PURPOSE;
	}

	@Override
	public boolean mayRead(UUID fileId, Caller caller) {
		return caller.hasRole(Role.CUSTOMER) && this.jobs.ownsScreenshot(fileId, caller.requireAccountId());
	}

}
