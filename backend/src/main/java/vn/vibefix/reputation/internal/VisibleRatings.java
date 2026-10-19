package vn.vibefix.reputation.internal;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import vn.vibefix.reputation.PortfolioEntry;
import vn.vibefix.reputation.RatingSummary;
import vn.vibefix.reputation.Reputation;

/**
 * {@link Reputation} before Ratings and Portfolio Entries exist: both come from J9 (FR-49, FR-50),
 * which no Job reaches yet, so every account has none and every Mentor is new. The Rating and
 * Portfolio stories replace this with the queries on their tables.
 */
@Component
class VisibleRatings implements Reputation {

	@Override
	public RatingSummary visibleSummary(UUID accountId) {
		return RatingSummary.NONE;
	}

	@Override
	public Map<UUID, RatingSummary> visibleSummaries(Collection<UUID> accountIds) {
		Map<UUID, RatingSummary> summaries = new LinkedHashMap<>();
		accountIds.forEach(id -> summaries.put(id, RatingSummary.NONE));
		return summaries;
	}

	@Override
	public List<PortfolioEntry> portfolio(UUID accountId) {
		return List.of();
	}

	@Override
	public boolean newcomer(UUID mentorId) {
		return portfolio(mentorId).isEmpty();
	}

	@Override
	public Set<UUID> newcomers(Collection<UUID> mentorIds) {
		return new LinkedHashSet<>(mentorIds);
	}

}
