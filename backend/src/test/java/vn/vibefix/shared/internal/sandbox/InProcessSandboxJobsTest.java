package vn.vibefix.shared.internal.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import vn.vibefix.shared.SandboxJobException;

/**
 * The dev stack's in-process job channel (Dev-first rule): it runs every handler the worker would,
 * answers as the worker does, and never starts under {@code prod}.
 */
class InProcessSandboxJobsTest {

	@Test
	void runsTheHandlersTheWorkerRuns() {
		InProcessSandboxJobs jobs = jobs(new MockEnvironment().withProperty("VIBEFIX_SECRETS_DIR", "no-such-dir"));
		ObjectNode input = JsonNodeFactory.instance.objectNode().put(HealthJobHandler.NONCE, "n-1");

		assertThat(jobs.run(HealthJobHandler.TYPE, input).output().path(HealthJobHandler.NONCE).asString())
			.isEqualTo("n-1");
		assertThat(input.path(HealthJobHandler.NONCE).asString()).as("the handler got a copy").isEqualTo("n-1");
	}

	@Test
	void refusalsAndUnknownTypesAreSandboxJobExceptions() {
		InProcessSandboxJobs jobs = jobs(new MockEnvironment());

		assertThatThrownBy(() -> jobs.run("NO_SUCH_JOB", JsonNodeFactory.instance.objectNode()))
			.isInstanceOf(SandboxJobException.class)
			.hasMessageContaining("NO_SUCH_JOB");
		assertThatThrownBy(() -> jobs.run(HealthJobHandler.TYPE, JsonNodeFactory.instance.objectNode()))
			.isInstanceOf(SandboxJobException.class)
			.hasMessageContaining("refused");
	}

	@Test
	void prodRefusesToStart() {
		MockEnvironment prod = new MockEnvironment();
		prod.setActiveProfiles("prod");

		assertThatThrownBy(() -> jobs(prod)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining(InProcessSandboxJobs.PROPERTY);
	}

	private static InProcessSandboxJobs jobs(Environment environment) {
		DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
		beans.registerSingleton("environment", environment);
		return new InProcessSandboxJobs(environment, beans);
	}

}
