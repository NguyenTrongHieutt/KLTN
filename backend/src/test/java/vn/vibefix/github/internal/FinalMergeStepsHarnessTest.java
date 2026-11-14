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
import java.util.Arrays;
import java.util.Comparator;
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
 * Story 23.9c (deferred-work 626 and 642, the Final Merge half of 646; AD-45, AD-46, FR-37, R1): the Customer's
 * {@code vibefix.yml} {@code final-merge} job, its own {@code run:} scripts taken from
 * {@code deploy/customer/vibefix.yml}, runs under bash as GitHub runs a step
 * ({@code bash --noprofile --norc -eo pipefail}) against local bare repositories behind {@code file://} remotes,
 * exactly as {@link CopyStepsHarnessTest} does for the {@code copy} job. The direction is the mirror of the copy's:
 * here the Customer's own repository is the push target, reached as {@code here} through {@code GITHUB_SERVER_URL}
 * and {@code GITHUB_REPOSITORY}, and the Job's Intermediary Repository is the fetch source. Stub {@code curl} and
 * {@code jq} first on {@code PATH} stand in for GitHub's ID token endpoint and vibefix's exchange. The guards pass
 * and fail as written:
 * <ul>
 * <li>the job's shape: four steps, the last of them {@code if: always()};</li>
 * <li>every input's regular expression, the triggering actor, which must be a {@code [bot]}, the branch, which must
 * be a new one under {@code vibefix/}, and {@code working}, which this job takes none of;</li>
 * <li>the exchange: the ID token asked for with the audience {@code vibefix}, traded at the host the input names,
 * both tokens masked, and the two credential files written from the answers;</li>
 * <li>which credential reaches which remote: the Intermediary is read with vibefix's exchanged token, and every call
 * against {@code here} -- the existence check, the range fetch, the push and the read-back -- with
 * {@code GITHUB_TOKEN}, each taken through {@code GIT_ASKPASS} from its file. A {@code file://} remote asks for no
 * credential, so a git shim first on {@code PATH} records what each call received. This mapping is this job's own:
 * {@link CopyStepsHarnessTest} proves the copy's, which is the other way round;</li>
 * <li>the commit brought back must exist, be a commit, and be an ancestor of what came from the Intermediary. It is
 * deliberately tied to the Intermediary alone and never to this repository's history: the Customer may have pushed
 * here since J7, so asking the commit to descend from this repository's head would refuse exactly that case;</li>
 * <li>the branch must be new here: one that exists already is refused before anything is pushed, and the push itself
 * is never forced -- a branch another writer opens between the existence check and the push makes the push fail
 * instead of overwriting it, which is the case that would go green if the step read {@code push --force}, and a
 * directory/file conflict the exact-ref check cannot see fails it too;</li>
 * <li>the range may touch no file under {@code .github/workflows/}, because {@code GITHUB_TOKEN} holds no workflows
 * permission -- not one added, changed or deleted, not one made and reverted inside the range, and not one changed
 * only by the handover merge's own resolution, which is what {@code -m} is there for ({@code --full-history} is belt
 * and braces beside it). What the Customer changed here after J7 lies outside the range and blocks nothing;</li>
 * <li>the fetch's named fsck exceptions let a legacy history through, under both {@code unpack-objects} and
 * {@code index-pack}, and any other object error fails it;</li>
 * <li>the push is read back: a ref another writer moved, or deleted, under the run fails it.</li>
 * </ul>
 * What a {@code file://} remote cannot show is left to Story 23.12 on real GitHub: that GitHub itself refuses a
 * {@code GITHUB_TOKEN} push whose range touches {@code .github/workflows/}. The harness proves only that vibefix's
 * own guard refuses first. The text of this job is pinned by {@code RunnerWorkflowsTest}. Skipped where bash or git
 * is missing. On Windows, bash is Git for Windows' own, never WSL's {@code System32} one.
 */
class FinalMergeStepsHarnessTest {

	private static final String CORRELATION = "0b7c9a52-5f0e-4f7e-9d43-2d1c9b0e6a11";

	private static final String INTERMEDIARY = "vibefix-dev/job-0b7c9a525f0e4f7e9d432d1c9b0e6a11-intermediary";

	/** The Intermediary's default branch, which is not always {@code main} (AD-11). */
	private static final String INTERMEDIARY_DEFAULT = "develop";

	/** The Customer's own repository: the Final Merge's push target, {@code here}. */
	private static final String SOURCE = "lan-nguyen/so-dat-ban";

	/** The one branch a Final Merge opens here: {@code vibefix/final-<jobId>} (AD-11). */
	private static final String BRANCH = "vibefix/final-0b7c9a525f0e4f7e9d432d1c9b0e6a11";

	/** Another Job's Final Merge branch, left here from before: it blocks nothing and never moves. */
	private static final String OTHER_BRANCH = "vibefix/final-7c1d4e8a2b934f6085ad13c5e2f70b94";

	private static final String BOT = "vibefix-dev-source[bot]";

	private static final String EXCHANGE_URL = "https://exchange.example.test";

	private static final String ID_TOKEN_URL = "https://pipelines.example.test/idtoken?api-version=2.0";

	/** The step that does the work, between the exchange and the credentials' removal. */
	private static final String BRING_BACK = "Bring the Final Merge back";

	/** Every file the last step names, whatever the run did. */
	private static final List<String> CREDENTIALS = List.of("vibefix-token", "github-token", "askpass.sh", "oidc-header",
			"exchange.json", "exchanged.json");

	/** The messages the fetch lets through (deferred-work 650), in the order the workflow lists them. */
	private static final List<String> IGNORED = List.of("zeroPaddedFilemode", "badDate", "badDateOverflow", "badTimezone",
			"missingSpaceBeforeDate");

	/** A well-formed author or committer line's value. */
	private static final String IDENT = "Lan <lan@example.test> 1700000000 +0700";

	/** The file under {@code .github/workflows/} the Source Repository already holds: the published workflow itself. */
	private static final String PUBLISHED = ".github/workflows/vibefix.yml";

	/** Another workflow file, one of the Customer's own. */
	private static final String CI = ".github/workflows/ci.yml";

	/** The range guard's own flags, as the workflow writes them. */
	private static final String LOG_FLAGS = "--name-only --full-history -m refs/heads/vibefix-here";

	/**
	 * Fake credentials, built at run time: GitHub's ID token request token and ID token, the token the exchange answers,
	 * and the run's GITHUB_TOKEN.
	 */
	private static final String REQUEST_TOKEN = "harness-request-" + UUID.randomUUID();

	private static final String ID_TOKEN = "harness-id-" + UUID.randomUUID();

	private static final String VIBEFIX_TOKEN = "harness-vibefix-" + UUID.randomUUID();

	private static final String GITHUB_TOKEN = "harness-github-" + UUID.randomUUID();

	private static final Pattern INPUT = Pattern.compile("\\$\\{\\{ inputs\\.([a-z_]+) }}");

	/** Names as git orders tree entries: by their bytes, never by Java's UTF-16 order. */
	private static final Comparator<String> BYTES = (left, right) -> Arrays
		.compareUnsigned(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));

	private static @Nullable Path bash;

	private static List<Map<String, Object>> steps = List.of();

	@TempDir
	Path temp;

	private Path remotes;

	private Path runnerTemp;

	private Path runner;

	private Path stubs;

	/** The Customer's default branch, which {@code GITHUB_REF} names and the range guard fetches. */
	private String hereDefault = "main";

	@BeforeAll
	static void bashAndGit() throws IOException {
		bash = findBash();
		Assumptions.assumeTrue(bash != null, "no bash to run the Final Merge's scripts under");
		Assumptions.assumeTrue(works(List.of("git", "--version")), "no git to build the fixtures with");
		Assumptions.assumeTrue(works(List.of(bash.toString(), "--noprofile", "--norc", "-c",
				"command -v git && command -v grep && command -v sed && command -v cut && command -v sort")),
				"bash finds no git, grep, sed, cut or sort");
		Map<String, Object> workflow = new Yaml(new SafeConstructor(new LoaderOptions()))
			.load(GitHubStub.published(GitHubStub.CUSTOMER_WORKFLOW));
		steps = jobSteps(asMap(asMap(workflow.get("jobs")).get("final-merge")));
	}

	@BeforeEach
	void repositories() throws IOException {
		this.remotes = Files.createDirectories(this.temp.resolve("remotes"));
		this.runnerTemp = Files.createDirectories(this.temp.resolve("runner-temp"));
		Files.createDirectories(this.temp.resolve("home"));
		Files.writeString(this.temp.resolve("gitconfig"), "");
		this.runner = this.temp.resolve("runner.sh");
		Files.writeString(this.runner, """
				# FinalMergeStepsHarnessTest's runner: a step as GitHub runs it
				# (bash --noprofile --norc -eo pipefail {0}), with the stub curl and jq, and the git shim when
				# HARNESS_SHIM names it, first on PATH.
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
		this.hereDefault = "main";
		git(bare(SOURCE), "symbolic-ref", "HEAD", hereRef());
		git(bare(INTERMEDIARY), "symbolic-ref", "HEAD", "refs/heads/" + INTERMEDIARY_DEFAULT);
	}

	// ---------------------------------------------------------------- the job's shape

	/** AD-45: the four steps, in order, and the one that runs whatever the others did. */
	@Test
	void theJobChecksExchangesBringsBackAndAlwaysRemovesTheCredentials() {
		assertThat(steps).extracting(step -> step.get("name"))
			.containsExactly("Check the inputs", "Exchange the ID token", BRING_BACK, "Remove the credentials");
		assertThat(steps.subList(0, 3)).as("a failure stops the job before the next step")
			.allSatisfy(step -> assertThat(step).doesNotContainKey("if"));
		assertThat(steps.get(3)).as("the credentials go whatever happened").containsEntry("if", "always()");
	}

	// ---------------------------------------------------------------- the inputs

	@ParameterizedTest
	@MethodSource("accepted")
	void theInputChecksAcceptAWellFormedFinalMerge(String input, String value) throws Exception {
		Map<String, String> inputs = inputs("ab".repeat(20));
		inputs.put(input, value);

		Run check = check(inputs, BOT);

		assertThat(check.exit()).as(check.toString()).isZero();
		assertThat(check.stdout()).doesNotContain("::error::");
	}

	/** Every accepted shape, including the longest each regular expression takes. */
	static Stream<Arguments> accepted() {
		return Stream.of(Arguments.of("correlation", CORRELATION), Arguments.of("correlation", "0123abcd"),
				Arguments.of("correlation", "a" + "b".repeat(63)), Arguments.of("exchange_url", EXCHANGE_URL),
				Arguments.of("exchange_url", EXCHANGE_URL + ":8443"), Arguments.of("exchange_url", EXCHANGE_URL + ":99999"),
				Arguments.of("branch", BRANCH), Arguments.of("branch", "vibefix/final-1"),
				Arguments.of("branch", "vibefix/a.b_c-d"), Arguments.of("branch", "vibefix/final/" + UUID.randomUUID()),
				Arguments.of("branch", "vibefix/" + "b".repeat(192)), Arguments.of("intermediary", "vibefix-dev/a.b_c-d"),
				Arguments.of("intermediary", "o/x"), Arguments.of("intermediary", "a".repeat(39) + "/" + "x".repeat(100)),
				Arguments.of("working", ""));
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
		cases.add(Arguments.of("operation", "copy", "this job only brings a Final Merge back"));
		cases.add(Arguments.of("operation", "scan", "this job only brings a Final Merge back"));
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
		// The branch rules every pushed branch keeps, checked before the vibefix/ rule.
		for (String branch : List.of("", "vibefix/a..b", "vibefix//b", "vibefix/main/", "vibefix/main.lock", "-main", ".main",
				"vibefix/main branch", "vibefix/$(id)", "v".repeat(201), "refs/heads/vibefix/main\n")) {
			cases.add(Arguments.of("branch", branch, "branch is refused"));
		}
		// AD-11: a Final Merge opens a vibefix/ branch and nothing else.
		for (String branch : List.of("main", "vibefix", "vibefix-final", "vibefixer/final", "Vibefix/final",
				"release/vibefix/final", "handover/0b7c9a52")) {
			cases.add(Arguments.of("branch", branch, "final-merge pushes a new vibefix/ branch only"));
		}
		for (String repository : List.of("", "owner", "owner/.", "owner/..", "-owner/x", "owner/x/y", "owner/x y",
				"a".repeat(40) + "/x", "owner/" + "x".repeat(101), "owner/$(id)")) {
			cases.add(Arguments.of("intermediary", repository, "intermediary is not owner/name"));
		}
		// The Working Repository is the copy's business: a Final Merge never reads it.
		for (String working : List.of("vibefix-dev/job-working", "owner", " ")) {
			cases.add(Arguments.of("working", working, "final-merge takes no working repository"));
		}
		return cases.stream();
	}

	/** Only a GitHub App starts a Final Merge: the actor is checked before any input, so its refusal is the only one. */
	@ParameterizedTest
	@ValueSource(strings = { "octocat", "bot", "[bot]octocat", "octocat[bot] ", "octocat-bot", "" })
	void onlyABotStartsAFinalMerge(String actor) throws Exception {
		Map<String, String> inputs = inputs("not a sha");

		Run check = check(inputs, actor);

		assertThat(check.exit()).as(check.toString()).isEqualTo(1);
		assertThat(check.stdout().lines()).containsExactly("::error::only vibefix's Source App starts a Final Merge");
	}

	// ---------------------------------------------------------------- the exchange

	/** AD-44: the ID token is asked for with the audience vibefix, traded at the host the input names, and masked. */
	@Test
	void theExchangeTradesTheMaskedIdTokenForTheTwoCredentialFiles() throws Exception {
		Run exchange = exchange(inputs("ab".repeat(20)));

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

	// ---------------------------------------------------------------- the credentials

	/**
	 * AD-45: the Intermediary is read with vibefix's exchanged token and {@code here} with {@code GITHUB_TOKEN}, each
	 * taken through {@code GIT_ASKPASS} from its own file, with no credential helper and no credential on a command
	 * line. A {@code file://} remote asks for none, so the shim asks the askpass script what it would have answered.
	 */
	@Test
	void gitTakesEachCredentialThroughAskpassFromItsFile() throws Exception {
		History history = history();

		Run run = finalMerge(history.merge(), script -> script, true, null, null);

		assertThat(run.exit()).as(run.toString()).isZero();
		List<Call> calls = calls();
		String askpass = posix(this.runnerTemp.resolve("askpass.sh"));
		String vibefix = posix(this.runnerTemp.resolve("vibefix-token"));
		String github = posix(this.runnerTemp.resolve("github-token"));
		Map<String, String> tokens = Map.of(vibefix, VIBEFIX_TOKEN, github, GITHUB_TOKEN);
		List<Call> network = calls.stream().filter(call -> call.values().containsKey("file")).toList();

		assertThat(network.stream().map(call -> call.command() + " <- " + call.values().get("file")))
			.as("vibefix's token reads the Intermediary; GITHUB_TOKEN does everything here")
			.containsExactly("fetch <- " + vibefix, "ls-remote <- " + github, "fetch <- " + github, "push <- " + github,
					"ls-remote <- " + github);
		for (Call call : network) {
			assertThat(call.values()).as(call.toString())
				.containsEntry("askpass", askpass)
				.containsEntry("prompt", "0")
				.containsEntry("username", "x-access-token")
				.containsEntry("password", tokens.get(call.values().get("file")));
			assertThat(String.join(" ", call.args())).as("no helper may store or supply a credential")
				.contains("-c credential.helper= " + call.command());
		}
		assertThat(calls).as("no credential on any command line")
			.allSatisfy(call -> assertThat(call.args())
				.noneMatch(arg -> arg.contains(VIBEFIX_TOKEN) || arg.contains(GITHUB_TOKEN)));
		assertThat(calls.stream().filter(call -> !call.values().containsKey("file")).map(Call::command).distinct())
			.as("only the calls that reach a remote are given a credential")
			.containsExactlyInAnyOrder("init", "config", "cat-file", "merge-base", "log");
	}

	// ---------------------------------------------------------------- the happy path

	/**
	 * AD-45, FR-37: the Intermediary's merge commit comes back as a new {@code vibefix/} branch here, read back, and
	 * every credential file is gone at the end. No other ref moves, and nothing here is overwritten.
	 */
	@Test
	void bringsTheMergeCommitBackToANewVibefixBranchAndRemovesTheCredentials() throws Exception {
		History history = history();
		Path here = here();
		git(here, "update-ref", "refs/heads/" + OTHER_BRANCH, history.start());

		Run run = finalMerge(history.merge());

		assertThat(run.exit()).as(run.toString()).isZero();
		assertThat(refs(here)).as("the one new branch, and every other ref where it was")
			.containsExactlyInAnyOrder(history.start() + " " + hereRef(), history.start() + " refs/heads/" + OTHER_BRANCH,
					history.merge() + " refs/heads/" + BRANCH);
		assertThat(refs(intermediary())).as("the Intermediary is only read")
			.containsExactly(history.merge() + " refs/heads/" + INTERMEDIARY_DEFAULT);
		credentialsRemoved();
	}

	/** The guard names one folder: a path that only looks like a workflow file is no reason to refuse the range. */
	@Test
	void aRangeThatTouchesNoWorkflowFileComesBack() throws Exception {
		Map<String, String> team = changed();
		team.put(".github/workflows.md", "# how the workflow works\n");
		team.put(".github/dependabot.yml", "# dependabot\n");
		History history = history(List.of(team), team);

		Run run = finalMerge(history.merge());

		assertThat(run.exit()).as(run.toString()).isZero();
		assertThat(refs(here())).contains(history.merge() + " refs/heads/" + BRANCH);
	}

	/** The branch fetched for the range is whatever {@code GITHUB_REF} names, which is not always {@code main}. */
	@Test
	void theRangeIsFetchedFromTheBranchGithubRefNames() throws Exception {
		hereDefaultBranch("trunk");
		History history = history();

		Run run = finalMerge(history.merge());

		assertThat(run.exit()).as(run.toString()).isZero();
		assertThat(refs(here())).containsExactlyInAnyOrder(history.start() + " refs/heads/trunk",
				history.merge() + " refs/heads/" + BRANCH);
	}

	/**
	 * The normal case: the Customer kept working here after J7, so the merge commit descends from nothing this
	 * repository's head holds. The range simply starts wherever that head now is.
	 */
	@Test
	void aCustomersOwnCommitAfterJ7DoesNotBlockTheFinalMerge() throws Exception {
		History history = history();
		Map<String, String> own = base();
		own.put("src/booking.js", "// đặt bàn, sửa bên khách\n");
		String head = customerCommitted(own, history.start());

		Run run = finalMerge(history.merge());

		assertThat(run.exit()).as(run.toString()).isZero();
		assertThat(refs(here())).containsExactlyInAnyOrder(head + " " + hereRef(),
				history.merge() + " refs/heads/" + BRANCH);
	}

	/** Its complement: a workflow file the Customer changed here after J7 sits outside the range and blocks nothing. */
	@Test
	void aWorkflowFileTheCustomerChangedHereAfterJ7IsOutsideTheRange() throws Exception {
		History history = history();
		Map<String, String> own = base();
		own.put(PUBLISHED, "# vibefix, as the Customer changed it\n");
		String head = customerCommitted(own, history.start());

		Run run = finalMerge(history.merge());

		assertThat(run.exit()).as(run.toString()).isZero();
		assertThat(refs(here())).containsExactlyInAnyOrder(head + " " + hereRef(),
				history.merge() + " refs/heads/" + BRANCH);
	}

	// ---------------------------------------------------------------- the commit

	/** R1: {@code cat-file -e} is the guard, so the run stops before any of the branch's own checks. */
	@ParameterizedTest
	@ValueSource(strings = { "unknown", "tree", "blob" })
	void aShaThatIsNoCommitOfTheIntermediaryIsNeverPushed(String which) throws Exception {
		History history = history();
		String sha = switch (which) {
			case "tree" -> history.tree();
			case "blob" -> history.blob();
			default -> "0123456789abcdef0123456789abcdef01234567";
		};

		Run run = finalMerge(sha);

		assertThat(run.exit()).as(run.toString()).isNotZero();
		assertThat(run.stdout()).as("no guard of its own spoke: git's own words ended the run").doesNotContain("::error::");
		assertThat(fetchedIntermediary()).as("the fetch had already happened, so the commit check is what refused")
			.isEqualTo(history.merge());
		assertThat(refs(here())).containsExactly(history.start() + " " + hereRef());
	}

	/**
	 * R1: {@code merge-base --is-ancestor} is the guard. The commit is planted in the run's own bare repository, which
	 * the run reinitializes, because the fetch brings nothing but the Intermediary's own history: so
	 * {@code cat-file -e} passes and the ancestry check is the one that refuses.
	 */
	@Test
	void aCommitThatIsNoAncestorOfWhatCameFromTheIntermediaryIsNeverPushed() throws Exception {
		History history = history();
		Path planted = Files.createDirectories(this.runnerTemp.resolve("final.git"));
		git(planted, "init", "--quiet", "--bare");
		String orphan = commit(planted, history.tree(), List.of(), IDENT, "a commit on another root");

		Run run = finalMerge(orphan);

		assertThat(run.exit()).as(run.toString()).isNotZero();
		assertThat(run.stdout()).as("the ancestry check refuses without a word of its own").doesNotContain("::error::");
		assertThat(fetchedIntermediary()).as("the fetch had already happened").isEqualTo(history.merge());
		assertThat(run(List.of("git", "-C", posix(planted), "cat-file", "-e", orphan + "^{commit}")).exit())
			.as("the commit is there, so the ancestry check is the guard that refused")
			.isZero();
		assertThat(refs(here())).containsExactly(history.start() + " " + hereRef());
	}

	// ---------------------------------------------------------------- the branch and the push

	/** AD-11: the Final Merge's branch is new, so one that exists here already is refused before anything is pushed. */
	@Test
	void aFinalBranchThatExistsHereAlreadyIsRefusedBeforeAnyPush() throws Exception {
		History history = history();
		Path here = here();
		git(here, "update-ref", "refs/heads/" + BRANCH, history.start());

		Run run = finalMerge(history.merge());

		assertThat(run.exit()).as(run.toString()).isEqualTo(1);
		assertThat(errors(run)).containsExactly("::error::" + BRANCH + " exists here already");
		assertThat(refs(here)).as("left as it was, never overwritten")
			.containsExactlyInAnyOrder(history.start() + " " + hereRef(), history.start() + " refs/heads/" + BRANCH);
		credentialsRemoved();
	}

	/**
	 * AD-11: nothing is forced. A branch another writer opens between the existence check and the push makes the push
	 * fail with git's own words and leaves their commit where it was; with {@code push --force} this case goes green.
	 */
	@Test
	void aBranchThatAppearsBetweenTheCheckAndThePushIsNotOverwritten() throws Exception {
		History history = history();
		Path here = here();
		String other = commit(here, emptyTree(here), List.of(), IDENT, "someone else's commit");

		Run run = finalMerge(history.merge(), script -> script, true, null, writer("refs/heads/" + BRANCH, other));

		assertThat(run.exit()).as(run.toString()).isNotZero();
		assertThat(run.stderr()).as(run.toString()).contains("[rejected]", "failed to push some refs");
		assertThat(refs(here)).as("the other writer's commit is left as it was")
			.containsExactlyInAnyOrder(history.start() + " " + hereRef(), other + " refs/heads/" + BRANCH);
	}

	/** A directory/file conflict the exact-ref existence check cannot see still fails the push, and creates nothing. */
	@Test
	void aRefUnderTheBranchsOwnNameFailsThePush() throws Exception {
		History history = history();
		Path here = here();
		git(here, "update-ref", "refs/heads/" + BRANCH + "/old", history.start());

		Run run = finalMerge(history.merge());

		assertThat(run.exit()).as(run.toString()).isNotZero();
		assertThat(run.stdout()).as("the existence check saw nothing under that exact name").doesNotContain("::error::");
		assertThat(run.stderr()).as(run.toString()).contains("cannot lock ref", "refs/heads/" + BRANCH + "/old");
		assertThat(refs(here)).containsExactlyInAnyOrder(history.start() + " " + hereRef(),
				history.start() + " refs/heads/" + BRANCH + "/old");
	}

	// ---------------------------------------------------------------- the .github/workflows/ range guard

	/**
	 * Deferred-work 626: {@code GITHUB_TOKEN} holds no workflows permission, so a range that adds, changes or deletes a
	 * file under {@code .github/workflows/} -- nested paths included -- is refused here, with each path named, before
	 * anything is pushed.
	 */
	@ParameterizedTest
	@ValueSource(strings = { "added", "changed", "deleted", "nested" })
	void aRangeThatTouchesAWorkflowFileIsRefusedWithEachPathNamed(String which) throws Exception {
		Map<String, String> team = changed();
		String path = switch (which) {
			case "added" -> CI;
			case "nested" -> ".github/workflows/sub/ci.yml";
			default -> PUBLISHED;
		};
		if (which.equals("deleted")) {
			team.remove(path);
		}
		else {
			team.put(path, "# " + which + "\n");
		}
		History history = history(List.of(team), team);

		Run run = finalMerge(history.merge());

		assertThat(run.exit()).as(run.toString()).isEqualTo(1);
		assertThat(errors(run)).as(run.toString()).containsExactly("::error::" + path, summary());
		assertThat(refs(here())).as("nothing was pushed").containsExactly(history.start() + " " + hereRef());
	}

	/**
	 * Deferred-work 626: several paths each get a line of their own, once, in order, and the summary comes last -- one
	 * of them changed twice inside the range, which {@code sort -u} folds into one line.
	 */
	@Test
	void everyWorkflowFileTheRangeTouchesIsNamedOnceAndTheSummaryLast() throws Exception {
		Map<String, String> first = changed();
		first.put(CI, "# ci\n");
		first.put(PUBLISHED, "# vibefix, as the team changed it\n");
		Map<String, String> second = new LinkedHashMap<>(first);
		second.put(CI, "# ci, changed again inside the range\n");
		History history = history(List.of(first, second), second);

		Run run = finalMerge(history.merge());

		assertThat(run.exit()).as(run.toString()).isEqualTo(1);
		assertThat(errors(run)).as(run.toString()).containsExactly("::error::" + CI, "::error::" + PUBLISHED, summary());
		assertThat(refs(here())).containsExactly(history.start() + " " + hereRef());
	}

	/**
	 * Deferred-work 626: a workflow file added and taken away again inside the range leaves both ends alike, so only the
	 * range itself shows it -- and the range is what this run may not push.
	 */
	@Test
	void aWorkflowFileMadeAndRevertedInsideTheRangeIsStillRefused() throws Exception {
		Map<String, String> added = changed();
		added.put(CI, "# ci\n");
		History history = history(List.of(added, changed()), changed());
		assertThat(gitOutput(intermediary(), false, "rev-parse", history.merge() + "^{tree}").strip())
			.as("both ends of the range hold the same workflow files")
			.isEqualTo(gitOutput(intermediary(), false, "rev-parse", history.team() + "^{tree}").strip());

		Run run = finalMerge(history.merge());

		assertThat(run.exit()).as(run.toString()).isEqualTo(1);
		assertThat(errors(run)).as(run.toString()).containsExactly("::error::" + CI, summary());
		assertThat(refs(here())).containsExactly(history.start() + " " + hereRef());
	}

	/** Deferred-work 626: the commit brought back is a handover merge, whose own resolution may change a workflow file. */
	@Test
	void aWorkflowFileChangedOnlyByTheMergesOwnResolutionIsStillRefused() throws Exception {
		History history = resolution();

		Run run = finalMerge(history.merge());

		assertThat(run.exit()).as(run.toString()).isEqualTo(1);
		assertThat(errors(run)).as(run.toString()).containsExactly("::error::" + PUBLISHED, summary());
		assertThat(refs(here())).containsExactly(history.start() + " " + hereRef());
	}

	/**
	 * Story 23.9a's review: {@code git log} shows a merge commit no diff of its own unless {@code -m} is given, so
	 * {@code -m} is the flag that keeps {@link #aWorkflowFileChangedOnlyByTheMergesOwnResolutionIsStillRefused} --
	 * dropped, alone or with its neighbour, that merge goes through unnoticed. {@code --full-history} is belt and braces
	 * beside it, against a pathspec simplifying the merge away: {@code -m} implies it, so dropping it alone changes
	 * nothing here and the guard still refuses.
	 */
	@ParameterizedTest
	@MethodSource("droppedFlags")
	void onlyTheMergeFlagKeepsTheRangeGuardHonest(String dropped, String replacement, boolean refused) throws Exception {
		History history = resolution();

		Run run = finalMerge(history.merge(), script -> {
			assertThat(script).contains(LOG_FLAGS);
			return script.replace(LOG_FLAGS, replacement);
		});

		if (refused) {
			assertThat(run.exit()).as("without " + dropped + " the guard still refuses").isEqualTo(1);
			assertThat(errors(run)).containsExactly("::error::" + PUBLISHED, summary());
			assertThat(refs(here())).containsExactly(history.start() + " " + hereRef());
		}
		else {
			assertThat(run.exit()).as("without " + dropped + " the guard misses the merge's own resolution").isZero();
			assertThat(run.stdout()).doesNotContain("::error::");
			assertThat(refs(here())).contains(history.merge() + " refs/heads/" + BRANCH);
		}
	}

	/** Each flag of the range guard taken off in turn, and what the guard then sees. */
	static Stream<Arguments> droppedFlags() {
		return Stream.of(Arguments.of("--full-history -m", "--name-only refs/heads/vibefix-here", false),
				Arguments.of("-m", "--name-only --full-history refs/heads/vibefix-here", false),
				Arguments.of("--full-history", "--name-only -m refs/heads/vibefix-here", true));
	}

	// ---------------------------------------------------------------- the object checks

	static Stream<String> ignored() {
		return IGNORED.stream();
	}

	/**
	 * The unpack limits a fetch is checked under: git's default, under which these small packs are unpacked by
	 * {@code unpack-objects --strict}, and 1, under which they are indexed by {@code index-pack --strict}.
	 */
	static Stream<Arguments> legacyMessages() {
		return IGNORED.stream().flatMap(message -> Stream.of(Arguments.of(message, null), Arguments.of(message, "1")));
	}

	/** AD-46: a history vibefix accepted at J7 is not refused at the end of the Job, for each message on the list. */
	@ParameterizedTest
	@MethodSource("legacyMessages")
	void aLegacyHistoryComesBackPastEachIgnoredMessage(String message, @Nullable String unpackLimit) throws Exception {
		Legacy legacy = legacy(message);

		Run run = finalMerge(legacy.merge(), script -> script, false, unpackLimit, null);

		assertThat(run.exit()).as(run.toString()).isZero();
		assertThat(read(this.temp.resolve("trace.log"))).as("the objects went through the checked path the limit picks")
			.contains(unpackLimit == null ? "unpack-objects" : "index-pack");
		assertThat(refs(here())).as("the legacy history came through the fetch and went to the branch")
			.containsExactlyInAnyOrder(legacy.start() + " " + hereRef(), legacy.merge() + " refs/heads/" + BRANCH);
	}

	/** Each ignore is needed: with its message taken off the list, the same history fails the fetch. */
	@ParameterizedTest
	@MethodSource("ignored")
	void eachIgnoredMessageFailsOnceItsIgnoreIsRemoved(String message) throws Exception {
		Legacy legacy = legacy(message);
		String list = "for message in " + String.join(" ", IGNORED) + "; do\n";
		List<String> others = IGNORED.stream().filter(ignored -> !ignored.equals(message)).toList();

		Run run = finalMerge(legacy.merge(), script -> {
			assertThat(script).contains(list);
			return script.replace(list, "for message in " + String.join(" ", others) + "; do\n");
		});

		assertThat(run.exit()).as(run.toString()).isNotZero();
		assertThat(run.stderr()).as(run.toString()).contains(message);
		assertThat(refs(here())).containsExactly(legacy.start() + " " + hereRef());
	}

	static Stream<Arguments> otherErrors() {
		return Stream.of("missingEmail", "zeroPaddedDate", "hasDotgit")
			.flatMap(message -> Stream.of(Arguments.of(message, null), Arguments.of(message, "1")));
	}

	/** AD-46: any other object error fails the fetch with git's own words, and nothing comes back. */
	@ParameterizedTest
	@MethodSource("otherErrors")
	void anyOtherObjectErrorFailsTheFetch(String message, @Nullable String unpackLimit) throws Exception {
		Legacy legacy = legacy(message);

		Run run = finalMerge(legacy.merge(), script -> script, false, unpackLimit, null);

		assertThat(run.exit()).as(run.toString()).isNotZero();
		assertThat(run.stderr()).as(run.toString()).contains(message);
		assertThat(refs(here())).containsExactly(legacy.start() + " " + hereRef());
	}

	// ---------------------------------------------------------------- the read-back

	/** FR-37: the branch is read back, so a ref another writer moved or deleted under the run fails it. */
	@ParameterizedTest
	@ValueSource(strings = { "moved", "deleted" })
	void aRefChangedUnderTheRunFailsTheReadBack(String which) throws Exception {
		History history = history();
		Path here = here();
		String other = commit(here, emptyTree(here), List.of(), IDENT, "changed under the run");
		boolean deleted = which.equals("deleted");
		Path hook = here.resolve("hooks").resolve("post-receive");
		Files.writeString(hook,
				"#!/bin/sh\ngit update-ref " + (deleted ? "-d refs/heads/" + BRANCH : "refs/heads/" + BRANCH + " " + other)
						+ "\n",
				StandardCharsets.UTF_8);
		executable(hook);

		Run run = finalMerge(history.merge());

		assertThat(run.exit()).as(run.toString()).isEqualTo(1);
		assertThat(errors(run)).containsExactly("::error::" + BRANCH + " reads back another commit");
		assertThat(refs(here)).as("the push itself succeeded, and what reads back is the other writer's doing")
			.containsExactlyInAnyOrderElementsOf(deleted ? List.of(history.start() + " " + hereRef())
					: List.of(history.start() + " " + hereRef(), other + " refs/heads/" + BRANCH));
	}

	// ---------------------------------------------------------------- running the steps

	/** One step's outcome, or a whole job's: every step's output in order, and the first non-zero exit. */
	private record Run(int exit, String stdout, String stderr) {

		Run then(Run next) {
			return new Run(this.exit != 0 ? this.exit : next.exit(), this.stdout + next.stdout(), this.stderr + next.stderr());
		}

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
		inputs.put("operation", "final-merge");
		inputs.put("correlation", CORRELATION);
		inputs.put("exchange_url", EXCHANGE_URL);
		inputs.put("sha", sha);
		inputs.put("branch", BRANCH);
		inputs.put("intermediary", INTERMEDIARY);
		inputs.put("working", "");
		return inputs;
	}

	private Run check(Map<String, String> inputs, String actor) throws Exception {
		return step("Check the inputs", inputs, actor, script("Check the inputs"), Map.of(), false);
	}

	/**
	 * The exchange step in full: GitHub's ID token endpoint answers {@code ID_TOKEN}, and vibefix's exchange answers the
	 * token the two credential files are then written from. Every request is recorded in {@code curl.log}.
	 */
	private Run exchange(Map<String, String> inputs) throws Exception {
		Map<String, String> answers = new LinkedHashMap<>();
		answers.put("ACTIONS_ID_TOKEN_REQUEST_TOKEN", REQUEST_TOKEN);
		answers.put("ACTIONS_ID_TOKEN_REQUEST_URL", ID_TOKEN_URL);
		answers.put("HARNESS_ID_TOKEN", ID_TOKEN);
		answers.put("HARNESS_EXCHANGE_STATUS", "200");
		answers.put("HARNESS_EXCHANGE_BODY", "{\"token\":\"" + VIBEFIX_TOKEN + "\"}");
		answers.put("HARNESS_CURL_LOG", posix(this.temp.resolve("curl.log")));
		return step("Exchange the ID token", inputs, BOT, script("Exchange the ID token"), answers, false);
	}

	private Run finalMerge(String sha) throws Exception {
		return finalMerge(sha, script -> script);
	}

	private Run finalMerge(String sha, UnaryOperator<String> edit) throws Exception {
		return finalMerge(sha, edit, false, null, null);
	}

	/**
	 * The whole job: the input checks, the exchange, the Final Merge, and the credentials' removal, which runs whatever
	 * the others did and whose own output counts too. A step that fails stops the job, as GitHub stops it.
	 * @param edit a change to the {@code Bring the Final Merge back} step's script
	 * @param shim whether the git shim records the step's git calls
	 * @param unpackLimit the step's {@code transfer.unpackLimit}, or git's default
	 * @param writer what another writer does to {@code here} just before the step's push, which needs the shim
	 */
	private Run finalMerge(String sha, UnaryOperator<String> edit, boolean shim, @Nullable String unpackLimit,
			@Nullable Path writer) throws Exception {
		Map<String, String> inputs = inputs(sha);
		Run outcome = check(inputs, BOT);
		assertThat(outcome.exit()).as(outcome.toString()).isZero();
		Run exchange = exchange(inputs);
		assertThat(exchange.exit()).as(exchange.toString()).isZero();
		outcome = outcome.then(exchange);
		assertThat(read(this.runnerTemp.resolve("vibefix-token"))).as("the exchange wrote vibefix's token")
			.isEqualTo(VIBEFIX_TOKEN);
		assertThat(read(this.runnerTemp.resolve("github-token"))).as("and the run's own token").isEqualTo(GITHUB_TOKEN);
		assertThat(this.runnerTemp.resolve("askpass.sh")).isRegularFile();
		Map<String, String> extra = new LinkedHashMap<>();
		extra.put("GIT_TRACE", posix(this.temp.resolve("trace.log")));
		if (unpackLimit != null) {
			extra.put("GIT_CONFIG_COUNT", "1");
			extra.put("GIT_CONFIG_KEY_0", "transfer.unpackLimit");
			extra.put("GIT_CONFIG_VALUE_0", unpackLimit);
		}
		if (writer != null) {
			extra.put("HARNESS_BEFORE_PUSH", posix(writer));
		}
		outcome = outcome.then(step(BRING_BACK, inputs, BOT, edit.apply(script(BRING_BACK)), extra, shim));
		Run removed = step("Remove the credentials", inputs, BOT, script("Remove the credentials"), Map.of(), false);
		assertThat(removed.exit()).as(removed.toString()).isZero();
		return outcome.then(removed);
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
		// The mirror of the copy: here the Customer's own repository is the push target, and GITHUB_REF its default branch.
		environment.put("GITHUB_REPOSITORY", SOURCE);
		environment.put("GITHUB_REF", hereRef());
		environment.put("HARNESS_STUBS", posix(this.stubs));
		Object declared = steps.stream().filter(step -> name.equals(step.get("name"))).findFirst().orElseThrow().get("env");
		if (declared != null) {
			for (Map.Entry<String, Object> variable : asMap(declared).entrySet()) {
				String expression = String.valueOf(variable.getValue());
				Matcher input = INPUT.matcher(expression);
				String value;
				if (input.matches()) {
					value = inputs.containsKey(input.group(1)) ? inputs.get(input.group(1))
							: fail("the harness does not know the input " + input.group(1));
				}
				else {
					value = switch (expression) {
						case "${{ github.triggering_actor }}" -> actor;
						case "${{ github.token }}" -> GITHUB_TOKEN;
						default -> fail("the harness does not know " + expression);
					};
				}
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
				# FinalMergeStepsHarnessTest's curl: GitHub's ID token endpoint, or vibefix's exchange when asked to write
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
				else
				  printf 'exchange\\t%s\\t%s\\n' "$url" "$(cat "$data")" >> "$HARNESS_CURL_LOG"
				  printf '%s' "$HARNESS_EXCHANGE_BODY" > "$out"
				  printf '%s' "$HARNESS_EXCHANGE_STATUS"
				fi
				""", StandardCharsets.UTF_8);
		Path jq = directory.resolve("jq");
		Files.writeString(jq, """
				#!/usr/bin/env bash
				# FinalMergeStepsHarnessTest's jq: the four filters the exchange step uses, on flat JSON objects of strings.
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

	/**
	 * A git first on PATH that records each call's arguments, credential settings and askpass answers, and that lets
	 * another writer in just before the step's push when {@code HARNESS_BEFORE_PUSH} names one.
	 */
	private Path shim() throws IOException {
		Path directory = Files.createDirectories(this.temp.resolve("shim"));
		Path git = directory.resolve("git");
		if (!Files.exists(git)) {
			Files.writeString(git, """
					#!/usr/bin/env bash
					# FinalMergeStepsHarnessTest's git shim: records what each git call of the step received, then runs the
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
					if [ -n "${HARNESS_BEFORE_PUSH:-}" ]; then
					  for arg in "$@"; do
					    [ "$arg" = push ] || continue
					    "$HARNESS_BEFORE_PUSH"
					    break
					  done
					fi
					exec "$HARNESS_REAL_GIT" "$@"
					""", StandardCharsets.UTF_8);
			executable(git);
		}
		return directory;
	}

	/** What another writer does to {@code here} between the existence check and the push. */
	private Path writer(String ref, String sha) throws IOException {
		Path script = this.temp.resolve("writer.sh");
		Files.writeString(script, """
				#!/usr/bin/env bash
				# FinalMergeStepsHarnessTest: another writer opens the branch under the run.
				exec "$HARNESS_REAL_GIT" -C "%s" update-ref "%s" "%s"
				""".formatted(posix(here()), ref, sha), StandardCharsets.UTF_8);
		executable(script);
		return script;
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

	/** Only the steps' own {@code ::error::} lines, in the order they were written. */
	private static List<String> errors(Run run) {
		return run.stdout().lines().filter(line -> line.startsWith("::error::")).toList();
	}

	/** The line the range guard ends with. */
	private static String summary() {
		return "::error::the range changes files under .github/workflows/, which this run may not push";
	}

	private void credentialsRemoved() {
		assertThat(CREDENTIALS.stream().map(this.runnerTemp::resolve)).as("every file the last step names is gone")
			.noneMatch(Files::exists);
	}

	// ---------------------------------------------------------------- the fixtures

	/**
	 * The Job's history: the Customer's default branch at the commit copied at J7, and the Intermediary's default branch
	 * at a handover merge of the team's work into it. {@code tree} and {@code blob} are objects of the J7 commit, for
	 * the sha that is no commit.
	 */
	private record History(String start, String team, String merge, String tree, String blob) {
	}

	/** The files every commit of the fixtures starts from, the published workflow among them. */
	private Map<String, String> base() {
		Map<String, String> files = new LinkedHashMap<>();
		files.put("README.md", "# Sổ đặt bàn\n");
		files.put("src/booking.js", "// đặt bàn\n");
		files.put(PUBLISHED, "# vibefix\n");
		return files;
	}

	/** The team's own work: one file of the Customer's, changed, and no workflow file touched. */
	private Map<String, String> changed() {
		Map<String, String> files = base();
		files.put("src/booking.js", "// đặt bàn, đã sửa\n");
		return files;
	}

	/** The plain handover: the team changes one file, and the merge takes their tree as it is. */
	private History history() throws IOException {
		Map<String, String> changed = changed();
		return history(List.of(changed), changed);
	}

	/** A handover whose merge commit changes a workflow file in its own resolution, and in neither parent. */
	private History resolution() throws IOException {
		Map<String, String> merged = changed();
		merged.put(PUBLISHED, "# vibefix, resolved\n");
		return history(List.of(changed()), merged);
	}

	/**
	 * The Customer's default branch at the J7 commit, and the Intermediary's at the handover merge: {@code team} names
	 * the whole tree of each of the team's commits, in order, and {@code merged} the merge's own tree. The Intermediary
	 * takes the J7 commit through a fetch, as the J7 copy gives it. Both repositories are then proved well formed, so
	 * that a slip in the hand-rolled trees fails here and not inside the run's own object checks.
	 */
	private History history(List<Map<String, String>> team, Map<String, String> merged) throws IOException {
		Path here = here();
		String base = tree(here, base());
		String first = commit(here, base, List.of(), IDENT, "first");
		String start = commit(here, base, List.of(first), IDENT, "the commit copied at J7");
		git(here, "update-ref", hereRef(), start);
		Path intermediary = intermediary();
		git(intermediary, "fetch", "--quiet", "--no-tags", fileUrl(this.remotes) + "/" + SOURCE + ".git",
				"+" + hereRef() + ":refs/heads/" + INTERMEDIARY_DEFAULT);
		String tip = start;
		for (int at = 0; at < team.size(); at++) {
			tip = commit(intermediary, tree(intermediary, team.get(at)), List.of(tip), IDENT, "the team's work " + (at + 1));
		}
		String merge = commit(intermediary, tree(intermediary, merged), List.of(start, tip), IDENT,
				"Merge the handover into " + INTERMEDIARY_DEFAULT);
		git(intermediary, "update-ref", "refs/heads/" + INTERMEDIARY_DEFAULT, merge);
		wellFormed(here);
		wellFormed(intermediary);
		return new History(start, tip, merge, base, gitOutput(here, false, "rev-parse", start + ":README.md").strip());
	}

	/** What the Customer pushed on their own default branch after J7, which the range then starts from. */
	private String customerCommitted(Map<String, String> files, String onTopOf) throws IOException {
		Path here = here();
		String commit = commit(here, tree(here, files), List.of(onTopOf), IDENT, "the Customer's own work after J7");
		git(here, "update-ref", hereRef(), commit);
		wellFormed(here);
		return commit;
	}

	/** A legacy history: the J7 commit here, and the handover merge that brings one fsck finding with it. */
	private record Legacy(String start, String merge) {
	}

	/**
	 * A handover whose team commit holds one fsck finding, {@code message}, and nothing under
	 * {@code .github/workflows/}, so that the range guard has nothing to say about it. The fixture is proved first:
	 * {@code git fsck --strict} reports that message on the Intermediary, and nothing here.
	 */
	private Legacy legacy(String message) throws IOException {
		Path here = here();
		String tree = readmeTree(here);
		String first = commit(here, tree, List.of(), IDENT, "first");
		String start = commit(here, tree, List.of(first), IDENT, "the commit copied at J7");
		git(here, "update-ref", hereRef(), start);
		Path intermediary = intermediary();
		git(intermediary, "fetch", "--quiet", "--no-tags", fileUrl(this.remotes) + "/" + SOURCE + ".git",
				"+" + hereRef() + ":refs/heads/" + INTERMEDIARY_DEFAULT);
		String legacy = switch (message) {
			case "zeroPaddedFilemode" -> commit(intermediary, object(intermediary, "tree", entry("040000", "sub", tree)),
					List.of(start), IDENT, "a directory with a zero-padded mode");
			case "hasDotgit" -> commit(intermediary, object(intermediary, "tree", entry("40000", ".git", tree)), List.of(start),
					IDENT, "a .git directory in the tree");
			case "badDate" -> commit(intermediary, tree, List.of(start), "Lan <lan@example.test> 17x0000000 +0700", message);
			case "badDateOverflow" -> commit(intermediary, tree, List.of(start),
					"Lan <lan@example.test> 99999999999999999999 +0700", message);
			case "badTimezone" -> commit(intermediary, tree, List.of(start), "Lan <lan@example.test> 1700000000 0700", message);
			case "missingSpaceBeforeDate" -> commit(intermediary, tree, List.of(start),
					"Lan <lan@example.test>1700000000 +0700", message);
			case "missingEmail" -> commit(intermediary, tree, List.of(start), "Lan 1700000000 +0700", message);
			case "zeroPaddedDate" -> commit(intermediary, tree, List.of(start), "Lan <lan@example.test> 01700000000 +0700",
					message);
			default -> fail("no fixture for " + message);
		};
		String merge = commit(intermediary, tree, List.of(start, legacy), IDENT,
				"Merge the handover into " + INTERMEDIARY_DEFAULT);
		git(intermediary, "update-ref", "refs/heads/" + INTERMEDIARY_DEFAULT, merge);
		wellFormed(here);
		assertThat(gitOutput(intermediary, true, "fsck", "--strict")).as("the fixture holds " + message).contains(message);
		return new Legacy(start, merge);
	}

	/** Proves a fixture repository: {@code git fsck --strict} finds nothing to report. */
	private void wellFormed(Path repository) throws IOException {
		git(repository, "fsck", "--strict");
	}

	private Path here() {
		return this.remotes.resolve(SOURCE + ".git");
	}

	private Path intermediary() {
		return this.remotes.resolve(INTERMEDIARY + ".git");
	}

	/** The Customer's default branch, which {@code GITHUB_REF} names. */
	private String hereRef() {
		return "refs/heads/" + this.hereDefault;
	}

	/** Points the Customer's default branch somewhere other than {@code main}, as {@code GITHUB_REF} then names it. */
	private void hereDefaultBranch(String branch) throws IOException {
		this.hereDefault = branch;
		git(here(), "symbolic-ref", "HEAD", hereRef());
	}

	/** What the run's own bare repository, as the step left it, took from the Intermediary. */
	private String fetchedIntermediary() throws IOException {
		return gitOutput(this.runnerTemp.resolve("final.git"), false, "rev-parse", "refs/heads/vibefix-intermediary").strip();
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
	 * The tree of {@code files}, each a path with {@code /} separators, written with its subtrees. Entries are sorted as
	 * git sorts them: by their bytes, a subtree compared as if its name ended with {@code /}.
	 */
	private String tree(Path repository, Map<String, String> files) throws IOException {
		Map<String, String> blobs = new TreeMap<>(BYTES);
		Map<String, Map<String, String>> subtrees = new LinkedHashMap<>();
		Set<String> directories = new LinkedHashSet<>();
		for (Map.Entry<String, String> file : files.entrySet()) {
			String path = file.getKey();
			assertThat(path.split("/", -1)).as("every segment of " + path + " needs a name").noneMatch(String::isEmpty);
			int slash = path.indexOf('/');
			if (slash < 0) {
				blobs.put(path, object(repository, "blob", file.getValue().getBytes(StandardCharsets.UTF_8)));
			}
			else {
				String directory = path.substring(0, slash);
				directories.add(directory);
				subtrees.computeIfAbsent(directory, name -> new LinkedHashMap<>())
					.put(path.substring(slash + 1), file.getValue());
			}
		}
		Map<String, byte[]> entries = new TreeMap<>(BYTES);
		blobs.forEach((name, sha) -> entries.put(name, entry("100644", name, sha)));
		for (String directory : directories) {
			if (blobs.containsKey(directory)) {
				fail(directory + " cannot be both a file and a folder");
			}
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
			if (!process.waitFor(60, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				return false;
			}
			return process.exitValue() == 0;
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

	private static List<Map<String, Object>> jobSteps(Map<String, Object> job) {
		Object declared = job.get("steps");
		assertThat(declared).as("the job declares its steps").isInstanceOf(List.class);
		return asSteps(declared);
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> asSteps(Object value) {
		return (List<Map<String, Object>>) value;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> asMap(Object value) {
		assertThat(value).isInstanceOf(Map.class);
		return (Map<String, Object>) value;
	}

}
