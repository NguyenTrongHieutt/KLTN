package vn.vibefix.timers.internal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code vibefix.timers.*} (AD-7).
 * @param pollInterval how often the poller claims due Deadlines, and how long a rearmed or failed one
 * waits: 30 s
 * @param batchSize the most Deadlines one poll runs
 */
@ConfigurationProperties("vibefix.timers")
record TimersProperties(Duration pollInterval, int batchSize) {

	TimersProperties {
		if (pollInterval == null || pollInterval.isNegative() || pollInterval.isZero()) {
			throw new IllegalArgumentException("The Deadline poll interval must be positive");
		}
		if (batchSize < 1) {
			throw new IllegalArgumentException("A poll runs at least one Deadline");
		}
	}

}
