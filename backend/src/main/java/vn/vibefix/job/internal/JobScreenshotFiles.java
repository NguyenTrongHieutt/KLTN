package vn.vibefix.job.internal;

import java.util.UUID;

import org.springframework.stereotype.Component;

import vn.vibefix.files.FileReadPolicy;
import vn.vibefix.identity.GitHubGate;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;

/**
 * Who may read a Job's screenshot (FR-9, FR-12, AD-20): the Job's Customer, by their role in it (AD-43), and,
 * through the GitHub gate of "Tìm việc" (X1, Story 20.3), any unlocked user with GitHub linked while the Job is
 * listed there (AD-36, Story 21.1): not hidden, and Open or recruiting its Crew.
 */
@Component
class JobScreenshotFiles implements FileReadPolicy {

	private final Jobs jobs;

	private final JobPostQueries posts;

	private final GitHubGate mentors;

	private final JobRoleReader roles;

	JobScreenshotFiles(Jobs jobs, JobPostQueries posts, GitHubGate mentors, JobRoleReader roles) {
		this.jobs = jobs;
		this.posts = posts;
		this.mentors = mentors;
		this.roles = roles;
	}

	@Override
	public String purpose() {
		return Jobs.SCREENSHOT_PURPOSE;
	}

	@Override
	public boolean mayRead(UUID fileId, Caller caller) {
		if (this.jobs.screenshotJob(fileId).map(job -> this.roles.roleOf(job, caller).isCustomer()).orElse(false)) {
			return true;
		}
		return caller.isUser() && linked(caller.requireAccountId()) && this.posts.isListedScreenshot(fileId);
	}

	/** The gate reads the account again (AD-19); it runs in no transaction, so its refusal is only an answer. */
	private boolean linked(UUID mentorId) {
		try {
			this.mentors.requireGitHubLinked(mentorId);
			return true;
		}
		catch (ApiException refused) {
			return false;
		}
	}

}
