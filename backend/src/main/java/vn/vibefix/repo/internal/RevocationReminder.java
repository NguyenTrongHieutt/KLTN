package vn.vibefix.repo.internal;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import vn.vibefix.timers.DeadlineHandler;
import vn.vibefix.timers.DeadlineKind;
import vn.vibefix.timers.DeadlineOutcome;
import vn.vibefix.timers.DueDeadline;

/**
 * {@value #KEY} (FR-66, AD-7): 1 and 7 days after the Revocation Checklist opened, a Customer with
 * unticked items is reminded ({@value RevocationChecklists#REMINDER}). Not pausable: a freeze changes
 * nothing about access the Customer gave. With every item ticked it is {@code OBSOLETE}. Collaborators are
 * looked up on use.
 */
@Component
class RevocationReminder implements DeadlineHandler {

	static final String KEY = "repo.revocation_checklist_reminder";

	static final DeadlineKind KIND = new DeadlineKind(KEY, false, true);

	private final ObjectProvider<RevocationChecklists> checklists;

	RevocationReminder(ObjectProvider<RevocationChecklists> checklists) {
		this.checklists = checklists;
	}

	@Override
	public DeadlineKind kind() {
		return KIND;
	}

	@Override
	public DeadlineOutcome handle(DueDeadline deadline) {
		return this.checklists.getObject().remind(deadline.subjectId(), deadline.generation()) ? DeadlineOutcome.FIRED
				: DeadlineOutcome.OBSOLETE;
	}

}
