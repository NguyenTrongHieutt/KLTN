package vn.vibefix.job.internal;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import vn.vibefix.job.internal.JobLifecycle.Actor;
import vn.vibefix.timers.DeadlineHandler;
import vn.vibefix.timers.DeadlineKind;
import vn.vibefix.timers.DeadlineOutcome;
import vn.vibefix.timers.DueDeadline;

/**
 * {@value #KEY} (FR-9, AD-7): an Open Job Post nobody was selected for in its copied selection period
 * returns to Draft through J2. Not pausable, since a Job is never frozen while Open. The generation is
 * the Job's version after J1, so a withdrawn, republished, or selected Job leaves the Deadline
 * {@code OBSOLETE}. The lifecycle is looked up on use: {@code timers} collects its handlers when it is
 * built, before the commands that schedule Deadlines exist.
 */
@Component
class JobSelectionLapse implements DeadlineHandler {

	static final String KEY = "job.selection_lapse";

	static final DeadlineKind KIND = new DeadlineKind(KEY, false, true);

	private final ObjectProvider<JobLifecycle> lifecycle;

	JobSelectionLapse(ObjectProvider<JobLifecycle> lifecycle) {
		this.lifecycle = lifecycle;
	}

	@Override
	public DeadlineKind kind() {
		return KIND;
	}

	@Override
	public DeadlineOutcome handle(DueDeadline deadline) {
		return this.lifecycle.getObject()
			.tryApply(Transition.J2, deadline.subjectId(), Actor.SYSTEM, Trigger.AUTO, Long.parseLong(deadline.generation()))
			.isPresent() ? DeadlineOutcome.FIRED : DeadlineOutcome.OBSOLETE;
	}

}
