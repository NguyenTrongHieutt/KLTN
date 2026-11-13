package vn.vibefix.github.internal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * GitHub Actions' side of the token exchange, for the tests of the modules that dispatch runs (Story 23.6b): a stand-in
 * OIDC issuer whose key set {@link GitHubStub} serves, the ID token it signs for a stubbed run, and that run's
 * {@code POST /github/actions/exchange}, with no session and no CSRF token. A test names the stub the issuer
 * ({@code vibefix.github.exchange.issuer}). ID tokens are fake, built at run time.
 */
public final class ActionsExchangeSupport {

	private static final String KEY_ID = "exchange-support-key";

	private static final RSAKey KEY = TestKeys.jwk(KEY_ID);

	private ActionsExchangeSupport() {
	}

	/** The stub serves the stand-in issuer's key set. */
	public static void issue(GitHubStub github) {
		github.jwks = new JWKSet(KEY.toPublicJWK()).toString();
	}

	/**
	 * The ID token GitHub Actions signs for the stubbed run {@code runId}: its repository, numeric ID, branch, head,
	 * event and runner as the stub shows the run, valid around {@code now}.
	 */
	public static String idToken(GitHubStub github, Instant now, long runId, long repositoryId) throws JOSEException {
		Map<String, Object> run = github.runs.get(runId);
		Map<String, Object> claims = new LinkedHashMap<>();
		claims.put("iss", github.apiUrl().toString());
		claims.put("aud", ActionsIdTokens.AUDIENCE);
		claims.put("iat", Date.from(now.minusSeconds(5)));
		claims.put("nbf", Date.from(now.minusSeconds(5)));
		claims.put("exp", Date.from(now.plus(Duration.ofMinutes(5))));
		claims.put("repository", run.get("repository_full_name"));
		claims.put("repository_id", String.valueOf(repositoryId));
		claims.put("ref", "refs/heads/" + run.get("head_branch"));
		claims.put("sha", run.get("head_sha"));
		claims.put("workflow_sha", run.get("head_sha"));
		claims.put("event_name", "workflow_dispatch");
		claims.put("run_id", String.valueOf(runId));
		claims.put("runner_environment", "github-hosted");
		JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder();
		claims.forEach(builder::claim);
		SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY_ID).build(), builder.build());
		jwt.sign(new RSASSASigner(KEY));
		return jwt.serialize();
	}

	/** The run's exchange of {@code idToken} under {@code correlation}. */
	public static MockHttpServletRequestBuilder exchange(String idToken, UUID correlation) {
		return post(ActionsExchangeController.PATH).contentType(MediaType.APPLICATION_JSON)
			.content(GitHubHttp.JSON.writeValueAsString(Map.of("id_token", idToken, "correlation", correlation.toString())))
			.with(request -> {
				request.setRemoteAddr("192.0.2.20");
				return request;
			});
	}

}
