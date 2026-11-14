package vn.vibefix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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
 * Story 23.9b's three forward migrations (FR-37, AD-9, AD-10, AD-12, R1, R16) on a database that still holds what
 * Story 23.9a left for it: a {@code repo.merge_installation} row, an authorization with {@code bound_at} filled,
 * unfinished publications of events that name the retired App, and deduplication history under it. After them the
 * table and the column are gone, the publications AD-9 would replay into an {@code App} constant that no longer
 * exists are gone with their stalled marks, and {@code github.webhook_delivery} holds and admits {@code PLATFORM}
 * alone. What stays: both authorizations with every other column — the row is still the Final Merge's identity, the
 * FR-37 deadline's anchor and {@code repo.zip_fallback}'s reference until Story 23.10 —, the same listeners'
 * publications of another App, a completed one until the registry's purge, and the Platform App's deliveries.
 */
class RetireMergeAppItselfMigrationIT {

	private static final PostgresTestDatabase database = PostgresTestDatabase.instance();

	/** The version right before this story's migrations, the last of Story 23.9a's five. */
	private static final String BEFORE = "202611141704";

	/** The last of this story's three. */
	private static final String AFTER = "202611141712";

	private static final String USED = "00000000-0000-7000-8000-00000000fb01";

	private static final String OPEN = "00000000-0000-7000-8000-00000000fb02";

	private static final String MERGE_WEBHOOK = "00000000-0000-7000-8000-00000000fb03";

	private static final String PLATFORM_WEBHOOK = "00000000-0000-7000-8000-00000000fb04";

	private static final String MERGE_DRIFT = "00000000-0000-7000-8000-00000000fb05";

	private static final String MERGE_WEBHOOK_DONE = "00000000-0000-7000-8000-00000000fb06";

	private static final String GRANT_CHECKS = "vn.vibefix.repo.internal.GrantChecks.onWebhook"
			+ "(vn.vibefix.github.GitHubWebhookReceived)";

	private static final String ADMIN_ALERTS = "vn.vibefix.admin.internal.AdminAlerts.onConfigurationDrift"
			+ "(vn.vibefix.github.GitHubConfigurationDrift)";

	@Test
	void theAppLeavesTheSchemaTheRegistryAndTheDeliveryHistoryAndNothingElseGoes() throws Exception {
		String db = database.createScratchDatabase();
		flyway(db, BEFORE).migrate();
		execute(db, """
				INSERT INTO repo.merge_installation (installation_id, account_github_id, account_login, status, seen_at)
				VALUES (7702, 4243, 'quan-may', 'ACTIVE', now())
				""", """
				INSERT INTO repo.final_merge_authorization (id, job_id, product_id, customer_account_id,
				    repository_github_id, repository_name, status, deadline_at, requested_at, bound_at, ended_at)
				VALUES ('%s', gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 9902, 'quan-may/so-dat-ban',
				        'USED', now() + interval '7 days', now(), now(), now())
				""".formatted(USED), """
				INSERT INTO repo.final_merge_authorization (id, job_id, product_id, customer_account_id,
				    repository_github_id, repository_name, status, deadline_at, requested_at)
				VALUES ('%s', gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 9903, 'lan-dev/gom-shop',
				        'REQUESTED', now() + interval '7 days', now())
				""".formatted(OPEN), """
				INSERT INTO platform.event_publication (id, listener_id, event_type, serialized_event, publication_date,
				    completion_date)
				VALUES ('%1$s', '%5$s', 'vn.vibefix.github.GitHubWebhookReceived',
				        '{"app":"MERGE","event":"installation"}', now(), NULL),
				       ('%2$s', '%5$s', 'vn.vibefix.github.GitHubWebhookReceived',
				        '{"app":"PLATFORM","event":"member"}', now(), NULL),
				       ('%3$s', '%6$s', 'vn.vibefix.github.GitHubConfigurationDrift',
				        '{"app":"MERGE","day":"2026-11-14"}', now(), NULL),
				       ('%4$s', '%5$s', 'vn.vibefix.github.GitHubWebhookReceived',
				        '{"app":"MERGE","event":"installation"}', now(), now())
				""".formatted(MERGE_WEBHOOK, PLATFORM_WEBHOOK, MERGE_DRIFT, MERGE_WEBHOOK_DONE, GRANT_CHECKS, ADMIN_ALERTS), """
				INSERT INTO platform.event_publication_stalled (publication_id, stalled_at)
				VALUES ('%s', now()), ('%s', now())
				""".formatted(MERGE_WEBHOOK, PLATFORM_WEBHOOK), """
				INSERT INTO github.webhook_delivery (delivery_id, app, event, received_at)
				VALUES ('d-merge', 'MERGE', 'installation_repositories', now()),
				       ('d-platform', 'PLATFORM', 'pull_request', now())
				""");

		assertThat(rows(db, "SELECT count(*)::text FROM repo.merge_installation"))
			.as("the installations are there before the migration")
			.containsExactly("1");

		flyway(db, AFTER).migrate();

		assertThat(rows(db, "SELECT coalesce(to_regclass('repo.merge_installation')::text, 'gone')"))
			.as("the Merge App's installations go with the App")
			.containsExactly("gone");
		assertThat(rows(db, """
				SELECT count(*)::text FROM information_schema.columns
				WHERE table_schema = 'repo' AND table_name = 'final_merge_authorization' AND column_name = 'bound_at'
				""")).as("bound_at, which Story 23.9a left for this story").containsExactly("0");
		assertThat(rows(db, """
				SELECT id::text || ' ' || status || ' ' || repository_name || ' ' || repository_github_id
				       || ' ' || (deadline_at IS NOT NULL) || ' ' || (customer_account_id IS NOT NULL)
				       || ' ' || coalesce(end_reason, '-')
				FROM repo.final_merge_authorization ORDER BY repository_github_id
				""")).as("both rows and every other column survive")
			.containsExactly(USED + " USED quan-may/so-dat-ban 9902 true true -",
					OPEN + " REQUESTED lan-dev/gom-shop 9903 true true -");
		assertThat(rows(db, "SELECT id::text FROM platform.event_publication ORDER BY id"))
			.as("AD-9 would replay a MERGE payload into an App constant that is gone; another App's stays, and a"
					+ " completed one waits for the purge")
			.containsExactly(PLATFORM_WEBHOOK, MERGE_WEBHOOK_DONE);
		assertThat(rows(db, "SELECT publication_id::text FROM platform.event_publication_stalled"))
			.containsExactly(PLATFORM_WEBHOOK);
		assertThat(rows(db, "SELECT delivery_id FROM github.webhook_delivery ORDER BY delivery_id"))
			.as("the retired App's 30-day deduplication history goes; the Platform App's stays")
			.containsExactly("d-platform");
		assertThatCode(() -> execute(db, """
				INSERT INTO github.webhook_delivery (delivery_id, app, event, received_at)
				VALUES ('d-merge-again', 'MERGE', 'installation', now())
				""")).as("and the narrowed CHECK keeps no MERGE row again").isInstanceOf(SQLException.class);
		assertThatCode(() -> execute(db, """
				INSERT INTO github.webhook_delivery (delivery_id, app, event, received_at)
				VALUES ('d-platform-2', 'PLATFORM', 'repository', now())
				""")).as("the App that is left is unaffected").doesNotThrowAnyException();
	}

	/**
	 * The {@code repo} migration's header promises that a database that never had the table or the column migrates
	 * all the same, and Flyway keys on version, so nothing can re-run the file: its two statements are run again
	 * here against the migrated database, where neither object exists any more.
	 */
	@Test
	void theRepoMigrationRunsOnADatabaseThatNeverHadTheTableOrTheColumn() throws Exception {
		String db = database.createScratchDatabase();
		flyway(db, AFTER).migrate();

		assertThatCode(() -> execute(db, """
				ALTER TABLE repo.final_merge_authorization
				    DROP COLUMN IF EXISTS bound_at
				""", "DROP TABLE IF EXISTS repo.merge_installation")).doesNotThrowAnyException();

		assertThat(rows(db, "SELECT coalesce(to_regclass('repo.merge_installation')::text, 'gone')"))
			.containsExactly("gone");
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
