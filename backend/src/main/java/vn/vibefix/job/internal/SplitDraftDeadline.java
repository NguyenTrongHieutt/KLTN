package vn.vibefix.job.internal;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import vn.vibefix.timers.DeadlineHandler;
import vn.vibefix.timers.DeadlineKind;
import vn.vibefix.timers.DeadlineOutcome;
import vn.vibefix.timers.DueDeadline;

/**
 * {@value #KEY} (FR-83, R11, AD-7, SG-26): with no draft by the draft deadline, the System saves the default version. Its
 * generation is the split, and a draft leaves it {@code OBSOLETE}. Never paused: it reads neither the freeze nor the busy
 * marker, so it answers neither {@code REPAUSE} nor {@code REARM}. Collaborators are looked up on use, since
 * {@code timers} collects its handlers first.
 */
@Component
class SplitDraftDeadline implements DeadlineHandler {

	static final String KEY = "job.split_draft";

	static final DeadlineKind KIND = new DeadlineKind(KEY, false, true);

	private final ObjectProvider<SplitCommands> commands;

	SplitDraftDeadline(ObjectProvider<SplitCommands> commands) {
		this.commands = commands;
	}

	@Override
	public DeadlineKind kind() {
		return KIND;
	}

	@Override
	public DeadlineOutcome handle(DueDeadline deadline) {
		return this.commands.getObject().saveDefault(deadline.subjectId()) ? DeadlineOutcome.FIRED : DeadlineOutcome.OBSOLETE;
	}

}
