package vn.vibefix.job.internal;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import vn.vibefix.timers.DeadlineHandler;
import vn.vibefix.timers.DeadlineKind;
import vn.vibefix.timers.DeadlineOutcome;
import vn.vibefix.timers.DueDeadline;

/**
 * {@value #KEY} (SG-26): one day before the draft deadline the Mentor is reminded; a draft leaves it {@code OBSOLETE}. It
 * is not scheduled when the period is one day, and never paused. Collaborators are looked up on use, since {@code timers}
 * collects its handlers first.
 */
@Component
class SplitDraftReminder implements DeadlineHandler {

	static final String KEY = "job.split_draft_reminder";

	static final DeadlineKind KIND = new DeadlineKind(KEY, false, true);

	private final ObjectProvider<SplitCommands> commands;

	SplitDraftReminder(ObjectProvider<SplitCommands> commands) {
		this.commands = commands;
	}

	@Override
	public DeadlineKind kind() {
		return KIND;
	}

	@Override
	public DeadlineOutcome handle(DueDeadline deadline) {
		return this.commands.getObject().remindDraft(deadline.subjectId(), deadline.id()) ? DeadlineOutcome.FIRED
				: DeadlineOutcome.OBSOLETE;
	}

}
