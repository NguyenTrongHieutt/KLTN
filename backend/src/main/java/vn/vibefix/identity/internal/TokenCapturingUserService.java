package vn.vibefix.identity.internal;

import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Reads the GitHub user as Spring does, and hands the access token to the sign-in handler through a
 * request attribute: it never enters the principal, so it never reaches the session, and no
 * authorized-client store keeps it (SG-9).
 */
class TokenCapturingUserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

	static final String TOKEN_ATTRIBUTE = TokenCapturingUserService.class.getName() + ".token";

	private final OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate;

	TokenCapturingUserService() {
		this(new DefaultOAuth2UserService());
	}

	TokenCapturingUserService(OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate) {
		this.delegate = delegate;
	}

	@Override
	public OAuth2User loadUser(OAuth2UserRequest request) {
		OAuth2User user = this.delegate.loadUser(request);
		RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
		if (attributes != null) {
			attributes.setAttribute(TOKEN_ATTRIBUTE, request.getAccessToken().getTokenValue(),
					RequestAttributes.SCOPE_REQUEST);
		}
		return user;
	}

}
