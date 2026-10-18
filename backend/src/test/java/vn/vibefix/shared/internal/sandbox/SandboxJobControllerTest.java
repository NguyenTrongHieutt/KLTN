package vn.vibefix.shared.internal.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import vn.vibefix.shared.SandboxJobHandler;
import vn.vibefix.shared.SandboxJobRequest;
import vn.vibefix.shared.SandboxJobResult;

/**
 * AD-32: a worker serves every handler unless {@code vibefix.sandbox.worker.job-types} lists the
 * types it may run, as {@code checker} does; a listed type without a handler refuses the start.
 */
class SandboxJobControllerTest {

	private static final List<SandboxJobHandler> HANDLERS = List.of(echo("HEALTH"), echo("CLONE"));

	@Test
	void withoutAListEveryHandlerIsServed() {
		SandboxJobController controller = new SandboxJobController(HANDLERS, null);

		assertThat(statusOf(controller.run(job("HEALTH"), null))).isEqualTo(HttpStatus.OK);
		assertThat(statusOf(controller.run(job("CLONE"), null))).isEqualTo(HttpStatus.OK);
	}

	@Test
	void withAListOnlyTheListedTypesAreServed() {
		SandboxJobController controller = new SandboxJobController(HANDLERS, List.of("HEALTH"));

		ResponseEntity<?> health = controller.run(job("HEALTH"), null);
		assertThat(statusOf(health)).isEqualTo(HttpStatus.OK);
		assertThat(health.getBody()).isInstanceOfSatisfying(SandboxJobResult.class,
				result -> assertThat(result.type()).isEqualTo("HEALTH"));
		assertThat(statusOf(controller.run(job("CLONE"), null))).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void blankEntriesInTheListAreIgnored() {
		SandboxJobController controller = new SandboxJobController(HANDLERS, List.of(" HEALTH ", ""));

		assertThat(statusOf(controller.run(job("HEALTH"), null))).isEqualTo(HttpStatus.OK);
		assertThat(statusOf(controller.run(job("CLONE"), null))).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void aListedTypeWithoutAHandlerRefusesTheStart() {
		assertThatThrownBy(() -> new SandboxJobController(HANDLERS, List.of("HEALTH", "URL_CHECK")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("URL_CHECK");
	}

	@Test
	void aListOfOnlyBlankEntriesRefusesTheStart() {
		assertThatThrownBy(() -> new SandboxJobController(HANDLERS, List.of(" ")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("vibefix.sandbox.worker.job-types");
	}

	@Test
	void anEmptyListRefusesTheStartRatherThanServingEveryHandler() {
		assertThatThrownBy(() -> new SandboxJobController(HANDLERS, List.of()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("vibefix.sandbox.worker.job-types");
	}

	private static HttpStatus statusOf(ResponseEntity<?> response) {
		return HttpStatus.valueOf(response.getStatusCode().value());
	}

	private static SandboxJobRequest job(String type) {
		return new SandboxJobRequest(type, JsonNodeFactory.instance.objectNode());
	}

	/**
	 * An anonymous class, not a nested one: the sandbox worker's component scan would pick up a
	 * nested handler class from the test classpath in every worker the integration tests start.
	 */
	private static SandboxJobHandler echo(String type) {
		return new SandboxJobHandler() {

			@Override
			public String type() {
				return type;
			}

			@Override
			public JsonNode handle(JsonNode input) {
				return input;
			}

		};
	}

}
