package vn.vibefix.reputation;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Rating aggregates and Portfolio Entries, defined once (AD-33, FR-49, FR-50). */
public interface Reputation {

	/**
	 * {@code reputation.visibleSummary}: the account's visible Ratings in one role only (AD-33): as Mentor, the
	 * Customers'; as Crew member, the Mentors'; as Customer, the Mentors'. At most one counts per rater and ratee, and
	 * private feedback never counts.
	 */
	RatingSummary visibleSummary(UUID accountId, RatingRole role);

	/** {@link #visibleSummary} for a page of accounts in one read, keyed by account ID (FR-14, SG-43). */
	Map<UUID, RatingSummary> visibleSummaries(Collection<UUID> accountIds, RatingRole role);

	/** The account's Portfolio Entries, newest first (FR-50). */
	List<PortfolioEntry> portfolio(UUID accountId);

	/**
	 * Whether the Mentor is new: no Job of theirs closed with Accepted work (FR-14, AD-17), so PUB-4 and the
	 * Profile result card show "Mới".
	 */
	boolean newcomer(UUID mentorId);

	/** Which of these Mentors are new, in one read (FR-14). */
	Set<UUID> newcomers(Collection<UUID> mentorIds);

}
