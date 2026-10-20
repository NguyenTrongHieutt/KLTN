package vn.vibefix.job.internal;

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

import vn.vibefix.job.JobPostHidden;
import vn.vibefix.shared.ContentModeration;
import vn.vibefix.shared.IdGenerator;

/**
 * FR-60, FR-14, AD-36: a hidden Open Job Post leaves browse and search and takes no new Application;
 * {@link JobPostHidden} lets {@code matching} lapse its invitations and tell its applicants.
 */
@Component
class JobPostModeration implements ContentModeration {

	private final JdbcClient jdbc;

	private final Clock clock;

	private final ApplicationEventPublisher events;

	private final IdGenerator ids;

	JobPostModeration(JdbcClient jdbc, Clock clock, ApplicationEventPublisher events, IdGenerator ids) {
		this.jdbc = jdbc;
		this.clock = clock;
		this.events = events;
		this.ids = ids;
	}

	@Override
	public String kind() {
		return "JOB_POST";
	}

	@Override
	@Transactional(readOnly = true)
	public List<Item> items(boolean hidden, int limit) {
		return this.jdbc.sql("""
				SELECT id, title, problem, customer_account_id AS author_id, opened_at AS created_at, hidden_at, hidden_reason
				FROM job.job WHERE state = 'OPEN' AND (hidden_at IS NOT NULL) = :hidden
				ORDER BY opened_at DESC, id DESC LIMIT :limit
				""").param("hidden", hidden).param("limit", limit).query(JobPostModeration::item).list();
	}

	@Override
	@Transactional
	public Optional<Item> hide(UUID id, String reason) {
		Optional<Item> hidden = this.jdbc.sql("""
				UPDATE job.job SET hidden_at = :now, hidden_reason = :reason, version = version + 1, updated_at = :now
				WHERE id = :id AND state = 'OPEN' AND hidden_at IS NULL
				RETURNING id, title, problem, customer_account_id AS author_id, opened_at AS created_at, hidden_at, hidden_reason
				""")
			.param("id", id)
			.param("reason", reason.strip())
			.param("now", Timestamp.from(this.clock.instant()))
			.query(JobPostModeration::item)
			.optional();
		hidden.ifPresent(item -> this.events.publishEvent(new JobPostHidden(this.ids.newId(), id)));
		return hidden;
	}

	private static Item item(ResultSet rs, int n) throws SQLException {
		Timestamp hiddenAt = rs.getTimestamp("hidden_at");
		return new Item(rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("problem"), rs.getObject("author_id", UUID.class),
				rs.getTimestamp("created_at").toInstant(), hiddenAt == null ? null : hiddenAt.toInstant(),
				rs.getString("hidden_reason"));
	}

}
