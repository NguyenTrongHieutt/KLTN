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
 * Story 23.11b's forward migration (R6): {@code repo.job_grant} takes {@code read_only}, and
 * {@code job_grant_read_only_check} refuses the {@code WRITE} status and a {@code write_at} on such a row -- which is
 * what makes "nobody ever gets write on an Assessment Job's copy" an invariant of the table rather than a rule each
 * caller must remember. Every other CHECK stays, every row there was reads {@code false}, and a grant that is not
 * read-only still reaches {@code WRITE}.
 */
class ReadOnlyGrantMigrationIT {

	private static final PostgresTestDatabase database = PostgresTestDatabase.instance();

	private static final String BEFORE = "202611180900";

	private static final String AFTER = "202611190900";

	private static final String READ = "00000000-0000-7000-8000-00000000fb01";

	private static final String TEAM = "00000000-0000-7000-8000-00000000fb02";

	@Test
	void onlyTheReadOnlyCheckIsAddedAndItRefusesEveryWriteOnAReadOnlyGrant() throws Exception {
		String db = database.createScratchDatabase();
		flyway(db, BEFORE).migrate();
		List<String> before = checks(db);
		execute(db, grant(READ, "MENTOR"));

		flyway(db, AFTER).migrate();

		List<String> after = checks(db);
		assertThat(after).as("every CHECK there was stays").containsAll(before).hasSize(before.size() + 1);
		assertThat(after).anySatisfy(check -> assertThat(check).contains("read_only").contains("write_at"));
		assertThat(rows(db, "SELECT read_only::TEXT FROM repo.job_grant WHERE id = '%s'".formatted(READ)))
			.as("a grant from before this story grants write as it did").containsExactly("false");
		execute(db, "UPDATE repo.job_grant SET status = 'WRITE', write_at = now() WHERE id = '%s'".formatted(READ));

		execute(db, grant(TEAM, "CREW"), "UPDATE repo.job_grant SET read_only = TRUE WHERE id = '%s'".formatted(TEAM));
		assertThatThrownBy(() -> execute(db, """
				UPDATE repo.job_grant SET status = 'WRITE', write_at = now() WHERE id = '%s'
				""".formatted(TEAM))).as("a read-only grant never reaches WRITE").isInstanceOf(SQLException.class);
		assertThatThrownBy(() -> execute(db, "UPDATE repo.job_grant SET write_at = now() WHERE id = '%s'".formatted(TEAM)))
			.as("nor holds a write time").isInstanceOf(SQLException.class);
		assertThatThrownBy(() -> execute(db, """
				UPDATE repo.job_grant SET read_only = TRUE WHERE id = '%s'
				""".formatted(READ))).as("nor is a grant that already writes marked read-only").isInstanceOf(SQLException.class);
		assertThat(rows(db, "SELECT role || ' ' || status FROM repo.job_grant WHERE id IN ('%s', '%s') ORDER BY id"
			.formatted(READ, TEAM))).containsExactly("MENTOR WRITE", "CREW ACCEPTED");
	}

	/** An accepted grant of {@code role} on an Intermediary Repository. */
	private static String grant(String id, String role) {
		return """
				INSERT INTO repo.job_grant (id, job_id, product_id, account_id, role, github_login, github_user_id,
				    repository_github_id, repository_name, crew_change, status, accepted_at, created_at)
				VALUES ('%s', gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), '%s', 'hung-dev', 7801, 9801,
				    'vibefix-dev/job-intermediary', false, 'ACCEPTED', now(), now())
				""".formatted(id, role);
	}

	private static List<String> checks(String db) throws SQLException {
		return rows(db, """
				SELECT pg_get_constraintdef(oid) FROM pg_constraint
				WHERE conrelid = 'repo.job_grant'::regclass AND contype = 'c' ORDER BY 1
				""");
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
