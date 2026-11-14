package vn.vibefix.github.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Story 23.8b (deferred-work 646, the runner half; AD-45, AD-46, SM-5): the runner's {@code s6-copy.yml}, its own
 * {@code run:} scripts taken from {@code deploy/runner/s6-copy.yml}, runs under bash as GitHub runs a step
 * ({@code bash --noprofile --norc -eo pipefail}) against local bare repositories behind {@code file://} remotes,
 * exactly as {@link CopyStepsHarnessTest} does for the Customer's copy. Stub {@code curl} and {@code jq} first on
 * {@code PATH} stand in for GitHub's ID token endpoint, vibefix's exchange and vibefix's path report. The guards
 * pass and fail as written:
 * <ul>
 * <li>every input's regular expression, and {@code target_branch}, which must be {@code handover/<uuid>};</li>
 * <li>the exchange: a refusal names its reason, and no token, an unsafe one or one too long ends the run;</li>
 * <li>the fetch: both ends of the range must be commits on the source branch, and {@code handover/<uuid>} must not
 * exist on the target yet;</li>
 * <li>the report: the whole changed-path list, NUL separated, gzipped and base64 encoded, with its own count and
 * the {@code .github/workflows} verdict, and never a path in the log; a workflow change ends the run before the
 * push, and so does a report vibefix refuses;</li>
 * <li>the push: only the Handover Commit, onto a branch of its own, never forced, read back;</li>
 * <li>each ignored fsck message, in a crafted history, copies, and fails once its ignore is removed; any other
 * object error fails the fetch with git's own words.</li>
 * </ul>
 * Skipped where bash or git is missing. On Windows, bash is Git for Windows' own, never WSL's {@code System32} one.
 */
class RunnerCopyStepsHarnessTest {

	private static final String CORRELATION = "0b7c9a52-5f0e-4f7e-9d43-2d1c9b0e6a11";

	private static final String INTERMEDIARY = "vibefix-dev/job-0b7c9a525f0e4f7e9d432d1c9b0e6a11-intermediary";

	private static final String WORKING = "vibefix-dev/job-0b7c9a525f0e4f7e9d432d1c9b0e6a11-working";

	/** The branch one handover is pushed to: {@code handover/} and the submission's UUID (AD-11, AD-46). */
	private static final String TARGET_BRANCH = "handover/3f2a1c64-9b0e-4d7a-8c51-7e6d5b4a3c21";

	private static final String EXCHANGE_URL = "https://exchange.example.test";

	private static final String ID_TOKEN_URL = "https://pipelines.example.test/idtoken?api-version=2.0";

	/** The messages the copy lets through (deferred-work 650), in the order the workflow lists them. */
	private static final List<String> IGNORED = List.of("zeroPaddedFilemode", "badDate", "badDateOverflow", "badTimezone",
			"missingSpaceBeforeDate");

	/** A well-formed author or committer line's value. */
	private static final String IDENT = "Lan <lan@example.test> 1700000000 +0700";

	/** Fake credentials, built at run time: GitHub's ID token request token and ID token, and the exchanged token. */
	private static final String REQUEST_TOKEN = "harness-request-" + UUID.randomUUID();

	private static final String ID_TOKEN = "harness-id-" + UUID.randomUUID();

	private static final String VIBEFIX_TOKEN = "harness-vibefix-" + UUID.randomUUID();

	private static final Pattern INPUT = Pattern.compile("\\$\\{\\{ inputs\\.([a-z_]+) }}");

	/** What a step's neighbour does to the fixtures between two steps. */
	private interface Between {

		void run() throws IOException;

		Between NOTHING = () -> {
		};

	}

	private static @Nullable Path bash;

	private static List<Map<String, Object>> steps = List.of();

	@TempDir
	Path temp;

	private Path remotes;

	private Path runnerTemp;

	private Path runner;

	private Path stubs;

	@BeforeAll
	static void bashAndGit() throws IOException {
		bash = findBash();
		Assumptions.assumeTrue(bash != null, "no bash to run the runner's scripts under");
		Assumptions.assumeTrue(works(List.of("git", "--version")), "no git to build the fixtures with");
		Assumptions.assumeTrue(works(List.of(bash.toString(), "--noprofile", "--norc", "-c",
				"command -v git && command -v grep && command -v sed && command -v gzip && command -v base64"
						+ " && command -v tr && command -v wc")),
				"bash finds no git, grep, sed, gzip, base64, tr or wc");
		Map<String, Object> workflow = new Yaml(new SafeConstructor(new LoaderOptions()))
			.load(GitHubStub.published(GitHubStub.RUNNER_WORKFLOW));
		steps = jobSteps(asMap(asMap(workflow.get("jobs")).get("copy")));
		assertThat(steps).extracting(step -> step.get("name"))
			.containsExactly("Check the inputs", "Exchange the ID token", "Fetch the commit", "Report the changed paths",
					"Push the commit", "Remove the credentials");
	}

	@BeforeEach
	void repositories() throws IOException {
		this.remotes = Files.createDirectories(this.temp.resolve("remotes"));
		this.runnerTemp = Files.createDirectories(this.temp.resolve("runner-temp"));
		Files.createDirectories(this.temp.resolve("home"));
		Files.writeString(this.temp.resolve("gitconfig"), "");
		this.runner = this.temp.resolve("runner.sh");
		Files.writeString(this.runner, """
				# RunnerCopyStepsHarnessTest's runner: a step as GitHub runs it (bash --noprofile --norc -eo pipefail {0}),
				# with the stub curl and jq first on PATH.
				first() {
				  PATH="$(cygpath -u "$1" 2>/dev/null || printf '%s' "$1"):$PATH"
				  export PATH
				}
				HARNESS_REAL_GIT="$(command -v git)"
				export HARNESS_REAL_GIT
				first "$HARNESS_STUBS"
				exec bash --noprofile --norc -eo pipefail "$1"
				""", StandardCharsets.UTF_8);
		this.stubs = stubs();
		Path working = bare(WORKING);
		git(working, "symbolic-ref", "HEAD", "refs/heads/main");
		bare(INTERMEDIARY);
	}

	// ---------------------------------------------------------------- the inputs

	@ParameterizedTest
	@MethodSource("accepted")
	void theInputChecksAcceptAWellFormedCopy(String input, String value) throws Exception {
		Map<String, String> inputs = inputs("ab".repeat(20), "cd".repeat(20));
		inputs.put(input, value);

		Run check = check(inputs);

		assertThat(check.exit()).as(check.toString()).isZero();
		assertThat(check.stdout()).doesNotContain("::error::");
	}

	static Stream<Arguments> accepted() {
		return Stream.of(Arguments.of("correlation", CORRELATION), Arguments.of("correlation", "0123abcd"),
				Arguments.of("exchange_url", EXCHANGE_URL), Arguments.of("exchange_url", EXCHANGE_URL + ":8443"),
				Arguments.of("source_branch", "main"), Arguments.of("source_branch", "release/v2.1"),
				Arguments.of("source_repository", "vibefix-dev/a.b_c-d"), Arguments.of("target_repository", "o/x"),
				Arguments.of("target_branch", "handover/" + UUID.randomUUID()));
	}

	@ParameterizedTest
	@MethodSource("refused")
	void everyInputCheckRefusesAsWritten(String input, String value, String error) throws Exception {
		Map<String, String> inputs = inputs("ab".repeat(20), "cd".repeat(20));
		inputs.put(input, value);

		Run check = check(inputs);

		assertThat(check.exit()).as(check.toString()).isEqualTo(1);
		assertThat(check.stdout().lines()).as(check.toString()).containsExactly("::error::" + error);
	}

	static Stream<Arguments> refused() {
		List<Arguments> cases = new ArrayList<>();
		for (String correlation : List.of("", "0B7C9A52-5F0E", "short", "-0b7c9a52-5f0e", "0b7c9a52;5f0e", "0b7c9a52 5f0e",
				"a".repeat(65), "0b7c9a52\n")) {
			cases.add(Arguments.of("correlation", correlation, "correlation is not a correlation ID"));
		}
		for (String url : List.of("", "http://exchange.example.test", "https://exchange.example.test/github",
				"https://user@exchange.example.test", "https://exchange.example.test?x=1", "https://exchange.example.test:",
				"https://-exchange.example.test", "https://exchange.example.test:123456", "file:///etc")) {
			cases.add(Arguments.of("exchange_url", url, "exchange_url is not an https origin"));
		}
		for (String sha : List.of("", "AB".repeat(20), "a".repeat(39), "a".repeat(41), "g".repeat(40),
				"ab".repeat(20) + "\n", "HEAD")) {
			cases.add(Arguments.of("sha", sha, "a commit is not 40 hex digits"));
			cases.add(Arguments.of("from_sha", sha, "a commit is not 40 hex digits"));
		}
		for (String branch : List.of("", "a..b", "a//b", "main/", "main.lock", "-main", ".main", "main branch", "$(id)",
				"x".repeat(201), "refs/heads/main\n")) {
			cases.add(Arguments.of("source_branch", branch, "a branch name is refused"));
			cases.add(Arguments.of("target_branch", branch, "a branch name is refused"));
		}
		for (String repository : List.of("", "owner", "owner/.", "owner/..", "-owner/x", "owner/x/y", "owner/x y",
				"a".repeat(40) + "/x", "owner/" + "x".repeat(101), "owner/$(id)")) {
			cases.add(Arguments.of("source_repository", repository, "a repository is not owner/name"));
			cases.add(Arguments.of("target_repository", repository, "a repository is not owner/name"));
		}
		// AD-11, AD-46: the runner only ever opens the new handover branch of one handover.
		for (String branch : List.of("main", "handover/main", "handover/0b7c9a52", "handover/" + "a".repeat(36),
				"handover/0B7C9A52-5F0E-4F7E-9D43-2D1C9B0E6A11")) {
			cases.add(Arguments.of("target_branch", branch, "target_branch is not handover/<uuid>"));
		}
		return cases.stream();
	}

	/** FR-32: a range that starts at its own commit would hand over nothing, so the inputs refuse it. */
	@Test
	void aRangeThatStartsAtItsOwnCommitIsRefused() throws Exception {
		Run check = check(inputs("ab".repeat(20), "ab".repeat(20)));

		assertThat(check.exit()).as(check.toString()).isEqualTo(1);
		assertThat(check.stdout().lines()).containsExactly("::error::the handover range starts at its own commit");
	}

	// ---------------------------------------------------------------- the exchange

	@Test
	void theExchangeTradesTheMaskedIdTokenForATokenFileAndCleansUp() throws Exception {
		Run exchange = exchange(200, "{\"token\":\"" + VIBEFIX_TOKEN + "\"}", ID_TOKEN);

		assertThat(exchange.exit()).as(exchange.toString()).isZero();
		assertThat(exchange.stdout().lines()).as("both masked, the ID token first, and nothing else said")
			.containsExactly("::add-mask::" + ID_TOKEN, "::add-mask::" + VIBEFIX_TOKEN);
		assertThat(read(this.runnerTemp.resolve("vibefix-token"))).isEqualTo(VIBEFIX_TOKEN);
		assertThat(this.runnerTemp.resolve("askpass.sh")).isRegularFile();
		assertThat(Stream.of("oidc-header", "exchange.json", "exchanged.json").map(this.runnerTemp::resolve))
			.as("the request files are gone").noneMatch(Files::exists);
		assertThat(Files.readAllLines(this.temp.resolve("curl.log"), StandardCharsets.UTF_8)).containsExactly(
				String.join("\t", "oidc", ID_TOKEN_URL + "&audience=vibefix", "Authorization: bearer " + REQUEST_TOKEN),
				String.join("\t", "post", EXCHANGE_URL + "/github/actions/exchange",
						"{\"id_token\":\"" + ID_TOKEN + "\",\"correlation\":\"" + CORRELATION + "\"}"));
	}

	@ParameterizedTest
	@MethodSource("exchangeRefusals")
	void theExchangeStepEndsTheRunWithoutAToken(int status, String body, String idToken, String error) throws Exception {
		Run exchange = exchange(status, body, idToken);

		assertThat(exchange.exit()).as(exchange.toString()).isEqualTo(1);
		assertThat(exchange.stdout().lines()).as(exchange.toString()).contains("::error::" + error);
		assertThat(exchange.stdout()).as("no token is ever printed").doesNotContain(VIBEFIX_TOKEN);
		assertThat(this.runnerTemp.resolve("vibefix-token")).doesNotExist();
		assertThat(Stream.of("oidc-header", "exchange.json", "exchanged.json").map(this.runnerTemp::resolve))
			.as("the request files are gone").noneMatch(Files::exists);
	}

	static Stream<Arguments> exchangeRefusals() {
		return Stream.of(Arguments.of(200, "{}", "", "GitHub gave no ID token"),
				Arguments.of(403, "{\"status\":403,\"code\":\"FORBIDDEN\",\"reason\":\"blob\"}", ID_TOKEN,
						"the exchange refused the ID token: HTTP 403 blob"),
				Arguments.of(502, "<html>Bad gateway</html>", ID_TOKEN, "the exchange refused the ID token: HTTP 502 -"),
				Arguments.of(200, "{}", ID_TOKEN, "the exchange answered no token"),
				Arguments.of(200, "{\"token\":\"\"}", ID_TOKEN, "the exchange answered no token"),
				Arguments.of(200, "{\"token\":\"harness short\"}", ID_TOKEN, "the exchange answered no token"),
				Arguments.of(200, "{\"token\":\"" + "t".repeat(4097) + "\"}", ID_TOKEN, "the exchange answered no token"));
	}

	// ---------------------------------------------------------------- the whole copy

	@Test
	void copiesOnlyTheHandoverCommitToANewHandoverBranchAndRemovesTheCredentials() throws Exception {
		History history = history();

		Run copy = copy(history.start(), history.handover());

		assertThat(copy.exit()).as(copy.toString()).isZero();
		Path intermediary = this.remotes.resolve(INTERMEDIARY + ".git");
		assertThat(refs(intermediary)).as("one new handover branch, holding exactly the Handover Commit")
			.containsExactly(history.handover() + " refs/heads/" + TARGET_BRANCH);
		assertThat(has(intermediary, history.tip())).as("a later commit of the default branch is not copied").isFalse();
		assertThat(has(intermediary, history.feature())).as("another branch is not copied").isFalse();
		assertThat(Stream.of("vibefix-token", "askpass.sh").map(this.runnerTemp::resolve))
			.as("the last step removes the credentials").noneMatch(Files::exists);
	}

	/** AD-11, FR-32: both ends of the range must be commits on the source branch, or nothing is pushed. */
	@ParameterizedTest
	@ValueSource(strings = { "feature", "unknownFrom", "unknownSha", "reversed" })
	void aRangeEndThatIsNotOnTheSourceBranchIsNeverPushed(String which) throws Exception {
		History history = history();
		String unknown = "0123456789abcdef0123456789abcdef01234567";
		String from = switch (which) {
			case "feature" -> history.feature();
			case "unknownFrom" -> unknown;
			case "reversed" -> history.handover();
			default -> history.start();
		};
		String sha = switch (which) {
			case "unknownSha" -> unknown;
			case "reversed" -> history.start();
			default -> history.handover();
		};

		Run copy = copy(from, sha);

		assertThat(copy.exit()).as(copy.toString()).isNotZero();
		assertThat(refs(this.remotes.resolve(INTERMEDIARY + ".git"))).isEmpty();
	}

	/** AD-11: the handover branch is new, so a target that holds it already is refused before anything is pushed. */
	@Test
	void aHandoverBranchThatExistsAlreadyIsRefusedBeforeThePush() throws Exception {
		History history = history();
		Path intermediary = this.remotes.resolve(INTERMEDIARY + ".git");
		git(intermediary, "fetch", "--quiet", fileUrl(this.remotes) + "/" + WORKING + ".git",
				"+refs/heads/main:refs/heads/" + TARGET_BRANCH);

		Run copy = copy(history.start(), history.handover());

		assertThat(copy.exit()).as(copy.toString()).isEqualTo(1);
		assertThat(copy.stdout().lines()).contains("::error::" + TARGET_BRANCH + " exists on " + INTERMEDIARY + " already");
		assertThat(refs(intermediary)).containsExactly(history.tip() + " refs/heads/" + TARGET_BRANCH);
	}

	/** Nothing is forced: a branch that appeared under the run is refused with git's own words. */
	@Test
	void thePushIsNeverForced() throws Exception {
		History history = history();
		Path intermediary = this.remotes.resolve(INTERMEDIARY + ".git");

		Run copy = copy(history.start(), history.handover(), script -> script, () -> {
			String diverged = commit(intermediary, emptyTree(intermediary), List.of(), IDENT, "someone else's commit");
			git(intermediary, "update-ref", "refs/heads/" + TARGET_BRANCH, diverged);
		}, 204);

		assertThat(copy.exit()).as(copy.toString()).isEqualTo(1);
		assertThat(copy.stdout().lines()).as(copy.toString())
			.anySatisfy(line -> assertThat(line).startsWith("::error::").contains("[rejected]"))
			.anySatisfy(line -> assertThat(line).startsWith("::error::").contains("failed to push some refs"))
			.anySatisfy(line -> assertThat(line).matches(
					Pattern.quote("::error::the push to " + INTERMEDIARY + " failed (git exit ") + "\\d+\\)"));
	}

	// ---------------------------------------------------------------- the path report

	/**
	 * FR-32, AD-11: the report carries every path the range changed, NUL separated so that a path with a quote or a
	 * space stays one entry, gzipped and base64 encoded, with its own count; no path is ever printed.
	 */
	@Test
	void theReportCarriesTheWholeNulSeparatedPathListWithItsCountAndPrintsNone() throws Exception {
		History history = history(List.of("src/booking.js", "tai lieu/gia moi.txt", "a\"b.txt"));

		Run copy = copy(history.start(), history.handover());

		assertThat(copy.exit()).as(copy.toString()).isZero();
		Map<String, String> report = report();
		assertThat(report).containsEntry("correlation", CORRELATION).containsEntry("workflows_changed", "false");
		List<String> paths = paths(report.get("paths"));
		assertThat(paths).containsExactlyInAnyOrder("src/booking.js", "tai lieu/gia moi.txt", "a\"b.txt");
		assertThat(report.get("count")).as("the count is the list's own length").isEqualTo(String.valueOf(paths.size()));
		assertThat(copy.stdout()).as("no path is printed, only the count and the verdict")
			.doesNotContain("booking.js", "gia moi")
			.contains("VIBEFIX_REPORT paths=" + paths.size() + " workflows_changed=false");
	}

	/** N-16: a change under {@code .github/workflows} is reported and then ends the run, before any push. */
	@Test
	void aWorkflowChangeIsReportedAndEndsTheRunBeforeThePush() throws Exception {
		History history = history(List.of("src/booking.js", ".github/workflows/ci.yml"));

		Run copy = copy(history.start(), history.handover());

		assertThat(copy.exit()).as(copy.toString()).isEqualTo(1);
		assertThat(copy.stdout().lines()).as(copy.toString())
			.contains("::error::the handover changes files under .github/workflows, so nothing is pushed");
		assertThat(report()).containsEntry("workflows_changed", "true");
		assertThat(refs(this.remotes.resolve(INTERMEDIARY + ".git"))).as("nothing was pushed").isEmpty();
	}

	/** vibefix refusing the report ends the run with its status, and nothing is pushed. */
	@Test
	void aRefusedReportEndsTheRunWithoutAPush() throws Exception {
		History history = history();

		Run copy = copy(history.start(), history.handover(), script -> script, Between.NOTHING, 503);

		assertThat(copy.exit()).as(copy.toString()).isEqualTo(1);
		assertThat(copy.stdout().lines()).contains("::error::vibefix refused the path report: HTTP 503 -");
		assertThat(refs(this.remotes.resolve(INTERMEDIARY + ".git"))).isEmpty();
	}

	// ---------------------------------------------------------------- the object checks

	/** Each message the copy lets through (deferred-work 650), alone in a history otherwise well formed. */
	@ParameterizedTest
	@MethodSource("ignored")
	void aLegacyHistoryCopiesPastEachIgnoredMessage(String message) throws Exception {
		Legacy legacy = legacy(message);

		Run copy = copy(legacy.start(), legacy.handover());

		assertThat(copy.exit()).as(copy.toString()).isZero();
		assertThat(refs(this.remotes.resolve(INTERMEDIARY + ".git")))
			.containsExactly(legacy.handover() + " refs/heads/" + TARGET_BRANCH);
	}

	static Stream<String> ignored() {
		return IGNORED.stream();
	}

	/** Each ignore is needed: with its message taken off the list, the same history fails with that message. */
	@ParameterizedTest
	@MethodSource("ignored")
	void eachIgnoredMessageFailsOnceItsIgnoreIsRemoved(String message) throws Exception {
		Legacy legacy = legacy(message);
		String list = "for message in " + String.join(" ", IGNORED) + "; do\n";
		List<String> others = IGNORED.stream().filter(ignored -> !ignored.equals(message)).toList();

		Run copy = copy(legacy.start(), legacy.handover(), script -> {
			assertThat(script).contains(list);
			return script.replace(list, "for message in " + String.join(" ", others) + "; do\n");
		}, Between.NOTHING, 204);

		assertThat(copy.exit()).as(copy.toString()).isEqualTo(1);
		assertThat(copy.stdout().lines()).as(copy.toString())
			.anySatisfy(line -> assertThat(line).startsWith("::error::").contains(message));
		assertThat(refs(this.remotes.resolve(INTERMEDIARY + ".git"))).isEmpty();
	}

	/** Any other object error fails the fetch with git's own words as {@code ::error::} lines. */
	@ParameterizedTest
	@ValueSource(strings = { "missingEmail", "zeroPaddedDate", "hasDotgit" })
	void anyOtherObjectErrorFailsTheRunWithGitsMessage(String message) throws Exception {
		Legacy legacy = legacy(message);

		Run copy = copy(legacy.start(), legacy.handover());

		assertThat(copy.exit()).as(copy.toString()).isEqualTo(1);
		assertThat(copy.stdout().lines()).as(copy.toString())
			.anySatisfy(line -> assertThat(line).startsWith("::error::").contains(message))
			.anySatisfy(line -> assertThat(line).matches("::error::the fetch of main failed \\(git exit \\d+\\)"));
		assertThat(refs(this.remotes.resolve(INTERMEDIARY + ".git"))).isEmpty();
	}

	// ---------------------------------------------------------------- running the steps

	/** One step's outcome, or a whole job's: every step's output in order, and the first non-zero exit. */
	private record Run(int exit, String stdout, String stderr) {

		Run then(Run next) {
			return new Run(this.exit != 0 ? this.exit : next.exit(), this.stdout + next.stdout(),
					this.stderr + next.stderr());
		}

		@Override
		public String toString() {
			return "exit " + this.exit + "\n--- stdout\n" + this.stdout + "--- stderr\n" + this.stderr;
		}

	}

	private static Map<String, String> inputs(String fromSha, String sha) {
		Map<String, String> inputs = new LinkedHashMap<>();
		inputs.put("correlation", CORRELATION);
		inputs.put("exchange_url", EXCHANGE_URL);
		inputs.put("source_repository", WORKING);
		inputs.put("source_branch", "main");
		inputs.put("from_sha", fromSha);
		inputs.put("sha", sha);
		inputs.put("target_repository", INTERMEDIARY);
		inputs.put("target_branch", TARGET_BRANCH);
		return inputs;
	}

	private Run check(Map<String, String> inputs) throws Exception {
		return step("Check the inputs", inputs, script("Check the inputs"), Map.of());
	}

	/**
	 * The exchange step in full: GitHub's ID token endpoint answers {@code idToken}, and vibefix's exchange answers
	 * {@code status} with {@code body}.
	 */
	private Run exchange(int status, String body, String idToken) throws Exception {
		return step("Exchange the ID token", inputs("ab".repeat(20), "cd".repeat(20)), script("Exchange the ID token"),
				answers(idToken, status, body, 204));
	}

	/** What the stubs answer: GitHub's ID token, vibefix's exchange, and vibefix's report endpoint. */
	private Map<String, String> answers(String idToken, int exchangeStatus, String exchangeBody, int reportStatus) {
		Map<String, String> answers = new LinkedHashMap<>();
		answers.put("ACTIONS_ID_TOKEN_REQUEST_TOKEN", REQUEST_TOKEN);
		answers.put("ACTIONS_ID_TOKEN_REQUEST_URL", ID_TOKEN_URL);
		answers.put("HARNESS_ID_TOKEN", idToken);
		answers.put("HARNESS_EXCHANGE_STATUS", String.valueOf(exchangeStatus));
		answers.put("HARNESS_EXCHANGE_BODY", exchangeBody);
		answers.put("HARNESS_REPORT_STATUS", String.valueOf(reportStatus));
		answers.put("HARNESS_REPORT_FILE", posix(this.temp.resolve("report.json")));
		answers.put("HARNESS_CURL_LOG", posix(this.temp.resolve("curl.log")));
		return answers;
	}

	private Run copy(String fromSha, String sha) throws Exception {
		return copy(fromSha, sha, script -> script, Between.NOTHING, 204);
	}

	/**
	 * The whole job: the input checks, the exchange, the fetch, the path report, the push, and the credentials'
	 * removal, which runs whatever the copy did. A step that fails stops the job, as GitHub stops it.
	 * @param editFetch a change to the fetch step's script
	 * @param betweenReportAndPush what happens to the target between the report and the push
	 * @param reportStatus what vibefix answers the path report
	 */
	private Run copy(String fromSha, String sha, UnaryOperator<String> editFetch, Between betweenReportAndPush,
			int reportStatus) throws Exception {
		Map<String, String> inputs = inputs(fromSha, sha);
		Map<String, String> answers = answers(ID_TOKEN, 200, "{\"token\":\"" + VIBEFIX_TOKEN + "\"}", reportStatus);
		Run outcome = check(inputs);
		if (outcome.exit() == 0) {
			Run exchange = step("Exchange the ID token", inputs, script("Exchange the ID token"), answers);
			assertThat(exchange.exit()).as(exchange.toString()).isZero();
			outcome = outcome.then(exchange);
		}
		if (outcome.exit() == 0) {
			outcome = outcome.then(step("Fetch the commit", inputs, editFetch.apply(script("Fetch the commit")), answers));
		}
		if (outcome.exit() == 0) {
			outcome = outcome.then(step("Report the changed paths", inputs, script("Report the changed paths"), answers));
		}
		if (outcome.exit() == 0) {
			betweenReportAndPush.run();
			outcome = outcome.then(step("Push the commit", inputs, script("Push the commit"), answers));
		}
		Run removed = step("Remove the credentials", inputs, script("Remove the credentials"), answers);
		assertThat(removed.exit()).as(removed.toString()).isZero();
		return outcome;
	}

	private static String script(String name) {
		return String.valueOf(steps.stream().filter(step -> name.equals(step.get("name"))).findFirst().orElseThrow().get("run"));
	}

	/** Runs a step's script with its {@code env:} filled from the inputs, and the variables GitHub sets for a step. */
	private Run step(String name, Map<String, String> inputs, String script, Map<String, String> extra) throws Exception {
		Path file = Files.createTempFile(this.temp, "step", ".sh");
		Files.writeString(file, script, StandardCharsets.UTF_8);
		Path stdout = Files.createTempFile(this.temp, "stdout", ".txt");
		Path stderr = Files.createTempFile(this.temp, "stderr", ".txt");
		ProcessBuilder builder = new ProcessBuilder(String.valueOf(bash), "--noprofile", "--norc", posix(this.runner),
				posix(file))
			.directory(this.temp.toFile())
			.redirectOutput(stdout.toFile())
			.redirectError(stderr.toFile());
		Map<String, String> environment = isolated(builder.environment());
		environment.put("RUNNER_TEMP", posix(this.runnerTemp));
		environment.put("GITHUB_SERVER_URL", fileUrl(this.remotes));
		environment.put("HARNESS_STUBS", posix(this.stubs));
		Object declared = steps.stream().filter(step -> name.equals(step.get("name"))).findFirst().orElseThrow().get("env");
		if (declared != null) {
			for (Map.Entry<String, Object> variable : asMap(declared).entrySet()) {
				String expression = String.valueOf(variable.getValue());
				Matcher input = INPUT.matcher(expression);
				if (!input.matches()) {
					fail("the harness does not know " + expression);
				}
				environment.put(variable.getKey(), inputs.getOrDefault(input.group(1), ""));
			}
		}
		environment.putAll(extra);
		Process process = builder.start();
		if (!process.waitFor(120, TimeUnit.SECONDS)) {
			process.destroyForcibly();
			fail(name + " did not end within 120 s");
		}
		return new Run(process.exitValue(), read(stdout), read(stderr));
	}

	/** The environment without the caller's git settings: no system or global config, English messages. */
	private Map<String, String> isolated(Map<String, String> environment) {
		environment.keySet().removeIf(key -> key.toUpperCase(Locale.ROOT).startsWith("GIT_"));
		environment.put("GIT_CONFIG_NOSYSTEM", "1");
		environment.put("GIT_CONFIG_GLOBAL", posix(this.temp.resolve("gitconfig")));
		environment.put("HOME", posix(this.temp.resolve("home")));
		environment.put("XDG_CONFIG_HOME", posix(this.temp.resolve("home")));
		environment.put("LC_ALL", "C");
		environment.put("LANG", "C");
		return environment;
	}

	/**
	 * The stub {@code curl} and {@code jq} the exchange and the report steps run: GitHub's ID token endpoint,
	 * vibefix's exchange and its report endpoint as the test sets them, each request recorded, the report's own body
	 * kept for the test to decode; and the jq filters those steps use, on flat JSON objects.
	 */
	private Path stubs() throws IOException {
		Path directory = Files.createDirectories(this.temp.resolve("stubs"));
		Path curl = directory.resolve("curl");
		Files.writeString(curl, """
				#!/usr/bin/env bash
				# RunnerCopyStepsHarnessTest's curl: GitHub's ID token endpoint, or a POST to vibefix when asked to write
				# the status.
				out=; write=; header=; data=; url=
				while [ $# -gt 0 ]; do
				  case "$1" in
				    -o) out="$2"; shift 2 ;;
				    -w) write="$2"; shift 2 ;;
				    -H) case "$2" in @*) header="${2#@}" ;; esac; shift 2 ;;
				    --data-binary) data="${2#@}"; shift 2 ;;
				    --max-time|-X) shift 2 ;;
				    -*) shift ;;
				    *) url="$1"; shift ;;
				  esac
				done
				if [ -z "$write" ]; then
				  printf 'oidc\\t%s\\t%s\\n' "$url" "$(cat "$header")" >> "$HARNESS_CURL_LOG"
				  printf '{"value":"%s"}\\n' "$HARNESS_ID_TOKEN"
				elif [ "${url%/github/actions/report}" != "$url" ]; then
				  printf 'report\\t%s\\n' "$url" >> "$HARNESS_CURL_LOG"
				  cp "$data" "$HARNESS_REPORT_FILE"
				  printf '{}' > "$out"
				  printf '%s' "$HARNESS_REPORT_STATUS"
				else
				  printf 'post\\t%s\\t%s\\n' "$url" "$(cat "$data")" >> "$HARNESS_CURL_LOG"
				  printf '%s' "$HARNESS_EXCHANGE_BODY" > "$out"
				  printf '%s' "$HARNESS_EXCHANGE_STATUS"
				fi
				""", StandardCharsets.UTF_8);
		Path jq = directory.resolve("jq");
		Files.writeString(jq, """
				#!/usr/bin/env bash
				# RunnerCopyStepsHarnessTest's jq: the filters the exchange and the report steps use.
				null=0; rawfile=
				while [ $# -gt 0 ]; do
				  case "$1" in
				    -r) shift ;;
				    -n) null=1; shift ;;
				    --rawfile) rawfile="$3"; shift 3 ;;
				    *) break ;;
				  esac
				done
				filter="$1"
				shift
				if [ "$null" = 1 ]; then
				  case "$filter" in
				    *workflows_changed*)
				      changed=false
				      [ "$CHANGED" != true ] || changed=true
				      printf '{"id_token":"%s","correlation":"%s","workflows_changed":%s,"paths":"%s","count":%s}\\n' \\
				        "$ID_TOKEN" "$CORRELATION" "$changed" "$(cat "$rawfile")" "$COUNT"
				      ;;
				    *id_token*correlation*) printf '{"id_token":"%s","correlation":"%s"}\\n' "$ID_TOKEN" "$CORRELATION" ;;
				    *) echo "jq stub: $filter" >&2; exit 3 ;;
				  esac
				  exit 0
				fi
				case "$filter" in
				  '.value // empty') key=value; fallback= ;;
				  '.token // empty') key=token; fallback= ;;
				  '.reason // "-"') key=reason; fallback=- ;;
				  *) echo "jq stub: $filter" >&2; exit 3 ;;
				esac
				input="$(cat "${1:-/dev/stdin}")" || exit 2
				value="$(printf '%s' "$input" | sed -n -E "s/.*\\"$key\\" *: *\\"([^\\"]*)\\".*/\\1/p")"
				printf '%s\\n' "${value:-$fallback}"
				""", StandardCharsets.UTF_8);
		executable(curl);
		executable(jq);
		return directory;
	}

	/** The report the run posted, as its flat JSON holds it; its ID token is never kept. */
	private Map<String, String> report() throws IOException {
		String json = read(this.temp.resolve("report.json"));
		Map<String, String> fields = new LinkedHashMap<>();
		Matcher matcher = Pattern.compile("\"([a-z_]+)\":(?:\"([^\"]*)\"|([A-Za-z0-9]+))").matcher(json);
		while (matcher.find()) {
			fields.put(matcher.group(1), matcher.group(2) != null ? matcher.group(2) : matcher.group(3));
		}
		assertThat(fields).containsKey("id_token");
		fields.remove("id_token");
		return fields;
	}

	/** The reported paths: base64, gzipped, NUL separated UTF-8, exactly as the run wrote them. */
	private static List<String> paths(String base64) throws IOException {
		byte[] bytes;
		try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(base64)))) {
			bytes = in.readAllBytes();
		}
		String text = new String(bytes, StandardCharsets.UTF_8);
		assertThat(text).as("the list is NUL separated, never newline separated").doesNotContain("\n");
		return Stream.of(text.split("\0", -1)).filter(path -> !path.isEmpty()).toList();
	}

	// ---------------------------------------------------------------- the fixtures

	/**
	 * The Working Repository's history on {@code main}: the previous Handover Commit, the Handover Commit, a commit
	 * pushed after it, and a branch of its own.
	 */
	private record History(String start, String handover, String tip, String feature) {
	}

	private History history() throws IOException {
		return history(List.of("src/booking.js"));
	}

	/** A history whose Handover Commit adds exactly {@code changed}, beside the README both ends share. */
	private History history(List<String> changed) throws IOException {
		Path repository = this.remotes.resolve(WORKING + ".git");
		Map<String, String> before = new LinkedHashMap<>();
		before.put("README.md", "# Sổ đặt bàn\n");
		String base = tree(repository, before);
		String first = commit(repository, base, List.of(), IDENT, "first");
		String start = commit(repository, base, List.of(first), IDENT, "the previous handover");
		Map<String, String> after = new LinkedHashMap<>(before);
		changed.forEach(path -> after.put(path, "// " + path + "\n"));
		String handover = commit(repository, tree(repository, after), List.of(start), IDENT, "the handover");
		String tip = commit(repository, tree(repository, after), List.of(handover), IDENT, "pushed after the handover");
		String feature = commit(repository, base, List.of(first), IDENT, "on another branch");
		git(repository, "update-ref", "refs/heads/main", tip);
		git(repository, "update-ref", "refs/heads/feature", feature);
		return new History(start, handover, tip, feature);
	}

	/** A legacy history: the range's start, and a Handover Commit that holds one fsck finding. */
	private record Legacy(String start, String handover) {
	}

	/**
	 * A default branch whose Handover Commit holds one fsck finding, {@code message}. The fixture is proved first:
	 * {@code git fsck --strict} reports that message.
	 */
	private Legacy legacy(String message) throws IOException {
		Path repository = this.remotes.resolve(WORKING + ".git");
		String base = readmeTree(repository);
		String first = commit(repository, base, List.of(), IDENT, "first");
		String start = commit(repository, base, List.of(first), IDENT, "the previous handover");
		String handover = switch (message) {
			case "zeroPaddedFilemode" -> commit(repository, object(repository, "tree", entry("040000", "sub", base)),
					List.of(start), IDENT, "a directory with a zero-padded mode");
			case "hasDotgit" -> commit(repository, object(repository, "tree", entry("40000", ".git", base)), List.of(start),
					IDENT, "a .git directory in the tree");
			case "badDate" -> commit(repository, base, List.of(start), "Lan <lan@example.test> 17x0000000 +0700", message);
			case "badDateOverflow" -> commit(repository, base, List.of(start),
					"Lan <lan@example.test> 99999999999999999999 +0700", message);
			case "badTimezone" -> commit(repository, base, List.of(start), "Lan <lan@example.test> 1700000000 0700", message);
			case "missingSpaceBeforeDate" -> commit(repository, base, List.of(start),
					"Lan <lan@example.test>1700000000 +0700", message);
			case "missingEmail" -> commit(repository, base, List.of(start), "Lan 1700000000 +0700", message);
			case "zeroPaddedDate" -> commit(repository, base, List.of(start), "Lan <lan@example.test> 01700000000 +0700",
					message);
			default -> fail("no fixture for " + message);
		};
		git(repository, "update-ref", "refs/heads/main", handover);
		assertThat(gitOutput(repository, true, "fsck", "--strict")).as("the fixture holds " + message).contains(message);
		return new Legacy(start, handover);
	}

	private Path bare(String name) throws IOException {
		Path repository = Files.createDirectories(this.remotes.resolve(name + ".git"));
		git(repository, "init", "--quiet", "--bare");
		return repository;
	}

	private String readmeTree(Path repository) throws IOException {
		String readme = object(repository, "blob", "# Sổ đặt bàn\n".getBytes(StandardCharsets.UTF_8));
		return object(repository, "tree", entry("100644", "README.md", readme));
	}

	private String emptyTree(Path repository) throws IOException {
		return object(repository, "tree", new byte[0]);
	}

	/**
	 * The tree of {@code files}, each a path with {@code /} separators, written with its subtrees. Entries are
	 * sorted as git sorts them: by name, a subtree compared as if its name ended with {@code /}.
	 */
	private String tree(Path repository, Map<String, String> files) throws IOException {
		Map<String, String> blobs = new TreeMap<>();
		Map<String, Map<String, String>> subtrees = new LinkedHashMap<>();
		Set<String> directories = new LinkedHashSet<>();
		for (Map.Entry<String, String> file : files.entrySet()) {
			String path = file.getKey();
			int slash = path.indexOf('/');
			if (slash < 0) {
				blobs.put(path, object(repository, "blob", file.getValue().getBytes(StandardCharsets.UTF_8)));
			}
			else {
				String directory = path.substring(0, slash);
				directories.add(directory);
				subtrees.computeIfAbsent(directory, name -> new LinkedHashMap<>()).put(path.substring(slash + 1),
						file.getValue());
			}
		}
		Map<String, byte[]> entries = new TreeMap<>();
		blobs.forEach((name, sha) -> entries.put(name, entry("100644", name, sha)));
		for (String directory : directories) {
			entries.put(directory + "/", entry("40000", directory, tree(repository, subtrees.get(directory))));
		}
		int size = entries.values().stream().mapToInt(entry -> entry.length).sum();
		byte[] raw = new byte[size];
		int at = 0;
		for (byte[] entry : entries.values()) {
			System.arraycopy(entry, 0, raw, at, entry.length);
			at += entry.length;
		}
		return object(repository, "tree", raw);
	}

	/** One raw tree entry: the mode as written, the name, and the object's 20 bytes. */
	private static byte[] entry(String mode, String name, String sha) {
		byte[] head = (mode + " " + name + "\0").getBytes(StandardCharsets.UTF_8);
		byte[] id = HexFormat.of().parseHex(sha);
		byte[] entry = new byte[head.length + id.length];
		System.arraycopy(head, 0, entry, 0, head.length);
		System.arraycopy(id, 0, entry, head.length, id.length);
		return entry;
	}

	private String commit(Path repository, String tree, List<String> parents, String author, String message)
			throws IOException {
		StringBuilder commit = new StringBuilder("tree ").append(tree).append('\n');
		parents.forEach(parent -> commit.append("parent ").append(parent).append('\n'));
		commit.append("author ").append(author).append('\n');
		commit.append("committer ").append(IDENT).append("\n\n").append(message).append('\n');
		return object(repository, "commit", commit.toString().getBytes(StandardCharsets.UTF_8));
	}

	/** Writes an object as given, unchecked, as an older tool might have. */
	private String object(Path repository, String type, byte[] content) throws IOException {
		Path file = Files.createTempFile(this.temp, "object", ".bin");
		Files.write(file, content);
		return gitOutput(repository, false, "hash-object", "-t", type, "-w", "--literally", posix(file)).strip();
	}

	/** The repository's refs as "sha ref" lines. */
	private List<String> refs(Path repository) throws IOException {
		return gitOutput(repository, false, "for-each-ref", "--format=%(objectname) %(refname)").lines().toList();
	}

	private boolean has(Path repository, String sha) throws IOException {
		return run(List.of("git", "-C", posix(repository), "cat-file", "-e", sha)).exit() == 0;
	}

	private void git(Path repository, String... args) throws IOException {
		gitOutput(repository, false, args);
	}

	/**
	 * Runs git in the repository, isolated as the steps are.
	 * @param mayFail whether a failure returns its output, stdout and stderr together, instead of failing the test
	 */
	private String gitOutput(Path repository, boolean mayFail, String... args) throws IOException {
		List<String> command = new ArrayList<>(List.of("git", "-C", posix(repository)));
		command.addAll(List.of(args));
		Run run = run(command);
		if (run.exit() != 0 && !mayFail) {
			fail("git " + String.join(" ", args) + " failed: " + run);
		}
		return run.exit() == 0 ? run.stdout() : run.stdout() + run.stderr();
	}

	private Run run(List<String> command) throws IOException {
		Path stdout = Files.createTempFile(this.temp, "git", ".out");
		Path stderr = Files.createTempFile(this.temp, "git", ".err");
		ProcessBuilder builder = new ProcessBuilder(command).redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
		isolated(builder.environment());
		try {
			Process process = builder.start();
			if (!process.waitFor(60, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				fail(String.join(" ", command) + " did not end within 60 s");
			}
			return new Run(process.exitValue(), read(stdout), read(stderr));
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new IOException(interrupted);
		}
	}

	// ---------------------------------------------------------------- the platform

	/**
	 * bash to run the steps under: on Windows, Git for Windows' own, found beside the git on PATH or in Program
	 * Files ({@code System32\bash.exe} is WSL's); elsewhere, bash on PATH.
	 */
	private static @Nullable Path findBash() {
		String path = System.getenv("PATH");
		List<String> directories = path == null ? List.of() : List.of(path.split(File.pathSeparator));
		if (!System.getProperty("os.name", "").startsWith("Windows")) {
			return directories.stream()
				.map(directory -> resolve(directory, "bash"))
				.filter(candidate -> candidate != null && Files.isExecutable(candidate))
				.findFirst()
				.orElse(null);
		}
		List<Path> candidates = new ArrayList<>();
		for (String directory : directories) {
			Path git = resolve(directory, "git.exe");
			if (git != null && Files.isRegularFile(git) && git.getParent() != null && git.getParent().getParent() != null) {
				Path root = git.getParent().getParent();
				candidates.add(root.resolve("bin").resolve("bash.exe"));
				if (root.getParent() != null) {
					candidates.add(root.getParent().resolve("bin").resolve("bash.exe"));
				}
			}
		}
		String programFiles = System.getenv("ProgramFiles");
		if (programFiles != null) {
			candidates.add(Path.of(programFiles, "Git", "bin", "bash.exe"));
		}
		return candidates.stream()
			.filter(Files::isRegularFile)
			.filter(candidate -> !candidate.toString().toLowerCase(Locale.ROOT).contains("system32"))
			.findFirst()
			.orElse(null);
	}

	private static @Nullable Path resolve(String directory, String name) {
		try {
			return directory.isBlank() ? null : Path.of(directory.strip()).resolve(name);
		}
		catch (InvalidPathException unusable) {
			return null;
		}
	}

	private static boolean works(List<String> command) {
		try {
			Process process = new ProcessBuilder(command).redirectErrorStream(true)
				.redirectOutput(ProcessBuilder.Redirect.DISCARD)
				.start();
			return process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0;
		}
		catch (IOException unavailable) {
			return false;
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	private static void executable(Path file) throws IOException {
		if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
			Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));
		}
	}

	/** A path as both bash and git read it: forward slashes, and on Windows its drive, such as C:/Users/…. */
	private static String posix(Path path) {
		return path.toAbsolutePath().toString().replace('\\', '/');
	}

	private static String fileUrl(Path directory) {
		String path = posix(directory);
		return path.startsWith("/") ? "file://" + path : "file:///" + path;
	}

	private static String read(Path file) throws IOException {
		return Files.readString(file, StandardCharsets.UTF_8);
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> jobSteps(Map<String, Object> job) {
		return (List<Map<String, Object>>) job.get("steps");
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> asMap(Object value) {
		assertThat(value).isInstanceOf(Map.class);
		return (Map<String, Object>) value;
	}

}
