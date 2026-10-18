package vn.vibefix.shared.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * AD-22: a secret property set in an environment variable (any spelling Spring binds) or a JVM
 * system property refuses the start; the same property from a secret file, the command line or a
 * test's dynamic properties is fine. The refusal never shows the value.
 */
class SecretSourceGuardTest {

	private static final String VALUE = "do-not-print-me";

	@ParameterizedTest
	@ValueSource(strings = { "SPRING_DATASOURCE_PASSWORD", "SPRING_FLYWAY_PASSWORD", "DB_APP_PASSWORD",
			"DB_MIGRATOR_PASSWORD", "db_app_password", "spring.datasource.password" })
	void secretInAnEnvironmentVariableIsRefused(String variable) {
		StandardEnvironment environment = environmentWith(Map.of(variable, VALUE), Map.of());

		assertThatThrownBy(() -> new SecretSourceGuard(environment).afterPropertiesSet())
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("environment variable")
			.hasMessageNotContaining(VALUE);
	}

	@ParameterizedTest
	@ValueSource(strings = { "db-app-password", "db-migrator-password", "spring.datasource.password",
			"spring.flyway.password" })
	void secretInASystemPropertyIsRefused(String property) {
		StandardEnvironment environment = environmentWith(Map.of(), Map.of(property, ""));

		assertThatThrownBy(() -> new SecretSourceGuard(environment).afterPropertiesSet())
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining(property + " is set in a JVM system property");
	}

	@Test
	void secretFromAnotherSourceIsAccepted() {
		StandardEnvironment environment = environmentWith(Map.of("VIBEFIX_SECRETS_DIR", "/run/secrets/"), Map.of());
		environment.getPropertySources()
			.addFirst(new MapPropertySource("Config resource 'configtree' [/run/secrets/]",
					Map.of("db-app-password", VALUE, "db-migrator-password", VALUE)));
		environment.getPropertySources()
			.addFirst(new MapPropertySource("commandLineArgs", Map.of("spring.datasource.password", VALUE)));

		assertThatNoException().isThrownBy(() -> new SecretSourceGuard(environment).afterPropertiesSet());
	}

	@Test
	void everyOffendingPropertyIsNamed() {
		StandardEnvironment environment = environmentWith(
				Map.of("SPRING_DATASOURCE_PASSWORD", VALUE, "DB_MIGRATOR_PASSWORD", VALUE),
				Map.of("spring.flyway.password", VALUE));

		assertThat(SecretSourceGuard.violations(environment)).containsExactlyInAnyOrder(
				"spring.datasource.password is set in an environment variable",
				"db-migrator-password is set in an environment variable",
				"spring.flyway.password is set in a JVM system property");
	}

	/** An environment whose real process sources are replaced by the given maps. */
	private static StandardEnvironment environmentWith(Map<String, Object> variables, Map<String, Object> properties) {
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources()
			.replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, new SystemEnvironmentPropertySource(
					StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables));
		environment.getPropertySources()
			.replace(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
					new MapPropertySource(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, properties));
		return environment;
	}

}
