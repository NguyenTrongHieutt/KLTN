package vn.vibefix.operations.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.operations.Lane;
import vn.vibefix.operations.OperationCompleted;
import vn.vibefix.operations.OperationRun;
import vn.vibefix.operations.OperationStatus;
import vn.vibefix.shared.IdGenerator;

/**
 * The worker's short transactions (AD-6): claiming, completing, and rescheduling. Every change after
 * the claim is fenced by the attempt number, so a worker whose lease expired and whose operation was
 * claimed again changes nothing.
 */
@Component
class OperationStore {

	static final String AUDIT_OBJECT = "operations.external_operation";

	private final JdbcClient jdbc;

	private final OperationJson json;

	private final TransactionTemplate transactions;

	private final AuditLog audit;

	private final ApplicationEventPublisher events;

	private final IdGenerator ids;

	OperationStore(JdbcClient jdbc, OperationJson json, PlatformTransactionManager transactionManager, AuditLog audit,
			ApplicationEventPublisher events, IdGenerator ids) {
		this.jdbc = jdbc;
		this.json = json;
		this.transactions = new TransactionTemplate(transactionManager);
		this.audit = audit;
		this.events = events;
		this.ids = ids;
	}

	/**
	 * One claimed attempt.
	 * @param readBackFirst the previous attempt was ambiguous, or its lease expired
	 * @param lastErrorCode the code of the previous failure, if any
	 */
	record Claimed(UUID id, String key, String type, int attempt, int priority, Map<String, Object> payload,
			@Nullable Map<String, Object> personalData, boolean readBackFirst, @Nullable String lastErrorCode) {

		OperationRun run() {
			return new OperationRun(this.id, this.key, this.type, this.attempt, this.payload, this.personalData);
		}

	}

	/**
	 * Claims up to {@code limit} due operations of {@code lane}, by priority, then due time: pending
	 * ones whose next attempt is due, and running ones whose lease expired, which read back first.
	 * Rows other workers hold are skipped.
	 */
	List<Claimed> claim(Lane lane, Collection<String> types, int limit, Instant now, Instant leaseUntil) {
		if (limit <= 0 || types.isEmpty()) {
			return List.of();
		}
		List<Claimed> claimed = this.transactions.execute(status -> this.jdbc.sql("""
				WITH due AS (
				    SELECT id FROM operations.external_operation
				    WHERE lane = :lane AND type IN (:types)
				      AND ((status = 'PENDING' AND next_attempt_at <= :now) OR (status = 'RUNNING' AND lease_until <= :now))
				    ORDER BY priority, next_attempt_at, id
				    LIMIT :limit
				    FOR UPDATE SKIP LOCKED)
				UPDATE operations.external_operation operation
				SET status = 'RUNNING', attempt_count = operation.attempt_count + 1, lease_until = :leaseUntil,
				    read_back_first = operation.read_back_first OR operation.status = 'RUNNING'
				FROM due WHERE operation.id = due.id
				RETURNING operation.id, operation.operation_key, operation.type, operation.attempt_count,
				          operation.priority, operation.payload::text AS payload,
				          operation.personal_data::text AS personal_data, operation.read_back_first,
				          operation.error_code
				""")
			.param("lane", lane.name())
			.param("types", types)
			.param("now", Timestamp.from(now))
			.param("limit", limit)
			.param("leaseUntil", Timestamp.from(leaseUntil))
			.query((row, number) -> new Claimed(row.getObject("id", UUID.class), row.getString("operation_key"),
					row.getString("type"), row.getInt("attempt_count"), row.getInt("priority"),
					this.json.read(row.getString("payload")), this.json.read(row.getString("personal_data")),
					row.getBoolean("read_back_first"), row.getString("error_code")))
			.list());
		return claimed.stream().sorted(Comparator.comparingInt(Claimed::priority)).toList();
	}

	/**
	 * Completes the attempt in one transaction: the status, the result or error code, the personal-data
	 * purge, the audit entry, and {@link OperationCompleted}.
	 * @return {@code false} when the attempt no longer holds the operation
	 */
	boolean complete(Claimed claimed, OperationStatus status, @Nullable Map<String, Object> result,
			@Nullable String errorCode, Instant now) {
		if (!status.isCompleted()) {
			throw new IllegalArgumentException(status + " is not a completion");
		}
		Boolean completed = this.transactions.execute(transaction -> {
			int updated = this.jdbc.sql("""
					UPDATE operations.external_operation
					SET status = :status, result = CAST(:result AS jsonb), error_code = :errorCode, personal_data = NULL,
					    lease_until = NULL, read_back_first = FALSE, completed_at = :now
					WHERE id = :id AND status = 'RUNNING' AND attempt_count = :attempt
					""")
				.param("status", status.name())
				.param("result", this.json.write(result))
				.param("errorCode", errorCode)
				.param("now", Timestamp.from(now))
				.param("id", claimed.id())
				.param("attempt", claimed.attempt())
				.update();
			if (updated == 0) {
				return false;
			}
			this.audit.record(AuditEntry.of(AuditActor.system(),
					"operations.operation_" + status.name().toLowerCase(), AuditObject.of(AUDIT_OBJECT, claimed.id())));
			this.events.publishEvent(new OperationCompleted(this.ids.newId(), claimed.id(), claimed.key(),
					claimed.type(), status, errorCode));
			return true;
		});
		return Boolean.TRUE.equals(completed);
	}

	/** Puts a failed attempt back in the queue until {@code nextAttemptAt}. */
	boolean retryLater(Claimed claimed, Instant nextAttemptAt, String errorCode, boolean readBackFirst) {
		return reschedule(claimed, nextAttemptAt, errorCode, readBackFirst, 0);
	}

	/** Puts the attempt back without counting it: it was not tried. */
	boolean defer(Claimed claimed, Instant until) {
		return reschedule(claimed, until, claimed.lastErrorCode(), claimed.readBackFirst(), 1);
	}

	private boolean reschedule(Claimed claimed, Instant nextAttemptAt, @Nullable String errorCode,
			boolean readBackFirst, int uncount) {
		Integer updated = this.transactions.execute(status -> this.jdbc.sql("""
				UPDATE operations.external_operation
				SET status = 'PENDING', lease_until = NULL, next_attempt_at = :next, error_code = :errorCode,
				    read_back_first = :readBackFirst, attempt_count = attempt_count - :uncount
				WHERE id = :id AND status = 'RUNNING' AND attempt_count = :attempt
				""")
			.param("next", Timestamp.from(nextAttemptAt))
			.param("errorCode", errorCode)
			.param("readBackFirst", readBackFirst)
			.param("uncount", uncount)
			.param("id", claimed.id())
			.param("attempt", claimed.attempt())
			.update());
		return updated != null && updated == 1;
	}

}
