package vn.vibefix.github.internal;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import org.springframework.stereotype.Component;

import vn.vibefix.github.GitHubException;
import vn.vibefix.github.OAuthGrants;

/**
 * {@link OAuthGrants} on GitHub's REST API: {@code DELETE /applications/{client_id}/grant} with HTTP Basic
 * authentication by the OAuth App and the token in the body. 204 revoked it; 404 and 422 mean the grant or the
 * token is gone already.
 */
@Component
class GitHubOAuthGrants implements OAuthGrants {

	private final GitHubHttp http;

	GitHubOAuthGrants(GitHubProperties properties) {
		this.http = new GitHubHttp(properties.apiUrl());
	}

	@Override
	public void revokeGrant(String clientId, String clientSecret, String accessToken) {
		String basic = Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
		GitHubHttp.Answer answer = this.http.sendAuthorized("DELETE", "/applications/" + clientId + "/grant", "Basic " + basic,
				Map.of("access_token", accessToken));
		if (answer.status() == 204 || answer.status() == 404 || answer.status() == 422) {
			return;
		}
		throw new GitHubException(answer.status(), "GitHub refused the grant revocation: HTTP " + answer.status());
	}

}
