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
 * Story 23.2's forward migrations (D9, R16, AD-9) on a database that still holds recorded heads, a push-scan row,
 * and publications to the removed listeners: {@code repo.branch_head} and {@code repo.push_scan} are gone, and no
 * unfinished publication of {@code WorkingHeadMoved}, nor one to {@code HandoverVoids.onWorkingHeadMoved} or
 * {@code PushScans.onWebhook}, is left with its stalled mark, nor their replay records. A completed publication waits
 * for the purge, and the webhook's other listeners keep theirs.
 */
class RetireHeadTrackingMigrationIT {

	private static final PostgresTestDatabase database = PostgresTestDatabase.instance();

	private static final String BEFORE = "202611121307";

	private static final String AFTER = "202611121402";

	private static final String MOVED = "00000000-0000-7000-8000-00000000e401";

	private static final String HINTED = "00000000-0000-7000-8000-00000000e402";

	private static final String MOVED_DONE = "00000000-0000-7000-8000-00000000e403";

	private static final String GUARDED = "00000000-0000-7000-8000-00000000e404";

	private static final String VOIDED = "00000000-0000-7000-8000-00000000e405";

	@Test
	void theHeadTablesAndThePublicationsOfTheRemovedListenersGo() throws Exception {
		String db = database.createScratchDatabase();
		flyway(db, BEFORE).migrate();
		execute(db, """
				INSERT INTO repo.branch_head (repository_github_id, ref, job_id, sha, updated_at)
				VALUES (9101, 'refs/heads/main', gen_random_uuid(), repeat('a', 40), now()),
				       (9101, 'refs/tags/v1', gen_random_uuid(), repeat('b', 40), now())
				""", """
				INSERT INTO repo.push_scan (scan_run_id, job_id, product_id, repository_github_id, requested_at)
				VALUES (gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 9101, now())
				""", """
				INSERT INTO platform.event_publication (id, listener_id, event_type, serialized_event, publication_date,
				    completion_date)
				VALUES ('%1$s',
				        'vn.vibefix.delivery.internal.HandoverVoids.onWorkingHeadMoved(vn.vibefix.repo.WorkingHeadMoved)',
				        'vn.vibefix.repo.WorkingHeadMoved', '{}', now(), NULL),
				       ('%2$s',
				        'vn.vibefix.repo.internal.PushScans.onWebhook(vn.vibefix.github.GitHubWebhookReceived)',
				        'vn.vibefix.github.GitHubWebhookReceived', '{}', now(), NULL),
				       ('%3$s',
				        'vn.vibefix.delivery.internal.HandoverVoids.onWorkingHeadMoved(vn.vibefix.repo.WorkingHeadMoved)',
				        'vn.vibefix.repo.WorkingHeadMoved', '{}', now(), now()),
				       ('%4$s',
				        'vn.vibefix.repo.internal.PlatformGuard.onWebhook(vn.vibefix.github.GitHubWebhookReceived)',
				        'vn.vibefix.github.GitHubWebhookReceived', '{}', now(), NULL),
				       ('%5$s',
				        'vn.vibefix.delivery.internal.HandoverVoids.onJobTransitioned(vn.vibefix.job.JobTransitioned)',
				        'vn.vibefix.job.JobTransitioned', '{}', now(), NULL)
				""".formatted(MOVED, HINTED, MOVED_DONE, GUARDED, VOIDED), """
				INSERT INTO platform.event_publication_stalled (publication_id, stalled_at)
				VALUES ('%s', now()), ('%s', now()), ('%s', now())
				""".formatted(MOVED, HINTED, GUARDED), """
				INSERT INTO platform.processed_event (listener, event_id, processed_at)
				VALUES ('repo.PushScans.onWebhook', gen_random_uuid(), now()),
				       ('delivery.HandoverVoids.onWorkingHeadMoved', gen_random_uuid(), now()),
				       ('delivery.HandoverVoids.onJobTransitioned', gen_random_uuid(), now()),
				       ('repo.PlatformGuard.onWebhook', gen_random_uuid(), now())
				""");

		flyway(db, AFTER).migrate();

		assertThat(rows(db, """
				SELECT coalesce(to_regclass('repo.branch_head')::text, 'gone') || ' '
				       || coalesce(to_regclass('repo.push_scan')::text, 'gone')
				""")).containsExactly("gone gone");
		assertThat(rows(db, "SELECT coalesce(to_regclass('repo.handover_merge')::text, 'gone')")).as("the rest of repo stays")
			.containsExactly("repo.handover_merge");
		assertThat(rows(db, "SELECT id::text FROM platform.event_publication ORDER BY id"))
			.as("a completed publication waits for the purge, and other listeners keep theirs")
			.containsExactly(MOVED_DONE, GUARDED, VOIDED);
		assertThat(rows(db, "SELECT publication_id::text FROM platform.event_publication_stalled")).containsExactly(GUARDED);
		assertThat(rows(db, "SELECT listener FROM platform.processed_event ORDER BY listener"))
			.containsExactly("delivery.HandoverVoids.onJobTransitioned", "repo.PlatformGuard.onWebhook");
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
