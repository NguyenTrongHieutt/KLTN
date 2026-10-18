package vn.vibefix.shared.internal;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.Location;
import org.flywaydb.core.api.configuration.Configuration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Refuses to start unless the {@code platform.environment} row equals the active environment
 * profile (AD-25). It wraps Flyway: check, migrate, check again, so an application started with
 * the wrong profile never applies its migrations to another environment's database.
 * <ol>
 * <li>Exactly one of the profiles {@code dev} and {@code prod} must be active, and the Flyway
 * placeholder {@code environment} must name it.</li>
 * <li>Before migrating: unless the database is fresh (neither {@code platform.environment} nor a
 * Flyway history yet), its single row must equal the profile.</li>
 * <li>After migrating (the first migration stamps the row from the placeholder): the row must exist
 * and equal the profile.</li>
 * </ol>
 * These checks read through Flyway's own connection, which uses the migration role. When Flyway
 * does not run ({@code spring.flyway.enabled=false}), the row is checked through the application
 * {@link DataSource} once all singletons exist, before the web server starts.
 * <p>
 * SG-36: under {@code prod}, the guard also refuses, before migrating, a Flyway location under
 * {@code db/seed} (dev seed data) and the dev GitHub organization {@value #DEV_ORGANIZATION}. It
 * depends on {@link SecretSourceGuard}, so secrets have been checked (AD-22) before Flyway connects.
 */
@Component
class EnvironmentGuard implements FlywayMigrationStrategy, SmartInitializingSingleton {

	static final Set<String> ENVIRONMENTS = Set.of("dev", "prod");

	static final String PLACEHOLDER = "environment";

	static final String PROD = "prod";

	static final String ORGANIZATION_PROPERTY = "vibefix.github.organization";

	static final String DEV_ORGANIZATION = "vibefix-dev";

	static final String SEED_LOCATION = "db/seed";

	private final Environment springEnvironment;

	private final ObjectProvider<DataSource> dataSources;

	private volatile boolean checkedByMigration;

	/**
	 * @param secretSources not used after construction: injecting it makes the secret-source check run
	 * before this strategy (and so Flyway) exists
	 */
	EnvironmentGuard(Environment springEnvironment, ObjectProvider<DataSource> dataSources,
			SecretSourceGuard secretSources) {
		this.springEnvironment = springEnvironment;
		this.dataSources = dataSources;
	}

	@Override
	public void migrate(Flyway flyway) {
		String expected = activeEnvironment();
		Configuration configuration = flyway.getConfiguration();
		refuseDevSettingsInProd(expected, configuration.getLocations());
		String placeholder = configuration.getPlaceholders().get(PLACEHOLDER);
		if (!expected.equals(placeholder)) {
			throw new IllegalStateException("Flyway placeholder '" + PLACEHOLDER + "' is '" + placeholder
					+ "' but the active environment profile is '" + expected + "'");
		}
		JdbcTemplate jdbc = new JdbcTemplate(configuration.getDataSource());
		if (!isStamped(jdbc) && exists(jdbc, configuration.getDefaultSchema() + "." + configuration.getTable())) {
			throw new IllegalStateException("The database has a Flyway history but no platform.environment row;"
					+ " refusing to migrate a database of unknown environment");
		}
		check(jdbc, expected, false);
		flyway.migrate();
		check(jdbc, expected, true);
		this.checkedByMigration = true;
	}

	@Override
	public void afterSingletonsInstantiated() {
		if (this.checkedByMigration) {
			return;
		}
		String expected = activeEnvironment();
		refuseDevSettingsInProd(expected, new Location[0]);
		DataSource dataSource = this.dataSources.getIfAvailable();
		if (dataSource == null) {
			return; // No database in this process, so there is nothing to mix up.
		}
		check(new JdbcTemplate(dataSource), expected, true);
	}

	/** SG-36: production never loads dev seed data or talks to the dev GitHub organization. */
	private void refuseDevSettingsInProd(String environment, Location[] locations) {
		if (!PROD.equals(environment)) {
			return;
		}
		String organization = this.springEnvironment.getProperty(ORGANIZATION_PROPERTY, "");
		if (organization.strip().toLowerCase(Locale.ROOT).equals(DEV_ORGANIZATION)) {
			throw new IllegalStateException("The prod profile is configured with the dev GitHub organization ("
					+ ORGANIZATION_PROPERTY + "=" + DEV_ORGANIZATION + "); refusing to start");
		}
		for (Location location : locations) {
			String descriptor = String.valueOf(location.getDescriptor()).replace('\\', '/');
			if (descriptor.contains(SEED_LOCATION)) {
				throw new IllegalStateException("The prod profile is configured with the dev seed location "
						+ descriptor + "; refusing to start before any migration runs");
			}
		}
	}

	String activeEnvironment() {
		String[] active = this.springEnvironment.getActiveProfiles();
		List<String> environments = Arrays.stream(active).filter(ENVIRONMENTS::contains).distinct().toList();
		if (environments.size() != 1) {
			throw new IllegalStateException("Exactly one environment profile of " + ENVIRONMENTS
					+ " must be active, but the active profiles are " + Arrays.toString(active));
		}
		return environments.getFirst();
	}

	/**
	 * @param requireStamp whether a missing {@code platform.environment} table refuses the start; it
	 * is allowed only before the first migration of a fresh database
	 */
	private static void check(JdbcTemplate jdbc, String expected, boolean requireStamp) {
		if (!isStamped(jdbc)) {
			if (requireStamp) {
				throw new IllegalStateException("platform.environment is missing; refusing to start");
			}
			return; // A fresh database: the first migration stamps it.
		}
		List<String> rows = jdbc.queryForList("SELECT name FROM platform.environment", String.class);
		if (!rows.equals(List.of(expected))) {
			throw new IllegalStateException("The database belongs to environment " + rows
					+ " but the active environment profile is '" + expected + "'; refusing to start");
		}
	}

	private static boolean isStamped(JdbcTemplate jdbc) {
		return exists(jdbc, "platform.environment");
	}

	private static boolean exists(JdbcTemplate jdbc, String qualifiedTable) {
		return Boolean.TRUE.equals(jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class,
				qualifiedTable));
	}

}
