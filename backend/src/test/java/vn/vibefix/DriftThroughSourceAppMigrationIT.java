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
 * Story 23.5b's forward migration (FR-20, R7): the drift poll reads through the Source App, so
 * {@code DELETED_OR_PRIVATE} goes. A warning of that kind reads {@code GONE}; the kind CHECK allows {@code GONE},
 * {@code APP_UNINSTALLED} and {@code WORKFLOW_CHANGED} besides the kinds that stay, and no longer the retired one.
 */
class DriftThroughSourceAppMigrationIT {

	private static final PostgresTestDatabase database = PostgresTestDatabase.instance();

	private static final String BEFORE = "202611121601";

	private static final String AFTER = "202611131000";

	private static final String GONE = "00000000-0000-7000-8000-00000000f601";

	private static final String COMMITS = "00000000-0000-7000-8000-00000000f602";

	private static final String RENAMED = "00000000-0000-7000-8000-00000000f603";

	@Test
	void theRetiredKindMapsToGoneAndTheCheckAllowsTheSourceAppKinds() throws Exception {
		String db = database.createScratchDatabase();
		flyway(db, BEFORE).migrate();
		execute(db, """
				INSERT INTO repo.drift_warning (id, job_id, product_id, kind, head_sha, full_name, commits, detected_at)
				VALUES ('%s', gen_random_uuid(), gen_random_uuid(), 'DELETED_OR_PRIVATE', NULL, 'lan/so-dat-ban', '{}', now()),
				       ('%s', gen_random_uuid(), gen_random_uuid(), 'COMMITS', 'abc', NULL, ARRAY['abc'], now()),
				       ('%s', gen_random_uuid(), gen_random_uuid(), 'RENAMED', NULL, 'lan/so-dat-ban-v2', '{}', now())
				""".formatted(GONE, COMMITS, RENAMED));

		flyway(db, AFTER).migrate();

		assertThat(rows(db, "SELECT id || ' ' || kind FROM repo.drift_warning ORDER BY id"))
			.containsExactly(GONE + " GONE", COMMITS + " COMMITS", RENAMED + " RENAMED");
		assertThat(rows(db, "SELECT full_name FROM repo.drift_warning WHERE id = '" + GONE + "'"))
			.as("the evidence stays").containsExactly("lan/so-dat-ban");
		assertThat(rows(db, """
				SELECT count(*)::text FROM pg_constraint
				WHERE conrelid = 'repo.drift_warning'::regclass AND contype = 'c'
				  AND pg_get_constraintdef(oid) LIKE '%DELETED_OR_PRIVATE%'
				""")).as("no CHECK names the retired kind").containsExactly("0");
		for (String kind : List.of("APP_UNINSTALLED", "WORKFLOW_CHANGED", "ARCHIVED", "TRANSFERRED", "GONE")) {
			execute(db, "UPDATE repo.drift_warning SET kind = '%s' WHERE id = '%s'".formatted(kind, GONE));
		}
		assertThatThrownBy(() -> execute(db, "UPDATE repo.drift_warning SET kind = 'DELETED_OR_PRIVATE' WHERE id = '%s'"
			.formatted(GONE))).isInstanceOf(SQLException.class);
		assertThatThrownBy(() -> execute(db, "UPDATE repo.drift_warning SET kind = 'PRIVATE' WHERE id = '%s'"
			.formatted(GONE))).isInstanceOf(SQLException.class);
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
