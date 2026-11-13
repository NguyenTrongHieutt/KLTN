package vn.vibefix.github.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

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
 * Story 23.6c (deferred-work 646, the copy half; AD-45, SM-5): the Customer's {@code vibefix.yml} {@code copy} job, its
 * own {@code run:} scripts taken from {@code deploy/customer/vibefix.yml}, runs under bash as GitHub runs a step
 * ({@code bash --noprofile --norc -eo pipefail}) against local bare repositories behind {@code file://} remotes. The
 * exchange step runs in full, with stub {@code curl} and {@code jq} first on {@code PATH} standing in for GitHub's ID
 * token endpoint and vibefix's exchange. The guards pass and fail as written:
 * <ul>
 * <li>every input's regular expression, and the triggering actor, which must be a {@code [bot]};</li>
 * <li>the exchange: a refusal names its reason, and no token, an unsafe one or one too long ends the run;</li>
 * <li>only the confirmed commit is pushed, and a commit that is not on the default branch fails;</li>
 * <li>nothing is forced: a target whose branch diverged is refused with git's words, and each push is read back;</li>
 * <li>each ignored fsck message, in a crafted history, copies, and fails once its ignore is removed; any other object
 * error fails with git's words; both through {@code unpack-objects} and, with {@code transfer.unpackLimit = 1},
 * {@code index-pack};</li>
 * <li>git takes each credential through {@code GIT_ASKPASS} from its file. A {@code file://} remote asks for none, so a
 * git shim first on {@code PATH} records what each git call received and what the askpass script answers.</li>
 * </ul>
 * Skipped where bash or git is missing. On Windows, bash is Git for Windows' own, never WSL's {@code System32} one.
 */
class CopyStepsHarnessTest {

	private static final String CORRELATION = "0b7c9a52-5f0e-4f7e-9d43-2d1c9b0e6a11";

	private static final String INTERMEDIARY = "vibefix-dev/job-0b7c9a525f0e4f7e9d432d1c9b0e6a11-intermediary";

	private static final String WORKING = "vibefix-dev/job-0b7c9a525f0e4f7e9d432d1c9b0e6a11-working";

	private static final String SOURCE = "lan-nguyen/so-dat-ban";

	private static final String BOT = "vibefix-dev-source[bot]";

	private static final String EXCHANGE_URL = "https://exchange.example.test";

	private static final String ID_TOKEN_URL = "https://pipelines.example.test/idtoken?api-version=2.0";

	/** The messages the copy lets through (deferred-work 650), in the order the workflow lists them. */
	private static final List<String> IGNORED = List.of("zeroPaddedFilemode", "badDate", "badDateOverflow", "badTimezone",
			"missingSpaceBeforeDate");

	/** A well-formed author or committer line's value. */
	private static final String IDENT = "Lan <lan@example.test> 1700000000 +0700";

	/**
	 * Fake credentials, built at run time: GitHub's ID token request token and ID token, the token the exchange answers,
	 * and the run's GITHUB_TOKEN.
	 */
	private static final String REQUEST_TOKEN = "harness-request-" + UUID.randomUUID();

	private static final String ID_TOKEN = "harness-id-" + UUID.randomUUID();

	private static final String VIBEFIX_TOKEN = "harness-vibefix-" + UUID.randomUUID();

	private static final String GITHUB_TOKEN = "harness-github-" + UUID.randomUUID();

	private static final Pattern INPUT = Pattern.compile("\\$\\{\\{ inputs\\.([a-z_]+) }}");

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
		Assumptions.assumeTrue(bash != null, "no bash to run the copy's scripts under");
		Assumptions.assumeTrue(works(List.of("git", "--version")), "no git to build the fixtures with");
		Assumptions.assumeTrue(works(List.of(bash.toString(), "--noprofile", "--norc", "-c",
				"command -v git && command -v grep && command -v sed && command -v cut")),
				"bash finds no git, grep, sed or cut");
		Map<String, Object> workflow = new Yaml(new SafeConstructor(new LoaderOptions()))
			.load(GitHubStub.published(GitHubStub.CUSTOMER_WORKFLOW));
		steps = jobSteps(asMap(asMap(workflow.get("jobs")).get("copy")));
		assertThat(steps).extracting(step -> step.get("name"))
			.containsExactly("Check the inputs", "Exchange the ID token", "Copy the commit", "Remove the credentials");
	}

	@BeforeEach
	void repositories() throws IOException {
		this.remotes = Files.createDirectories(this.temp.resolve("remotes"));
		this.runnerTemp = Files.createDirectories(this.temp.resolve("runner-temp"));
		Files.createDirectories(this.temp.resolve("home"));
		Files.writeString(this.temp.resolve("gitconfig"), "");
		this.runner = this.temp.resolve("runner.sh");
		Files.writeString(this.runner, """
				# CopyStepsHarnessTest's runner: a step as GitHub runs it (bash --noprofile --norc -eo pipefail {0}), with
				# the stub curl and jq, and the git shim when HARNESS_SHIM names it, first on PATH.
				first() {
				  PATH="$(cygpath -u "$1" 2>/dev/null || printf '%s' "$1"):$PATH"
				  export PATH
				}
				HARNESS_REAL_GIT="$(command -v git)"
				export HARNESS_REAL_GIT
				first "$HARNESS_STUBS"
				if [ -n "${HARNESS_SHIM:-}" ]; then
				  first "$HARNESS_SHIM"
				fi
				exec bash --noprofile --norc -eo pipefail "$1"
				""", StandardCharsets.UTF_8);
		this.stubs = stubs();
		Path sourceRepository = bare(SOURCE);
		git(sourceRepository, "symbolic-ref", "HEAD", "refs/heads/main");
		bare(INTERMEDIARY);
		bare(WORKING);
	}

	// ---------------------------------------------------------------- the inputs

	@ParameterizedTest
	@MethodSource("accepted")
	void theInputChecksAcceptAWellFormedCopy(String input, String value) throws Exception {
		Map<String, String> inputs = inputs("ab".repeat(20));
		inputs.put(input, value);

		Run check = check(inputs, BOT);

		assertThat(check.exit()).as(check.toString()).isZero();
		assertThat(check.stdout()).doesNotContain("::error::");
	}

	static Stream<Arguments> accepted() {
		return Stream.of(Arguments.of("correlation", CORRELATION), Arguments.of("correlation", "0123abcd"),
				Arguments.of("exchange_url", EXCHANGE_URL), Arguments.of("exchange_url", EXCHANGE_URL + ":8443"),
				Arguments.of("branch", "main"), Arguments.of("branch", "release/v2.1"),
				Arguments.of("intermediary", "vibefix-dev/a.b_c-d"), Arguments.of("working", "o/x"));
	}

	@ParameterizedTest
	@MethodSource("refused")
	void everyInputCheckRefusesAsWritten(String input, String value, String error) throws Exception {
		Map<String, String> inputs = inputs("ab".repeat(20));
		inputs.put(input, value);

		Run check = check(inputs, BOT);

		assertThat(check.exit()).as(check.toString()).isEqualTo(1);
		assertThat(check.stdout().lines()).as(check.toString()).containsExactly("::error::" + error);
	}

	static Stream<Arguments> refused() {
		List<Arguments> cases = new ArrayList<>();
		cases.add(Arguments.of("operation", "final-merge", "this job only copies"));
		cases.add(Arguments.of("operation", "scan", "this job only copies"));
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
			cases.add(Arguments.of("sha", sha, "sha is not 40 hex digits"));
		}
		for (String branch : List.of("", "a..b", "a//b", "main/", "main.lock", "-main", ".main", "main branch", "$(id)",
				"x".repeat(201), "refs/heads/main\n")) {
			cases.add(Arguments.of("branch", branch, "branch is refused"));
		}
		for (String repository : List.of("", "owner", "owner/.", "owner/..", "-owner/x", "owner/x/y", "owner/x y",
				"a".repeat(40) + "/x", "owner/" + "x".repeat(101), "owner/$(id)")) {
			cases.add(Arguments.of("intermediary", repository, "intermediary is not owner/name"));
			cases.add(Arguments.of("working", repository, "working is not owner/name"));
		}
		return cases.stream();
	}

	/** Only a GitHub App starts a copy: the actor is checked before any input, so its refusal is the only one. */
	@ParameterizedTest
	@ValueSource(strings = { "octocat", "bot", "[bot]octocat", "octocat[bot] ", "octocat-bot", "" })
	void onlyABotStartsACopy(String actor) throws Exception {
		Map<String, String> inputs = inputs("not a sha");

		Run check = check(inputs, actor);

		assertThat(check.exit()).as(check.toString()).isEqualTo(1);
		assertThat(check.stdout().lines()).containsExactly("::error::only vibefix's Source App starts a copy");
	}

	// ---------------------------------------------------------------- the exchange

	@Test
	void theExchangeTradesTheMaskedIdTokenForATokenFileAndCleansUp() throws Exception {
		Run exchange = exchange(200, "{\"token\":\"" + VIBEFIX_TOKEN + "\"}", ID_TOKEN);

		assertThat(exchange.exit()).as(exchange.toString()).isZero();
		assertThat(exchange.stdout().lines()).as("both masked, the ID token first, and nothing else said")
			.containsExactly("::add-mask::" + ID_TOKEN, "::add-mask::" + VIBEFIX_TOKEN);
		assertThat(read(this.runnerTemp.resolve("vibefix-token"))).isEqualTo(VIBEFIX_TOKEN);
		assertThat(read(this.runnerTemp.resolve("github-token"))).isEqualTo(GITHUB_TOKEN);
		assertThat(this.runnerTemp.resolve("askpass.sh")).isRegularFile();
		assertThat(Stream.of("oidc-header", "exchange.json", "exchanged.json").map(this.runnerTemp::resolve))
			.as("the request files are gone").noneMatch(Files::exists);
		assertThat(Files.readAllLines(this.temp.resolve("curl.log"), StandardCharsets.UTF_8)).containsExactly(
				String.join("\t", "oidc", ID_TOKEN_URL + "&audience=vibefix", "Authorization: bearer " + REQUEST_TOKEN),
				String.join("\t", "exchange", EXCHANGE_URL + "/github/actions/exchange",
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
				Arguments.of(403, "{\"status\":403,\"code\":\"FORBIDDEN\",\"reason\":\"sha\"}", ID_TOKEN,
						"the exchange refused the ID token: HTTP 403 sha"),
				Arguments.of(502, "<html>Bad gateway</html>", ID_TOKEN, "the exchange refused the ID token: HTTP 502 -"),
				Arguments.of(200, "{}", ID_TOKEN, "the exchange answered no token"),
				Arguments.of(200, "{\"token\":\"\"}", ID_TOKEN, "the exchange answered no token"),
				Arguments.of(200, "{\"token\":\"harness short\"}", ID_TOKEN, "the exchange answered no token"),
				Arguments.of(200, "{\"token\":\"harness-token with a space-0123456789\"}", ID_TOKEN,
						"the exchange answered no token"),
				Arguments.of(200, "{\"token\":\"" + "t".repeat(4097) + "\"}", ID_TOKEN, "the exchange answered no token"));
	}

	// ---------------------------------------------------------------- the copy

	@Test
	void copiesOnlyTheConfirmedCommitToBothRepositoriesAndRemovesTheCredentials() throws Exception {
		History history = history();

		Run copy = copy(history.confirmed());

		assertThat(copy.exit()).as(copy.toString()).isZero();
		for (String target : List.of(INTERMEDIARY, WORKING)) {
			Path repository = this.remotes.resolve(target + ".git");
			assertThat(refs(repository)).as(target + " holds the confirmed commit on its branch, and nothing else")
				.containsExactly(history.confirmed() + " refs/heads/main");
			assertThat(has(repository, history.tip())).as("the default branch's later commit is not copied").isFalse();
			assertThat(has(repository, history.feature())).as("another branch is not copied").isFalse();
		}
		assertThat(Stream.of("vibefix-token", "github-token", "askpass.sh").map(this.runnerTemp::resolve))
			.as("the last step removes the credentials").noneMatch(Files::exists);
	}

	@ParameterizedTest
	@ValueSource(strings = { "feature", "unknown" })
	void aCommitThatIsNotOnTheDefaultBranchIsNeverPushed(String which) throws Exception {
		History history = history();
		String sha = which.equals("feature") ? history.feature() : "0123456789abcdef0123456789abcdef01234567";

		Run copy = copy(sha);

		assertThat(copy.exit()).as(copy.toString()).isNotZero();
		assertThat(refs(this.remotes.resolve(INTERMEDIARY + ".git"))).isEmpty();
		assertThat(refs(this.remotes.resolve(WORKING + ".git"))).isEmpty();
	}

	@Test
	void nothingIsForcedATargetWhoseBranchDivergedIsRefusedWithGitsWords() throws Exception {
		History history = history();
		Path intermediary = this.remotes.resolve(INTERMEDIARY + ".git");
		String diverged = commit(intermediary, emptyTree(intermediary), List.of(), IDENT, "someone else's commit");
		git(intermediary, "update-ref", "refs/heads/main", diverged);

		Run copy = copy(history.confirmed());

		assertThat(copy.exit()).as(copy.toString()).isEqualTo(1);
		assertThat(copy.stdout().lines()).as(copy.toString())
			.anySatisfy(line -> assertThat(line).startsWith("::error::").contains("[rejected]"))
			.anySatisfy(line -> assertThat(line).startsWith("::error::").contains("failed to push some refs"))
			.anySatisfy(line -> assertThat(line).matches(
					Pattern.quote("::error::the push to " + INTERMEDIARY + " failed (git exit ") + "\\d+\\)"));
		assertThat(refs(intermediary)).as("left as it was").containsExactly(diverged + " refs/heads/main");
		assertThat(refs(this.remotes.resolve(WORKING + ".git"))).as("the copy stopped at the first refusal").isEmpty();
	}

	@Test
	void eachPushIsReadBackAndAnotherCommitThereFailsTheRun() throws Exception {
		History history = history();
		Path working = this.remotes.resolve(WORKING + ".git");
		String other = commit(working, emptyTree(working), List.of(), IDENT, "moved under the copy");
		Path hook = working.resolve("hooks").resolve("post-receive");
		Files.writeString(hook, "#!/bin/sh\ngit update-ref refs/heads/main " + other + "\n", StandardCharsets.UTF_8);
		executable(hook);

		Run copy = copy(history.confirmed());

		assertThat(copy.exit()).as(copy.toString()).isEqualTo(1);
		assertThat(copy.stdout().lines()).contains("::error::" + WORKING + " reads back another commit on main");
	}

	// ---------------------------------------------------------------- the object checks

	/**
	 * The unpack limits a fetch is checked under: git's default, under which these small packs are unpacked by
	 * {@code unpack-objects --strict}, and 1, under which they are indexed by {@code index-pack --strict}.
	 */
	static Stream<Arguments> legacyMessages() {
		return IGNORED.stream().flatMap(message -> Stream.of(Arguments.of(message, null), Arguments.of(message, "1")));
	}

	/** Each message the copy lets through (deferred-work 650), alone in a history otherwise well formed. */
	@ParameterizedTest
	@MethodSource("legacyMessages")
	void aLegacyHistoryCopiesPastEachIgnoredMessage(String message, @Nullable String unpackLimit) throws Exception {
		String tip = legacyTip(message);

		Run copy = copy(tip, false, unpackLimit, script -> script);

		assertThat(copy.exit()).as(copy.toString()).isZero();
		assertThat(read(this.temp.resolve("trace.log"))).as("the objects went through the checked path the limit picks")
			.contains(unpackLimit == null ? "unpack-objects" : "index-pack");
		assertThat(refs(this.remotes.resolve(INTERMEDIARY + ".git"))).containsExactly(tip + " refs/heads/main");
		assertThat(refs(this.remotes.resolve(WORKING + ".git"))).containsExactly(tip + " refs/heads/main");
	}

	static Stream<String> ignored() {
		return IGNORED.stream();
	}

	/** Each ignore is needed: with its message taken off the list, the same history fails with that message. */
	@ParameterizedTest
	@MethodSource("ignored")
	void eachIgnoredMessageFailsOnceItsIgnoreIsRemoved(String message) throws Exception {
		String tip = legacyTip(message);
		String list = "for message in " + String.join(" ", IGNORED) + "; do\n";
		List<String> others = IGNORED.stream().filter(ignored -> !ignored.equals(message)).toList();

		Run copy = copy(tip, false, null, script -> {
			assertThat(script).contains(list);
			return script.replace(list, "for message in " + String.join(" ", others) + "; do\n");
		});

		assertThat(copy.exit()).as(copy.toString()).isEqualTo(1);
		assertThat(copy.stdout().lines()).as(copy.toString())
			.anySatisfy(line -> assertThat(line).startsWith("::error::").contains(message));
		assertThat(refs(this.remotes.resolve(INTERMEDIARY + ".git"))).isEmpty();
	}

	static Stream<Arguments> otherErrors() {
		return Stream.of("missingEmail", "zeroPaddedDate", "hasDotgit")
			.flatMap(message -> Stream.of(Arguments.of(message, null), Arguments.of(message, "1")));
	}

	/** Any other object error, a date one too, fails the copy with git's own words as {@code ::error::} lines. */
	@ParameterizedTest
	@MethodSource("otherErrors")
	void anyOtherObjectErrorFailsTheRunWithGitsMessage(String message, @Nullable String unpackLimit) throws Exception {
		String tip = legacyTip(message);

		Run copy = copy(tip, false, unpackLimit, script -> script);

		assertThat(copy.exit()).as(copy.toString()).isEqualTo(1);
		assertThat(copy.stdout().lines()).as(copy.toString())
			.anySatisfy(line -> assertThat(line).startsWith("::error::").contains(message))
			.anySatisfy(line -> assertThat(line).matches("::error::the fetch of the default branch failed \\(git exit \\d+\\)"));
		assertThat(refs(this.remotes.resolve(INTERMEDIARY + ".git"))).isEmpty();
		assertThat(refs(this.remotes.resolve(WORKING + ".git"))).isEmpty();
	}

	/**
	 * A fetch that fails for another reason shows every line git wrote, not only its error lines, as a remote's own
	 * explanation would be; blank lines are left out.
	 */
	@Test
	void aFetchThatFailsShowsEveryLineGitWrote() throws Exception {
		History history = history();

		Run copy = copy(history.confirmed(), false, null,
				script -> script.replace("$GITHUB_REPOSITORY.git", "no-such-source.git"));

		assertThat(copy.exit()).as(copy.toString()).isEqualTo(1);
		assertThat(copy.stdout().lines()).as(copy.toString())
			.allSatisfy(line -> assertThat(line).startsWith("::error::").isNotEqualTo("::error::"))
			.anySatisfy(line -> assertThat(line).startsWith("::error::fatal: ").contains("no-such-source.git"))
			.anySatisfy(line -> assertThat(line).isEqualTo("::error::Please make sure you have the correct access rights"))
			.last()
			.satisfies(line -> assertThat(line).matches("::error::the fetch of the default branch failed \\(git exit \\d+\\)"));
	}

	// ---------------------------------------------------------------- the credentials

	@Test
	void gitTakesEachCredentialThroughAskpassFromItsFile() throws Exception {
		History history = history();

		Run copy = copy(history.confirmed(), true, null, script -> script);

		assertThat(copy.exit()).as(copy.toString()).isZero();
		List<Call> calls = calls();
		String askpass = posix(this.runnerTemp.resolve("askpass.sh"));
		Map<String, String> expected = Map.of("fetch", posix(this.runnerTemp.resolve("github-token")), "push",
				posix(this.runnerTemp.resolve("vibefix-token")), "ls-remote", posix(this.runnerTemp.resolve("vibefix-token")));
		Map<String, String> tokens = Map.of(posix(this.runnerTemp.resolve("github-token")), GITHUB_TOKEN,
				posix(this.runnerTemp.resolve("vibefix-token")), VIBEFIX_TOKEN);
		List<Call> network = calls.stream().filter(call -> expected.containsKey(call.command())).toList();

		assertThat(network).extracting(Call::command).containsExactly("fetch", "push", "ls-remote", "push", "ls-remote");
		for (Call call : network) {
			assertThat(call.values()).as(call.toString())
				.containsEntry("file", expected.get(call.command()))
				.containsEntry("askpass", askpass)
				.containsEntry("prompt", "0")
				.containsEntry("username", "x-access-token")
				.containsEntry("password", tokens.get(expected.get(call.command())));
			assertThat(String.join(" ", call.args())).as("no helper may store or supply a credential")
				.contains("-c credential.helper= " + call.command());
		}
		assertThat(calls).as("no credential on any command line")
			.allSatisfy(call -> assertThat(call.args()).noneMatch(arg -> arg.contains(VIBEFIX_TOKEN) || arg.contains(GITHUB_TOKEN)));
		assertThat(calls).as("no other git call reads a token file")
			.filteredOn(call -> !expected.containsKey(call.command()))
			.allSatisfy(call -> assertThat(call.values()).doesNotContainKey("file"));
	}

	// ---------------------------------------------------------------- running the steps

	/** One step's outcome. */
	private record Run(int exit, String stdout, String stderr) {

		@Override
		public String toString() {
			return "exit " + this.exit + "\n--- stdout\n" + this.stdout + "--- stderr\n" + this.stderr;
		}

	}

	/** One git call as the shim recorded it. */
	private record Call(List<String> args, Map<String, String> values) {

		/** The git command: the first argument after {@code -C dir} and {@code -c name=value} options. */
		String command() {
			for (int at = 0; at < this.args.size(); at++) {
				String arg = this.args.get(at);
				if (arg.equals("-C") || arg.equals("-c")) {
					at++;
				}
				else if (!arg.startsWith("-")) {
					return arg;
				}
			}
			return "";
		}

	}

	private static Map<String, String> inputs(String sha) {
		Map<String, String> inputs = new LinkedHashMap<>();
		inputs.put("operation", "copy");
		inputs.put("correlation", CORRELATION);
		inputs.put("exchange_url", EXCHANGE_URL);
		inputs.put("sha", sha);
		inputs.put("branch", "main");
		inputs.put("intermediary", INTERMEDIARY);
		inputs.put("working", WORKING);
		return inputs;
	}

	private Run check(Map<String, String> inputs, String actor) throws Exception {
		return step("Check the inputs", inputs, actor, script("Check the inputs"), Map.of(), false);
	}

	/**
	 * The exchange step in full: GitHub's ID token endpoint answers {@code idToken}, and vibefix's exchange answers
	 * {@code status} with {@code body}.
	 */
	private Run exchange(int status, String body, String idToken) throws Exception {
		Map<String, String> answers = new LinkedHashMap<>();
		answers.put("ACTIONS_ID_TOKEN_REQUEST_TOKEN", REQUEST_TOKEN);
		answers.put("ACTIONS_ID_TOKEN_REQUEST_URL", ID_TOKEN_URL);
		answers.put("HARNESS_ID_TOKEN", idToken);
		answers.put("HARNESS_EXCHANGE_STATUS", String.valueOf(status));
		answers.put("HARNESS_EXCHANGE_BODY", body);
		answers.put("HARNESS_CURL_LOG", posix(this.temp.resolve("curl.log")));
		return step("Exchange the ID token", inputs("ab".repeat(20)), BOT, script("Exchange the ID token"), answers, false);
	}

	private Run copy(String sha) throws Exception {
		return copy(sha, false, null, script -> script);
	}

	/**
	 * The whole job: the input checks, the exchange, the copy, and the credentials' removal, which runs whatever the copy
	 * did. The copy step's git calls are traced to {@code trace.log}.
	 * @param shim whether the git shim records the copy step's git calls
	 * @param unpackLimit the copy step's {@code transfer.unpackLimit}, or git's default
	 * @param edit a change to the copy step's script
	 */
	private Run copy(String sha, boolean shim, @Nullable String unpackLimit, UnaryOperator<String> edit) throws Exception {
		Map<String, String> inputs = inputs(sha);
		Run check = check(inputs, BOT);
		assertThat(check.exit()).as(check.toString()).isZero();
		Run exchange = exchange(200, "{\"token\":\"" + VIBEFIX_TOKEN + "\"}", ID_TOKEN);
		assertThat(exchange.exit()).as(exchange.toString()).isZero();
		Map<String, String> traced = new LinkedHashMap<>();
		traced.put("GIT_TRACE", posix(this.temp.resolve("trace.log")));
		if (unpackLimit != null) {
			traced.put("GIT_CONFIG_COUNT", "1");
			traced.put("GIT_CONFIG_KEY_0", "transfer.unpackLimit");
			traced.put("GIT_CONFIG_VALUE_0", unpackLimit);
		}
		Run copy = step("Copy the commit", inputs, BOT, edit.apply(script("Copy the commit")), traced, shim);
		Run removed = step("Remove the credentials", inputs, BOT, script("Remove the credentials"), Map.of(), false);
		assertThat(removed.exit()).as(removed.toString()).isZero();
		return copy;
	}

	private static String script(String name) {
		return String.valueOf(steps.stream().filter(step -> name.equals(step.get("name"))).findFirst().orElseThrow().get("run"));
	}

	/** Runs a step's script with its {@code env:} filled from the inputs, and the variables GitHub sets for a step. */
	private Run step(String name, Map<String, String> inputs, String actor, String script, Map<String, String> extra,
			boolean shim) throws Exception {
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
		environment.put("GITHUB_REPOSITORY", SOURCE);
		environment.put("GITHUB_REF", "refs/heads/main");
		environment.put("HARNESS_STUBS", posix(this.stubs));
		Object declared = steps.stream().filter(step -> name.equals(step.get("name"))).findFirst().orElseThrow().get("env");
		if (declared != null) {
			for (Map.Entry<String, Object> variable : asMap(declared).entrySet()) {
				String expression = String.valueOf(variable.getValue());
				Matcher input = INPUT.matcher(expression);
				String value = input.matches() ? inputs.getOrDefault(input.group(1), "")
						: switch (expression) {
							case "${{ github.triggering_actor }}" -> actor;
							case "${{ github.token }}" -> GITHUB_TOKEN;
							default -> fail("the harness does not know " + expression);
						};
				environment.put(variable.getKey(), value);
			}
		}
		environment.putAll(extra);
		if (shim) {
			environment.put("HARNESS_SHIM", posix(shim()));
			environment.put("HARNESS_GIT_LOG", posix(this.temp.resolve("git.log")));
		}
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
	 * The stub {@code curl} and {@code jq} the exchange step runs: GitHub's ID token endpoint and vibefix's exchange as
	 * the test sets them, each request recorded; and the four jq filters the step uses, on flat JSON objects of strings.
	 */
	private Path stubs() throws IOException {
		Path directory = Files.createDirectories(this.temp.resolve("stubs"));
		Path curl = directory.resolve("curl");
		Files.writeString(curl, """
				#!/usr/bin/env bash
				# CopyStepsHarnessTest's curl: GitHub's ID token endpoint, or vibefix's exchange when asked to write the status.
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
				else
				  printf 'exchange\\t%s\\t%s\\n' "$url" "$(cat "$data")" >> "$HARNESS_CURL_LOG"
				  printf '%s' "$HARNESS_EXCHANGE_BODY" > "$out"
				  printf '%s' "$HARNESS_EXCHANGE_STATUS"
				fi
				""", StandardCharsets.UTF_8);
		Path jq = directory.resolve("jq");
		Files.writeString(jq, """
				#!/usr/bin/env bash
				# CopyStepsHarnessTest's jq: the four filters the exchange step uses, on flat JSON objects of strings.
				null=0
				while [ $# -gt 0 ]; do
				  case "$1" in
				    -r) shift ;;
				    -n) null=1; shift ;;
				    *) break ;;
				  esac
				done
				filter="$1"
				shift
				if [ "$null" = 1 ]; then
				  [ "$filter" = '{id_token: env.ID_TOKEN, correlation: env.CORRELATION}' ] || { echo "jq stub: $filter" >&2; exit 3; }
				  printf '{"id_token":"%s","correlation":"%s"}\\n' "$ID_TOKEN" "$CORRELATION"
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

	/** A git first on PATH that records each call's arguments, credential settings and askpass answers. */
	private Path shim() throws IOException {
		Path directory = Files.createDirectories(this.temp.resolve("shim"));
		Path git = directory.resolve("git");
		if (!Files.exists(git)) {
			Files.writeString(git, """
					#!/usr/bin/env bash
					# CopyStepsHarnessTest's git shim: records what each git call of the step received, then runs the
					# real git. git's own inner calls inherit HARNESS_IN_GIT and are not recorded.
					if [ -n "${HARNESS_IN_GIT:-}" ]; then
					  exec "$HARNESS_REAL_GIT" "$@"
					fi
					export HARNESS_IN_GIT=1
					{
					  printf 'args'
					  printf '\\t%s' "$@"
					  printf '\\n'
					  if [ -n "${VIBEFIX_TOKEN_FILE:-}" ]; then
					    printf 'file\\t%s\\n' "$VIBEFIX_TOKEN_FILE"
					    printf 'askpass\\t%s\\n' "${GIT_ASKPASS:-}"
					    printf 'prompt\\t%s\\n' "${GIT_TERMINAL_PROMPT:-}"
					    printf 'username\\t%s\\n' "$("$GIT_ASKPASS" "Username for 'https://github.com': ")"
					    printf 'password\\t%s\\n' "$("$GIT_ASKPASS" "Password for 'https://x-access-token@github.com': ")"
					  fi
					  printf 'end\\n'
					} >> "$HARNESS_GIT_LOG"
					exec "$HARNESS_REAL_GIT" "$@"
					""", StandardCharsets.UTF_8);
			executable(git);
		}
		return directory;
	}

	private List<Call> calls() throws IOException {
		List<Call> calls = new ArrayList<>();
		List<String> args = List.of();
		Map<String, String> values = new LinkedHashMap<>();
		for (String line : Files.readAllLines(this.temp.resolve("git.log"), StandardCharsets.UTF_8)) {
			String[] fields = line.split("\t", -1);
			switch (fields[0]) {
				case "args" -> {
					args = List.of(fields).subList(1, fields.length);
					values = new LinkedHashMap<>();
				}
				case "end" -> calls.add(new Call(args, values));
				default -> values.put(fields[0], fields.length > 1 ? fields[1] : "");
			}
		}
		return calls;
	}

	// ---------------------------------------------------------------- the fixtures

	/**
	 * The Source Repository's history: the default branch {@code main} at three commits, the copy confirming the middle
	 * one; a tag; and a branch with a commit of its own.
	 */
	private record History(String confirmed, String tip, String feature) {
	}

	private History history() throws IOException {
		Path repository = this.remotes.resolve(SOURCE + ".git");
		String tree = readmeTree(repository);
		String first = commit(repository, tree, List.of(), IDENT, "first");
		String confirmed = commit(repository, tree, List.of(first), IDENT, "confirmed");
		String tip = commit(repository, tree, List.of(confirmed), IDENT, "pushed after the confirmation");
		String feature = commit(repository, tree, List.of(first), IDENT, "on another branch");
		git(repository, "update-ref", "refs/heads/main", tip);
		git(repository, "update-ref", "refs/heads/feature", feature);
		git(repository, "update-ref", "refs/tags/v1", first);
		return new History(confirmed, tip, feature);
	}

	/**
	 * A default branch whose second commit holds one fsck finding, {@code message}, with a well-formed commit on top. The
	 * fixture is proved first: {@code git fsck --strict} reports that message.
	 */
	private String legacyTip(String message) throws IOException {
		Path repository = this.remotes.resolve(SOURCE + ".git");
		String tree = readmeTree(repository);
		String first = commit(repository, tree, List.of(), IDENT, "first");
		String legacy = switch (message) {
			case "zeroPaddedFilemode" -> commit(repository, object(repository, "tree", entry("040000", "sub", tree)),
					List.of(first), IDENT, "a directory with a zero-padded mode");
			case "hasDotgit" -> commit(repository, object(repository, "tree", entry("40000", ".git", tree)), List.of(first),
					IDENT, "a .git directory in the tree");
			case "badDate" -> commit(repository, tree, List.of(first), "Lan <lan@example.test> 17x0000000 +0700", message);
			case "badDateOverflow" -> commit(repository, tree, List.of(first),
					"Lan <lan@example.test> 99999999999999999999 +0700", message);
			case "badTimezone" -> commit(repository, tree, List.of(first), "Lan <lan@example.test> 1700000000 0700", message);
			case "missingSpaceBeforeDate" -> commit(repository, tree, List.of(first),
					"Lan <lan@example.test>1700000000 +0700", message);
			case "missingEmail" -> commit(repository, tree, List.of(first), "Lan 1700000000 +0700", message);
			case "zeroPaddedDate" -> commit(repository, tree, List.of(first), "Lan <lan@example.test> 01700000000 +0700",
					message);
			default -> fail("no fixture for " + message);
		};
		String tip = commit(repository, tree, List.of(legacy), IDENT, "after the legacy commit");
		git(repository, "update-ref", "refs/heads/main", tip);
		assertThat(gitOutput(repository, true, "fsck", "--strict")).as("the fixture holds " + message).contains(message);
		return tip;
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
	 * bash to run the steps under: on Windows, Git for Windows' own, found beside the git on PATH or in Program Files
	 * ({@code System32\bash.exe} is WSL's, which sees neither these paths nor this git); elsewhere, bash on PATH.
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
				// ...\Git\cmd\git.exe, ...\Git\bin\git.exe, or ...\Git\mingw64\bin\git.exe
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
