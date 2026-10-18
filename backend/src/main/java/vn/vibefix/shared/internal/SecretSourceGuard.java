package vn.vibefix.shared.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.stereotype.Component;

/**
 * AD-22: platform credentials are mounted secret files, never environment variables (nor JVM system
 * properties, which are as visible in the process listing). This guard refuses to start when a
 * known secret property is set in either place, in any spelling Spring would bind (for example
 * {@code SPRING_DATASOURCE_PASSWORD} or {@code DB_APP_PASSWORD}).
 * <p>
 * {@link EnvironmentGuard} depends on it, so the check has run before Flyway connects. The error
 * names the property and its source, never the value.
 */
@Component
class SecretSourceGuard implements InitializingBean {

	/** Every secret property of the platform; later stories add theirs (GitHub App keys, SMTP, VNPay). */
	static final Set<String> SECRET_PROPERTIES = Set.of("spring.datasource.password", "spring.flyway.password",
			"db-app-password", "db-migrator-password");

	private final ConfigurableEnvironment environment;

	SecretSourceGuard(ConfigurableEnvironment environment) {
		this.environment = environment;
	}

	@Override
	public void afterPropertiesSet() {
		List<String> violations = violations(this.environment);
		if (!violations.isEmpty()) {
			throw new IllegalStateException("Secrets must come from mounted secret files only (AD-22), but "
					+ String.join(", ", violations) + "; refusing to start");
		}
	}

	/** Each secret property set in an environment variable or a system property, with that source. */
	static List<String> violations(ConfigurableEnvironment environment) {
		List<String> violations = new ArrayList<>();
		for (PropertySource<?> source : environment.getPropertySources()) {
			String kind = processSource(source);
			if (kind == null) {
				continue;
			}
			// containsProperty checks every relaxed spelling for an environment variable source.
			SECRET_PROPERTIES.stream()
				.sorted()
				.filter(source::containsProperty)
				.forEach(name -> violations.add(name + " is set in " + kind));
		}
		return violations;
	}

	private static @Nullable String processSource(PropertySource<?> source) {
		if (source instanceof SystemEnvironmentPropertySource) {
			return "an environment variable";
		}
		if (StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME.equals(source.getName())) {
			return "a JVM system property";
		}
		return null;
	}

}
