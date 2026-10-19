package vn.vibefix.github.internal;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import vn.vibefix.github.GitHubApps.App;
import vn.vibefix.github.GitHubApps.InstallationToken;
import vn.vibefix.github.GitHubException;

/**
 * Spike S4's probe (Story 2.1, AD-10, AD-11, SG-17, SG-21): under {@code dev} with {@code cli},
 * {@code --vibefix.cli.command=spike-s4 --source=<owner/name> --collaborator=<login> [--forks=3]
 * [--prefix=s4-…]} runs every repository operation J7 and the Handover depend on, with the dev
 * Platform App, and prints each outcome. A second run with {@code --intermediary=<name>
 * --member-token-file=<path>} checks, with an organization member's own token, that the ruleset
 * refuses them. Each installation token is minted for one check with only its permissions and
 * revoked after it; no token is ever printed. Nothing is deleted on GitHub.
 * <p>
 * Exit code 0 when every check is {@code OK} or {@code SKIPPED}, 1 for a {@code FAIL} or a refusal,
 * 2 for a wrong invocation.
 */
@Component
@Profile("cli")
class SpikeS4Command implements ApplicationRunner, ExitCodeGenerator {

	static final String COMMAND = "spike-s4";

	private static final Pattern REPOSITORY = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})/[A-Za-z0-9._-]{1,100}");

	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,80}");

	private static final Pattern LOGIN = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})");

	private static final DateTimeFormatter PREFIX = DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneOffset.UTC);

	enum Outcome {
		OK, FAIL, SKIPPED
	}

	/** One check's outcome, as printed. */
	record Check(int number, String name, Outcome outcome, int status, String detail) {

		String line() {
			return "[%2d] %-34s %-7s %s%s".formatted(this.number, this.name, this.outcome,
					this.status > 0 ? "HTTP " + this.status + "  " : "", this.detail);
		}

	}

	/** What the checks share: the Intermediary Repository, once created or named. */
	private static final class Run {

		@Nullable String name;

		long id;

		@Nullable String defaultBranch;

		@Nullable Path clone;

	}

	private final GitHubAppsClient apps;

	private final Environment environment;

	private final Clock clock;

	private final Duration forkPoll;

	private final Duration forkWait;

	private final PrintStream out;

	private final PrintStream err;

	private volatile int exitCode;

	@Autowired
	SpikeS4Command(GitHubAppsClient apps, Environment environment, Clock clock) {
		this(apps, environment, clock, Duration.ofSeconds(5), Duration.ofMinutes(5), System.out, System.err);
	}

	SpikeS4Command(GitHubAppsClient apps, Environment environment, Clock clock, Duration forkPoll, Duration forkWait,
			PrintStream out, PrintStream err) {
		this.apps = apps;
		this.environment = environment;
		this.clock = clock;
		this.forkPoll = forkPoll;
		this.forkWait = forkWait;
		this.out = out;
		this.err = err;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (!COMMAND.equals(this.environment.getProperty("vibefix.cli.command"))) {
			this.exitCode = 0;
			return;
		}
		String source = single(args, "source");
		String collaborator = single(args, "collaborator");
		String intermediary = single(args, "intermediary");
		String memberTokenFile = single(args, "member-token-file");
		String forksArg = single(args, "forks");
		String prefix = single(args, "prefix");
		int forks = forksArg == null ? 3 : parse(forksArg);
		boolean memberRun = intermediary != null;
		boolean valid = memberRun ? NAME.matcher(intermediary).matches() && memberTokenFile != null
				: source != null && REPOSITORY.matcher(source).matches() && collaborator != null
						&& LOGIN.matcher(collaborator).matches() && forks >= 1 && forks <= 10
						&& (prefix == null || NAME.matcher(prefix).matches());
		if (!valid) {
			this.err.println("Usage: --spring.profiles.active=dev,cli --vibefix.cli.command=" + COMMAND
					+ " --source=<owner/name> --collaborator=<GitHub username> [--forks=1..10] [--prefix=<name>]");
			this.err.println("   or: --vibefix.cli.command=" + COMMAND
					+ " --intermediary=<name from the first run> --member-token-file=<path>");
			this.exitCode = 2;
			return;
		}
		if (this.environment.acceptsProfiles(Profiles.of("prod")) || !this.environment.acceptsProfiles(Profiles.of("dev"))) {
			this.err.println("Refused: " + COMMAND + " runs on the dev stack only");
			this.exitCode = 1;
			return;
		}
		String organization = this.apps.properties().organization();
		if (organization == null) {
			this.err.println("Refused: vibefix.github.organization is not configured");
			this.exitCode = 1;
			return;
		}
		List<Check> checks = new ArrayList<>();
		Run run = new Run();
		try {
			if (memberRun) {
				memberChecks(organization, intermediary, Path.of(memberTokenFile), run, checks);
			}
			else {
				String name = (prefix == null ? "s4-" + PREFIX.format(this.clock.instant()) : prefix) + "-intermediary";
				this.out.println("Spike S4 on " + organization + ": copying " + source + " to " + organization + "/" + name);
				platformChecks(organization, source, name, collaborator, forks, run, checks);
			}
		}
		finally {
			deleteClone(run.clone);
		}
		boolean failed = checks.stream().anyMatch(check -> check.outcome() == Outcome.FAIL);
		this.out.println(failed ? "Spike S4: at least one check FAILED" : "Spike S4: every check passed or was skipped");
		this.exitCode = failed ? 1 : 0;
	}

	@Override
	public int getExitCode() {
		return this.exitCode;
	}

	private void platformChecks(String organization, String source, String name, String collaborator, int forks, Run run,
			List<Check> checks) {
		record(checks, check(1, "organization settings", () -> organizationSettings(organization)));
		record(checks, check(2, "installation repository selection", this::installationSelection));
		Check created = check(3, "create Intermediary Repository", () -> create(organization, name, run));
		record(checks, created);
		boolean ready = created.outcome() == Outcome.OK;
		Check copied = ready ? check(4, "copy default branch with workflows", () -> copy(organization, source, run))
				: skipped(4, "copy default branch with workflows");
		record(checks, copied);
		ready = ready && copied.outcome() == Outcome.OK;
		Check rulesets = ready ? check(5, "create rulesets after the push", () -> createRulesets(organization, run))
				: skipped(5, "create rulesets after the push");
		record(checks, rulesets);
		record(checks, rulesets.outcome() == Outcome.OK ? check(6, "read rulesets back", () -> readRulesets(organization, run))
				: skipped(6, "read rulesets back"));
		record(checks, new Check(7, "member refused by the ruleset", Outcome.SKIPPED, 0,
				"run again with --intermediary=" + name + " --member-token-file=<path>"));
		record(checks, ready ? check(8, "Platform App pushes handover/s4", () -> appHandoverPush(organization, run))
				: skipped(8, "Platform App pushes handover/s4"));
		for (int i = 1; i <= forks; i++) {
			int fork = i;
			record(checks, created.outcome() == Outcome.OK
					? check(8 + fork, "fork " + fork + " inside the organization", () -> fork(organization, run, fork))
					: skipped(8 + fork, "fork " + fork + " inside the organization"));
		}
		int number = 9 + forks;
		record(checks, created.outcome() == Outcome.OK
				? check(number, "invite read, raise to write", () -> invite(organization, run, collaborator, number))
				: skipped(number, "invite read, raise to write"));
	}

	private Check organizationSettings(String organization) {
		return withToken(Set.of(), Map.of("organization_administration", "read"), token -> {
			GitHubHttp.Answer org = this.apps.http().send("GET", "/orgs/" + organization, token.value(), null);
			GitHubHttp.Answer actions = this.apps.http()
				.send("GET", "/orgs/" + organization + "/actions/permissions", token.value(), null);
			return new Check(1, "organization settings", org.ok() ? Outcome.OK : Outcome.FAIL, org.status(),
					"two_factor_requirement_enabled=" + org.body().path("two_factor_requirement_enabled").asString("absent")
							+ " default_repository_permission="
							+ org.body().path("default_repository_permission").asString("absent")
							+ " actions.enabled_repositories=" + actions.body().path("enabled_repositories").asString("absent")
							+ " (actions HTTP " + actions.status() + ")");
		});
	}

	private Check installationSelection() {
		GitHubHttp.Answer answer = this.apps.installation(App.PLATFORM);
		return new Check(2, "installation repository selection", answer.ok() ? Outcome.OK : Outcome.FAIL, answer.status(),
				"repository_selection=" + answer.body().path("repository_selection").asString("absent"));
	}

	private Check create(String organization, String name, Run run) {
		return withToken(Set.of(), Map.of("administration", "write"), token -> {
			GitHubHttp.Answer answer = this.apps.http()
				.send("POST", "/orgs/" + organization + "/repos", token.value(),
						Map.of("name", name, "private", false, "has_wiki", false, "has_issues", false, "has_projects", false,
								"has_discussions", false, "auto_init", false));
			if (answer.status() == 201) {
				run.name = name;
				run.id = answer.body().path("id").asLong();
			}
			return new Check(3, "create Intermediary Repository", answer.status() == 201 ? Outcome.OK : Outcome.FAIL,
					answer.status(), answer.status() == 201 ? organization + "/" + name + " id=" + run.id
							: answer.body().path("message").asString(""));
		});
	}

	private Check copy(String organization, String source, Run run) throws IOException, GitAPIException {
		Path clone = Files.createTempDirectory("spike-s4-");
		run.clone = clone;
		try (Git git = Git.cloneRepository().setURI(gitUrl(source)).setBare(true).setDirectory(clone.toFile()).call()) {
			Ref head = git.getRepository().exactRef("HEAD");
			String branch = head != null && head.isSymbolic() ? head.getTarget().getName() : "refs/heads/main";
			run.defaultBranch = branch;
			ObjectId sha = git.getRepository().resolve(branch);
			Check pushed = withToken(Set.of(run.id), Map.of("contents", "write", "workflows", "write"),
					token -> push(git, gitUrl(organization + "/" + run.name), token.value(), branch + ":" + branch, false,
							4, "copy default branch with workflows", "sha=" + (sha == null ? "?" : sha.name())));
			if (pushed.outcome() != Outcome.OK) {
				return pushed;
			}
			return withToken(Set.of(run.id), Map.of("contents", "read"), token -> {
				GitHubHttp.Answer workflows = this.apps.http()
					.send("GET", "/repos/" + organization + "/" + run.name + "/contents/.github/workflows", token.value(), null);
				return new Check(4, "copy default branch with workflows",
						workflows.ok() && workflows.body().size() > 0 ? Outcome.OK : Outcome.FAIL, workflows.status(),
						pushed.detail() + " workflow files=" + workflows.body().size());
			});
		}
	}

	private Check createRulesets(String organization, Run run) {
		long appId = this.apps.appId(App.PLATFORM);
		List<Map<String, Object>> bypass = List.of(Map.of("actor_id", appId, "actor_type", "Integration", "bypass_mode", "always"));
		return withToken(Set.of(run.id), Map.of("administration", "write"), token -> {
			String path = "/repos/" + organization + "/" + run.name + "/rulesets";
			GitHubHttp.Answer branch = this.apps.http().send("POST", path, token.value(), Map.of("name", "s4-default-branch",
					"target", "branch", "enforcement", "active", "conditions",
					Map.of("ref_name", Map.of("include", List.of("~DEFAULT_BRANCH"), "exclude", List.of())), "rules",
					List.of(Map.of("type", "deletion"), Map.of("type", "non_fast_forward"), Map.of("type", "update")),
					"bypass_actors", bypass));
			GitHubHttp.Answer handover = this.apps.http().send("POST", path, token.value(), Map.of("name", "s4-handover",
					"target", "branch", "enforcement", "active", "conditions",
					Map.of("ref_name", Map.of("include", List.of("refs/heads/handover/**"), "exclude", List.of())), "rules",
					List.of(Map.of("type", "creation"), Map.of("type", "update"), Map.of("type", "deletion")),
					"bypass_actors", bypass));
			boolean ok = branch.status() == 201 && handover.status() == 201;
			return new Check(5, "create rulesets after the push", ok ? Outcome.OK : Outcome.FAIL,
					ok ? 201 : Math.max(branch.status() == 201 ? 0 : branch.status(), handover.status() == 201 ? 0 : handover.status()),
					"default-branch id=" + branch.body().path("id").asString("-") + " handover id="
							+ handover.body().path("id").asString("-"));
		});
	}

	private Check readRulesets(String organization, Run run) {
		long appId = this.apps.appId(App.PLATFORM);
		return withToken(Set.of(run.id), Map.of("administration", "read"), token -> {
			GitHubHttp.Answer list = this.apps.http()
				.send("GET", "/repos/" + organization + "/" + run.name + "/rulesets", token.value(), null);
			List<String> found = new ArrayList<>();
			boolean ok = list.ok();
			for (JsonNode summary : list.body()) {
				GitHubHttp.Answer ruleset = this.apps.http()
					.send("GET", "/repos/" + organization + "/" + run.name + "/rulesets/" + summary.path("id").asString(),
							token.value(), null);
				List<String> rules = new ArrayList<>();
				for (JsonNode rule : ruleset.body().path("rules")) {
					rules.add(rule.path("type").asString());
				}
				boolean bypass = false;
				for (JsonNode actor : ruleset.body().path("bypass_actors")) {
					bypass |= actor.path("actor_type").asString().equals("Integration") && actor.path("actor_id").asLong() == appId;
				}
				ok &= ruleset.ok() && bypass && "active".equals(ruleset.body().path("enforcement").asString());
				found.add(ruleset.body().path("name").asString() + rules + (bypass ? " bypass=app" : " bypass=missing"));
			}
			ok &= found.size() == 2;
			return new Check(6, "read rulesets back", ok ? Outcome.OK : Outcome.FAIL, list.status(), String.join("; ", found));
		});
	}

	private Check appHandoverPush(String organization, Run run) throws IOException {
		try (Git git = Git.open(run.clone.toFile())) {
			return withToken(Set.of(run.id), Map.of("contents", "write"), token -> push(git,
					gitUrl(organization + "/" + run.name), token.value(), run.defaultBranch + ":refs/heads/handover/s4", false, 8,
					"Platform App pushes handover/s4", ""));
		}
	}

	private Check fork(String organization, Run run, int number) throws InterruptedException {
		String forkName = run.name.replaceAll("-intermediary$", "") + "-working-" + number;
		Instant started = this.clock.instant();
		GitHubHttp.Answer answer = withToken(Set.of(run.id), Map.of("administration", "write", "contents", "read"),
				token -> this.apps.http().send("POST", "/repos/" + organization + "/" + run.name + "/forks", token.value(),
						Map.of("organization", organization, "name", forkName, "default_branch_only", true)));
		if (answer.status() != 202) {
			return new Check(8 + number, "fork " + number + " inside the organization", Outcome.FAIL, answer.status(),
					forkName + ": " + answer.body().path("message").asString(""));
		}
		long forkId = answer.body().path("id").asLong();
		String actualName = answer.body().path("name").asString(forkName);
		long waited = 0;
		while (waited <= this.forkWait.toMillis()) {
			GitHubHttp.Answer ready = withToken(Set.of(forkId), Map.of("contents", "read"),
					token -> this.apps.http().send("GET", "/repos/" + organization + "/" + actualName + "/commits?per_page=1",
							token.value(), null));
			if (ready.ok() && ready.body().size() > 0) {
				return new Check(8 + number, "fork " + number + " inside the organization", Outcome.OK, ready.status(),
						organization + "/" + actualName + " ready after ~" + Duration.ofMillis(waited).toSeconds() + " s"
								+ (actualName.equals(forkName) ? "" : " (GitHub named it " + actualName + ")"));
			}
			Thread.sleep(this.forkPoll.toMillis());
			waited += this.forkPoll.toMillis();
		}
		return new Check(8 + number, "fork " + number + " inside the organization", Outcome.FAIL, 0,
				actualName + " not ready after " + this.forkWait.toMinutes() + " min (started " + started + ")");
	}

	private Check invite(String organization, Run run, String collaborator, int number) {
		return withToken(Set.of(run.id), Map.of("administration", "write"), token -> {
			String path = "/repos/" + organization + "/" + run.name;
			GitHubHttp.Answer read = this.apps.http()
				.send("PUT", path + "/collaborators/" + collaborator, token.value(), Map.of("permission", "pull"));
			GitHubHttp.Answer write = this.apps.http()
				.send("PUT", path + "/collaborators/" + collaborator, token.value(), Map.of("permission", "push"));
			GitHubHttp.Answer invitations = this.apps.http().send("GET", path + "/invitations", token.value(), null);
			int count = 0;
			String permission = "-";
			for (JsonNode invitation : invitations.body()) {
				if (invitation.path("invitee").path("login").asString().equalsIgnoreCase(collaborator)) {
					count++;
					permission = invitation.path("permissions").asString();
				}
			}
			boolean ok = (read.status() == 201 || read.status() == 204) && (write.status() == 201 || write.status() == 204)
					&& invitations.ok() && count <= 1;
			return new Check(number, "invite read, raise to write", ok ? Outcome.OK : Outcome.FAIL, write.status(),
					"read HTTP " + read.status() + ", write HTTP " + write.status() + ", invitations for " + collaborator + "="
							+ count + " permissions=" + permission);
		});
	}

	/** The second run: an organization member's own token, refused by the ruleset everywhere it guards. */
	private void memberChecks(String organization, String name, Path tokenFile, Run run, List<Check> checks) {
		String memberToken;
		try {
			memberToken = Files.readString(tokenFile, StandardCharsets.US_ASCII).strip();
		}
		catch (IOException ex) {
			this.err.println("Refused: the member token file cannot be read");
			checks.add(new Check(7, "member refused by the ruleset", Outcome.FAIL, 0, "no token"));
			return;
		}
		this.out.println("Spike S4 on " + organization + ": the ruleset against a member's token on " + name);
		record(checks, check(7, "member refused by the ruleset", () -> {
			Path clone = Files.createTempDirectory("spike-s4-member-");
			run.clone = clone;
			try (Git git = Git.cloneRepository().setURI(gitUrl(organization + "/" + name)).setBare(true)
				.setDirectory(clone.toFile()).call()) {
				Ref head = git.getRepository().exactRef("HEAD");
				String branch = head != null && head.isSymbolic() ? head.getTarget().getName() : "refs/heads/main";
				String url = gitUrl(organization + "/" + name);
				ObjectId tip = git.getRepository().resolve(branch);
				ObjectId child = commit(git, tip, true);
				ObjectId orphan = commit(git, tip, false);
				List<String> results = new ArrayList<>();
				boolean allRefused = true;
				for (String[] attempt : new String[][] { { "direct push", child.name() + ":" + branch },
						{ "force push", "+" + orphan.name() + ":" + branch }, { "deletion", ":" + branch },
						{ "handover push", tip.name() + ":refs/heads/handover/s4-member" } }) {
					Check pushed = push(git, url, memberToken, attempt[1], attempt[1].startsWith("+"), 7, attempt[0], "");
					boolean refused = pushed.outcome() != Outcome.OK;
					results.add(attempt[0] + "=" + (refused ? "refused" : "ACCEPTED"));
					allRefused &= refused;
				}
				return new Check(7, "member refused by the ruleset", allRefused ? Outcome.OK : Outcome.FAIL, 0,
						String.join(", ", results));
			}
		}));
	}

	/** A commit with the tip's tree: its child, or an orphan for the force push. */
	private static ObjectId commit(Git git, ObjectId tip, boolean child) throws IOException {
		try (ObjectInserter inserter = git.getRepository().newObjectInserter(); RevWalk walk = new RevWalk(git.getRepository())) {
			RevCommit parent = walk.parseCommit(tip);
			CommitBuilder commit = new CommitBuilder();
			commit.setTreeId(parent.getTree().getId());
			if (child) {
				commit.setParentId(tip);
			}
			PersonIdent author = new PersonIdent("vibefix spike S4", "spike-s4@vibefix.invalid");
			commit.setAuthor(author);
			commit.setCommitter(author);
			commit.setMessage(child ? "Spike S4: a member's direct push" : "Spike S4: a member's force push");
			ObjectId id = inserter.insert(commit);
			inserter.flush();
			return id;
		}
	}

	private Check push(Git git, String url, String token, String refSpec, boolean force, int number, String name,
			String detail) {
		try {
			Iterable<PushResult> results = git.push()
				.setRemote(url)
				.setRefSpecs(new RefSpec(refSpec))
				.setForce(force)
				.setCredentialsProvider(new UsernamePasswordCredentialsProvider("x-access-token", token))
				.call();
			List<String> statuses = new ArrayList<>();
			boolean ok = true;
			for (PushResult result : results) {
				for (RemoteRefUpdate update : result.getRemoteUpdates()) {
					statuses.add(update.getRemoteName() + "=" + update.getStatus());
					ok &= update.getStatus() == RemoteRefUpdate.Status.OK
							|| update.getStatus() == RemoteRefUpdate.Status.UP_TO_DATE;
				}
			}
			return new Check(number, name, ok ? Outcome.OK : Outcome.FAIL, 0,
					(detail.isEmpty() ? "" : detail + " ") + String.join(" ", statuses));
		}
		catch (GitAPIException ex) {
			// JGit's message names the remote URL, which holds no credential; the token is never in it.
			return new Check(number, name, Outcome.FAIL, 0, ex.getClass().getSimpleName() + ": " + ex.getMessage());
		}
	}

	private interface Step {

		Check run() throws Exception;

	}

	private Check check(int number, String name, Step step) {
		try {
			return step.run();
		}
		catch (GitHubException ex) {
			return new Check(number, name, Outcome.FAIL, ex.status(), ex.getMessage());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return new Check(number, name, Outcome.FAIL, 0, "interrupted");
		}
		catch (Exception ex) {
			return new Check(number, name, Outcome.FAIL, 0, ex.getClass().getSimpleName());
		}
	}

	private static Check skipped(int number, String name) {
		return new Check(number, name, Outcome.SKIPPED, 0, "an earlier check failed");
	}

	/** Mints a token for one check, runs it, and revokes the token whatever happened. */
	private <T> T withToken(Set<Long> repositoryIds, Map<String, String> permissions, Function<InstallationToken, T> use) {
		InstallationToken token = this.apps.token(App.PLATFORM, repositoryIds, permissions);
		try {
			return use.apply(token);
		}
		finally {
			this.apps.revoke(token);
		}
	}

	private void record(List<Check> checks, Check check) {
		checks.add(check);
		this.out.println(check.line());
	}

	private String gitUrl(String repository) {
		return this.apps.properties().gitUrl().toString().replaceAll("/+$", "") + "/" + repository + ".git";
	}

	private static @Nullable String single(ApplicationArguments args, String name) {
		List<String> values = args.getOptionValues(name);
		return values == null || values.size() != 1 ? null : values.getFirst();
	}

	private static int parse(String value) {
		try {
			return Integer.parseInt(value);
		}
		catch (NumberFormatException ex) {
			return -1;
		}
	}

	private static void deleteClone(@Nullable Path clone) {
		if (clone == null) {
			return;
		}
		try (Stream<Path> paths = Files.walk(clone)) {
			paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
		}
		catch (IOException ex) {
			// A temporary clone left behind holds only the public source's history.
		}
	}

}
