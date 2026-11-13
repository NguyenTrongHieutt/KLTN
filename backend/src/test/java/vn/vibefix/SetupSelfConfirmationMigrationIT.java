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
 * Story 23.6b's forward migration (R4, AD-11, AD-24) on a database with Setups from before the self-confirmation: one
 * still RUNNING (under its old marker) waits for the Customer from its first step; a DONE one counts as confirmed at
 * its check, with its copy done; a FAILED one forgets the commit and the copy it had, keeping its repositories; a
 * BLOCKED one stays. The new CHECKs then hold: the new blocks are allowed, {@code EXCHANGE_REFUSED} names its reason,
 * nothing copies without a confirmation, a waiting Setup has none, one live Setup per Job counts the waiting ones, and a
 * waiting one may end. {@code repo.source_confirmation} belongs to its Setup and keeps the confirming account, which an
 * erasure alone may clear.
 */
class SetupSelfConfirmationMigrationIT {

	private static final PostgresTestDatabase database = PostgresTestDatabase.instance();

	private static final String BEFORE = "202611141000";

	private static final String AFTER = "202611141100";

	private static final String RUNNING = "00000000-0000-7000-8000-00000000f101";

	private static final String DONE = "00000000-0000-7000-8000-00000000f102";

	private static final String FAILED = "00000000-0000-7000-8000-00000000f103";

	private static final String BLOCKED = "00000000-0000-7000-8000-00000000f104";

	private static final String SHA = "ab".repeat(20);

	@Test
	void oldSetupsFollowTheNewChainAndItsChecksHold() throws Exception {
		String db = database.createScratchDatabase();
		flyway(db, BEFORE).migrate();
		execute(db, """
				INSERT INTO repo.job_setup (id, job_id, product_id, status, source_full_name, default_branch, approved_sha,
				    checked_at, started_at)
				VALUES ('%s', gen_random_uuid(), gen_random_uuid(), 'RUNNING', 'lan-dev/quan-may', 'main', '%s', now(), now())
				""".formatted(RUNNING, SHA), """
				INSERT INTO repo.job_setup (id, job_id, product_id, status, source_full_name, default_branch, approved_sha,
				    intermediary_github_id, intermediary_name, working_github_id, working_name, checked_at, intermediary_at,
				    working_at, started_at, finished_at)
				VALUES ('%s', gen_random_uuid(), gen_random_uuid(), 'DONE', 'lan-dev/quan-may', 'main', '%s', 1,
				        'vibefix-dev/job-1-intermediary', 2, 'vibefix-dev/job-1-working', now(), now(), now(), now(), now())
				""".formatted(DONE, SHA), """
				INSERT INTO repo.job_setup (id, job_id, product_id, status, source_full_name, default_branch, approved_sha,
				    intermediary_github_id, intermediary_name, checked_at, intermediary_at, started_at, finished_at)
				VALUES ('%s', gen_random_uuid(), gen_random_uuid(), 'FAILED', 'lan-dev/quan-may', 'main', '%s', 3,
				        'vibefix-dev/job-2-intermediary', now(), now(), now(), now())
				""".formatted(FAILED, SHA), """
				INSERT INTO repo.job_setup (id, job_id, product_id, status, blocked_reason, started_at, finished_at)
				VALUES ('%s', gen_random_uuid(), gen_random_uuid(), 'BLOCKED', 'WORKFLOW_MISSING', now(), now())
				""".formatted(BLOCKED));

		flyway(db, AFTER).migrate();

		assertThat(rows(db, """
				SELECT id || ' ' || status || ' ' || (checked_at IS NULL) || ' ' || coalesce(head_sha, '-') || ' '
				       || coalesce(approved_sha, '-') || ' ' || (confirmed_at IS NULL) || ' ' || (intermediary_at IS NULL) || ' '
				       || (copied_at IS NULL) || ' ' || coalesce(intermediary_github_id::text, '-') || ' ' || (finished_at IS NULL)
				FROM repo.job_setup ORDER BY id
				""")).containsExactly(RUNNING + " CONFIRMING true - - true true true - true",
						DONE + " DONE false " + SHA + " " + SHA + " false false false 1 false",
						FAILED + " FAILED false " + SHA + " - true true true 3 false",
						BLOCKED + " BLOCKED true - - true true true - false");
		assertThat(rows(db, "SELECT coalesce(to_regclass('repo.source_confirmation')::text, 'gone')"))
			.containsExactly("repo.source_confirmation");

		for (String block : List.of("RUN_FAILED", "ACTIONS_QUOTA")) {
			execute(db, "UPDATE repo.job_setup SET blocked_reason = '%s' WHERE id = '%s'".formatted(block, BLOCKED));
		}
		execute(db, "UPDATE repo.job_setup SET blocked_reason = 'EXCHANGE_REFUSED', exchange_reason = 'blob' WHERE id = '%s'"
			.formatted(BLOCKED));
		assertThatThrownBy(() -> execute(db, "UPDATE repo.job_setup SET exchange_reason = NULL WHERE id = '%s'"
			.formatted(BLOCKED))).as("an exchange refusal names its reason").isInstanceOf(SQLException.class);
		assertThatThrownBy(() -> execute(db, "UPDATE repo.job_setup SET status = 'RUNNING' WHERE id = '%s'".formatted(RUNNING)))
			.as("nothing copies without a confirmation").isInstanceOf(SQLException.class);
		assertThatThrownBy(() -> execute(db, "UPDATE repo.job_setup SET blocked_reason = 'INTAKE_FINDING_OPEN' WHERE id = '%s'"
			.formatted(BLOCKED))).as("no retired block").isInstanceOf(SQLException.class);
		assertThatThrownBy(() -> execute(db, """
				UPDATE repo.job_setup SET approved_sha = '%s', confirmed_at = now() WHERE id = '%s'
				""".formatted(SHA, RUNNING))).as("a waiting Setup holds no confirmation").isInstanceOf(SQLException.class);
		assertThatThrownBy(() -> execute(db, """
				INSERT INTO repo.job_setup (id, job_id, product_id, status, started_at)
				SELECT gen_random_uuid(), job_id, product_id, 'CONFIRMING', now() FROM repo.job_setup WHERE id = '%s'
				""".formatted(DONE))).as("one live Setup per Job, the waiting ones counted").isInstanceOf(SQLException.class);
		execute(db, """
				INSERT INTO repo.source_confirmation (id, job_id, product_id, setup_id, account_id, head_sha, notice_version,
				    confirmed_at)
				SELECT gen_random_uuid(), job_id, product_id, id, NULL, '%s', '1.0', now() FROM repo.job_setup WHERE id = '%s'
				""".formatted(SHA, DONE));
		assertThatThrownBy(() -> execute(db, """
				INSERT INTO repo.source_confirmation (id, job_id, product_id, setup_id, account_id, head_sha, notice_version,
				    confirmed_at)
				SELECT gen_random_uuid(), job_id, product_id, id, NULL, '%s', 'latest', now() FROM repo.job_setup WHERE id = '%s'
				""".formatted(SHA, DONE))).as("a notice version is major.minor").isInstanceOf(SQLException.class)
			.hasMessageContaining("source_confirmation_notice_version_check");
		assertThatThrownBy(() -> execute(db, """
				INSERT INTO repo.source_confirmation (id, job_id, product_id, setup_id, account_id, head_sha, notice_version,
				    confirmed_at)
				VALUES (gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), NULL, '%s', '1.0', now())
				""".formatted(SHA))).as("a confirmation belongs to a Setup").isInstanceOf(SQLException.class)
			.hasMessageContaining("source_confirmation_setup_id_fkey");
		assertThat(rows(db, """
				SELECT has_table_privilege('%1$s', 'repo.source_confirmation', 'UPDATE') || ' '
				       || has_column_privilege('%1$s', 'repo.source_confirmation', 'account_id', 'UPDATE') || ' '
				       || has_column_privilege('%1$s', 'repo.source_confirmation', 'head_sha', 'UPDATE')
				""".formatted(PostgresTestDatabase.APP))).as("only an erasure changes a confirmation: who made it")
			.containsExactly("false true false");
		execute(db, "UPDATE repo.job_setup SET status = 'ENDED', finished_at = now() WHERE id = '%s'".formatted(RUNNING));
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
