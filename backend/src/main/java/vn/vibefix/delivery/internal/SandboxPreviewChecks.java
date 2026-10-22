package vn.vibefix.delivery.internal;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import vn.vibefix.shared.SandboxJobException;
import vn.vibefix.shared.SandboxJobs;

/** {@link PreviewChecks} through the sandbox's outbound check (AD-32); a check that did not run does not respond. */
@Component
class SandboxPreviewChecks implements PreviewChecks {

	private final SandboxJobs sandbox;

	SandboxPreviewChecks(SandboxJobs sandbox) {
		this.sandbox = sandbox;
	}

	@Override
	public Result check(String url) {
		ObjectNode input = JsonNodeFactory.instance.objectNode();
		input.put("url", url.strip());
		try {
			JsonNode output = this.sandbox.run(PreviewCheckJobHandler.TYPE, input).output();
			return new Result(output.path("reachable").asBoolean(false), PreviewChecks.domain(url),
					output.path("reason").isString() ? output.path("reason").asString() : null);
		}
		catch (SandboxJobException ex) {
			return new Result(false, PreviewChecks.domain(url), PreviewCheckJobHandler.NO_RESPONSE);
		}
	}

}
