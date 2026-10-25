package vn.vibefix.identity.internal;

import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * Reads the Google user as Spring does, and refuses a sign-in whose email Google has not verified
 * (FR-1): an {@code email_verified} claim that is false or missing, or no email at all, fails with the
 * OAuth2 error {@code email_not_verified}, before any account or session exists. The claims come from
 * the ID token Spring has already validated: Google names its granted scopes as URLs, so
 * {@code OidcUserService} does not call the userinfo endpoint. Nothing of Google's tokens is kept, and
 * no authorized-client store keeps them (AD-22).
 */
class GoogleUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

	static final String EMAIL_NOT_VERIFIED = "email_not_verified";

	private final OAuth2UserService<OidcUserRequest, OidcUser> delegate;

	GoogleUserService() {
		this(new OidcUserService());
	}

	GoogleUserService(OAuth2UserService<OidcUserRequest, OidcUser> delegate) {
		this.delegate = delegate;
	}

	@Override
	public OidcUser loadUser(OidcUserRequest request) {
		OidcUser user = this.delegate.loadUser(request);
		String email = user.getEmail();
		if (!Boolean.TRUE.equals(user.getEmailVerified()) || email == null || email.isBlank()) {
			throw new OAuth2AuthenticationException(new OAuth2Error(EMAIL_NOT_VERIFIED));
		}
		return user;
	}

}
