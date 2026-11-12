package vn.vibefix.github.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import vn.vibefix.shared.RateLimitedException;

/**
 * Story 23.4 (AD-34): the Actions exchange's limits. Calls a minute are capped per client address and in all,
 * and a call refused for its address uses up nothing of the overall cap, so one address cannot starve every
 * run; a refusal carries {@code Retry-After} until the next minute.
 */
class ExchangeRateLimiterTest {

	/** 20 seconds into a minute: 40 seconds until the next. */
	private final ExchangeRateLimiter limiter = new ExchangeRateLimiter(
			Clock.fixed(Instant.parse("2026-11-12T03:00:20Z"), ZoneOffset.UTC));

	@Test
	void theSixtyFirstCallAMinuteAcrossAddressesIsRefused() {
		for (int call = 0; call < ExchangeRateLimiter.IN_ALL; call++) {
			this.limiter.call("198.51.100." + call / ExchangeRateLimiter.PER_ADDRESS);
		}

		assertThatThrownBy(() -> this.limiter.call("203.0.113.1")).isInstanceOfSatisfying(RateLimitedException.class,
				limited -> assertThat(limited.retryAfterSeconds()).isEqualTo(40));
	}

	@Test
	void callsRefusedForTheirAddressDoNotCountTowardTheOverallCap() {
		for (int call = 0; call < ExchangeRateLimiter.PER_ADDRESS; call++) {
			this.limiter.call("198.51.100.1");
		}
		for (int refused = 0; refused < 100; refused++) {
			assertThatThrownBy(() -> this.limiter.call("198.51.100.1")).isInstanceOf(RateLimitedException.class);
		}

		// The flooding address used 10 of the 60: 50 more calls from others still pass.
		for (int call = 0; call < ExchangeRateLimiter.IN_ALL - ExchangeRateLimiter.PER_ADDRESS; call++) {
			this.limiter.call("203.0.113." + call / ExchangeRateLimiter.PER_ADDRESS);
		}
		assertThatThrownBy(() -> this.limiter.call("192.0.2.1")).isInstanceOf(RateLimitedException.class);
	}

	@Test
	void aCorrelationTakesFiveAttemptsOverItsLife() {
		for (int attempt = 0; attempt < ExchangeRateLimiter.PER_CORRELATION; attempt++) {
			this.limiter.attempt("0f8c5a2e-6b1d-4c3e-9a7f-1d2e3f4a5b6c");
		}

		assertThatThrownBy(() -> this.limiter.attempt("0f8c5a2e-6b1d-4c3e-9a7f-1d2e3f4a5b6c"))
			.isInstanceOfSatisfying(RateLimitedException.class, limited -> assertThat(limited.retryAfterSeconds())
				.isEqualTo(GitHubActionsRuns.CORRELATION_LIFETIME.toSeconds()));
		this.limiter.attempt("1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d");
	}

}
