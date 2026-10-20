package vn.vibefix.work.internal;

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
 * FR-60: a hidden Page, with the reason its creator reads; {@link Pages#SHOWN} keeps it, and its
 * children, from every reader.
 */
@Component
class PageModeration implements ContentModeration {

	private final JdbcClient jdbc;

	private final Clock clock;

	PageModeration(JdbcClient jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Override
	public String kind() {
		return "PAGE";
	}

	@Override
	@Transactional(readOnly = true)
	public List<Item> items(boolean hidden, int limit) {
		return this.jdbc.sql("""
				SELECT p.id, p.title, left(v.body, 200) AS excerpt, p.created_by_account_id AS author_id, p.created_at,
				       p.hidden_at, p.hidden_reason
				FROM work.page p JOIN work.page_version v ON v.page_id = p.id AND v.version_no = p.version_no
				WHERE (p.hidden_at IS NOT NULL) = :hidden ORDER BY p.created_at DESC, p.id DESC LIMIT :limit
				""").param("hidden", hidden).param("limit", limit).query(PageModeration::item).list();
	}

	@Override
	@Transactional
	public Optional<Item> hide(UUID id, String reason) {
		Optional<Item> hidden = this.jdbc.sql("""
				UPDATE work.page SET hidden_at = :now, hidden_reason = :reason WHERE id = :id AND hidden_at IS NULL
				RETURNING id, title, NULL AS excerpt, created_by_account_id AS author_id, created_at, hidden_at, hidden_reason
				""")
			.param("id", id)
			.param("reason", reason.strip())
			.param("now", Timestamp.from(this.clock.instant()))
			.query(PageModeration::item)
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
