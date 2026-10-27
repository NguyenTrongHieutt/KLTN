package vn.vibefix.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import vn.vibefix.support.PostgresTestDatabase;

/**
 * Story 20.1's forward migration on a database that still holds sessions from before it (AD-19): they hold
 * the old serialized authentication with the account's role, so every session and its attributes go, and
 * everyone signs in again once.
 */
class SessionResetMigrationIT {

	private static final PostgresTestDatabase database = PostgresTestDatabase.instance();

	private static final String BEFORE = "202610261205";

	@Test
	void everySessionFromBeforeTheMigrationIsRemoved() throws Exception {
		String db = database.createScratchDatabase();
		flyway(db, BEFORE).migrate();
		execute(db, """
				INSERT INTO platform.spring_session (primary_id, session_id, creation_time, last_access_time,
				    max_inactive_interval, expiry_time, principal_name)
				VALUES ('00000000-0000-7000-8000-00000000d001', '00000000-0000-7000-8000-00000000d101', 1, 1, 1800,
				        9999999999999, '00000000-0000-7000-8000-00000000d201'),
				       ('00000000-0000-7000-8000-00000000d002', '00000000-0000-7000-8000-00000000d102', 1, 1, 1800,
				        9999999999999, NULL)
				""", """
				INSERT INTO platform.spring_session_attributes (session_primary_id, attribute_name, attribute_bytes)
				VALUES ('00000000-0000-7000-8000-00000000d001', 'SPRING_SECURITY_CONTEXT', '\\x00'),
				       ('00000000-0000-7000-8000-00000000d002', 'pendingWelcome', '\\x00')
				""");

		flyway(db, "latest").migrate();

		assertThat(count(db, "platform.spring_session")).isZero();
		assertThat(count(db, "platform.spring_session_attributes")).isZero();
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
		try (Connection connection = database.migratorConnection(db);
				Statement statement = connection.createStatement()) {
			for (String sql : statements) {
				statement.execute(sql);
			}
		}
	}

	private static long count(String db, String table) throws SQLException {
		try (Connection connection = database.migratorConnection(db);
				Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
			rows.next();
			return rows.getLong(1);
		}
	}

}
