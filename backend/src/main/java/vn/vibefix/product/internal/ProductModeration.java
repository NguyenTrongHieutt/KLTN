package vn.vibefix.product.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.product.ProductHidden;
import vn.vibefix.shared.ContentModeration;
import vn.vibefix.shared.IdGenerator;

/**
 * FR-60: a hidden Product, with the reason its Customer reads; {@link ProductHidden} then hides its Open
 * Job Posts. Its running Jobs continue.
 */
@Component
class ProductModeration implements ContentModeration {

	private final JdbcClient jdbc;

	private final Clock clock;

	private final ApplicationEventPublisher events;

	private final IdGenerator ids;

	ProductModeration(JdbcClient jdbc, Clock clock, ApplicationEventPublisher events, IdGenerator ids) {
		this.jdbc = jdbc;
		this.clock = clock;
		this.events = events;
		this.ids = ids;
	}

	@Override
	public String kind() {
		return "PRODUCT";
	}

	@Override
	@Transactional(readOnly = true)
	public List<Item> items(boolean hidden, int limit) {
		return this.jdbc.sql("""
				SELECT id, name, description, customer_account_id AS author_id, created_at, hidden_at, hidden_reason
				FROM product.product WHERE deleted_at IS NULL AND (hidden_at IS NOT NULL) = :hidden
				ORDER BY created_at DESC, id DESC LIMIT :limit
				""").param("hidden", hidden).param("limit", limit).query(ProductModeration::item).list();
	}

	@Override
	@Transactional
	public Optional<Item> hide(UUID id, String reason) {
		Optional<Item> hidden = this.jdbc.sql("""
				UPDATE product.product SET hidden_at = :now, hidden_reason = :reason
				WHERE id = :id AND deleted_at IS NULL AND hidden_at IS NULL
				RETURNING id, name, description, customer_account_id AS author_id, created_at, hidden_at, hidden_reason
				""")
			.param("id", id)
			.param("reason", reason.strip())
			.param("now", Timestamp.from(this.clock.instant()))
			.query(ProductModeration::item)
			.optional();
		hidden.ifPresent(item -> this.events.publishEvent(new ProductHidden(this.ids.newId(), id)));
		return hidden;
	}

	private static Item item(ResultSet rs, int n) throws SQLException {
		Timestamp hiddenAt = rs.getTimestamp("hidden_at");
		return new Item(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("description"), rs.getObject("author_id", UUID.class),
				rs.getTimestamp("created_at").toInstant(), hiddenAt == null ? null : hiddenAt.toInstant(),
				rs.getString("hidden_reason"));
	}

}
