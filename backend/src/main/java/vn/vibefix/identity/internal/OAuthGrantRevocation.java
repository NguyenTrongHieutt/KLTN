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
 * {@value #TYPE} (SG-9, NFR-11, FR-80, AD-10, R31): the linking callback uses GitHub's token once to read the
 * person and keeps it nowhere, so it requests the revocation of the whole OAuth grant the token came with (the
 * payload names no {@code revoke}): a link made since Story 23.5d leaves vibefix authorized on nobody's GitHub
 * account. Someone who linked before then may still find vibefix listed on GitHub, under Settings › Applications,
 * until they remove it there. The open revocations recorded before Story 23.5d with {@value #TOKEN}, which revoked
 * that token alone, lost the key in that story's migration; the mode is still read for any other. The token rides
 * sealed as the operation's personal data, opened in memory with the account ID as associated data, and purged when
 * the operation completes. Without the App's credentials (an environment that has none) there is nothing to revoke.
 */
@Component
class OAuthGrantRevocation implements OperationHandler {

	static final String TYPE = "identity.revoke_oauth_grant";

	/** The payload's {@code revoke} for one token alone. */
	static final String TOKEN = "TOKEN";

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
			if (TOKEN.equals(run.payload().get("revoke"))) {
				this.grants.revokeToken(registration.getClientId(), registration.getClientSecret(), token);
			}
			else {
				this.grants.revokeGrant(registration.getClientId(), registration.getClientSecret(), token);
			}
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
