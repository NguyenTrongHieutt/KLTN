package vn.vibefix.shared;

import java.time.Duration;
import java.util.Objects;

/**
 * A request refused by an abuse limit (AD-34): {@code 429 RATE_LIMITED} with {@code Retry-After},
 * in whole seconds rounded up and at least 1.
 */
public class RateLimitedException extends ApiException {

	private final Duration retryAfter;

	public RateLimitedException(Duration retryAfter) {
		super(ErrorCode.RATE_LIMITED);
		this.retryAfter = Objects.requireNonNull(retryAfter, "retryAfter");
	}

	public Duration retryAfter() {
		return this.retryAfter;
	}

	/** The {@code Retry-After} value: whole seconds, rounded up, at least 1. */
	public long retryAfterSeconds() {
		long seconds = this.retryAfter.toSeconds() + (this.retryAfter.toNanosPart() > 0 ? 1 : 0);
		return Math.max(1, seconds);
	}

}
