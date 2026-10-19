package vn.vibefix.reputation;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Rating aggregates and Portfolio Entries, defined once (AD-33, FR-49, FR-50). */
public interface Reputation {

	/**
	 * {@code reputation.visibleSummary}: the account's visible Ratings only, at most one per
	 * Customer–Mentor pair and per Mentor–Student pair; private feedback never counts.
	 */
	RatingSummary visibleSummary(UUID accountId);

	/** {@link #visibleSummary} for a page of accounts in one read, keyed by account ID (FR-14, SG-43). */
	Map<UUID, RatingSummary> visibleSummaries(Collection<UUID> accountIds);

	/** The account's Portfolio Entries, newest first (FR-50). */
	List<PortfolioEntry> portfolio(UUID accountId);

	/**
	 * Whether the Mentor is new: no Job of theirs closed with Accepted work (FR-14, AD-17), so PUB-5
	 * shows "Mới".
	 */
	boolean newcomer(UUID mentorId);

	/** Which of these Mentors are new, in one read (FR-14). */
	Set<UUID> newcomers(Collection<UUID> mentorIds);

}
