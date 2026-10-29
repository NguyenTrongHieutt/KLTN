package vn.vibefix.matching.internal;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import vn.vibefix.timers.DeadlineHandler;
import vn.vibefix.timers.DeadlineKind;
import vn.vibefix.timers.DeadlineOutcome;
import vn.vibefix.timers.DueDeadline;

/**
 * {@value #KEY} (FR-61, FR-81, R6, AD-7, Story 21.4): a Crew Application still pending when the Job's copied
 * {@code CREW_APPLICATION_LAPSE_DAYS} run out lapses, and its applicant learns why. Not pausable: a Dispute freezing the
 * Job does not hold an applicant's wait. The generation is the application, so one that already ended leaves the
 * Deadline {@code OBSOLETE}. Collaborators are looked up on use: {@code timers} collects its handlers before the
 * commands exist.
 */
@Component
class CrewApplicationLapse implements DeadlineHandler {

	static final String KEY = "matching.crew_application_lapse";

	static final DeadlineKind KIND = new DeadlineKind(KEY, false, true);

	private final ObjectProvider<CrewApplications> applications;

	CrewApplicationLapse(ObjectProvider<CrewApplications> applications) {
		this.applications = applications;
	}

	@Override
	public DeadlineKind kind() {
		return KIND;
	}

	@Override
	public DeadlineOutcome handle(DueDeadline deadline) {
		return this.applications.getObject().lapse(deadline.subjectId()) ? DeadlineOutcome.FIRED : DeadlineOutcome.OBSOLETE;
	}

}
