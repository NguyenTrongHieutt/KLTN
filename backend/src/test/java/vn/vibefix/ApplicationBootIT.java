package vn.vibefix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.CompletedEventPublications;
import org.springframework.test.web.servlet.MockMvc;

import vn.vibefix.shared.SandboxJobHandler;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.MutableClock;
import vn.vibefix.support.PostgresTestDatabase;

/**
 * Boots the whole application against PostgreSQL 18.6 the way production runs it: Flyway as the
 * migration role, the application as the runtime role (Conventions, Migrations; AD-25). Also the
 * API's security headers and correlation ID (AD-21, Conventions, Logging), Actuator (AD-22), the
 * dev seed (SG-36), and the sandbox worker's absence from the application (AD-32).
 */
class ApplicationBootIT extends IntegrationTest {

	static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

	@Autowired
	private ApplicationContext context;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private MockMvc mvc;

	@Autowired
	private Clock injectedClock;

	@Autowired
	private CompletedEventPublications completedEventPublications;

	@Test
	void applicationConnectsAsTheRuntimeRole() {
		String currentUser = this.jdbc.sql("SELECT current_user").query(String.class).single();

		assertThat(currentUser).isEqualTo(PostgresTestDatabase.APP);
	}

	@Test
	void flywayRanEveryMigrationAsTheMigrationRole() throws Exception {
		List<String> installedBy = new ArrayList<>();
		try (Connection connection = database.superuserConnection(PostgresTestDatabase.DATABASE);
				Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery(
						"SELECT installed_by, success FROM platform.flyway_schema_history ORDER BY installed_rank")) {
			while (rows.next()) {
				assertThat(rows.getBoolean("success")).isTrue();
				installedBy.add(rows.getString("installed_by"));
			}
		}

		assertThat(installedBy).isNotEmpty().containsOnly(PostgresTestDatabase.MIGRATOR);
	}

	@Test
	void runtimeRoleHasDmlOnly() {
		assertThatThrownBy(() -> this.jdbc.sql("CREATE TABLE platform.probe (id INT)").update()).rootCause()
			.hasMessageContaining("permission denied for schema platform");
		assertThatThrownBy(() -> this.jdbc.sql("UPDATE platform.environment SET name = 'prod'").update()).rootCause()
			.hasMessageContaining("permission denied for table environment");
		assertThatThrownBy(() -> this.jdbc.sql("DELETE FROM platform.flyway_schema_history").update()).rootCause()
			.hasMessageContaining("permission denied for table flyway_schema_history");
	}

	@Test
	void databaseIsStampedWithTheActiveEnvironment() throws Exception {
		this.mvc.perform(get("/api/v1/environment"))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.name").value("dev"));
	}

	@Test
	void unmappedRouteIsANotFoundProblem() throws Exception {
		this.mvc.perform(get("/api/v1/nope"))
			.andExpect(status().isNotFound())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.detail").doesNotExist());
	}

	@Test
	void resetRestoresTheMigratedDatabaseWithoutDeletingRows() {
		this.jdbc.sql("""
				INSERT INTO platform.event_publication (id, listener_id, event_type, serialized_event, publication_date)
				VALUES (:id, 'probe', 'probe', '{}', :at)
				""").param("id", UUID.randomUUID()).param("at", Timestamp.from(this.clock.instant())).update();
		assertThat(countPublications()).isOne();

		database.resetFromTemplate(this.dataSource);

		assertThat(countPublications()).isZero();
		assertThat(this.jdbc.sql("SELECT name FROM platform.environment").query(String.class).single())
			.isEqualTo("dev");
	}

	@Test
	void eventPublicationRegistryUsesThePlatformSchema() {
		assertThat(this.completedEventPublications.findAll()).isEmpty();
	}

	@Test
	void businessTimeComesFromTheInjectedClock() {
		assertThat(this.injectedClock).isSameAs(this.clock);
		assertThat(this.injectedClock.instant()).isEqualTo(MutableClock.DEFAULT_INSTANT);
	}

	@Test
	void apiResponsesCarryTheSecurityHeadersAndACorrelationId() throws Exception {
		this.mvc.perform(get("/api/v1/environment"))
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Security-Policy",
					"default-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'"))
			.andExpect(header().string("Strict-Transport-Security", "max-age=31536000; includeSubDomains"))
			.andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
			.andExpect(header().string("Permissions-Policy",
					"camera=(), geolocation=(), microphone=(), payment=(), usb=()"))
			.andExpect(header().string("X-Content-Type-Options", "nosniff"))
			.andExpect(header().string("Cross-Origin-Opener-Policy", "same-origin"))
			.andExpect(header().string("Cross-Origin-Embedder-Policy", "require-corp"))
			.andExpect(header().string("Cross-Origin-Resource-Policy", "same-origin"))
			.andExpect(header().string("X-Correlation-Id", matchesPattern(UUID_PATTERN)));
	}

	@Test
	void everyRequestGetsItsOwnCorrelationIdAndAClientIdIsIgnored() throws Exception {
		String forged = "00000000-0000-0000-0000-000000000000";
		String first = this.mvc.perform(get("/api/v1/environment").header("X-Correlation-Id", forged))
			.andReturn()
			.getResponse()
			.getHeader("X-Correlation-Id");
		String second = this.mvc.perform(get("/api/v1/nope")).andReturn().getResponse().getHeader("X-Correlation-Id");

		assertThat(first).matches(UUID_PATTERN).isNotEqualTo(forged).isNotEqualTo(second);
		assertThat(second).matches(UUID_PATTERN);
	}

	@Test
	void actuatorExposesOnlyHealthWithoutDetails() throws Exception {
		this.mvc.perform(get("/actuator/health"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("UP"))
			.andExpect(jsonPath("$.components.db.status").value("UP"))
			.andExpect(jsonPath("$.components.db.details").doesNotExist())
			.andExpect(jsonPath("$.components.sandbox").doesNotExist());
		this.mvc.perform(get("/actuator/health/liveness")).andExpect(jsonPath("$.status").value("UP"));
		this.mvc.perform(get("/actuator/health/readiness")).andExpect(jsonPath("$.status").value("UP"));
		for (String path : List.of("/actuator", "/actuator/env", "/actuator/beans", "/actuator/configprops",
				"/actuator/heapdump", "/actuator/metrics", "/actuator/loggers", "/actuator/info")) {
			this.mvc.perform(get(path)).andExpect(status().isNotFound());
		}
	}

	@Test
	void devSeedRanFromItsOwnLocationAsTheMigrationRole() throws Exception {
		List<String> repeatables = new ArrayList<>();
		try (Connection connection = database.superuserConnection(PostgresTestDatabase.DATABASE);
				Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery("SELECT script, installed_by FROM platform.flyway_schema_history"
						+ " WHERE version IS NULL AND type = 'SQL' AND success ORDER BY installed_rank")) {
			while (rows.next()) {
				repeatables.add(rows.getString("script") + " by " + rows.getString("installed_by"));
			}
		}

		assertThat(repeatables).containsExactly("R__dev_seed.sql by " + PostgresTestDatabase.MIGRATOR);
	}

	@Test
	void sandboxWorkerIsNotPartOfTheApplication() throws Exception {
		assertThat(this.context.getBeansOfType(SandboxJobHandler.class)).isEmpty();
		this.mvc
			.perform(post("/internal/sandbox/jobs").contentType(MediaType.APPLICATION_JSON)
				.content("{\"type\":\"HEALTH\",\"input\":{\"nonce\":\"x\"}}"))
			.andExpect(status().isNotFound());
	}

	private long countPublications() {
		return this.jdbc.sql("SELECT count(*) FROM platform.event_publication").query(Long.class).single();
	}

}
