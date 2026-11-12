package vn.vibefix.github.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import vn.vibefix.github.GitHubApps.InstallationToken;

/**
 * Spike S6 revision: the token exchange gives a run's GitHub Actions ID token one Platform App token
 * narrowed to its correlation, only for a genuine run of the published file, and refuses everything else
 * with one reason code: a forged, expired, foreign-audience or foreign-issuer token (only counted, never
 * held against a correlation); a self-hosted runner; another repository or repository ID, event,
 * workflow, head, actor or title; a changed file (before its ref); another ref; a correlation never
 * dispatched or already exchanged. A correlation keeps at most 32 refusals. A finished correlation
 * revokes its token and takes no more, also when it finishes while an exchange reads the run or mints;
 * two genuine attempts at once get one token; closing revokes whatever is still out, once. Nothing is
 * ever printed.
 */
class OidcExchangeTest {

	private static final Instant NOW = Instant.parse("2026-11-09T03:00:00Z");

	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

	private static final String REPOSITORY = "lan-shop/quan-may-private";

	private static final long REPOSITORY_ID = 9001;

	private static final String PATH = ".github/workflows/vibefix.yml";

	private static final String PUBLISHED = "name: vibefix\r\non:\r\n  workflow_dispatch:\r\n";

	private static final String HEAD = "1f".repeat(20);

	private static final String BOT = "vibefix-dev-source[bot]";

	private static final long RUN = 4242;

	private static final String CORRELATION = "j7-copy-0123456789abcdef";

	private static final OidcExchange.Grant GRANT = new OidcExchange.Grant(Set.of(7001L, 7002L),
			Map.of("contents", "write", "workflows", "write"));

	private static final String KEY_ID = "test-key";

	private static final RSAKey KEY = TestKeys.jwk(KEY_ID);

	/** The flaws refused before the token is verified: counted only, never held against a correlation. */
	private static final Set<String> UNVERIFIED = Set.of("a foreign key", "HS256", "another audience", "two audiences",
			"another issuer", "an expired token", "a token not yet valid", "a token issued later", "no expiry");

	private HttpServer issuer;

	private String issuerName;

	private OidcExchange exchange;

	private final List<InstallationToken> minted = Collections.synchronizedList(new ArrayList<>());

	private final List<InstallationToken> revoked = Collections.synchronizedList(new ArrayList<>());

	/** What a revocation answers. */
	private final AtomicInteger revocationStatus = new AtomicInteger(204);

	/** The claims the next ID token carries. */
	private final Map<String, Object> claims = new LinkedHashMap<>();

	/** What {@code GET /repos/{repository}/actions/runs/{id}} answers for {@link #RUN}. */
	private final Map<String, Object> run = new LinkedHashMap<>();

	/** The blob of the workflow file at {@link #HEAD}. */
	private String blobAtHead = OidcExchange.blobSha(PUBLISHED);

	private final AtomicBoolean mintRefused = new AtomicBoolean();

	/** Runs while the exchange reads the run, and while it mints. */
	private volatile Runnable whileReading = () -> {
	};

	private volatile Runnable whileMinting = () -> {
	};

	@BeforeEach
	void anIssuerAndAnExchange() throws IOException {
		this.issuer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		this.issuer.createContext("/.well-known/jwks", http -> {
			byte[] bytes = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
			http.sendResponseHeaders(200, bytes.length);
			try (OutputStream out = http.getResponseBody()) {
				out.write(bytes);
			}
			http.close();
		});
		this.issuer.start();
		this.issuerName = "http://127.0.0.1:" + this.issuer.getAddress().getPort();
		this.exchange = exchange();
		this.exchange.expect(expected(CORRELATION));
		this.claims.put("iss", this.issuerName);
		this.claims.put("aud", OidcExchange.AUDIENCE);
		this.claims.put("sub", "repo:" + REPOSITORY + ":ref:refs/heads/main");
		this.claims.put("iat", Date.from(NOW.minusSeconds(5)));
		this.claims.put("nbf", Date.from(NOW.minusSeconds(5)));
		this.claims.put("exp", Date.from(NOW.plus(Duration.ofMinutes(5))));
		this.claims.put("repository", REPOSITORY);
		this.claims.put("repository_id", String.valueOf(REPOSITORY_ID));
		this.claims.put("ref", "refs/heads/main");
		this.claims.put("sha", HEAD);
		this.claims.put("workflow_sha", HEAD);
		this.claims.put("event_name", "workflow_dispatch");
		this.claims.put("run_id", String.valueOf(RUN));
		this.claims.put("runner_environment", "github-hosted");
		this.run.put("id", RUN);
		this.run.put("path", PATH);
		this.run.put("head_sha", HEAD);
		this.run.put("triggering_actor", Map.of("login", BOT));
		this.run.put("display_title", "vibefix copy " + CORRELATION);
	}

	@AfterEach
	void stop() {
		this.exchange.close();
		try {
			this.issuer.stop(0);
		}
		catch (RuntimeException alreadyStopped) {
			// One test stops the issuer itself.
		}
	}

	@Test
	void aGenuineRunGetsOneNarrowedTokenOnceAndItIsRevokedWhenTheRunEnds() throws Exception {
		PrintStream out = System.out;
		PrintStream err = System.err;
		ByteArrayOutputStream printed = new ByteArrayOutputStream();
		OidcExchange.Answer first;
		OidcExchange.Answer replay;
		try {
			System.setOut(new PrintStream(printed, true, StandardCharsets.UTF_8));
			System.setErr(new PrintStream(printed, true, StandardCharsets.UTF_8));
			first = this.exchange.exchange(body(sign(KEY), CORRELATION));
			replay = this.exchange.exchange(body(sign(KEY), CORRELATION));
		}
		finally {
			System.setOut(out);
			System.setErr(err);
		}

		assertThat(first.status()).isEqualTo(200);
		assertThat(first.token()).isNotNull().isEqualTo(this.minted.getFirst());
		assertThat(this.minted).as("one token for the correlation").hasSize(1);
		assertThat(replay.status()).isEqualTo(403);
		assertThat(replay.reason()).isEqualTo("replay");
		assertThat(this.revoked).as("nothing is revoked while the run goes on").isEmpty();
		assertThat(printed.toString(StandardCharsets.UTF_8)).as("the exchange prints nothing").isEmpty();

		OidcExchange.Result result = this.exchange.finish(CORRELATION);

		assertThat(result.issued()).isTrue();
		assertThat(result.runId()).isEqualTo(RUN);
		assertThat(result.grant()).isEqualTo(GRANT);
		assertThat(result.refusals()).containsExactly("replay");
		assertThat(result.revoked()).isEqualTo(204);
		assertThat(this.revoked).containsExactly(this.minted.getFirst());
		assertThat(this.exchange.finish(CORRELATION)).as("finishing again revokes nothing twice").isEqualTo(result);
		assertThat(this.revoked).hasSize(1);
		assertThat(this.exchange.exchange(body(sign(KEY), CORRELATION)).reason()).as("a finished correlation takes no more")
			.isEqualTo("correlation");
		assertThat(this.exchange.unrevoked()).isZero();
	}

	@Test
	void timesWithinTheSkewAreAccepted() throws Exception {
		this.claims.put("exp", Date.from(NOW.minusSeconds(50)));
		this.claims.put("nbf", Date.from(NOW.plusSeconds(50)));
		this.claims.put("iat", Date.from(NOW.plusSeconds(50)));

		assertThat(this.exchange.exchange(body(sign(KEY), CORRELATION)).status()).isEqualTo(200);
	}

	@ParameterizedTest(name = "{0} is refused as {1}")
	@CsvSource({ "a foreign key, signature", "HS256, algorithm", "another audience, audience", "two audiences, audience",
			"another issuer, issuer", "an expired token, expired", "a token not yet valid, not_yet_valid",
			"a token issued later, not_yet_valid", "no expiry, malformed", "another repository, repository",
			"another repository ID, repository", "a push, event", "another ref, ref", "a self-hosted runner, runner",
			"an unknown run, run", "another workflow file, workflow", "another head, head_sha", "a foreign actor, actor",
			"a foreign title, title", "a changed file, blob", "a changed file on another ref, blob",
			"an unknown correlation, correlation", "no run ID, malformed" })
	void anythingButAGenuineRunIsRefusedWithOneReasonAndNoToken(String flaw, String reason) throws Exception {
		RSAKey key = KEY;
		String correlation = CORRELATION;
		switch (flaw) {
			case "a foreign key" -> key = TestKeys.foreignJwk(KEY_ID);
			case "another audience" -> this.claims.put("aud", "sts.amazonaws.com");
			case "two audiences" -> this.claims.put("aud", List.of(OidcExchange.AUDIENCE, "sts.amazonaws.com"));
			case "another issuer" -> this.claims.put("iss", OidcExchange.GITHUB_ISSUER);
			case "an expired token" -> this.claims.put("exp", Date.from(NOW.minusSeconds(61)));
			case "a token not yet valid" -> this.claims.put("nbf", Date.from(NOW.plusSeconds(120)));
			case "a token issued later" -> this.claims.put("iat", Date.from(NOW.plusSeconds(120)));
			case "no expiry" -> this.claims.remove("exp");
			case "another repository" -> this.claims.put("repository", "mallory/quan-may-private");
			case "another repository ID" -> this.claims.put("repository_id", "9002");
			case "a push" -> this.claims.put("event_name", "push");
			case "another ref" -> this.claims.put("ref", "refs/heads/tampered");
			case "a self-hosted runner" -> this.claims.put("runner_environment", "self-hosted");
			case "an unknown run" -> this.claims.put("run_id", "999");
			case "another workflow file" -> this.run.put("path", ".github/workflows/other.yml");
			case "another head" -> this.run.put("head_sha", "2e".repeat(20));
			case "a foreign actor" -> this.run.put("triggering_actor", Map.of("login", "mallory"));
			case "a foreign title" -> this.run.put("display_title", "vibefix copy");
			case "a changed file" -> this.blobAtHead = OidcExchange.blobSha(PUBLISHED + "# changed\n");
			case "a changed file on another ref" -> {
				this.claims.put("ref", "refs/heads/tampered");
				this.blobAtHead = OidcExchange.blobSha(PUBLISHED + "# changed\n");
			}
			case "an unknown correlation" -> correlation = "j7-copy-fedcba9876543210";
			case "no run ID" -> this.claims.remove("run_id");
			default -> {
			}
		}
		String idToken = flaw.equals("HS256") ? signHs256() : sign(key);

		OidcExchange.Answer answer = this.exchange.exchange(body(idToken, correlation));

		assertThat(answer.status()).isEqualTo(403);
		assertThat(answer.reason()).isEqualTo(reason);
		assertThat(answer.token()).isNull();
		assertThat(this.minted).as("no token is minted").isEmpty();
		OidcExchange.Result result = this.exchange.finish(CORRELATION);
		assertThat(result.issued()).isFalse();
		assertThat(result.revoked()).isZero();
		if (UNVERIFIED.contains(flaw) || reason.equals("correlation")) {
			assertThat(result.refusals()).as("held against no correlation").isEmpty();
			assertThat(this.exchange.unmatched()).isOne();
		}
		else {
			assertThat(result.refusals()).containsExactly(reason);
			assertThat(this.exchange.unmatched()).isZero();
		}
	}

	@Test
	void forgedTokensNamingALiveCorrelationLeaveItsRefusalsEmpty() throws Exception {
		for (int i = 0; i < 40; i++) {
			assertThat(this.exchange.exchange(body(sign(TestKeys.foreignJwk(KEY_ID)), CORRELATION)).reason())
				.isEqualTo("signature");
		}

		assertThat(this.exchange.exchange(body(sign(KEY), CORRELATION)).status()).as("the genuine run still gets its token")
			.isEqualTo(200);
		OidcExchange.Result result = this.exchange.finish(CORRELATION);
		assertThat(result.refusals()).isEmpty();
		assertThat(result.dropped()).isZero();
		assertThat(this.exchange.unmatched()).isEqualTo(40);
	}

	@Test
	void aCorrelationKeepsAtMost32RefusalsAndCountsTheRest() throws Exception {
		this.claims.put("ref", "refs/heads/tampered");
		for (int i = 0; i < 40; i++) {
			assertThat(this.exchange.exchange(body(sign(KEY), CORRELATION)).reason()).isEqualTo("ref");
		}

		OidcExchange.Result result = this.exchange.finish(CORRELATION);

		assertThat(result.refusals()).hasSize(OidcExchange.MAX_REFUSALS).containsOnly("ref");
		assertThat(result.dropped()).isEqualTo(8);
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = { "not json", "[]", "{\"correlation\":\"j7-copy-0123456789abcdef\"}",
			"{\"id_token\":\"x.y.z\",\"correlation\":\"j7-copy-0123456789abcdef\"}",
			"{\"id_token\":\"x.y.z\",\"correlation\":\"../etc\"}" })
	void aMalformedBodyIsRefused(String body) {
		OidcExchange.Answer answer = this.exchange.exchange(body.getBytes(StandardCharsets.UTF_8));

		assertThat(answer.status()).isEqualTo(403);
		assertThat(answer.reason()).isEqualTo("malformed");
		assertThat(this.minted).isEmpty();
		assertThat(this.exchange.unmatched()).isOne();
		assertThat(this.exchange.finish(CORRELATION).refusals()).isEmpty();
	}

	@Test
	void aMintGitHubRefusesIsNoExchangeAndTheRunMayAskAgain() throws Exception {
		this.mintRefused.set(true);
		OidcExchange.Answer refused = this.exchange.exchange(body(sign(KEY), CORRELATION));
		this.mintRefused.set(false);

		OidcExchange.Answer again = this.exchange.exchange(body(sign(KEY), CORRELATION));

		assertThat(refused.status()).isEqualTo(502);
		assertThat(refused.reason()).isEqualTo("mint");
		assertThat(again.status()).isEqualTo(200);
		assertThat(this.exchange.finish(CORRELATION).refusals()).containsExactly("mint");
	}

	@Test
	void aTokenMintedAfterTheRunEndedIsRevokedAtOnceAndAFailedRevocationIsCounted() throws Exception {
		this.revocationStatus.set(500);
		this.whileMinting = () -> this.exchange.finish(CORRELATION);

		OidcExchange.Answer answer = this.exchange.exchange(body(sign(KEY), CORRELATION));

		assertThat(answer.status()).isEqualTo(403);
		assertThat(answer.reason()).isEqualTo("correlation");
		assertThat(answer.token()).isNull();
		assertThat(this.minted).hasSize(1);
		assertThat(this.revoked).as("the late token never leaves the exchange").containsExactlyElementsOf(this.minted);
		assertThat(this.exchange.unrevoked()).as("GitHub's 500 to that revocation is counted").isOne();
		assertThat(this.exchange.finish(CORRELATION).issued()).isFalse();
	}

	@Test
	void aRunThatEndsWhileTheExchangeReadsItGetsNoToken() throws Exception {
		this.whileReading = () -> this.exchange.finish(CORRELATION);

		OidcExchange.Answer answer = this.exchange.exchange(body(sign(KEY), CORRELATION));

		assertThat(answer.status()).isEqualTo(403);
		assertThat(answer.reason()).isEqualTo("correlation");
		assertThat(this.minted).as("nothing is minted for a finished correlation").isEmpty();
		assertThat(this.revoked).isEmpty();
		assertThat(this.exchange.finish(CORRELATION).issued()).isFalse();
	}

	@Test
	void twoGenuineAttemptsAtOnceGetExactlyOneToken() throws Exception {
		CountDownLatch bothReading = new CountDownLatch(2);
		this.whileReading = () -> {
			bothReading.countDown();
			try {
				bothReading.await(10, TimeUnit.SECONDS);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		};
		byte[] request = body(sign(KEY), CORRELATION);
		ExecutorService threads = Executors.newFixedThreadPool(2);
		List<OidcExchange.Answer> answers;
		try {
			CompletableFuture<OidcExchange.Answer> one = CompletableFuture.supplyAsync(() -> this.exchange.exchange(request),
					threads);
			CompletableFuture<OidcExchange.Answer> two = CompletableFuture.supplyAsync(() -> this.exchange.exchange(request),
					threads);
			answers = List.of(one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS));
		}
		finally {
			threads.shutdownNow();
		}

		assertThat(answers).extracting(OidcExchange.Answer::status).containsExactlyInAnyOrder(200, 403);
		assertThat(answers).extracting(OidcExchange.Answer::reason).containsExactlyInAnyOrder(null, "replay");
		assertThat(this.minted).hasSize(1);
	}

	@Test
	void aKeySetThatCannotBeReadIsNoRefusalOfTheToken() throws Exception {
		this.issuer.stop(0);

		OidcExchange.Answer answer = this.exchange.exchange(body(sign(KEY), CORRELATION));

		assertThat(answer.status()).isEqualTo(502);
		assertThat(answer.reason()).isEqualTo("jwks");
		assertThat(this.minted).isEmpty();
	}

	@Test
	void closingRevokesEveryTokenStillOutAndFinishesEveryCorrelation() throws Exception {
		this.exchange.expect(expected("handover-1-0123456789abcdef"));
		assertThat(this.exchange.exchange(body(sign(KEY), CORRELATION)).status()).isEqualTo(200);

		this.exchange.close();

		assertThat(this.revoked).containsExactlyElementsOf(this.minted);
		assertThat(this.exchange.finish(CORRELATION).issued()).isTrue();
		assertThat(this.exchange.finish("handover-1-0123456789abcdef").issued()).isFalse();
		assertThat(this.exchange.exchange(body(sign(KEY), CORRELATION)).reason()).isEqualTo("correlation");
	}

	@Test
	void aSecondCloseAsAShutdownHookWaitsForTheFirstAndRevokesNothingTwice() throws Exception {
		assertThat(this.exchange.exchange(body(sign(KEY), CORRELATION)).status()).isEqualTo(200);

		CompletableFuture<Void> hook = CompletableFuture.runAsync(this.exchange::close);
		this.exchange.close();
		hook.get(20, TimeUnit.SECONDS);

		assertThat(this.revoked).containsExactlyElementsOf(this.minted).hasSize(1);
	}

	@Test
	void theServerAnswersPostExchangeOnlyWithJsonAndNoCaching() throws Exception {
		OidcExchange served = exchange().listen(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
		try {
			served.expect(expected(CORRELATION));
			String url = "http://127.0.0.1:" + served.port();
			HttpClient client = HttpClient.newHttpClient();

			HttpResponse<String> ok = client.send(HttpRequest.newBuilder(URI.create(url + OidcExchange.PATH))
				.POST(HttpRequest.BodyPublishers.ofByteArray(body(sign(KEY), CORRELATION)))
				.build(), HttpResponse.BodyHandlers.ofString());
			HttpResponse<String> replay = client.send(HttpRequest.newBuilder(URI.create(url + OidcExchange.PATH))
				.POST(HttpRequest.BodyPublishers.ofByteArray(body(sign(KEY), CORRELATION)))
				.build(), HttpResponse.BodyHandlers.ofString());
			HttpResponse<String> get = client.send(HttpRequest.newBuilder(URI.create(url + OidcExchange.PATH)).GET().build(),
					HttpResponse.BodyHandlers.ofString());
			HttpResponse<String> elsewhere = client.send(HttpRequest.newBuilder(URI.create(url + "/other"))
				.POST(HttpRequest.BodyPublishers.ofString("{}"))
				.build(), HttpResponse.BodyHandlers.ofString());
			HttpResponse<String> large = client.send(HttpRequest.newBuilder(URI.create(url + OidcExchange.PATH))
				.POST(HttpRequest.BodyPublishers.ofString("x".repeat(17 * 1024)))
				.build(), HttpResponse.BodyHandlers.ofString());
			// Story 23.4: the published workflows post to the production path, which the probe serves too.
			HttpResponse<String> production = client.send(
					HttpRequest.newBuilder(URI.create(url + OidcExchange.PRODUCTION_PATH))
						.POST(HttpRequest.BodyPublishers.ofByteArray(body(sign(KEY), CORRELATION)))
						.build(),
					HttpResponse.BodyHandlers.ofString());

			assertThat(ok.statusCode()).isEqualTo(200);
			assertThat(ok.headers().firstValue("Cache-Control")).hasValue("no-store");
			JsonNode issued = GitHubHttp.JSON.readTree(ok.body());
			assertThat(issued.path("token").asString()).isEqualTo(this.minted.getFirst().value());
			assertThat(issued.path("expires_at").asString()).isEqualTo(this.minted.getFirst().expiresAt().toString());
			assertThat(replay.statusCode()).isEqualTo(403);
			assertThat(replay.body()).isEqualTo("{\"reason\":\"replay\"}");
			assertThat(get.statusCode()).isEqualTo(405);
			assertThat(elsewhere.statusCode()).isEqualTo(404);
			assertThat(large.statusCode()).isEqualTo(413);
			assertThat(production.statusCode()).as("served, and refused as the correlation's second exchange")
				.isEqualTo(403);
			assertThat(production.body()).isEqualTo("{\"reason\":\"replay\"}");
		}
		finally {
			served.close();
		}
		assertThat(this.revoked).as("closing the server revoked the token it issued").containsExactlyElementsOf(this.minted);
	}

	@Test
	void theBlobIsGitHashObjectWithCrlfReadAsLf() {
		assertThat(OidcExchange.blobSha("hello\n")).isEqualTo("ce013625030ba8dba906f756967f9e9ca394464a");
		assertThat(OidcExchange.blobSha("hello\r\n")).isEqualTo("ce013625030ba8dba906f756967f9e9ca394464a");
		assertThat(OidcExchange.blobSha(PUBLISHED)).isEqualTo(SpikeS6Stub.blobSha(PUBLISHED.replace("\r\n", "\n")));
	}

	private OidcExchange exchange() {
		return new OidcExchange(new OidcExchange.Issuer(this.issuerName, URI.create(this.issuerName + "/.well-known/jwks")),
				CLOCK, token -> {
					this.revoked.add(token);
					return this.revocationStatus.get();
				});
	}

	private OidcExchange.Expected expected(String correlation) {
		return new OidcExchange.Expected(correlation, REPOSITORY, REPOSITORY_ID, "main", PATH, OidcExchange.blobSha(PUBLISHED),
				BOT, GRANT, new OidcExchange.Reads() {

					@Override
					public @Nullable JsonNode run(long runId) {
						OidcExchangeTest.this.whileReading.run();
						return runId == RUN ? GitHubHttp.JSON.valueToTree(OidcExchangeTest.this.run) : null;
					}

					@Override
					public @Nullable String blob(String path, String commit) {
						return PATH.equals(path) && HEAD.equals(commit) ? OidcExchangeTest.this.blobAtHead : null;
					}

				}, () -> {
					if (this.mintRefused.get()) {
						throw new vn.vibefix.github.GitHubException(422, "GitHub refused the installation token");
					}
					// Built at run time, as a fake token.
					InstallationToken token = new InstallationToken(String.join("", "ghs", "_", "exchanged", String.valueOf(
							this.minted.size()), "x".repeat(20)), NOW.plus(Duration.ofHours(1)), GRANT.repositoryIds());
					this.minted.add(token);
					this.whileMinting.run();
					return token;
				});
	}

	private String sign(RSAKey key) throws JOSEException {
		SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY_ID).build(), claims());
		jwt.sign(new RSASSASigner(key));
		return jwt.serialize();
	}

	private String signHs256() throws JOSEException {
		SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(KEY_ID).build(), claims());
		jwt.sign(new MACSigner("ab".repeat(32).getBytes(StandardCharsets.US_ASCII)));
		return jwt.serialize();
	}

	private JWTClaimsSet claims() {
		JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder();
		this.claims.forEach(builder::claim);
		return builder.build();
	}

	private static byte[] body(String idToken, String correlation) {
		return GitHubHttp.JSON.writeValueAsBytes(Map.of("id_token", idToken, "correlation", correlation));
	}

}
