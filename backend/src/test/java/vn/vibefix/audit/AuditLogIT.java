package vn.vibefix.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.shared.internal.CorrelationIdFilter;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.PostgresTestDatabase;

/**
 * AD-8, FR-63: an audit entry is written in the caller's transaction, holds internal IDs only, and
 * can never be changed: the runtime role may only insert and read, and triggers reject updates,
 * deletes and truncation by anyone, the owning migration role included.
 */
class AuditLogIT extends IntegrationTest {

	private static final UUID ACCOUNT = UUID.fromString("0190f0c3-0000-7000-8000-000000000001");

	private static final UUID JOB = UUID.fromString("0190f0c3-0000-7000-8000-000000000002");

	private static final UUID MILESTONE = UUID.fromString("0190f0c3-0000-7000-8000-000000000003");

	private static final UUID REQUEST = UUID.fromString("0190f0c3-0000-7000-8000-000000000004");

	@Autowired
	private AuditLog auditLog;

	@Autowired
	private TransactionTemplate transactions;

	@Autowired
	private JdbcClient jdbc;

	@Test
	void entryCommitsWithTheCallersTransaction() {
		MDC.put(CorrelationIdFilter.MDC_KEY, REQUEST.toString());
		try {
			this.transactions.executeWithoutResult(status -> this.auditLog.record(AuditEntry
				.of(AuditActor.user(ACCOUNT), "job.milestone_funded", AuditObject.of("job.milestone", MILESTONE))
				.forJob(JOB)));
		}
		finally {
			MDC.remove(CorrelationIdFilter.MDC_KEY);
		}

		Map<String, Object> entry = this.jdbc.sql("SELECT * FROM audit.audit_entry").query().singleRow();
		assertThat(entry).containsEntry("actor_type", "USER")
			.containsEntry("actor_account_id", ACCOUNT)
			.containsEntry("action", "job.milestone_funded")
			.containsEntry("job_id", JOB)
			.containsEntry("correlation_id", REQUEST);
		assertThat(((java.sql.Timestamp) entry.get("occurred_at")).toInstant()).isEqualTo(this.clock.instant());
		assertThat(((UUID) entry.get("id")).version()).isEqualTo(7);
		assertThat(this.jdbc.sql("SELECT object_type, object_id FROM audit.audit_entry_object").query().listOfRows())
			.containsExactly(Map.of("object_type", "job.milestone", "object_id", MILESTONE));
	}

	@Test
	void entryRollsBackWithTheCallersTransaction() {
		this.transactions.executeWithoutResult(status -> {
			this.auditLog.record(AuditEntry.of(AuditActor.system(), "timers.deadline_fired",
					AuditObject.of("timers.deadline", MILESTONE), AuditObject.of("job.job", JOB)));
			status.setRollbackOnly();
		});

		assertThat(count("audit.audit_entry")).isZero();
		assertThat(count("audit.audit_entry_object")).isZero();
	}

	@Test
	void systemEntryHasNoAccountAndKeepsTheObjectOrder() {
		this.transactions.executeWithoutResult(status -> this.auditLog.record(AuditEntry.of(AuditActor.system(),
				"timers.deadline_fired", AuditObject.of("timers.deadline", MILESTONE), AuditObject.of("job.job", JOB))));

		Map<String, Object> entry = this.jdbc.sql("SELECT * FROM audit.audit_entry").query().singleRow();
		assertThat(entry.get("actor_type")).isEqualTo("SYSTEM");
		assertThat(entry.get("actor_account_id")).isNull();
		assertThat(entry.get("job_id")).isNull();
		assertThat(entry.get("correlation_id")).isNull();
		assertThat(this.jdbc.sql("SELECT object_id FROM audit.audit_entry_object ORDER BY position")
			.query(UUID.class)
			.list()).containsExactly(MILESTONE, JOB);
	}

	@Test
	void recordingOutsideATransactionIsRefused() {
		assertThatThrownBy(() -> this.auditLog
			.record(AuditEntry.of(AuditActor.system(), "timers.deadline_fired", AuditObject.of("job.job", JOB))))
			.isInstanceOf(IllegalTransactionStateException.class);
		assertThat(count("audit.audit_entry")).isZero();
	}

	@Test
	void runtimeRoleMayOnlyInsertAndSelect() {
		recordOne();

		for (String table : List.of("audit_entry", "audit_entry_object")) {
			Set<String> privileges = new TreeSet<>(this.jdbc.sql("""
					SELECT privilege_type FROM information_schema.role_table_grants
					WHERE grantee = :grantee AND table_schema = 'audit' AND table_name = :table
					""").param("grantee", PostgresTestDatabase.APP).param("table", table).query(String.class).list());
			assertThat(privileges).as(table).containsExactly("INSERT", "SELECT");
		}
		assertThatThrownBy(() -> this.jdbc.sql("UPDATE audit.audit_entry SET action = 'x.y'").update()).rootCause()
			.hasMessageContaining("permission denied for table audit_entry");
		assertThatThrownBy(() -> this.jdbc.sql("DELETE FROM audit.audit_entry_object").update()).rootCause()
			.hasMessageContaining("permission denied for table audit_entry_object");
		assertThatThrownBy(() -> this.jdbc.sql("TRUNCATE audit.audit_entry CASCADE").update()).rootCause()
			.hasMessageContaining("permission denied for table audit_entry");
		assertThat(count("audit.audit_entry")).isOne();
	}

	@Test
	void triggersRejectChangesEvenByTheOwningMigrationRole() throws SQLException {
		recordOne();

		for (String sql : List.of("UPDATE audit.audit_entry SET action = 'x.y'", "DELETE FROM audit.audit_entry",
				"UPDATE audit.audit_entry_object SET object_type = 'x.y'", "DELETE FROM audit.audit_entry_object",
				"TRUNCATE audit.audit_entry_object", "TRUNCATE audit.audit_entry CASCADE")) {
			try (Connection connection = database.migratorConnection(PostgresTestDatabase.DATABASE);
					Statement statement = connection.createStatement()) {
				assertThatThrownBy(() -> statement.execute(sql)).as(sql)
					.isInstanceOf(SQLException.class)
					.hasMessageContaining("audit entries are append-only");
			}
		}
		assertThat(count("audit.audit_entry")).isOne();
		assertThat(count("audit.audit_entry_object")).isOne();
	}

	@Test
	void migrationRoleOwnsTheAuditTables() {
		assertThat(this.jdbc.sql("SELECT DISTINCT tableowner FROM pg_tables WHERE schemaname = 'audit'")
			.query(String.class)
			.list()).containsExactly(PostgresTestDatabase.MIGRATOR);
	}

	@Test
	void entriesKeepTheInjectedClockTime() {
		Instant later = Instant.parse("2026-03-01T00:00:00Z");
		this.clock.setInstant(later);

		recordOne();

		assertThat(this.jdbc.sql("SELECT occurred_at FROM audit.audit_entry").query(java.sql.Timestamp.class).single()
			.toInstant()).isEqualTo(later);
	}

	private void recordOne() {
		this.transactions.executeWithoutResult(status -> this.auditLog
			.record(AuditEntry.of(AuditActor.user(ACCOUNT), "identity.account_created",
					AuditObject.of("identity.account", ACCOUNT))));
	}

	private long count(String table) {
		return this.jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
	}

}
