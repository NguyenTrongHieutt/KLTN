package vn.vibefix.github.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

import vn.vibefix.github.ActionsRuns;
import vn.vibefix.github.ActionsRuns.Dispatch;
import vn.vibefix.github.ActionsRuns.Dispatched;
import vn.vibefix.github.ActionsRuns.Operation;
import vn.vibefix.github.GitHubApps.App;
import vn.vibefix.github.PlatformRepositories;
import vn.vibefix.github.PlatformRepositories.PlatformRepository;

/**
 * Story 23.4's {@code runner-dispatch}: on the dev stack it records a handover-copy correlation for two
 * repositories of the organization and dispatches the runner's copy workflow, printing the correlation and the
 * run, never a token; any other stack, a wrong invocation, or a repository not found is refused.
 */
class RunnerDispatchCommandTest {

	private static final String SHA = "1f".repeat(20);

	private final ByteArrayOutputStream out = new ByteArrayOutputStream();

	private final ByteArrayOutputStream err = new ByteArrayOutputStream();

	private final List<Dispatch> dispatched = new ArrayList<>();

	private final UUID correlation = UUID.randomUUID();

	@Test
	void itDispatchesAHandoverCopyOnTheRunnerWithTheGrantOfBothRepositories() {
		RunnerDispatchCommand command = command("dev", "cli");

		command.run(arguments("--source=job-1-working", "--source-branch=main", "--sha=" + SHA, "--target=job-1-intermediary",
				"--target-branch=handover/h1"));

		assertThat(command.getExitCode()).isZero();
		assertThat(RunnerDispatchCommand.COMMAND).as("the name BootstrapCommand lets run").isEqualTo("runner-dispatch");
		assertThat(this.dispatched).singleElement().satisfies(dispatch -> {
			assertThat(dispatch.app()).as("the runner's run is the Platform App's").isEqualTo(App.PLATFORM);
			assertThat(dispatch.sourceInstallationId()).isNull();
			assertThat(dispatch.operation()).isEqualTo(Operation.HANDOVER_COPY);
			assertThat(dispatch.targetId()).isEqualTo(9);
			assertThat(dispatch.workflow()).isEqualTo("s6-copy.yml");
			assertThat(dispatch.grant()).containsExactlyInAnyOrder(7L, 8L);
			assertThat(dispatch.inputs()).isEqualTo(Map.of("source_repository", "vibefix-dev/job-1-working", "source_branch", "main",
					"sha", SHA, "target_repository", "vibefix-dev/job-1-intermediary", "target_branch", "handover/h1"));
		});
		assertThat(this.out.toString(StandardCharsets.UTF_8)).contains(this.correlation.toString(), "run 4242",
				"vibefix-dev/vibefix-runner");
	}

	@Test
	void anyStackButDevIsRefused() {
		RunnerDispatchCommand command = command("prod", "cli");

		command.run(arguments("--source=job-1-working", "--source-branch=main", "--sha=" + SHA, "--target=job-1-intermediary",
				"--target-branch=handover/h1"));

		assertThat(command.getExitCode()).isOne();
		assertThat(this.dispatched).isEmpty();
	}

	@Test
	void aWrongInvocationOrAMissingRepositoryIsRefused() {
		RunnerDispatchCommand wrong = command("dev", "cli");
		wrong.run(arguments("--source=vibefix-dev/job-1-working", "--source-branch=main", "--sha=" + SHA,
				"--target=job-1-intermediary", "--target-branch=../main"));
		assertThat(wrong.getExitCode()).isEqualTo(2);

		RunnerDispatchCommand missing = command("dev", "cli");
		missing.run(arguments("--source=job-gone-working", "--source-branch=main", "--sha=" + SHA,
				"--target=job-1-intermediary", "--target-branch=handover/h1"));
		assertThat(missing.getExitCode()).isOne();
		assertThat(this.err.toString(StandardCharsets.UTF_8)).contains("no repository job-gone-working");
		assertThat(this.dispatched).isEmpty();
	}

	@Test
	void anotherCommandIsLeftAlone() {
		MockEnvironment environment = environment("dev", "cli");
		environment.setProperty("vibefix.cli.command", "spike-s6");
		RunnerDispatchCommand command = new RunnerDispatchCommand(runs(), repositories(), properties(), environment,
				new PrintStream(this.out, true, StandardCharsets.UTF_8), new PrintStream(this.err, true, StandardCharsets.UTF_8));

		command.run(arguments("--part=a"));

		assertThat(command.getExitCode()).isZero();
		assertThat(this.out.toString(StandardCharsets.UTF_8) + this.err.toString(StandardCharsets.UTF_8)).isEmpty();
	}

	private RunnerDispatchCommand command(String... profiles) {
		MockEnvironment environment = environment(profiles);
		environment.setProperty("vibefix.cli.command", RunnerDispatchCommand.COMMAND);
		return new RunnerDispatchCommand(runs(), repositories(), properties(), environment,
				new PrintStream(this.out, true, StandardCharsets.UTF_8), new PrintStream(this.err, true, StandardCharsets.UTF_8));
	}

	private static MockEnvironment environment(String... profiles) {
		MockEnvironment environment = new MockEnvironment();
		environment.setActiveProfiles(profiles);
		return environment;
	}

	private static GitHubProperties properties() {
		return new GitHubProperties(URI.create("https://api.github.com"), null, "vibefix-dev", null, null, null,
				"vibefix-runner");
	}

	private ActionsRuns runs() {
		return new ActionsRuns() {

			@Override
			public Dispatched dispatch(Dispatch dispatch) {
				RunnerDispatchCommandTest.this.dispatched.add(dispatch);
				return new Dispatched(RunnerDispatchCommandTest.this.correlation, 4242);
			}

			@Override
			public Optional<Correlation> correlation(UUID id) {
				return Optional.empty();
			}

			@Override
			public Optional<Run> run(UUID correlation) {
				return Optional.empty();
			}

			@Override
			public void close(UUID correlation) {
			}

		};
	}

	/** The organization's repositories by name: only {@code find} is called. */
	private static PlatformRepositories repositories() {
		Map<String, Long> ids = Map.of("job-1-working", 7L, "job-1-intermediary", 8L, "vibefix-runner", 9L);
		return (PlatformRepositories) Proxy.newProxyInstance(PlatformRepositories.class.getClassLoader(),
				new Class<?>[] { PlatformRepositories.class }, (proxy, method, args) -> {
					if (!method.getName().equals("find")) {
						throw new UnsupportedOperationException(method.getName());
					}
					String name = (String) args[0];
					Long id = ids.get(name);
					return id == null ? Optional.empty() : Optional.of(new PlatformRepository(id, name, "vibefix-dev/" + name));
				});
	}

	private static DefaultApplicationArguments arguments(String... args) {
		return new DefaultApplicationArguments(args);
	}

}
