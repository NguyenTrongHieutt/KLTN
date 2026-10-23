package vn.vibefix.reputation.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.reputation.PrivateFeedback;
import vn.vibefix.shared.PlatformCounts;

/**
 * ADM-9 (FR-62, FR-78): for each Rating kind its average stars and count, hidden Ratings left out (Story
 * 17.2); and the private-feedback figure, only when it aggregates at least 5 feedbacks across at least 3
 * Mentors. Averages are in tenths of a star; no figure names a person.
 */
@Component
class ReputationCounts implements PlatformCounts {

	private final PrivateFeedback feedback;

	private final JdbcClient jdbc;

	ReputationCounts(PrivateFeedback feedback, JdbcClient jdbc) {
		this.feedback = feedback;
		this.jdbc = jdbc;
	}

	@Override
	@Transactional(readOnly = true)
	public List<Count> counts() {
		List<Count> counts = new ArrayList<>();
		this.jdbc.sql("""
				SELECT kind, count(*) AS ratings, avg(stars) AS average FROM reputation.rating WHERE hidden_at IS NULL
				GROUP BY kind
				""").query((rs, n) -> {
			String kind = rs.getString("kind");
			counts.add(new Count("ratings", kind + "_AVERAGE", tenths(rs.getBigDecimal("average")), Unit.AVERAGE_TENTHS));
			return counts.add(new Count("ratings", kind + "_COUNT", rs.getLong("ratings")));
		}).list();
		this.feedback.statistics().ifPresent(statistics -> {
			counts.add(new Count("privateFeedback", "COUNT", statistics.count()));
			counts.add(new Count("privateFeedback", "AVERAGE_TENTHS",
					statistics.average().multiply(BigDecimal.TEN).longValue(), Unit.AVERAGE_TENTHS));
		});
		return counts;
	}

	private static long tenths(BigDecimal average) {
		return average.multiply(BigDecimal.TEN).setScale(0, RoundingMode.HALF_UP).longValue();
	}

}
