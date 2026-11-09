package vn.vibefix.scanning.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spike S6: the job {@code deploy/runner/s6-scan.yml} posts to the sandbox worker on the runner is one
 * this module's worker runs as it is: its type is {@link SecretScanJobHandler#TYPE}, and its limits are
 * the seed limits, which {@link ScanLimits#read} needs in full.
 */
class S6ScanJobTest {

	private static final Path WORKFLOW = Path.of("..", "deploy", "runner", "s6-scan.yml");

	private static final Pattern JOB = Pattern.compile("(?m)^job='(\\{.*})'$");

	@Test
	@SuppressWarnings("unchecked")
	void theScanWorkflowsJobIsASecretScanWithTheSeedLimits() throws Exception {
		Map<String, Object> workflow = (Map<String, Object>) new Yaml(new SafeConstructor(new LoaderOptions()))
			.load(Files.readString(WORKFLOW, StandardCharsets.UTF_8));
		Map<String, Object> job = (Map<String, Object>) ((Map<String, Object>) workflow.get("jobs")).get("scan");
		String script = ((List<Map<String, Object>>) job.get("steps")).stream()
			.map(step -> String.valueOf(step.get("run")))
			.filter(run -> run.contains("/internal/sandbox/jobs"))
			.findFirst()
			.orElseThrow();
		Matcher literal = JOB.matcher(script);
		assertThat(literal.find()).as("the job body is one JSON literal in the scan step").isTrue();

		JsonNode body = JsonMapper.builder().build().readTree(literal.group(1));

		assertThat(body.path("type").asString()).isEqualTo(SecretScanJobHandler.TYPE);
		assertThat(ScanLimits.read(body.path("input").path("limits"))).isEqualTo(ScanLimits.DEFAULT);
		assertThat(script).as("the repository is added to that body").contains("'.input.repository = $repository'");
	}

}
