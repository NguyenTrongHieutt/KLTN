package vn.vibefix.scanning.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.notification.Todos;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;

/**
 * Resolving Findings (FR-8, AD-18): who may resolve each one, and the rotation a resolver confirms.
 * An Intake Finding's resolver is its Product's Customer, written when the Finding opens; later
 * epics replace the resolvers of their own Findings. Each resolution is audited and closes the
 * Finding's resolve to-do.
 */
@Component
class FindingResolutions {

	/** The to-do every Intake Finding opens for its Customer (UX-DR15, tier 2). */
	static final String RESOLVE_INTAKE_FINDING = "scanning.resolve_intake_finding";

	private final JdbcClient jdbc;

	private final AuditLog auditLog;

	private final Todos todos;

	private final Clock clock;

	FindingResolutions(JdbcClient jdbc, AuditLog auditLog, Todos todos, Clock clock) {
		this.jdbc = jdbc;
		this.auditLog = auditLog;
		this.todos = todos;
		this.clock = clock;
	}

	boolean mayResolve(UUID findingId, UUID accountId) {
		return this.jdbc.sql("SELECT count(*) FROM scanning.finding_resolver WHERE finding_id = :f AND account_id = :a")
			.param("f", findingId)
			.param("a", accountId)
			.query(Integer.class)
			.single() > 0;
	}

	private record Row(String category, String status) {
	}

	/** The open Finding a resolver acts on, locked. */
	private Row lockOpen(UUID productId, UUID findingId, UUID accountId) {
		Row row = this.jdbc.sql("""
				SELECT category, status FROM scanning.finding WHERE id = :id AND product_id = :product FOR UPDATE
				""")
			.param("id", findingId)
			.param("product", productId)
			.query((rs, n) -> new Row(rs.getString("category"), rs.getString("status")))
			.optional()
			.filter(found -> mayResolve(findingId, accountId))
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (!"OPEN".equals(row.status())) {
			throw new ApiException(ErrorCode.CONFLICT, ScanningReason.FINDING_CLOSED);
		}
		return row;
	}

	@Transactional
	void markFalsePositive(UUID productId, UUID findingId, UUID accountId, String reason) {
		lockOpen(productId, findingId, accountId);
		this.jdbc.sql("""
				UPDATE scanning.finding SET status = 'FALSE_POSITIVE', resolution = 'FALSE_POSITIVE',
				    resolution_reason = :reason, resolved_by_account_id = :account, resolved_at = :now
				WHERE id = :id
				""")
			.param("id", findingId)
			.param("reason", reason)
			.param("account", accountId)
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
		this.auditLog.record(AuditEntry.of(AuditActor.user(accountId), "scanning.finding_marked_false_positive",
				AuditObject.of("scanning.finding", findingId)));
		this.todos.closeAll(RESOLVE_INTAKE_FINDING, findingId);
	}

	@Transactional
	void confirmRotation(UUID productId, UUID findingId, UUID accountId) {
		Row row = lockOpen(productId, findingId, accountId);
		if (!"CREDENTIAL".equals(row.category())) {
			throw new ApiException(ErrorCode.GUARD_FAILED, ScanningReason.NOT_A_CREDENTIAL);
		}
		Instant now = this.clock.instant();
		this.jdbc.sql("""
				UPDATE scanning.finding SET status = 'RESOLVED', resolution = 'ROTATED', resolved_by_account_id = :account,
				    resolved_at = :now
				WHERE id = :id
				""").param("id", findingId).param("account", accountId).param("now", Timestamp.from(now)).update();
		this.auditLog.record(AuditEntry.of(AuditActor.user(accountId), "scanning.finding_rotated",
				AuditObject.of("scanning.finding", findingId)));
		this.todos.closeAll(RESOLVE_INTAKE_FINDING, findingId);
	}

	/**
	 * The Source Repository was replaced by a clean one (FR-65): every open personal-data Finding of
	 * the Product is resolved, audited, and its to-do closed.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	void clearPersonalData(UUID productId, UUID accountId) {
		List<UUID> findings = this.jdbc.sql("""
				SELECT id FROM scanning.finding
				WHERE product_id = :product AND category = 'PERSONAL_DATA' AND status = 'OPEN' ORDER BY id FOR UPDATE
				""").param("product", productId).query(UUID.class).list();
		Instant now = this.clock.instant();
		for (UUID finding : findings) {
			this.jdbc.sql("""
					UPDATE scanning.finding SET status = 'RESOLVED', resolution = 'CLEAN_REPOSITORY',
					    resolved_by_account_id = :account, resolved_at = :now
					WHERE id = :id
					""").param("id", finding).param("account", accountId).param("now", Timestamp.from(now)).update();
			this.auditLog.record(AuditEntry.of(AuditActor.user(accountId), "scanning.finding_cleared",
					AuditObject.of("scanning.finding", finding)));
			this.todos.closeAll(RESOLVE_INTAKE_FINDING, finding);
		}
	}

	@Transactional(propagation = Propagation.MANDATORY)
	void setResolvers(UUID findingId, Set<UUID> accountIds) {
		this.jdbc.sql("DELETE FROM scanning.finding_resolver WHERE finding_id = :f").param("f", findingId).update();
		accountIds.forEach(account -> addResolver(findingId, account));
	}

	void addResolver(UUID findingId, UUID accountId) {
		this.jdbc.sql("""
				INSERT INTO scanning.finding_resolver (finding_id, account_id) VALUES (:f, :a) ON CONFLICT DO NOTHING
				""").param("f", findingId).param("a", accountId).update();
	}

}
