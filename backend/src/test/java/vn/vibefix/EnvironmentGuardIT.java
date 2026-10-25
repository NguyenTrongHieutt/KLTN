package vn.vibefix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import vn.vibefix.support.PostgresTestDatabase;

/**
 * AD-25: the application starts only when the {@code platform.environment} row equals the active
 * profile, and it checks before any migration runs (or, with Flyway off, before the context is
 * ready). SG-36: {@code prod} refuses the dev seed location and the dev GitHub organization before
 * migrating. AD-22: database passwords come from secret files, and a secret given as a JVM system
 * property refuses the start ({@code SecretSourceGuardTest} covers environment variables). Each
 * scenario gets its own database.
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
		// "sandbox" is not here: under it the worker starts instead of the application (SandboxRoundTripIT).
		return Stream.of("", "local", "dev,prod");
	}

	@Test
	void placeholderThatDiffersFromTheProfileIsRefusedBeforeMigrating() throws Exception {
		String db = database.createScratchDatabase();

		assertThatThrownBy(() -> start(db, "dev", "--spring.flyway.placeholders.environment=prod")).rootCause()
			.hasMessageContaining("placeholder 'environment' is 'prod'");
		assertThat(tableExists(db, "platform.flyway_schema_history")).isFalse();
		assertThat(tableExists(db, "platform.environment")).isFalse();
	}

	@Test
	void databaseWithHistoryButNoEnvironmentRowIsRefusedBeforeMigrating() throws Exception {
		String db = database.createScratchDatabase();
		try (Connection connection = database.migratorConnection(db);
				Statement statement = connection.createStatement()) {
			statement.execute("CREATE SCHEMA platform");
			statement.execute("CREATE TABLE platform.flyway_schema_history (installed_rank INT PRIMARY KEY)");
		}

		assertThatThrownBy(() -> start(db, "dev")).rootCause().hasMessageContaining("Flyway history");
		assertThat(tableExists(db, "platform.environment")).isFalse();
	}

	@Test
	void environmentIsCheckedEvenWhenFlywayIsDisabled() throws Exception {
		String db = database.createScratchDatabase();
		try (ConfigurableApplicationContext ignored = start(db, "prod")) {
			assertThat(environmentRows(db)).containsExactly("prod");
		}

		// Thrown unwrapped from afterSingletonsInstantiated, before the context is ready.
		assertThatThrownBy(() -> start(db, "dev", "--spring.flyway.enabled=false"))
			.hasMessageContaining("[prod]")
			.hasMessageContaining("'dev'");
		assertThatThrownBy(() -> start(database.createScratchDatabase(), "dev", "--spring.flyway.enabled=false"))
			.hasMessageContaining("platform.environment is missing");
	}

	@Test
	void passwordsAreReadFromSecretFiles(@TempDir Path secrets) throws Exception {
		Files.writeString(secrets.resolve("db-app-password"), database.appPassword());
		Files.writeString(secrets.resolve("db-migrator-password"), database.migratorPassword());
		String db = database.createScratchDatabase();
		List<String> args = new ArrayList<>(List.of(database.applicationArgumentsWithoutPasswords(db)));
		args.add("--VIBEFIX_SECRETS_DIR=" + secrets.toAbsolutePath().toString().replace('\\', '/') + "/");

		try (ConfigurableApplicationContext ignored = run(args, "dev")) {
			assertThat(environmentRows(db)).containsExactly("dev");
		}
	}

	@Test
	void prodRefusesTheDevSeedLocationBeforeMigrating() throws Exception {
		String db = database.createScratchDatabase();

		assertThatThrownBy(() -> start(db, "prod", "--spring.flyway.locations=classpath:db/migration,classpath:db/seed/dev"))
			.rootCause()
			.hasMessageContaining("dev seed location")
			.hasMessageContaining("db/seed/dev");
		assertThat(tableExists(db, "platform.flyway_schema_history")).isFalse();
		assertThat(tableExists(db, "platform.environment")).isFalse();
	}

	@Test
	void prodRefusesAParentOfTheDevSeedLocationBeforeMigrating() throws Exception {
		String db = database.createScratchDatabase();

		// Flyway scans classpath:db recursively, so it would find db/seed/dev too.
		assertThatThrownBy(() -> start(db, "prod", "--spring.flyway.locations=classpath:db")).rootCause()
			.hasMessageContaining("dev seed location classpath:db");
		assertThat(tableExists(db, "platform.flyway_schema_history")).isFalse();
	}

	@Test
	void prodRefusesTheDevGitHubOrganizationBeforeMigrating() throws Exception {
		String db = database.createScratchDatabase();

		assertThatThrownBy(() -> start(db, "prod", "--vibefix.github.organization=vibefix-dev")).rootCause()
			.hasMessageContaining("dev GitHub organization");
		assertThat(tableExists(db, "platform.flyway_schema_history")).isFalse();

		// Also with Flyway off, where the guard checks once all singletons exist.
		assertThatThrownBy(() -> start(db, "prod", "--spring.flyway.enabled=false",
				"--vibefix.github.organization=Vibefix-Dev"))
			.hasMessageContaining("dev GitHub organization");
	}

	@Test
	void secretGivenAsASystemPropertyIsRefusedBeforeMigrating() throws Exception {
		String db = database.createScratchDatabase();
		System.setProperty("db-app-password", database.appPassword());
		try {
			assertThatThrownBy(() -> start(db, "dev")).rootCause()
				.hasMessageContaining("db-app-password is set in a JVM system property")
				.hasMessageNotContaining(database.appPassword());
		}
		finally {
			System.clearProperty("db-app-password");
		}
		assertThat(tableExists(db, "platform.flyway_schema_history")).isFalse();
	}

	@Test
	void prodRefusesToStartWithoutTheGitHubOAuthApp() throws Exception {
		String db = database.createScratchDatabase();
		List<String> args = new ArrayList<>(List.of(database.applicationArguments(db)));
		args.addAll(GOOGLE);
		args.addAll(EMAIL);

		assertThatThrownBy(() -> run(args, "prod")).rootCause()
			.hasMessageContaining("github-oauth-client-id")
			.hasMessageContaining("refusing to start");
	}

	@Test
	void prodRefusesToStartWithoutTheGoogleSignInClient() throws Exception {
		String db = database.createScratchDatabase();
		List<String> args = new ArrayList<>(List.of(database.applicationArguments(db)));
		args.addAll(GITHUB);
		args.addAll(EMAIL);

		assertThatThrownBy(() -> run(args, "prod")).rootCause()
			.hasMessageContaining("google-oauth-client-id")
			.hasMessageContaining("refusing to start");
	}

	@Test
	void prodRefusesToStartWithoutAnSmtpServer() throws Exception {
		String db = database.createScratchDatabase();
		List<String> args = new ArrayList<>(List.of(database.applicationArguments(db)));
		args.addAll(OAUTH);

		assertThatThrownBy(() -> run(args, "prod")).rootCause()
			.hasMessageContaining("vibefix.email.smtp.host")
			.hasMessageContaining("refusing to start");
	}

	/** AD-25: production needs the GitHub OAuth App (linked in Story 19.2) ... */
	private static final List<String> GITHUB = List.of("--github-oauth-client-id=test-client",
			"--github-oauth-client-secret=test-secret");

	/** ... and the Google sign-in client (Story 19.1) ... */
	private static final List<String> GOOGLE = List.of("--google-oauth-client-id=test-google-client",
			"--google-oauth-client-secret=test-google-secret");

	private static final List<String> OAUTH = Stream.concat(GITHUB.stream(), GOOGLE.stream()).toList();

	/** ... and an SMTP server with the platform address its links use (AD-27). */
	private static final List<String> EMAIL = List.of("--vibefix.email.smtp.host=localhost",
			"--vibefix.email.link-base-url=https://vibefix.vn");

	private static ConfigurableApplicationContext start(String db, String profiles, String... extraArgs) {
		List<String> args = new ArrayList<>(List.of(database.applicationArguments(db)));
		// These scenarios are about the environment, so production gets what it needs besides.
		args.addAll(OAUTH);
		args.addAll(EMAIL);
		args.addAll(List.of(extraArgs));
		return run(args, profiles);
	}

	private static ConfigurableApplicationContext run(List<String> args, String profiles) {
		List<String> all = new ArrayList<>(args);
		all.add("--spring.profiles.active=" + profiles);
		all.add("--spring.main.web-application-type=none");
		all.add("--spring.main.banner-mode=off");
		return new SpringApplicationBuilder(VibefixApplication.class).run(all.toArray(String[]::new));
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
