package vn.vibefix.reputation.internal;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import vn.vibefix.timers.DeadlineHandler;
import vn.vibefix.timers.DeadlineKind;
import vn.vibefix.timers.DeadlineOutcome;
import vn.vibefix.timers.DueDeadline;

/**
 * {@value #KEY} (FR-49, FR-50, AD-7): the Rating Window ends after the Job's copied days; every Rating is
 * then published, a Portfolio summary not drafted or not decided lapses, and the to-dos close. Not
 * pausable (AD-7 Kinds). Collaborators are looked up on use.
 */
@Component
class RatingWindowEnd implements DeadlineHandler {

	static final String KEY = "reputation.rating_window";

	static final DeadlineKind KIND = new DeadlineKind(KEY, false, true);

	private final ObjectProvider<Ratings> ratings;

	private final ObjectProvider<Portfolios> portfolios;

	RatingWindowEnd(ObjectProvider<Ratings> ratings, ObjectProvider<Portfolios> portfolios) {
		this.ratings = ratings;
		this.portfolios = portfolios;
	}

	@Override
	public DeadlineKind kind() {
		return KIND;
	}

	@Override
	public DeadlineOutcome handle(DueDeadline deadline) {
		boolean ended = this.ratings.getObject().end(deadline.subjectId());
		boolean lapsed = this.portfolios.getObject().lapse(deadline.subjectId());
		return ended || lapsed ? DeadlineOutcome.FIRED : DeadlineOutcome.OBSOLETE;
	}

}
