package vn.vibefix.github.internal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

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
 * {@code POST /github/actions/exchange}, and the path report a handover copy posts at
 * {@code POST /github/actions/report} (Story 23.8a), both with no session and no CSRF token. A test names the stub
 * the issuer ({@code vibefix.github.exchange.issuer}). ID tokens are fake, built at run time.
 */
public final class ActionsExchangeSupport {

	private static final String KEY_ID = "exchange-support-key";

	/** The address a run calls from, unless a test names its own. */
	public static final String ADDRESS = "192.0.2.20";

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

	/**
	 * The run's path report under {@code correlation} (Story 23.8a): the paths NUL separated as
	 * {@code git diff -z} writes them, gzipped and base64 encoded.
	 */
	public static MockHttpServletRequestBuilder report(String idToken, UUID correlation, boolean workflowsChanged,
			List<String> paths) {
		return report(idToken, correlation, workflowsChanged, paths, ADDRESS);
	}

	/** The same report from {@code address}, for a test whose runs must not share the abuse limits (AD-34). */
	public static MockHttpServletRequestBuilder report(String idToken, UUID correlation, boolean workflowsChanged,
			List<String> paths, String address) {
		return report(reportBody(idToken, correlation.toString(), workflowsChanged, paths, paths.size()), address);
	}

	/** One report body, posted from {@code address}: the only encoder of a run's list that the tests use. */
	public static MockHttpServletRequestBuilder report(Map<String, Object> body, String address) {
		return post(ActionsReportController.PATH).contentType(MediaType.APPLICATION_JSON)
			.content(GitHubHttp.JSON.writeValueAsString(body))
			.with(request -> {
				request.setRemoteAddr(address);
				return request;
			});
	}

	/**
	 * A report body, so a test can name a {@code count} or a list no run would write.
	 * @param paths NUL-terminated and gzipped as {@code git diff -z} writes them
	 */
	public static Map<String, Object> reportBody(String idToken, String correlation, boolean workflowsChanged,
			List<String> paths, int count) {
		ByteArrayOutputStream plain = new ByteArrayOutputStream();
		for (String path : paths) {
			plain.writeBytes(path.getBytes(StandardCharsets.UTF_8));
			plain.write(0);
		}
		return reportBody(idToken, correlation, workflowsChanged, gzipped(plain.toByteArray()), count);
	}

	/** The same body with a list of the caller's own, already base64 encoded, however broken. */
	public static Map<String, Object> reportBody(String idToken, String correlation, boolean workflowsChanged,
			String encodedPaths, int count) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("id_token", idToken);
		body.put("correlation", correlation);
		body.put("workflows_changed", workflowsChanged);
		body.put("paths", encodedPaths);
		body.put("count", count);
		return body;
	}

	/** {@code plain} gzipped and base64 encoded, as the run posts its list. */
	public static String gzipped(byte[] plain) {
		ByteArrayOutputStream gzip = new ByteArrayOutputStream();
		try (GZIPOutputStream out = new GZIPOutputStream(gzip)) {
			out.write(plain);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return Base64.getEncoder().encodeToString(gzip.toByteArray());
	}

	/** The run's exchange of {@code idToken} under {@code correlation}. */
	public static MockHttpServletRequestBuilder exchange(String idToken, UUID correlation) {
		return exchange(idToken, correlation, ADDRESS);
	}

	/** The same exchange from {@code address}, for a test whose runs must not share the abuse limits (AD-34). */
	public static MockHttpServletRequestBuilder exchange(String idToken, UUID correlation, String address) {
		return post(ActionsExchangeController.PATH).contentType(MediaType.APPLICATION_JSON)
			.content(GitHubHttp.JSON.writeValueAsString(Map.of("id_token", idToken, "correlation", correlation.toString())))
			.with(request -> {
				request.setRemoteAddr(address);
				return request;
			});
	}

}
