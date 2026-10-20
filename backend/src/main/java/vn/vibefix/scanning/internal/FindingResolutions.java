package vn.vibefix.scanning.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.notification.TodoRequest;
import vn.vibefix.notification.Todos;
import vn.vibefix.scanning.FalsePositive;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.IdGenerator;

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

	static final String REOPENED = "scanning.finding_reopened";

	private final JdbcClient jdbc;

	private final AuditLog auditLog;

	private final Todos todos;

	private final Notifications notifications;

	private final IdGenerator ids;

	private final Clock clock;

	FindingResolutions(JdbcClient jdbc, AuditLog auditLog, Todos todos, Notifications notifications, IdGenerator ids,
			Clock clock) {
		this.jdbc = jdbc;
		this.auditLog = auditLog;
		this.todos = todos;
		this.notifications = notifications;
		this.ids = ids;
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

	/** The false positives, the newest first (FR-64). */
	List<FalsePositive> falsePositives(int limit) {
		return this.jdbc.sql("""
				SELECT f.id, f.product_id, r.product_name, f.rule_type, f.file_path, f.resolution_reason, f.resolved_by_account_id,
				       f.resolved_at
				FROM scanning.finding f JOIN scanning.scan_run r ON r.id = f.scan_run_id
				WHERE f.status = 'FALSE_POSITIVE' ORDER BY f.resolved_at DESC, f.id DESC LIMIT :limit
				""").param("limit", limit).query((rs, n) -> new FalsePositive(rs.getObject("id", UUID.class),
				rs.getObject("product_id", UUID.class), rs.getString("product_name"), rs.getString("rule_type"),
				rs.getString("file_path"), rs.getString("resolution_reason"), rs.getObject("resolved_by_account_id", UUID.class),
				rs.getTimestamp("resolved_at").toInstant())).list();
	}

	/**
	 * FR-64: the false positive is open again; each resolver gets its to-do and the notice back. Audited
	 * as the Administrator's.
	 */
	@Transactional
	void reopen(UUID findingId, UUID administrator) {
		record Reopened(UUID productId, String productName, String ruleType) {
		}
		String status = this.jdbc.sql("SELECT status FROM scanning.finding WHERE id = :id FOR UPDATE")
			.param("id", findingId)
			.query(String.class)
			.optional()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (!"FALSE_POSITIVE".equals(status)) {
			throw new ApiException(ErrorCode.CONFLICT, ScanningReason.NOT_A_FALSE_POSITIVE);
		}
		Reopened reopened = this.jdbc.sql("""
				UPDATE scanning.finding f SET status = 'OPEN', resolution = NULL, resolution_reason = NULL,
				    resolved_by_account_id = NULL, resolved_at = NULL
				FROM scanning.scan_run r
				WHERE f.id = :id AND r.id = f.scan_run_id
				RETURNING f.product_id, r.product_name, f.rule_type
				""").param("id", findingId).query((rs, n) -> new Reopened(rs.getObject("product_id", UUID.class),
				rs.getString("product_name"), rs.getString("rule_type"))).single();
		List<UUID> resolvers = this.jdbc.sql("SELECT account_id FROM scanning.finding_resolver WHERE finding_id = :id")
			.param("id", findingId)
			.query(UUID.class)
			.list();
		String link = "/san-pham/" + reopened.productId() + "/van-de/" + findingId;
		for (UUID resolver : resolvers) {
			this.todos.open(new TodoRequest(RESOLVE_INTAKE_FINDING, resolver, Audience.SUBJECT, findingId,
					Map.of("product", reopened.productName()), link, null, null));
		}
		if (!resolvers.isEmpty()) {
			this.notifications.send(new NotificationRequest(REOPENED, this.ids.newId(),
					resolvers.stream().map(resolver -> new Recipient(resolver, Audience.SUBJECT)).toList(),
					Map.of("product", reopened.productName(), "type", reopened.ruleType()), link, null, findingId));
		}
		this.auditLog.record(AuditEntry.of(AuditActor.user(administrator), "scanning.finding_reopened",
				AuditObject.of("scanning.finding", findingId)));
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
