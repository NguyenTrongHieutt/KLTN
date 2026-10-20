package vn.vibefix.reputation.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.shared.ContentModeration;

/**
 * FR-60, FR-49: a hidden Rating, with the reason its rater reads; no screen or API shows it and no
 * summary counts it.
 */
@Component
class RatingModeration implements ContentModeration {

	private final JdbcClient jdbc;

	private final Clock clock;

	RatingModeration(JdbcClient jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Override
	public String kind() {
		return "RATING";
	}

	@Override
	@Transactional(readOnly = true)
	public List<Item> items(boolean hidden, int limit) {
		return this.jdbc.sql("""
				SELECT r.id, r.stars || ' sao · ' || w.job_title AS title, r.comment AS excerpt, r.rater_account_id AS author_id,
				       r.created_at, r.hidden_at, r.hidden_reason
				FROM reputation.rating r JOIN reputation.rating_window w ON w.job_id = r.job_id
				WHERE (r.hidden_at IS NOT NULL) = :hidden ORDER BY r.created_at DESC, r.id DESC LIMIT :limit
				""").param("hidden", hidden).param("limit", limit).query(RatingModeration::item).list();
	}

	@Override
	@Transactional
	public Optional<Item> hide(UUID id, String reason) {
		Optional<Item> hidden = this.jdbc.sql("""
				UPDATE reputation.rating r SET hidden_at = :now, hidden_reason = :reason
				FROM reputation.rating_window w
				WHERE r.id = :id AND r.hidden_at IS NULL AND w.job_id = r.job_id
				RETURNING r.id, r.stars || ' sao · ' || w.job_title AS title, r.comment AS excerpt, r.rater_account_id AS author_id,
				          r.created_at, r.hidden_at, r.hidden_reason
				""")
			.param("id", id)
			.param("reason", reason.strip())
			.param("now", Timestamp.from(this.clock.instant()))
			.query(RatingModeration::item)
			.optional();
		return hidden;
	}

	private static Item item(ResultSet rs, int n) throws SQLException {
		Timestamp hiddenAt = rs.getTimestamp("hidden_at");
		return new Item(rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("excerpt"), rs.getObject("author_id", UUID.class),
				rs.getTimestamp("created_at").toInstant(), hiddenAt == null ? null : hiddenAt.toInstant(),
				rs.getString("hidden_reason"));
	}

}
