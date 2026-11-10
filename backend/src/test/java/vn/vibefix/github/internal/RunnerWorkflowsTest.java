package vn.vibefix.github.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Spike S6 revision: the Customer's {@code deploy/customer/vibefix.yml} and the runner's
 * {@code deploy/runner/s6-copy.yml} hold the rules the design runs under. Steps run scripts only, with no
 * action; every input reaches a script through {@code env:} only and is checked by a regular expression
 * first; each run asks for an ID token with the audience {@code vibefix}, exchanges it, and masks both
 * the ID token and the token before the first {@code git}; git reads the token from a file through
 * {@code GIT_ASKPASS}, never pushes with force, and reads every branch it pushes back; the job runs on
 * {@code ubuntu-24.04} for at most 15 minutes, with only the permissions it needs, holds no secret, and
 * uploads nothing.
 */
class RunnerWorkflowsTest {

	private static final String CUSTOMER = "customer/vibefix.yml";

	private static final String RUNNER = "runner/s6-copy.yml";

	private static final Path DEPLOY = Path.of("..", "deploy");

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
				"\"$EXCHANGE_URL/exchange\"", "umask 077");
		assertThat(scripts).as("the ID token never goes on a command line").doesNotContain("--arg id_token");
		assertThat(scripts.indexOf("::add-mask::$id_token")).as("the ID token is masked before it is sent")
			.isLessThan(scripts.indexOf("\"$EXCHANGE_URL/exchange\""));
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
		List<Map<String, Object>> steps = steps(load(text(CUSTOMER)));
		String check = String.valueOf(steps.getFirst().get("run"));
		Map<String, Object> finalMerge = steps.stream()
			.filter(step -> "inputs.operation == 'final-merge'".equals(step.get("if")))
			.findFirst()
			.orElseThrow();
		String script = String.valueOf(finalMerge.get("run"));

		assertThat(check).contains("[[ \"$BRANCH\" == vibefix/* ]]", "[ -z \"$WORKING\" ]");
		assertThat(script).contains("+HEAD:refs/heads/vibefix-intermediary", "[ -z \"$existing\" ]",
				"VIBEFIX_TOKEN_FILE=\"$RUNNER_TEMP/github-token\"");
		assertThat(script.indexOf("[ -z \"$existing\" ]")).as("the branch must be new before the push")
			.isLessThan(script.indexOf("credential.helper= push"));
		assertThat(steps.stream().filter(step -> "inputs.operation == 'copy'".equals(step.get("if")))).hasSize(1);
	}

	@ParameterizedTest
	@ValueSource(strings = { CUSTOMER, RUNNER })
	void theJobIsBoundedWithOnlyThePermissionsItNeeds(String file) throws IOException {
		Map<String, Object> workflow = load(text(file));

		assertThat(workflow.get("permissions")).isEqualTo(file.equals(CUSTOMER)
				? Map.of("contents", "write", "id-token", "write") : Map.of("contents", "read", "id-token", "write"));
		assertThat(String.valueOf(workflow.get("run-name"))).contains("${{ inputs.correlation }}");
		assertThat(on(workflow)).containsOnlyKeys("workflow_dispatch");
		Map<?, ?> jobs = (Map<?, ?>) workflow.get("jobs");
		assertThat(jobs).hasSize(1);
		Map<?, ?> job = (Map<?, ?>) jobs.values().iterator().next();
		assertThat(job.get("runs-on")).isEqualTo("ubuntu-24.04");
		assertThat(job.get("timeout-minutes")).isEqualTo(15);
		assertThat(asMap(job)).doesNotContainKey("permissions");
		Map<String, Object> last = steps(workflow).getLast();
		assertThat(last.get("if")).isEqualTo("always()");
		assertThat(String.valueOf(last.get("run"))).contains("rm -f", "vibefix-token", "askpass.sh");
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
		assertThat(inputs.keySet()).containsExactlyElementsOf(expected);
		inputs.forEach((name, input) -> {
			Map<String, Object> declared = asMap(input);
			if (name.equals("operation")) {
				assertThat(declared).containsEntry("type", "choice").containsEntry("required", true)
					.containsEntry("options", List.of("copy", "final-merge"));
			}
			else if (name.equals("working")) {
				assertThat(declared).containsEntry("type", "string").containsEntry("required", false).containsEntry("default", "");
			}
			else {
				assertThat(declared).as(name).containsEntry("type", "string").containsEntry("required", true);
			}
		});
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
