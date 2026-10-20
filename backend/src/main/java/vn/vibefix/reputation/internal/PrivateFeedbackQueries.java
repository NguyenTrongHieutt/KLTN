package vn.vibefix.reputation.internal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.vibefix.reputation.PrivateFeedback;

/**
 * {@link PrivateFeedback} (FR-78, FR-62): only Administrator surfaces call it (Story 10.1). The
 * statistics stay empty below 5 feedbacks across 3 Mentors, so no figure points to one author.
 */
@Component
class PrivateFeedbackQueries implements PrivateFeedback {

	static final int THRESHOLD_COUNT = 3;

	static final BigDecimal THRESHOLD_AVERAGE = new BigDecimal("3");

	static final int STATISTICS_COUNT = 5;

	static final int STATISTICS_MENTORS = 3;

	private final JdbcClient jdbc;

	PrivateFeedbackQueries(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<Summary> summary(UUID mentorId) {
		List<Summary> summaries = summaries("WHERE mentor_account_id = :mentor", mentorId);
		return summaries.stream().findFirst();
	}

	@Override
	public boolean enoughToAct(UUID mentorId) {
		return this.jdbc.sql("""
				SELECT count(*) >= 3 AND count(DISTINCT student_account_id) >= 2 AND count(DISTINCT job_id) >= 2
				FROM reputation.private_feedback WHERE mentor_account_id = :mentor
				""").param("mentor", mentorId).query(Boolean.class).single();
	}

	@Override
	public List<Summary> belowThreshold() {
		return summaries("", null).stream()
			.filter(summary -> summary.count() >= THRESHOLD_COUNT && summary.average().compareTo(THRESHOLD_AVERAGE) < 0)
			.toList();
	}

	@Override
	public Optional<Statistics> statistics() {
		return this.jdbc.sql("""
				SELECT round(avg(stars), 1) AS average, count(*) AS n, count(DISTINCT mentor_account_id) AS mentors
				FROM reputation.private_feedback
				""").query((rs, n) -> new Statistics(rs.getObject("average", BigDecimal.class), rs.getInt("n"), rs.getInt("mentors")))
			.optional()
			.filter(stats -> stats.count() >= STATISTICS_COUNT && stats.mentors() >= STATISTICS_MENTORS);
	}

	private List<Summary> summaries(String where, UUID mentorId) {
		var statement = this.jdbc.sql("""
				SELECT mentor_account_id, round(avg(stars), 1) AS average, count(*) AS n,
				       coalesce(array_agg(comment ORDER BY created_at, id) FILTER (WHERE comment IS NOT NULL), '{}') AS comments
				FROM reputation.private_feedback
				""" + where + """

				GROUP BY mentor_account_id ORDER BY mentor_account_id
				""");
		if (mentorId != null) {
			statement = statement.param("mentor", mentorId);
		}
		return statement.query((rs, n) -> {
			List<String> comments = new ArrayList<>();
			for (Object comment : (Object[]) rs.getArray("comments").getArray()) {
				comments.add((String) comment);
			}
			return new Summary(rs.getObject("mentor_account_id", UUID.class), rs.getObject("average", BigDecimal.class),
					rs.getInt("n"), List.copyOf(comments));
		}).list();
	}

}
