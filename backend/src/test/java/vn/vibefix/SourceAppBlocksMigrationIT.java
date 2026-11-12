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
 * Story 23.5's forward migrations (AD-13, R2, R7): {@code SOURCE_NOT_PUBLIC} goes. A Setup it blocked, a Final Merge
 * it refused and the ZIP Fallback it routed to read {@code SOURCE_APP_MISSING}; the CHECKs allow the Source App's and
 * the workflow file's blocks and no longer the retired reason; {@code RELINK_GITHUB} stays until Story 23.5b; and
 * {@code product.source_app_binding} exists.
 */
class SourceAppBlocksMigrationIT {

	private static final PostgresTestDatabase database = PostgresTestDatabase.instance();

	private static final String BEFORE = "202611121500";

	private static final String AFTER = "202611121601";

	private static final String NOT_PUBLIC = "00000000-0000-7000-8000-00000000f301";

	private static final String RELINK = "00000000-0000-7000-8000-00000000f302";

	private static final String AUTHORIZATION = "00000000-0000-7000-8000-00000000f401";

	private static final String JOB = "00000000-0000-7000-8000-00000000f501";

	@Test
	void theRetiredReasonMapsToSourceAppMissingAndTheChecksAllowTheNewBlocks() throws Exception {
		String db = database.createScratchDatabase();
		flyway(db, BEFORE).migrate();
		execute(db, """
				INSERT INTO repo.job_setup (id, job_id, product_id, status, blocked_reason, started_at, finished_at)
				VALUES ('%s', gen_random_uuid(), gen_random_uuid(), 'BLOCKED', 'SOURCE_NOT_PUBLIC', now(), now()),
				       ('%s', gen_random_uuid(), gen_random_uuid(), 'BLOCKED', 'RELINK_GITHUB', now(), now())
				""".formatted(NOT_PUBLIC, RELINK), """
				INSERT INTO repo.final_merge_authorization (id, job_id, product_id, repository_github_id, repository_name,
				    status, requested_at, ended_at, end_reason)
				VALUES ('%s', '%s', gen_random_uuid(), 9301, 'lan/so-dat-ban', 'ENDED', now(), now(), 'ZIP_FALLBACK')
				""".formatted(AUTHORIZATION, JOB), """
				INSERT INTO repo.final_merge (authorization_id, job_id, product_id, status, refusal, created_at)
				VALUES ('%s', '%s', gen_random_uuid(), 'REFUSED', 'SOURCE_NOT_PUBLIC', now())
				""".formatted(AUTHORIZATION, JOB), """
				INSERT INTO repo.zip_fallback (job_id, authorization_id, product_id, reason, status, created_at)
				VALUES ('%s', '%s', gen_random_uuid(), 'SOURCE_NOT_PUBLIC', 'BUILDING', now())
				""".formatted(JOB, AUTHORIZATION));

		flyway(db, AFTER).migrate();

		assertThat(rows(db, "SELECT id || ' ' || blocked_reason FROM repo.job_setup ORDER BY id"))
			.containsExactly(NOT_PUBLIC + " SOURCE_APP_MISSING", RELINK + " RELINK_GITHUB");
		assertThat(rows(db, "SELECT refusal FROM repo.final_merge")).containsExactly("SOURCE_APP_MISSING");
		assertThat(rows(db, "SELECT reason FROM repo.zip_fallback")).containsExactly("SOURCE_APP_MISSING");
		assertThat(rows(db, """
				SELECT count(*)::text FROM pg_constraint
				WHERE conrelid IN ('repo.job_setup'::regclass, 'repo.zip_fallback'::regclass) AND contype = 'c'
				  AND pg_get_constraintdef(oid) LIKE '%SOURCE_NOT_PUBLIC%'
				""")).as("no CHECK names the retired reason").containsExactly("0");
		for (String block : List.of("SOURCE_APP_WRONG_REPOSITORY", "WORKFLOW_MISSING", "WORKFLOW_MISMATCH",
				"NO_ADMIN_PERMISSION")) {
			execute(db, "UPDATE repo.job_setup SET blocked_reason = '%s' WHERE id = '%s'".formatted(block, RELINK));
		}
		assertThatThrownBy(() -> execute(db, "UPDATE repo.job_setup SET blocked_reason = 'SOURCE_NOT_PUBLIC' WHERE id = '%s'"
			.formatted(NOT_PUBLIC))).isInstanceOf(SQLException.class);
		assertThatThrownBy(() -> execute(db, "UPDATE repo.zip_fallback SET reason = 'SOURCE_NOT_PUBLIC'"))
			.isInstanceOf(SQLException.class);
		assertThat(rows(db, "SELECT coalesce(to_regclass('product.source_app_binding')::text, 'gone')"))
			.containsExactly("product.source_app_binding");
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
