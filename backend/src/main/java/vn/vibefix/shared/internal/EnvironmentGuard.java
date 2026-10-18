package vn.vibefix.shared.internal;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.flywaydb.core.Flyway;
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
 * <li>Before migrating: unless the database is fresh (no {@code platform.environment} table yet),
 * its single row must equal the profile.</li>
 * <li>After migrating (the first migration stamps the row from the placeholder): the row must exist
 * and equal the profile.</li>
 * </ol>
 * The checks read through Flyway's own connection, which uses the migration role.
 */
@Component
class EnvironmentGuard implements FlywayMigrationStrategy {

	static final Set<String> ENVIRONMENTS = Set.of("dev", "prod");

	static final String PLACEHOLDER = "environment";

	private final Environment springEnvironment;

	EnvironmentGuard(Environment springEnvironment) {
		this.springEnvironment = springEnvironment;
	}

	@Override
	public void migrate(Flyway flyway) {
		String expected = activeEnvironment();
		String placeholder = flyway.getConfiguration().getPlaceholders().get(PLACEHOLDER);
		if (!expected.equals(placeholder)) {
			throw new IllegalStateException("Flyway placeholder '" + PLACEHOLDER + "' is '" + placeholder
					+ "' but the active environment profile is '" + expected + "'");
		}
		JdbcTemplate jdbc = new JdbcTemplate(flyway.getConfiguration().getDataSource());
		check(jdbc, expected, false);
		flyway.migrate();
		check(jdbc, expected, true);
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

	private static void check(JdbcTemplate jdbc, String expected, boolean afterMigration) {
		Boolean stamped = jdbc.queryForObject("SELECT to_regclass('platform.environment') IS NOT NULL", Boolean.class);
		if (!Boolean.TRUE.equals(stamped)) {
			if (afterMigration) {
				throw new IllegalStateException("platform.environment is missing after migration");
			}
			return; // A fresh database: the first migration stamps it.
		}
		List<String> rows = jdbc.queryForList("SELECT name FROM platform.environment", String.class);
		if (!rows.equals(List.of(expected))) {
			throw new IllegalStateException("The database belongs to environment " + rows
					+ " but the active environment profile is '" + expected + "'; refusing to start");
		}
	}

}
