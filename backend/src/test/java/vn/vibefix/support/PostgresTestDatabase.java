package vn.vibefix.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One PostgreSQL 18.6 container per test JVM, with the vibefix roles from
 * {@code db/init-roles.sql} and random passwords.
 * <p>
 * The application database {@value #DATABASE} is owned by the migration role, so Flyway runs as
 * {@value #MIGRATOR} and the application as {@value #APP}, exactly as in production. There is no
 * {@code @ServiceConnection}: it would connect the application as the superuser.
 * <p>
 * Tests never delete rows (AD-8). {@link #resetFromTemplate} snapshots the freshly migrated
 * database once, then recreates it from that snapshot with {@code DROP DATABASE ... WITH (FORCE)}
 * and {@code CREATE DATABASE ... TEMPLATE} while the application's Hikari pool is suspended.
 */
public final class PostgresTestDatabase {

	public static final String IMAGE = "postgres:18.6";

	public static final String DATABASE = "vibefix";

	public static final String MIGRATOR = "vibefix_migrator";

	public static final String APP = "vibefix_app";

	static final String TEMPLATE = "vibefix_template";

	/** The token key of test applications (AD-22): random per JVM, like the database passwords. */
	public static final String TEST_TOKEN_KEY = (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "");

	private static final AtomicInteger scratchDatabases = new AtomicInteger();

	private final PostgreSQLContainer container;

	private final String migratorPassword = randomPassword();

	private final String appPassword = randomPassword();

	private boolean templateReady;

	private PostgresTestDatabase() {
		this.container = new PostgreSQLContainer(DockerImageName.parse(IMAGE)).withDatabaseName("postgres")
			.withUsername("postgres")
			.withPassword(randomPassword())
			.withEnv("TZ", "UTC")
			.withCommand("postgres", "-c", "fsync=off", "-c", "full_page_writes=off")
			.withInitScript("db/init-roles.sql");
		this.container.start();
		executeAsSuperuser("postgres", "ALTER ROLE " + MIGRATOR + " PASSWORD '" + this.migratorPassword + "'",
				"ALTER ROLE " + APP + " PASSWORD '" + this.appPassword + "'",
				"CREATE DATABASE " + DATABASE + " OWNER " + MIGRATOR);
	}

	public static PostgresTestDatabase instance() {
		return Holder.INSTANCE;
	}

	/** Points the application at {@value #DATABASE}: runtime role for the app, migration role for Flyway. */
	public void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", () -> jdbcUrl(DATABASE));
		registry.add("spring.datasource.username", () -> APP);
		registry.add("spring.datasource.password", () -> this.appPassword);
		registry.add("spring.flyway.user", () -> MIGRATOR);
		registry.add("spring.flyway.password", () -> this.migratorPassword);
		registry.add("spring.datasource.hikari.allow-pool-suspension", () -> "true");
		// No background refill: connections are opened only on demand, so none is being opened to
		// the old database while it is dropped.
		registry.add("spring.datasource.hikari.minimum-idle", () -> "0");
	}

	/** Command-line arguments that point an application started by hand at {@code database}. */
	public String[] applicationArguments(String database) {
		return new String[] { "--spring.datasource.url=" + jdbcUrl(database), "--spring.datasource.username=" + APP,
				"--spring.datasource.password=" + this.appPassword, "--spring.flyway.user=" + MIGRATOR,
				"--spring.flyway.password=" + this.migratorPassword, "--identity-token-key-1=" + TEST_TOKEN_KEY };
	}

	/**
	 * Like {@link #applicationArguments} but without passwords, so the application must read them
	 * from its secret files ({@code db-app-password}, {@code db-migrator-password}).
	 */
	public String[] applicationArgumentsWithoutPasswords(String database) {
		return new String[] { "--spring.datasource.url=" + jdbcUrl(database), "--spring.datasource.username=" + APP,
				"--spring.flyway.user=" + MIGRATOR, "--identity-token-key-1=" + TEST_TOKEN_KEY };
	}

	public String appPassword() {
		return this.appPassword;
	}

	public String migratorPassword() {
		return this.migratorPassword;
	}

	public String jdbcUrl(String database) {
		return "jdbc:postgresql://" + this.container.getHost() + ":" + this.container.getMappedPort(5432) + "/"
				+ database;
	}

	/** A new empty database owned by the migration role, for tests that need their own. */
	public String createScratchDatabase() {
		String name = "scratch_" + scratchDatabases.incrementAndGet();
		executeAsSuperuser("postgres", "CREATE DATABASE " + name + " OWNER " + MIGRATOR);
		return name;
	}

	/** A superuser connection to {@code database}, for assertions the runtime role may not make. */
	public Connection superuserConnection(String database) throws SQLException {
		return DriverManager.getConnection(jdbcUrl(database), this.container.getUsername(),
				this.container.getPassword());
	}

	/** A connection to {@code database} as the migration role. */
	public Connection migratorConnection(String database) throws SQLException {
		return DriverManager.getConnection(jdbcUrl(database), MIGRATOR, this.migratorPassword);
	}

	/**
	 * Restores {@value #DATABASE} to its freshly migrated state. The first call (right after the
	 * application context started and migrated it) takes the snapshot; later calls recreate the
	 * database from it.
	 */
	public synchronized void resetFromTemplate(DataSource applicationDataSource) {
		HikariPoolMXBean pool = startedPool(applicationDataSource);
		pool.suspendPool();
		try {
			pool.softEvictConnections();
			if (!this.templateReady) {
				executeAsSuperuser("postgres", terminateConnections(DATABASE),
						"CREATE DATABASE " + TEMPLATE + " TEMPLATE " + DATABASE + " OWNER " + MIGRATOR);
				this.templateReady = true;
			}
			else {
				executeAsSuperuser("postgres", "DROP DATABASE " + DATABASE + " WITH (FORCE)",
						"CREATE DATABASE " + DATABASE + " TEMPLATE " + TEMPLATE + " OWNER " + MIGRATOR);
			}
			// Anything that joined the pool meanwhile belongs to a terminated session.
			pool.softEvictConnections();
		}
		finally {
			pool.resumePool();
		}
	}

	private static HikariPoolMXBean startedPool(DataSource dataSource) {
		try {
			HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
			try (Connection ignored = hikari.getConnection()) {
				// Starts the pool if nothing has used it yet.
			}
			return hikari.getHikariPoolMXBean();
		}
		catch (SQLException ex) {
			throw new IllegalStateException("The application DataSource must be a started Hikari pool", ex);
		}
	}

	private static String terminateConnections(String database) {
		return "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '" + database
				+ "' AND pid <> pg_backend_pid()";
	}

	private void executeAsSuperuser(String database, String... statements) {
		try (Connection connection = superuserConnection(database); Statement statement = connection.createStatement()) {
			for (String sql : statements) {
				statement.execute(sql);
			}
		}
		catch (SQLException ex) {
			throw new IllegalStateException("Test database setup failed", ex);
		}
	}

	private static String randomPassword() {
		return UUID.randomUUID().toString().replace("-", "");
	}

	private static final class Holder {

		private static final PostgresTestDatabase INSTANCE = new PostgresTestDatabase();

	}

}
