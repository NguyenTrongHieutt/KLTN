package vn.vibefix.identity.internal;

import java.util.Optional;

import org.jspecify.annotations.Nullable;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.stereotype.Component;

/**
 * The linking OAuth App of this environment (AD-10, AD-25), built from the secret files
 * {@code github-oauth-client-id} and {@code github-oauth-client-secret}. The scope is set explicitly
 * to {@code read:user user:email}, never a repository scope (FR-80, SG-11). Without the files the
 * {@code dev} profile still starts and ACC-2 says linking is not set up; {@code prod} refuses to
 * start. The endpoint URIs are configuration so tests can point them at a stub.
 * <p>
 * It signs nobody in: users sign in with Google ({@link GoogleSignIn}). Since Story 19.2 a signed-in
 * account links GitHub through it ({@link GitHubLinkController}), and its callback is
 * {@value #CALLBACK_PATH}, outside Spring's login filter.
 */
@Component
class GitHubSignIn {

	static final String REGISTRATION_ID = "github";

	static final String CLIENT_ID = "github-oauth-client-id";

	static final String CLIENT_SECRET = "github-oauth-client-secret";

	/** Where GitHub returns the browser after linking; the Vite dev server forwards it to the backend. */
	static final String CALLBACK_PATH = "/link/oauth2/code/" + REGISTRATION_ID;

	private final @Nullable ClientRegistration registration;

	private final @Nullable String userEmailsUri;

	GitHubSignIn(Environment environment) {
		this.userEmailsUri = environment.getProperty("vibefix.github.oauth.user-emails-uri");
		String clientId = environment.getProperty(CLIENT_ID, "").strip();
		String clientSecret = environment.getProperty(CLIENT_SECRET, "").strip();
		if (clientId.isEmpty() || clientSecret.isEmpty()) {
			if (environment.acceptsProfiles(Profiles.of("prod"))) {
				throw new IllegalStateException("The sign-in OAuth App needs the secret files " + CLIENT_ID + " and "
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
			.redirectUri("{baseUrl}" + CALLBACK_PATH)
			.scope("read:user", "user:email")
			.authorizationUri(environment.getRequiredProperty("vibefix.github.oauth.authorization-uri"))
			.tokenUri(environment.getRequiredProperty("vibefix.github.oauth.token-uri"))
			.userInfoUri(environment.getRequiredProperty("vibefix.github.oauth.user-info-uri"))
			.userNameAttributeName("id")
			.clientName("GitHub")
			.build();
	}

	/** Where the linking user's email addresses are read (scope {@code user:email}). */
	@Nullable String userEmailsUri() {
		return this.userEmailsUri;
	}

	/** The registration, or empty when this environment has no linking OAuth App yet. */
	Optional<ClientRegistration> registration() {
		return Optional.ofNullable(this.registration);
	}

}
