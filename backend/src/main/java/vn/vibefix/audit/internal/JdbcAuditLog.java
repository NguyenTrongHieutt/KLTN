package vn.vibefix.audit.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.shared.IdGenerator;

/**
 * Writes entries as the runtime role, which may only insert into and read the audit tables; their
 * triggers reject every later change (AD-8).
 */
@Component
class JdbcAuditLog implements AuditLog {

	/** The MDC key that {@code shared}'s correlation-ID filter fills for every request. */
	static final String CORRELATION_ID_KEY = "correlationId";

	private final JdbcClient jdbc;

	private final IdGenerator ids;

	private final Clock clock;

	JdbcAuditLog(JdbcClient jdbc, IdGenerator ids, Clock clock) {
		this.jdbc = jdbc;
		this.ids = ids;
		this.clock = clock;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void record(AuditEntry entry) {
		UUID id = this.ids.newId();
		this.jdbc.sql("""
				INSERT INTO audit.audit_entry (id, occurred_at, actor_type, actor_account_id, action, job_id, correlation_id)
				VALUES (:id, :occurredAt, :actorType, :actorAccountId, :action, :jobId, :correlationId)
				""")
			.param("id", id)
			.param("occurredAt", Timestamp.from(this.clock.instant()))
			.param("actorType", entry.actor().isSystem() ? "SYSTEM" : "USER")
			.param("actorAccountId", entry.actor().accountId())
			.param("action", entry.action())
			.param("jobId", entry.jobId())
			.param("correlationId", correlationId())
			.update();
		List<AuditObject> objects = entry.objects();
		for (int position = 0; position < objects.size(); position++) {
			this.jdbc.sql("""
					INSERT INTO audit.audit_entry_object (entry_id, position, object_type, object_id)
					VALUES (:entryId, :position, :type, :objectId)
					""")
				.param("entryId", id)
				.param("position", position)
				.param("type", objects.get(position).type())
				.param("objectId", objects.get(position).id())
				.update();
		}
	}

	/** The request's correlation ID; none outside a request or when it is not a UUID. */
	private static @Nullable UUID correlationId() {
		String value = MDC.get(CORRELATION_ID_KEY);
		if (value == null) {
			return null;
		}
		try {
			return UUID.fromString(value);
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

}
