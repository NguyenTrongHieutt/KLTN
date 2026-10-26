package vn.vibefix.shared.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.CommandLinePropertySource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.stereotype.Component;

/**
 * AD-22: platform credentials are mounted secret files, never environment variables (nor JVM system
 * properties or command-line arguments, which are as visible in the process listing). This guard
 * refuses to start when a known secret property is set in any of those places, in any spelling
 * Spring would bind (for example {@code SPRING_DATASOURCE_PASSWORD} or {@code DB_APP_PASSWORD}), or
 * inside {@code SPRING_APPLICATION_JSON}, which Boot reads from an environment variable or a system
 * property into its own property source.
 * <p>
 * {@link EnvironmentGuard} depends on it, so the check has run before Flyway connects. The error
 * names the property and its source, never the value.
 */
@Component
class SecretSourceGuard implements InitializingBean {

	/**
	 * Every secret property of the platform; later stories add theirs (GitHub App keys, VNPay). The dev
	 * LinkedIn app's secret is read by Spike S5's probe only, until Story 19.6.
	 */
	static final Set<String> SECRET_PROPERTIES = Set.of("spring.datasource.password",
			"spring.datasource.hikari.password", "spring.flyway.password", "db-app-password", "db-migrator-password",
			"github-oauth-client-secret", "google-oauth-client-secret", "linkedin-client-secret", "smtp-password",
			"scanning-fingerprint-key");

	/**
	 * Secrets that come in numbered versions, matched against the property names of the process
	 * sources in any spelling Spring would bind ({@code identity-token-key-2}, {@code IDENTITY_TOKEN_KEY_2}):
	 * the token keys and the person-link signal keys (AD-19).
	 */
	static final List<Pattern> SECRET_PATTERNS = List.of(
			Pattern.compile("identity[-_.]token[-_.]key[-_.]\\d+", Pattern.CASE_INSENSITIVE),
			Pattern.compile("identity[-_.]person[-_.]signal[-_.]key[-_.]\\d+", Pattern.CASE_INSENSITIVE));

	/** The property source Boot builds from {@code SPRING_APPLICATION_JSON} / {@code spring.application.json}. */
	static final String APPLICATION_JSON_SOURCE = "spring.application.json";

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

	/** Each secret property set in an environment variable, a system property or a command-line argument. */
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
			if (source instanceof EnumerablePropertySource<?> enumerable) {
				Arrays.stream(enumerable.getPropertyNames())
					.filter(name -> SECRET_PATTERNS.stream().anyMatch(pattern -> pattern.matcher(name).matches()))
					.sorted()
					.forEach(name -> violations.add(name + " is set in " + kind));
			}
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
		if (APPLICATION_JSON_SOURCE.equals(source.getName())) {
			return "the SPRING_APPLICATION_JSON environment variable or system property";
		}
		if (source instanceof CommandLinePropertySource<?>) {
			return "a command-line argument";
		}
		return null;
	}

}
