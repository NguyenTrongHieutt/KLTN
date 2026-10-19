package vn.vibefix.reputation.internal;

import java.util.UUID;

import org.springframework.stereotype.Component;

import vn.vibefix.reputation.RatingSummary;
import vn.vibefix.reputation.Reputation;

/**
 * {@link Reputation} before Ratings exist: they are given in a Job's Rating Window (FR-49), which
 * opens at J9, and no Job reaches it yet. The Rating story replaces this with the query on its table.
 */
@Component
class VisibleRatings implements Reputation {

	@Override
	public RatingSummary visibleSummary(UUID accountId) {
		return RatingSummary.NONE;
	}

}
