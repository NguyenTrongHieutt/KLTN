package vn.vibefix.github;

/**
 * The sign-in OAuth App's grants (SG-9): revoked when an account is deleted, with the App's own credentials and
 * the token the person signed in with. Upper modules hold the credentials and the token; this module only calls.
 */
public interface OAuthGrants {

	/**
	 * Revokes the App's grant for the user {@code accessToken} belongs to; a grant or token already gone changes
	 * nothing.
	 * @throws GitHubException for any other answer, with status 0 when GitHub could not be reached
	 */
	void revokeGrant(String clientId, String clientSecret, String accessToken);

}
