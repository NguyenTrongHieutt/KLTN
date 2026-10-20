package vn.vibefix.operations.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.operations.ExternalOperations;
import vn.vibefix.operations.FailedOperations;
import vn.vibefix.operations.OperationHandler;
import vn.vibefix.operations.OperationRequest;
import vn.vibefix.operations.OperationStatus;
import vn.vibefix.operations.OperationTicket;
import vn.vibefix.shared.IdGenerator;

/** Records, joins and looks up External Operations (AD-6). */
@Component
class JdbcExternalOperations implements ExternalOperations, FailedOperations {

	private final JdbcClient jdbc;

	private final OperationJson json;

	private final OperationHandlers handlers;

	private final IdGenerator ids;

	private final Clock clock;

	JdbcExternalOperations(JdbcClient jdbc, OperationJson json, OperationHandlers handlers, IdGenerator ids,
			Clock clock) {
		this.jdbc = jdbc;
		this.json = json;
		this.handlers = handlers;
		this.ids = ids;
		this.clock = clock;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public OperationTicket request(OperationRequest request) {
		OperationHandler handler = this.handlers.require(request.type());
		String key = request.key();
		String payload = this.json.write(request.payload());
		Timestamp now = Timestamp.from(this.clock.instant());
		int inserted = this.jdbc.sql("""
				INSERT INTO operations.external_operation
				    (id, operation_key, type, lane, priority, status, payload, personal_data, account_id,
				     next_attempt_at, created_at)
				VALUES (:id, :key, :type, :lane, :priority, 'PENDING', CAST(:payload AS jsonb),
				        CAST(:personalData AS jsonb), :accountId, :now, :now)
				ON CONFLICT (operation_key) DO NOTHING
				""")
			.param("id", this.ids.newId())
			.param("key", key)
			.param("type", request.type())
			.param("lane", handler.lane().name())
			.param("priority", request.priority())
			.param("payload", payload)
			.param("personalData", this.json.write(request.personalData()))
			.param("accountId", request.accountId())
			.param("now", now)
			.update();
		if (inserted == 0) {
			boolean samePayload = this.jdbc
				.sql("SELECT payload = CAST(:payload AS jsonb) FROM operations.external_operation WHERE operation_key = :key")
				.param("payload", payload)
				.param("key", key)
				.query(Boolean.class)
				.single();
			if (!samePayload) {
				throw new IllegalStateException(
						"External Operation " + key + " exists with another payload; a payload never changes");
			}
		}
		return find(key).orElseThrow();
	}

	@Override
	public Optional<OperationTicket> find(String key) {
		return this.jdbc.sql("""
				SELECT id, operation_key, status, result::text AS result, error_code
				FROM operations.external_operation WHERE operation_key = :key
				""")
			.param("key", key)
			.query((row, number) -> new OperationTicket(row.getObject("id", UUID.class), row.getString("operation_key"),
					OperationStatus.valueOf(row.getString("status")), this.json.read(row.getString("result")),
					row.getString("error_code")))
			.optional();
	}

	@Override
	public List<FailedOperation> findAll() {
		return this.jdbc.sql("""
				SELECT id, operation_key, type, error_code, attempt_count, completed_at,
				       CASE WHEN payload ->> 'jobId' ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
				            THEN CAST(payload ->> 'jobId' AS UUID) END AS job_id
				FROM operations.external_operation WHERE status = 'FAILED'
				ORDER BY completed_at, id
				""")
			.query((row, number) -> new FailedOperation(row.getObject("id", UUID.class), row.getString("operation_key"),
					row.getString("type"), row.getString("error_code"), row.getInt("attempt_count"),
					instant(row.getTimestamp("completed_at")), row.getObject("job_id", UUID.class)))
			.list();
	}

	@Override
	@Transactional
	public boolean retry(UUID operationId) {
		return this.jdbc.sql("""
				UPDATE operations.external_operation
				SET status = 'PENDING', attempt_count = 0, next_attempt_at = :now, completed_at = NULL, error_code = NULL,
				    read_back_first = TRUE
				WHERE id = :id AND status = 'FAILED'
				""").param("id", operationId).param("now", Timestamp.from(this.clock.instant())).update() == 1;
	}

	private static Instant instant(Timestamp timestamp) {
		return timestamp.toInstant();
	}

}
