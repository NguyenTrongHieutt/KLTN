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
 * Story 23.7's forward migration (R5): {@code repo.job_grant} takes the role {@code CUSTOMER} and the status
 * {@code LAPSED}. Only the role and status CHECKs are replaced, by their definitions; every other CHECK stays. A
 * Customer's read inserts, never reaches {@code WRITE}, and may lapse; nobody else's grant lapses.
 */
class CustomerReadGrantMigrationIT {

	private static final PostgresTestDatabase database = PostgresTestDatabase.instance();

	private static final String BEFORE = "202611141100";

	private static final String AFTER = "202611141300";

	private static final String CUSTOMER = "00000000-0000-7000-8000-00000000f701";

	private static final String CREW = "00000000-0000-7000-8000-00000000f702";

	@Test
	void onlyTheRoleAndStatusChecksAreReplacedAndTheCustomersReadFitsThem() throws Exception {
		String db = database.createScratchDatabase();
		flyway(db, BEFORE).migrate();
		List<String> before = checks(db);
		List<String> kept = before.stream().filter(check -> !check.contains("CREW") && !check.contains("MEMBER_DRIFT")).toList();
		assertThat(before).as("the role and the status CHECKs, and the others").hasSize(kept.size() + 2);

		flyway(db, AFTER).migrate();

		List<String> after = checks(db);
		assertThat(after).as("every other CHECK stays").containsAll(kept).hasSize(kept.size() + 4);
		assertThat(after).anySatisfy(check -> assertThat(check).contains("'CUSTOMER'").contains("'CREW'").contains("'MENTOR'"));
		assertThat(after).anySatisfy(check -> assertThat(check).contains("'MEMBER_DRIFT'").contains("'LAPSED'"));

		execute(db, row(CUSTOMER, "CUSTOMER"), row(CREW, "CREW"));
		assertThatThrownBy(() -> execute(db, """
				UPDATE repo.job_grant SET status = 'WRITE', write_at = now() WHERE id = '%s'
				""".formatted(CUSTOMER))).as("a Customer's read never reaches WRITE").isInstanceOf(SQLException.class);
		execute(db, "UPDATE repo.job_grant SET status = 'WRITE', write_at = now() WHERE id = '%s'".formatted(CREW));
		execute(db, "UPDATE repo.job_grant SET status = 'LAPSED' WHERE id = '%s'".formatted(CUSTOMER));
		assertThatThrownBy(() -> execute(db, "UPDATE repo.job_grant SET status = 'LAPSED', write_at = NULL WHERE id = '%s'"
			.formatted(CREW))).as("only the Customer's read lapses").isInstanceOf(SQLException.class);
		assertThat(rows(db, "SELECT role || ' ' || status FROM repo.job_grant WHERE id IN ('%s', '%s') ORDER BY id"
			.formatted(CUSTOMER, CREW))).containsExactly("CUSTOMER LAPSED", "CREW WRITE");
	}

	/** An accepted grant of {@code role}. */
	private static String row(String id, String role) {
		return """
				INSERT INTO repo.job_grant (id, job_id, product_id, account_id, role, github_login, github_user_id,
				    repository_github_id, repository_name, crew_change, status, accepted_at, created_at)
				VALUES ('%s', gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), '%s', 'lan-dev', 7701, 9701,
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
