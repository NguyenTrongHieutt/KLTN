package vn.vibefix.notification.internal;

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
 * Story 23.10a's to-do clean-up on a database from before it (FR-37, FR-38, D10, UX-DR118): the Customer's
 * {@code repo.authorize_final_merge} rows go, open and closed alike, because the key is retired and its replacement
 * {@code repo.receive_code} names the Job -- a title only the {@code job} schema keeps, which a {@code notification}
 * migration must not read (Conventions, Migrations). Every other key of the same Job is left exactly as it was.
 */
class ReceiveCodeTodoMigrationIT {

	private static final PostgresTestDatabase database = PostgresTestDatabase.instance();

	/** The migration before this one: Story 23.10a's own {@code repo.code_receipt} table. */
	private static final String BEFORE = "202611151000";

	private static final String JOB = "00000000-0000-7000-8000-00000000d001";

	private static final String LAN = "00000000-0000-7000-8000-00000000d101";

	private static final String MAI = "00000000-0000-7000-8000-00000000d102";

	@Test
	void theRetiredClosingChoiceToDosGoAndAnotherKeyOfTheSameJobStays() throws Exception {
		String db = database.createScratchDatabase();
		flyway(db, BEFORE).migrate();
		execute(db, todo("repo.authorize_final_merge", LAN, null), todo("repo.authorize_final_merge", MAI, "now()"),
				todo("repo.reconfirm_source", LAN, null));

		flyway(db, "latest").migrate();

		assertThat(rows(db, "SELECT todo_key FROM notification.todo"))
			.as("the retired key goes, open and closed alike")
			.containsExactly("repo.reconfirm_source");
		assertThat(rows(db, """
				SELECT account_id::text FROM notification.todo WHERE todo_key = 'repo.reconfirm_source'
				""")).as("the Job's other to-do keeps its recipient").containsExactly(LAN);
	}

	private static String todo(String key, String account, String closedAt) {
		return """
				INSERT INTO notification.todo (id, todo_key, account_id, subject_id, tier, params, link, job_id, opened_at,
				    closed_at)
				VALUES (gen_random_uuid(), '%s', '%s', gen_random_uuid(), 1, '{}'::jsonb, '/viec-can-lam', '%s', now(), %s)
				""".formatted(key, account, JOB, closedAt == null ? "NULL" : closedAt);
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
