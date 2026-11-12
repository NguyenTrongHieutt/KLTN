package vn.vibefix.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

/**
 * Story 23.4: the bootstrap CLI lets {@code runner-dispatch} answer for itself, as it does the probes, and its usage
 * names it.
 */
class BootstrapCommandRunnerDispatchTest {

	private final ByteArrayOutputStream out = new ByteArrayOutputStream();

	private final ByteArrayOutputStream err = new ByteArrayOutputStream();

	@Test
	void theBootstrapCommandLeavesTheRunnerDispatchAlone() {
		BootstrapCommand bootstrap = bootstrap("runner-dispatch");

		bootstrap.run(new DefaultApplicationArguments("--source=job-1-working"));

		assertThat(bootstrap.getExitCode()).isZero();
		assertThat(this.out.toString(StandardCharsets.UTF_8) + this.err.toString(StandardCharsets.UTF_8)).isEmpty();
	}

	@Test
	void theUsageNamesIt() {
		BootstrapCommand bootstrap = bootstrap("no-such-command");

		bootstrap.run(new DefaultApplicationArguments());

		assertThat(bootstrap.getExitCode()).isEqualTo(2);
		assertThat(this.err.toString(StandardCharsets.UTF_8)).contains(BootstrapCommand.RUNNER_DISPATCH);
	}

	private BootstrapCommand bootstrap(String command) {
		MockEnvironment environment = new MockEnvironment();
		environment.setActiveProfiles("dev", "cli");
		environment.setProperty("vibefix.cli.command", command);
		// The bootstrap returns before it would use AdministratorBootstrap, so none is needed here.
		return new BootstrapCommand(null, environment, () -> new char[0], new PrintStream(this.out, true, StandardCharsets.UTF_8),
				new PrintStream(this.err, true, StandardCharsets.UTF_8));
	}

}
