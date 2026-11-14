package vn.vibefix.github.internal;

import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import vn.vibefix.github.ActionsRuns;
import vn.vibefix.github.ActionsRuns.Dispatch;
import vn.vibefix.github.ActionsRuns.Dispatched;
import vn.vibefix.github.ActionsRuns.Operation;
import vn.vibefix.github.ActionsRuns.WorkflowRefused;
import vn.vibefix.github.GitHubApps.App;
import vn.vibefix.github.GitHubException;
import vn.vibefix.github.PlatformRepositories;
import vn.vibefix.github.PlatformRepositories.PlatformRepository;

/**
 * Story 23.4's dev command, with Story 23.8a's range: under {@code dev} with {@code cli},
 * {@code --vibefix.cli.command=runner-dispatch --source=<Working> --source-branch=<branch> --from-sha=<commit>
 * --sha=<commit> --target=<Intermediary> --target-branch=handover/<uuid>} records a handover-copy correlation for
 * the two repositories of the platform organization, named without their owner, and dispatches
 * {@value GitHubPlatformRepositories#RUNNER_WORKFLOW} on the runner ({@code vibefix.github.runner-repository})
 * with the Platform App. It then exits: the running dev backend serves the exchange through the cloudflared
 * quick tunnel ({@code vibefix.github.exchange.public-url}, which this command needs too), and revokes the
 * token when the run ends. It prints the correlation and the run, never a token. Exit code 0 once dispatched,
 * 1 when refused (any stack but {@code dev}, no runner, a repository not found, a workflow not accepted, or
 * GitHub's refusal), 2 for a wrong invocation.
 */
@Component
@Profile("cli")
class RunnerDispatchCommand implements ApplicationRunner, ExitCodeGenerator {

	static final String COMMAND = "runner-dispatch";

	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,100}");

	private static final Pattern BRANCH = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,199}");

	/** AD-11: the run pushes a new {@code handover/<uuid>} branch only, as the workflow checks too. */
	private static final Pattern HANDOVER_BRANCH = Pattern
		.compile("handover/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

	private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");

	private final ActionsRuns runs;

	private final PlatformRepositories repositories;

	private final GitHubProperties properties;

	private final Environment environment;

	private final PrintStream out;

	private final PrintStream err;

	private volatile int exitCode;

	@Autowired
	RunnerDispatchCommand(ActionsRuns runs, PlatformRepositories repositories, GitHubProperties properties,
			Environment environment) {
		this(runs, repositories, properties, environment, System.out, System.err);
	}

	RunnerDispatchCommand(ActionsRuns runs, PlatformRepositories repositories, GitHubProperties properties,
			Environment environment, PrintStream out, PrintStream err) {
		this.runs = runs;
		this.repositories = repositories;
		this.properties = properties;
		this.environment = environment;
		this.out = out;
		this.err = err;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (!COMMAND.equals(this.environment.getProperty("vibefix.cli.command"))) {
			this.exitCode = 0;
			return;
		}
		if (this.environment.acceptsProfiles(Profiles.of("prod")) || !this.environment.acceptsProfiles(Profiles.of("dev"))) {
			this.err.println("Refused: " + COMMAND + " runs on the dev stack only");
			this.exitCode = 1;
			return;
		}
		String source = single(args, "source");
		String sourceBranch = single(args, "source-branch");
		String fromSha = single(args, "from-sha");
		String sha = single(args, "sha");
		String target = single(args, "target");
		String targetBranch = single(args, "target-branch");
		if (!matches(NAME, source) || !branch(sourceBranch) || !matches(SHA, fromSha) || !matches(SHA, sha)
				|| fromSha.equals(sha) || !matches(NAME, target) || !branch(targetBranch)
				|| !matches(HANDOVER_BRANCH, targetBranch)) {
			this.err.println("Usage: --spring.profiles.active=dev,cli --vibefix.cli.command=" + COMMAND
					+ " --source=<Working Repository> --source-branch=<branch> --from-sha=<40-hex commit>"
					+ " --sha=<40-hex commit> --target=<Intermediary Repository> --target-branch=handover/<uuid>"
					+ " (repositories of the platform organization, named without their owner)");
			this.exitCode = 2;
			return;
		}
		String runner = this.properties.runnerRepository();
		String organization = this.properties.organization();
		if (runner == null || organization == null) {
			this.err.println("Refused: vibefix.github.organization and vibefix.github.runner-repository must be set");
			this.exitCode = 1;
			return;
		}
		try {
			PlatformRepository runnerRepository = find(runner);
			PlatformRepository working = find(source);
			PlatformRepository intermediary = find(target);
			Map<String, String> inputs = new LinkedHashMap<>();
			inputs.put("source_repository", working.fullName());
			inputs.put("source_branch", sourceBranch);
			inputs.put("from_sha", fromSha);
			inputs.put("sha", sha);
			inputs.put("target_repository", intermediary.fullName());
			inputs.put("target_branch", targetBranch);
			Dispatched dispatched = this.runs.dispatch(new Dispatch(App.PLATFORM, null, Operation.HANDOVER_COPY,
					runnerRepository.id(), GitHubPlatformRepositories.RUNNER_WORKFLOW, inputs,
					Set.of(working.id(), intermediary.id()), null));
			this.out.println("Dispatched " + GitHubPlatformRepositories.RUNNER_WORKFLOW + " on " + runnerRepository.fullName()
					+ " under correlation " + dispatched.correlation() + ", "
					+ (dispatched.runId() > 0 ? "run " + dispatched.runId() : "no run shown yet")
					+ ": the running dev backend exchanges its token and revokes it when the run ends");
			this.exitCode = 0;
		}
		catch (WorkflowRefused ex) {
			this.err.println("Refused: " + ex.refusal() + ": " + ex.getMessage());
			this.exitCode = 1;
		}
		catch (GitHubException ex) {
			this.err.println("Refused: " + ex.getMessage());
			this.exitCode = 1;
		}
	}

	@Override
	public int getExitCode() {
		return this.exitCode;
	}

	private PlatformRepository find(String name) {
		Optional<PlatformRepository> found = this.repositories.find(name);
		return found.orElseThrow(() -> new GitHubException(404, "no repository " + name + " in "
				+ this.properties.organization()));
	}

	/** A branch name, as the workflow's {@code branch_re} and git allow it. */
	private static boolean branch(@Nullable String value) {
		return matches(BRANCH, value) && !value.contains("..") && !value.contains("//") && !value.endsWith("/")
				&& !value.endsWith(".lock");
	}

	private static boolean matches(Pattern pattern, @Nullable String value) {
		return value != null && pattern.matcher(value).matches();
	}

	private static @Nullable String single(ApplicationArguments args, String name) {
		List<String> values = args.getOptionValues(name);
		return values == null || values.size() != 1 ? null : values.getFirst();
	}

}
