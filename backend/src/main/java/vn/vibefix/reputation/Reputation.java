package vn.vibefix.reputation;

import java.util.UUID;

/** Rating aggregates, defined once (AD-33). */
public interface Reputation {

	/**
	 * {@code reputation.visibleSummary}: the account's visible Ratings only, at most one per
	 * Customer–Mentor pair and per Mentor–Student pair; private feedback never counts.
	 */
	RatingSummary visibleSummary(UUID accountId);

}
