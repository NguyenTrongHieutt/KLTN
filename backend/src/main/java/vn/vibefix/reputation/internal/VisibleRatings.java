package vn.vibefix.reputation.internal;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.vibefix.product.ProductDirectory;
import vn.vibefix.reputation.PortfolioEntry;
import vn.vibefix.reputation.RatingSummary;
import vn.vibefix.reputation.Reputation;
import vn.vibefix.reputation.internal.Portfolios.Entry;
import vn.vibefix.reputation.internal.Portfolios.Status;
import vn.vibefix.reputation.internal.Portfolios.Summary;

/**
 * {@link Reputation} (AD-33, FR-49, FR-51): an account's visible summary averages the published Ratings
 * it received, the latest per rater and ratee, to one decimal; a hidden Rating never counts. Its
 * portfolio is its published Portfolio Entries (FR-50), each with the summary the Customer approved, the
 * Product name only if allowed, and its Rating once visible; a Mentor with any entry is not new (FR-14).
 */
@Component
class VisibleRatings implements Reputation {

	/** The published Ratings, the latest per rater and ratee (an erased rater counts per Rating). */
	static final String LATEST_PUBLISHED = """
			SELECT DISTINCT ON (coalesce(r.rater_account_id, r.id), r.ratee_account_id) r.ratee_account_id, r.stars
			FROM reputation.rating r JOIN reputation.rating_window w ON w.job_id = r.job_id
			WHERE r.ratee_account_id IN (:ids) AND r.hidden_at IS NULL
			  AND ((r.kind <> 'MENTOR_TO_STUDENT' AND w.pair_published_at IS NOT NULL)
			    OR (r.kind = 'MENTOR_TO_STUDENT' AND w.crew_published_at IS NOT NULL))
			ORDER BY coalesce(r.rater_account_id, r.id), r.ratee_account_id, r.created_at DESC, r.id DESC
			""";

	private final JdbcClient jdbc;

	private final Portfolios portfolios;

	private final SkillNames skills;

	private final ProductDirectory products;

	VisibleRatings(JdbcClient jdbc, Portfolios portfolios, SkillNames skills, ProductDirectory products) {
		this.jdbc = jdbc;
		this.portfolios = portfolios;
		this.skills = skills;
		this.products = products;
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
		List<Entry> entries = this.portfolios.published(accountId);
		if (entries.isEmpty()) {
			return List.of();
		}
		// FR-60: a hidden Product is no longer named on portfolio entries.
		Map<UUID, UUID> productOf = new HashMap<>();
		this.jdbc.sql("SELECT job_id, product_id FROM reputation.portfolio_entry WHERE account_id = :account")
			.param("account", accountId)
			.query((rs, n) -> productOf.put(rs.getObject("job_id", UUID.class), rs.getObject("product_id", UUID.class)))
			.list();
		Set<UUID> hiddenProducts = this.products.hidden(new HashSet<>(productOf.values()));
		Map<UUID, Summary> summaries = new HashMap<>();
		this.portfolios.summaries(entries.stream().map(Entry::jobId).toList())
			.forEach(summary -> summaries.put(summary.jobId(), summary));
		Map<UUID, Integer> ratings = new HashMap<>();
		this.jdbc.sql("""
				SELECT r.job_id, r.stars FROM reputation.rating r JOIN reputation.rating_window w ON w.job_id = r.job_id
				WHERE r.ratee_account_id = :account AND r.kind IN ('CUSTOMER_TO_MENTOR', 'MENTOR_TO_STUDENT') AND r.hidden_at IS NULL
				  AND ((r.kind <> 'MENTOR_TO_STUDENT' AND w.pair_published_at IS NOT NULL)
				    OR (r.kind = 'MENTOR_TO_STUDENT' AND w.crew_published_at IS NOT NULL))
				""").param("account", accountId).query((rs, n) -> {
			ratings.put(rs.getObject("job_id", UUID.class), rs.getInt("stars"));
			return null;
		}).list();
		return entries.stream().map(entry -> {
			Summary summary = summaries.get(entry.jobId());
			boolean approved = summary != null && summary.status() == Status.APPROVED;
			String product = approved && summary.showProductName() && !entry.productName().isEmpty()
					&& !hiddenProducts.contains(productOf.get(entry.jobId())) ? entry.productName() : null;
			return new PortfolioEntry(entry.jobTitle(), entry.role(), entry.jobTypes(), this.skills.namesOf(entry.skillIds()),
					entry.closedAt(), entry.milestones(), entry.workItemsApproved(), entry.milestonesHandedOver(),
					entry.reviewsWritten(), ratings.get(entry.jobId()), approved ? summary.text() : null, product);
		}).toList();
	}

	@Override
	public boolean newcomer(UUID mentorId) {
		return newcomers(List.of(mentorId)).contains(mentorId);
	}

	@Override
	public Set<UUID> newcomers(Collection<UUID> mentorIds) {
		Set<UUID> newcomers = new LinkedHashSet<>(mentorIds);
		newcomers.removeAll(this.portfolios.withEntries(mentorIds));
		return newcomers;
	}

}
