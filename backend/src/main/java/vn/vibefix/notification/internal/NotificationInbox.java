package vn.vibefix.notification.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.vibefix.notification.NotificationType;

/**
 * What one account reads of its notifications and to-dos (AD-20): only its own rows, notifications
 * of the last 90 days, open to-dos in the strip's order (AD-27, UX-DR14).
 */
@Component
class NotificationInbox {

	/** A deadline that decides automatically warns this long before (EXPERIENCE.md, Deadline chip). */
	static final Duration NEAR = Duration.ofHours(48);

	/** At most this many open to-dos are listed; the total counts all of them. */
	static final int TODO_LIMIT = 100;

	/** The icon of a type that left the registry. */
	static final String DEFAULT_ICON = "bell";

	private final JdbcClient jdbc;

	private final NotificationRegistry registry;

	private final NotificationJson json;

	private final Clock clock;

	NotificationInbox(JdbcClient jdbc, NotificationRegistry registry, NotificationJson json, Clock clock) {
		this.jdbc = jdbc;
		this.registry = registry;
		this.json = json;
		this.clock = clock;
	}

	/** One listed notification. */
	record NotificationItem(UUID id, String type, String icon, Map<String, String> params, String link, Instant createdAt,
			boolean read) {
	}

	/** One open to-do. */
	record TodoItem(UUID id, String key, int tier, Map<String, String> params, String link, @Nullable Instant deadlineAt,
			boolean deadlineNear, @Nullable UUID jobId, Instant openedAt) {
	}

	List<NotificationItem> notifications(UUID account, int page, int size) {
		return this.jdbc.sql("""
				SELECT id, type, params::text AS params, link, created_at, read_at
				FROM notification.notification
				WHERE account_id = :account AND created_at > :cutoff
				ORDER BY created_at DESC, id DESC
				LIMIT :size OFFSET :offset
				""")
			.param("account", account)
			.param("cutoff", cutoff())
			.param("size", size)
			.param("offset", (long) page * size)
			.query(this::item)
			.list();
	}

	long count(UUID account) {
		return this.jdbc.sql("""
				SELECT count(*) FROM notification.notification WHERE account_id = :account AND created_at > :cutoff
				""").param("account", account).param("cutoff", cutoff()).query(Long.class).single();
	}

	long unread(UUID account) {
		return this.jdbc.sql("""
				SELECT count(*) FROM notification.notification
				WHERE account_id = :account AND read_at IS NULL AND created_at > :cutoff
				""").param("account", account).param("cutoff", cutoff()).query(Long.class).single();
	}

	/** Marks the account's notification read; {@code false} when the account has no such notification. */
	boolean markRead(UUID account, UUID id) {
		return this.jdbc.sql("""
				UPDATE notification.notification SET read_at = COALESCE(read_at, :now)
				WHERE id = :id AND account_id = :account AND created_at > :cutoff
				""")
			.param("now", Timestamp.from(this.clock.instant()))
			.param("id", id)
			.param("account", account)
			.param("cutoff", cutoff())
			.update() == 1;
	}

	void markAllRead(UUID account) {
		this.jdbc.sql("""
				UPDATE notification.notification SET read_at = :now WHERE account_id = :account AND read_at IS NULL
				""").param("now", Timestamp.from(this.clock.instant())).param("account", account).update();
	}

	/**
	 * The account's open to-dos (optionally of one Job): tier 1 and 2 by earliest deadline, tier 2
	 * then and tier 3 by oldest (EXPERIENCE.md, To-do Items).
	 */
	List<TodoItem> todos(UUID account, @Nullable UUID jobId) {
		Instant now = this.clock.instant();
		return this.jdbc.sql("""
				SELECT id, todo_key, tier, params::text AS params, link, deadline_at, job_id, opened_at
				FROM notification.todo
				WHERE account_id = :account AND closed_at IS NULL AND (CAST(:jobId AS uuid) IS NULL OR job_id = :jobId)
				ORDER BY tier,
				         CASE WHEN tier IN (1, 2) THEN deadline_at END ASC NULLS LAST,
				         opened_at, id
				LIMIT :limit
				""")
			.param("account", account)
			.param("jobId", jobId)
			.param("limit", TODO_LIMIT)
			.query((row, number) -> todo(row, now))
			.list();
	}

	long todoCount(UUID account, @Nullable UUID jobId) {
		return this.jdbc.sql("""
				SELECT count(*) FROM notification.todo
				WHERE account_id = :account AND closed_at IS NULL AND (CAST(:jobId AS uuid) IS NULL OR job_id = :jobId)
				""").param("account", account).param("jobId", jobId).query(Long.class).single();
	}

	private Timestamp cutoff() {
		return Timestamp.from(JdbcNotifications.cutoff(this.clock.instant()));
	}

	private NotificationItem item(ResultSet row, int number) throws SQLException {
		String type = row.getString("type");
		String icon = this.registry.notificationType(type).map(NotificationType::icon).orElse(DEFAULT_ICON);
		return new NotificationItem(row.getObject("id", UUID.class), type, icon, this.json.read(row.getString("params")),
				row.getString("link"), row.getTimestamp("created_at").toInstant(), row.getTimestamp("read_at") != null);
	}

	private TodoItem todo(ResultSet row, Instant now) throws SQLException {
		int tier = row.getInt("tier");
		Timestamp deadline = row.getTimestamp("deadline_at");
		Instant deadlineAt = deadline == null ? null : deadline.toInstant();
		boolean near = tier == 1 && deadlineAt != null && !deadlineAt.isAfter(now.plus(NEAR));
		return new TodoItem(row.getObject("id", UUID.class), row.getString("todo_key"), tier,
				this.json.read(row.getString("params")), row.getString("link"), deadlineAt, near,
				row.getObject("job_id", UUID.class), row.getTimestamp("opened_at").toInstant());
	}

}
