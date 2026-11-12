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
		Dispatched dispatched = this.runs.dispatch(new Dispatch(Operation.COPY, COPY_TARGET_ID, "vibefix.yml",
				Map.of("operation", "copy", "sha", confirmed, "branch", "main", "intermediary",
						ORGANIZATION + "/job-1-intermediary", "working", ORGANIZATION + "/job-1-working"),
				Set.of(WORKING_ID, INTERMEDIARY_ID), confirmed));

		assertThat(GITHUB.runs.get(dispatched.runId()).get("display_title")).asString()
			.isEqualTo("vibefix copy " + dispatched.correlation());
		this.mvc.perform(exchange(sign(claims(dispatched, COPY_TARGET_ID)), dispatched.correlation()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.reason").value("SHA"));
		assertThat(this.runs.correlation(dispatched.correlation()).orElseThrow().refusal()).isEqualTo(ExchangeReason.SHA);

		String head = GITHUB.head(ORGANIZATION, "copy-target", "main");
		Dispatched confirmedHead = this.runs.dispatch(new Dispatch(Operation.COPY, COPY_TARGET_ID, "vibefix.yml",
				Map.of("operation", "copy", "sha", head), Set.of(WORKING_ID, INTERMEDIARY_ID), head));
		this.mvc.perform(exchange(sign(claims(confirmedHead, COPY_TARGET_ID)), confirmedHead.correlation()))
			.andExpect(status().isOk());
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
		GitHubActionsRuns quick = new GitHubActionsRuns(this.apps, this.correlations, this.transactionManager, this.environment,
				this.clock, new GitHubActionsRuns.Waits(Duration.ofMillis(10), Duration.ofMillis(30)));

		Dispatched dispatched = quick.dispatch(dispatch(RUNNER_ID));

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
		return new Dispatch(Operation.HANDOVER_COPY, target, "s6-copy.yml", inputs, Set.of(WORKING_ID, INTERMEDIARY_ID),
				null);
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
