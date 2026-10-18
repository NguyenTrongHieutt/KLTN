package vn.vibefix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import vn.vibefix.support.PostgresTestDatabase;

/**
 * AD-25: the application starts only when the {@code platform.environment} row equals the active
 * profile, and it checks before any migration runs. Each scenario gets its own database.
 */
class EnvironmentGuardIT {

	private static final PostgresTestDatabase database = PostgresTestDatabase.instance();

	@Test
	void freshDatabaseIsStampedWithTheActiveProfileAndStartsAgain() throws Exception {
		String db = database.createScratchDatabase();

		try (ConfigurableApplicationContext ignored = start(db, "dev")) {
			assertThat(environmentRows(db)).containsExactly("dev");
		}
		try (ConfigurableApplicationContext ignored = start(db, "dev")) {
			assertThat(environmentRows(db)).containsExactly("dev");
		}
	}

	@Test
	void databaseOfAnotherEnvironmentIsRefused() throws Exception {
		String db = database.createScratchDatabase();
		try (ConfigurableApplicationContext ignored = start(db, "prod")) {
			assertThat(environmentRows(db)).containsExactly("prod");
		}
		List<String> historyBefore = migrationHistory(db);

		assertThatThrownBy(() -> start(db, "dev")).rootCause()
			.hasMessageContaining("[prod]")
			.hasMessageContaining("'dev'");
		assertThat(migrationHistory(db)).isEqualTo(historyBefore);
	}

	@Test
	void mismatchIsDetectedBeforeAnyMigrationRuns() throws Exception {
		String db = database.createScratchDatabase();
		try (Connection connection = database.migratorConnection(db);
				Statement statement = connection.createStatement()) {
			statement.execute("CREATE SCHEMA platform");
			statement.execute("CREATE TABLE platform.environment (name TEXT NOT NULL)");
			statement.execute("INSERT INTO platform.environment (name) VALUES ('prod')");
		}

		assertThatThrownBy(() -> start(db, "dev")).rootCause().hasMessageContaining("[prod]");
		assertThat(tableExists(db, "platform.flyway_schema_history")).isFalse();
	}

	@ParameterizedTest
	@MethodSource("profilesWithoutExactlyOneEnvironment")
	void startupFailsWithoutExactlyOneEnvironmentProfile(String profiles) throws Exception {
		String db = database.createScratchDatabase();

		assertThatThrownBy(() -> start(db, profiles)).rootCause()
			.hasMessageContaining("Exactly one environment profile");
		assertThat(tableExists(db, "platform.flyway_schema_history")).isFalse();
	}

	static Stream<String> profilesWithoutExactlyOneEnvironment() {
		return Stream.of("", "sandbox", "dev,prod");
	}

	private static ConfigurableApplicationContext start(String db, String profiles) {
		List<String> args = new ArrayList<>(List.of(database.applicationArguments(db)));
		args.add("--spring.profiles.active=" + profiles);
		args.add("--spring.main.web-application-type=none");
		args.add("--spring.main.banner-mode=off");
		return new SpringApplicationBuilder(VibefixApplication.class).run(args.toArray(String[]::new));
	}

	private static List<String> environmentRows(String db) throws SQLException {
		return queryStrings(db, "SELECT name FROM platform.environment");
	}

	private static List<String> migrationHistory(String db) throws SQLException {
		return queryStrings(db,
				"SELECT installed_rank || ':' || coalesce(version, '') || ':' || checksum FROM platform.flyway_schema_history ORDER BY installed_rank");
	}

	private static boolean tableExists(String db, String table) throws SQLException {
		return queryStrings(db, "SELECT to_regclass('" + table + "')::text").getFirst() != null;
	}

	private static List<String> queryStrings(String db, String sql) throws SQLException {
		List<String> values = new ArrayList<>();
		try (Connection connection = database.superuserConnection(db);
				Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery(sql)) {
			while (rows.next()) {
				values.add(rows.getString(1));
			}
		}
		return values;
	}

}
