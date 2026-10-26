package vn.vibefix.github;

/**
 * The linking OAuth App's grants and tokens (SG-9, AD-10): a single token is revoked once vibefix no longer
 * keeps it, and the whole grant when an account is deleted, with the App's own credentials. Upper modules hold
 * the credentials and the token; this module only calls.
 */
public interface OAuthGrants {

	/**
	 * Revokes the App's grant for the user {@code accessToken} belongs to; a grant or token already gone changes
	 * nothing.
	 * @throws GitHubException for any other answer, with status 0 when GitHub could not be reached
	 */
	void revokeGrant(String clientId, String clientSecret, String accessToken);

	/**
	 * Revokes {@code accessToken} alone ({@code DELETE /applications/{client_id}/token}); the App's grant and any
	 * newer token stay. A token already gone changes nothing.
	 * @throws GitHubException for any other answer, with status 0 when GitHub could not be reached
	 */
	void revokeToken(String clientId, String clientSecret, String accessToken);

}
