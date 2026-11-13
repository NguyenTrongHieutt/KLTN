package vn.vibefix.github.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Spike S6 revision and Story 23.4: the Customer's {@code deploy/customer/vibefix.yml} and the runner's
 * {@code deploy/runner/s6-copy.yml} hold the rules the design runs under. Steps run scripts only, with no
 * action; every input reaches a script through {@code env:} only and is checked by a regular expression
 * first; each run asks for an ID token with the audience {@code vibefix}, exchanges it at
 * {@code POST /github/actions/exchange}, and masks both
 * the ID token and the token before the first {@code git}; git reads the token from a file through
 * {@code GIT_ASKPASS}, never pushes with force, and reads every branch it pushes back; the job runs on
 * {@code ubuntu-24.04} for at most 15 minutes, with only the permissions it needs, holds no secret, and
 * uploads nothing. The Customer's {@code scan} takes only its operation and correlation, skips the
 * exchange, runs a pinned gitleaks verified by its sha256 over the default branch's whole history with
 * gitleaks' default rules and the Secret Scan's three Vietnamese rules, and logs only its count.
 */
class RunnerWorkflowsTest {

	private static final String CUSTOMER = "customer/vibefix.yml";

	private static final String RUNNER = "runner/s6-copy.yml";

	private static final Path DEPLOY = Path.of("..", "deploy");

	/** Where each run posts its ID token: vibefix's exchange (Story 23.4). */
	private static final String EXCHANGE = "\"$EXCHANGE_URL/github/actions/exchange\"";

	/** The Secret Scan's rules, which the Customer's gitleaks scan ports three of. */
	private static final Path RULES = Path.of("src", "main", "resources", "scanning", "rules.yaml");

	/** A {@code git} command: at the start of a line or after a separator, not inside a word like GIT_ASKPASS. */
	private static final Pattern GIT = Pattern.compile("(?m)(^|[\\s;&|(])git\\s");

	/** A push refspec that forces: {@code +sha:refs/…}. */
	private static final Pattern FORCED_REFSPEC = Pattern.compile("push[^\\n]*\\n?[^\\n]*\"\\+");

	@ParameterizedTest
	@ValueSource(strings = { CUSTOMER, RUNNER })
	void stepsRunScriptsOnlyAndNoInputReachesAScriptDirectly(String file) throws IOException {
		String text = text(file);
		List<Map<String, Object>> steps = steps(load(text));

		assertThat(steps).isNotEmpty().allSatisfy(step -> {
			assertThat(step).as(String.valueOf(step.get("name"))).doesNotContainKey("uses").containsKey("run");
			assertThat(String.valueOf(step.get("run"))).doesNotContain("${{", "github.event.inputs");
		});
		assertThat(text).doesNotContain("uses:", "extraheader", "x-access-token:", "x-access-token@", "secrets.",
				"VIBEFIX_RUNNER_KEY");
		assertThat(text).doesNotContainPattern("https://[^/\\s\"']*@");
	}

	@ParameterizedTest
	@ValueSource(strings = { CUSTOMER, RUNNER })
	void everyInputIsCheckedByARegularExpressionBeforeAnythingElse(String file) throws IOException {
		Map<String, Object> workflow = load(text(file));
		Map<String, Object> first = steps(workflow).getFirst();
		Map<String, Object> env = asMap(first.get("env"));
		String script = String.valueOf(first.get("run"));

		assertThat(inputs(workflow).keySet()).contains("exchange_url").allSatisfy(input -> assertThat(env)
			.as("the first step takes " + input).containsValue("${{ inputs." + input + " }}"));
		for (String variable : env.keySet()) {
			assertThat(script).as(variable + " is checked").contains("\"$" + variable + "\"");
		}
		assertThat(script).startsWith("set -euo pipefail").contains("=~", "exit 1");
		assertThat(script).contains("url_re='^https://");
	}

	@ParameterizedTest
	@ValueSource(strings = { CUSTOMER, RUNNER })
	void theIdTokenAndTheTokenAreMaskedBeforeTheFirstGit(String file) throws IOException {
		List<Map<String, Object>> steps = steps(load(text(file)));
		String scripts = String.join("\n", scripts(steps));

		Matcher git = GIT.matcher(scripts);
		assertThat(git.find()).as("the workflow runs git").isTrue();
		for (String mask : List.of("::add-mask::$id_token", "::add-mask::$token")) {
			assertThat(scripts.indexOf(mask)).as(mask + " precedes the first git").isNotNegative().isLessThan(git.start());
		}
		assertThat(scripts).contains("\"$ACTIONS_ID_TOKEN_REQUEST_URL&audience=vibefix\"",
				"-H @\"$RUNNER_TEMP/oidc-header\"", "ID_TOKEN=\"$id_token\" jq -n '{id_token: env.ID_TOKEN, correlation: env.CORRELATION}'",
				EXCHANGE, "umask 077");
		assertThat(scripts).as("the ID token never goes on a command line").doesNotContain("--arg id_token");
		assertThat(scripts.indexOf("::add-mask::$id_token")).as("the ID token is masked before it is sent")
			.isLessThan(scripts.indexOf(EXCHANGE));
	}

	/**
	 * Story 23.4: both files post to the production exchange's path, which the spike's probe serves too; the
	 * exchange URL stays an input (no production host is published), and a correlation, a random UUID, passes
	 * the input check.
	 */
	@ParameterizedTest
	@ValueSource(strings = { CUSTOMER, RUNNER })
	void theRunPostsToTheProductionExchangesPathAndTakesAUuidCorrelation(String file) throws IOException {
		String text = text(file);
		String check = String.valueOf(steps(load(text)).getFirst().get("run"));
		Matcher correlation = Pattern.compile("(?m)^correlation_re='([^']+)'$").matcher(check);

		assertThat(EXCHANGE).isEqualTo("\"$EXCHANGE_URL" + ActionsExchangeController.PATH + "\"")
			.contains(OidcExchange.PRODUCTION_PATH);
		// Story 23.5: the Customer's copy and final-merge are jobs of their own, each with its exchange step.
		assertThat(count(text, EXCHANGE)).isEqualTo(file.equals(CUSTOMER) ? 2 : 1);
		assertThat(text).doesNotContain("\"$EXCHANGE_URL/exchange\"", "vibefix.vn", "trycloudflare.com");
		assertThat(correlation.find()).isTrue();
		assertThat(Pattern.compile(correlation.group(1)).matcher(java.util.UUID.randomUUID().toString()).matches())
			.as("a correlation is a random UUID").isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { CUSTOMER, RUNNER })
	void theTokenCheckTakesGitHubsStatelessInstallationTokens(String file) throws IOException {
		String script = String.valueOf(step(steps(load(text(file))), "Exchange the ID token").get("run"));
		Matcher check = Pattern.compile("(?m)^token_re='([^']+)'$").matcher(script);
		assertThat(check.find()).as("the exchange step checks the token it is given").isTrue();
		Pattern tokenRe = Pattern.compile(check.group(1));
		// Built at run time, as fake credentials. GitHub's format since its 2026 rollout is ghs_<App ID>_<JWT>,
		// about 520 characters, the JWT's parts joined by dots and holding dashes and underscores.
		String stateless = String.join("", "ghs", "_", "5408194", "_", String.join(".", "eyJ" + "hbGciOiJSUzI1NiJ9",
				"eyJ" + "pc3MiOi" + "C".repeat(380), "Zm9v-YmFy_" + "s".repeat(76)));
		String legacy = String.join("", "ghs", "_", "L".repeat(36));

		assertThat(stateless.length()).isBetween(500, 4096);
		assertThat(tokenRe.matcher(stateless).matches()).as("a stateless installation token").isTrue();
		assertThat(tokenRe.matcher(legacy).matches()).as("a legacy installation token").isTrue();
		for (String unsafe : List.of("", "ghs_short", legacy + " x", legacy + "'", legacy + "$(id)", legacy + "/x",
				legacy + "\nx", legacy + "\"")) {
			assertThat(tokenRe.matcher(unsafe).matches()).as("refused: [%s]", unsafe).isFalse();
		}
		assertThat(script).as("the length is bounded apart, since a regular expression's count may be capped at 255")
			.contains("if ! [[ \"$token\" =~ $token_re ]] || (( ${#token} > 4096 )); then")
			.doesNotContain("{20,255}");
	}

	@ParameterizedTest
	@ValueSource(strings = { CUSTOMER, RUNNER })
	void gitReadsTheTokenFromAFileNeverForcesAndReadsBackWhatItPushed(String file) throws IOException {
		String scripts = String.join("\n", scripts(steps(load(text(file)))));

		assertThat(scripts).contains("GIT_ASKPASS=\"$RUNNER_TEMP/askpass.sh\"", "cat \"$VIBEFIX_TOKEN_FILE\"",
				"printf '%s' \"$token\" > \"$RUNNER_TEMP/vibefix-token\"", "transfer.fsckObjects true", "--no-tags",
				"merge-base --is-ancestor \"$SHA\"", "credential.helper= push", "ls-remote");
		assertThat(scripts).doesNotContain("--force", "push -f", "--mirror", "\"+$SHA", "credential.helper store");
		assertThat(scripts).doesNotContainPattern(FORCED_REFSPEC);
		assertThat(count(scripts, "credential.helper= push")).as("each push is read back")
			.isEqualTo(count(scripts, "| cut -f1)\""));
	}

	@Test
	void theCustomersFinalMergePushesOnlyANewVibefixBranch() throws IOException {
		List<Map<String, Object>> steps = jobSteps(asMap(asMap(load(text(CUSTOMER)).get("jobs")).get("final-merge")));
		String check = String.valueOf(steps.getFirst().get("run"));
		String script = String.valueOf(step(steps, "Bring the Final Merge back").get("run"));

		assertThat(check).contains("[[ \"$BRANCH\" == vibefix/* ]]", "[ -z \"$WORKING\" ]");
		assertThat(script).contains("+HEAD:refs/heads/vibefix-intermediary", "[ -z \"$existing\" ]",
				"VIBEFIX_TOKEN_FILE=\"$RUNNER_TEMP/github-token\"");
		assertThat(script.indexOf("[ -z \"$existing\" ]")).as("the branch must be new before the push")
			.isLessThan(script.indexOf("credential.helper= push"));
		assertThat(steps).extracting(step -> step.get("name"))
			.containsExactly("Check the inputs", "Exchange the ID token", "Bring the Final Merge back", "Remove the credentials");
		assertThat(jobSteps(asMap(asMap(load(text(CUSTOMER)).get("jobs")).get("copy")))).extracting(step -> step.get("name"))
			.containsExactly("Check the inputs", "Exchange the ID token", "Copy the commit", "Remove the credentials");
		assertThat(steps(load(text(CUSTOMER)))).as("no step decides by the operation: each job is one")
			.allSatisfy(step -> assertThat(step.get("if")).isIn(null, "always()"));
	}

	/**
	 * Story 23.5 (deferred-work 630): the copy and the Final Merge first refuse a run that no GitHub App started, by its
	 * triggering actor; the exchange holds the exact Source App bot, so the file names no bot and no exchange host.
	 */
	@Test
	void theCopyAndTheFinalMergeFirstRefuseARunNoGitHubAppStarted() throws IOException {
		String text = text(CUSTOMER);
		Map<String, Object> jobs = asMap(load(text).get("jobs"));
		String guard = "[[ \"$TRIGGERING_ACTOR\" == *'[bot]' ]] || {";

		for (String name : List.of("copy", "final-merge")) {
			Map<String, Object> first = jobSteps(asMap(jobs.get(name))).getFirst();
			String script = String.valueOf(first.get("run"));
			assertThat(asMap(first.get("env"))).as(name).containsEntry("TRIGGERING_ACTOR", "${{ github.triggering_actor }}");
			assertThat(script).as(name + " checks the actor before anything else")
				.startsWith("set -euo pipefail\n# Only a GitHub App starts")
				.contains(guard);
			assertThat(script.indexOf(guard)).as(name).isLessThan(script.indexOf("correlation_re="));
		}
		String scan = String.valueOf(jobSteps(asMap(jobs.get("scan"))).getFirst().get("run"));
		assertThat(scan).as("the Customer may start a scan by hand").doesNotContain("TRIGGERING_ACTOR");
		assertThat(text).as("no bot login and no exchange host").doesNotContain("[bot]\"", "vibefix-dev-source[bot]",
				"trycloudflare.com", "vibefix.vn");
	}

	/**
	 * Story 23.6c (deferred-work 650): the copy keeps git's object checks on and lets through, on fetch only, exactly the
	 * messages a legacy history written by older tools holds, with that rationale and its assumption in the file. Any
	 * other object error fails the run, and so does a failed push, each with every line git wrote (GitHub's
	 * {@code remote:} lines too) as {@code ::error::} lines. {@code CopyStepsHarnessTest} runs it.
	 */
	@Test
	void theCopyChecksEveryObjectAndLetsThroughOnlyTheNamedLegacyMessages() throws IOException {
		String copy = String.valueOf(step(jobSteps(asMap(asMap(load(text(CUSTOMER)).get("jobs")).get("copy"))),
				"Copy the commit").get("run"));
		Matcher list = Pattern.compile("(?m)^for message in ([A-Za-z ]+); do$").matcher(copy);
		String check = "git -C \"$repository\" config transfer.fsckObjects true\n";
		String ignore = "  git -C \"$repository\" config \"fetch.fsck.$message\" ignore\ndone\n";
		String fetch = "fetch --quiet --no-tags";

		assertThat(list.find()).as("the copy names its ignore list").isTrue();
		assertThat(list.group(1).split(" ")).containsExactly("zeroPaddedFilemode", "badDate", "badDateOverflow",
				"badTimezone", "missingSpaceBeforeDate");
		assertThat(copy).contains(check, ignore);
		assertThat(copy.indexOf(check)).as("the checks are on before the list").isLessThan(list.start());
		assertThat(copy.indexOf(ignore)).as("the list is set before the fetch").isLessThan(copy.indexOf(fetch));
		assertThat(count(copy, "fsck")).as("no other object-check setting: no skip list, nothing turned off").isEqualTo(2);
		assertThat(copy).doesNotContain("skipList", "receive.fsck", "fsckObjects false", "-c fetch.fsck");
		assertThat(copy.substring(0, list.start())).as("the list's rationale and its assumption")
			.contains("still reads the same way", "[ASSUMPTION; Story 23.12 confirms on real GitHub]");
		assertThat(copy).as("a failed fetch or push says why with every line git wrote")
			.contains("git_errors() { grep -v '^[[:space:]]*$' \"$1\" | sed 's/^/::error::/' || true; }\n",
					"2> \"$RUNNER_TEMP/fetch.log\" || fetched=$?\nif [ \"$fetched\" != 0 ]; then\n"
							+ "  git_errors \"$RUNNER_TEMP/fetch.log\"\n"
							+ "  echo \"::error::the fetch of the default branch failed (git exit $fetched)\"\n  exit 1\nfi\n",
					"\"$SHA:refs/heads/$BRANCH\" 2> \"$RUNNER_TEMP/push.log\" || pushed=$?\n  if [ \"$pushed\" != 0 ]; then\n"
							+ "    git_errors \"$RUNNER_TEMP/push.log\"\n"
							+ "    echo \"::error::the push to $target failed (git exit $pushed)\"\n    exit 1\n  fi\n");
	}

	@ParameterizedTest
	@ValueSource(strings = { CUSTOMER, RUNNER })
	void theJobIsBoundedWithOnlyThePermissionsItNeeds(String file) throws IOException {
		Map<String, Object> workflow = load(text(file));
		Map<String, Object> jobs = asMap(workflow.get("jobs"));

		assertThat(String.valueOf(workflow.get("run-name"))).contains("${{ inputs.correlation }}");
		assertThat(on(workflow)).containsOnlyKeys("workflow_dispatch");
		if (file.equals(CUSTOMER)) {
			// Nothing by default, and a job per operation (Story 23.5, deferred-work 630): the copy may only read here
			// and ask for an ID token, the Final Merge may write here and ask for one, and the scan may only read.
			assertThat(workflow.get("permissions")).isEqualTo(Map.of());
			assertThat(jobs).containsOnlyKeys("copy", "final-merge", "scan");
			assertThat(asMap(jobs.get("copy"))).containsEntry("if", "inputs.operation == 'copy'")
				.containsEntry("permissions", Map.of("contents", "read", "id-token", "write"));
			assertThat(asMap(jobs.get("final-merge"))).containsEntry("if", "inputs.operation == 'final-merge'")
				.containsEntry("permissions", Map.of("contents", "write", "id-token", "write"));
			assertThat(asMap(jobs.get("scan"))).containsEntry("if", "inputs.operation == 'scan'")
				.containsEntry("permissions", Map.of("contents", "read"));
		}
		else {
			assertThat(workflow.get("permissions")).isEqualTo(Map.of("contents", "read", "id-token", "write"));
			assertThat(jobs).hasSize(1);
			assertThat(asMap(jobs.values().iterator().next())).doesNotContainKey("permissions");
		}
		jobs.forEach((name, value) -> {
			Map<String, Object> job = asMap(value);
			assertThat(job.get("runs-on")).as(name).isEqualTo("ubuntu-24.04");
			assertThat(job.get("timeout-minutes")).as(name).isEqualTo(15);
			Map<String, Object> last = jobSteps(job).getLast();
			assertThat(last.get("if")).as(name).isEqualTo("always()");
			assertThat(String.valueOf(last.get("run"))).as(name).contains("rm -f", "askpass.sh");
			if (!name.equals("scan")) {
				assertThat(String.valueOf(last.get("run"))).as(name).contains("vibefix-token");
			}
		});
		assertThat(text(file)).doesNotContain("artifact", "result.json");
	}

	@ParameterizedTest
	@ValueSource(strings = { CUSTOMER, RUNNER })
	void everyInputTheProbeSendsIsDeclared(String file) throws IOException {
		Map<String, Object> inputs = inputs(load(text(file)));

		List<String> expected = file.equals(CUSTOMER)
				? List.of("operation", "correlation", "exchange_url", "sha", "branch", "intermediary", "working")
				: List.of("correlation", "exchange_url", "source_repository", "source_branch", "sha", "target_repository",
						"target_branch");
		// A scan needs only its operation, and a Customer may start one by hand: the other inputs are optional,
		// and each job checks them.
		Set<String> optional = file.equals(CUSTOMER)
				? Set.of("correlation", "exchange_url", "sha", "branch", "intermediary", "working") : Set.of();
		assertThat(inputs.keySet()).containsExactlyElementsOf(expected);
		inputs.forEach((name, input) -> {
			Map<String, Object> declared = asMap(input);
			if (name.equals("operation")) {
				assertThat(declared).containsEntry("type", "choice").containsEntry("required", true)
					.containsEntry("options", List.of("copy", "final-merge", "scan"));
			}
			else if (optional.contains(name)) {
				assertThat(declared).as(name).containsEntry("type", "string").containsEntry("required", false)
					.containsEntry("default", "");
			}
			else {
				assertThat(declared).as(name).containsEntry("type", "string").containsEntry("required", true);
			}
		});
	}

	@Test
	void eachCustomerJobChecksEveryInputFirst() throws IOException {
		Map<String, Object> workflow = load(text(CUSTOMER));
		Map<String, Object> jobs = asMap(workflow.get("jobs"));

		jobs.forEach((name, job) -> {
			Map<String, Object> first = jobSteps(asMap(job)).getFirst();
			Map<String, Object> env = asMap(first.get("env"));
			String script = String.valueOf(first.get("run"));
			assertThat(first.get("name")).as(name).isEqualTo("Check the inputs");
			assertThat(inputs(workflow).keySet()).allSatisfy(input -> assertThat(env).as(name + " takes " + input)
				.containsValue("${{ inputs." + input + " }}"));
			for (String variable : env.keySet()) {
				assertThat(script).as(name + " checks " + variable).contains("\"$" + variable + "\"");
			}
			assertThat(script).as(name).startsWith("set -euo pipefail").contains("exit 1");
		});
		String copy = String.valueOf(jobSteps(asMap(jobs.get("copy"))).getFirst().get("run"));
		assertThat(copy).as("the copy needs every input, the Working Repository too").contains(
				"[ \"$OPERATION\" = copy ] || {", "[[ \"$CORRELATION\" =~ $correlation_re ]] || {",
				"[[ \"$EXCHANGE_URL\" =~ $url_re ]] || {", "[[ \"$SHA\" =~ $sha_re ]] || {", "[[ \"$BRANCH\" =~ $branch_re",
				"[[ \"$INTERMEDIARY\" =~ $repository_re", "[[ \"$WORKING\" =~ $repository_re")
			.doesNotContain("scan", "[ -z \"$WORKING\" ]", "vibefix/*");
		String finalMerge = String.valueOf(jobSteps(asMap(jobs.get("final-merge"))).getFirst().get("run"));
		assertThat(finalMerge).as("the Final Merge needs every input but the Working Repository, and a new vibefix/ branch")
			.contains("[ \"$OPERATION\" = final-merge ] || {", "[[ \"$CORRELATION\" =~ $correlation_re ]] || {",
					"[[ \"$EXCHANGE_URL\" =~ $url_re ]] || {", "[[ \"$SHA\" =~ $sha_re ]] || {", "[[ \"$BRANCH\" =~ $branch_re",
					"[[ \"$INTERMEDIARY\" =~ $repository_re", "[ -z \"$WORKING\" ] || {", "[[ \"$BRANCH\" == vibefix/* ]]")
			.doesNotContain("scan", "[[ \"$WORKING\" =~");
		String scan = String.valueOf(jobSteps(asMap(jobs.get("scan"))).getFirst().get("run"));
		assertThat(scan).as("a scan takes its operation, an empty or valid correlation, and every copy input empty").contains(
				"[ \"$OPERATION\" = scan ] || {",
				"[ -z \"$CORRELATION\" ] || [[ \"$CORRELATION\" =~ $correlation_re ]] || {",
				"for empty in \"$EXCHANGE_URL\" \"$SHA\" \"$BRANCH\" \"$INTERMEDIARY\" \"$WORKING\"; do", "[ -z \"$empty\" ] || {");
	}

	@Test
	void theScanRunsInAJobOfItsOwnWithNoVibefixToken() throws IOException {
		Map<String, Object> workflow = load(text(CUSTOMER));
		Map<String, Object> jobs = asMap(workflow.get("jobs"));
		Map<String, Object> copy = step(jobSteps(asMap(jobs.get("copy"))), "Exchange the ID token");
		Map<String, Object> finalMerge = step(jobSteps(asMap(jobs.get("final-merge"))), "Exchange the ID token");
		List<Map<String, Object>> scan = scanSteps();

		assertThat(steps(workflow)).as("only the copy's and the Final Merge's exchanges ask for an ID token")
			.filteredOn(step -> String.valueOf(step.get("run")).contains("ACTIONS_ID_TOKEN_REQUEST"))
			.containsExactly(copy, finalMerge);
		assertThat(copy.get("run")).as("the same exchange in both").isEqualTo(finalMerge.get("run"));
		assertThat(scan).extracting(step -> step.get("name"))
			.containsExactly("Check the inputs", "Download gitleaks", "Scan with gitleaks", "Remove the credentials");
		for (Map<String, Object> step : scan) {
			assertThat(String.valueOf(step.get("run"))).as(String.valueOf(step.get("name")))
				.doesNotContain("ACTIONS_ID_TOKEN_REQUEST", "/exchange", "vibefix-token");
			if (!"Check the inputs".equals(step.get("name")) && step.get("env") != null) {
				assertThat(asMap(step.get("env")).values()).as("no input reaches " + step.get("name"))
					.noneMatch(value -> String.valueOf(value).contains("inputs."));
			}
		}
		String script = String.valueOf(step(scan, "Scan with gitleaks").get("run"));
		int gitleaks = script.indexOf("/gitleaks/gitleaks\" git ");
		assertThat(script.indexOf("unset GITHUB_TOKEN\n")).as("gitleaks runs without the token").isNotNegative()
			.isLessThan(gitleaks);
		assertThat(script.indexOf("rm -f \"$RUNNER_TEMP/github-token\"")).as("the token file is gone before gitleaks runs")
			.isNotNegative()
			.isLessThan(gitleaks);
	}

	@Test
	void theScanRunsAPinnedGitleaksVerifiedBeforeItIsExtracted() throws IOException {
		Map<String, Object> download = step(scanSteps(), "Download gitleaks");
		Map<String, Object> env = asMap(download.get("env"));
		String script = String.valueOf(download.get("run"));

		assertThat(env).containsEntry("GITLEAKS_URL",
				"https://github.com/gitleaks/gitleaks/releases/download/v8.30.1/gitleaks_8.30.1_linux_x64.tar.gz");
		assertThat(String.valueOf(env.get("GITLEAKS_SHA256"))).as("a pinned sha256").matches("[0-9a-f]{64}");
		assertThat(text(CUSTOMER)).as("a comment names the release's checksums file")
			.contains("# https://github.com/gitleaks/gitleaks/releases/download/v8.30.1/gitleaks_8.30.1_checksums.txt");
		assertThat(script).startsWith("set -euo pipefail")
			.contains("curl -sS --fail", "\"$GITLEAKS_URL\"", "\"$GITLEAKS_SHA256\"");
		assertThat(count(script, "tar -x")).isOne();
		assertThat(script.indexOf("| sha256sum -c ")).as("verified before it is extracted").isNotNegative()
			.isLessThan(script.indexOf("tar -x"));
	}

	@Test
	void theScanReadsTheDefaultBranchsWholeHistoryRedactedAndLogsOnlyTheCount() throws IOException {
		Map<String, Object> scan = step(scanSteps(), "Scan with gitleaks");
		String script = String.valueOf(scan.get("run"));
		String countEcho = "echo \"" + SpikeS6Command.COUNT_PREFIX + " head=$head findings=$count\"";
		int countLine = script.indexOf(countEcho);
		int fail = script.indexOf("fail() {\n");
		String failure = script.substring(fail, script.indexOf("\n}\n", fail));

		assertThat(asMap(scan.get("env"))).containsOnly(Map.entry("GITHUB_TOKEN", "${{ github.token }}"),
				Map.entry("DEFAULT_BRANCH", "${{ github.event.repository.default_branch }}"));
		assertThat(script).startsWith("set -euo pipefail")
			.contains("if [ -z \"$DEFAULT_BRANCH\" ] || ! git check-ref-format --branch \"$DEFAULT_BRANCH\" > /dev/null 2>&1; then",
					"fetch --quiet --no-tags", "VIBEFIX_TOKEN_FILE=\"$RUNNER_TEMP/github-token\"",
					"\"+refs/heads/$DEFAULT_BRANCH:refs/heads/vibefix-scan\"", "2> \"$RUNNER_TEMP/fetch.log\" || fetched=$?",
					"fail \"the fetch of the default branch failed (git exit $fetched)\" \"$RUNNER_TEMP/fetch.log\"",
					"head=\"$(git -C \"$repository\" rev-parse --verify --quiet 'refs/heads/vibefix-scan^{commit}'")
			.as("a read-only fetch, which takes a legacy history; and git, not a regular expression, judges the name")
			.doesNotContain("transfer.fsckObjects", "branch_re", "--depth", "--shallow", "--log-opts");
		assertThat(script).contains("--config \"$RUNNER_TEMP/gitleaks.toml\" --redact --ignore-gitleaks-allow",
				"--report-format json", "--exit-code 42", "\nstatus=0\n", "> \"$RUNNER_TEMP/gitleaks.log\" 2>&1 || status=$?",
				"[ \"$status\" != 0 ] && [ \"$status\" != 42 ]", "[ -s \"$RUNNER_TEMP/gitleaks.log\" ]");
		assertThat(failure).as("a failure's reason goes to the summary, and one ::error:: line to the log")
			.contains(">> \"$GITHUB_STEP_SUMMARY\"", "tail -n 20 \"$2\" | jq -Rr '@html'", "echo \"::error::$1;");
		assertThat(count(script, "tail ")).as("no tool output is tailed to the log").isOne();
		assertThat(script.lines().map(String::strip).filter(line -> line.startsWith("echo ")))
			.as("the log gets ::error:: lines and the count line only")
			.allSatisfy(line -> assertThat(line.startsWith("echo \"::error::") || line.equals(countEcho)).as(line).isTrue());
		assertThat(script).as("the summary names the commit, and each finding's rule, file, short commit and line")
			.contains(">> \"$GITHUB_STEP_SUMMARY\"", "\\(env.HEAD_SHA)", "\\(.RuleID | cell)", "\\(.File | cell)",
					"\\(.Commit[0:7] | cell)", "\\(.StartLine | cell)", "| Rule | File | Commit | Line |")
			.doesNotContain("Secret", "Match");
		assertThat(count(script, SpikeS6Command.COUNT_PREFIX)).as("one count line").isOne();
		assertThat(countLine).isPositive();
		for (String before : List.of("fail \"the gitleaks scan failed", "fail \"the gitleaks report cannot be read\"",
				"fail \"the gitleaks exit code and its report disagree\"", "' \"$RUNNER_TEMP/gitleaks.json\" >> \"$GITHUB_STEP_SUMMARY\"")) {
			assertThat(script.indexOf(before)).as(before + " comes before the count line").isNotNegative()
				.isLessThan(countLine);
		}
		assertThat(script.indexOf("[ \"$count\" = 0 ] || {")).as("findings fail the run after the count line")
			.isGreaterThan(countLine);
	}

	@Test
	void theScanRulesExtendGitleaksDefaultsWithTheSecretScansVietnameseAndStatelessTokenRules() throws IOException {
		String script = String.valueOf(step(scanSteps(), "Scan with gitleaks").get("run"));
		int start = script.indexOf("<<'RULES'\n");
		String toml = script.substring(start + "<<'RULES'\n".length(), script.indexOf("\nRULES\n", start));
		Map<String, Object> rulesYaml = asMap(new Yaml(new SafeConstructor(new LoaderOptions()))
			.load(Files.readString(RULES, StandardCharsets.UTF_8)));
		// gitleaks reads keywords per changed hunk, where MoMo's and ZaloPay's names are often missing: their
		// ported rules keep none, a deliberate difference from rules.yaml. VNPay's pattern holds its keywords.
		Set<String> keywordsDropped = Set.of("momo-secret-key", "zalopay-key");

		assertThat(toml).contains("[extend]\nuseDefault = true\n");
		Map<String, Ported> ported = new LinkedHashMap<>();
		for (String block : toml.split("\\[\\[rules\\]\\]\n")) {
			Matcher id = Pattern.compile("(?m)^id = \"([^\"]+)\"$").matcher(block);
			if (!id.find()) {
				continue;
			}
			Matcher regex = Pattern.compile("(?m)^regex = '''(.+)'''$").matcher(block);
			Matcher group = Pattern.compile("(?m)^secretGroup = (\\d+)$").matcher(block);
			Matcher allowlist = Pattern
				.compile("(?m)^\\[\\[rules\\.allowlists\\]\\]\nregexTarget = \"secret\"\nregexes = \\['''(.+)'''\\]$")
				.matcher(block);
			assertThat(regex.find() && group.find() && allowlist.find()).as(block).isTrue();
			Matcher keywords = Pattern.compile("(?m)^keywords = \\[(.*)\\]$").matcher(block);
			List<String> words = null;
			if (keywords.find()) {
				words = new ArrayList<>();
				Matcher word = Pattern.compile("\"([^\"]*)\"").matcher(keywords.group(1));
				while (word.find()) {
					words.add(word.group(1));
				}
			}
			ported.put(id.group(1), new Ported(regex.group(1), group.group(1), words, allowlist.group(1)));
		}
		// gitleaks 8.30.1's default github-app-token rule reads only the legacy 36-character token, so the
		// stateless format of 2026 (ghs_<App ID>_<JWT>) is ported from rules.yaml too.
		assertThat(ported).containsOnlyKeys("vnpay-hash-secret", "momo-secret-key", "zalopay-key",
				"github-app-token-stateless");
		for (Object rule : (List<?>) rulesYaml.get("rules")) {
			Map<String, Object> source = asMap(rule);
			String id = String.valueOf(source.get("id"));
			Ported port = ported.remove(id);
			if (port == null) {
				continue;
			}
			assertThat(port.pattern()).as(id + "'s pattern").isEqualTo(source.get("pattern"));
			assertThat(port.group()).as("the one capturing group: the credential").isEqualTo("1");
			assertThat(Pattern.compile(String.valueOf(source.get("pattern"))).matcher("").groupCount()).isOne();
			assertThat(port.placeholders()).as(id + " skips the Secret Scan's placeholders").isEqualTo(rulesYaml.get("placeholders"));
			if (keywordsDropped.contains(id)) {
				assertThat(port.keywords()).as(id + " keeps no keyword").isNull();
				assertThat((List<?>) source.get("keywords")).as(id + " has keywords in rules.yaml").isNotEmpty();
			}
			else {
				assertThat(port.keywords()).as(id + "'s keywords").isEqualTo(source.get("keywords"));
			}
			assertThat(source).as("no part of the rule is left behind").containsOnlyKeys("id", "type", "keywords", "pattern");
		}
		assertThat(ported).as("every ported rule is in rules.yaml").isEmpty();
	}

	/** One rule of the scan's gitleaks config: its pattern, capture group, keywords if any, and placeholders. */
	private record Ported(String pattern, String group, @Nullable List<String> keywords, String placeholders) {
	}

	private static Map<String, Object> step(List<Map<String, Object>> steps, String name) {
		return steps.stream().filter(step -> name.equals(step.get("name"))).findFirst().orElseThrow();
	}

	/** The steps of the Customer's scan job. */
	private static List<Map<String, Object>> scanSteps() throws IOException {
		return jobSteps(asMap(asMap(load(text(CUSTOMER)).get("jobs")).get("scan")));
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> jobSteps(Map<String, Object> job) {
		return (List<Map<String, Object>>) job.get("steps");
	}

	private static String text(String file) throws IOException {
		return Files.readString(DEPLOY.resolve(file), StandardCharsets.UTF_8);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> load(String text) {
		return (Map<String, Object>) new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
	}

	/** YAML 1.1 reads the key {@code on} as {@code true}. */
	private static Map<String, Object> on(Map<String, Object> workflow) {
		return asMap(workflow.containsKey("on") ? workflow.get("on") : workflow.get(Boolean.TRUE));
	}

	private static Map<String, Object> inputs(Map<String, Object> workflow) {
		return asMap(asMap(on(workflow).get("workflow_dispatch")).get("inputs"));
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> steps(Map<String, Object> workflow) {
		Map<?, ?> jobs = (Map<?, ?>) workflow.get("jobs");
		List<Map<String, Object>> steps = new ArrayList<>();
		for (Object job : jobs.values()) {
			steps.addAll((List<Map<String, Object>>) ((Map<?, ?>) job).get("steps"));
		}
		return steps;
	}

	private static List<String> scripts(List<Map<String, Object>> steps) {
		List<String> scripts = new ArrayList<>();
		for (Map<String, Object> step : steps) {
			if (step.get("run") instanceof String script) {
				scripts.add(script);
			}
		}
		return scripts;
	}

	private static int count(String text, String part) {
		int count = 0;
		for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) {
			count++;
		}
		return count;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> asMap(Object value) {
		assertThat(value).isInstanceOf(Map.class);
		return (Map<String, Object>) value;
	}

}
