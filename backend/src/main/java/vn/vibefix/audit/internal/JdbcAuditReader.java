package vn.vibefix.audit.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditObject;
import vn.vibefix.audit.AuditReader;

/** {@link AuditReader} over {@code audit.audit_entry} and its objects. */
@Component
class JdbcAuditReader implements AuditReader {

	private final JdbcClient jdbc;

	JdbcAuditReader(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	@Transactional(readOnly = true)
	public Page search(@Nullable UUID jobId, @Nullable UUID accountId, int page) {
		StringBuilder where = new StringBuilder(" WHERE TRUE");
		Map<String, Object> params = new HashMap<>();
		if (jobId != null) {
			where.append(" AND (e.job_id = :job OR EXISTS (SELECT 1 FROM audit.audit_entry_object o")
				.append(" WHERE o.entry_id = e.id AND o.object_id = :job))");
			params.put("job", jobId);
		}
		if (accountId != null) {
			where.append(" AND (e.actor_account_id = :account OR EXISTS (SELECT 1 FROM audit.audit_entry_object o")
				.append(" WHERE o.entry_id = e.id AND o.object_id = :account))");
			params.put("account", accountId);
		}
		long total = this.jdbc.sql("SELECT count(*) FROM audit.audit_entry e" + where).params(params).query(Long.class).single();
		int current = Math.max(0, page);
		params.put("size", PAGE_SIZE);
		params.put("offset", current * PAGE_SIZE);
		Map<UUID, Entry> entries = new LinkedHashMap<>();
		this.jdbc.sql("SELECT e.id, e.occurred_at, e.actor_account_id, e.action, e.job_id FROM audit.audit_entry e" + where
				+ " ORDER BY e.occurred_at DESC, e.id DESC LIMIT :size OFFSET :offset")
			.params(params)
			.query((rs, n) -> entries.put(rs.getObject("id", UUID.class),
					new Entry(rs.getObject("id", UUID.class), rs.getTimestamp("occurred_at").toInstant(),
							rs.getObject("actor_account_id", UUID.class), rs.getString("action"), rs.getObject("job_id", UUID.class),
							new ArrayList<>())))
			.list();
		if (!entries.isEmpty()) {
			this.jdbc.sql("""
					SELECT entry_id, object_type, object_id FROM audit.audit_entry_object WHERE entry_id IN (:ids)
					ORDER BY entry_id, position
					""").param("ids", List.copyOf(entries.keySet())).query((rs, n) -> {
				entries.get(rs.getObject("entry_id", UUID.class))
					.objects()
					.add(AuditObject.of(rs.getString("object_type"), rs.getObject("object_id", UUID.class)));
				return null;
			}).list();
		}
		return new Page(entries.values().stream()
			.map(entry -> new Entry(entry.id(), entry.occurredAt(), entry.actorAccountId(), entry.action(), entry.jobId(),
					List.copyOf(entry.objects())))
			.toList(), total, current, PAGE_SIZE);
	}

}
