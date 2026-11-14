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
 * Story 23.10a's to-do rename on a database from before it (FR-37, FR-38, D10, UX-DR118): the Customer's open
 * {@code repo.authorize_final_merge} becomes {@code repo.receive_code} and gains the {@code job} parameter its new
 * text names, read from the Job the row already points at; a row whose Job is gone has no title to name and goes,
 * because its text would show a raw placeholder. Nothing is left under the old key, open or closed.
 */
class ReceiveCodeTodoMigrationIT {

	private static final PostgresTestDatabase database = PostgresTestDatabase.instance();

	/** The migration before this one: Story 23.10a's own {@code repo.code_receipt} table. */
	private static final String BEFORE = "202611151000";

	private static final String JOB = "00000000-0000-7000-8000-00000000d001";

	private static final String GONE = "00000000-0000-7000-8000-00000000d002";

	private static final String LAN = "00000000-0000-7000-8000-00000000d101";

	private static final String MAI = "00000000-0000-7000-8000-00000000d102";

	@Test
	void theClosingChoiceToDoIsRenamedWithItsJobAndAnOrphanGoes() throws Exception {
		String db = database.createScratchDatabase();
		flyway(db, BEFORE).migrate();
		execute(db, """
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, job_types, rules, selection_days,
				    mentor_account_id, selected_application_id, selected_at, created_at, updated_at)
				VALUES ('%s', gen_random_uuid(), '%s', 'CLOSING', 'Sửa lỗi đặt bàn', ARRAY['BUG_FIX'], '{}'::jsonb, 14,
				    gen_random_uuid(), gen_random_uuid(), now(), now(), now())
				""".formatted(JOB, LAN), todo("repo.authorize_final_merge", LAN, JOB),
				todo("repo.authorize_final_merge", MAI, GONE), todo("repo.reconfirm_source", LAN, JOB));

		flyway(db, "latest").migrate();

		assertThat(rows(db, """
				SELECT todo_key || ' ' || coalesce(params ->> 'job', '-') FROM notification.todo
				""")).containsExactlyInAnyOrder("repo.receive_code Sửa lỗi đặt bàn", "repo.reconfirm_source -");
		assertThat(rows(db, """
				SELECT count(*) FROM notification.todo WHERE todo_key = 'repo.authorize_final_merge'
				""")).containsExactly("0");
		assertThat(rows(db, "SELECT account_id::text FROM notification.todo WHERE todo_key = 'repo.receive_code'"))
			.as("the row keeps its recipient")
			.containsExactly(LAN);
	}

	private static String todo(String key, String account, String job) {
		return """
				INSERT INTO notification.todo (id, todo_key, account_id, subject_id, tier, params, link, job_id, opened_at)
				VALUES (gen_random_uuid(), '%s', '%s', gen_random_uuid(), 1, '{}'::jsonb, '/viec-can-lam', '%s', now())
				""".formatted(key, account, job);
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
