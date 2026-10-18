package vn.vibefix.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import vn.vibefix.support.MutableClock;

/** AD-34: the per-minute sign-in window; AD-19: a sign-in returns only to a path inside the SPA. */
class SignInGuardsTest {

	@Test
	void theWindowAllowsTheLimitPerAddressAndMinuteThenStartsAgain() {
		SignInRateLimiter limiter = new SignInRateLimiter(new MutableClock(Instant.EPOCH), 3);

		assertThat(limiter.allow("10.0.0.1", 7)).isTrue();
		assertThat(limiter.allow("10.0.0.1", 7)).isTrue();
		assertThat(limiter.allow("10.0.0.1", 7)).isTrue();
		assertThat(limiter.allow("10.0.0.1", 7)).isFalse();
		assertThat(limiter.allow("10.0.0.2", 7)).as("another address").isTrue();
		assertThat(limiter.allow("10.0.0.1", 8)).as("the next minute").isTrue();
	}

	@Test
	void onlyAPlainRelativePathIsKeptAsTheReturnRoute() {
		assertThat(SignInStartFilter.safeReturnPath("/viec-can-lam")).isEqualTo("/viec-can-lam");
		assertThat(SignInStartFilter.safeReturnPath("/cong-viec/abc?tab=2")).isEqualTo("/cong-viec/abc?tab=2");
		for (String unsafe : new String[] { "//evil.example", "/\\evil.example", "https://evil.example", "evil",
				"/a b", "/" + "x".repeat(300), null }) {
			assertThat(SignInStartFilter.safeReturnPath(unsafe)).as(String.valueOf(unsafe)).isNull();
		}
	}

}
