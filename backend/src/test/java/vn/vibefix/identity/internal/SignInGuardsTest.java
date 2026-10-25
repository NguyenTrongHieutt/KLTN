package vn.vibefix.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import vn.vibefix.support.MutableClock;

/**
 * AD-34: the per-minute sign-in window; AD-19: a sign-in returns only to a path inside the SPA, only
 * the Google start signs in (Story 19.1), and an email Google has not verified is refused in words of
 * its own (FR-1).
 */
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
	void theRateLimitCoversTheGoogleStartAndItsCallback() {
		SignInRateLimiter limiter = new SignInRateLimiter(new MutableClock(Instant.EPOCH), 3);

		assertThat(limiter.shouldNotFilter(new MockHttpServletRequest("GET", "/oauth2/authorization/google"))).isFalse();
		assertThat(limiter.shouldNotFilter(new MockHttpServletRequest("GET", "/login/oauth2/code/google"))).isFalse();
		assertThat(limiter.shouldNotFilter(new MockHttpServletRequest("GET", "/api/v1/session"))).isTrue();
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

	@Test
	void onlyTheGoogleStartGoesOnAndItSaysWhenGoogleIsNotSetUp() throws Exception {
		assertThat(start(true, "/oauth2/authorization/google").getRedirectedUrl()).as("goes on to Spring").isNull();
		assertThat(start(false, "/oauth2/authorization/google").getRedirectedUrl())
			.isEqualTo("/dang-nhap?loi=chua-thiet-lap");
		assertThat(start(true, "/oauth2/authorization/github").getRedirectedUrl()).isEqualTo("/dang-nhap?loi=that-bai");
		assertThat(start(false, "/oauth2/authorization/github").getRedirectedUrl()).isEqualTo("/dang-nhap?loi=that-bai");
	}

	@Test
	void anUnverifiedEmailHasItsOwnWordsOnSignInAndAnyOtherFailureTheGeneralOnes() {
		assertThat(SecurityConfiguration.failurePage(new OAuth2AuthenticationException(new OAuth2Error("email_not_verified"))))
			.isEqualTo("email-chua-xac-minh");
		assertThat(SecurityConfiguration.failurePage(new OAuth2AuthenticationException(new OAuth2Error("invalid_nonce"))))
			.isEqualTo("that-bai");
		assertThat(SecurityConfiguration.failurePage(new BadCredentialsException("x"))).isEqualTo("that-bai");
	}

	@Test
	void theUserServiceRefusesAFalseOrMissingEmailVerifiedClaim() {
		assertThatThrownBy(() -> new GoogleUserService(request -> user(false, "lan@gmail.test")).loadUser(request()))
			.isInstanceOf(OAuth2AuthenticationException.class)
			.satisfies(ex -> assertThat(((OAuth2AuthenticationException) ex).getError().getErrorCode())
				.isEqualTo("email_not_verified"));
		assertThatThrownBy(() -> new GoogleUserService(request -> user(null, "lan@gmail.test")).loadUser(request()))
			.isInstanceOf(OAuth2AuthenticationException.class);
		assertThatThrownBy(() -> new GoogleUserService(request -> user(true, null)).loadUser(request()))
			.as("no email at all")
			.isInstanceOf(OAuth2AuthenticationException.class);
		assertThat(new GoogleUserService(request -> user(true, "lan@gmail.test")).loadUser(request()).getEmail())
			.isEqualTo("lan@gmail.test");
	}

	private static MockHttpServletResponse start(boolean configured, String path) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
		request.setRequestURI(path);
		MockHttpServletResponse response = new MockHttpServletResponse();
		new SignInStartFilter(configured).doFilter(request, response, new MockFilterChain());
		return response;
	}

	private static OidcUser user(Boolean verified, String email) {
		OidcIdToken.Builder token = OidcIdToken.withTokenValue("id-token").subject("google-lan").issuedAt(Instant.EPOCH)
			.expiresAt(Instant.EPOCH.plusSeconds(60));
		if (verified != null) {
			token.claim("email_verified", verified);
		}
		if (email != null) {
			token.claim("email", email);
		}
		return new DefaultOidcUser(java.util.List.of(), token.build());
	}

	/** The request is never read: the delegate answers the user. */
	private static OidcUserRequest request() {
		return null;
	}

}
