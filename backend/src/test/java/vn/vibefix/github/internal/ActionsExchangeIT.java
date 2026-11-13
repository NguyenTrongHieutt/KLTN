package vn.vibefix.github.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import tools.jackson.databind.JsonNode;
import vn.vibefix.github.ActionsRuns;
import vn.vibefix.github.ActionsRuns.Dispatch;
import vn.vibefix.github.ActionsRuns.Dispatched;
import vn.vibefix.github.ActionsRuns.Operation;
import vn.vibefix.github.ActionsRuns.Status;
import vn.vibefix.github.ActionsRuns.WorkflowRefusal;
import vn.vibefix.github.ActionsRuns.WorkflowRefused;
import vn.vibefix.github.ExchangeReason;
import vn.vibefix.github.GitHubApps.App;
import vn.vibefix.identity.internal.AdministratorTestSupport;
import vn.vibefix.identity.internal.AdministratorTestSupport.Administrator;
import vn.vibefix.support.IntegrationTest;

/**
 * Story 23.4 (AD-44, AD-45, AD-46, AD-34): the Actions token exchange in production. Each run is dispatched
 * after its workflow file proves an accepted blob, under a correlation recorded first; its ID token, signed by
 * a stand-in issuer whose key set {@link GitHubStub} serves, is exchanged at {@code POST /github/actions/exchange}
 * without a session or a CSRF token, once, for a token narrowed to the grant; and that token is revoked when the
 * stubbed run completes, or 15 minutes after issue. Every refusal the spec names is tried once: those before
 * verification are only counted, those after it are recorded, audited and alerted. The limits, the 16 KB body,
 * the dev port, a restart's unknown revocation, and the hygiene of logs and refusals are proved too. Tokens and
 * ID tokens are fake, built at run time.
 */
@ExtendWith(OutputCaptureExtension.class)
class ActionsExchangeIT extends IntegrationTest {

	private static final Path DIRECTORY;

	private static final GitHubStub GITHUB;

	private static final String ORGANIZATION = GitHubStub.ORGANIZATION;

	private static final String RUNNER = "vibefix-runner";

	private static final String KEY_ID = "exchange-it-key";

	private static final RSAKey KEY = TestKeys.jwk(KEY_ID);

	private static final String TUNNEL = "https://exchange.example.test";

	private static final long RUNNER_ID;

	private static final long WORKING_ID;

	private static final long INTERMEDIARY_ID;

	/** An organization repository holding the published {@code vibefix.yml}: a Customer's stand-in until Story 23.5. */
	private static final long COPY_TARGET_ID;

	/** Holds the runner's {@code s6-copy.yml} changed by one line. */
	private static final long TAMPERED_RUNNER_ID;

	/** The runner's {@code tampered} branch, whose {@code s6-copy.yml} is changed. */
	private static final String TAMPERED_HEAD;

	/** A Customer's private Source Repository holding the published {@code vibefix.yml} (Story 23.6a). */
	private static final String SOURCE_OWNER = "lan-shop";

	private static final String SOURCE_NAME = "shop-gom";

	private static final String SOURCE = SOURCE_OWNER + "/" + SOURCE_NAME;

	private static final long SOURCE_ID = 900_001;

	private static final long CUSTOMER_GITHUB_ID = 900_101;

	/** The Customer's installation of the Source App, which selects exactly their Source Repository. */
	private static final long SOURCE_INSTALLATION;

	static {
		try {
			DIRECTORY = Files.createTempDirectory("actions-exchange-");
			GITHUB = new GitHubStub(Files.createDirectories(DIRECTORY.resolve("git")));
			Files.writeString(DIRECTORY.resolve("app-key"), TestKeys.pkcs1());
			GITHUB.runner(RUNNER);
			RUNNER_ID = GITHUB.organizationRepositoryId(RUNNER);
			GITHUB.source("lan-shop", "quan-may");
			WORKING_ID = GITHUB.platformRepository("job-1-working", "lan-shop", "quan-may");
			INTERMEDIARY_ID = GITHUB.platformRepository("job-1-intermediary", "lan-shop", "quan-may");
			COPY_TARGET_ID = GITHUB.platformRepository("copy-target", "lan-shop", "quan-may");
			GITHUB.push(ORGANIZATION, "copy-target", "main", ".github/workflows/vibefix.yml",
					GitHubStub.published(GitHubStub.CUSTOMER_WORKFLOW));
			TAMPERED_RUNNER_ID = GITHUB.platformRepository("tampered-runner", "lan-shop", "quan-may");
			GITHUB.push(ORGANIZATION, "tampered-runner", "main", ".github/workflows/s6-copy.yml",
					GitHubStub.published(GitHubStub.RUNNER_WORKFLOW) + "# tampered\n");
			try (org.eclipse.jgit.lib.Repository runner = GITHUB.repository(ORGANIZATION, RUNNER)) {
				var create = runner.updateRef("refs/heads/tampered");
				create.setNewObjectId(runner.resolve("refs/heads/main"));
				create.update();
			}
			TAMPERED_HEAD = GITHUB.push(ORGANIZATION, RUNNER, "tampered", ".github/workflows/s6-copy.yml",
					GitHubStub.published(GitHubStub.RUNNER_WORKFLOW) + "# tampered\n");
			GITHUB.source(SOURCE_OWNER, SOURCE_NAME);
			GITHUB.push(SOURCE_OWNER, SOURCE_NAME, "main", ".github/workflows/vibefix.yml",
					GitHubStub.published(GitHubStub.CUSTOMER_WORKFLOW));
			GITHUB.publish(SOURCE_OWNER, SOURCE_NAME, SOURCE_ID, CUSTOMER_GITHUB_ID, false, true);
			SOURCE_INSTALLATION = GITHUB.installSource(SOURCE_OWNER, SOURCE_NAME, SOURCE_ID, CUSTOMER_GITHUB_ID);
			GITHUB.jwks = new JWKSet(KEY.toPublicJWK()).toString();
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	@Autowired
	private MockMvc mvc;

	@Autowired
	private ActionsRuns runs;

	@Autowired
	private ExchangeRevocations revocations;

	@Autowired
	private IssuedTokens issued;

	@Autowired
	private ExchangeRateLimiter limiter;

	@Autowired
	private ActionsExchange exchange;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private ApplicationContext context;

	@Autowired
	private GitHubAppsClient apps;

	@Autowired
	private ExchangeCorrelations correlations;

	@Autowired
	private DispatchingApps dispatching;

	@Autowired
	private org.springframework.transaction.PlatformTransactionManager transactionManager;

	@Autowired
	private org.springframework.core.env.Environment environment;

	@DynamicPropertySource
	static void github(DynamicPropertyRegistry registry) {
		registry.add("vibefix.github.api-url", () -> GITHUB.apiUrl().toString());
		registry.add("vibefix.github.git-url", () -> GITHUB.gitUrl().toString());
		registry.add("vibefix.github.platform-app.id", () -> "123456");
		registry.add("vibefix.github.platform-app.installation-id", () -> String.valueOf(GitHubStub.INSTALLATION));
		registry.add("vibefix.github.platform-app.key-file", () -> DIRECTORY.resolve("app-key").toString());
		registry.add("vibefix.github.source-app.id", () -> String.valueOf(GitHubStub.SOURCE_APP));
		registry.add("vibefix.github.source-app.key-file", () -> DIRECTORY.resolve("app-key").toString());
		registry.add("vibefix.github.runner-repository", () -> RUNNER);
		registry.add("vibefix.github.exchange.public-url", () -> TUNNEL);
		registry.add("vibefix.github.exchange.issuer", () -> GITHUB.apiUrl().toString());
	}

	@AfterAll
	static void stop() {
		GITHUB.close();
	}

	@BeforeEach
	void aFreshExchange() {
		this.limiter.reset();
		GITHUB.revocationStatus = 204;
		GITHUB.runDetails = true;
		GITHUB.runsHidden = false;
		GITHUB.mintRefused = body -> false;
		GITHUB.unavailable.clear();
		GITHUB.tokenFormat = n -> "ghs_stubtoken_" + n + "_d41d8cd98f";
	}

	@Test
	void aGenuineRunGetsOneNarrowedTokenWhichIsRevokedWhenItsRunCompletes() throws Exception {
		Dispatched dispatched = handover();
		Map<String, Object> run = GITHUB.runs.get(dispatched.runId());

		assertThat(dispatched.runId()).isPositive();
		assertThat(run.get("display_title")).asString().isEqualTo("s6-copy " + dispatched.correlation());
		assertThat(run.get("head_branch")).isEqualTo("main");
		assertThat(inputs(run)).containsEntry("correlation", dispatched.correlation().toString())
			.containsEntry("exchange_url", TUNNEL)
			.containsEntry("target_branch", "handover/1");
		assertThat(this.runs.correlation(dispatched.correlation())).hasValueSatisfying(correlation -> {
			assertThat(correlation.status()).isEqualTo(Status.OPEN);
			assertThat(correlation.operation()).isEqualTo(Operation.HANDOVER_COPY);
			assertThat(correlation.repository()).isEqualTo(ORGANIZATION + "/" + RUNNER);
			assertThat(correlation.repositoryId()).isEqualTo(RUNNER_ID);
			assertThat(correlation.workflowPath()).isEqualTo(".github/workflows/s6-copy.yml");
			assertThat(correlation.grant()).containsExactlyInAnyOrder(WORKING_ID, INTERMEDIARY_ID);
			assertThat(correlation.runId()).isEqualTo(dispatched.runId());
		});
		int minted = GITHUB.tokens.size();

		MockHttpServletResponse answer = this.mvc.perform(exchange(sign(claims(dispatched, RUNNER_ID)), dispatched.correlation()))
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.expires_at").isString())
			.andReturn()
			.getResponse();

		String token = GitHubHttp.JSON.readTree(answer.getContentAsString()).path("token").asString();
		assertThat(token).isEqualTo(GITHUB.tokens.getLast());
		JsonNode grant = GITHUB.seen.stream()
			.filter(seen -> seen.method().equals("POST") && seen.path().endsWith("/access_tokens"))
			.toList()
			.getLast()
			.body();
		assertThat(longs(grant.path("repository_ids"))).as("narrowed to the grant")
			.containsExactlyInAnyOrder(WORKING_ID, INTERMEDIARY_ID);
		Map<String, String> permissions = new LinkedHashMap<>();
		grant.path("permissions").properties().forEach(entry -> permissions.put(entry.getKey(), entry.getValue().asString()));
		assertThat(permissions).as("with exactly the operation's permissions")
			.isEqualTo(Map.of("contents", "write", "workflows", "write"));
		assertThat(GITHUB.tokens.size()).as("the reads mint their own tokens, and one more is issued")
			.isGreaterThan(minted);
		assertThat(this.runs.correlation(dispatched.correlation()).orElseThrow().status()).isEqualTo(Status.EXCHANGED);

		this.revocations.poll();
		assertThat(GITHUB.revokedTokens).as("the run goes on: its token stays").doesNotContain(token);

		GITHUB.completeRun(dispatched.runId(), "success");
		this.revocations.poll();

		assertThat(GITHUB.revokedTokens).contains(token);
		assertThat(this.runs.correlation(dispatched.correlation())).hasValueSatisfying(correlation -> {
			assertThat(correlation.status()).isEqualTo(Status.REVOKED);
			assertThat(correlation.issuedAt()).isNotNull();
			assertThat(correlation.revokedAt()).isNotNull();
		});
		assertThat(this.issued.get(dispatched.correlation())).isEmpty();
		awaitListeners();
		assertThat(audited(dispatched.correlation())).containsExactlyInAnyOrder("github.exchange_issued", "github.exchange_revoked");
	}

	@ParameterizedTest(name = "{0} is refused as {1}")
	@CsvSource({ "a forged signature, SIGNATURE", "a wrong audience, AUDIENCE", "an expired token, EXPIRY",
			"an unknown correlation, CORRELATION", "a wrong repository, REPOSITORY", "a push, EVENT",
			"a self-hosted runner, RUNNER", "a foreign run, RUN", "a head other than workflow_sha, RUN",
			"another workflow path, RUN", "a run GitHub does not know, RUN", "a tampered blob, BLOB", "another ref, REF" })
	void anythingButAGenuineRunIsRefusedWithItsReasonAndNoToken(String flaw, ExchangeReason reason) throws Exception {
		Administrator ha = AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		Dispatched dispatched = handover();
		Map<String, Object> claims = claims(dispatched, RUNNER_ID);
		RSAKey key = KEY;
		Object correlation = dispatched.correlation();
		switch (flaw) {
			case "a forged signature" -> key = TestKeys.foreignJwk(KEY_ID);
			case "a wrong audience" -> claims.put("aud", "sts.amazonaws.com");
			case "an expired token" -> claims.put("exp", Date.from(this.clock.instant().minusSeconds(61)));
			case "an unknown correlation" -> correlation = UUID.randomUUID();
			case "a wrong repository" -> claims.put("repository_id", String.valueOf(COPY_TARGET_ID));
			case "a push" -> claims.put("event_name", "push");
			case "a self-hosted runner" -> claims.put("runner_environment", "self-hosted");
			case "a foreign run" -> GITHUB.runs.get(dispatched.runId()).put("triggering_actor", Map.of("login", "mallory"));
			case "a head other than workflow_sha" -> GITHUB.runs.get(dispatched.runId()).put("head_sha", TAMPERED_HEAD);
			case "another workflow path" -> GITHUB.runs.get(dispatched.runId()).put("path", ".github/workflows/other.yml");
			case "a run GitHub does not know" -> claims.put("run_id", "987654321");
			case "a tampered blob" -> {
				GITHUB.runs.get(dispatched.runId()).put("head_sha", TAMPERED_HEAD);
				claims.put("workflow_sha", TAMPERED_HEAD);
				claims.put("ref", "refs/heads/tampered");
			}
			case "another ref" -> claims.put("ref", "refs/heads/tampered");
			default -> throw new IllegalArgumentException(flaw);
		}
		Map<ExchangeReason, Long> before = this.exchange.unverifiedCounts();

		this.mvc.perform(exchange(sign(claims, key), correlation))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("FORBIDDEN"))
			.andExpect(jsonPath("$.reason").value(reason.name()))
			.andExpect(jsonPath("$.token").doesNotExist());

		ActionsRuns.Correlation after = this.runs.correlation(dispatched.correlation()).orElseThrow();
		assertThat(this.issued.get(dispatched.correlation())).isEmpty();
		awaitListeners();
		if (reason.beforeVerification()) {
			assertThat(after.status()).as("a forged token names nothing reliably").isEqualTo(Status.OPEN);
			assertThat(this.exchange.unverifiedCounts().get(reason)).isEqualTo(before.get(reason) + 1);
			assertThat(audited(dispatched.correlation())).isEmpty();
			assertThat(alerts(ha.accountId())).isEmpty();
		}
		else {
			assertThat(after.status()).isEqualTo(Status.REFUSED);
			assertThat(after.refusal()).isEqualTo(reason);
			assertThat(audited(dispatched.correlation())).containsExactly("github.exchange_refused_" + reason.code());
			assertThat(alerts(ha.accountId())).containsExactly(Map.of("operation", "handover-copy", "reason", reason.code(),
					"correlation", dispatched.correlation().toString()));
			assertThat(this.jdbc.sql("""
					SELECT count(*) FROM operations.external_operation WHERE type = 'notification.email'
					AND payload ->> 'template' = 'github.exchange_refused'
					""").query(Long.class).single()).as("Administrators are told by email too").isOne();
			this.mvc.perform(exchange(sign(claims(dispatched, RUNNER_ID)), dispatched.correlation()))
				.andExpect(jsonPath("$.reason").value("CORRELATION"))
				.andDo(result -> assertThat(this.issued.get(dispatched.correlation())).as("a refused correlation is closed")
					.isEmpty());
		}
	}

	@Test
	void aCopyWhoseHeadMovedAfterTheConfirmationIsRefusedSha() throws Exception {
		String confirmed = GITHUB.head(ORGANIZATION, "copy-target", "main");
		GITHUB.push(ORGANIZATION, "copy-target", "main", "src/moved.txt", "a push after the confirmation\n");
		Dispatched dispatched = this.runs.dispatch(new Dispatch(App.PLATFORM, null, Operation.COPY, COPY_TARGET_ID,
				"vibefix.yml", Map.of("operation", "copy", "sha", confirmed, "branch", "main", "intermediary",
						ORGANIZATION + "/job-1-intermediary", "working", ORGANIZATION + "/job-1-working"),
				Set.of(WORKING_ID, INTERMEDIARY_ID), confirmed));

		assertThat(GITHUB.runs.get(dispatched.runId()).get("display_title")).asString()
			.isEqualTo("vibefix copy " + dispatched.correlation());
		this.mvc.perform(exchange(sign(claims(dispatched, COPY_TARGET_ID)), dispatched.correlation()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.reason").value("SHA"));
		assertThat(this.runs.correlation(dispatched.correlation()).orElseThrow().refusal()).isEqualTo(ExchangeReason.SHA);

		String head = GITHUB.head(ORGANIZATION, "copy-target", "main");
		Dispatched confirmedHead = this.runs.dispatch(new Dispatch(App.PLATFORM, null, Operation.COPY, COPY_TARGET_ID,
				"vibefix.yml", Map.of("operation", "copy", "sha", head), Set.of(WORKING_ID, INTERMEDIARY_ID), head));
		this.mvc.perform(exchange(sign(claims(confirmedHead, COPY_TARGET_ID)), confirmedHead.correlation()))
			.andExpect(status().isOk());
	}

	/**
	 * Story 23.6a: J7's copy dispatched in the Customer's Source Repository through their own Source App installation.
	 * The target and its workflow file are read, the run dispatched and read back with that installation's tokens, each
	 * narrowed to the Source Repository and revoked; the token the run gets is the Platform App's, over the grant's two
	 * platform repositories with exactly the copy's permissions; and the poll reads the run through the installation, so
	 * that token is revoked when the run completes, not at the timeout.
	 */
	@Test
	void aSourceDispatchedCopyGetsAPlatformTokenOverTheGrantAndIsReadBackThroughTheInstallation() throws Exception {
		int from = GITHUB.seen.size();
		int sourceTokens = GITHUB.tokensOf(SOURCE_INSTALLATION).size();
		String confirmed = GITHUB.head(SOURCE_OWNER, SOURCE_NAME, "main");

		Dispatched dispatched = sourceCopy(confirmed);

		Map<String, Object> run = GITHUB.runs.get(dispatched.runId());
		assertThat(dispatched.runId()).isPositive();
		assertThat(run.get("repository_full_name")).isEqualTo(SOURCE);
		assertThat(run.get("display_title")).asString().isEqualTo("vibefix copy " + dispatched.correlation());
		assertThat(run.get("triggering_actor")).as("the Source App's bot dispatched it")
			.isEqualTo(Map.of("login", GitHubStub.SOURCE_SLUG + "[bot]", "type", "Bot"));
		assertThat(this.runs.correlation(dispatched.correlation())).hasValueSatisfying(correlation -> {
			assertThat(correlation.app()).isEqualTo(App.SOURCE);
			assertThat(correlation.repository()).isEqualTo(SOURCE);
			assertThat(correlation.repositoryId()).isEqualTo(SOURCE_ID);
			assertThat(correlation.grant()).containsExactlyInAnyOrder(WORKING_ID, INTERMEDIARY_ID);
			assertThat(correlation.confirmedHeadSha()).isEqualTo(confirmed);
			assertThat(correlation.runHtmlUrl()).isEqualTo("https://github.com/" + SOURCE + "/actions/runs/" + dispatched.runId());
		});
		assertThat(this.jdbc.sql("SELECT source_installation_id FROM github.exchange_correlation WHERE id = :id")
			.param("id", dispatched.correlation()).query(Long.class).single()).isEqualTo(SOURCE_INSTALLATION);
		GitHubStub.Seen dispatch = requests(from, "POST", "/repos/" + SOURCE + "/actions/workflows/vibefix.yml/dispatches")
			.getFirst();
		assertThat(GITHUB.tokensOf(SOURCE_INSTALLATION)).contains(bearer(dispatch));
		assertThat(permissionsOf(bearer(dispatch))).isEqualTo(Map.of("actions", "write", "contents", "read"));
		assertThat(requests(from, "GET", "/repositories/" + SOURCE_ID)).as("the target is read with a Source App token")
			.singleElement()
			.satisfies(request -> {
				assertThat(GITHUB.tokensOf(SOURCE_INSTALLATION)).contains(bearer(request));
				assertThat(longs(GITHUB.grantOf(bearer(request)).path("repository_ids"))).containsExactly(SOURCE_ID);
			});

		MockHttpServletResponse answer = this.mvc.perform(exchange(sign(claims(dispatched, SOURCE_ID)), dispatched.correlation()))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse();

		String token = GitHubHttp.JSON.readTree(answer.getContentAsString()).path("token").asString();
		assertThat(GITHUB.tokensOf(SOURCE_INSTALLATION)).as("not a Source App token").doesNotContain(token);
		GitHubStub.Seen mint = GITHUB.seen.stream()
			.filter(seen -> seen.method().equals("POST") && seen.path().endsWith("/access_tokens"))
			.toList()
			.getLast();
		assertThat(mint.path()).as("minted by the Platform App").isEqualTo("/app/installations/" + GitHubStub.INSTALLATION
				+ "/access_tokens");
		assertThat(longs(mint.body().path("repository_ids"))).containsExactlyInAnyOrder(WORKING_ID, INTERMEDIARY_ID);
		assertThat(permissionsOf(token)).isEqualTo(Map.of("contents", "write", "workflows", "write"));
		assertThat(requests(from, "POST", "/app/installations/" + GitHubStub.INSTALLATION + "/access_tokens"))
			.as("no Platform token names the Customer's repository")
			.isNotEmpty()
			.noneMatch(request -> longs(request.body().path("repository_ids")).contains(SOURCE_ID));
		List<GitHubStub.Seen> readBack = requests(from, "GET", "/repos/" + SOURCE + "/actions/runs/" + dispatched.runId());
		assertThat(readBack).as("the run is read back through the Customer's installation").isNotEmpty()
			.allSatisfy(request -> assertThat(GITHUB.tokensOf(SOURCE_INSTALLATION)).contains(bearer(request)));

		this.revocations.poll();
		assertThat(GITHUB.revokedTokens).as("the run goes on: its token stays").doesNotContain(token);
		GITHUB.completeRun(dispatched.runId(), "success");
		this.revocations.poll();

		assertThat(GITHUB.revokedTokens).as("revoked when the run completes").contains(token);
		assertThat(this.runs.correlation(dispatched.correlation()).orElseThrow().status()).isEqualTo(Status.REVOKED);
		assertThat(requests(from, "GET", "/repos/" + SOURCE + "/")).as("every request in the Source Repository")
			.isNotEmpty()
			.allSatisfy(request -> assertThat(GITHUB.tokensOf(SOURCE_INSTALLATION)).as(request.method() + " " + request.path())
				.contains(bearer(request)));
		List<String> minted = GITHUB.tokensOf(SOURCE_INSTALLATION);
		assertThat(minted.subList(sourceTokens, minted.size())).as("each Source App token").isNotEmpty().allSatisfy(source -> {
			assertThat(longs(GITHUB.grantOf(source).path("repository_ids"))).containsExactly(SOURCE_ID);
			assertThat(permissionsOf(source)).doesNotContainKey("workflows").doesNotContainEntry("contents", "write");
			assertThat(GITHUB.revokedTokens).contains(source);
		});
		awaitListeners();
		assertThat(audited(dispatched.correlation())).containsExactlyInAnyOrder("github.exchange_issued", "github.exchange_revoked");
	}

	/** Story 23.6a: a Source copy whose head moved after the Customer confirmed it is refused {@code sha}, as built. */
	@Test
	void aSourceCopyWhoseHeadMovedAfterTheConfirmationIsRefusedSha() throws Exception {
		String confirmed = GITHUB.head(SOURCE_OWNER, SOURCE_NAME, "main");
		GITHUB.push(SOURCE_OWNER, SOURCE_NAME, "main", "src/after-" + UUID.randomUUID() + ".txt", "a push after the confirmation\n");
		Dispatched moved = sourceCopy(confirmed);

		this.mvc.perform(exchange(sign(claims(moved, SOURCE_ID)), moved.correlation()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.reason").value("SHA"));
		assertThat(this.runs.correlation(moved.correlation()).orElseThrow().refusal()).isEqualTo(ExchangeReason.SHA);
		assertThat(this.issued.get(moved.correlation())).isEmpty();

		Dispatched head = sourceCopy(GITHUB.head(SOURCE_OWNER, SOURCE_NAME, "main"));
		this.mvc.perform(exchange(sign(claims(head, SOURCE_ID)), head.correlation())).andExpect(status().isOk());
	}

	/**
	 * Story 23.6a: the triggering actor must be the dispatching App's bot. A Source run shown as triggered by the Platform
	 * App's bot is refused {@code run}, audited; so is a runner run shown as triggered by the Source App's bot.
	 */
	@Test
	void aRunTriggeredByAnotherAppsBotIsRefusedRun() throws Exception {
		Dispatched source = sourceCopy(GITHUB.head(SOURCE_OWNER, SOURCE_NAME, "main"));
		GITHUB.runs.get(source.runId()).put("triggering_actor", Map.of("login", "vibefix-dev-platform[bot]"));
		Dispatched runner = handover();
		GITHUB.runs.get(runner.runId()).put("triggering_actor", Map.of("login", GitHubStub.SOURCE_SLUG + "[bot]"));

		this.mvc.perform(exchange(sign(claims(source, SOURCE_ID)), source.correlation()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.reason").value("RUN"));
		this.mvc.perform(exchange(sign(claims(runner, RUNNER_ID)), runner.correlation()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.reason").value("RUN"));

		assertThat(this.runs.correlation(source.correlation()).orElseThrow().refusal()).isEqualTo(ExchangeReason.RUN);
		assertThat(this.issued.get(source.correlation())).isEmpty();
		assertThat(this.issued.get(runner.correlation())).isEmpty();
		awaitListeners();
		assertThat(audited(source.correlation())).containsExactly("github.exchange_refused_run");
	}

	/**
	 * Story 23.6a: when the exchange verifies another run under the correlation than the one found at the dispatch, the
	 * page recorded for that first run goes with it.
	 */
	@Test
	void anExchangeForAnotherRunForgetsThePageOfTheRunFoundBefore() throws Exception {
		Dispatched dispatched = handover();
		assertThat(this.runs.correlation(dispatched.correlation()).orElseThrow().runHtmlUrl()).isNotNull();
		Map<String, Object> other = new LinkedHashMap<>(GITHUB.runs.get(dispatched.runId()));
		other.put("id", 987_655L);
		GITHUB.runs.put(987_655L, other);
		Map<String, Object> claims = claims(dispatched, RUNNER_ID);
		claims.put("run_id", "987655");

		this.mvc.perform(exchange(sign(claims), dispatched.correlation())).andExpect(status().isOk());

		assertThat(this.runs.correlation(dispatched.correlation())).hasValueSatisfying(correlation -> {
			assertThat(correlation.runId()).isEqualTo(987_655L);
			assertThat(correlation.runHtmlUrl()).isNull();
		});
	}

	/**
	 * Story 23.6a: a Source dispatch GitHub answers 204 finds its run by its title with a lookup token of its own from
	 * the Customer's installation (Actions and Contents read), revoked, and records the run and its page.
	 */
	@Test
	void aSourceRunGitHubDoesNotNameIsFoundThroughTheInstallationAndRecorded() throws Exception {
		GITHUB.runDetails = false;
		int from = GITHUB.seen.size();

		Dispatched dispatched = sourceCopy(GITHUB.head(SOURCE_OWNER, SOURCE_NAME, "main"));

		assertThat(dispatched.runId()).isPositive();
		assertThat(this.runs.correlation(dispatched.correlation())).hasValueSatisfying(correlation -> {
			assertThat(correlation.runId()).isEqualTo(dispatched.runId());
			assertThat(correlation.runHtmlUrl()).isEqualTo("https://github.com/" + SOURCE + "/actions/runs/" + dispatched.runId());
		});
		assertThat(requests(from, "GET", "/repos/" + SOURCE + "/actions/workflows/vibefix.yml/runs")).isNotEmpty()
			.allSatisfy(request -> {
				assertThat(GITHUB.tokensOf(SOURCE_INSTALLATION)).as("a Source App token").contains(bearer(request));
				assertThat(permissionsOf(bearer(request))).isEqualTo(Map.of("actions", "read", "contents", "read"));
				assertThat(GITHUB.revokedTokens).contains(bearer(request));
			});
	}

	/**
	 * Story 23.6a: a dispatch GitHub accepted whose lookup then fails does not fail: its run is live and its correlation
	 * open, so a retry would start a second run. The run read finds it later.
	 */
	@Test
	void aLookupThatFailsAfterTheDispatchLeavesTheRunToTheRunRead() throws Exception {
		GITHUB.runDetails = false;
		GITHUB.unavailable.add("GET /repos/" + SOURCE + "/actions/workflows/vibefix.yml/runs");

		Dispatched dispatched = sourceCopy(GITHUB.head(SOURCE_OWNER, SOURCE_NAME, "main"));

		assertThat(dispatched.runId()).isZero();
		assertThat(GITHUB.runs.values()).anyMatch(run -> String.valueOf(run.get("display_title"))
			.contains(dispatched.correlation().toString()));
		assertThat(this.runs.correlation(dispatched.correlation()).orElseThrow().status()).isEqualTo(Status.OPEN);
		GITHUB.unavailable.clear();
		assertThat(this.runs.run(dispatched.correlation())).hasValueSatisfying(run -> assertThat(run.id()).isPositive());
	}

	/**
	 * Story 23.6a: when the Customer uninstalls the Source App mid-run, GitHub refuses its read-back token (404): no fault
	 * of the run, so the exchange answers 502 as for GitHub's outage, refuses nothing and alerts nobody.
	 */
	@Test
	void aSourceAppUninstalledMidRunAnswers502AndRefusesNothing() throws Exception {
		Administrator ha = AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		Dispatched dispatched = sourceCopy(GITHUB.head(SOURCE_OWNER, SOURCE_NAME, "main"));
		GitHubStub.MergeInstallation installed = GITHUB.mergeInstallations.remove(SOURCE_INSTALLATION);
		try {
			this.mvc.perform(exchange(sign(claims(dispatched, SOURCE_ID)), dispatched.correlation()))
				.andExpect(status().isBadGateway());
		}
		finally {
			GITHUB.mergeInstallations.put(SOURCE_INSTALLATION, installed);
		}

		ActionsRuns.Correlation after = this.runs.correlation(dispatched.correlation()).orElseThrow();
		assertThat(after.status()).isEqualTo(Status.OPEN);
		assertThat(after.refusal()).isNull();
		assertThat(this.issued.get(dispatched.correlation())).isEmpty();
		awaitListeners();
		assertThat(audited(dispatched.correlation())).isEmpty();
		assertThat(alerts(ha.accountId())).isEmpty();
	}

	/**
	 * Story 23.6a: correlations are deleted 30 days after their expiry [ASSUMPTION], since a Source App row names the
	 * Customer's repository and links its run; one whose token is still out is kept.
	 */
	@Test
	void aCorrelationIsDeletedThirtyDaysAfterItsExpiryUnlessItsTokenIsOut() throws Exception {
		Dispatched unused = sourceCopy(GITHUB.head(SOURCE_OWNER, SOURCE_NAME, "main"));
		Dispatched out = handover();
		this.mvc.perform(exchange(sign(claims(out, RUNNER_ID)), out.correlation())).andExpect(status().isOk());
		// The token stays out: its revocation keeps failing.
		GITHUB.revocationStatus = 500;

		this.clock.advance(GitHubActionsRuns.CORRELATION_LIFETIME.plus(ExchangeRevocations.RETENTION).minusMinutes(1));
		this.revocations.poll();
		assertThat(this.runs.correlation(unused.correlation()).orElseThrow().status()).isEqualTo(Status.EXPIRED);

		this.clock.advance(Duration.ofMinutes(1));
		this.revocations.poll();
		assertThat(this.runs.correlation(unused.correlation())).as("30 days past its expiry").isEmpty();
		assertThat(this.runs.correlation(out.correlation())).as("its token is still out").isPresent();
	}

	/**
	 * Story 23.6a: {@link ActionsRuns#run} reads the run's first attempt through its dispatching App as it goes, queued,
	 * in progress and completed, and records its page and its conclusion on the correlation; once recorded, the run is
	 * answered from the correlation with no GitHub call.
	 */
	@Test
	void theRunReadShowsTheRunAsItGoesAndRecordsItsPageAndConclusion() throws Exception {
		Dispatched dispatched = sourceCopy(GITHUB.head(SOURCE_OWNER, SOURCE_NAME, "main"));
		String page = "https://github.com/" + SOURCE + "/actions/runs/" + dispatched.runId();
		int from = GITHUB.seen.size();

		assertThat(this.runs.run(dispatched.correlation())).hasValue(
				new ActionsRuns.Run(dispatched.runId(), page, ActionsRuns.RunStatus.QUEUED, null, null));
		GITHUB.startRun(dispatched.runId());
		assertThat(this.runs.run(dispatched.correlation())).hasValue(
				new ActionsRuns.Run(dispatched.runId(), page, ActionsRuns.RunStatus.IN_PROGRESS, null, null));
		assertThat(this.runs.correlation(dispatched.correlation()).orElseThrow().runConclusion()).isNull();
		GITHUB.completeRun(dispatched.runId(), "success");
		assertThat(this.runs.run(dispatched.correlation())).hasValue(
				new ActionsRuns.Run(dispatched.runId(), page, ActionsRuns.RunStatus.COMPLETED, "success", null));

		assertThat(this.runs.correlation(dispatched.correlation())).hasValueSatisfying(correlation -> {
			assertThat(correlation.runHtmlUrl()).isEqualTo(page);
			assertThat(correlation.runConclusion()).isEqualTo("success");
		});
		assertThat(requests(from, "GET", "/repos/" + SOURCE + "/actions/runs/")).hasSize(3).allSatisfy(request -> {
			assertThat(request.path()).as("the first attempt")
				.isEqualTo("/repos/" + SOURCE + "/actions/runs/" + dispatched.runId() + "/attempts/1");
			assertThat(GITHUB.tokensOf(SOURCE_INSTALLATION)).as("read through the Customer's installation")
				.contains(bearer(request));
			assertThat(permissionsOf(bearer(request))).isEqualTo(Map.of("actions", "read", "contents", "read"));
			assertThat(GITHUB.revokedTokens).contains(bearer(request));
		});

		int seen = GITHUB.seen.size();
		assertThat(this.runs.run(dispatched.correlation())).hasValue(
				new ActionsRuns.Run(dispatched.runId(), page, ActionsRuns.RunStatus.COMPLETED, "success", null));
		assertThat(GITHUB.seen).as("answered from the correlation").hasSize(seen);
		assertThat(this.runs.run(UUID.randomUUID())).as("no such correlation").isEmpty();
	}

	/**
	 * Story 23.6a: a Customer's re-run starts the run's latest attempt again; vibefix reads the first attempt, so its
	 * answer for that completed attempt does not change, before or after its conclusion is recorded.
	 */
	@Test
	void aCustomersRerunChangesNothingTheRunReadAnswers() throws Exception {
		Dispatched dispatched = sourceCopy(GITHUB.head(SOURCE_OWNER, SOURCE_NAME, "main"));
		GITHUB.completeRun(dispatched.runId(), "success");
		GITHUB.rerun(dispatched.runId());
		assertThat(GITHUB.runs.get(dispatched.runId())).containsEntry("status", "in_progress").containsEntry("run_attempt", 2);
		ActionsRuns.Run completed = new ActionsRuns.Run(dispatched.runId(),
				"https://github.com/" + SOURCE + "/actions/runs/" + dispatched.runId(), ActionsRuns.RunStatus.COMPLETED,
				"success", null);

		assertThat(this.runs.run(dispatched.correlation())).as("read from GitHub").hasValue(completed);
		GITHUB.completeRun(dispatched.runId(), "failure");
		assertThat(this.runs.run(dispatched.correlation())).as("answered from the correlation").hasValue(completed);
		assertThat(this.runs.correlation(dispatched.correlation()).orElseThrow().runConclusion()).isEqualTo("success");
	}

	/**
	 * Story 23.6a: GitHub refusing the run read is a {@code GitHubException}; a failed run whose jobs cannot be read
	 * still records its conclusion, with no marker.
	 */
	@Test
	void aRunReadGitHubFailsThrowsAndUnreadJobsLeaveTheFailureUnmarked() throws Exception {
		Dispatched unread = sourceCopy(GITHUB.head(SOURCE_OWNER, SOURCE_NAME, "main"));
		GITHUB.unavailable.add("GET /repos/" + SOURCE + "/actions/runs/" + unread.runId());
		assertThatThrownBy(() -> this.runs.run(unread.correlation())).isInstanceOfSatisfying(
				vn.vibefix.github.GitHubException.class, refused -> assertThat(refused.status()).isEqualTo(502));
		GITHUB.unavailable.clear();

		Dispatched stopped = sourceCopy(GITHUB.head(SOURCE_OWNER, SOURCE_NAME, "main"));
		GITHUB.failBeforeAnyJob(stopped.runId());
		GITHUB.unavailable.add("GET /repos/" + SOURCE + "/actions/runs/" + stopped.runId() + "/attempts/1/jobs");

		assertThat(this.runs.run(stopped.correlation())).hasValueSatisfying(run -> {
			assertThat(run.conclusion()).isEqualTo("failure");
			assertThat(run.marker()).as("its jobs could not be read").isNull();
		});
		assertThat(this.runs.correlation(stopped.correlation()).orElseThrow().runConclusion()).isEqualTo("failure");
	}

	/**
	 * Story 23.6a: a failed run is marked {@code ACTIONS_QUOTA} only when it lists jobs and none of them started (no
	 * steps, no runner), as GitHub ends a run its account's Actions minutes stopped; a failure on a runner, a failure that
	 * lists no job, a cancellation and a {@code startup_failure} are not marked.
	 */
	@Test
	void aFailedRunIsMarkedActionsQuotaOnlyWhenNoneOfItsJobsStarted() throws Exception {
		Dispatched failed = handover();
		GITHUB.completeRun(failed.runId(), "failure");
		Dispatched stopped = sourceCopy(GITHUB.head(SOURCE_OWNER, SOURCE_NAME, "main"));
		GITHUB.failBeforeAnyJob(stopped.runId());
		Dispatched cancelled = handover();
		GITHUB.failBeforeAnyJob(cancelled.runId());
		GITHUB.runs.get(cancelled.runId()).put("conclusion", "cancelled");
		Dispatched jobless = handover();
		GITHUB.runJobs.get(jobless.runId()).clear();
		GITHUB.completeRun(jobless.runId(), "failure");
		Dispatched startup = handover();
		GITHUB.failBeforeAnyJob(startup.runId());
		GITHUB.runs.get(startup.runId()).put("conclusion", "startup_failure");

		assertThat(this.runs.run(jobless.correlation())).hasValueSatisfying(run -> {
			assertThat(run.conclusion()).isEqualTo("failure");
			assertThat(run.marker()).as("a failure that lists no job").isNull();
		});
		assertThat(this.runs.run(startup.correlation())).hasValueSatisfying(run -> {
			assertThat(run.conclusion()).isEqualTo("startup_failure");
			assertThat(run.marker()).isNull();
		});

		assertThat(this.runs.run(failed.correlation())).hasValueSatisfying(run -> {
			assertThat(run.status()).isEqualTo(ActionsRuns.RunStatus.COMPLETED);
			assertThat(run.conclusion()).isEqualTo("failure");
			assertThat(run.marker()).as("its job ran on a runner").isNull();
		});
		assertThat(this.runs.run(stopped.correlation())).hasValueSatisfying(run -> {
			assertThat(run.conclusion()).isEqualTo("failure");
			assertThat(run.marker()).isEqualTo(ActionsRuns.RunMarker.ACTIONS_QUOTA);
		});
		assertThat(this.runs.run(cancelled.correlation())).hasValueSatisfying(run -> {
			assertThat(run.conclusion()).isEqualTo("cancelled");
			assertThat(run.marker()).as("only a failure is read for the quota").isNull();
		});
		assertThat(this.jdbc.sql("SELECT run_conclusion || ':' || run_marker FROM github.exchange_correlation WHERE id = :id")
			.param("id", stopped.correlation()).query(String.class).single()).isEqualTo("failure:ACTIONS_QUOTA");
	}

	/**
	 * Story 23.6a: a run that has not shown under its correlation reads empty; once it shows, the read finds it by its
	 * title among the runs dispatched since the correlation, triggered by the dispatching App's bot, and records it. A
	 * run someone dispatched by hand with the correlation copied into its title is not it.
	 */
	@Test
	void aRunNotShownYetReadsEmptyUntilItShowsUnderItsCorrelation() {
		GITHUB.runDetails = false;
		GITHUB.runsHidden = true;
		Dispatched dispatched = quick().dispatch(dispatch(RUNNER_ID));
		assertThat(dispatched.runId()).isZero();

		assertThat(this.runs.run(dispatched.correlation())).isEmpty();
		assertThat(this.runs.correlation(dispatched.correlation()).orElseThrow().runId()).isNull();

		GITHUB.runsHidden = false;
		// A later run of the same workflow, by hand, its title copying the correlation: listed first, newest first.
		Map<String, Object> copied = new LinkedHashMap<>(GITHUB.runs.values()
			.stream()
			.filter(shown -> String.valueOf(shown.get("display_title")).contains(dispatched.correlation().toString()))
			.findFirst()
			.orElseThrow());
		copied.put("id", 987_654L);
		copied.put("triggering_actor", Map.of("login", "lan-customer"));
		GITHUB.runs.put(987_654L, copied);
		int listed = GITHUB.runListQueries.size();
		ActionsRuns.Run run = this.runs.run(dispatched.correlation()).orElseThrow();

		assertThat(run.id()).as("not the run dispatched by hand").isNotEqualTo(987_654L);
		assertThat(GITHUB.runListQueries.subList(listed, GITHUB.runListQueries.size())).singleElement().satisfies(query -> {
			assertThat(query).contains("per_page=100", "event=workflow_dispatch");
			assertThat(query).contains("created=>=" + java.time.format.DateTimeFormatter.ISO_INSTANT.format(
					this.runs.correlation(dispatched.correlation()).orElseThrow().createdAt()
						.minus(GitHubActionsRuns.CLOCK_SKEW)
						.truncatedTo(java.time.temporal.ChronoUnit.SECONDS)));
		});
		assertThat(GITHUB.runs.get(run.id()).get("display_title")).asString().contains(dispatched.correlation().toString());
		assertThat(run.status()).isEqualTo(ActionsRuns.RunStatus.QUEUED);
		assertThat(run.htmlUrl()).isEqualTo("https://github.com/" + ORGANIZATION + "/" + RUNNER + "/actions/runs/" + run.id());
		assertThat(this.runs.correlation(dispatched.correlation())).hasValueSatisfying(correlation -> {
			assertThat(correlation.runId()).isEqualTo(run.id());
			assertThat(correlation.runHtmlUrl()).isEqualTo(run.htmlUrl());
		});
	}

	@Test
	void aReplayIsRefusedAndAlertedButLeavesTheCorrelationExchanged() throws Exception {
		Administrator ha = AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		Dispatched dispatched = handover();
		String idToken = sign(claims(dispatched, RUNNER_ID));
		this.mvc.perform(exchange(idToken, dispatched.correlation())).andExpect(status().isOk());
		int minted = GITHUB.tokens.size();

		this.mvc.perform(exchange(idToken, dispatched.correlation()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.reason").value("REPLAY"))
			.andExpect(jsonPath("$.token").doesNotExist());

		assertThat(this.runs.correlation(dispatched.correlation()).orElseThrow().status()).isEqualTo(Status.EXCHANGED);
		assertThat(GITHUB.tokens).as("no second token: a replay is refused before anything is read").hasSize(minted);
		awaitListeners();
		assertThat(audited(dispatched.correlation())).containsExactlyInAnyOrder("github.exchange_issued",
				"github.exchange_refused_replay");
		assertThat(alerts(ha.accountId())).containsExactly(Map.of("operation", "handover-copy", "reason", "replay",
				"correlation", dispatched.correlation().toString()));
	}

	/** The run's title must name the correlation: run A's ID token, posted for open correlation B, is not B's run. */
	@Test
	void anotherCorrelationsRunIsRefusedRun() throws Exception {
		Dispatched a = handover();
		Dispatched b = handover();

		this.mvc.perform(exchange(sign(claims(a, RUNNER_ID)), b.correlation()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.reason").value("RUN"));

		assertThat(this.runs.correlation(b.correlation())).hasValueSatisfying(correlation -> {
			assertThat(correlation.status()).isEqualTo(Status.REFUSED);
			assertThat(correlation.refusal()).isEqualTo(ExchangeReason.RUN);
		});
		assertThat(this.runs.correlation(a.correlation()).orElseThrow().status()).isEqualTo(Status.OPEN);
	}

	/** GitHub's server error to the issue-time mint is no refusal: 502, the row stays open, and the run asks again. */
	@Test
	void aMintGitHubFailsAnswers502AndTheRunMayAskAgain() throws Exception {
		Dispatched dispatched = handover();
		String idToken = sign(claims(dispatched, RUNNER_ID));
		// The issue-time mint is the one asking for workflows write; the read-back token reads only.
		GITHUB.mintRefused = body -> body.path("permissions").path("workflows").asString("").equals("write");

		this.mvc.perform(exchange(idToken, dispatched.correlation())).andExpect(status().isBadGateway());

		assertThat(this.runs.correlation(dispatched.correlation()).orElseThrow().status()).isEqualTo(Status.OPEN);
		assertThat(this.issued.get(dispatched.correlation())).isEmpty();

		GITHUB.mintRefused = body -> false;
		this.mvc.perform(exchange(idToken, dispatched.correlation())).andExpect(status().isOk());
		assertThat(this.runs.correlation(dispatched.correlation()).orElseThrow().status()).isEqualTo(Status.EXCHANGED);
		assertThat(this.issued.get(dispatched.correlation())).isPresent();
	}

	/** GitHub's server error to the run read-back is no refusal: 502, the row stays open, and nothing is published. */
	@Test
	void aRunReadBackGitHubFailsAnswers502AndRefusesNothing() throws Exception {
		Dispatched dispatched = handover();
		GITHUB.unavailable.add("GET /repos/" + ORGANIZATION + "/" + RUNNER + "/actions/runs/");

		this.mvc.perform(exchange(sign(claims(dispatched, RUNNER_ID)), dispatched.correlation()))
			.andExpect(status().isBadGateway());

		ActionsRuns.Correlation after = this.runs.correlation(dispatched.correlation()).orElseThrow();
		assertThat(after.status()).isEqualTo(Status.OPEN);
		assertThat(after.refusal()).isNull();
		awaitListeners();
		assertThat(audited(dispatched.correlation())).as("no event was published").isEmpty();
		assertThat(this.jdbc.sql("SELECT count(*) FROM notification.notification WHERE type = 'github.exchange_refused'")
			.query(Long.class)
			.single()).isZero();
	}

	/** A dispatch GitHub answers 204 finds its run by the title, which holds the correlation (AD-46). */
	@Test
	void aRunGitHubDoesNotNameIsFoundByItsTitle() {
		GITHUB.runDetails = false;

		Dispatched dispatched = handover();

		assertThat(dispatched.runId()).isPositive();
		assertThat(GITHUB.runs.get(dispatched.runId()).get("display_title")).asString()
			.contains(dispatched.correlation().toString());
		assertThat(this.runs.correlation(dispatched.correlation()).orElseThrow().runId()).isEqualTo(dispatched.runId());
	}

	/** A run that never shows within the find window leaves the dispatch without one (run ID 0). */
	@Test
	void aRunThatNeverShowsLeavesTheDispatchWithoutOne() {
		GITHUB.runDetails = false;
		GITHUB.runsHidden = true;
		Dispatched dispatched = quick().dispatch(dispatch(RUNNER_ID));

		assertThat(dispatched.runId()).isZero();
		assertThat(this.runs.correlation(dispatched.correlation())).hasValueSatisfying(correlation -> {
			assertThat(correlation.status()).isEqualTo(Status.OPEN);
			assertThat(correlation.runId()).isNull();
		});
	}

	/** GitHub's 401 to a revocation means the token is gone already: the row is REVOKED and the token forgotten. */
	@Test
	void aRevocationAnswered401CountsAsRevoked() throws Exception {
		Dispatched dispatched = handover();
		this.mvc.perform(exchange(sign(claims(dispatched, RUNNER_ID)), dispatched.correlation())).andExpect(status().isOk());
		GITHUB.completeRun(dispatched.runId(), "success");
		GITHUB.revocationStatus = 401;

		this.revocations.poll();

		assertThat(this.runs.correlation(dispatched.correlation()).orElseThrow().status()).isEqualTo(Status.REVOKED);
		assertThat(this.issued.get(dispatched.correlation())).isEmpty();
	}

	@Test
	void aWorkflowThatIsNotAcceptedIsNeverDispatched() {
		int seen = GITHUB.seen.size();

		assertThatThrownBy(() -> this.runs.dispatch(dispatch(TAMPERED_RUNNER_ID))).isInstanceOfSatisfying(
				WorkflowRefused.class, refused -> assertThat(refused.refusal()).isEqualTo(WorkflowRefusal.WORKFLOW_MISMATCH));
		assertThatThrownBy(() -> this.runs.dispatch(dispatch(COPY_TARGET_ID))).as("it holds vibefix.yml, no s6-copy.yml")
			.isInstanceOfSatisfying(WorkflowRefused.class,
					refused -> assertThat(refused.refusal()).isEqualTo(WorkflowRefusal.WORKFLOW_MISSING));
		assertThatThrownBy(() -> this.runs.dispatch(dispatch(WORKING_ID))).as("a grant never names the run's own repository")
			.isInstanceOf(IllegalArgumentException.class);

		assertThat(GITHUB.seen.subList(seen, GITHUB.seen.size())).noneMatch(request -> request.path().endsWith("/dispatches"));
		assertThat(this.jdbc.sql("SELECT count(*) FROM github.exchange_correlation").query(Long.class).single()).isZero();
	}

	@Test
	void aTokenIsRevokedFifteenMinutesAfterIssueWhileItsRunGoesOn() throws Exception {
		Dispatched dispatched = handover();
		this.mvc.perform(exchange(sign(claims(dispatched, RUNNER_ID)), dispatched.correlation())).andExpect(status().isOk());

		this.clock.advance(Duration.ofMinutes(14));
		this.revocations.poll();
		assertThat(this.runs.correlation(dispatched.correlation()).orElseThrow().status()).isEqualTo(Status.EXCHANGED);

		this.clock.advance(Duration.ofMinutes(1));
		this.revocations.poll();
		assertThat(this.runs.correlation(dispatched.correlation()).orElseThrow().status()).isEqualTo(Status.REVOKED);
	}

	@Test
	void aRevocationGitHubDoesNotConfirmIsTriedAgainNextPoll() throws Exception {
		Dispatched dispatched = handover();
		this.mvc.perform(exchange(sign(claims(dispatched, RUNNER_ID)), dispatched.correlation())).andExpect(status().isOk());
		GITHUB.completeRun(dispatched.runId(), "failure");
		GITHUB.revocationStatus = 500;

		this.revocations.poll();
		assertThat(this.runs.correlation(dispatched.correlation()).orElseThrow().status()).isEqualTo(Status.EXCHANGED);
		assertThat(this.issued.get(dispatched.correlation())).isPresent();

		GITHUB.revocationStatus = 204;
		this.revocations.poll();
		assertThat(this.runs.correlation(dispatched.correlation()).orElseThrow().status()).isEqualTo(Status.REVOKED);
	}

	@Test
	void aRestartLeavesTheTokenRevokeUnknownAndAlertsOnce() throws Exception {
		Administrator ha = AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		Dispatched dispatched = handover();
		this.mvc.perform(exchange(sign(claims(dispatched, RUNNER_ID)), dispatched.correlation())).andExpect(status().isOk());

		this.issued.forgetAll();
		this.revocations.poll();
		this.revocations.poll();

		assertThat(this.runs.correlation(dispatched.correlation()).orElseThrow().status()).isEqualTo(Status.REVOKE_UNKNOWN);
		awaitListeners();
		assertThat(audited(dispatched.correlation())).containsExactlyInAnyOrder("github.exchange_issued",
				"github.exchange_revoke_unknown");
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM notification.notification WHERE type = 'github.exchange_revoke_unknown' AND account_id = :id
				""").param("id", ha.accountId()).query(Long.class).single()).isOne();
	}

	@Test
	void aCorrelationNeverExchangedExpires() throws Exception {
		Dispatched dispatched = handover();

		this.clock.advance(GitHubActionsRuns.CORRELATION_LIFETIME);
		this.mvc.perform(exchange(sign(claims(dispatched, RUNNER_ID)), dispatched.correlation()))
			.andExpect(jsonPath("$.reason").value("CORRELATION"));
		this.revocations.poll();

		assertThat(this.runs.correlation(dispatched.correlation()).orElseThrow().status()).isEqualTo(Status.EXPIRED);
	}

	@Test
	void aFloodIsRateLimitedPerAddressAndPerCorrelation() throws Exception {
		for (int call = 1; call <= ExchangeRateLimiter.PER_ADDRESS; call++) {
			this.mvc.perform(exchange("not-a-jwt", UUID.randomUUID(), "198.51.100.7")).andExpect(status().isForbidden());
		}
		this.mvc.perform(exchange("not-a-jwt", UUID.randomUUID(), "198.51.100.7"))
			.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("RATE_LIMITED"))
			.andExpect(header().exists("Retry-After"));

		// Forged posts naming a correlation never count against it, so they cannot lock its genuine run out.
		UUID correlation = UUID.randomUUID();
		for (int attempt = 1; attempt <= 2 * ExchangeRateLimiter.PER_CORRELATION; attempt++) {
			this.mvc.perform(exchange("not-a-jwt", correlation, "192.0.2." + attempt))
				.andExpect(jsonPath("$.reason").value("SIGNATURE"));
		}
		// Verified ID tokens do: the sixth on one correlation is refused.
		String verified = sign(verifiedClaims());
		for (int attempt = 1; attempt <= ExchangeRateLimiter.PER_CORRELATION; attempt++) {
			this.mvc.perform(exchange(verified, correlation, "203.0.113." + attempt))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.reason").value("CORRELATION"));
		}
		this.mvc.perform(exchange(verified, correlation, "203.0.113.99"))
			.andExpect(status().isTooManyRequests())
			.andExpect(header().exists("Retry-After"));
	}

	/** A token GitHub Actions would sign: issuer, audience and times, naming no run. */
	private Map<String, Object> verifiedClaims() {
		Instant now = this.clock.instant();
		Map<String, Object> claims = new LinkedHashMap<>();
		claims.put("iss", GITHUB.apiUrl().toString());
		claims.put("aud", ActionsIdTokens.AUDIENCE);
		claims.put("iat", Date.from(now.minusSeconds(5)));
		claims.put("nbf", Date.from(now.minusSeconds(5)));
		claims.put("exp", Date.from(now.plus(Duration.ofMinutes(5))));
		return claims;
	}

	@Test
	void aBodyOver16KbIsRefusedUnread() throws Exception {
		this.mvc.perform(post(ActionsExchangeController.PATH).contentType(MediaType.APPLICATION_JSON)
			.content("{\"id_token\":\"" + "x".repeat(17 * 1024) + "\",\"correlation\":\"" + UUID.randomUUID() + "\"}"))
			.andExpect(status().is(413));
	}

	@Test
	void theDevPortServesTheExchangeAlone() throws Exception {
		this.mvc.perform(get("/api/v1/environment").with(port(GitHubProperties.Exchange.DEFAULT_DEV_PORT)))
			.andExpect(status().isNotFound());
		this.mvc.perform(get(ActionsExchangeController.PATH).with(port(GitHubProperties.Exchange.DEFAULT_DEV_PORT)))
			.andExpect(status().isNotFound());
		this.mvc.perform(exchange("not-a-jwt", UUID.randomUUID()).with(port(GitHubProperties.Exchange.DEFAULT_DEV_PORT)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.reason").value("SIGNATURE"));
		this.mvc.perform(get("/api/v1/environment")).andExpect(status().isOk());
	}

	@Test
	void neitherTheIdTokenNorTheTokenReachesALogOrARefusal(CapturedOutput output) throws Exception {
		// GitHub's stateless installation tokens, ghs_<App ID>_<JWT>, built at run time as fake values.
		GITHUB.tokenFormat = n -> String.join("", "ghs", "_", "123456", "_", "eyJ" + "hbGciOiJSUzI1NiJ9", ".",
				"eyJ" + "pc3MiOi" + "C".repeat(120) + n, ".", "Zm9v-YmFy_" + "s".repeat(40));
		Dispatched dispatched = handover();
		String idToken = sign(claims(dispatched, RUNNER_ID));
		String forged = sign(claims(dispatched, RUNNER_ID), TestKeys.foreignJwk(KEY_ID));

		String issuedBody = this.mvc.perform(exchange(idToken, dispatched.correlation()))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		String token = GitHubHttp.JSON.readTree(issuedBody).path("token").asString();
		String replay = this.mvc.perform(exchange(idToken, dispatched.correlation()))
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
		String refused = this.mvc.perform(exchange(forged, dispatched.correlation()))
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
		GITHUB.completeRun(dispatched.runId(), "success");
		this.revocations.poll();
		awaitListeners();

		assertThat(token).startsWith("ghs_123456_eyJ");
		assertThat(GITHUB.revokedTokens).contains(token);
		for (String secret : List.of(idToken, forged, token, idToken.substring(idToken.lastIndexOf('.') + 1),
				token.substring(token.lastIndexOf('.') + 1))) {
			assertThat(output.getAll()).as("the logs").doesNotContain(secret);
			assertThat(replay + refused).as("the refusals").doesNotContain(secret);
			assertThat(this.jdbc.sql("SELECT count(*) FROM notification.notification WHERE params::text LIKE :secret")
				.param("secret", "%" + secret + "%")
				.query(Long.class)
				.single()).isZero();
			assertThat(this.jdbc.sql("SELECT count(*) FROM github.exchange_correlation c WHERE c::text LIKE :secret")
				.param("secret", "%" + secret + "%")
				.query(Long.class)
				.single()).isZero();
		}
		assertThat(output.getAll()).contains("The Actions exchange issued correlation " + dispatched.correlation());
	}

	// Helpers.

	private Dispatched handover() {
		return this.runs.dispatch(dispatch(RUNNER_ID));
	}

	private static Dispatch dispatch(long target) {
		Map<String, String> inputs = new LinkedHashMap<>();
		inputs.put("source_repository", ORGANIZATION + "/job-1-working");
		inputs.put("source_branch", "main");
		inputs.put("sha", "1f".repeat(20));
		inputs.put("target_repository", ORGANIZATION + "/job-1-intermediary");
		inputs.put("target_branch", "handover/1");
		return new Dispatch(App.PLATFORM, null, Operation.HANDOVER_COPY, target, "s6-copy.yml", inputs,
				Set.of(WORKING_ID, INTERMEDIARY_ID), null);
	}

	/** J7's copy of the Customer's head, dispatched in their Source Repository through their Source App (Story 23.6a). */
	private Dispatched sourceCopy(String confirmed) {
		return this.runs.dispatch(sourceCopyDispatch(confirmed));
	}

	private static Dispatch sourceCopyDispatch(String confirmed) {
		return new Dispatch(App.SOURCE, SOURCE_INSTALLATION, Operation.COPY, SOURCE_ID, "vibefix.yml",
				Map.of("operation", "copy", "sha", confirmed, "branch", "main", "intermediary",
						ORGANIZATION + "/job-1-intermediary", "working", ORGANIZATION + "/job-1-working"),
				Set.of(WORKING_ID, INTERMEDIARY_ID), confirmed);
	}

	/** The dispatch with a find window of 30 ms, for a run that does not show. */
	private GitHubActionsRuns quick() {
		return new GitHubActionsRuns(this.apps, this.dispatching, this.correlations, this.transactionManager,
				this.environment, this.clock, new GitHubActionsRuns.Waits(Duration.ofMillis(10), Duration.ofMillis(30)));
	}

	/** The requests GitHub received since the {@code from}-th, with this method and a path starting with {@code pathPrefix}. */
	private static List<GitHubStub.Seen> requests(int from, String method, String pathPrefix) {
		List<GitHubStub.Seen> seen = List.copyOf(GITHUB.seen);
		return seen.subList(from, seen.size())
			.stream()
			.filter(request -> request.method().equals(method) && request.path().startsWith(pathPrefix))
			.toList();
	}

	private static String bearer(GitHubStub.Seen request) {
		return request.authorization().replaceFirst("^Bearer ", "");
	}

	/** The permissions a token was minted with. */
	private static Map<String, String> permissionsOf(String token) {
		Map<String, String> permissions = new LinkedHashMap<>();
		GITHUB.grantOf(token).path("permissions").properties()
			.forEach(entry -> permissions.put(entry.getKey(), entry.getValue().asString()));
		return permissions;
	}

	/** The claims GitHub Actions signs for the dispatched run, as the stub shows it. */
	private Map<String, Object> claims(Dispatched dispatched, long repositoryId) {
		Map<String, Object> run = GITHUB.runs.get(dispatched.runId());
		Instant now = this.clock.instant();
		Map<String, Object> claims = new LinkedHashMap<>();
		claims.put("iss", GITHUB.apiUrl().toString());
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
		claims.put("run_id", String.valueOf(dispatched.runId()));
		claims.put("runner_environment", "github-hosted");
		return claims;
	}

	private static String sign(Map<String, Object> claims) throws JOSEException {
		return sign(claims, KEY);
	}

	private static String sign(Map<String, Object> claims, RSAKey key) throws JOSEException {
		JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder();
		claims.forEach(builder::claim);
		SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY_ID).build(), builder.build());
		jwt.sign(new RSASSASigner(key));
		return jwt.serialize();
	}

	private static MockHttpServletRequestBuilder exchange(String idToken, Object correlation) {
		return exchange(idToken, correlation, "192.0.2.10");
	}

	/** The run's {@code POST}, with no session and no CSRF token, from {@code address}. */
	private static MockHttpServletRequestBuilder exchange(String idToken, Object correlation, String address) {
		return post(ActionsExchangeController.PATH).contentType(MediaType.APPLICATION_JSON)
			.content(GitHubHttp.JSON.writeValueAsString(Map.of("id_token", idToken, "correlation", correlation.toString())))
			.with(request -> {
				request.setRemoteAddr(address);
				return request;
			});
	}

	private static org.springframework.test.web.servlet.request.RequestPostProcessor port(int port) {
		return request -> {
			request.setLocalPort(port);
			request.setServerPort(port);
			return request;
		};
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> inputs(Map<String, Object> run) {
		return (Map<String, Object>) run.get("inputs");
	}

	private static List<Long> longs(JsonNode array) {
		return java.util.stream.StreamSupport.stream(array.spliterator(), false).map(JsonNode::asLong).toList();
	}

	/** The audit actions on the correlation, in order. */
	private List<String> audited(UUID correlation) {
		return this.jdbc.sql("""
				SELECT e.action FROM audit.audit_entry e JOIN audit.audit_entry_object o ON o.entry_id = e.id
				WHERE o.object_type = 'github.exchange_correlation' AND o.object_id = :id ORDER BY e.occurred_at, e.id
				""").param("id", correlation).query(String.class).list();
	}

	/** The parameters of each {@code github.exchange_refused} the Administrator got. */
	private List<Map<String, String>> alerts(UUID administrator) {
		return this.jdbc.sql("""
				SELECT params::text FROM notification.notification WHERE type = 'github.exchange_refused' AND account_id = :id
				""").param("id", administrator).query(String.class).list().stream().map(params -> {
					Map<String, String> values = new LinkedHashMap<>();
					GitHubHttp.JSON.readTree(params).properties().forEach(entry -> values.put(entry.getKey(), entry.getValue().asString()));
					return values;
				}).toList();
	}

	private void awaitListeners() {
		await().atMost(Duration.ofSeconds(15))
			.until(() -> this.jdbc.sql("SELECT count(*) FROM platform.event_publication WHERE completion_date IS NULL")
				.query(Long.class)
				.single() == 0);
	}

}
