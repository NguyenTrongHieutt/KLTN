package vn.vibefix.shared.internal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code vibefix.events.*}: how the event publication registry is kept (AD-9).
 * @param resubmitAfter how long an incomplete publication waits after its last attempt before it is
 * resubmitted
 * @param maxAttempts attempts, the first delivery included, before a publication is stalled
 * @param purgeAfter how long a completed publication is kept
 * @param processedRetention how long a listener's replay marker is kept; longer than
 * {@code purgeAfter}, so a marker outlives every possible replay of its publication
 */
@ConfigurationProperties("vibefix.events")
record EventPublicationProperties(Duration resubmitAfter, int maxAttempts, Duration purgeAfter,
		Duration processedRetention) {

	EventPublicationProperties {
		if (maxAttempts < 1) {
			throw new IllegalArgumentException("vibefix.events.max-attempts must be at least 1");
		}
		if (processedRetention.compareTo(purgeAfter) <= 0) {
			throw new IllegalArgumentException("vibefix.events.processed-retention must exceed purge-after");
		}
	}

}
