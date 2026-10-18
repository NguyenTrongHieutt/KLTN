package vn.vibefix.operations;

import java.time.Duration;
import java.util.Objects;

/**
 * How often a type is tried (AD-6, seed operation policy): {@code maxAttempts} attempts, waiting
 * {@code firstRetryDelay} after the first failure and twice as long after each further one.
 */
public record OperationPolicy(int maxAttempts, Duration firstRetryDelay) {

	/** The seed default: 30 s doubling, 8 attempts. */
	public static final OperationPolicy DEFAULT = new OperationPolicy(8, Duration.ofSeconds(30));

	public OperationPolicy {
		if (maxAttempts < 1) {
			throw new IllegalArgumentException("An operation has at least one attempt");
		}
		Objects.requireNonNull(firstRetryDelay, "firstRetryDelay");
		if (firstRetryDelay.isNegative() || firstRetryDelay.isZero()) {
			throw new IllegalArgumentException("The retry delay must be positive");
		}
	}

	/** The wait after failed attempt {@code attempt} (1-based): 30 s, 60 s, 120 s, … for the default. */
	public Duration delayAfter(int attempt) {
		return this.firstRetryDelay.multipliedBy(1L << Math.min(Math.max(attempt - 1, 0), 20));
	}

}
