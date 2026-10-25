package vn.vibefix.identity.internal;

import java.util.Optional;

import org.jspecify.annotations.Nullable;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.stereotype.Component;

/**
 * The sign-in Google client of this environment (FR-1, AD-19, AD-25), an OpenID Connect client built
 * from the secret files {@code google-oauth-client-id} and {@code google-oauth-client-secret}. The
 * scopes are set explicitly to {@code openid email profile}, nothing more. Spring validates the ID
 * token against Google's keys, issuer, audience, and the nonce; the account key is the subject
 * ({@code sub}), never the email. Without the files the {@code dev} profile still starts and PUB-2
 * says sign-in is not set up; {@code prod} refuses to start. The endpoints are configuration so tests
 * can point them at a stub.
 */
@Component
class GoogleSignIn {

	static final String REGISTRATION_ID = "google";

	static final String CLIENT_ID = "google-oauth-client-id";

	static final String CLIENT_SECRET = "google-oauth-client-secret";

	private final @Nullable ClientRegistration registration;

	GoogleSignIn(Environment environment) {
		String clientId = environment.getProperty(CLIENT_ID, "").strip();
		String clientSecret = environment.getProperty(CLIENT_SECRET, "").strip();
		if (clientId.isEmpty() || clientSecret.isEmpty()) {
			if (environment.acceptsProfiles(Profiles.of("prod"))) {
				throw new IllegalStateException("The Google sign-in needs the secret files " + CLIENT_ID + " and "
						+ CLIENT_SECRET + " in production (AD-25); refusing to start");
			}
			this.registration = null;
			return;
		}
		this.registration = ClientRegistration.withRegistrationId(REGISTRATION_ID)
			.clientId(clientId)
			.clientSecret(clientSecret)
			.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
			.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
			.redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
			.scope("openid", "email", "profile")
			.issuerUri(environment.getRequiredProperty("vibefix.google.oauth.issuer-uri"))
			.authorizationUri(environment.getRequiredProperty("vibefix.google.oauth.authorization-uri"))
			.tokenUri(environment.getRequiredProperty("vibefix.google.oauth.token-uri"))
			.jwkSetUri(environment.getRequiredProperty("vibefix.google.oauth.jwk-set-uri"))
			.userInfoUri(environment.getRequiredProperty("vibefix.google.oauth.user-info-uri"))
			.userNameAttributeName(IdTokenClaimNames.SUB)
			.clientName("Google")
			.build();
	}

	/** The registration, or empty when this environment has no Google client yet. */
	Optional<ClientRegistration> registration() {
		return Optional.ofNullable(this.registration);
	}

}
