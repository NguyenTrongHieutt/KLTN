package vn.vibefix.shared.internal.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import vn.vibefix.shared.SandboxJobResult;

/**
 * AD-32: {@link SandboxHealthIndicator} is UP only when the {@code HEALTH} job echoes its nonce and
 * reports zero secret files and {@code datasourceConfigured: false}; every other answer is DOWN.
 */
class SandboxHealthIndicatorTest {

	@Test
	void isolatedSandboxThatEchoesTheNonceIsUp() {
		assertThat(statusFor(SandboxHealthIndicatorTest::isolated)).isEqualTo(Status.UP);
	}

	@Test
	void sandboxWithADatasourceIsDown() {
		assertThat(statusFor(nonce -> isolated(nonce).put(HealthJobHandler.DATASOURCE_CONFIGURED, true)))
			.isEqualTo(Status.DOWN);
	}

	@Test
	void answerWithoutTheDatasourceFlagIsDown() {
		assertThat(statusFor(nonce -> isolated(nonce).without(HealthJobHandler.DATASOURCE_CONFIGURED)))
			.isEqualTo(Status.DOWN);
	}

	@Test
	void answerWithAnotherNonceIsDown() {
		assertThat(statusFor(nonce -> isolated("other"))).isEqualTo(Status.DOWN);
	}

	@Test
	void sandboxThatSeesSecretFilesIsDown() {
		assertThat(statusFor(nonce -> isolated(nonce).put(HealthJobHandler.SECRET_FILES, 1))).isEqualTo(Status.DOWN);
	}

	/** The indicator's status when the sandbox answers each HEALTH job with {@code answer(nonce)}. */
	private static Status statusFor(Function<String, JsonNode> answer) {
		SandboxHealthIndicator indicator = new SandboxHealthIndicator((type, input) -> new SandboxJobResult(type,
				answer.apply(input.path(HealthJobHandler.NONCE).asString())));
		return indicator.health().getStatus();
	}

	private static ObjectNode isolated(String nonce) {
		return JsonNodeFactory.instance.objectNode()
			.put(HealthJobHandler.NONCE, nonce)
			.put(HealthJobHandler.SECRET_FILES, 0)
			.put(HealthJobHandler.DATASOURCE_CONFIGURED, false);
	}

}
