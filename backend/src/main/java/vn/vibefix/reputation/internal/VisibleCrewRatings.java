package vn.vibefix.reputation.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.reputation.CrewRatings;

/** {@link CrewRatings} over the published, unhidden {@code MENTOR_TO_STUDENT} Ratings. */
@Component
class VisibleCrewRatings implements CrewRatings {

	private static final String VISIBLE = """
			SELECT r.id, r.job_id, w.job_title, r.rater_account_id, r.ratee_account_id, r.stars, w.crew_published_at
			FROM reputation.rating r JOIN reputation.rating_window w ON w.job_id = r.job_id
			WHERE r.kind = 'MENTOR_TO_STUDENT' AND r.hidden_at IS NULL AND w.crew_published_at IS NOT NULL
			""";

	private final JdbcClient jdbc;

	VisibleCrewRatings(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	@Transactional(readOnly = true)
	public List<CrewRating> receivedBy(UUID studentId) {
		return this.jdbc.sql(VISIBLE + " AND r.ratee_account_id = :id ORDER BY w.crew_published_at DESC, r.id")
			.param("id", studentId)
			.query(VisibleCrewRatings::rating)
			.list();
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<CrewRating> find(UUID ratingId) {
		return this.jdbc.sql(VISIBLE + " AND r.id = :id").param("id", ratingId).query(VisibleCrewRatings::rating).optional();
	}

	private static CrewRating rating(ResultSet rs, int n) throws SQLException {
		return new CrewRating(rs.getObject("id", UUID.class), rs.getObject("job_id", UUID.class), rs.getString("job_title"),
				rs.getObject("rater_account_id", UUID.class), rs.getObject("ratee_account_id", UUID.class), rs.getInt("stars"),
				rs.getTimestamp("crew_published_at").toInstant());
	}

}
