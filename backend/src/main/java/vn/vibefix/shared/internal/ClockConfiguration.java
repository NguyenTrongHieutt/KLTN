package vn.vibefix.shared.internal;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The only source of business time (Conventions, Time). Code injects this {@link Clock} and never
 * calls {@code Instant.now()} or {@code LocalDateTime.now()}; {@code ClockRuleTest} enforces it.
 * Tests replace the bean with a fixed clock.
 */
@Configuration(proxyBeanMethods = false)
class ClockConfiguration {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

}
