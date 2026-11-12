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
 * Story 23.5c's forward migration (AD-45, R12): every Product, live or deleted, reads {@code READY} with no workflow
 * blob and no check time, since nothing is known of it yet; the status CHECK allows the five statuses only, a blob is a
 * 40-hex blob SHA, and only a {@code READY} Product holds one.
 */
class ProductSourceStatusMigrationIT {

	private static final PostgresTestDatabase database = PostgresTestDatabase.instance();

	private static final String BEFORE = "202611131000";

	private static final String AFTER = "202611131200";

	private static final String LIVE = "00000000-0000-7000-8000-00000000c501";

	private static final String DELETED = "00000000-0000-7000-8000-00000000c502";

	private static final String BLOB = "db448cf8fe316ef7876e5a6b0556a554ae74c976";

	@Test
	void everyProductReadsReadyWithNothingKnownAndTheChecksHold() throws Exception {
		String db = database.createScratchDatabase();
		flyway(db, BEFORE).migrate();
		execute(db, product(LIVE, 501, "NULL"), product(DELETED, 502, "now()"));

		flyway(db, AFTER).migrate();

		assertThat(rows(db, """
				SELECT id || ' ' || source_status || ' ' || coalesce(workflow_blob, '-') || ' '
				       || coalesce(source_checked_at::text, '-')
				FROM product.product ORDER BY id
				""")).containsExactly(LIVE + " READY - -", DELETED + " READY - -");
		for (String status : List.of("SOURCE_APP_MISSING", "SOURCE_APP_WRONG_REPOSITORY", "WORKFLOW_MISSING",
				"WORKFLOW_MISMATCH", "READY")) {
			execute(db, "UPDATE product.product SET source_status = '%s', source_checked_at = now() WHERE id = '%s'"
				.formatted(status, LIVE));
		}
		execute(db, "UPDATE product.product SET workflow_blob = '%s' WHERE id = '%s'".formatted(BLOB, LIVE));
		assertThatThrownBy(() -> execute(db, "UPDATE product.product SET source_status = 'RELINK_GITHUB' WHERE id = '%s'"
			.formatted(LIVE))).as("no other status").isInstanceOf(SQLException.class);
		assertThatThrownBy(() -> execute(db, "UPDATE product.product SET source_status = 'WORKFLOW_MISMATCH' WHERE id = '%s'"
			.formatted(LIVE))).as("a blob only with READY").isInstanceOf(SQLException.class);
		assertThatThrownBy(() -> execute(db, "UPDATE product.product SET workflow_blob = 'not-a-blob' WHERE id = '%s'"
			.formatted(LIVE))).as("a blob SHA").isInstanceOf(SQLException.class);
		execute(db, "UPDATE product.product SET source_status = 'WORKFLOW_MISSING', workflow_blob = NULL WHERE id = '%s'"
			.formatted(LIVE));
	}

	private static String product(String id, long repository, String deletedAt) {
		return """
				INSERT INTO product.product (id, customer_account_id, name, description, ai_tools, repository_github_id,
				    repository_full_name, repository_owner_type, repository_default_branch, public_notice_version,
				    copy_permission_version, detection_notice_version, acknowledged_at, created_at, deleted_at)
				VALUES ('%s', gen_random_uuid(), 'Sổ đặt bàn', 'Đặt bàn.', ARRAY['LOVABLE'], %d, 'lan/so-dat-ban-%d', 'User',
				    'main', '2.0', '2.0', '2.0', now(), now(), %s)
				""".formatted(id, repository, repository, deletedAt);
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
