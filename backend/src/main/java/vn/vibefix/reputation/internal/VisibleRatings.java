package vn.vibefix.reputation.internal;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.vibefix.reputation.PortfolioEntry;
import vn.vibefix.reputation.RatingSummary;
import vn.vibefix.reputation.Reputation;

/**
 * {@link Reputation} (AD-33, FR-49, FR-51): an account's visible summary averages the published Ratings
 * it received, the latest per rater and ratee, to one decimal; a hidden Rating never counts. Portfolio
 * Entries arrive with Story 6.8, so every Mentor is still new.
 */
@Component
class VisibleRatings implements Reputation {

	/** The published Ratings, the latest per rater and ratee (an erased rater counts per Rating). */
	static final String LATEST_PUBLISHED = """
			SELECT DISTINCT ON (coalesce(r.rater_account_id, r.id), r.ratee_account_id) r.ratee_account_id, r.stars
			FROM reputation.rating r JOIN reputation.rating_window w ON w.job_id = r.job_id
			WHERE r.ratee_account_id IN (:ids)
			  AND ((r.kind <> 'MENTOR_TO_STUDENT' AND w.pair_published_at IS NOT NULL)
			    OR (r.kind = 'MENTOR_TO_STUDENT' AND w.crew_published_at IS NOT NULL))
			ORDER BY coalesce(r.rater_account_id, r.id), r.ratee_account_id, r.created_at DESC, r.id DESC
			""";

	private final JdbcClient jdbc;

	VisibleRatings(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public RatingSummary visibleSummary(UUID accountId) {
		return visibleSummaries(List.of(accountId)).get(accountId);
	}

	@Override
	public Map<UUID, RatingSummary> visibleSummaries(Collection<UUID> accountIds) {
		Map<UUID, RatingSummary> summaries = new LinkedHashMap<>();
		accountIds.forEach(id -> summaries.put(id, RatingSummary.NONE));
		if (accountIds.isEmpty()) {
			return summaries;
		}
		this.jdbc.sql("SELECT ratee_account_id, round(avg(stars), 1) AS average, count(*) AS n FROM (" + LATEST_PUBLISHED
				+ ") latest GROUP BY ratee_account_id")
			.param("ids", new LinkedHashSet<>(accountIds))
			.query((rs, n) -> {
				summaries.put(rs.getObject("ratee_account_id", UUID.class),
						new RatingSummary(rs.getObject("average", BigDecimal.class), rs.getInt("n")));
				return null;
			})
			.list();
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
