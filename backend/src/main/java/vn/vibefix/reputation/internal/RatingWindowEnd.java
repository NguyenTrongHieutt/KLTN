package vn.vibefix.reputation.internal;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import vn.vibefix.timers.DeadlineHandler;
import vn.vibefix.timers.DeadlineKind;
import vn.vibefix.timers.DeadlineOutcome;
import vn.vibefix.timers.DueDeadline;

/**
 * {@value #KEY} (FR-49, AD-7): the Rating Window ends after the Job's copied days; every Rating is then
 * published and the to-dos close. Not pausable (AD-7 Kinds). Collaborators are looked up on use.
 */
@Component
class RatingWindowEnd implements DeadlineHandler {

	static final String KEY = "reputation.rating_window";

	static final DeadlineKind KIND = new DeadlineKind(KEY, false, true);

	private final ObjectProvider<Ratings> ratings;

	RatingWindowEnd(ObjectProvider<Ratings> ratings) {
		this.ratings = ratings;
	}

	@Override
	public DeadlineKind kind() {
		return KIND;
	}

	@Override
	public DeadlineOutcome handle(DueDeadline deadline) {
		return this.ratings.getObject().end(deadline.subjectId()) ? DeadlineOutcome.FIRED : DeadlineOutcome.OBSOLETE;
	}

}
