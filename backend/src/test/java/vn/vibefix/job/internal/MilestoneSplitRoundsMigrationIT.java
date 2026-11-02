package vn.vibefix.job.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import vn.vibefix.support.PostgresTestDatabase;

/**
 * Story 22.2's forward migrations on a database from before them (FR-61, AD-29): the Mentor share cap moves to 30 only
 * if it still is the old default, so an Administrator's own choice stays, and a final Mentor-alone split gets when and how
 * it became final from when it was created.
 */
class MilestoneSplitRoundsMigrationIT {

	private static final PostgresTestDatabase database = PostgresTestDatabase.instance();

	private static final String BEFORE = "202611021540";

	private static final String CREATED = "2026-11-01T03:00:00Z";

	@ParameterizedTest
	@CsvSource({ "35, 35", "40, 30" })
	void theCapMovesOnlyFromTheOldDefaultAndAFinalSplitKeepsWhenItWasCreated(int before, int after) throws Exception {
		String db = database.createScratchDatabase();
		flyway(db, BEFORE).migrate();
		execute(db, "UPDATE settings.rule_value SET value = %d WHERE rule_key = 'MENTOR_SHARE_CAP_PERCENT'".formatted(before), """
				INSERT INTO job.milestone_split (id, job_id, product_id, milestone_id, milestone_number, status, cap_applies,
				    released_at, draft_due_at, final_version_no, created_at)
				VALUES (gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 1, 'FINAL', false,
				    '%1$s', '%1$s', 1, '%1$s')
				""".formatted(CREATED), """
				INSERT INTO job.milestone_split (id, job_id, product_id, milestone_id, milestone_number, status, cap_applies,
				    released_at, draft_due_at, created_at)
				VALUES (gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 2, 'AWAITING_DRAFT', true,
				    '%1$s', '%1$s', '%1$s')
				""".formatted(CREATED));

		flyway(db, "latest").migrate();

		assertThat(rows(db, "SELECT value FROM settings.rule_value WHERE rule_key = 'MENTOR_SHARE_CAP_PERCENT'"))
			.containsExactly(String.valueOf(after));
		assertThat(rows(db, "SELECT value FROM settings.rule_value WHERE rule_key = 'SPLIT_UNDECIDED_FLAG_DAYS'"))
			.containsExactly("7");
		assertThat(rows(db, """
				SELECT status || ':' || (final_at = created_at) || ':' || finality || ':' || confirmation_round
				FROM job.milestone_split WHERE milestone_number = 1
				""")).containsExactly("FINAL:true:MENTOR_ONLY:0");
		assertThat(rows(db, """
				SELECT status || ':' || (final_at IS NULL) || ':' || (finality IS NULL) || ':' || confirmation_round
				FROM job.milestone_split WHERE milestone_number = 2
				""")).containsExactly("AWAITING_DRAFT:true:true:0");
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
