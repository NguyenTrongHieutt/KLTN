package vn.vibefix.github.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

import tools.jackson.databind.JsonNode;
import vn.vibefix.github.internal.GitHubProperties.AppProperties;
import vn.vibefix.github.internal.SpikeS6Stub.Exchanged;
import vn.vibefix.github.internal.SpikeS6Stub.Flaw;
import vn.vibefix.github.internal.SpikeS6Stub.Grant;

/**
 * Spike S6 revision: the probe's three parts against a stand-in GitHub whose runs exchange their ID
 * tokens at the probe's own exchange. A dispatch carries no token at all; each run gets one Platform
 * token narrowed to its correlation, revoked when the run ends; a forged, expired, foreign or replayed ID
 * token, a self-hosted runner, a foreign run, or a changed workflow on another ref gets none, and nothing
 * moves; every other token is narrowed and revoked, and a revocation GitHub refuses fails the run; the
 * runner's run logs are deleted and a Customer's stay; part c waits for the invitation, hands over twice
 * on the runner, and brings the Final Merge back on the Customer's Actions; with {@code --scan}, part b's
 * gitleaks scan never reaches the exchange and is OK only when its one count line agrees with its
 * conclusion, printing that count and nothing of a finding; and no token or ID token is ever printed.
 */
class SpikeS6CommandTest {

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-09T03:00:00Z"), ZoneOffset.UTC);

	private static final Path CUSTOMER_WORKFLOW = Path.of("..", "deploy", "customer", "vibefix.yml");

	private static final Path RUNNER_WORKFLOWS = Path.of("..", "deploy", "runner");

	private static final Map<String, String> COPY = Map.of("contents", "write", "workflows", "write");

	@TempDir
	Path directory;

	private SpikeS6Stub github;

	private GitHubAppsClient apps;

	private GitHubPlatformRepositories repositories;

	private Path sourceKey;

	private final ByteArrayOutputStream out = new ByteArrayOutputStream();

	private final ByteArrayOutputStream err = new ByteArrayOutputStream();

	@BeforeEach
	void aStandInGitHub() throws Exception {
		this.github = new SpikeS6Stub(Files.readString(CUSTOMER_WORKFLOW, StandardCharsets.UTF_8));
		Path platformKey = Files.writeString(this.directory.resolve("github-platform-app-key"), TestKeys.pkcs1());
		this.sourceKey = Files.writeString(this.directory.resolve("github-source-app-key"), TestKeys.pkcs8());
		this.apps = apps(platformKey, SpikeS6Stub.RUNNER_NAME);
		this.repositories = new GitHubPlatformRepositories(this.apps, (type, input) -> {
			throw new AssertionError("Spike S6 sends no sandbox job");
		}, CLOCK);
	}

	@AfterEach
	void stop() {
		this.github.close();
	}

	@Test
	void everyStackButDevIsRefusedBeforeAnyCallWhateverTheArguments() throws Exception {
		assertThat(run("prod,cli", partA())).isOne();
		assertThat(run("cli", partA())).as("cli alone").isOne();
		assertThat(run("dev,prod,cli", partB("s6-x", null))).as("dev with prod").isOne();
		assertThat(run("prod,cli", "--part=d")).as("a malformed invocation on prod").isOne();
		assertThat(run("prod,cli")).isOne();

		assertThat(errors()).contains("dev stack only").doesNotContain("Usage");
		assertThat(this.github.seen).as("refused before any call to GitHub").isEmpty();
	}

	@Test
	void aWrongInvocationExitsTwo() throws Exception {
		assertThat(run("dev,cli")).isEqualTo(2);
		assertThat(run("dev,cli", "--part=d")).isEqualTo(2);
		for (String source : List.of("not a repository", "lan-shop/.", "lan-shop/..")) {
			assertThat(run("dev,cli", replace(partA(), "--source=", source))).as(source).isEqualTo(2);
		}
		assertThat(run("dev,cli", without(partA(), "--customer-workflow="))).as("part a needs the published file")
			.isEqualTo(2);
		assertThat(run("dev,cli", without(partB("s6-x", null), "--exchange-url="))).as("part b needs the exchange")
			.isEqualTo(2);
		assertThat(run("dev,cli", without(partB("s6-x", null), "--workflows-dir="))).as("part b needs the runner's file")
			.isEqualTo(2);
		for (String url : List.of("http://tunnel.example", "https://s6.trycloudflare.com/exchange",
				"https://someone@s6.trycloudflare.com", "https://s6.trycloudflare.com?x=1", "ftp://s6.trycloudflare.com")) {
			assertThat(run("dev,cli", replace(partB("s6-x", null), "--exchange-url=", url))).as(url).isEqualTo(2);
		}
		for (String port : List.of("0", "70000", "http")) {
			assertThat(run("dev,cli", replace(partB("s6-x", null), "--exchange-port=", port))).as("port " + port).isEqualTo(2);
		}
		for (String prefix : List.of("..x", ".hidden", "s6.x", "-s6", "s6_x")) {
			assertThat(run("dev,cli", partB(prefix, null))).as("--prefix=" + prefix).isEqualTo(2);
		}
		for (String ref : List.of("bad..ref", "trailing/", "x.lock", "-tampered")) {
			assertThat(run("dev,cli", partB("s6-x", ref))).as("--tampered-ref=" + ref).isEqualTo(2);
		}
		List<String> twice = new ArrayList<>(List.of(partB("s6-x", null)));
		twice.add("--prefix=s6-y");
		assertThat(run("dev,cli", twice.toArray(String[]::new))).as("a prefix given twice").isEqualTo(2);
		assertThat(run("dev,cli", without(partC("s6-x"), "--prefix="))).as("part c needs part b's prefix").isEqualTo(2);
		assertThat(run("dev,cli", without(partC("s6-x"), "--source="))).as("part c needs the source").isEqualTo(2);
		List<String> scanWithAValue = new ArrayList<>(List.of(partB("s6-x", null)));
		scanWithAValue.add("--scan=yes");
		assertThat(run("dev,cli", scanWithAValue.toArray(String[]::new))).as("--scan takes no value").isEqualTo(2);
		assertThat(run("dev,cli", withScan(partA()))).as("--scan is part b's").isEqualTo(2);
		assertThat(run("dev,cli", withScan(partC("s6-x")))).as("--scan is part b's").isEqualTo(2);

		assertThat(errors()).contains("--part=a", "--part=b", "--part=c", "--exchange-url=", "--customer-workflow=",
				"[--scan]");
		assertThat(this.github.seen).isEmpty();
	}

	@Test
	void aBadFileExitsTwoBeforeAnyCallAndPrintsNothingOfIt() throws Exception {
		// Built at run time, so no PEM block sits in the source for a secret scanner.
		String privateLabel = String.join(" ", "RSA", "PRIVATE", "KEY");
		Path badAppKey = Files.writeString(this.directory.resolve("bad-app-key"), "-----BEGIN " + privateLabel
				+ "-----\nbm90IGEga2V5IGF0IGFsbA==\n-----END " + privateLabel + "-----\n");
		Path emptyDirectory = Files.createDirectories(this.directory.resolve("no-workflows"));

		assertThat(run("dev,cli", replace(partA(), "--source-app-key-file=", badAppKey.toString()))).isEqualTo(2);
		assertThat(run("dev,cli", replace(partA(), "--customer-workflow=", this.directory.resolve("missing.yml").toString())))
			.isEqualTo(2);
		assertThat(run("dev,cli", replace(partB("s6-bad", null), "--workflows-dir=", emptyDirectory.toString()))).isEqualTo(2);

		assertThat(output()).isEmpty();
		assertThat(errors()).contains("Source App's key file", "--customer-workflow cannot be read",
				"--workflows-dir holds no readable s6-copy.yml")
			.doesNotContain("bm90IGEga2V5");
		assertThat(this.github.seen).isEmpty();
	}

	@Test
	void partsBAndCNeedTheRunnerConfigured() throws Exception {
		this.apps = apps(Files.writeString(this.directory.resolve("platform-key"), TestKeys.pkcs1()), null);

		assertThat(run("dev,cli", partB("s6-none", null))).isOne();
		assertThat(run("dev,cli", partC("s6-none"))).isOne();

		assertThat(errors()).contains("vibefix.github.runner-repository is not configured");
		assertThat(this.github.seen).isEmpty();
	}

	@Test
	void anExchangePortInUseIsRefusedBeforeAnyCall() throws Exception {
		try (ServerSocket taken = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			String[] args = replace(partB("s6-port", null), "--exchange-port=", String.valueOf(taken.getLocalPort()));

			assertThat(run("dev,cli", args)).isOne();
		}
		assertThat(errors()).contains("the exchange cannot listen on port");
		assertThat(this.github.seen).isEmpty();
	}

	@Test
	void partAChecksTheSourceAppAndTheCustomersFileWithNarrowedRevokedTokens() throws Exception {
		int exit = run("dev,cli", partA());

		String output = output();
		assertThat(exit).as(output + errors()).isZero();
		assertThat(output).contains("slug=vibefix-dev-source permissions={actions=write, contents=read, metadata=read} events=[]",
				"repository_selection=selected repositories=[" + SpikeS6Stub.SOURCE + "]", "private=true visibility=private",
				"default_branch=main head=" + shortSha(this.github.head(SpikeS6Stub.SOURCE, "main")), "permission=admin",
				"refused, as a Contents-read token must be",
				".github/workflows/vibefix.yml on main blob=" + shortSha(publishedBlob()) + ", the published file's",
				"Spike S6 part a: every check passed or was skipped");
		assertThat(output.lines().filter(line -> line.contains(" FAIL "))).isEmpty();
		long source = this.github.repository(SpikeS6Stub.SOURCE).id;
		List<Grant> grants = grantsOf(SpikeS6Stub.SOURCE_INSTALLATION);
		assertThat(grants).allSatisfy(grant -> assertThat(grant.repositoryIds().equals(Set.of(source))
				|| grant.permissions().equals(Map.of("metadata", "read"))).as(grant.toString()).isTrue());
		assertThat(grants.stream().filter(grant -> grant.permissions().containsKey("contents"))).hasSize(3)
			.allSatisfy(grant -> assertThat(grant.repositoryIds()).containsExactly(source));
		assertThat(this.github.repository(SpikeS6Stub.SOURCE).branches).as("the refused ref was not created")
			.containsOnlyKeys("main");
		assertEveryTokenRevokedAndNeverPrinted();
	}

	@Test
	void partAFailsACustomerFileThatIsNotThePublishedOne() throws Exception {
		this.github.commitFile(SpikeS6Stub.SOURCE, "main", SpikeS6Stub.CUSTOMER_PATH, "name: vibefix\non: push\n");

		int exit = run("dev,cli", partA());

		assertThat(exit).isOne();
		assertThat(line(output(), "customer workflow file")).contains(" FAIL ",
				"while the published file's is " + shortSha(publishedBlob()), "copies deploy/customer/vibefix.yml unchanged");
	}

	@Test
	void partBCopiesOnTheCustomersActionsWithOneExchangedTokenAndNoTokenInTheDispatch() throws Exception {
		int exit = run("dev,cli", partB("s6-test", null));

		String output = output();
		assertThat(exit).as(output + errors()).isZero();
		String head = this.github.head(SpikeS6Stub.SOURCE, "main");
		long intermediary = this.github.repository("vibefix-dev/s6-test-intermediary").id;
		long working = this.github.repository("vibefix-dev/s6-test-working").id;
		long run = onlyRun(SpikeS6Stub.SOURCE);
		assertThat(output).contains(
				"Actions on vibefix-runner only: as AD-11 needs, members_can_fork_private_repositories=false",
				"slug=vibefix-dev-platform permissions={actions=write, administration=write, contents=write, members=read,"
						+ " metadata=read, organization_administration=read, pull_requests=write, workflows=write}",
				SpikeS6Stub.RUNNER + " id=9002 private=true default_branch=main actions enabled=true; s6-copy.yml=created;"
						+ " .github/workflows/ holds [s6-copy.yml]",
				"slug=vibefix-dev-source permissions={actions=write, contents=read, metadata=read} events=[];"
						+ " repository_selection=selected repositories=[" + SpikeS6Stub.SOURCE + "]",
				".github/workflows/vibefix.yml on main blob=" + shortSha(publishedBlob()) + ", the published file's",
				"vibefix-dev/s6-test-intermediary id=", "vibefix-dev/s6-test-working id=",
				"private=true allow_forking=false, wiki, issues, projects and discussions off",
				"run ID in field workflow_run_id, run " + run + " success, token issued to run " + run + ", revoked HTTP 204",
				"logs kept (a Customer's run), read back vibefix-dev/s6-test-intermediary main=" + shortSha(head)
						+ " and vibefix-dev/s6-test-working main=" + shortSha(head),
				"run " + run + " path=.github/workflows/vibefix.yml event=workflow_dispatch"
						+ " triggering_actor=vibefix-dev-source[bot] head_sha=" + shortSha(head)
						+ "; the exchange issued one token for run " + run + ", requested for "
						+ new java.util.TreeSet<>(Set.of(intermediary, working)) + " {contents=write, workflows=write}, revoked HTTP 204",
				"vibefix-dev/s6-test-intermediary enabled=false, vibefix-dev/s6-test-working enabled=false",
				"HTTP 403  GitHub answers: " + SpikeS6Stub.FREE_RULESET, "no --tampered-ref",
				"Spike S6 part b: every check passed or was skipped");
		assertThat(output.lines().filter(line -> line.contains(" FAIL "))).isEmpty();
		assertTheTotalsAddUp(line(output, "run durations and billed minutes"), 1);
		assertThat(line(output, SpikeS6Command.SCAN_CHECK)).contains("SKIPPED", "no --scan");
		assertThat(this.github.scans).as("no scan without --scan").isEmpty();
		assertThat(errors()).as("no run failed, so no log is printed").doesNotContain("did not succeed");
		assertThat(this.github.file(SpikeS6Stub.RUNNER, "main", ".github/workflows/s6-copy.yml"))
			.isEqualTo(Files.readString(RUNNER_WORKFLOWS.resolve("s6-copy.yml"), StandardCharsets.UTF_8).replace("\r\n", "\n"));
		assertThat(this.github.head("vibefix-dev/s6-test-intermediary", "main")).isEqualTo(head);
		assertThat(this.github.head("vibefix-dev/s6-test-working", "main")).isEqualTo(head);

		assertThat(this.github.dispatches).hasSize(1);
		JsonNode dispatch = this.github.dispatches.getFirst();
		assertThat(dispatch.path("ref").asString()).isEqualTo("main");
		assertThat(dispatch.path("return_run_details").asBoolean()).isTrue();
		List<String> inputs = new ArrayList<>();
		dispatch.path("inputs").properties().forEach(input -> inputs.add(input.getKey()));
		assertThat(inputs).containsExactly("operation", "correlation", "exchange_url", "sha", "branch", "intermediary", "working");
		for (String token : this.github.tokens) {
			assertThat(dispatch.toString()).as("the dispatch holds no token").doesNotContain(token);
		}
		assertExchanged(1, new Grant(SpikeS6Stub.PLATFORM_INSTALLATION, Set.of(intermediary, working), COPY));
		assertThat(this.github.deletedLogs).as("a Customer's run logs stay").isEmpty();
		assertThat(grantsOf(SpikeS6Stub.SOURCE_INSTALLATION)).allSatisfy(grant -> assertThat(
				grant.repositoryIds().equals(Set.of(9001L)) || grant.permissions().equals(Map.of("metadata", "read")))
			.as(grant.toString())
			.isTrue());
		assertEveryTokenRevokedAndNeverPrinted();
	}

	@Test
	void aTamperedWorkflowOnAnotherRefGetsNoTokenAndMovesNothing() throws Exception {
		this.github.tamper("tampered",
				Files.readString(CUSTOMER_WORKFLOW, StandardCharsets.UTF_8) + "      # tampered: prints the token\n");

		int exit = run("dev,cli", partB("s6-tamper", "tampered"));

		String output = output();
		assertThat(exit).as(output + errors()).isZero();
		String head = this.github.head(SpikeS6Stub.SOURCE, "main");
		assertThat(line(output, "tampered workflow refused")).contains(" OK ", "dispatched on tampered:", " failure,",
				"no token issued: the exchange refused [blob]", "logs kept (a Customer's run)",
				"vibefix-dev/s6-tamper-intermediary main=" + shortSha(head), ", unchanged");
		assertThat(this.github.dispatches).hasSize(2);
		assertThat(this.github.dispatches.getLast().path("ref").asString()).isEqualTo("tampered");
		assertThat(this.github.exchanged).hasSize(2);
		assertThat(this.github.exchanged.getLast().status()).isEqualTo(403);
		assertThat(this.github.exchanged.getLast().token()).isNull();
		assertThat(errors()).contains("did not succeed; failed step 3 (Exchange the ID token)");
		assertTheTotalsAddUp(line(output, "run durations and billed minutes"), 2);
		assertEveryTokenRevokedAndNeverPrinted();
	}

	@Test
	void aTamperedCheckWhoseHeadsCannotBeReadFails() throws Exception {
		this.github.tamper("tampered",
				Files.readString(CUSTOMER_WORKFLOW, StandardCharsets.UTF_8) + "      # tampered: prints the token\n");
		this.github.refReadsFail = "vibefix-dev/s6-unread-intermediary";

		int exit = run("dev,cli", partB("s6-unread", "tampered"));

		assertThat(exit).as(output()).isOne();
		assertThat(line(output(), "tampered workflow refused")).contains(" FAIL ", "no token issued: the exchange refused [blob]",
				"vibefix-dev/s6-unread-intermediary main=unreadable", "a head could not be read")
			.doesNotContain("unchanged");
		assertEveryTokenRevokedAndNeverPrinted();
	}

	@Test
	void aCleanScanRunsOnTheCustomersActionsWithNoTokenAndCountsZero() throws Exception {
		int exit = run("dev,cli", withScan(partB("s6-clean", null)));

		String output = output();
		assertThat(exit).as(output + errors()).isZero();
		long scan = onlyScan();
		String head = this.github.head(SpikeS6Stub.SOURCE, "main");
		assertThat(SpikeS6Command.SCAN_CHECK).as("the name fits the printed column").hasSizeLessThanOrEqualTo(34);
		assertThat(line(output, SpikeS6Command.SCAN_CHECK)).startsWith("[12] " + "%-34s".formatted(SpikeS6Command.SCAN_CHECK) + " OK ")
			.contains("run ID in field workflow_run_id, run " + scan + " conclusion=success findings=0 head=" + shortSha(head)
					+ ", read back path=.github/workflows/vibefix.yml head_sha=" + shortSha(head) + " blob="
					+ shortSha(publishedBlob()) + ", the published file's, no exchange expected, " + (10_000 + scan % 1000)
					+ " ms billed=1 min (timing.billable), logs kept (a Customer's run)")
			.doesNotContain("failed at", "refused");
		assertThat(output).contains("Spike S6 part b: every check passed or was skipped");
		assertThat(this.github.dispatches).hasSize(2);
		JsonNode dispatch = this.github.dispatches.getLast();
		assertThat(dispatch.path("ref").asString()).isEqualTo("main");
		List<String> inputs = new ArrayList<>();
		dispatch.path("inputs").properties().forEach(input -> inputs.add(input.getKey()));
		assertThat(inputs).as("a scan sends no copy input").containsExactly("operation", "correlation");
		assertThat(dispatch.path("inputs").path("operation").asString()).isEqualTo("scan");
		assertThat(logReads(scan)).as("the logs, read once").isOne();
		assertTheScanNeverReachedTheExchangeAndKeptItsLogs(scan);
		assertEveryTokenRevokedAndNeverPrinted();
	}

	@Test
	void aScanWithFindingsFailsItsRunAndTheCheckPrintsOnlyTheirCount() throws Exception {
		this.github.scanFindings = 3;

		int exit = run("dev,cli", withScan(partB("s6-found", null)));

		String output = output();
		assertThat(exit).as(output + errors()).isZero();
		long scan = onlyScan();
		assertThat(line(output, SpikeS6Command.SCAN_CHECK)).contains(" OK ",
				"run " + scan + " conclusion=failure findings=3 head=" + shortSha(this.github.head(SpikeS6Stub.SOURCE, "main")),
				", the published file's, no exchange expected,", "billed=1 min", "failed at step 4 (Scan with gitleaks)");
		assertThat(output + errors()).as("no finding's file or rule, and no line of the scan's logs")
			.doesNotContain(SpikeS6Stub.FINDING_FILE, SpikeS6Stub.FINDING_RULE, "did not succeed", "gitleaks found", "  | ");
		assertTheScanNeverReachedTheExchangeAndKeptItsLogs(scan);
		assertEveryTokenRevokedAndNeverPrinted();
	}

	@Test
	void aScanWithoutItsCountLineFails() throws Exception {
		// A gitleaks download whose sha256 differs stops the run before the scan, with no count line.
		this.github.scanLineMissing = true;

		int exit = run("dev,cli", withScan(partB("s6-nocount", null)));

		String output = output();
		assertThat(exit).as(output).isOne();
		long scan = onlyScan();
		assertThat(line(output, SpikeS6Command.SCAN_CHECK)).contains(" FAIL ",
				"run " + scan + " conclusion=failure findings=- head=-, read back", ", no exchange expected,",
				"failed at step 3 (Download gitleaks), no VIBEFIX_SCAN line in the run's logs");
		assertThat(output.lines().filter(line -> line.contains(" FAIL "))).as("only the scan failed").hasSize(1);
		assertThat(errors()).as("no line of the scan's logs").doesNotContain("did not succeed", "checksum");
		assertThat(logReads(scan)).as("read once more after a poll").isEqualTo(2);
		assertTheScanNeverReachedTheExchangeAndKeptItsLogs(scan);
		assertEveryTokenRevokedAndNeverPrinted();
	}

	@ParameterizedTest(name = "the count line written after {0} read(s): {1}")
	@CsvSource({ "1, OK", "2, FAIL" })
	void aScanWhoseLogsLackTheCountLineIsReadOnceMoreAfterAPoll(int readsWithoutLine, String outcome) throws Exception {
		this.github.scanLogsWithoutLine = readsWithoutLine;

		int exit = run("dev,cli", withScan(partB("s6-late", null)));

		assertThat(exit).as(output()).isEqualTo(outcome.equals("OK") ? 0 : 1);
		long scan = onlyScan();
		assertThat(line(output(), SpikeS6Command.SCAN_CHECK)).contains(" " + outcome + " ", outcome.equals("OK")
				? "conclusion=success findings=0 head=" + shortSha(this.github.head(SpikeS6Stub.SOURCE, "main"))
				: "no VIBEFIX_SCAN line in the run's logs");
		assertThat(logReads(scan)).as("read once more after a poll, and no more").isEqualTo(2);
		assertEveryTokenRevokedAndNeverPrinted();
	}

	@ParameterizedTest
	@EnumSource(value = SpikeS6Stub.CountLines.class, names = { "TWICE_IN_ONE_FILE", "ZERO_AND_THREE" })
	void aScanWhoseLogsHoldMoreThanOneCountLineFails(SpikeS6Stub.CountLines lines) throws Exception {
		this.github.countLines = lines;
		this.github.scanFindings = 3;

		int exit = run("dev,cli", withScan(partB("s6-repeat", null)));

		assertThat(exit).as(output()).isOne();
		long scan = onlyScan();
		assertThat(line(output(), SpikeS6Command.SCAN_CHECK)).contains(" FAIL ", "findings=- head=-",
				"more than one VIBEFIX_SCAN line in the run's logs");
		assertThat(logReads(scan)).as("a definite answer: no second read").isOne();
		assertEveryTokenRevokedAndNeverPrinted();
	}

	@Test
	void aScanRunOfAnotherVibefixYmlFails() throws Exception {
		// A changed vibefix.yml pushed after check 3 and before the scan: its run is not the published file's.
		String changed = Files.readString(CUSTOMER_WORKFLOW, StandardCharsets.UTF_8).replace("\r\n", "\n")
				+ "# changed after check 3\n";
		this.github.pushedBeforeScan = changed;

		int exit = run("dev,cli", withScan(partB("s6-pushed", null)));

		String output = output();
		assertThat(exit).as(output).isOne();
		String head = this.github.head(SpikeS6Stub.SOURCE, "main");
		assertThat(line(output, SpikeS6Command.SCAN_CHECK)).contains(" FAIL ",
				"conclusion=success findings=0 head=" + shortSha(head) + ", read back path=.github/workflows/vibefix.yml head_sha="
						+ shortSha(head) + " blob=" + shortSha(OidcExchange.blobSha(changed)) + " while the published file's is "
						+ shortSha(publishedBlob()));
		assertThat(output.lines().filter(line -> line.contains(" FAIL "))).as("only the scan failed").hasSize(1);
		assertEveryTokenRevokedAndNeverPrinted();
	}

	@ParameterizedTest(name = "findings={0} with conclusion {1}")
	@CsvSource({ "0, failure", "2, success" })
	void aScanWhoseConclusionDisagreesWithItsCountFails(int findings, String conclusion) throws Exception {
		this.github.scanFindings = findings;
		this.github.scanConclusion = conclusion;

		int exit = run("dev,cli", withScan(partB("s6-odd", null)));

		assertThat(exit).as(output()).isOne();
		assertThat(line(output(), SpikeS6Command.SCAN_CHECK)).contains(" FAIL ",
				"conclusion=" + conclusion + " findings=" + findings,
				"a conclusion that does not match the count (success at 0, failure above 0)");
		assertEveryTokenRevokedAndNeverPrinted();
	}

	@Test
	void aScanWaitsForTheCustomersSide() throws Exception {
		this.github.sourceAccepted = false;

		int exit = run("dev,cli", withScan(partB("s6-wait", null)));

		assertThat(exit).as(output()).isOne();
		assertThat(line(output(), SpikeS6Command.SCAN_CHECK)).contains("SKIPPED", "an earlier check failed");
		assertThat(this.github.dispatches).isEmpty();
		assertThat(this.github.scans).isEmpty();
	}

	@Test
	void aScanNeedsOnlyTheCustomersSide() throws Exception {
		// The organization lets every repository run Actions: check 1 fails, nothing is created, and the scan runs.
		this.github.actionsEnabled = "all";

		int exit = run("dev,cli", withScan(partB("s6-only", null)));

		String output = output();
		assertThat(exit).as(output).isOne();
		assertThat(line(output, "organization settings")).contains(" FAIL ");
		assertThat(line(output, "Source App and customer file")).contains(" OK ");
		assertThat(line(output, "J7 copy on Customer's Actions")).contains("SKIPPED");
		assertThat(line(output, SpikeS6Command.SCAN_CHECK)).contains(" OK ", "conclusion=success findings=0 head=");
		assertThat(this.github.dispatches).singleElement()
			.satisfies(dispatch -> assertThat(dispatch.path("inputs").path("operation").asString()).isEqualTo("scan"));
		assertThat(this.github.idTokens).isEmpty();
		assertThat(this.github.exchanged).isEmpty();
		assertThat(this.github.repository("vibefix-dev/s6-only-intermediary")).as("nothing is created").isNull();
		assertEveryTokenRevokedAndNeverPrinted();
	}

	@Test
	void aScanWhoseWorkflowRunsTheExchangeStepGetsNoTokenAndFails() throws Exception {
		// A vibefix.yml whose copy job runs for every operation, added as the published file: the stand-in reads the
		// jobs' if:s from the file (not the scripts), so this scan asks for an ID token, which no scan of the
		// published file does. Since Story 23.5 the copy is a job of its own, and its exchange step has no if:.
		String published = Files.readString(CUSTOMER_WORKFLOW, StandardCharsets.UTF_8).replace("\r\n", "\n");
		String exchanging = published.replaceAll("(?m)^ +if: inputs\\.operation == 'copy'\n", "");
		assertThat(published.split("if: inputs\\.operation == 'copy'\n", -1)).as("the copy job's if:").hasSize(2);
		assertThat(exchanging).as("gone").doesNotContain("if: inputs.operation == 'copy'\n");
		Path file = Files.writeString(this.directory.resolve("exchanging-vibefix.yml"), exchanging);
		this.github.commitFile(SpikeS6Stub.SOURCE, "main", SpikeS6Stub.CUSTOMER_PATH, exchanging);

		int exit = run("dev,cli", withScan(replace(partB("s6-asks", null), "--customer-workflow=", file.toString())));

		String output = output();
		assertThat(exit).as(output).isOne();
		long scan = onlyScan();
		assertThat(line(output, "J7 copy on Customer's Actions")).contains(" OK ");
		assertThat(line(output, SpikeS6Command.SCAN_CHECK)).contains(" FAIL ", "conclusion=failure findings=- head=-",
				"failed at step 3 (Exchange the ID token), no VIBEFIX_SCAN line in the run's logs");
		assertThat(this.github.idTokens).as("the J7 copy's and the scan's").hasSize(2);
		// With no exchange_url, the scan's post reaches no exchange, and it is issued nothing.
		assertThat(this.github.exchanged.stream().filter(answer -> answer.runId() == scan)).singleElement()
			.satisfies(answer -> {
				assertThat(answer.status()).isNotEqualTo(200);
				assertThat(answer.token()).isNull();
			});
		assertThat(grantsOf(SpikeS6Stub.PLATFORM_INSTALLATION))
			.filteredOn(grant -> grant.permissions().equals(COPY) && grant.repositoryIds().size() == 2)
			.hasSize(1);
		assertEveryTokenRevokedAndNeverPrinted();
	}

	@ParameterizedTest(name = "{0} is refused as {1}")
	@CsvSource({ "SIGNATURE, signature", "AUDIENCE, audience", "EXPIRED, expired", "ISSUER, issuer",
			"SELF_HOSTED, runner", "FOREIGN_ACTOR, actor", "FOREIGN_TITLE, title" })
	void anIdTokenOrRunThatIsNotGenuineGetsNoTokenAndNothingMoves(Flaw flaw, String reason) throws Exception {
		this.github.flaw = flaw;

		int exit = run("dev,cli", partB("s6-flaw", null));

		String output = output();
		assertThat(exit).as(output).isOne();
		// An unverified token names no correlation for sure: the exchange only counts it.
		boolean unverified = Set.of(Flaw.SIGNATURE, Flaw.AUDIENCE, Flaw.EXPIRED, Flaw.ISSUER).contains(flaw);
		assertThat(line(output, "J7 copy on Customer's Actions")).contains(" FAIL ", " failure,", unverified
				? "no token issued: no verified attempt named the run's correlation; 1 attempt(s) refused meanwhile"
				: "no token issued: the exchange refused [" + reason + "]");
		assertThat(line(output, "J7 run verified")).contains("SKIPPED");
		assertThat(this.github.exchanged).singleElement().satisfies(answer -> {
			assertThat(answer.status()).isEqualTo(403);
			assertThat(answer.reason()).isEqualTo(reason);
			assertThat(answer.token()).isNull();
		});
		assertThat(this.github.repository("vibefix-dev/s6-flaw-intermediary").branches).as("nothing was pushed").isEmpty();
		assertThat(this.github.repository("vibefix-dev/s6-flaw-working").branches).isEmpty();
		assertThat(grantsOf(SpikeS6Stub.PLATFORM_INSTALLATION)).as("no Platform token with write on both").noneMatch(
				grant -> grant.permissions().equals(COPY) && grant.repositoryIds().size() == 2);
		assertEveryTokenRevokedAndNeverPrinted();
	}

	@Test
	void aRunNamingACorrelationNeverDispatchedGetsNoToken() throws Exception {
		this.github.flaw = Flaw.UNKNOWN_CORRELATION;

		int exit = run("dev,cli", partB("s6-other", null));

		assertThat(exit).as(output()).isOne();
		assertThat(line(output(), "J7 copy on Customer's Actions")).contains(" FAIL ",
				"no token issued: no verified attempt named the run's correlation; 1 attempt(s) refused meanwhile");
		assertThat(this.github.exchanged).singleElement().satisfies(answer -> assertThat(answer.reason()).isEqualTo("correlation"));
		assertThat(this.github.repository("vibefix-dev/s6-other-intermediary").branches).isEmpty();
		assertEveryTokenRevokedAndNeverPrinted();
	}

	@Test
	void aReplayedIdTokenIsRefusedAndTheRunKeepsItsOneToken() throws Exception {
		this.github.flaw = Flaw.REPLAY;

		int exit = run("dev,cli", partB("s6-replay", null));

		assertThat(exit).as(output() + errors()).isZero();
		assertThat(line(output(), "J7 run verified")).contains(" OK ", "other attempts refused [replay]");
		assertThat(this.github.exchanged).extracting(Exchanged::status).containsExactly(200, 403);
		assertThat(this.github.exchanged.getLast().reason()).isEqualTo("replay");
		assertExchanged(1, null);
		assertEveryTokenRevokedAndNeverPrinted();
	}

	@Test
	void aDispatchAnswering204IsFoundByItsRunName() throws Exception {
		this.github.dispatchStatus = 204;

		int exit = run("dev,cli", partB("s6-old", null));

		assertThat(exit).as(output()).isZero();
		assertThat(line(output(), "J7 copy on Customer's Actions")).contains("204, found by run-name",
				"token issued to run");
		assertThat(this.github.seen.stream()
			.filter(request -> request.path().endsWith("/actions/workflows/" + SpikeS6Command.CUSTOMER_WORKFLOW + "/runs")))
			.isNotEmpty();
		assertEveryTokenRevokedAndNeverPrinted();
	}

	@Test
	void aDispatchRefusingReturnRunDetailsIsSentAgainWithoutIt() throws Exception {
		this.github.refuseReturnRunDetails = true;

		int exit = run("dev,cli", partB("s6-plain", null));

		assertThat(exit).as(output()).isZero();
		assertThat(line(output(), "J7 copy on Customer's Actions")).contains(" OK ",
				"return_run_details refused (HTTP 422), 204, found by run-name");
		assertThat(this.github.dispatches).hasSize(1)
			.allSatisfy(dispatch -> assertThat(dispatch.has("return_run_details")).isFalse());
		assertEveryTokenRevokedAndNeverPrinted();
	}

	@Test
	void aDispatchAnswering404IsTriedAgainWhileGitHubLearnsTheWorkflow() throws Exception {
		this.github.dispatchNotFound = 2;

		int exit = run("dev,cli", partB("s6-new", null));

		assertThat(exit).as(output()).isZero();
		assertThat(line(output(), "J7 copy on Customer's Actions")).contains(" OK ",
				"dispatched after 2 retries on 404, run ID in field workflow_run_id");
		assertThat(this.github.seen.stream().filter(request -> request.path().endsWith("/dispatches"))).hasSize(3);
		assertEveryTokenRevokedAndNeverPrinted();
	}

	@Test
	void aRunNotCompletedInTimeIsCancelledAndItsTokenRevoked() throws Exception {
		this.github.neverCompletes = true;

		int exit = run("dev,cli", partB("s6-slow", null));

		String output = output();
		assertThat(exit).as(output).isOne();
		long run = onlyRun(SpikeS6Stub.SOURCE);
		assertThat(line(output, "J7 copy on Customer's Actions")).contains(" FAIL ", "token issued to run " + run
				+ ", revoked HTTP 204", "not completed in", "cancel HTTP 202; its logs stay: https://github.com/"
						+ SpikeS6Stub.SOURCE + "/actions/runs/" + run);
		assertThat(line(output, "run durations and billed minutes")).contains("SKIPPED", "no run completed");
		assertThat(this.github.cancelled).containsExactly(run);
		assertThat(this.github.deletedLogs).isEmpty();
		assertEveryTokenRevokedAndNeverPrinted();
	}

	@ParameterizedTest
	@ValueSource(strings = { "an installation token", "a stateless installation token", "a JWT" })
	void aFailedRunPrintsItsFailedStepsLastLinesWithAnythingTokenLikeCutOut(String kind) throws Exception {
		this.github.failAtStep = "push";
		// Built at run time, as fake credentials. The stateless one is GitHub's format since 2026:
		// ghs_<App ID>_<JWT>, about 520 characters, its JWT's parts joined by dots and holding dashes.
		String leak = switch (kind) {
			case "a JWT" -> String.join(".", "eyJ" + "hbGciOiJSUzI1NiJ9", "eyJ" + "zdWIiOiJyZXBvIn0", "c2lnbmF0" + "dXJlX18");
			case "a stateless installation token" -> String.join("", "ghs", "_", "5699338", "_",
					String.join(".", "eyJ" + "hbGciOiJSUzI1NiJ9", "eyJ" + "zdWIiOiJyZXBvIn0" + "B".repeat(400),
							"c2lnbmF0" + "dXJl-X18" + "z".repeat(60)));
			default -> String.join("", "ghs", "_", "leaked", "q".repeat(30));
		};
		this.github.logLeak = leak;

		int exit = run("dev,cli", partB("s6-fail", null));

		assertThat(exit).as(output()).isOne();
		assertThat(line(output(), "J7 copy on Customer's Actions")).contains("run ID in field workflow_run_id", " failure,",
				"token issued to run", "revoked HTTP 204", "logs kept (a Customer's run)");
		String errors = errors();
		assertThat(errors).contains("did not succeed; failed step 4 (Copy the commit), the last 40 lines of copy/4_Copy the"
				+ " commit.txt");
		List<String> tail = errors.lines().filter(line -> line.startsWith("  | ")).toList();
		assertThat(tail).hasSize(SpikeS6Command.LOG_LINES);
		assertThat(tail.getFirst()).endsWith(" log line " + (SpikeS6Stub.LOG_LINES - SpikeS6Command.LOG_LINES + 1));
		assertThat(tail.getLast()).endsWith("error: failed to push some refs (log line " + SpikeS6Stub.LOG_LINES + ")");
		assertThat(errors).contains("remote: credential <redacted> refused").doesNotContain(leak);
		for (String part : leak.split("[._-]")) {
			if (part.length() >= 12) {
				assertThat(errors).as("no part of the token is left over").doesNotContain(part);
			}
		}
		assertThat(this.github.logAuthorizations).as("the logs' storage never sees the token").containsExactly("none");
		assertThat(this.github.deletedLogs).as("a Customer's run logs stay, even after a failure").isEmpty();
		assertEveryTokenRevokedAndNeverPrinted();
	}

	@ParameterizedTest
	@ValueSource(strings = { "master", "public", "another workflow", "no Actions write", "Actions write not accepted" })
	void aRunnerOrPlatformAppTheDesignDoesNotAllowGetsNoRepositoryAndNoDispatch(String flaw) throws Exception {
		SpikeS6Stub.Repo runner = this.github.repository(SpikeS6Stub.RUNNER);
		switch (flaw) {
			case "master" -> runner.defaultBranch = "master";
			case "public" -> runner.privateRepository = false;
			case "another workflow" -> this.github.commitFile(SpikeS6Stub.RUNNER, "main", ".github/workflows/other.yml", "on: push\n");
			case "no Actions write" -> this.github.platformActions = false;
			default -> this.github.platformAccepted = false;
		}

		int exit = run("dev,cli", partB("s6-guard", null));

		String output = output();
		assertThat(exit).as(output).isOne();
		assertThat(line(output, "Platform App and runner")).contains(" FAIL ", switch (flaw) {
			case "another workflow" -> "nothing written: .github/workflows/ already holds [other.yml]; it must hold [s6-copy.yml] only";
			case "no Actions write" -> "nothing written: the Platform App must hold AD-10's permissions and actions=write";
			case "Actions write not accepted" -> "nothing written: the Platform App's installation has not accepted actions write";
			default -> "nothing written: the runner must be private, with the default branch main";
		});
		assertThat(this.github.file(SpikeS6Stub.RUNNER, "main", ".github/workflows/s6-copy.yml")).as("nothing was written")
			.isNull();
		for (String later : List.of("create private Intermediary", "create private Working", "J7 copy on Customer's Actions")) {
			assertThat(line(output, later)).as(later).contains("SKIPPED");
		}
		assertThat(this.github.dispatches).isEmpty();
		assertThat(this.github.repository("vibefix-dev/s6-guard-intermediary")).as("nothing is created").isNull();
		assertEveryTokenRevokedAndNeverPrinted();
	}

	@ParameterizedTest
	@ValueSource(strings = { "Actions write not accepted", "a changed vibefix.yml" })
	void aCustomerSideTheDesignDoesNotAllowGetsNoRepositoryAndNoDispatch(String flaw) throws Exception {
		if (flaw.equals("a changed vibefix.yml")) {
			this.github.commitFile(SpikeS6Stub.SOURCE, "main", SpikeS6Stub.CUSTOMER_PATH,
					Files.readString(CUSTOMER_WORKFLOW, StandardCharsets.UTF_8) + "      # changed\n");
		}
		else {
			this.github.sourceAccepted = false;
		}

		int exit = run("dev,cli", partB("s6-side", null));

		String output = output();
		assertThat(exit).as(output).isOne();
		assertThat(line(output, "Source App and customer file")).contains(" FAIL ", flaw.equals("a changed vibefix.yml")
				? "while the published file's is " + shortSha(publishedBlob())
				: "the Source App's installation has not accepted actions write");
		for (String later : List.of("create private Intermediary", "create private Working", "J7 copy on Customer's Actions")) {
			assertThat(line(output, later)).as(later).contains("SKIPPED");
		}
		assertThat(this.github.repository("vibefix-dev/s6-side-intermediary")).as("nothing is created").isNull();
		assertThat(this.github.dispatches).isEmpty();
		assertEveryTokenRevokedAndNeverPrinted();
	}

	@Test
	void plainHttpOnLoopbackIsRefusedAgainstGitHubsIssuer() throws Exception {
		MockEnvironment environment = new MockEnvironment();
		environment.setActiveProfiles("dev", "cli");
		environment.setProperty("vibefix.cli.command", SpikeS6Command.COMMAND);
		SpikeS6Command command = new SpikeS6Command(this.apps, this.repositories, environment, CLOCK, SpikeS6Command.Waits.GITHUB,
				new SpikeS6Command.Listening(OidcExchange.Issuer.GITHUB, InetAddress.getLoopbackAddress()),
				new PrintStream(this.out, true, StandardCharsets.UTF_8), new PrintStream(this.err, true, StandardCharsets.UTF_8));

		command.run(new DefaultApplicationArguments(partB("s6-http", null)));

		assertThat(command.getExitCode()).as("GitHub's runs need https: the workflow would refuse it").isEqualTo(2);
		assertThat(errors()).contains("Usage");
		assertThat(this.github.seen).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = { "another repository too", "none", "all" })
	void anOrganizationWhoseActionsRunAnywhereButTheRunnerAloneGetsNoRepository(String policy) throws Exception {
		switch (policy) {
			case "another repository too" -> this.github.actionsRepositories.add("s6-other");
			default -> this.github.actionsEnabled = policy;
		}

		int exit = run("dev,cli", partB("s6-org", null));

		String output = output();
		assertThat(exit).as(output).isOne();
		assertThat(line(output, "organization settings")).contains(" FAIL ", "instead of the runner vibefix-runner only");
		assertThat(line(output, "create private Intermediary")).contains("SKIPPED");
		assertThat(this.github.repository("vibefix-dev/s6-org-intermediary")).isNull();
		assertThat(this.github.dispatches).isEmpty();
	}

	@Test
	void aRulesetAnswerThatNamesNoPlanFails() throws Exception {
		this.github.rulesetMessage = "Resource not accessible by integration";

		int exit = run("dev,cli", partB("s6-rule", null));

		assertThat(exit).as(output()).isOne();
		assertThat(line(output(), "ruleset on private Intermediary")).contains(" FAIL ",
				"HTTP 403  an answer that names no plan: Resource not accessible by integration");
	}

	@Test
	void durationsAndBilledMinutesFallBackToTheRunAndItsJobs() throws Exception {
		this.github.timingWithoutDuration = true;
		this.github.timingWithoutBillable = true;

		int exit = run("dev,cli", partB("s6-jobs", null));

		String output = output();
		assertThat(exit).as(output).isZero();
		// The stand-in's run goes from 03:00:00 to 03:00:42, and its job from 03:00:00 to 03:01:05.
		assertThat(line(output, "run durations and billed minutes")).contains("j7-copy=42000 ms billed=2 min (jobs)",
				"total=42000 ms billed=2 min");
		assertTheTotalsAddUp(line(output, "run durations and billed minutes"), 1);
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
	void partCWaitsForTheInvitationHandsOverTwiceOnTheRunnerAndBringsTheFinalMergeBack() throws Exception {
		assertThat(run("dev,cli", partB("s6-hand", null))).as(output() + errors()).isZero();
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
		String merge = intermediary.branches.get("main").getLast();
		assertThat(output).contains("is a collaborator", "Intermediary permission=read role_name=read; Working HTTP 404",
				"H1=" + shortSha(h1), "H2=" + shortSha(h2) + " on vibefix-dev/s6-hand-working/main, parent " + shortSha(h1) + " (H1)",
				"logs deleted HTTP 204, read back vibefix-dev/s6-hand-intermediary handover/s6-hand-1=" + shortSha(h1)
						+ ", pull request #1",
				"read back vibefix-dev/s6-hand-intermediary handover/s6-hand-2=" + shortSha(h2) + ", pull request #2",
				"#2 merge_method=merge sha=" + shortSha(h2) + ": merged=true commit=" + shortSha(merge),
				"after #2 merged, #1 state=open merged=false; commented HTTP 201, closed HTTP 200, read back state=closed",
				"logs kept (a Customer's run), read back with the Source App " + SpikeS6Stub.SOURCE + " vibefix/final-s6-hand="
						+ shortSha(merge) + ", the Intermediary's merge commit",
				"Spike S6 part c: every check passed or was skipped");
		assertThat(output.lines().filter(line -> line.contains(" FAIL "))).isEmpty();
		assertTheTotalsAddUp(line(output, "run durations and billed minutes"), 3);
		assertThat(intermediary.pulls.get(2).merged).isTrue();
		assertThat(intermediary.pulls.get(1).state).isEqualTo("closed");
		assertThat(this.github.comments).containsExactly("vibefix-dev/s6-hand-intermediary#1");
		assertThat(intermediary.branches.get("main")).contains(h1, h2);
		assertThat(this.github.head(SpikeS6Stub.SOURCE, "vibefix/final-s6-hand")).isEqualTo(merge);

		assertThat(this.github.dispatches).hasSize(4);
		List<JsonNode> handovers = this.github.dispatches.subList(1, 3);
		for (JsonNode handover : handovers) {
			assertThat(handover.path("ref").asString()).isEqualTo("main");
			List<String> inputs = new ArrayList<>();
			handover.path("inputs").properties().forEach(input -> inputs.add(input.getKey()));
			assertThat(inputs).containsExactly("correlation", "exchange_url", "source_repository", "source_branch", "sha",
					"target_repository", "target_branch");
		}
		JsonNode finalMerge = this.github.dispatches.getLast().path("inputs");
		assertThat(finalMerge.path("operation").asString()).isEqualTo("final-merge");
		assertThat(finalMerge.has("working")).isFalse();
		List<Exchanged> issued = this.github.exchanged.stream().filter(answer -> answer.status() == 200).toList();
		assertThat(issued).hasSize(4);
		Grant handover = new Grant(SpikeS6Stub.PLATFORM_INSTALLATION, Set.of(working.id, intermediary.id), COPY);
		assertThat(this.github.grants.get(issued.get(1).token())).isEqualTo(handover);
		assertThat(this.github.grants.get(issued.get(2).token())).isEqualTo(handover);
		assertThat(this.github.grants.get(issued.get(3).token()))
			.isEqualTo(new Grant(SpikeS6Stub.PLATFORM_INSTALLATION, Set.of(intermediary.id), Map.of("contents", "read")));
		Map<Long, String> runs = this.github.runRepositories();
		assertThat(this.github.deletedLogs).as("only the runner's run logs are deleted")
			.containsExactlyElementsOf(runs.entrySet().stream().filter(run -> run.getValue().equals(SpikeS6Stub.RUNNER))
				.map(Map.Entry::getKey)
				.toList())
			.hasSize(2);
		assertRevokedAfterTheirRuns();
		assertEveryTokenRevokedAndNeverPrinted();
	}

	@Test
	void aRunnerLogDeletionGitHubRefusesFailsTheHandover() throws Exception {
		assertThat(run("dev,cli", partB("s6-logs", null))).isZero();
		assertThat(run("dev,cli", partC("s6-logs"))).isZero();
		this.github.accept(SpikeS6Stub.CUSTOMER);
		this.github.logsDeleteStatus = 403;
		this.out.reset();

		int exit = run("dev,cli", partC("s6-logs"));

		assertThat(exit).as(output()).isOne();
		assertThat(line(output(), "copy H1 to handover, pull request")).contains(" FAIL ", "token issued to run",
				"logs deleted HTTP 403, cleanup failed");
		assertThat(line(output(), "commit H2, a child of H1")).contains("SKIPPED");
		assertThat(this.github.deletedLogs).isEmpty();
		assertEveryTokenRevokedAndNeverPrinted();
	}

	@Test
	void partCRefusesAPrefixWhoseHandoverBranchExists() throws Exception {
		assertThat(run("dev,cli", partB("s6-twice", null))).isZero();
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
		assertThat(line(output(), "Final Merge on Customer's Actions")).contains("SKIPPED");
		assertThat(this.github.dispatches).as("nothing is dispatched").hasSize(dispatches);
		assertThat(this.github.repository("vibefix-dev/s6-twice-working").branches.get("main"))
			.as("nothing is committed")
			.isEqualTo(intermediary.branches.get("main"));
	}

	@Test
	void aFirstPullRequestGitHubMarkedMergedIsLeftAsItIs() throws Exception {
		this.github.containedPullsMarkedMerged = true;
		assertThat(run("dev,cli", partB("s6-auto", null))).isZero();
		assertThat(run("dev,cli", partC("s6-auto"))).isZero();
		this.github.accept(SpikeS6Stub.CUSTOMER);
		this.out.reset();

		int exit = run("dev,cli", partC("s6-auto"));

		assertThat(exit).as(output()).isZero();
		assertThat(output()).contains("after #2 merged, #1 state=closed merged=true").doesNotContain("commented");
		assertThat(this.github.comments).isEmpty();
	}

	@Test
	void aPullRequestRefusedToAPullRequestsOnlyTokenIsOpenedWithContentsReadAdded() throws Exception {
		this.github.pullsNeedContents = true;
		assertThat(run("dev,cli", partB("s6-blind", null))).isZero();
		assertThat(run("dev,cli", partC("s6-blind"))).isZero();
		this.github.accept(SpikeS6Stub.CUSTOMER);
		this.out.reset();

		int exit = run("dev,cli", partC("s6-blind"));

		String output = output();
		assertThat(exit).as(output + errors()).isZero();
		for (int n = 1; n <= 2; n++) {
			assertThat(line(output, "copy H" + n + " to handover, pull request")).contains(" OK ",
					"pull_requests write alone: HTTP 422 Validation Failed [PullRequest.head invalid], again after 5 ms:"
							+ " HTTP 422 Validation Failed [PullRequest.head invalid], with contents read added: HTTP 201,"
							+ " pull request #" + n);
		}
		assertThat(output).contains("Spike S6 part c: every check passed or was skipped");
		SpikeS6Stub.Repo intermediary = this.github.repository("vibefix-dev/s6-blind-intermediary");
		assertThat(this.github.grants.values()).contains(new Grant(SpikeS6Stub.PLATFORM_INSTALLATION,
				Set.of(intermediary.id), Map.of("contents", "read", "pull_requests", "write")));
		assertThat(intermediary.pulls.get(2).merged).isTrue();
		assertEveryTokenRevokedAndNeverPrinted();
	}

	@Test
	void aPullRequestRefusedOnceIsOpenedOnTheSecondTryAsItWas() throws Exception {
		assertThat(run("dev,cli", partB("s6-late", null))).isZero();
		assertThat(run("dev,cli", partC("s6-late"))).isZero();
		this.github.accept(SpikeS6Stub.CUSTOMER);
		this.github.pullsRefused.set(1);
		this.out.reset();

		int exit = run("dev,cli", partC("s6-late"));

		String output = output();
		assertThat(exit).as(output + errors()).isZero();
		assertThat(line(output, "copy H1 to handover, pull request")).contains(" OK ",
				"pull_requests write alone: HTTP 422 Validation Failed [PullRequest.head invalid], again after 5 ms: HTTP 201,"
						+ " pull request #1")
			.doesNotContain("contents read added");
		assertThat(line(output, "copy H2 to handover, pull request")).contains(" OK ", ", pull request #2")
			.doesNotContain("HTTP 422");
		SpikeS6Stub.Repo intermediary = this.github.repository("vibefix-dev/s6-late-intermediary");
		assertThat(this.github.grants.values()).doesNotContain(new Grant(SpikeS6Stub.PLATFORM_INSTALLATION,
				Set.of(intermediary.id), Map.of("contents", "read", "pull_requests", "write")));
	}

	@Test
	void aPullRequestRefusedThriceFailsTheHandoverWithEachAnswer() throws Exception {
		assertThat(run("dev,cli", partB("s6-shut", null))).isZero();
		assertThat(run("dev,cli", partC("s6-shut"))).isZero();
		this.github.accept(SpikeS6Stub.CUSTOMER);
		this.github.pullsRefused.set(3);
		this.out.reset();

		int exit = run("dev,cli", partC("s6-shut"));

		assertThat(exit).as(output()).isOne();
		assertThat(line(output(), "copy H1 to handover, pull request")).contains(" FAIL ", "HTTP 422",
				"pull_requests write alone: HTTP 422 Validation Failed [PullRequest.head invalid], again after 5 ms: HTTP 422"
						+ " Validation Failed [PullRequest.head invalid], with contents read added: HTTP 422, pull request refused:"
						+ " Validation Failed [PullRequest.head invalid]");
		assertThat(line(output(), "commit H2, a child of H1")).contains("SKIPPED");
		assertEveryTokenRevokedAndNeverPrinted();
	}

	/**
	 * The {@code n}th token the exchange issued is narrowed as expected ({@code null}: to the J7 copy's two
	 * repositories), and each one was revoked only after its run completed.
	 */
	private void assertExchanged(int count, Grant expected) {
		List<Exchanged> issued = this.github.exchanged.stream().filter(answer -> answer.status() == 200).toList();
		assertThat(issued).hasSize(count);
		for (Exchanged answer : issued) {
			assertThat(this.github.tokens).contains(answer.token());
			if (expected != null) {
				assertThat(this.github.grants.get(answer.token())).isEqualTo(expected);
			}
			else {
				assertThat(this.github.grants.get(answer.token()).permissions()).isEqualTo(COPY);
				assertThat(this.github.grants.get(answer.token()).repositoryIds()).hasSize(2);
			}
		}
		assertRevokedAfterTheirRuns();
	}

	private void assertRevokedAfterTheirRuns() {
		for (Exchanged answer : this.github.exchanged) {
			if (answer.token() == null) {
				continue;
			}
			int completed = this.github.events.indexOf("completed " + answer.runId());
			assertThat(completed).as("run " + answer.runId() + " completed").isNotNegative();
			assertThat(this.github.events.indexOf("revoked " + answer.token())).as("revoked after its run completed")
				.isGreaterThan(completed);
		}
	}

	/** The durations line: each run's milliseconds and minutes add up to its totals. */
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
		for (String idToken : this.github.idTokens) {
			assertThat(output).as("no ID token is printed").doesNotContain(idToken).doesNotContain(idToken.split("\\.")[2]);
		}
		assertThat(output).doesNotContain("sig=stubsignature");
	}

	/**
	 * The scan run asked for no ID token and never reached the exchange, so no Platform token was minted for
	 * it; the probe read its logs with a Source App token narrowed to the source, Actions read, and left them.
	 */
	private void assertTheScanNeverReachedTheExchangeAndKeptItsLogs(long scan) {
		assertThat(this.github.exchanged).as("the scan never reached the exchange").noneMatch(answer -> answer.runId() == scan);
		assertThat(this.github.idTokens).as("only the J7 copy asked for an ID token").hasSize(1);
		assertThat(grantsOf(SpikeS6Stub.PLATFORM_INSTALLATION)).as("one Platform token with write on two repositories: the J7 copy's")
			.filteredOn(grant -> grant.permissions().equals(COPY) && grant.repositoryIds().size() == 2)
			.hasSize(1);
		String logs = "/repos/" + SpikeS6Stub.SOURCE + "/actions/runs/" + scan + "/logs";
		List<SpikeS6Stub.Seen> reads = this.github.seen.stream()
			.filter(request -> request.method().equals("GET") && request.path().equals(logs))
			.toList();
		Grant actionsRead = new Grant(SpikeS6Stub.SOURCE_INSTALLATION, Set.of(this.github.repository(SpikeS6Stub.SOURCE).id),
				Map.of("actions", "read"));
		assertThat(reads).as("the logs were read").isNotEmpty().allSatisfy(request -> assertThat(
				this.github.grants.get(request.authorization().substring("Bearer ".length()))).isEqualTo(actionsRead));
		assertThat(this.github.logAuthorizations).as("the logs' storage never sees a token").containsOnly("none");
		assertThat(this.github.seen).as("the Customer's logs are never deleted")
			.noneMatch(request -> request.method().equals("DELETE") && request.path().endsWith("/logs"));
		assertThat(this.github.deletedLogs).isEmpty();
	}

	/** How often the probe read the run's logs. */
	private long logReads(long run) {
		String logs = "/repos/" + SpikeS6Stub.SOURCE + "/actions/runs/" + run + "/logs";
		return this.github.seen.stream().filter(request -> request.method().equals("GET") && request.path().equals(logs)).count();
	}

	private long onlyScan() {
		assertThat(this.github.scans).hasSize(1);
		assertThat(this.github.runRepositories()).containsEntry(this.github.scans.getFirst(), SpikeS6Stub.SOURCE);
		return this.github.scans.getFirst();
	}

	private long onlyRun(String repository) {
		List<Long> runs = this.github.runRepositories().entrySet().stream()
			.filter(run -> run.getValue().equals(repository))
			.map(Map.Entry::getKey)
			.toList();
		assertThat(runs).hasSize(1);
		return runs.getFirst();
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

	private static String publishedBlob() throws IOException {
		return OidcExchange.blobSha(Files.readString(CUSTOMER_WORKFLOW, StandardCharsets.UTF_8));
	}

	private GitHubAppsClient apps(Path platformKey, String runner) {
		return new GitHubAppsClient(new GitHubProperties(this.github.apiUrl(), null, SpikeS6Stub.ORGANIZATION,
				new AppProperties(SpikeS6Stub.PLATFORM_APP, SpikeS6Stub.PLATFORM_INSTALLATION, platformKey), null, null, runner),
				CLOCK);
	}

	private String[] partA() {
		return new String[] { "--part=a", "--source=" + SpikeS6Stub.SOURCE, "--customer=" + SpikeS6Stub.CUSTOMER,
				"--source-app-id=" + SpikeS6Stub.SOURCE_APP, "--source-app-key-file=" + this.sourceKey,
				"--customer-workflow=" + CUSTOMER_WORKFLOW };
	}

	private String[] partB(String prefix, String tamperedRef) throws IOException {
		List<String> args = new ArrayList<>(List.of("--part=b", "--prefix=" + prefix, "--source=" + SpikeS6Stub.SOURCE,
				"--source-app-id=" + SpikeS6Stub.SOURCE_APP, "--source-app-key-file=" + this.sourceKey,
				"--customer-workflow=" + CUSTOMER_WORKFLOW, "--workflows-dir=" + RUNNER_WORKFLOWS));
		args.addAll(exchange());
		if (tamperedRef != null) {
			args.add("--tampered-ref=" + tamperedRef);
		}
		return args.toArray(String[]::new);
	}

	private String[] partC(String prefix) throws IOException {
		List<String> args = new ArrayList<>(List.of("--part=c", "--prefix=" + prefix, "--customer=" + SpikeS6Stub.CUSTOMER,
				"--source=" + SpikeS6Stub.SOURCE, "--source-app-id=" + SpikeS6Stub.SOURCE_APP,
				"--source-app-key-file=" + this.sourceKey, "--customer-workflow=" + CUSTOMER_WORKFLOW,
				"--workflows-dir=" + RUNNER_WORKFLOWS));
		args.addAll(exchange());
		return args.toArray(String[]::new);
	}

	/** A free loopback port for each run's exchange, and the URL its runs post to. */
	private static List<String> exchange() throws IOException {
		int port;
		try (ServerSocket free = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			port = free.getLocalPort();
		}
		return List.of("--exchange-url=http://127.0.0.1:" + port, "--exchange-port=" + port);
	}

	private static String[] withScan(String[] args) {
		List<String> scan = new ArrayList<>(List.of(args));
		scan.add("--scan");
		return scan.toArray(String[]::new);
	}

	private static String[] replace(String[] args, String option, String value) {
		return List.of(args).stream().map(arg -> arg.startsWith(option) ? option + value : arg).toArray(String[]::new);
	}

	private static String[] without(String[] args, String option) {
		return List.of(args).stream().filter(arg -> !arg.startsWith(option)).toArray(String[]::new);
	}

	private int run(String profiles, String... args) {
		MockEnvironment environment = new MockEnvironment();
		environment.setActiveProfiles(profiles.split(","));
		environment.setProperty("vibefix.cli.command", SpikeS6Command.COMMAND);
		SpikeS6Command command = new SpikeS6Command(this.apps, this.repositories, environment, CLOCK,
				new SpikeS6Command.Waits(Duration.ofMillis(5), Duration.ofMillis(300), Duration.ofMillis(5), Duration.ofMillis(100)),
				new SpikeS6Command.Listening(new OidcExchange.Issuer(this.github.issuer(), this.github.jwks()),
						InetAddress.getLoopbackAddress()),
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

	private static String shortSha(String sha) {
		return sha.substring(0, 12);
	}

}
