package vn.vibefix.job.internal;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import vn.vibefix.timers.DeadlineHandler;
import vn.vibefix.timers.DeadlineKind;
import vn.vibefix.timers.DeadlineOutcome;
import vn.vibefix.timers.DueDeadline;

/**
 * {@value #KEY} (FR-83, AD-7, AD-29, A-09): the latest version becomes final, however late this runs. Its generation is
 * {@code {versionNo}:{round}}, so a newer save leaves it {@code OBSOLETE}, as does {@code IN_RESOLUTION}, after which a
 * withdrawal starts a new round. Never paused: it reads neither the freeze nor the busy marker, so it answers neither
 * {@code REPAUSE} nor {@code REARM}. Collaborators are looked up on use, since {@code timers} collects its handlers first.
 */
@Component
class SplitConfirmationDeadline implements DeadlineHandler {

	static final String KEY = "job.split_confirmation";

	static final DeadlineKind KIND = new DeadlineKind(KEY, false, true);

	private final ObjectProvider<SplitCommands> commands;

	SplitConfirmationDeadline(ObjectProvider<SplitCommands> commands) {
		this.commands = commands;
	}

	@Override
	public DeadlineKind kind() {
		return KIND;
	}

	@Override
	public DeadlineOutcome handle(DueDeadline deadline) {
		return this.commands.getObject().finalizeAtDeadline(deadline.subjectId(), deadline.generation())
				? DeadlineOutcome.FIRED : DeadlineOutcome.OBSOLETE;
	}

}
