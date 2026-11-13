package vn.vibefix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * Story 23.5d's forward migrations (R31, AD-10, AD-27) on a database that still keeps GitHub linking tokens, a Setup
 * blocked on the re-link, and the re-link's to-dos, notifications and queued emails: {@code identity.customer_token}
 * is gone with its rows; the blocked Setup reads {@code SOURCE_APP_MISSING} and the CHECK no longer allows
 * {@code RELINK_GITHUB}; every {@code identity.relink_github} to-do and notification goes, open or closed, read or
 * not; a queued or failed email under its template goes; and a grant revocation still open with
 * {@code "revoke": "TOKEN"} loses that key, so it revokes the whole grant. Everything else stays.
 */
class RetireLinkingTokenMigrationIT {

	private static final PostgresTestDatabase database = PostgresTestDatabase.instance();

	private static final String BEFORE = "202611131200";

	private static final String AFTER = "202611131303";

	private static final String LAN = "00000000-0000-7000-8000-00000000e001";

	private static final String RELINK = "00000000-0000-7000-8000-00000000e101";

	private static final String WORKFLOW = "00000000-0000-7000-8000-00000000e102";

	@Test
	void theTokensTheReLinkBlockAndTheReLinkRowsGoAndEverythingElseStays() throws Exception {
		String db = database.createScratchDatabase();
		flyway(db, BEFORE).migrate();
		execute(db, """
				INSERT INTO identity.account (id, kind, github_user_id, github_login, created_at)
				VALUES ('%s', 'USER', 9501, 'lan-dev', now())
				""".formatted(LAN), """
				INSERT INTO identity.customer_token (account_id, key_version, nonce, ciphertext, stored_at)
				VALUES ('%s', 1, '\\x00'::bytea, '\\x01'::bytea, now())
				""".formatted(LAN), """
				INSERT INTO repo.job_setup (id, job_id, product_id, status, blocked_reason, started_at, finished_at)
				VALUES ('%s', gen_random_uuid(), gen_random_uuid(), 'BLOCKED', 'RELINK_GITHUB', now(), now()),
				       ('%s', gen_random_uuid(), gen_random_uuid(), 'BLOCKED', 'WORKFLOW_MISSING', now(), now())
				""".formatted(RELINK, WORKFLOW), """
				INSERT INTO notification.todo (id, todo_key, account_id, subject_id, tier, params, link, opened_at, closed_at)
				VALUES (gen_random_uuid(), 'identity.relink_github', '%1$s', '%1$s', 1, '{}', '/ho-so/xac-minh', now(), NULL),
				       (gen_random_uuid(), 'identity.relink_github', '%1$s', '%1$s', 1, '{}', '/ho-so/xac-minh', now(), now()),
				       (gen_random_uuid(), 'identity.link_github', '%1$s', '%1$s', 1, '{}', '/ho-so/xac-minh', now(), NULL)
				""".formatted(LAN), """
				INSERT INTO notification.notification (id, account_id, type, causing_record_id, params, link, created_at,
				    read_at)
				VALUES (gen_random_uuid(), '%1$s', 'identity.relink_github', gen_random_uuid(), '{}', '/ho-so/xac-minh', now(),
				        NULL),
				       (gen_random_uuid(), '%1$s', 'identity.relink_github', gen_random_uuid(), '{}', '/ho-so/xac-minh', now(),
				        now()),
				       (gen_random_uuid(), '%1$s', 'repo.job_start_blocked', gen_random_uuid(), '{}', '/', now(), NULL)
				""".formatted(LAN), """
				INSERT INTO operations.external_operation (id, operation_key, type, lane, priority, status, payload,
				    next_attempt_at, error_code, created_at, completed_at, lease_until)
				VALUES (gen_random_uuid(), 'relink-mail-1', 'notification.email', 'EMAIL', 0, 'PENDING',
				        '{"template":"identity.relink_github"}', now(), NULL, now(), NULL, NULL),
				       (gen_random_uuid(), 'relink-mail-2', 'notification.email', 'EMAIL', 0, 'FAILED',
				        '{"template":"identity.relink_github"}', now(), 'SMTP_REFUSED', now(), now(), NULL),
				       (gen_random_uuid(), 'relink-mail-3', 'notification.email', 'EMAIL', 0, 'SUCCEEDED',
				        '{"template":"identity.relink_github"}', now(), NULL, now(), now(), NULL),
				       (gen_random_uuid(), 'other-mail', 'notification.email', 'EMAIL', 0, 'PENDING',
				        '{"template":"identity.account_locked"}', now(), NULL, now(), NULL, NULL)
				""", """
				INSERT INTO operations.external_operation (id, operation_key, type, lane, priority, status, payload,
				    personal_data, account_id, attempt_count, next_attempt_at, error_code, created_at, completed_at,
				    lease_until)
				VALUES (gen_random_uuid(), 'revoke-pending', 'identity.revoke_oauth_grant', 'GITHUB_SHORT', 0, 'PENDING',
				        '{"accountId":"%1$s","revoke":"TOKEN"}', '{"keyVersion":1,"nonce":"bm9uY2U=","ciphertext":"c2VhbGVk"}',
				        '%1$s', 2, now(), NULL, now(), NULL, NULL),
				       (gen_random_uuid(), 'revoke-running', 'identity.revoke_oauth_grant', 'GITHUB_SHORT', 0, 'RUNNING',
				        '{"accountId":"%1$s","revoke":"TOKEN"}', '{"keyVersion":1,"nonce":"bm9uY2U=","ciphertext":"c2VhbGVk"}',
				        '%1$s', 1, now(), NULL, now(), NULL, now() + INTERVAL '1 minute'),
				       (gen_random_uuid(), 'revoke-failed', 'identity.revoke_oauth_grant', 'GITHUB_SHORT', 0, 'FAILED',
				        '{"accountId":"%1$s","revoke":"TOKEN"}', NULL, '%1$s', 5, now(), 'GITHUB_UNAVAILABLE', now(), now(),
				        NULL),
				       (gen_random_uuid(), 'revoke-done', 'identity.revoke_oauth_grant', 'GITHUB_SHORT', 0, 'SUCCEEDED',
				        '{"accountId":"%1$s","revoke":"TOKEN"}', NULL, '%1$s', 1, now(), NULL, now(), now(), NULL),
				       (gen_random_uuid(), 'revoke-grant', 'identity.revoke_oauth_grant', 'GITHUB_SHORT', 0, 'PENDING',
				        '{"accountId":"%1$s"}', '{"keyVersion":1,"nonce":"bm9uY2U=","ciphertext":"c2VhbGVk"}', '%1$s', 0, now(),
				        NULL, now(), NULL, NULL)
				""".formatted(LAN));
		String pendingId = rows(db, "SELECT id::text FROM operations.external_operation WHERE operation_key = 'revoke-pending'")
			.getFirst();

		flyway(db, AFTER).migrate();

		assertThat(rows(db, "SELECT coalesce(to_regclass('identity.customer_token')::text, 'gone')")).containsExactly("gone");
		assertThat(rows(db, "SELECT github_login FROM identity.account WHERE id = '%s'".formatted(LAN)))
			.as("the link itself stays")
			.containsExactly("lan-dev");
		assertThat(rows(db, "SELECT id || ' ' || blocked_reason FROM repo.job_setup ORDER BY id"))
			.containsExactly(RELINK + " SOURCE_APP_MISSING", WORKFLOW + " WORKFLOW_MISSING");
		assertThat(rows(db, """
				SELECT count(*)::text FROM pg_constraint
				WHERE conrelid = 'repo.job_setup'::regclass AND contype = 'c' AND pg_get_constraintdef(oid) LIKE '%RELINK_GITHUB%'
				""")).as("no CHECK names the retired reason").containsExactly("0");
		assertThatThrownBy(() -> execute(db, "UPDATE repo.job_setup SET blocked_reason = 'RELINK_GITHUB' WHERE id = '%s'"
			.formatted(RELINK))).isInstanceOf(SQLException.class);
		for (String block : List.of("SOURCE_APP_WRONG_REPOSITORY", "WORKFLOW_MISMATCH", "NO_ADMIN_PERMISSION",
				"SOURCE_APP_MISSING")) {
			execute(db, "UPDATE repo.job_setup SET blocked_reason = '%s' WHERE id = '%s'".formatted(block, RELINK));
		}
		assertThat(rows(db, "SELECT todo_key FROM notification.todo WHERE account_id = '%s' ORDER BY todo_key".formatted(LAN)))
			.containsExactly("identity.link_github");
		assertThat(rows(db, "SELECT type FROM notification.notification WHERE account_id = '%s' ORDER BY type".formatted(LAN)))
			.containsExactly("repo.job_start_blocked");
		assertThat(rows(db, """
				SELECT operation_key || ' ' || status FROM operations.external_operation
				WHERE operation_key IN ('relink-mail-1', 'relink-mail-2', 'relink-mail-3', 'other-mail') ORDER BY operation_key
				""")).as("a finished email is history, and an email of a live template still goes")
			.containsExactly("other-mail PENDING", "relink-mail-3 SUCCEEDED");
		assertThat(rows(db, """
				SELECT operation_key || ' ' || status || ' ' || coalesce(payload ->> 'revoke', 'GRANT') || ' '
				       || (payload ->> 'accountId') || ' ' || attempt_count || ' ' || (personal_data IS NOT NULL)
				FROM operations.external_operation WHERE type = 'identity.revoke_oauth_grant' ORDER BY operation_key
				""")).as("an open revocation now revokes the whole grant, with its token and everything else; a finished one"
						+ " is history")
			.containsExactly("revoke-done SUCCEEDED TOKEN " + LAN + " 1 false",
					"revoke-failed FAILED GRANT " + LAN + " 5 false", "revoke-grant PENDING GRANT " + LAN + " 0 true",
					"revoke-pending PENDING GRANT " + LAN + " 2 true", "revoke-running RUNNING GRANT " + LAN + " 1 true");
		assertThat(rows(db, "SELECT id::text FROM operations.external_operation WHERE operation_key = 'revoke-pending'"))
			.as("re-inserted under its own ID")
			.containsExactly(pendingId);
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
