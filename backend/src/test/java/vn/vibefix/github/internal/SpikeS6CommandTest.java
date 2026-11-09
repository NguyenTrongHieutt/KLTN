package vn.vibefix.github.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import vn.vibefix.github.internal.GitHubProperties.AppProperties;
import vn.vibefix.github.internal.SpikeS6Stub.Grant;

/**
 * Spike S6: the probe's three parts against a stand-in GitHub. Tokens are narrowed and revoked, and a
 * revocation GitHub refuses fails the run; a dispatch carries only sealed tokens, which the runner's key
 * opens to the minted ones; the run is found after a 200, a 204, a 422 on {@code return_run_details}, or
 * 404s; the artifact and the logs are fetched without the Authorization header and deleted, and a failed
 * delete fails the run; a failed run's step log is printed first; a run not completed is cancelled; part
 * c waits for the invitation; and no token is ever printed.
 */
class SpikeS6CommandTest {

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-09T03:00:00Z"), ZoneOffset.UTC);

	private static final Path WORKFLOWS = Path.of("..", "deploy", "runner");

	private static final Map<String, String> READ = Map.of("contents", "read");

	private static final Map<String, String> WRITE = Map.of("contents", "write", "workflows", "write");

	static KeyPair runnerKeys;

	/** The probe's second key pair: s6-scan seals its result under the public half. */
	static KeyPair resultKeys;

	@TempDir
	Path directory;

	private SpikeS6Stub github;

	private GitHubAppsClient apps;

	private GitHubPlatformRepositories repositories;

	private Path sourceKey;

	private Path runnerKey;

	private Path publicKey;

	private Path scannerJar;

	private Path resultKey;

	private final ByteArrayOutputStream out = new ByteArrayOutputStream();

	private final ByteArrayOutputStream err = new ByteArrayOutputStream();

	@BeforeAll
	static void theKeyPairs() throws Exception {
		runnerKeys = RunnerSealTest.keyPair(3072);
		resultKeys = RunnerSealTest.keyPair(3072);
	}

	@BeforeEach
	void aStandInGitHub() throws Exception {
		this.github = new SpikeS6Stub(runnerKeys.getPrivate());
		Path platformKey = Files.writeString(this.directory.resolve("github-platform-app-key"), TestKeys.pkcs1());
		this.sourceKey = Files.writeString(this.directory.resolve("github-source-app-key"), TestKeys.pkcs1());
		this.runnerKey = Files.writeString(this.directory.resolve("github-runner-app-key"), TestKeys.pkcs8());
		this.publicKey = Files.writeString(this.directory.resolve("runner-key.pub.pem"), RunnerSealTest.publicPem(runnerKeys));
		this.scannerJar = Files.writeString(this.directory.resolve("vibefix-app.jar"), "a jar, as far as the stand-in can tell");
		this.resultKey = Files.writeString(this.directory.resolve("runner-result-key.pem"),
				RunnerSealTest.pem("PRIVATE KEY", resultKeys.getPrivate().getEncoded()));
		this.apps = new GitHubAppsClient(new GitHubProperties(this.github.apiUrl(), null, SpikeS6Stub.ORGANIZATION,
				new AppProperties(SpikeS6Stub.PLATFORM_APP, SpikeS6Stub.PLATFORM_INSTALLATION, platformKey), null), CLOCK);
		this.repositories = new GitHubPlatformRepositories(this.apps, (type, input) -> {
			throw new AssertionError("Spike S6 sends no sandbox job");
		}, Duration.ofMillis(1), Duration.ofMillis(10), CLOCK);
	}

	@AfterEach
	void stop() {
		this.github.close();
	}

	@Test
	void everyStackButDevIsRefusedBeforeAnyCallWhateverTheArguments() throws Exception {
		assertThat(run("prod,cli", partA())).isOne();
		assertThat(run("cli", partA())).as("cli alone").isOne();
		assertThat(run("dev,prod,cli", partA())).as("dev with prod").isOne();
		assertThat(run("prod,cli", "--part=d")).as("a malformed invocation on prod").isOne();
		assertThat(run("prod,cli")).isOne();

		assertThat(errors()).contains("dev stack only").doesNotContain("Usage");
		assertThat(this.github.seen).as("refused before any call to GitHub").isEmpty();
	}

	@Test
	void aWrongInvocationExitsTwo() throws Exception {
		assertThat(run("dev,cli")).isEqualTo(2);
		assertThat(run("dev,cli", "--part=d")).isEqualTo(2);
		assertThat(run("dev,cli", "--part=a", "--source=not a repository", "--customer=lan-shop", "--source-app-id=2222",
				"--source-app-key-file=" + this.sourceKey)).isEqualTo(2);
		for (String source : List.of("lan-shop/.", "lan-shop/..")) {
			assertThat(run("dev,cli", "--part=a", "--source=" + source, "--customer=lan-shop", "--source-app-id=2222",
					"--source-app-key-file=" + this.sourceKey)).as(source).isEqualTo(2);
		}
		assertThat(run("dev,cli", replace(partB("s6-x", false, this.publicKey), "--runner=", "vibefix-runner-dev/..")))
			.as("a runner named ..")
			.isEqualTo(2);
		for (String prefix : List.of("..x", ".hidden", "s6.x", "-s6", "s6_x")) {
			assertThat(run("dev,cli", partB(prefix, false, this.publicKey))).as("--prefix=" + prefix).isEqualTo(2);
		}
		assertThat(run("dev,cli", "--part=b", "--source=" + SpikeS6Stub.SOURCE)).as("part b needs the runner and its keys")
			.isEqualTo(2);
		assertThat(run("dev,cli", "--part=c", "--customer=lan-shop")).as("part c needs part b's prefix").isEqualTo(2);
		List<String> scanWithoutResultKey = new ArrayList<>(List.of(partB("s6-x", false, this.publicKey)));
		scanWithoutResultKey.add("--scanner-jar=" + this.scannerJar);
		assertThat(run("dev,cli", scanWithoutResultKey.toArray(String[]::new)))
			.as("a scan needs the key that opens its result")
			.isEqualTo(2);
		assertThat(errors()).contains("--part=a", "--part=b", "--part=c");
		assertThat(this.github.seen).isEmpty();
	}

	@Test
	void aBadKeyFileExitsTwoBeforeAnyCallAndPrintsNothingOfIt() throws Exception {
		// Built at run time, so no PEM block sits in the source for a secret scanner.
		String privateLabel = String.join(" ", "RSA", "PRIVATE", "KEY");
		Path badAppKey = Files.writeString(this.directory.resolve("bad-app-key"), "-----BEGIN " + privateLabel
				+ "-----\nbm90IGEga2V5IGF0IGFsbA==\n-----END " + privateLabel + "-----\n");
		Path badPublicKey = Files.writeString(this.directory.resolve("bad-public-key"),
				"-----BEGIN PUBLIC KEY-----\nbm90IGEga2V5IGF0IGFsbA==\n-----END PUBLIC KEY-----\n");

		assertThat(run("dev,cli", "--part=a", "--source=" + SpikeS6Stub.SOURCE, "--customer=" + SpikeS6Stub.CUSTOMER,
				"--source-app-id=" + SpikeS6Stub.SOURCE_APP, "--source-app-key-file=" + badAppKey)).isEqualTo(2);
		assertThat(run("dev,cli", partB("s6-bad", false, badPublicKey))).isEqualTo(2);
		this.resultKey = badAppKey;
		assertThat(run("dev,cli", partB("s6-bad", true, this.publicKey))).isEqualTo(2);

		assertThat(output()).isEmpty();
		assertThat(errors()).contains("Source App's key file", "runner public key", "result private key")
			.doesNotContain("bm90IGEga2V5");
		assertThat(this.github.seen).isEmpty();
	}

	@Test
	void partANarrowsEveryTokenToTheSourceAndRevokesIt() throws Exception {
		int exit = run("dev,cli", partA());

		String output = output();
		assertThat(exit).as(output + errors()).isZero();
		assertThat(output).contains("permissions={contents=read, metadata=read} events=[]",
				"repository_selection=selected repositories=[" + SpikeS6Stub.SOURCE + "]", "private=true visibility=private",
				"default_branch=main head=" + shortSha(this.github.head(SpikeS6Stub.SOURCE, "main")), "permission=admin",
				"refused, as a Contents-read token must be", "Spike S6 part a: every check passed or was skipped");
		assertThat(output.lines().filter(line -> line.contains(" FAIL "))).isEmpty();
		long source = this.github.repository(SpikeS6Stub.SOURCE).id;
		List<Grant> grants = grantsOf(SpikeS6Stub.SOURCE_INSTALLATION);
		assertThat(grants).allSatisfy(grant -> assertThat(grant.repositoryIds().equals(Set.of(source))
				|| grant.permissions().equals(Map.of("metadata", "read"))).as(grant.toString()).isTrue());
		assertThat(grants.stream().filter(grant -> grant.permissions().containsKey("contents"))).hasSize(2)
			.allSatisfy(grant -> assertThat(grant.repositoryIds()).containsExactly(source));
		assertThat(this.github.repository(SpikeS6Stub.SOURCE).branches).as("the refused ref was not created")
			.containsOnlyKeys("main");
		assertEveryTokenRevokedAndNeverPrinted();
	}

	@Test
	void partBCopiesInActionsWithOnlySealedNarrowedTokensInTheDispatch() throws Exception {
		int exit = run("dev,cli", partB("s6-test", true, this.publicKey));

		String output = output();
		assertThat(exit).as(output + errors()).isZero();
		String head = this.github.head(SpikeS6Stub.SOURCE, "main");
		assertThat(output).contains("as AD-11 needs, members_can_fork_private_repositories=false",
				"permissions={actions=write, contents=write, metadata=read, workflows=write} events=[]",
				"repository_selection=selected repositories=[" + SpikeS6Stub.RUNNER + "]",
				SpikeS6Stub.RUNNER + " id=9002 private=true default_branch=main; s6-copy.yml=created, s6-scan.yml=created;"
						+ " .github/workflows/ holds [s6-copy.yml, s6-scan.yml]",
				SpikeS6Command.scannerAssetName(sha256(this.scannerJar)) + " uploaded",
				"vibefix-dev/s6-test-intermediary id=", "vibefix-dev/s6-test-working id=",
				"private=true allow_forking=false, wiki, issues, projects and discussions off", "run ID in field workflow_run_id",
				"COPIED at step push", "artifact deleted HTTP 204, logs deleted HTTP 204",
				"read back vibefix-dev/s6-test-intermediary main=" + shortSha(head),
				"read back vibefix-dev/s6-test-working main=" + shortSha(head),
				"vibefix-dev/s6-test-intermediary enabled=false, vibefix-dev/s6-test-working enabled=false",
				"HTTP 403  GitHub answers: " + SpikeS6Stub.FREE_RULESET, "SCANNED at step scan",
				"commits=2 scannedBlobs=7 unscanned=0 credentials=1 personalData=1 head as approved",
				"billed 2 min from timing.billable (UBUNTU total_ms=61000 jobs=1)",
				"Spike S6 part b: every check passed or was skipped");
		assertThat(output).as("the scan prints counts only").doesNotContain("config/secret-path.env", "AKIA");
		assertThat(output.lines().filter(line -> line.contains(" FAIL "))).isEmpty();
		assertThat(line(output, "scan billed minutes")).as("on a line of its own").doesNotContain("commits=");
		assertThat(errors()).as("no run failed, so no log is printed").doesNotContain("did not succeed");
		assertTheTotalsAddUp(line(output, "run durations and billed minutes"), 3);
		assertThat(this.github.repository(SpikeS6Stub.RUNNER).files.get(".github/workflows/s6-copy.yml"))
			.isEqualTo(Files.readString(WORKFLOWS.resolve("s6-copy.yml"), StandardCharsets.UTF_8));
		assertThat(this.github.repository(SpikeS6Stub.RUNNER).files.get(".github/workflows/s6-scan.yml"))
			.isEqualTo(Files.readString(WORKFLOWS.resolve("s6-scan.yml"), StandardCharsets.UTF_8));
		assertThat(this.github.head("vibefix-dev/s6-test-intermediary", "main")).isEqualTo(head);
		assertThat(this.github.head("vibefix-dev/s6-test-working", "main")).isEqualTo(head);

		assertThat(this.github.dispatches).hasSize(3);
		for (JsonNode dispatch : this.github.dispatches) {
			assertThat(dispatch.path("ref").asString()).isEqualTo("main");
			assertThat(dispatch.path("return_run_details").asBoolean()).isTrue();
			for (String token : this.github.tokens) {
				assertThat(dispatch.toString()).as("the dispatch holds no minted token").doesNotContain(token);
			}
		}
		assertEveryDispatchNarrowed("s6-test");
		JsonNode scanDispatch = this.github.dispatches.getLast().path("inputs");
		assertThat(scanDispatch.path("result_key").asString())
			.isEqualTo(Base64.getEncoder().encodeToString(resultKeys.getPublic().getEncoded()));
		JsonNode sealedResult = GitHubHttp.JSON.readTree(this.github.uploadedResults.getLast());
		List<String> envelopeFields = new ArrayList<>();
		sealedResult.properties().forEach(field -> envelopeFields.add(field.getKey()));
		assertThat(envelopeFields).as("the scan artifact holds the envelope only")
			.containsExactlyInAnyOrder("wrapped", "iv", "ciphertext", "tag");
		assertThat(this.github.uploadedResults.getLast()).doesNotContain("commits", "SCANNED", "secret-path");
		assertThat(this.github.uploadedResults.getFirst()).as("a copy's result stays plain").contains("COPIED");

		assertThat(this.github.blobAuthorizations).as("blob storage never sees the token").hasSize(3).containsOnly("none");
		assertThat(this.github.deletedArtifacts).hasSize(3);
		assertThat(this.github.deletedLogs).containsExactlyElementsOf(this.github.unsealedByRun().keySet());
		this.github.unsealedByRun().forEach((run, tokens) -> {
			int completed = this.github.events.indexOf("completed " + run);
			assertThat(completed).isNotNegative();
			for (String token : tokens) {
				assertThat(this.github.events.indexOf("revoked " + token)).as("revoked after its run completed")
					.isGreaterThan(completed);
			}
		});
		assertThat(grantsOf(SpikeS6Stub.RUNNER_INSTALLATION)).allSatisfy(grant -> assertThat(
				grant.repositoryIds().equals(Set.of(9002L)) || grant.permissions().equals(Map.of("metadata", "read")))
			.as(grant.toString())
			.isTrue());
		assertEveryTokenRevokedAndNeverPrinted();
	}

	@Test
	void aSecondPartBFindsTheWorkflowsAndTheJarInPlaceButNotAnotherJar() throws Exception {
		assertThat(run("dev,cli", partB("s6-one", true, this.publicKey))).isZero();
		this.out.reset();

		assertThat(run("dev,cli", partB("s6-two", true, this.publicKey))).as(output()).isZero();
		assertThat(output()).contains("s6-copy.yml=unchanged, s6-scan.yml=unchanged",
				SpikeS6Command.scannerAssetName(sha256(this.scannerJar)) + " already uploaded");

		this.github.repository(SpikeS6Stub.RUNNER).releases.get(SpikeS6Command.RELEASE).assets.getFirst().put("size", 3);
		this.out.reset();
		assertThat(run("dev,cli", partB("s6-three", true, this.publicKey))).isOne();
		assertThat(line(output(), "scanner release asset")).contains(" FAIL ", "state=uploaded size=3",
				"delete that asset by hand");
		assertThat(line(output(), "scan in Actions")).contains("SKIPPED");
		assertEveryTokenRevokedAndNeverPrinted();
	}

	@Test
	void aDispatchAnswering204IsFoundByItsRunName() throws Exception {
		this.github.dispatchStatus = 204;

		int exit = run("dev,cli", partB("s6-old", false, this.publicKey));

		assertThat(exit).as(output()).isZero();
		assertThat(output()).contains("204, found by run-name", "COPIED at step push", "no --scanner-jar");
		assertThat(this.github.seen.stream().filter(request -> request.path().endsWith("/actions/workflows/s6-copy.yml/runs")))
			.isNotEmpty();
		assertThat(this.github.blobAuthorizations).containsOnly("none");
		assertEveryTokenRevokedAndNeverPrinted();
	}

	@Test
	void aDispatchRefusingReturnRunDetailsIsSentAgainWithoutIt() throws Exception {
		this.github.refuseReturnRunDetails = true;

		int exit = run("dev,cli", partB("s6-plain", false, this.publicKey));

		assertThat(exit).as(output()).isZero();
		assertThat(line(output(), "copy source to Intermediary")).contains(" OK ",
				"return_run_details refused (HTTP 422), 204, found by run-name", "COPIED at step push");
		assertThat(this.github.dispatches).hasSize(2)
			.allSatisfy(dispatch -> assertThat(dispatch.has("return_run_details")).isFalse());
		assertEveryTokenRevokedAndNeverPrinted();
	}

	@Test
	void aDispatchAnswering404IsTriedAgainWhileGitHubLearnsTheWorkflow() throws Exception {
		this.github.dispatchNotFound = 2;

		int exit = run("dev,cli", partB("s6-new", false, this.publicKey));

		assertThat(exit).as(output()).isZero();
		assertThat(line(output(), "copy source to Intermediary")).contains(" OK ",
				"dispatched after 2 retries on 404, run ID in field workflow_run_id", "COPIED at step push");
		assertThat(this.github.seen.stream().filter(request -> request.path().endsWith("/dispatches"))).hasSize(4);
		assertEveryTokenRevokedAndNeverPrinted();
	}

	@Test
	void aRunNotCompletedInTimeIsCancelledAndItsArtifactAndLogsStay() throws Exception {
		this.github.neverCompletes = true;

		int exit = run("dev,cli", partB("s6-slow", false, this.publicKey));

		String output = output();
		assertThat(exit).as(output).isOne();
		long run = this.github.unsealedByRun().keySet().iterator().next();
		assertThat(line(output, "copy source to Intermediary")).contains(" FAIL ", "not completed in",
				"cancel HTTP 202; its artifact and logs stay: https://github.com/" + SpikeS6Stub.RUNNER + "/actions/runs/" + run);
		assertThat(line(output, "copy Intermediary to Working")).contains("SKIPPED");
		assertThat(line(output, "run durations and billed minutes")).contains("SKIPPED", "no run completed");
		assertThat(this.github.cancelled).containsExactly(run);
		assertThat(this.github.deletedArtifacts).isEmpty();
		assertThat(this.github.deletedLogs).isEmpty();
		assertEveryTokenRevokedAndNeverPrinted();
	}

	@Test
	void aFailedRunPrintsItsFailedStepsLastLinesBeforeItsLogsAreDeleted() throws Exception {
		this.github.failAtStep = "push";

		int exit = run("dev,cli", partB("s6-fail", false, this.publicKey));

		assertThat(exit).as(output()).isOne();
		assertThat(line(output(), "copy source to Intermediary")).contains("run ID in field workflow_run_id",
				"failure, FAILED at step push", "artifact deleted HTTP 204, logs deleted HTTP 204");
		String errors = errors();
		assertThat(errors).contains("did not succeed; failed step 4 (Copy the commit), the last 40 lines of copy/4_Copy the commit.txt");
		List<String> tail = errors.lines().filter(line -> line.startsWith("  | ")).toList();
		assertThat(tail).hasSize(SpikeS6Command.LOG_LINES);
		assertThat(tail.getFirst()).endsWith(" log line " + (SpikeS6Stub.LOG_LINES - SpikeS6Command.LOG_LINES + 1));
		assertThat(tail.getLast()).endsWith("error: failed to push some refs (log line " + SpikeS6Stub.LOG_LINES + ")");
		assertThat(this.github.logAuthorizations).as("the logs' storage never sees the token").containsExactly("none");
		assertThat(this.github.deletedArtifacts).hasSize(1);
		assertThat(this.github.deletedLogs).as("the logs are still deleted, after they were read").hasSize(1);
		assertEveryTokenRevokedAndNeverPrinted();
	}

	@Test
	void aScanEnvelopeWithABadTagFailsAndNothingDecryptedIsPrinted() throws Exception {
		this.github.corruptScanTag = true;

		int exit = run("dev,cli", partB("s6-tag", true, this.publicKey));

		assertThat(exit).as(output()).isOne();
		assertThat(line(output(), "scan in Actions")).contains(" FAIL ",
				"the scan result did not open: the envelope's tag does not verify")
			.doesNotContain("SCANNED", "commits=", "credentials=");
		assertThat(output() + errors()).doesNotContain("secret-path", "AKIA");
		assertThat(this.github.deletedArtifacts).as("the spike's artifacts are still deleted").hasSize(3);
		assertEveryTokenRevokedAndNeverPrinted();
	}

	@Test
	void aScanResultWithPlaintextBesideItsEnvelopeIsRefused() throws Exception {
		this.github.plaintextBesideEnvelope = true;

		int exit = run("dev,cli", partB("s6-leak", true, this.publicKey));

		assertThat(exit).as(output()).isOne();
		assertThat(line(output(), "scan in Actions")).contains(" FAIL ",
				"the scan result is not sealed as {wrapped, iv, ciphertext, tag}")
			.doesNotContain("commits=");
		assertThat(output() + errors()).doesNotContain("secret-path", "AKIA");
		assertEveryTokenRevokedAndNeverPrinted();
	}

	@ParameterizedTest
	@ValueSource(strings = { "master", "public", "another workflow" })
	void aRunnerTheDesignDoesNotAllowGetsNoRepositoryAndNoDispatch(String runner) throws Exception {
		SpikeS6Stub.Repo repo = this.github.repository(SpikeS6Stub.RUNNER);
		switch (runner) {
			case "master" -> repo.defaultBranch = "master";
			case "public" -> repo.privateRepository = false;
			default -> repo.files.put(".github/workflows/other.yml", "on: push\n");
		}

		int exit = run("dev,cli", partB("s6-guard", true, this.publicKey));

		String output = output();
		assertThat(exit).as(output).isOne();
		assertThat(line(output, "runner App and workflows")).contains(" FAIL ",
				runner.equals("another workflow") ? ".github/workflows/ holds [other.yml, s6-copy.yml, s6-scan.yml]"
						: "nothing written: the runner must be private, with the default branch main");
		for (String later : List.of("create private Intermediary", "create private Working", "copy source to Intermediary",
				"scanner release asset", "scan in Actions")) {
			assertThat(line(output, later)).as(later).contains("SKIPPED");
		}
		assertThat(this.github.dispatches).isEmpty();
		assertThat(this.github.repository("vibefix-dev/s6-guard-intermediary")).as("nothing is created").isNull();
		assertEveryTokenRevokedAndNeverPrinted();
	}

	@Test
	void theUploadHostIsGitHubsOrTheApiItself() {
		URI api = URI.create("https://api.github.com");
		assertThat(SpikeS6Command.uploadHostAllowed(api,
				URI.create("https://uploads.github.com/repos/vibefix-runner-dev/vibefix-runner/releases/1/assets"))).isTrue();
		assertThat(SpikeS6Command.uploadHostAllowed(api, URI.create("https://uploads.github.com:443/repos/o/r/releases/1/assets")))
			.isTrue();
		assertThat(SpikeS6Command.uploadHostAllowed(api, URI.create("https://api.github.com/repos/o/r/releases/1/assets")))
			.isTrue();
		for (String foreign : List.of("http://uploads.github.com/x", "https://uploads.github.com.evil.test/x",
				"https://uploads.example.com/x", "https://uploads.github.com:8443/x", "https://api.github.com:8443/x")) {
			assertThat(SpikeS6Command.uploadHostAllowed(api, URI.create(foreign))).as(foreign).isFalse();
		}
		URI stub = URI.create("http://127.0.0.1:4100");
		assertThat(SpikeS6Command.uploadHostAllowed(stub, URI.create("http://127.0.0.1:4100/uploads/x"))).isTrue();
		assertThat(SpikeS6Command.uploadHostAllowed(stub, URI.create("http://127.0.0.1:4101/uploads/x"))).isFalse();
	}

	@Test
	void aForeignUploadUrlGetsNoRequestAndNoToken() throws Exception {
		AtomicInteger received = new AtomicInteger();
		HttpServer foreign = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		foreign.createContext("/", exchange -> {
			received.incrementAndGet();
			exchange.sendResponseHeaders(201, -1);
			exchange.close();
		});
		foreign.start();
		try {
			this.github.uploadUrl = "http://127.0.0.1:" + foreign.getAddress().getPort() + "/uploads/assets{?name,label}";

			int exit = run("dev,cli", partB("s6-foreign", true, this.publicKey));

			assertThat(exit).as(output()).isOne();
			assertThat(line(output(), "scanner release asset")).contains(" FAIL ",
					"the release's upload_url is not GitHub's upload host: nothing was sent to 127.0.0.1");
			assertThat(received).as("the foreign host got no request, and so no token").hasValue(0);
			assertThat(this.github.seen.stream().filter(request -> request.path().startsWith("/uploads/"))).isEmpty();
			assertEveryTokenRevokedAndNeverPrinted();
		}
		finally {
			foreign.stop(0);
		}
	}

	@Test
	void aRulesetAnswerThatNamesNoPlanFails() throws Exception {
		this.github.rulesetMessage = "Resource not accessible by integration";

		int exit = run("dev,cli", partB("s6-rule", false, this.publicKey));

		assertThat(exit).as(output()).isOne();
		assertThat(line(output(), "ruleset on private Intermediary")).contains(" FAIL ",
				"HTTP 403  an answer that names no plan: Resource not accessible by integration");
	}

	@Test
	void durationsAndBilledMinutesFallBackToTheRunAndItsJobs() throws Exception {
		this.github.timingWithoutDuration = true;
		this.github.timingWithoutBillable = true;

		int exit = run("dev,cli", partB("s6-jobs", true, this.publicKey));

		String output = output();
		assertThat(exit).as(output).isZero();
		// The stand-in's run goes from 03:00:00 to 03:00:42, and its job from 03:00:00 to 03:01:05.
		assertThat(line(output, "run durations and billed minutes")).contains(
				"copy-intermediary=42000 ms billed=2 min (jobs)", "copy-working=42000 ms billed=2 min (jobs)",
				"scan=42000 ms billed=2 min (jobs)", "total=126000 ms billed=6 min");
		assertThat(line(output, "scan billed minutes")).contains("billed 2 min from jobs");
		assertTheTotalsAddUp(line(output, "run durations and billed minutes"), 3);
	}

	@Test
	void aFailedCleanupFailsTheRun() throws Exception {
		this.github.artifactDeleteStatus = 403;

		int exit = run("dev,cli", partB("s6-keep", false, this.publicKey));

		assertThat(exit).as(output()).isOne();
		assertThat(line(output(), "copy source to Intermediary")).contains(" FAIL ", "COPIED at step push",
				"artifact deleted HTTP 403, logs deleted HTTP 204, cleanup failed");
	}

	@Test
	void aTokenGitHubDoesNotRevokeFailsTheRun() throws Exception {
		this.github.revocationStatus = 500;

		int exit = run("dev,cli", partA());

		assertThat(exit).as(output()).isOne();
		assertThat(output().lines().filter(line -> line.contains(" FAIL "))).as("every check itself passed").isEmpty();
		assertThat(output()).contains("token(s) were not revoked");
		assertThat(errors()).contains("Spike S6: token not revoked: HTTP 500");
		for (String token : this.github.tokens) {
			assertThat(output() + errors()).doesNotContain(token);
		}
	}

	@Test
	void partCWaitsForTheInvitationThenHandsOverTwice() throws Exception {
		assertThat(run("dev,cli", partB("s6-hand", false, this.publicKey))).isZero();
		this.out.reset();

		assertThat(run("dev,cli", partC("s6-hand"))).as("a pending invitation is no failure").isZero();
		assertThat(output()).contains("invited with pull", "permissions=read", "accept, then run again").doesNotContain("[ 2]");
		this.out.reset();
		assertThat(run("dev,cli", partC("s6-hand"))).isZero();
		assertThat(output()).contains("pending, permissions=read", "accept, then run again");
		assertThat(this.github.repository("vibefix-dev/s6-hand-intermediary").invitations).containsOnlyKeys(SpikeS6Stub.CUSTOMER);

		this.github.accept(SpikeS6Stub.CUSTOMER);
		this.out.reset();
		int exit = run("dev,cli", partC("s6-hand"));

		String output = output();
		assertThat(exit).as(output + errors()).isZero();
		SpikeS6Stub.Repo intermediary = this.github.repository("vibefix-dev/s6-hand-intermediary");
		SpikeS6Stub.Repo working = this.github.repository("vibefix-dev/s6-hand-working");
		List<String> commits = working.branches.get("main");
		String h1 = commits.get(commits.size() - 2);
		String h2 = commits.getLast();
		assertThat(output).contains("is a collaborator", "Intermediary permission=read role_name=read; Working HTTP 404",
				"H1=" + shortSha(h1), "H2=" + shortSha(h2) + " on vibefix-dev/s6-hand-working/main, parent " + shortSha(h1) + " (H1)",
				"read back vibefix-dev/s6-hand-intermediary handover/s6-hand-1=" + shortSha(h1) + ", pull request #1",
				"read back vibefix-dev/s6-hand-intermediary handover/s6-hand-2=" + shortSha(h2) + ", pull request #2",
				"#2 merge_method=merge sha=" + shortSha(h2) + ": merged=true",
				"after #2 merged, #1 state=open merged=false; commented HTTP 201, closed HTTP 200, read back state=closed",
				"Spike S6 part c: every check passed or was skipped");
		assertThat(output.lines().filter(line -> line.contains(" FAIL "))).isEmpty();
		assertThat(intermediary.pulls.get(2).merged).isTrue();
		assertThat(intermediary.pulls.get(1).state).isEqualTo("closed");
		assertThat(this.github.comments).containsExactly("vibefix-dev/s6-hand-intermediary#1");
		assertThat(intermediary.branches.get("main")).contains(h1, h2);
		assertThat(this.github.dispatches).hasSize(4);
		assertEveryDispatchNarrowed("s6-hand");
		assertEveryTokenRevokedAndNeverPrinted();
	}

	@Test
	void partCRefusesAPrefixWhoseHandoverBranchExists() throws Exception {
		assertThat(run("dev,cli", partB("s6-twice", false, this.publicKey))).isZero();
		assertThat(run("dev,cli", partC("s6-twice"))).isZero();
		this.github.accept(SpikeS6Stub.CUSTOMER);
		SpikeS6Stub.Repo intermediary = this.github.repository("vibefix-dev/s6-twice-intermediary");
		intermediary.branches.put("handover/s6-twice-1", new ArrayList<>(intermediary.branches.get("main")));
		int dispatches = this.github.dispatches.size();
		this.out.reset();

		int exit = run("dev,cli", partC("s6-twice"));

		assertThat(exit).as(output()).isOne();
		assertThat(line(output(), "commit H1 on Working")).contains(" FAIL ",
				"handover/s6-twice-1 exists on vibefix-dev/s6-twice-intermediary: part c already ran on s6-twice");
		assertThat(line(output(), "copy H1 to handover, pull request")).contains("SKIPPED");
		assertThat(this.github.dispatches).as("nothing is dispatched").hasSize(dispatches);
		assertThat(this.github.repository("vibefix-dev/s6-twice-working").branches.get("main"))
			.as("nothing is committed")
			.isEqualTo(intermediary.branches.get("main"));
	}

	@Test
	void aFirstPullRequestGitHubMarkedMergedIsLeftAsItIs() throws Exception {
		this.github.containedPullsMarkedMerged = true;
		assertThat(run("dev,cli", partB("s6-auto", false, this.publicKey))).isZero();
		assertThat(run("dev,cli", partC("s6-auto"))).isZero();
		this.github.accept(SpikeS6Stub.CUSTOMER);
		this.out.reset();

		int exit = run("dev,cli", partC("s6-auto"));

		assertThat(exit).as(output()).isZero();
		assertThat(output()).contains("after #2 merged, #1 state=closed merged=true").doesNotContain("commented");
		assertThat(this.github.comments).isEmpty();
	}

	/**
	 * Each sealed token of each dispatch opens to a token of the expected installation, narrowed to exactly
	 * one repository, with exactly the expected permissions.
	 */
	private void assertEveryDispatchNarrowed(String prefix) {
		long source = this.github.repository(SpikeS6Stub.SOURCE).id;
		long intermediary = this.github.repository("vibefix-dev/" + prefix + "-intermediary").id;
		long working = this.github.repository("vibefix-dev/" + prefix + "-working").id;
		Grant fromSource = new Grant(SpikeS6Stub.SOURCE_INSTALLATION, Set.of(source), READ);
		for (JsonNode dispatch : this.github.dispatches) {
			JsonNode inputs = dispatch.path("inputs");
			String kind = inputs.path("correlation").asString().replaceFirst("-[0-9a-f]{16}$", "");
			Map<String, Grant> expected = switch (kind) {
				case "intermediary" -> Map.of("source_token", fromSource, "target_token",
						new Grant(SpikeS6Stub.PLATFORM_INSTALLATION, Set.of(intermediary), WRITE));
				case "working" -> Map.of("source_token", new Grant(SpikeS6Stub.PLATFORM_INSTALLATION, Set.of(intermediary), READ),
						"target_token", new Grant(SpikeS6Stub.PLATFORM_INSTALLATION, Set.of(working), WRITE));
				case "scan" -> Map.of("token", fromSource);
				case "handover-1", "handover-2" -> Map.of("source_token",
						new Grant(SpikeS6Stub.PLATFORM_INSTALLATION, Set.of(working), READ), "target_token",
						new Grant(SpikeS6Stub.PLATFORM_INSTALLATION, Set.of(intermediary), WRITE));
				default -> throw new AssertionError("an unexpected dispatch: " + kind);
			};
			List<String> sealed = new ArrayList<>();
			inputs.properties().forEach(input -> {
				if (input.getKey().endsWith("token")) {
					sealed.add(input.getKey());
				}
			});
			assertThat(sealed).as(kind).containsExactlyInAnyOrderElementsOf(expected.keySet());
			expected.forEach((field, grant) -> {
				String token = open(inputs.path(field).asString());
				assertThat(this.github.tokens).as(kind + " " + field + " opens to a minted token").contains(token);
				assertThat(this.github.grants.get(token)).as(kind + " " + field).isEqualTo(grant);
			});
		}
	}

	/** Check 12's line: each run's milliseconds and minutes add up to its totals. */
	private static void assertTheTotalsAddUp(String line, int runs) {
		Matcher each = Pattern.compile("([a-z0-9-]+)=(\\d+) ms billed=(\\d+) min \\(([a-z.]+)\\)").matcher(line);
		long durations = 0;
		long minutes = 0;
		int counted = 0;
		while (each.find()) {
			durations += Long.parseLong(each.group(2));
			minutes += Long.parseLong(each.group(3));
			counted++;
		}
		Matcher total = Pattern.compile("total=(\\d+) ms billed=(\\d+) min").matcher(line);
		assertThat(total.find()).as(line).isTrue();
		assertThat(counted).as(line).isEqualTo(runs);
		assertThat(Long.parseLong(total.group(1))).as(line).isEqualTo(durations);
		assertThat(Long.parseLong(total.group(2))).as(line).isEqualTo(minutes);
	}

	private void assertEveryTokenRevokedAndNeverPrinted() {
		assertThat(this.github.tokens).isNotEmpty();
		assertThat(this.github.revoked).as("every token is revoked").containsExactlyInAnyOrderElementsOf(this.github.tokens);
		String output = output() + errors();
		assertThat(output).doesNotContain("token not revoked");
		for (String token : this.github.tokens) {
			assertThat(output).doesNotContain(token);
		}
		for (JsonNode dispatch : this.github.dispatches) {
			dispatch.path("inputs").properties().forEach(input -> {
				if (input.getKey().endsWith("token")) {
					assertThat(output).as("no sealed token is printed either").doesNotContain(input.getValue().asString());
				}
			});
		}
		assertThat(output).doesNotContain("sig=stubsignature");
	}

	private List<Grant> grantsOf(long installation) {
		synchronized (this.github.grants) {
			return this.github.grants.values().stream().filter(grant -> grant.installation() == installation).toList();
		}
	}

	/** The one printed line of a check. */
	private static String line(String output, String check) {
		List<String> lines = output.lines().filter(line -> line.contains("] " + check)).toList();
		assertThat(lines).as(check + " in\n" + output).hasSize(1);
		return lines.getFirst();
	}

	private static String open(String sealed) {
		try {
			Cipher cipher = Cipher.getInstance(RunnerSeal.TRANSFORMATION);
			cipher.init(Cipher.DECRYPT_MODE, runnerKeys.getPrivate(), RunnerSeal.OAEP);
			return new String(cipher.doFinal(Base64.getDecoder().decode(sealed)), StandardCharsets.UTF_8);
		}
		catch (java.security.GeneralSecurityException ex) {
			throw new AssertionError("a sealed token did not open", ex);
		}
	}

	private String[] partA() {
		return new String[] { "--part=a", "--source=" + SpikeS6Stub.SOURCE, "--customer=" + SpikeS6Stub.CUSTOMER,
				"--source-app-id=" + SpikeS6Stub.SOURCE_APP, "--source-app-key-file=" + this.sourceKey };
	}

	private String[] partB(String prefix, boolean scanner, Path publicKey) {
		List<String> args = new ArrayList<>(List.of("--part=b", "--prefix=" + prefix, "--source=" + SpikeS6Stub.SOURCE,
				"--runner=" + SpikeS6Stub.RUNNER, "--source-app-id=" + SpikeS6Stub.SOURCE_APP,
				"--source-app-key-file=" + this.sourceKey, "--runner-app-id=" + SpikeS6Stub.RUNNER_APP,
				"--runner-app-key-file=" + this.runnerKey, "--runner-public-key=" + publicKey, "--workflows-dir=" + WORKFLOWS));
		if (scanner) {
			args.add("--scanner-jar=" + this.scannerJar);
			args.add("--result-private-key=" + this.resultKey);
		}
		return args.toArray(String[]::new);
	}

	private String[] partC(String prefix) {
		return new String[] { "--part=c", "--prefix=" + prefix, "--customer=" + SpikeS6Stub.CUSTOMER,
				"--runner=" + SpikeS6Stub.RUNNER, "--runner-app-id=" + SpikeS6Stub.RUNNER_APP,
				"--runner-app-key-file=" + this.runnerKey, "--runner-public-key=" + this.publicKey };
	}

	private static String[] replace(String[] args, String option, String value) {
		return List.of(args).stream().map(arg -> arg.startsWith(option) ? option + value : arg).toArray(String[]::new);
	}

	private int run(String profiles, String... args) {
		MockEnvironment environment = new MockEnvironment();
		environment.setActiveProfiles(profiles.split(","));
		environment.setProperty("vibefix.cli.command", SpikeS6Command.COMMAND);
		SpikeS6Command command = new SpikeS6Command(this.apps, this.repositories, environment, CLOCK,
				new SpikeS6Command.Waits(Duration.ofMillis(5), Duration.ofMillis(300), Duration.ofMillis(5), Duration.ofMillis(100)),
				new PrintStream(this.out, true, StandardCharsets.UTF_8), new PrintStream(this.err, true, StandardCharsets.UTF_8));
		command.run(new DefaultApplicationArguments(args));
		return command.getExitCode();
	}

	private String output() {
		return this.out.toString(StandardCharsets.UTF_8);
	}

	private String errors() {
		return this.err.toString(StandardCharsets.UTF_8);
	}

	private static String sha256(Path file) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
	}

	private static String shortSha(String sha) {
		return sha.substring(0, 12);
	}

}
