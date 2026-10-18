package vn.vibefix.shared.internal.sandbox;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceInitializationAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.health.DataSourceHealthContributorAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.modulith.NamedInterface;

import vn.vibefix.shared.SandboxJobHandler;

/**
 * The sandbox worker (AD-32): what the {@code vibefix-app} image runs under the profile
 * {@value #PROFILE}, in place of the application ({@code VibefixApplication} is
 * {@code @Profile("!sandbox")}).
 * <p>
 * It loads no database, Flyway, JPA or event publication registry, and none of the application's
 * beans: its component scan includes only {@link SandboxJobHandler} implementations, which the
 * worker serves at {@code POST} {@value SandboxJobController#PATH}. Two containers run it with no
 * secrets, a read-only root filesystem and resource limits (deploy/compose.yaml): {@code sandbox},
 * whose network reaches only {@code app} and, through the egress proxy, GitHub; and {@code checker},
 * which serves only the job types listed in {@value SandboxJobController#JOB_TYPES_PROPERTY} and
 * reaches only {@code app} and, through its own egress listener, public hosts. It refuses to start
 * when {@code dev} or {@code prod} is active too: that combination would silently run the worker
 * in place of the application.
 * <p>
 * The named interface {@value #NAMED_INTERFACE} exposes this one type of {@code shared.internal},
 * so that {@code VibefixApplication.main} can start it; no module references it.
 */
@Configuration(proxyBeanMethods = false)
@Profile(SandboxWorkerConfiguration.PROFILE)
@EnableAutoConfiguration(exclude = { DataSourceAutoConfiguration.class,
		DataSourceTransactionManagerAutoConfiguration.class, JdbcTemplateAutoConfiguration.class,
		JdbcClientAutoConfiguration.class, DataSourceInitializationAutoConfiguration.class,
		DataSourceHealthContributorAutoConfiguration.class, FlywayAutoConfiguration.class,
		HibernateJpaAutoConfiguration.class, DataJpaRepositoriesAutoConfiguration.class },
		// Spring Modulith's event publication registry (AD-9) lives in the database, and its module
		// runtime needs the @SpringBootApplication class, which the worker does not load. They are
		// runtime dependencies, so named rather than referenced.
		excludeName = { "org.springframework.modulith.events.jdbc.JdbcEventPublicationAutoConfiguration",
				"org.springframework.modulith.events.config.EventPublicationAutoConfiguration",
				"org.springframework.modulith.runtime.autoconfigure.SpringModulithRuntimeAutoConfiguration",
				// Sign-in, sessions and CSRF belong to the application (AD-19); the worker has no users, and
				// the internal network is its only way in (AD-32).
				"org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration",
				"org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration",
				"org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration",
				"org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration",
				"org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration",
				"org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration",
				"org.springframework.boot.security.oauth2.client.autoconfigure.servlet.OAuth2ClientWebSecurityAutoConfiguration",
				"org.springframework.boot.session.autoconfigure.SessionAutoConfiguration",
				"org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration" })
@ComponentScan(basePackages = "vn.vibefix", useDefaultFilters = false,
		includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = SandboxJobHandler.class))
@Import(SandboxJobController.class)
@NamedInterface(SandboxWorkerConfiguration.NAMED_INTERFACE)
public class SandboxWorkerConfiguration {

	public static final String PROFILE = "sandbox";

	static final String NAMED_INTERFACE = "sandbox-worker";

	SandboxWorkerConfiguration(Environment environment) {
		if (environment.acceptsProfiles(Profiles.of("dev | prod"))) {
			throw new IllegalStateException("The profile " + PROFILE + " starts the sandbox worker and cannot be"
					+ " combined with dev or prod; refusing to start");
		}
	}

}
