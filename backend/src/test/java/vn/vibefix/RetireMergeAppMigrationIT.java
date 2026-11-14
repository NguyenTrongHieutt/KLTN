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
 * Story 23.9a's five forward migrations (FR-37, R1, D7, AD-9) on a database that still holds a Merge-App Final
 * Merge: a {@code MERGED} row, an authorization {@code BOUND} to an installation with its install state, the five
 * retired notifications, a queued email under a retired template, the 2-day reminder's Deadline, and an unfinished
 * publication to the removed webhook listener. After them the Final Merge reads {@code DONE} and the authorization
 * {@code REQUESTED}, every Merge-App column is gone, and the retired rows are gone with their stalled mark and
 * replay record. What stays: {@code repo.merge_installation} and {@code bound_at} (Story 23.9b),
 * {@code repo.final_merge_completed}, the FR-37 deadline, and every other module's rows.
 */
class RetireMergeAppMigrationIT {

	private static final PostgresTestDatabase database = PostgresTestDatabase.instance();

	/** The version right before this story's first migration. */
	private static final String BEFORE = "202611141610";

	/** The last of its five. */
	private static final String AFTER = "202611141704";

	private static final String AUTHORIZATION = "00000000-0000-7000-8000-00000000f901";

	private static final String JOB = "00000000-0000-7000-8000-00000000f902";

	private static final String WEBHOOK = "00000000-0000-7000-8000-00000000f903";

	private static final String WEBHOOK_DONE = "00000000-0000-7000-8000-00000000f904";

	private static final String TRANSITIONED = "00000000-0000-7000-8000-00000000f905";

	@Test
	void theFinalMergeIsMappedToTheRunAndEveryMergeAppRowGoes() throws Exception {
		String db = database.createScratchDatabase();
		flyway(db, BEFORE).migrate();
		execute(db, """
				INSERT INTO repo.merge_installation (installation_id, account_github_id, account_login, status, seen_at)
				VALUES (7701, 4242, 'quan-may', 'ACTIVE', now())
				""", """
				INSERT INTO repo.final_merge_authorization (id, job_id, product_id, customer_account_id,
				    repository_github_id, repository_name, status, installation_id, state_hash, state_expires_at,
				    deadline_at, refusal, refused_at, request_org, request_seen_at, request_refused_at, requested_at, bound_at)
				VALUES ('%1$s', '%2$s', gen_random_uuid(), gen_random_uuid(), 9901, 'quan-may/so-dat-ban', 'BOUND', 7701,
				        repeat('c', 64), now() + interval '1 hour', now() + interval '7 days',
				        'MERGE_APP_WRONG_REPOSITORY', now(), 'quan-may', now(), now(), now(), now())
				""".formatted(AUTHORIZATION, JOB), """
				INSERT INTO repo.final_merge (authorization_id, job_id, product_id, status, intermediary_sha, source_branch,
				    branch, pull_number, pull_url, merged_at, created_at)
				SELECT '%1$s', '%2$s', product_id, 'MERGED', repeat('a', 40), 'main', 'vibefix/final-merge-%1$s', 7,
				       'https://github.com/quan-may/so-dat-ban/pull/7', now(), now()
				FROM repo.final_merge_authorization WHERE id = '%1$s'
				""".formatted(AUTHORIZATION, JOB), """
				INSERT INTO notification.notification (id, account_id, type, causing_record_id, params, link, created_at)
				VALUES (gen_random_uuid(), gen_random_uuid(), 'repo.final_merge_authorization_pending', gen_random_uuid(),
				        '{}', '/cong-viec', now()),
				       (gen_random_uuid(), gen_random_uuid(), 'repo.final_merge_authorization_reminder', gen_random_uuid(),
				        '{}', '/cong-viec', now()),
				       (gen_random_uuid(), gen_random_uuid(), 'repo.final_merge_authorized', gen_random_uuid(),
				        '{}', '/cong-viec', now()),
				       (gen_random_uuid(), gen_random_uuid(), 'repo.merge_app_request_refused', gen_random_uuid(),
				        '{}', '/cong-viec', now()),
				       (gen_random_uuid(), gen_random_uuid(), 'repo.merge_app_selection_widened', gen_random_uuid(),
				        '{}', '/cong-viec', now()),
				       (gen_random_uuid(), gen_random_uuid(), 'repo.final_merge_completed', gen_random_uuid(),
				        '{}', '/cong-viec', now()),
				       (gen_random_uuid(), gen_random_uuid(), 'repo.zip_fallback_ready', gen_random_uuid(),
				        '{}', '/cong-viec', now())
				""", """
				INSERT INTO operations.external_operation (id, operation_key, type, lane, priority, status, payload,
				    next_attempt_at, created_at)
				VALUES (gen_random_uuid(), 'notification.email:retired', 'notification.email', 'EMAIL', 0, 'PENDING',
				        '{"template":"repo.final_merge_authorization_reminder"}', now(), now()),
				       (gen_random_uuid(), 'notification.email:kept', 'notification.email', 'EMAIL', 0, 'PENDING',
				        '{"template":"repo.final_merge_completed"}', now(), now())
				""", """
				INSERT INTO timers.deadline (id, kind, owner, subject_id, generation, job_id, pausable, status, due_at,
				    created_at)
				VALUES (gen_random_uuid(), 'repo.final_merge_reminder', 'repo', '%1$s', '%1$s', '%2$s', true, 'PENDING',
				        now() + interval '5 days', now()),
				       (gen_random_uuid(), 'repo.final_merge_deadline', 'repo', '%1$s', '%1$s', '%2$s', true, 'PENDING',
				        now() + interval '7 days', now())
				""".formatted(AUTHORIZATION, JOB), """
				INSERT INTO platform.event_publication (id, listener_id, event_type, serialized_event, publication_date,
				    completion_date)
				VALUES ('%1$s',
				        'vn.vibefix.repo.internal.FinalMergeListeners.onWebhook(vn.vibefix.github.GitHubWebhookReceived)',
				        'vn.vibefix.github.GitHubWebhookReceived', '{}', now(), NULL),
				       ('%2$s',
				        'vn.vibefix.repo.internal.FinalMergeListeners.onWebhook(vn.vibefix.github.GitHubWebhookReceived)',
				        'vn.vibefix.github.GitHubWebhookReceived', '{}', now(), now()),
				       ('%3$s',
				        'vn.vibefix.repo.internal.FinalMergeListeners.onJobTransitioned(vn.vibefix.job.JobTransitioned)',
				        'vn.vibefix.job.JobTransitioned', '{}', now(), NULL)
				""".formatted(WEBHOOK, WEBHOOK_DONE, TRANSITIONED), """
				INSERT INTO platform.event_publication_stalled (publication_id, stalled_at)
				VALUES ('%s', now()), ('%s', now())
				""".formatted(WEBHOOK, TRANSITIONED), """
				INSERT INTO platform.processed_event (listener, event_id, processed_at)
				VALUES ('repo.FinalMergeListeners.onWebhook', gen_random_uuid(), now()),
				       ('repo.FinalMergeListeners.onJobTransitioned', gen_random_uuid(), now())
				""");

		flyway(db, AFTER).migrate();

		assertThat(rows(db, "SELECT status || ' ' || (merged_at IS NOT NULL) FROM repo.final_merge"))
			.as("MERGED becomes DONE, and its time stays")
			.containsExactly("DONE true");
		assertThat(rows(db, """
				SELECT status || ' ' || coalesce(end_reason, '-') FROM repo.final_merge_authorization
				""")).as("BOUND becomes REQUESTED, still open").containsExactly("REQUESTED -");
		assertThat(rows(db, """
				SELECT string_agg(column_name, ' ' ORDER BY column_name) FROM information_schema.columns
				WHERE table_schema = 'repo' AND table_name = 'final_merge_authorization'
				  AND column_name IN ('installation_id', 'state_hash', 'state_expires_at', 'request_org', 'request_seen_at',
				                      'request_refused_at', 'refusal', 'refused_at')
				""")).as("every Merge-App column of the authorization is gone").containsOnlyNulls();
		assertThat(rows(db, """
				SELECT string_agg(column_name, ' ' ORDER BY column_name) FROM information_schema.columns
				WHERE table_schema = 'repo' AND table_name = 'final_merge' AND column_name IN ('pull_number', 'pull_url')
				""")).as("the pull request vibefix opened is gone").containsOnlyNulls();
		assertThat(rows(db, """
				SELECT string_agg(column_name, ' ' ORDER BY column_name) FROM information_schema.columns
				WHERE table_schema = 'repo' AND table_name = 'final_merge'
				  AND column_name IN ('correlation_id', 'dispatched_at', 'run_status', 'run_conclusion')
				""")).as("the run takes its place").containsExactly("correlation_id dispatched_at run_conclusion run_status");
		assertThat(rows(db, """
				SELECT coalesce(to_regclass('repo.merge_installation')::text, 'gone') || ' '
				       || (SELECT count(*)::text FROM information_schema.columns
				           WHERE table_schema = 'repo' AND table_name = 'final_merge_authorization' AND column_name = 'bound_at')
				""")).as("Story 23.9b retires the App itself and bound_at").containsExactly("repo.merge_installation 1");
		assertThat(rows(db, "SELECT type FROM notification.notification ORDER BY type"))
			.as("the five Merge-App notifications go; what people really got stays")
			.containsExactly("repo.final_merge_completed", "repo.zip_fallback_ready");
		assertThat(rows(db, "SELECT operation_key FROM operations.external_operation ORDER BY operation_key"))
			.as("only the email under a deleted template goes")
			.containsExactly("notification.email:kept");
		assertThat(rows(db, "SELECT kind FROM timers.deadline ORDER BY kind"))
			.as("the FR-37 deadline still decides the closing choice")
			.containsExactly("repo.final_merge_deadline");
		assertThat(rows(db, "SELECT id::text FROM platform.event_publication ORDER BY id"))
			.as("a completed publication waits for the purge, and the other listener keeps its own")
			.containsExactly(WEBHOOK_DONE, TRANSITIONED);
		assertThat(rows(db, "SELECT publication_id::text FROM platform.event_publication_stalled"))
			.containsExactly(TRANSITIONED);
		assertThat(rows(db, "SELECT listener FROM platform.processed_event ORDER BY listener"))
			.containsExactly("repo.FinalMergeListeners.onJobTransitioned");
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
