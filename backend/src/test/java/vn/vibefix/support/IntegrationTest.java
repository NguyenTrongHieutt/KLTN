package vn.vibefix.support;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base class for tests that boot the whole application (Conventions, Testing): the {@code dev}
 * profile against Testcontainers PostgreSQL 18.6, the application connected as the runtime role
 * and Flyway as the migration role, a {@link MutableClock}, and a database restored from its
 * template before every test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(IntegrationTest.TestClockConfiguration.class)
public abstract class IntegrationTest {

	protected static final PostgresTestDatabase database = PostgresTestDatabase.instance();

	@Autowired
	protected DataSource dataSource;

	@Autowired
	protected MutableClock clock;

	@DynamicPropertySource
	static void databaseProperties(DynamicPropertyRegistry registry) {
		database.registerProperties(registry);
	}

	@BeforeEach
	void resetDatabaseAndClock() {
		database.resetFromTemplate(this.dataSource);
		this.clock.reset();
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class TestClockConfiguration {

		@Bean
		@Primary
		MutableClock mutableClock() {
			return new MutableClock(MutableClock.DEFAULT_INSTANT);
		}

	}

}
