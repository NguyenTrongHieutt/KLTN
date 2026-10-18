package vn.vibefix.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the application {@link java.time.Clock} with a {@link MutableClock} in tests that boot
 * the application. A top-level class, so Spring never mistakes it for a test's default
 * configuration.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestClockConfiguration {

	@Bean
	@Primary
	MutableClock mutableClock() {
		return new MutableClock(MutableClock.DEFAULT_INSTANT);
	}

}
