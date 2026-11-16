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
 * Story 23.10c's four forward migrations (FR-38, D10, AD-8, AD-9) on a database that still holds the ZIP Fallback:
 * a stored fallback row, two authorizations that ended by the retired paths, the fallback's notification, its
 * queued email and its own operation, and an undelivered publication of the deleted
 * {@code FinalMergeAuthorizationEnded} with its stalled mark and the listener's replay record.
 * <p>
 * Afterwards {@code repo.zip_fallback} is gone, the two retired {@code end_reason}s read {@code NULL} while the
 * rows keep their {@code ENDED} status and their {@code ended_at} (AD-8), the CHECK admits only the three endings
 * that are left, and every retired notification, operation and publication is gone. What stays: the audit entries
 * of those Jobs, a notification and an email of another kind, an operation of another type, a completed
 * publication of the deleted event (the registry's purge takes it) and another listener's own rows.
 */
class RetireZipFallbackMigrationIT {

	private static final PostgresTestDatabase database = PostgresTestDatabase.instance();

	/** The version right before this story's first migration. */
	private static final String BEFORE = "202611151001";

	/** The last of its four. */
	private static final String AFTER = "202611160903";

	private static final String FALLBACK = "00000000-0000-7000-8000-000000010a01";

	private static final String DECLINED = "00000000-0000-7000-8000-000000010a02";

	private static final String DEADLINE = "00000000-0000-7000-8000-000000010a03";

	private static final String JOB = "00000000-0000-7000-8000-000000010a04";

	private static final String ENDED = "00000000-0000-7000-8000-000000010a05";

	private static final String ENDED_DONE = "00000000-0000-7000-8000-000000010a06";

	private static final String OTHER = "00000000-0000-7000-8000-000000010a07";

	@Test
	void theFallbackTableItsRowsAndItsUndeliverableEventsGo() throws Exception {
		String db = database.createScratchDatabase();
		flyway(db, BEFORE).migrate();
		execute(db, """
				INSERT INTO repo.final_merge_authorization (id, job_id, product_id, customer_account_id,
				    repository_github_id, repository_name, status, requested_at, ended_at, end_reason)
				VALUES ('%1$s', '%4$s', gen_random_uuid(), gen_random_uuid(), 9801, 'quan-may/so-dat-ban', 'ENDED', now(),
				        now(), 'ZIP_FALLBACK'),
				       ('%2$s', gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 9802, 'quan-may/kho-hang',
				        'ENDED', now(), now(), 'DECLINED'),
				       ('%3$s', gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 9803, 'quan-may/dat-ban-v2',
				        'ENDED', now(), now(), 'DEADLINE')
				""".formatted(FALLBACK, DECLINED, DEADLINE, JOB), """
				INSERT INTO repo.zip_fallback (job_id, authorization_id, product_id, reason, status, archive_file_id,
				    created_at, ready_at)
				SELECT job_id, id, product_id, 'MERGE_REFUSED', 'READY', gen_random_uuid(), now(), now()
				FROM repo.final_merge_authorization WHERE id = '%s'
				""".formatted(FALLBACK), """
				INSERT INTO audit.audit_entry (id, occurred_at, actor_type, action, job_id)
				VALUES (gen_random_uuid(), now(), 'SYSTEM', 'repo.zip_fallback_started', '%1$s'),
				       (gen_random_uuid(), now(), 'SYSTEM', 'repo.final_merge_ended', '%1$s')
				""".formatted(JOB), """
				INSERT INTO notification.notification (id, account_id, type, causing_record_id, params, link, created_at)
				VALUES (gen_random_uuid(), gen_random_uuid(), 'repo.zip_fallback_ready', gen_random_uuid(),
				        '{}', '/cong-viec', now()),
				       (gen_random_uuid(), gen_random_uuid(), 'repo.download_reminder', gen_random_uuid(),
				        '{}', '/cong-viec', now())
				""", """
				INSERT INTO operations.external_operation (id, operation_key, type, lane, priority, status, payload,
				    next_attempt_at, created_at)
				VALUES (gen_random_uuid(), 'repo.zip_fallback:retired', 'repo.zip_fallback', 'GITHUB_LONG', 0, 'PENDING',
				        '{}', now(), now()),
				       (gen_random_uuid(), 'notification.email:retired', 'notification.email', 'EMAIL', 0, 'PENDING',
				        '{"template":"repo.zip_fallback_ready"}', now(), now()),
				       (gen_random_uuid(), 'notification.email:kept', 'notification.email', 'EMAIL', 0, 'PENDING',
				        '{"template":"repo.download_reminder"}', now(), now())
				""", """
				INSERT INTO platform.event_publication (id, listener_id, event_type, serialized_event, publication_date,
				    completion_date)
				VALUES ('%1$s',
				        'vn.vibefix.repo.internal.ZipFallbacks.onAuthorizationEnded(vn.vibefix.repo.FinalMergeAuthorizationEnded)',
				        'vn.vibefix.repo.FinalMergeAuthorizationEnded', '{"reason":"DEADLINE"}', now(), NULL),
				       ('%2$s',
				        'vn.vibefix.repo.internal.ZipFallbacks.onAuthorizationEnded(vn.vibefix.repo.FinalMergeAuthorizationEnded)',
				        'vn.vibefix.repo.FinalMergeAuthorizationEnded', '{"reason":"ZIP_FALLBACK"}', now(), now()),
				       ('%3$s',
				        'vn.vibefix.repo.internal.Revocations.onJobTransitioned(vn.vibefix.job.JobTransitioned)',
				        'vn.vibefix.job.JobTransitioned', '{}', now(), NULL)
				""".formatted(ENDED, ENDED_DONE, OTHER), """
				INSERT INTO platform.event_publication_stalled (publication_id, stalled_at)
				VALUES ('%s', now()), ('%s', now())
				""".formatted(ENDED, OTHER), """
				INSERT INTO platform.processed_event (listener, event_id, processed_at)
				VALUES ('repo.ZipFallbacks.onAuthorizationEnded', gen_random_uuid(), now()),
				       ('repo.Revocations.onJobTransitioned', gen_random_uuid(), now())
				""");

		flyway(db, AFTER).migrate();

		assertThat(rows(db, "SELECT coalesce(to_regclass('repo.zip_fallback')::text, 'gone')"))
			.containsExactly("gone");
		assertThat(rows(db, "SELECT coalesce(to_regclass('repo.code_receipt')::text, 'gone')"))
			.as("the path that replaced it stays")
			.containsExactly("repo.code_receipt");
		assertThat(rows(db, """
				SELECT status || ' ' || coalesce(end_reason, '-') || ' ' || (ended_at IS NOT NULL)
				FROM repo.final_merge_authorization ORDER BY repository_github_id
				""")).as("the retired reasons go; the Jobs keep their ending and its time")
			.containsExactly("ENDED - true", "ENDED - true", "ENDED DEADLINE true");
		assertThat(rows(db, "SELECT action FROM audit.audit_entry ORDER BY action"))
			.as("the audit trail is never rewritten (AD-8)")
			.containsExactly("repo.final_merge_ended", "repo.zip_fallback_started");
		assertThat(rows(db, "SELECT type FROM notification.notification ORDER BY type"))
			.as("the fallback's notification goes; what people still need stays")
			.containsExactly("repo.download_reminder");
		assertThat(rows(db, "SELECT operation_key FROM operations.external_operation ORDER BY operation_key"))
			.as("the fallback's own operation and the email under its deleted template go")
			.containsExactly("notification.email:kept");
		assertThat(rows(db, "SELECT id::text FROM platform.event_publication ORDER BY id"))
			.as("a completed publication waits for the purge, and the other listener keeps its own")
			.containsExactly(ENDED_DONE, OTHER);
		assertThat(rows(db, "SELECT publication_id::text FROM platform.event_publication_stalled"))
			.containsExactly(OTHER);
		assertThat(rows(db, "SELECT listener FROM platform.processed_event ORDER BY listener"))
			.containsExactly("repo.Revocations.onJobTransitioned");
	}

	/**
	 * The CHECK admits only the three endings that are left, so no code can write a retired one again, while an
	 * ENDED row with no reason at all -- what the migrated rows are -- is admitted on purpose. A reason still
	 * implies an ended row.
	 */
	@Test
	void theCheckRefusesTheRetiredEndReasonsAndAdmitsAnEndingWithoutOne() throws Exception {
		String db = database.createScratchDatabase();
		flyway(db, AFTER).migrate();
		execute(db, """
				INSERT INTO repo.final_merge_authorization (id, job_id, product_id, customer_account_id,
				    repository_github_id, repository_name, status, requested_at, ended_at, end_reason)
				VALUES ('%s', gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 9804, 'quan-may/so-dat-ban',
				        'ENDED', now(), now(), 'CANCELLED'),
				       ('%s', gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 9805, 'quan-may/kho-hang',
				        'ENDED', now(), now(), NULL)
				""".formatted(FALLBACK, DECLINED));

		assertThatThrownBy(() -> execute(db,
				"UPDATE repo.final_merge_authorization SET end_reason = 'ZIP_FALLBACK' WHERE id = '" + FALLBACK + "'"))
			.hasMessageContaining("final_merge_authorization_end_reason_check");
		assertThatThrownBy(() -> execute(db,
				"UPDATE repo.final_merge_authorization SET end_reason = 'DECLINED' WHERE id = '" + FALLBACK + "'"))
			.hasMessageContaining("final_merge_authorization_end_reason_check");
		assertThatThrownBy(() -> execute(db, """
				UPDATE repo.final_merge_authorization SET status = 'USED', end_reason = 'CANCELLED' WHERE id = '%s'
				""".formatted(DECLINED))).hasMessageContaining("final_merge_authorization_ended_check");
		assertThat(rows(db, "SELECT count(*)::text FROM repo.final_merge_authorization")).containsExactly("2");
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
