package vn.vibefix;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import vn.vibefix.support.PostgresTestDatabase;

/**
 * Story 23.1's forward migrations (D8, R16, AD-9, AD-24) on a database that still holds scans, Findings, a clean
 * repository, and the rows that would otherwise break: the three scanning tables and
 * {@code product.repository_replacement} are gone and the empty {@code scanning} schema stays; {@code repo.job_setup}
 * loses {@code scan_run_id}, {@code open_findings} and their CHECK, and a Setup an open Intake Finding blocked is
 * FAILED, with an operation an Administrator can retry, so its next attempt re-checks the Source Repository; no
 * scanning to-do, notification, push-scan Deadline, scan operation, queued scanning email, or unfinished publication
 * to a removed listener is left. Everything else stays, and so does the audit trail (AD-8).
 */
class RetireScanningMigrationIT {

	private static final PostgresTestDatabase database = PostgresTestDatabase.instance();

	private static final String BEFORE = "202611031222";

	private static final String AFTER = "202611121307";

	private static final String ACCOUNT = "00000000-0000-7000-8000-00000000d001";

	private static final String PRODUCT = "00000000-0000-7000-8000-00000000d101";

	private static final String RUN = "00000000-0000-7000-8000-00000000d201";

	private static final String FINDING = "00000000-0000-7000-8000-00000000d202";

	private static final String FINDING_BLOCKED = "00000000-0000-7000-8000-00000000d301";

	private static final String OTHER_BLOCKED = "00000000-0000-7000-8000-00000000d302";

	private static final String RUNNING = "00000000-0000-7000-8000-00000000d303";

	@Test
	void theScanTablesColumnsAndTheRowsThatNeedThemGoAndABlockedSetupCanBeRetried() throws Exception {
		String db = database.createScratchDatabase();
		flyway(db, BEFORE).migrate();
		execute(db, """
				INSERT INTO product.product (id, customer_account_id, name, description, ai_tools, repository_github_id,
				    repository_full_name, repository_owner_type, repository_default_branch, public_notice_version,
				    copy_permission_version, detection_notice_version, acknowledged_at, created_at)
				VALUES ('%s', '%s', 'Sổ đặt bàn', 'Đặt bàn.', ARRAY['LOVABLE'], 9001, 'lan/so-dat-ban', 'User', 'main', '1.0',
				        '1.0', '1.0', now(), now())
				""".formatted(PRODUCT, ACCOUNT), """
				INSERT INTO product.repository_replacement (id, product_id, requested_by_account_id, repository_github_id,
				    repository_full_name, repository_owner_type, repository_default_branch, status, scan_run_id, requested_at)
				VALUES (gen_random_uuid(), '%s', '%s', 9002, 'lan/so-dat-ban-sach', 'User', 'main', 'PENDING', '%s', now())
				""".formatted(PRODUCT, ACCOUNT, RUN), """
				INSERT INTO scanning.scan_run (id, product_id, customer_account_id, product_name, repository_full_name,
				    repository_github_id, purpose, status, rules_version, found_count, new_findings, requested_at, finished_at)
				VALUES ('%s', '%s', '%s', 'Sổ', 'lan/so-dat-ban', 9001, 'INTAKE', 'SUCCEEDED', 2, 1, 1, now(), now())
				""".formatted(RUN, PRODUCT, ACCOUNT), """
				INSERT INTO scanning.finding (id, product_id, scan_run_id, category, rule_id, rule_type, file_path,
				    commit_sha, committed_at, masked_preview, fingerprint, status, created_at)
				VALUES ('%s', '%s', '%s', 'CREDENTIAL', 'stripe-secret-key', 'Khóa bí mật Stripe', 'pay.js',
				        repeat('a', 40), now(), 'sk_l••••••••Q7mZ', repeat('c', 64), 'OPEN', now())
				""".formatted(FINDING, PRODUCT, RUN), """
				INSERT INTO scanning.finding_resolver (finding_id, account_id) VALUES ('%s', '%s')
				""".formatted(FINDING, ACCOUNT), """
				INSERT INTO repo.job_setup (id, job_id, product_id, status, blocked_reason, open_findings, scan_run_id,
				    started_at, finished_at)
				VALUES ('%1$s', gen_random_uuid(), '%4$s', 'BLOCKED', 'INTAKE_FINDING_OPEN', 1, '%5$s', now(), now()),
				       ('%2$s', gen_random_uuid(), '%4$s', 'BLOCKED', 'SOURCE_NOT_PUBLIC', NULL, NULL, now(), now()),
				       ('%3$s', gen_random_uuid(), '%4$s', 'RUNNING', NULL, NULL, '%5$s', now(), NULL)
				""".formatted(FINDING_BLOCKED, OTHER_BLOCKED, RUNNING, PRODUCT, RUN), """
				INSERT INTO repo.push_scan (scan_run_id, job_id, product_id, repository_github_id, requested_at)
				VALUES ('%s', gen_random_uuid(), '%s', 9003, now())
				""".formatted(RUN, PRODUCT), """
				INSERT INTO notification.notification (id, account_id, type, causing_record_id, params, link, created_at,
				    read_at)
				VALUES (gen_random_uuid(), '%1$s', 'scanning.intake_scan_clean', gen_random_uuid(), '{}', '/', now(), NULL),
				       (gen_random_uuid(), '%1$s', 'scanning.finding_opened', gen_random_uuid(), '{}', '/', now(), now()),
				       (gen_random_uuid(), '%1$s', 'scanning.finding_reopened', gen_random_uuid(), '{}', '/', now(), NULL),
				       (gen_random_uuid(), '%1$s', 'scanning.push_scan_delayed', gen_random_uuid(), '{}', '/', now(), NULL),
				       (gen_random_uuid(), '%1$s', 'repo.job_start_blocked', gen_random_uuid(), '{}', '/', now(), NULL)
				""".formatted(ACCOUNT), """
				INSERT INTO notification.todo (id, todo_key, account_id, subject_id, tier, params, link, opened_at, closed_at)
				VALUES (gen_random_uuid(), 'scanning.resolve_intake_finding', '%1$s', '%2$s', 2, '{}', '/', now(), NULL),
				       (gen_random_uuid(), 'scanning.resolve_work_finding', '%1$s', '%2$s', 1, '{}', '/', now(), now()),
				       (gen_random_uuid(), 'identity.link_github', '%1$s', '%1$s', 1, '{}', '/', now(), NULL)
				""".formatted(ACCOUNT, FINDING), """
				INSERT INTO timers.deadline (id, kind, owner, subject_id, generation, pausable, status, due_at, created_at,
				    finished_at)
				VALUES (gen_random_uuid(), 'repo.push_scan_delay', 'repo', '%1$s', '0', FALSE, 'PENDING', now(), now(), NULL),
				       (gen_random_uuid(), 'repo.push_scan_delay', 'repo', '%1$s', '1', FALSE, 'FIRED', NULL, now(), now()),
				       (gen_random_uuid(), 'job.selection_lapse', 'job', '%1$s', '0', FALSE, 'PENDING', now(), now(), NULL)
				""".formatted(RUN), """
				INSERT INTO operations.external_operation (id, operation_key, type, lane, priority, status, payload,
				    next_attempt_at, error_code, created_at, completed_at, lease_until)
				VALUES (gen_random_uuid(), 'scan-pending', 'scanning.scan', 'SCAN', 0, 'PENDING', '{}', now(), NULL, now(), NULL,
				        NULL),
				       (gen_random_uuid(), 'scan-done', 'scanning.scan', 'SCAN', 0, 'SUCCEEDED', '{}', now(), NULL, now(), now(),
				        NULL),
				       (gen_random_uuid(), 'mail-1', 'notification.email', 'EMAIL', 0, 'PENDING',
				        '{"template":"scanning.finding_opened"}', now(), NULL, now(), NULL, NULL),
				       (gen_random_uuid(), 'mail-2', 'notification.email', 'EMAIL', 0, 'FAILED',
				        '{"template":"scanning.push_scan_delayed"}', now(), 'SMTP_REFUSED', now(), now(), NULL),
				       (gen_random_uuid(), 'mail-3', 'notification.email', 'EMAIL', 0, 'SUCCEEDED',
				        '{"template":"scanning.finding_reopened"}', now(), NULL, now(), now(), NULL),
				       (gen_random_uuid(), 'mail-4', 'notification.email', 'EMAIL', 0, 'PENDING',
				        '{"template":"identity.account_locked"}', now(), NULL, now(), NULL, NULL),
				       (gen_random_uuid(), 'repo.job_setup:%1$s', 'repo.job_setup', 'GITHUB_LONG', 0, 'ABORTED',
				        '{"setupId":"%1$s"}', now(), 'INTAKE_FINDING_OPEN', now(), now(), NULL),
				       (gen_random_uuid(), 'repo.job_setup:%2$s', 'repo.job_setup', 'GITHUB_LONG', 0, 'ABORTED',
				        '{"setupId":"%2$s"}', now(), 'SOURCE_NOT_PUBLIC', now(), now(), NULL)
				""".formatted(FINDING_BLOCKED, OTHER_BLOCKED), """
				INSERT INTO platform.event_publication (id, listener_id, event_type, serialized_event, publication_date,
				    completion_date)
				VALUES ('00000000-0000-7000-8000-00000000d401',
				        'vn.vibefix.product.internal.CleanRepositories.on(vn.vibefix.scanning.ScanCompleted)',
				        'vn.vibefix.scanning.ScanCompleted', '{}', now(), NULL),
				       ('00000000-0000-7000-8000-00000000d402',
				        'vn.vibefix.repo.internal.PushScans.onScanCompleted(vn.vibefix.scanning.ScanCompleted)',
				        'vn.vibefix.scanning.ScanCompleted', '{}', now(), NULL),
				       ('00000000-0000-7000-8000-00000000d403',
				        'vn.vibefix.scanning.internal.ScanOperationHandler.on(vn.vibefix.operations.OperationCompleted)',
				        'vn.vibefix.operations.OperationCompleted', '{}', now(), NULL),
				       ('00000000-0000-7000-8000-00000000d404',
				        'vn.vibefix.repo.internal.PushScans.onScanCompleted(vn.vibefix.scanning.ScanCompleted)',
				        'vn.vibefix.scanning.ScanCompleted', '{}', now(), now()),
				       ('00000000-0000-7000-8000-00000000d405',
				        'vn.vibefix.repo.internal.JobSetups.on(vn.vibefix.operations.OperationCompleted)',
				        'vn.vibefix.operations.OperationCompleted', '{}', now(), NULL)
				""", """
				INSERT INTO platform.event_publication_stalled (publication_id, stalled_at)
				VALUES ('00000000-0000-7000-8000-00000000d401', now()), ('00000000-0000-7000-8000-00000000d405', now())
				""", """
				INSERT INTO platform.processed_event (listener, event_id, processed_at)
				VALUES ('repo.PushScans.onScanCompleted', gen_random_uuid(), now()),
				       ('repo.PushScans.onWebhook', gen_random_uuid(), now())
				""", """
				INSERT INTO audit.audit_entry (id, occurred_at, actor_type, actor_account_id, action)
				VALUES (gen_random_uuid(), now(), 'USER', '%s', 'scanning.finding_rotated')
				""".formatted(ACCOUNT));

		flyway(db, AFTER).migrate();

		assertThat(rows(db, """
				SELECT coalesce(to_regclass('scanning.scan_run')::text, 'gone') || ' '
				       || coalesce(to_regclass('scanning.finding')::text, 'gone') || ' '
				       || coalesce(to_regclass('scanning.finding_resolver')::text, 'gone') || ' '
				       || coalesce(to_regclass('product.repository_replacement')::text, 'gone')
				""")).containsExactly("gone gone gone gone");
		assertThat(rows(db, "SELECT schema_name FROM information_schema.schemata WHERE schema_name = 'scanning'"))
			.as("the empty schema stays")
			.containsExactly("scanning");
		assertThat(rows(db, "SELECT table_name FROM information_schema.tables WHERE table_schema = 'scanning'")).isEmpty();
		assertThat(rows(db, """
				SELECT column_name FROM information_schema.columns
				WHERE table_schema = 'repo' AND table_name = 'job_setup' AND column_name IN ('scan_run_id', 'open_findings')
				""")).isEmpty();
		assertThat(rows(db, """
				SELECT pg_get_constraintdef(oid) FROM pg_constraint
				WHERE conrelid = 'repo.job_setup'::regclass AND contype = 'c' AND pg_get_constraintdef(oid) LIKE '%INTAKE%'
				""")).as("the retired reason is no longer allowed").isEmpty();
		assertThat(rows(db, "SELECT id || ' ' || status || ' ' || coalesce(blocked_reason, '-') FROM repo.job_setup ORDER BY id"))
			.containsExactly(FINDING_BLOCKED + " FAILED -", OTHER_BLOCKED + " BLOCKED SOURCE_NOT_PUBLIC", RUNNING + " RUNNING -");
		assertThat(rows(db, "SELECT count(*)::text FROM repo.push_scan")).as("Story 23.2 retires it").containsExactly("1");
		assertThat(rows(db, "SELECT type FROM notification.notification ORDER BY type")).containsExactly("repo.job_start_blocked");
		assertThat(rows(db, "SELECT todo_key FROM notification.todo ORDER BY todo_key")).containsExactly("identity.link_github");
		assertThat(rows(db, "SELECT kind FROM timers.deadline ORDER BY kind")).containsExactly("job.selection_lapse");
		assertThat(rows(db, "SELECT operation_key || ' ' || status FROM operations.external_operation ORDER BY operation_key"))
			.as("no scan operation; a finished email is history, and an email of a live template still goes")
			.containsExactly("mail-3 SUCCEEDED", "mail-4 PENDING", "repo.job_setup:" + FINDING_BLOCKED + " FAILED",
					"repo.job_setup:" + OTHER_BLOCKED + " ABORTED");
		assertThat(rows(db, """
				SELECT error_code || ' ' || (completed_at IS NOT NULL) FROM operations.external_operation
				WHERE operation_key = 'repo.job_setup:%s'
				""".formatted(FINDING_BLOCKED))).as("the moved operation keeps everything but its status")
			.containsExactly("INTAKE_FINDING_OPEN true");
		assertThat(rows(db, """
				SELECT pg_get_constraintdef(oid) FROM pg_constraint
				WHERE conrelid = 'operations.external_operation'::regclass AND contype = 'c'
				  AND pg_get_constraintdef(oid) LIKE '%''SCAN''%'
				""")).as("no lane is SCAN any more").isEmpty();
		assertThat(rows(db, "SELECT id::text FROM platform.event_publication ORDER BY id"))
			.as("a completed publication waits for the purge, and another listener's stays")
			.containsExactly("00000000-0000-7000-8000-00000000d404", "00000000-0000-7000-8000-00000000d405");
		assertThat(rows(db, "SELECT publication_id::text FROM platform.event_publication_stalled"))
			.containsExactly("00000000-0000-7000-8000-00000000d405");
		assertThat(rows(db, "SELECT listener FROM platform.processed_event")).containsExactly("repo.PushScans.onWebhook");
		assertThat(rows(db, "SELECT action FROM audit.audit_entry WHERE action LIKE 'scanning.%'"))
			.containsExactly("scanning.finding_rotated");
		assertThat(rows(db, "SELECT count(*)::text FROM product.product")).containsExactly("1");
	}

	private static Flyway flyway(String db, String target) {
		return Flyway.configure()
			.dataSource(database.jdbcUrl(db), PostgresTestDatabase.MIGRATOR, database.migratorPassword())
			.schemas("platform")
			.defaultSchema("platform")
			.createSchemas(true)
			.locations("classpath:db/migration")
			.placeholders(Map.of("runtime_role", PostgresTestDatabase.APP, "environment", "dev"))
			.target(target)
			.load();
	}

	private static void execute(String db, String... statements) throws SQLException {
		try (Connection connection = database.migratorConnection(db); Statement statement = connection.createStatement()) {
			for (String sql : statements) {
				statement.execute(sql);
			}
		}
	}

	private static List<String> rows(String db, String sql) throws SQLException {
		List<String> values = new ArrayList<>();
		try (Connection connection = database.superuserConnection(db); Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery(sql)) {
			while (result.next()) {
				values.add(result.getString(1));
			}
		}
		return values;
	}

}
