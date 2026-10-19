package vn.vibefix.job.internal;

import java.util.UUID;

import org.springframework.stereotype.Component;

import vn.vibefix.files.FileReadPolicy;
import vn.vibefix.identity.MentorGate;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.Role;

/**
 * Who may read a Job's screenshot (FR-9, FR-12, AD-20): its Customer, and an approved Mentor while
 * the Job is an Open Job Post that is not hidden.
 */
@Component
class JobScreenshotFiles implements FileReadPolicy {

	private final Jobs jobs;

	private final JobPostQueries posts;

	private final MentorGate mentors;

	JobScreenshotFiles(Jobs jobs, JobPostQueries posts, MentorGate mentors) {
		this.jobs = jobs;
		this.posts = posts;
		this.mentors = mentors;
	}

	@Override
	public String purpose() {
		return Jobs.SCREENSHOT_PURPOSE;
	}

	@Override
	public boolean mayRead(UUID fileId, Caller caller) {
		if (caller.hasRole(Role.CUSTOMER)) {
			return this.jobs.ownsScreenshot(fileId, caller.requireAccountId());
		}
		return caller.hasRole(Role.MENTOR) && approved(caller.requireAccountId()) && this.posts.isOpenScreenshot(fileId);
	}

	/** The gate reads the account again (AD-19); it runs in no transaction, so its refusal is only an answer. */
	private boolean approved(UUID mentorId) {
		try {
			this.mentors.requireApprovedMentor(mentorId);
			return true;
		}
		catch (ApiException refused) {
			return false;
		}
	}

}
