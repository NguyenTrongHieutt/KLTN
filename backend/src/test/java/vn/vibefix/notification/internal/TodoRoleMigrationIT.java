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
 * Story 20.6's forward migrations on a database from before them (AD-27, AD-19): the open to-dos of a Job whose key
 * reaches one audience class get that role, a key with several and an account to-do stay without one; and
 * {@code identity.account.role} goes with its checks while every row keeps its kind.
 */
class TodoRoleMigrationIT {

	private static final PostgresTestDatabase database = PostgresTestDatabase.instance();

	private static final String BEFORE = "202610271301";

	private static final String JOB = "00000000-0000-7000-8000-00000000c001";

	private static final String LAN = "00000000-0000-7000-8000-00000000c101";

	private static final String HUNG = "00000000-0000-7000-8000-00000000c102";

	private static final String VY = "00000000-0000-7000-8000-00000000c103";

	private static final String ADMIN = "00000000-0000-7000-8000-00000000c104";

	@Test
	void eachSingleAudienceToDoOfAJobGetsItsRoleAndTheAccountRoleGoes() throws Exception {
		String db = database.createScratchDatabase();
		flyway(db, BEFORE).migrate();
		execute(db, """
				INSERT INTO identity.account (id, kind, github_user_id, github_login, role, created_at)
				VALUES ('%s', 'USER', 7201, 'lan', 'CUSTOMER', now()), ('%s', 'USER', 7202, 'hung', 'MENTOR', now()),
				       ('%s', 'USER', 7203, 'vy', 'STUDENT', now())
				""".formatted(LAN, HUNG, VY), """
				INSERT INTO identity.account (id, kind, role, created_at) VALUES ('%s', 'ADMINISTRATOR', 'ADMINISTRATOR', now())
				""".formatted(ADMIN), todo("job.review_agreement", LAN, JOB), todo("job.draft_agreement", HUNG, JOB),
				todo("work.fix_returned_item", VY, JOB), todo("job.answer_closure", LAN, JOB),
				todo("identity.link_github", LAN, null), todo("matching.answer_invitation_to_apply", HUNG, JOB));

		flyway(db, "latest").migrate();

		assertThat(rows(db, "SELECT todo_key || ' ' || coalesce(role, '-') FROM notification.todo ORDER BY todo_key"))
			.containsExactly("identity.link_github -", "job.answer_closure -", "job.draft_agreement MENTOR",
					"job.review_agreement CUSTOMER", "matching.answer_invitation_to_apply -", "work.fix_returned_item CREW");
		assertThat(rows(db, """
				SELECT count(*) FROM information_schema.columns
				WHERE table_schema = 'identity' AND table_name = 'account' AND column_name = 'role'
				""")).containsExactly("0");
		assertThat(rows(db, "SELECT kind FROM identity.account ORDER BY id")).containsExactly("USER", "USER", "USER",
				"ADMINISTRATOR");
		assertThat(rows(db, """
				SELECT count(*) FROM pg_constraint
				WHERE conrelid = 'identity.account'::regclass AND pg_get_constraintdef(oid) LIKE '%role%'
				""")).containsExactly("0");
	}

	private static String todo(String key, String account, String job) {
		return """
				INSERT INTO notification.todo (id, todo_key, account_id, subject_id, tier, params, link, job_id, opened_at)
				VALUES (gen_random_uuid(), '%s', '%s', gen_random_uuid(), 2, '{}'::jsonb, '/viec-can-lam', %s, now())
				""".formatted(key, account, job == null ? "NULL" : "'" + job + "'");
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
