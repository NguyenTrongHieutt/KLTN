package vn.vibefix.identity.internal;

import java.util.Base64;
import java.util.Map;

import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.stereotype.Component;

import vn.vibefix.github.GitHubException;
import vn.vibefix.github.OAuthGrants;
import vn.vibefix.operations.Lane;
import vn.vibefix.operations.OperationHandler;
import vn.vibefix.operations.OperationRun;
import vn.vibefix.operations.Outcome;

/**
 * {@value #TYPE} (SG-9, NFR-11): after an erased Customer's deletion, the sign-in OAuth App's grant is revoked
 * with the token they last signed in with. The token rides sealed as the operation's personal data, opened in
 * memory with the account ID as associated data, and purged when the operation completes. Without the App's
 * credentials (an environment that has none) there is nothing to revoke.
 */
@Component
class OAuthGrantRevocation implements OperationHandler {

	static final String TYPE = "identity.revoke_oauth_grant";

	private final TokenCipher cipher;

	private final GitHubSignIn signIn;

	private final OAuthGrants grants;

	OAuthGrantRevocation(TokenCipher cipher, GitHubSignIn signIn, OAuthGrants grants) {
		this.cipher = cipher;
		this.signIn = signIn;
		this.grants = grants;
	}

	@Override
	public String type() {
		return TYPE;
	}

	@Override
	public Lane lane() {
		return Lane.GITHUB_SHORT;
	}

	@Override
	public Outcome run(OperationRun run) {
		ClientRegistration registration = this.signIn.registration().orElse(null);
		Map<String, Object> sealed = run.personalData();
		if (registration == null) {
			return new Outcome.Obsolete("NO_OAUTH_APP");
		}
		if (sealed == null || !(run.payload().get("accountId") instanceof String accountId)) {
			return new Outcome.Obsolete("NO_TOKEN");
		}
		String token = this.cipher.open(new TokenCipher.Sealed(((Number) sealed.get("keyVersion")).intValue(),
				Base64.getDecoder().decode((String) sealed.get("nonce")), Base64.getDecoder().decode((String) sealed.get("ciphertext"))),
				accountId);
		try {
			this.grants.revokeGrant(registration.getClientId(), registration.getClientSecret(), token);
			return Outcome.succeeded();
		}
		catch (GitHubException refused) {
			if (refused.status() == 0 || refused.status() >= 500) {
				return new Outcome.Transient("GITHUB_UNAVAILABLE");
			}
			return new Outcome.Refused("GRANT_REVOCATION_REFUSED");
		}
	}

}
