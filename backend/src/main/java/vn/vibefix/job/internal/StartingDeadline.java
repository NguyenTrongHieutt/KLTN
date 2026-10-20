package vn.vibefix.job.internal;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import vn.vibefix.timers.DeadlineHandler;
import vn.vibefix.timers.DeadlineKind;
import vn.vibefix.timers.DeadlineOutcome;
import vn.vibefix.timers.DueDeadline;

/**
 * {@value #KEY} (FR-40, AD-7): the Job did not reach Active within 7 days of J6, so either side may cancel alone. Pausable: a frozen Job pauses it.
 * A Job that moved on leaves it {@code OBSOLETE}. Collaborators are looked up on use.
 */
@Component
class StartingDeadline implements DeadlineHandler {

	static final String KEY = "job.starting_deadline";

	static final DeadlineKind KIND = new DeadlineKind(KEY, true, true);

	private final ObjectProvider<StallDeadlines> stalls;

	private final ObjectProvider<Jobs> jobs;

	StartingDeadline(ObjectProvider<StallDeadlines> stalls, ObjectProvider<Jobs> jobs) {
		this.stalls = stalls;
		this.jobs = jobs;
	}

	@Override
	public DeadlineKind kind() {
		return KIND;
	}

	@Override
	public DeadlineOutcome handle(DueDeadline deadline) {
		if (this.jobs.getObject().lock(deadline.subjectId()).map(job -> job.frozenAt() != null).orElse(false)) {
			return DeadlineOutcome.REPAUSE;
		}
		return this.stalls.getObject().passed(deadline.subjectId(), JobState.STARTING) ? DeadlineOutcome.FIRED
				: DeadlineOutcome.OBSOLETE;
	}

}
