package vn.vibefix.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

/** Spike S6: the bootstrap CLI lets {@code spike-s6} answer for itself, as it does the other probes. */
class BootstrapCommandSpikeS6Test {

	private final ByteArrayOutputStream out = new ByteArrayOutputStream();

	private final ByteArrayOutputStream err = new ByteArrayOutputStream();

	@Test
	void theBootstrapCommandLeavesTheProbeAlone() {
		MockEnvironment environment = new MockEnvironment();
		environment.setActiveProfiles("dev", "cli");
		environment.setProperty("vibefix.cli.command", "spike-s6");
		// The bootstrap returns before it would use AdministratorBootstrap, so none is needed here.
		BootstrapCommand bootstrap = new BootstrapCommand(null, environment, () -> new char[0],
				new PrintStream(this.out, true, StandardCharsets.UTF_8), new PrintStream(this.err, true, StandardCharsets.UTF_8));

		bootstrap.run(new DefaultApplicationArguments("--part=a"));

		assertThat(bootstrap.getExitCode()).isZero();
		assertThat(this.out.toString(StandardCharsets.UTF_8) + this.err.toString(StandardCharsets.UTF_8)).isEmpty();
	}

}
