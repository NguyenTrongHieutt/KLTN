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
 * Spike S6: the runner workflows in {@code deploy/runner/} hold the rules the spike runs under. Every
 * action is pinned by commit SHA; inputs reach scripts only through {@code env:}; the tokens are
 * unsealed with OAEP SHA-256/MGF1-SHA-256 and masked before the first {@code git}; the job is bounded
 * and reads contents only; and the result is uploaded for one day, even after a failure.
 */
class RunnerWorkflowsTest {

	private static final Path RUNNER = Path.of("..", "deploy", "runner");

	private static final Pattern PINNED = Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_./-]+@[0-9a-f]{40}");

	/** A {@code git} command: at the start of a line or after a separator, not inside a word like GIT_ASKPASS. */
	private static final Pattern GIT = Pattern.compile("(?m)(^|[\\s;&|(])git\\s");

	@ParameterizedTest
	@ValueSource(strings = { SpikeS6Command.COPY_WORKFLOW, SpikeS6Command.SCAN_WORKFLOW })
	void everyActionIsPinnedAndNoInputReachesAScriptDirectly(String file) throws IOException {
		String text = Files.readString(RUNNER.resolve(file), StandardCharsets.UTF_8);
		Map<String, Object> workflow = load(text);
		List<Map<String, Object>> steps = steps(workflow);

		List<String> uses = new ArrayList<>();
		for (Map<String, Object> step : steps) {
			if (step.get("uses") instanceof String action) {
				uses.add(action);
			}
		}
		assertThat(uses).isNotEmpty().allSatisfy(action -> assertThat(action).matches(PINNED));
		assertThat(uses).contains("actions/upload-artifact@cf430e030ddbb5b0abf93d22962f4752f3646cd9");
		if (file.equals(SpikeS6Command.SCAN_WORKFLOW)) {
			assertThat(uses).contains("actions/setup-java@77dd5b2ce225ea11469928ac317850245a74ae78");
		}
		for (String script : scripts(steps)) {
			assertThat(script).doesNotContain("${{ inputs.", "github.event.inputs", "${{");
		}
		assertThat(text).doesNotContain("extraheader", "x-access-token:", "x-access-token@");
		assertThat(text).doesNotContainPattern("https://[^/\\s\"']*@");
	}

	@ParameterizedTest
	@ValueSource(strings = { SpikeS6Command.COPY_WORKFLOW, SpikeS6Command.SCAN_WORKFLOW })
	void theTokensAreUnsealedAndMaskedBeforeTheFirstGit(String file) throws IOException {
		List<Map<String, Object>> steps = steps(load(Files.readString(RUNNER.resolve(file), StandardCharsets.UTF_8)));
		String scripts = String.join("\n", scripts(steps));

		int mask = scripts.indexOf("::add-mask::");
		Matcher git = GIT.matcher(scripts);
		assertThat(git.find()).as("the workflow runs git").isTrue();
		assertThat(mask).as("::add-mask:: precedes the first git").isNotNegative().isLessThan(git.start());
		assertThat(scripts).contains("openssl pkeyutl -decrypt", "rsa_padding_mode:oaep", "rsa_oaep_md:sha256",
				"rsa_mgf1_md:sha256", "GIT_ASKPASS", "transfer.fsckObjects true", "--no-tags");
		assertThat(scripts).doesNotContain("--force", "push -f", "--mirror", "\"+$SHA");
		Map<String, Object> unseal = steps.stream()
			.filter(step -> String.valueOf(step.get("run")).contains("openssl pkeyutl"))
			.findFirst()
			.orElseThrow();
		assertThat(asMap(unseal.get("env"))).containsEntry("RUNNER_KEY", "${{ secrets.VIBEFIX_RUNNER_KEY }}");
	}

	@ParameterizedTest
	@ValueSource(strings = { SpikeS6Command.COPY_WORKFLOW, SpikeS6Command.SCAN_WORKFLOW })
	void theRunIsBoundedReadsContentsOnlyAndAlwaysUploadsItsResult(String file) throws IOException {
		Map<String, Object> workflow = load(Files.readString(RUNNER.resolve(file), StandardCharsets.UTF_8));

		assertThat(workflow.get("permissions")).isEqualTo(Map.of("contents", "read"));
		assertThat(String.valueOf(workflow.get("run-name"))).contains("${{ inputs.correlation }}");
		// YAML 1.1 reads the key "on" as true.
		Object on = workflow.containsKey("on") ? workflow.get("on") : workflow.get(Boolean.TRUE);
		assertThat(on).isInstanceOf(Map.class);
		assertThat(asMap(on)).containsOnlyKeys("workflow_dispatch");
		Map<?, ?> jobs = (Map<?, ?>) workflow.get("jobs");
		assertThat(jobs).hasSize(1);
		Map<?, ?> job = (Map<?, ?>) jobs.values().iterator().next();
		assertThat(job.get("runs-on")).isEqualTo("ubuntu-24.04");
		assertThat(job.get("timeout-minutes")).isEqualTo(15);

		List<Map<String, Object>> steps = steps(workflow);
		Map<String, Object> first = steps.getFirst();
		assertThat(String.valueOf(first.get("run"))).as("the first step checks the inputs").contains("=~");
		Map<String, Object> result = steps.get(steps.size() - 2);
		assertThat(result.get("if")).isEqualTo("always()");
		assertThat(String.valueOf(result.get("run"))).contains("result.json", "correlation", "outcome", "step");
		Map<String, Object> upload = steps.getLast();
		assertThat(upload.get("if")).isEqualTo("always()");
		assertThat(String.valueOf(upload.get("uses"))).startsWith("actions/upload-artifact@");
		Map<?, ?> with = (Map<?, ?>) upload.get("with");
		assertThat(with.get("name")).isEqualTo("result-${{ inputs.correlation }}");
		assertThat(with.get("retention-days")).isEqualTo(1);
		assertThat(String.valueOf(with.get("path"))).endsWith("/result.json");
	}

	@ParameterizedTest
	@ValueSource(strings = { SpikeS6Command.COPY_WORKFLOW, SpikeS6Command.SCAN_WORKFLOW })
	void everyInputTheProbeSendsIsDeclared(String file) throws IOException {
		Map<String, Object> workflow = load(Files.readString(RUNNER.resolve(file), StandardCharsets.UTF_8));
		Object on = workflow.containsKey("on") ? workflow.get("on") : workflow.get(Boolean.TRUE);
		Map<String, Object> inputs = asMap(asMap(asMap(on).get("workflow_dispatch")).get("inputs"));

		List<String> expected = file.equals(SpikeS6Command.COPY_WORKFLOW)
				? List.of("correlation", "source_repository", "source_branch", "sha", "target_repository", "target_branch",
						"source_token", "target_token")
				: List.of("correlation", "repository", "branch", "sha", "token", "scanner_sha256", "result_key");
		assertThat(inputs.keySet()).containsExactlyInAnyOrderElementsOf(expected);
		for (Object input : inputs.values()) {
			assertThat(((Map<?, ?>) input).get("required")).isEqualTo(true);
			assertThat(((Map<?, ?>) input).get("type")).isEqualTo("string");
		}
	}

	@Test
	void theScanResultLeavesTheRunnerOnlySealed() throws IOException {
		List<Map<String, Object>> steps = steps(
				load(Files.readString(RUNNER.resolve(SpikeS6Command.SCAN_WORKFLOW), StandardCharsets.UTF_8)));
		Map<String, Object> seal = steps.get(steps.size() - 2);
		String script = String.valueOf(seal.get("run"));

		assertThat(asMap(seal.get("env"))).containsEntry("RESULT_KEY", "${{ inputs.result_key }}");
		// The commands RunnerSealTest runs against RunnerSeal.Opener.
		assertThat(script).contains("openssl rand -out \"$plain/aes.key\" 32", "openssl rand -out \"$plain/mac.key\" 32",
				"openssl rand -out \"$plain/iv.bin\" 16",
				"openssl enc -aes-256-ctr -K \"$aes_key\" -iv \"$iv\" -in \"$plain/result.json\" -out \"$plain/ciphertext.bin\"",
				"cat \"$plain/iv.bin\" \"$plain/ciphertext.bin\"",
				"openssl dgst -sha256 -mac HMAC -macopt \"hexkey:$mac_key\" -binary",
				"cat \"$plain/aes.key\" \"$plain/mac.key\"",
				"openssl pkeyutl -encrypt -pubin -keyform DER -inkey \"$plain/result-key.der\"",
				"-pkeyopt rsa_padding_mode:oaep -pkeyopt rsa_oaep_md:sha256 -pkeyopt rsa_mgf1_md:sha256",
				"'{wrapped: $wrapped, iv: $iv, ciphertext: $ciphertext, tag: $tag}' > \"$RUNNER_TEMP/result/result.json\"",
				"::add-mask::$aes_key", "::add-mask::$mac_key", "rm -rf \"$plain\"");
		assertThat(script.indexOf("::add-mask::$aes_key")).as("the keys are masked before openssl enc uses them")
			.isLessThan(script.indexOf("openssl enc"));
		assertThat(script).as("the plain result is written apart from what is uploaded")
			.contains("> \"$plain/result.json\"")
			.doesNotContain("output: $output[0]}' > \"$RUNNER_TEMP/result/result.json\"");
		Map<String, Object> upload = steps.getLast();
		assertThat(asMap(upload.get("with")).get("path")).isEqualTo("${{ runner.temp }}/result/result.json");
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> load(String text) {
		return (Map<String, Object>) new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
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

	@SuppressWarnings("unchecked")
	private static Map<String, Object> asMap(Object value) {
		assertThat(value).isInstanceOf(Map.class);
		return (Map<String, Object>) value;
	}

}
