package vn.vibefix.github.internal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.MissingNode;
import vn.vibefix.github.GitHubApps.App;
import vn.vibefix.github.GitHubApps.InstallationToken;
import vn.vibefix.github.GitHubException;
import vn.vibefix.github.PlatformRepositories;

/**
 * Spike S6's probe (private repositories, copies and scans in GitHub Actions): under {@code dev} with
 * {@code cli}, {@code --vibefix.cli.command=spike-s6 --part=a|b|c} tries the Free-plan design of
 * 2026-11-08 against {@code vibefix-dev}, a runner repository in its own organization, and a Customer's
 * private Source Repository, and prints each outcome. The Source App's and the Runner App's ID and key
 * file are the probe's arguments only (neither joins {@link App} or {@link GitHubProperties}); each
 * installation is resolved with the App's JWT ({@code GET /repos/{owner}/{repo}/installation}).
 * <ul>
 * <li>{@code a}: the Source App's registration, its installation on {@code --source} and its selection,
 * a Contents-read token narrowed to the source (private, default branch, head), the Customer's
 * {@code admin} permission there (AD-13), and a refused ref creation;</li>
 * <li>{@code b}: {@code vibefix-dev}'s settings; the Runner App's registration and installation, the
 * runner's workflows (and with {@code --scanner-jar} the release asset); two private repositories, the
 * copies source → Intermediary → Working in Actions, each read back; Actions disabled on both; one
 * ruleset on the private Intermediary; one scan (its result sealed for {@code --result-private-key},
 * printed as counts only) with its billed minutes; and every run's duration and billed minutes;</li>
 * <li>{@code c}: the Customer invited to the Intermediary with {@code pull} (a pending invitation stops
 * the run: "accept, then run again", exit 0), the permissions read back, two Contents-API commits on the
 * Working Repository copied to {@code handover/{prefix}-1} and {@code -2} with a pull request each, the
 * second merged at its commit, and the first one's state.</li>
 * </ul>
 * <b>Tokens.</b> Every token is minted for one check or one run and revoked after it through the probe's
 * own {@code DELETE /installation/token}, whose answer is read: anything but 204 is printed ("token not
 * revoked: HTTP n") and fails the run. Tokens are narrowed by numeric {@code repository_ids}, except
 * those nothing can narrow, as in Spike S4: a repository's lookup by name for its ID (Metadata read),
 * the creation of the Intermediary and Working Repositories (Administration write), the organization
 * reads (Organization administration and Members read), and the installations' repository listings
 * (Metadata read). A token sent to a workflow is sealed with the runner's public key ({@link RunnerSeal})
 * into one dispatch input; no token, decrypted value, or key is ever printed.
 * <p>
 * <b>Runs.</b> A run is dispatched with {@code return_run_details} (a 404 is retried while GitHub learns a
 * workflow file just written), found by its {@code run-name} after a 204, and polled every 10 s for up to
 * 20 minutes (the job's own limit is 15). Its {@code result-{correlation}} artifact is downloaded from the
 * redirect's {@code Location} without the Authorization header. A run that did not end {@code COPIED} or
 * {@code SCANNED} has its failed step's last lines printed to stderr from its logs, fetched the same way.
 * Then the artifact and the run's logs are deleted, and a delete answered otherwise than 204 fails the
 * run. A run not completed in time is cancelled, and its artifact and logs stay.
 * <p>
 * <b>What stays on GitHub.</b> The repositories, branches, and pull requests the probe creates; the
 * runner's workflow files and its release; and the run records: GitHub keeps each run's dispatch
 * inputs (repository names, branches, SHAs, the sealed tokens, and {@code result_key}) and shows them to
 * readers of the runner. The probe never deletes a run; the author deletes the runs by hand.
 * <p>
 * Exit code 0 when every check is {@code OK} or {@code SKIPPED} (or the invitation is pending), 1 for a
 * {@code FAIL}, a token not revoked, or a refusal (any stack other than {@code dev}, whatever the
 * arguments), 2 for a wrong invocation or an unusable key file.
 */
@Component
@Profile("cli")
class SpikeS6Command implements ApplicationRunner, ExitCodeGenerator {

	static final String COMMAND = "spike-s6";

	static final String COPY_WORKFLOW = "s6-copy.yml";

	static final String SCAN_WORKFLOW = "s6-scan.yml";

	static final List<String> WORKFLOWS = List.of(COPY_WORKFLOW, SCAN_WORKFLOW);

	/** The runner's release that carries the scanner jar. */
	static final String RELEASE = "s6-scanner";

	/** Every dispatch runs the workflows of the runner's {@code main}. */
	static final String DISPATCH_REF = "main";

	/** The largest plain {@code result.json} read, and the largest opened scan result. */
	static final int MAX_RESULT_BYTES = 1024 * 1024;

	/** The largest sealed {@code result.json}: the base64 of a 1 MB result, and room for the envelope's other fields. */
	static final int MAX_SEALED_RESULT_BYTES = 4 * ((MAX_RESULT_BYTES + 2) / 3) + 2048;

	private static final int MAX_DOWNLOAD_BYTES = 4 * 1024 * 1024;

	private static final int MAX_LOG_BYTES = 16 * 1024 * 1024;

	/** How many lines of a failed step's log are printed. */
	static final int LOG_LINES = 40;

	/** How often a dispatch answered 404 is tried again, {@code findPoll} apart. */
	static final int DISPATCH_RETRIES = 6;

	/** The Source App's registration: Contents and Metadata read, nothing else, and no events. */
	static final Map<String, String> SOURCE_APP_PERMISSIONS = Map.of("contents", "read", "metadata", "read");

	/** The Runner App's registration: Actions, Contents, and Workflows write, Metadata read, and no events. */
	static final Map<String, String> RUNNER_APP_PERMISSIONS = Map.of("actions", "write", "contents", "write", "workflows",
			"write", "metadata", "read");

	/** GitHub's upload host for release assets; the API host itself is accepted too (a stand-in GitHub). */
	private static final String UPLOADS_HOST = "uploads.github.com";

	/** A refusal that names the plan: what a Free organization answers for a ruleset on a private repository. */
	private static final Pattern PLAN_REFUSAL = Pattern.compile("upgrade|\\bpro\\b|\\bteam\\b|private repositor",
			Pattern.CASE_INSENSITIVE);

	private static final Pattern REPOSITORY = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})/[A-Za-z0-9._-]{1,100}");

	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9-]{0,79}");

	private static final Pattern LOGIN = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})");

	private static final Pattern APP_ID = Pattern.compile("[1-9][0-9]{0,17}");

	private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");

	private static final DateTimeFormatter PREFIX = DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneOffset.UTC);

	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

	private static final HexFormat HEX = HexFormat.of();

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

	/** How long the probe waits: a run's polls and its limit, and the search by {@code run-name} after a 204. */
	record Waits(Duration runPoll, Duration runWait, Duration findPoll, Duration findWait) {

		/** The job's own timeout is 15 minutes; the wait adds the queue and the upload. */
		static final Waits GITHUB = new Waits(Duration.ofSeconds(10), Duration.ofMinutes(20), Duration.ofSeconds(5),
				Duration.ofSeconds(60));

	}

	/** A GitHub App of the probe's arguments (the Source App or the Runner App). */
	private record ProbeApp(String name, AppJwt key) {
	}

	/** One repository as an App's installation reaches it, with its numeric ID. */
	private record Installed(ProbeApp app, long installationId, String selection, String repository, long repositoryId,
			String defaultBranch, boolean privateRepository) {
	}

	/** A repository of the platform organization. */
	private record Repo(String fullName, long id, String defaultBranch) {
	}

	/** The arguments of one part; those another part needs are left out. */
	private record Invocation(String part, @Nullable String source, @Nullable String customer, @Nullable String runner,
			@Nullable String prefix, @Nullable Long sourceAppId, @Nullable Path sourceAppKey, @Nullable Long runnerAppId,
			@Nullable Path runnerAppKey, @Nullable Path runnerPublicKey, @Nullable Path workflowsDir,
			@Nullable Path scannerJar, @Nullable Path resultPrivateKey) {
	}

	/** What the arguments' files hold, read before any call to GitHub. */
	private record Inputs(@Nullable ProbeApp sourceApp, @Nullable ProbeApp runnerApp, @Nullable RunnerSeal seal,
			Map<String, String> workflows, @Nullable Path scannerJar, @Nullable String scannerSha256,
			RunnerSeal.@Nullable Opener resultOpener) {
	}

	/** What Free bills a run, and where the figure comes from ({@code timing.billable}, {@code jobs}, or unknown). */
	private record Billed(long minutes, String from, String detail) {

		static final Billed UNKNOWN = new Billed(-1, "unknown", "neither timing.billable nor the jobs' times");

	}

	/**
	 * What one workflow run gave back; {@code problem} is why it does not count.
	 * @param artifact whether the run had its {@code result-{correlation}} artifact
	 */
	private record Ran(int status, long runId, String how, String conclusion, boolean completed, @Nullable JsonNode result,
			@Nullable String problem, long durationMs, Billed billed, boolean artifact, int artifactDeleted,
			int logsDeleted) {

		static Ran refused(int status, String how, String problem) {
			return new Ran(status, 0, how, "-", false, null, problem, -1, Billed.UNKNOWN, false, 0, 0);
		}

		/** An artifact existed, and its delete or the logs' delete was answered otherwise than 204. */
		boolean cleanupFailed() {
			return this.artifact && (this.artifactDeleted != 204 || this.logsDeleted != 204);
		}

		boolean ok(String expected) {
			return this.problem == null && !cleanupFailed() && this.result != null
					&& expected.equals(this.result.path("outcome").asString(""));
		}

		String describe() {
			StringBuilder line = new StringBuilder(this.how);
			if (this.runId > 0) {
				line.append(", run ").append(this.runId).append(' ').append(this.conclusion);
			}
			if (this.result != null) {
				line.append(", ")
					.append(this.result.path("outcome").asString("?"))
					.append(" at step ")
					.append(this.result.path("step").asString("?"));
			}
			if (this.problem != null) {
				line.append(", ").append(this.problem);
			}
			if (this.durationMs >= 0) {
				line.append(", ").append(this.durationMs).append(" ms");
			}
			if (this.completed) {
				line.append(", artifact deleted HTTP ")
					.append(this.artifact ? String.valueOf(this.artifactDeleted) : "- (none)")
					.append(", logs deleted HTTP ")
					.append(this.logsDeleted);
			}
			if (cleanupFailed()) {
				line.append(", cleanup failed");
			}
			return line.toString();
		}

	}

	/** Every completed run of one invocation: its duration and its billed minutes. */
	private static final class Timings {

		private record Timed(String label, long durationMs, Billed billed) {
		}

		private final List<Timed> runs = new ArrayList<>();

		void add(String label, Ran ran) {
			if (ran.completed()) {
				this.runs.add(new Timed(label, ran.durationMs(), ran.billed()));
			}
		}

		boolean isEmpty() {
			return this.runs.isEmpty();
		}

		String describe() {
			List<String> parts = new ArrayList<>();
			long durations = 0;
			long minutes = 0;
			boolean allDurations = true;
			boolean allMinutes = true;
			for (Timed run : this.runs) {
				parts.add(run.label() + "=" + (run.durationMs() >= 0 ? run.durationMs() + " ms" : "? ms") + " billed="
						+ (run.billed().minutes() >= 0 ? run.billed().minutes() + " min" : "? min") + " (" + run.billed().from()
						+ ")");
				allDurations &= run.durationMs() >= 0;
				allMinutes &= run.billed().minutes() >= 0;
				durations += Math.max(0, run.durationMs());
				minutes += Math.max(0, run.billed().minutes());
			}
			return String.join("; ", parts) + "; total=" + durations + " ms billed=" + minutes + " min"
					+ String.format(Locale.ROOT, " (%.1f s%s)", durations / 1000.0,
							allDurations && allMinutes ? "" : ", some runs unknown");
		}

	}

	/** What the checks of one part share. */
	private static final class State {

		@Nullable Installed source;

		@Nullable String sourceBranch;

		@Nullable String sourceHead;

		@Nullable Installed runner;

		@Nullable Repo intermediary;

		@Nullable Repo working;

		@Nullable String scannerSha256;

		@Nullable Ran scan;

		boolean invitationPending;

		@Nullable String h1;

		@Nullable String h2;

		int pull1;

		int pull2;

		final Timings timings = new Timings();

	}

	private final GitHubAppsClient apps;

	private final PlatformRepositories repositories;

	private final Environment environment;

	private final Clock clock;

	private final Waits waits;

	private final PrintStream out;

	private final PrintStream err;

	private final SecureRandom random = new SecureRandom();

	/** For the artifact's and logs' redirects and the release upload: GitHubHttp sends JSON to the API host only. */
	private final HttpClient transfers = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(10))
		.followRedirects(HttpClient.Redirect.NEVER)
		.build();

	/** The statuses of revocations answered otherwise than 204, in this invocation. */
	private final List<Integer> unrevoked = new ArrayList<>();

	private volatile int exitCode;

	@Autowired
	SpikeS6Command(GitHubAppsClient apps, PlatformRepositories repositories, Environment environment, Clock clock) {
		this(apps, repositories, environment, clock, Waits.GITHUB, System.out, System.err);
	}

	SpikeS6Command(GitHubAppsClient apps, PlatformRepositories repositories, Environment environment, Clock clock,
			Waits waits, PrintStream out, PrintStream err) {
		this.apps = apps;
		this.repositories = repositories;
		this.environment = environment;
		this.clock = clock;
		this.waits = waits;
		this.out = out;
		this.err = err;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (!COMMAND.equals(this.environment.getProperty("vibefix.cli.command"))) {
			this.exitCode = 0;
			return;
		}
		// Before the arguments: any stack but dev is refused, however the probe was called.
		if (this.environment.acceptsProfiles(Profiles.of("prod")) || !this.environment.acceptsProfiles(Profiles.of("dev"))) {
			this.err.println("Refused: " + COMMAND + " runs on the dev stack only");
			this.exitCode = 1;
			return;
		}
		Invocation invocation = parse(args);
		if (invocation == null) {
			usage();
			this.exitCode = 2;
			return;
		}
		String organization = this.apps.properties().organization();
		if (organization == null) {
			this.err.println("Refused: vibefix.github.organization is not configured");
			this.exitCode = 1;
			return;
		}
		Inputs inputs;
		try {
			inputs = read(invocation);
		}
		catch (IllegalArgumentException ex) {
			// The message names the file's role, never what it holds.
			this.err.println("Refused: " + ex.getMessage());
			this.exitCode = 2;
			return;
		}
		this.unrevoked.clear();
		List<Check> checks = new ArrayList<>();
		boolean waiting = false;
		switch (invocation.part()) {
			case "a" -> partA(invocation, inputs, checks);
			case "b" -> partB(organization, invocation, inputs, checks);
			default -> waiting = partC(organization, invocation, inputs, checks);
		}
		boolean checkFailed = checks.stream().anyMatch(check -> check.outcome() == Outcome.FAIL);
		boolean failed = checkFailed || !this.unrevoked.isEmpty();
		if (!waiting || failed) {
			this.out.println("Spike S6 part " + invocation.part() + ": "
					+ (checkFailed ? "at least one check FAILED" : "every check passed or was skipped")
					+ (this.unrevoked.isEmpty() ? "" : ", but " + this.unrevoked.size() + " token(s) were not revoked"));
		}
		this.exitCode = failed ? 1 : 0;
	}

	@Override
	public int getExitCode() {
		return this.exitCode;
	}

	// Part a: the Source App.

	private void partA(Invocation invocation, Inputs inputs, List<Check> checks) {
		String source = invocation.source();
		ProbeApp app = inputs.sourceApp();
		this.out.println("Spike S6 part a: the Source App on " + source + ", for the Customer " + invocation.customer());
		State state = new State();
		record(checks, check(1, "Source App registration", () -> registration(app)));
		record(checks, check(2, "Source App installation", () -> sourceSelection(app, source, state)));
		boolean installed = state.source != null;
		record(checks, installed ? check(3, "contents read narrowed to source", () -> sourceRead(state))
				: skipped(3, "contents read narrowed to source"));
		record(checks, installed
				? check(4, "Customer's permission on source", () -> customerPermission(state, invocation.customer()))
				: skipped(4, "Customer's permission on source"));
		record(checks, installed && state.sourceHead != null ? check(5, "ref creation refused", () -> refRefused(state))
				: skipped(5, "ref creation refused"));
	}

	private Check registration(ProbeApp app) {
		GitHubHttp.Answer answer = http().send("GET", "/app", jwt(app), null);
		Map<String, String> permissions = permissions(answer.body());
		List<String> events = events(answer.body());
		boolean ok = answer.status() == 200 && permissions.equals(SOURCE_APP_PERMISSIONS) && events.isEmpty();
		return new Check(1, "Source App registration", ok ? Outcome.OK : Outcome.FAIL, answer.status(),
				"slug=" + answer.body().path("slug").asString("-") + " permissions=" + permissions + " events=" + events
						+ (ok ? "" : " (expected permissions=" + new TreeMap<>(SOURCE_APP_PERMISSIONS) + " events=[])"));
	}

	private Check sourceSelection(ProbeApp app, String source, State state) {
		Installed installed = install(app, source);
		state.source = installed;
		state.sourceBranch = installed.defaultBranch();
		GitHubHttp.Answer listed = listInstallation(installed);
		List<String> names = names(listed.body().path("repositories"), "full_name");
		boolean ok = listed.ok() && "selected".equals(installed.selection()) && names.size() == 1
				&& names.getFirst().equalsIgnoreCase(installed.repository());
		return new Check(2, "Source App installation", ok ? Outcome.OK : Outcome.FAIL, listed.status(),
				"installation=" + installed.installationId() + " repository_selection=" + installed.selection()
						+ " repositories=" + names + " total_count=" + listed.body().path("total_count").asString("-")
						+ " (" + installed.repository() + " id=" + installed.repositoryId() + ")");
	}

	private Check sourceRead(State state) {
		Installed source = state.source;
		return withApp(source, Set.of(source.repositoryId()), Map.of("contents", "read"), token -> {
			GitHubHttp.Answer repository = http().send("GET", "/repos/" + source.repository(), token.value(), null);
			String branch = repository.body().path("default_branch").asString("");
			GitHubHttp.Answer head = branch.isEmpty() ? repository
					: http().send("GET", "/repos/" + source.repository() + "/git/ref/heads/" + branch, token.value(), null);
			String sha = head.body().path("object").path("sha").asString("");
			boolean isPrivate = repository.body().path("private").asBoolean(false);
			if (!branch.isEmpty() && SHA.matcher(sha).matches()) {
				state.sourceBranch = branch;
				state.sourceHead = sha;
			}
			boolean ok = repository.ok() && head.ok() && isPrivate && state.sourceHead != null;
			return new Check(3, "contents read narrowed to source", ok ? Outcome.OK : Outcome.FAIL, repository.status(),
					"private=" + isPrivate + " visibility=" + repository.body().path("visibility").asString("-")
							+ " default_branch=" + (branch.isEmpty() ? "-" : branch) + " head=" + shortSha(sha)
							+ (head.ok() ? "" : " (head HTTP " + head.status() + ")"));
		});
	}

	private Check customerPermission(State state, String customer) {
		Installed source = state.source;
		return withApp(source, Set.of(source.repositoryId()), Map.of("metadata", "read"), token -> {
			GitHubHttp.Answer answer = http().send("GET",
					"/repos/" + source.repository() + "/collaborators/" + customer + "/permission", token.value(), null);
			String permission = answer.body().path("permission").asString("-");
			boolean ok = answer.ok() && "admin".equals(permission);
			return new Check(4, "Customer's permission on source", ok ? Outcome.OK : Outcome.FAIL, answer.status(),
					customer + ": permission=" + permission + " role_name=" + answer.body().path("role_name").asString("-")
							+ (ok ? "" : " (AD-13 needs admin)"));
		});
	}

	private Check refRefused(State state) {
		Installed source = state.source;
		String ref = "refs/heads/s6-refused-" + STAMP.format(this.clock.instant());
		return withApp(source, Set.of(source.repositoryId()), Map.of("contents", "read"), token -> {
			GitHubHttp.Answer answer = http().send("POST", "/repos/" + source.repository() + "/git/refs", token.value(),
					Map.of("ref", ref, "sha", state.sourceHead));
			boolean refused = answer.status() >= 400 && answer.status() < 500;
			return new Check(5, "ref creation refused", refused ? Outcome.OK : Outcome.FAIL, answer.status(),
					refused ? "refused, as a Contents-read token must be: " + message(answer)
							: answer.ok() ? "ACCEPTED: the Source App created " + ref + " on " + source.repository()
									+ "; remove that branch by hand" : "no answer");
		});
	}

	// Part b: copies in Actions.

	private void partB(String organization, Invocation invocation, Inputs inputs, List<Check> checks) {
		String prefix = invocation.prefix() != null ? invocation.prefix() : "s6-" + PREFIX.format(this.clock.instant());
		String source = invocation.source();
		this.out.println("Spike S6 part b on " + organization + ": " + source + " through " + invocation.runner() + " into "
				+ organization + "/" + prefix + "-intermediary and " + prefix + "-working");
		State state = new State();
		Check settings = check(1, "organization settings", () -> organizationSettings(organization));
		record(checks, settings);
		Check runner = check(2, "runner App and workflows", () -> runnerCheck(inputs, invocation.runner(), state));
		record(checks, runner);
		boolean runnerReady = runner.outcome() == Outcome.OK;
		Check asset = inputs.scannerJar() == null
				? new Check(3, "scanner release asset", Outcome.SKIPPED, 0, "no --scanner-jar")
				: runnerReady ? check(3, "scanner release asset", () -> scannerAsset(inputs, state))
						: skipped(3, "scanner release asset");
		record(checks, asset);
		// Nothing is created unless the organization and the runner are as the design needs them.
		boolean create = settings.outcome() == Outcome.OK && runnerReady;
		Check intermediary = create
				? check(4, "create private Intermediary", () -> createPrivate(4, "create private Intermediary", organization,
						prefix + "-intermediary", repository -> state.intermediary = repository))
				: skipped(4, "create private Intermediary");
		record(checks, intermediary);
		Check working = create
				? check(5, "create private Working", () -> createPrivate(5, "create private Working", organization,
						prefix + "-working", repository -> state.working = repository))
				: skipped(5, "create private Working");
		record(checks, working);
		boolean copies = create && intermediary.outcome() == Outcome.OK && working.outcome() == Outcome.OK;
		Check toIntermediary = copies
				? check(6, "copy source to Intermediary", () -> copyFromSource(inputs, source, state))
				: skipped(6, "copy source to Intermediary");
		record(checks, toIntermediary);
		record(checks, toIntermediary.outcome() == Outcome.OK
				? check(7, "copy Intermediary to Working", () -> copyToWorking(inputs, state))
				: skipped(7, "copy Intermediary to Working"));
		record(checks, state.intermediary != null && state.working != null
				? check(8, "Actions disabled on both", () -> actionsDisabled(state))
				: skipped(8, "Actions disabled on both"));
		record(checks, state.intermediary != null ? check(9, "ruleset on private Intermediary", () -> ruleset(state))
				: skipped(9, "ruleset on private Intermediary"));
		record(checks, inputs.scannerJar() == null ? new Check(10, "scan in Actions", Outcome.SKIPPED, 0, "no --scanner-jar")
				: asset.outcome() == Outcome.OK ? check(10, "scan in Actions", () -> scan(inputs, source, state))
						: skipped(10, "scan in Actions"));
		record(checks, inputs.scannerJar() == null
				? new Check(11, "scan billed minutes", Outcome.SKIPPED, 0, "no --scanner-jar")
				: state.scan != null && state.scan.completed() ? billed(state.scan) : skipped(11, "scan billed minutes"));
		record(checks, state.timings.isEmpty()
				? new Check(12, "run durations and billed minutes", Outcome.SKIPPED, 0, "no run completed")
				: new Check(12, "run durations and billed minutes", Outcome.OK, 0, state.timings.describe()));
	}

	/**
	 * The scan's billed minutes: what a PUSH scan costs of the Free plan's 2,000 Actions minutes a month for
	 * private repositories, each job rounded up to the whole minute.
	 */
	private static Check billed(Ran scan) {
		Billed billed = scan.billed();
		return new Check(11, "scan billed minutes", Outcome.OK, 0,
				(billed.minutes() >= 0 ? "billed " + billed.minutes() + " min" : "billed minutes unknown") + " from "
						+ billed.from() + " (" + billed.detail() + "), each job rounded up to the minute, of Free's 2,000 a"
						+ " month; run_duration_ms=" + scan.durationMs());
	}

	/** AD-11's settings through PlatformRepositories, and whether members may fork private repositories. */
	private Check organizationSettings(String organization) {
		List<String> differences = this.repositories.organizationDifferences();
		GitHubHttp.Answer org = withPlatform(Set.of(), Map.of("organization_administration", "read"),
				token -> http().send("GET", "/orgs/" + organization, token.value(), null));
		JsonNode fork = org.body().path("members_can_fork_private_repositories");
		boolean ok = org.ok() && differences.isEmpty() && fork.isBoolean() && !fork.asBoolean();
		return new Check(1, "organization settings", ok ? Outcome.OK : Outcome.FAIL, org.status(),
				"2FA required, base permission none, Actions none: "
						+ (differences.isEmpty() ? "as AD-11 needs" : String.join("; ", differences))
						+ ", members_can_fork_private_repositories=" + (fork.isMissingNode() ? "absent" : fork.asString()));
	}

	/**
	 * The Runner App as the design needs it (registration, installed on the runner only), a private runner
	 * on {@value #DISPATCH_REF}, its two workflows written, and no other workflow beside them. Nothing is
	 * written unless the App and the runner are right.
	 */
	private Check runnerCheck(Inputs inputs, String runnerName, State state) {
		ProbeApp app = inputs.runnerApp();
		GitHubHttp.Answer registration = http().send("GET", "/app", jwt(app), null);
		Map<String, String> permissions = permissions(registration.body());
		List<String> events = events(registration.body());
		boolean registered = registration.status() == 200 && permissions.equals(RUNNER_APP_PERMISSIONS) && events.isEmpty();
		Installed runner = runner(inputs, runnerName, state);
		GitHubHttp.Answer listed = listInstallation(runner);
		List<String> names = names(listed.body().path("repositories"), "full_name");
		boolean selected = listed.ok() && "selected".equals(runner.selection()) && names.size() == 1
				&& names.getFirst().equalsIgnoreCase(runner.repository());
		boolean shaped = DISPATCH_REF.equals(runner.defaultBranch()) && runner.privateRepository();
		String found = "permissions=" + permissions + " events=" + events + "; installation=" + runner.installationId()
				+ " repository_selection=" + runner.selection() + " repositories=" + names + "; " + runner.repository() + " id="
				+ runner.repositoryId() + " private=" + runner.privateRepository() + " default_branch=" + runner.defaultBranch();
		if (!registered || !selected || !shaped) {
			List<String> wrong = new ArrayList<>();
			if (!registered) {
				wrong.add("the Runner App must hold exactly " + new TreeMap<>(RUNNER_APP_PERMISSIONS) + " and no event");
			}
			if (!selected) {
				wrong.add("it must be installed on " + runner.repository() + " only (selected)");
			}
			if (!shaped) {
				wrong.add("the runner must be private, with the default branch " + DISPATCH_REF);
			}
			return new Check(2, "runner App and workflows", Outcome.FAIL, registration.status(),
					found + "; nothing written: " + String.join("; ", wrong));
		}
		List<String> written = new ArrayList<>();
		int[] status = { 0 };
		List<String> files = new ArrayList<>();
		boolean ok = withApp(runner, Set.of(runner.repositoryId()), Map.of("contents", "write", "workflows", "write"),
				token -> {
					boolean all = true;
					String directory = "/repos/" + runner.repository() + "/contents/.github/workflows";
					for (Map.Entry<String, String> workflow : inputs.workflows().entrySet()) {
						String path = directory + "/" + workflow.getKey();
						GitHubHttp.Answer current = http().send("GET", path + "?ref=" + DISPATCH_REF, token.value(), null);
						if (current.status() == 200 && workflow.getValue().equals(decode(current.body().path("content").asString("")))) {
							written.add(workflow.getKey() + "=unchanged");
							status[0] = Math.max(status[0], 200);
							continue;
						}
						Map<String, Object> body = new LinkedHashMap<>();
						body.put("message", "Spike S6: " + workflow.getKey());
						body.put("content", Base64.getEncoder().encodeToString(workflow.getValue().getBytes(StandardCharsets.UTF_8)));
						body.put("branch", DISPATCH_REF);
						if (current.status() == 200) {
							body.put("sha", current.body().path("sha").asString(""));
						}
						GitHubHttp.Answer put = http().send("PUT", path, token.value(), body);
						status[0] = Math.max(status[0], put.status());
						all &= put.status() == 200 || put.status() == 201;
						written.add(workflow.getKey() + "=" + (put.status() == 201 ? "created" : put.status() == 200 ? "updated"
								: "HTTP " + put.status() + " " + message(put)));
					}
					GitHubHttp.Answer listing = http().send("GET", directory + "?ref=" + DISPATCH_REF, token.value(), null);
					files.addAll(names(listing.body(), "name"));
					return all && listing.ok() && Set.copyOf(files).equals(Set.copyOf(WORKFLOWS)) && files.size() == WORKFLOWS.size();
				});
		return new Check(2, "runner App and workflows", ok ? Outcome.OK : Outcome.FAIL, status[0],
				found + "; " + String.join(", ", written) + "; .github/workflows/ holds " + files
						+ (ok ? "" : " (it must hold " + WORKFLOWS + " only)"));
	}

	private Check scannerAsset(Inputs inputs, State state) {
		Installed runner = state.runner;
		String sha256 = inputs.scannerSha256();
		String asset = scannerAssetName(sha256);
		long size = size(inputs.scannerJar());
		return withApp(runner, Set.of(runner.repositoryId()), Map.of("contents", "write"), token -> {
			String releases = "/repos/" + runner.repository() + "/releases";
			GitHubHttp.Answer release = http().send("GET", releases + "/tags/" + RELEASE, token.value(), null);
			String made = "found";
			if (release.status() == 404) {
				Map<String, Object> body = new LinkedHashMap<>();
				body.put("tag_name", RELEASE);
				body.put("target_commitish", DISPATCH_REF);
				body.put("name", "Spike S6 scanner");
				body.put("body", "The backend jar that s6-scan.yml runs as the sandbox worker (Spike S6).");
				body.put("draft", false);
				body.put("prerelease", true);
				release = http().send("POST", releases, token.value(), body);
				made = "created";
			}
			if (!release.ok()) {
				return new Check(3, "scanner release asset", Outcome.FAIL, release.status(),
						"release " + RELEASE + ": " + message(release));
			}
			for (JsonNode existing : release.body().path("assets")) {
				if (asset.equals(existing.path("name").asString())) {
					String assetState = existing.path("state").asString("-");
					long assetSize = existing.path("size").asLong(-1);
					// The name holds 12 hex digits of the hash only: a broken or other upload must not pass for the jar.
					boolean same = "uploaded".equals(assetState) && assetSize == size;
					if (same) {
						state.scannerSha256 = sha256;
					}
					return new Check(3, "scanner release asset", same ? Outcome.OK : Outcome.FAIL, release.status(),
							"release " + RELEASE + " " + made + ", " + asset + (same ? " already uploaded (" + size + " bytes)"
									: " exists with state=" + assetState + " size=" + assetSize + " while the jar has " + size
											+ " bytes: delete that asset by hand (release " + RELEASE + " of " + runner.repository()
											+ ") and run again"));
				}
			}
			int uploaded = upload(release.body().path("upload_url").asString(""), asset, inputs.scannerJar(), token);
			boolean ok = uploaded == 201;
			if (ok) {
				state.scannerSha256 = sha256;
			}
			return new Check(3, "scanner release asset", ok ? Outcome.OK : Outcome.FAIL, uploaded,
					"release " + RELEASE + " " + made + ", " + asset + (ok ? " uploaded (" + megabytes(size) + ")" : " refused"));
		});
	}

	/** @param created told of the repository only when the check is OK */
	private Check createPrivate(int number, String name, String organization, String repositoryName, Consumer<Repo> created) {
		return withPlatform(Set.of(), Map.of("administration", "write"), token -> {
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("name", repositoryName);
			body.put("private", true);
			body.put("has_wiki", false);
			body.put("has_issues", false);
			body.put("has_projects", false);
			body.put("has_discussions", false);
			body.put("auto_init", false);
			GitHubHttp.Answer answer = http().send("POST", "/orgs/" + organization + "/repos", token.value(), body);
			if (answer.status() != 201) {
				return new Check(number, name, Outcome.FAIL, answer.status(),
						organization + "/" + repositoryName + ": " + message(answer));
			}
			JsonNode repository = answer.body();
			List<String> on = new ArrayList<>();
			for (String feature : List.of("has_wiki", "has_issues", "has_projects", "has_discussions")) {
				if (repository.path(feature).asBoolean(false)) {
					on.add(feature);
				}
			}
			boolean isPrivate = repository.path("private").asBoolean(false);
			JsonNode forking = repository.path("allow_forking");
			boolean noForks = forking.isBoolean() && !forking.asBoolean();
			Repo repo = new Repo(repository.path("full_name").asString(organization + "/" + repositoryName),
					repository.path("id").asLong(), repository.path("default_branch").asString(DISPATCH_REF));
			boolean ok = isPrivate && noForks && on.isEmpty();
			if (ok) {
				created.accept(repo);
			}
			return new Check(number, name, ok ? Outcome.OK : Outcome.FAIL, 201,
					repo.fullName() + " id=" + repo.id() + " private=" + isPrivate + " allow_forking="
							+ (forking.isMissingNode() ? "absent" : forking.asString())
							+ (on.isEmpty() ? ", wiki, issues, projects and discussions off" : ", still on: " + on));
		});
	}

	private Check copyFromSource(Inputs inputs, String sourceName, State state) {
		Installed source = source(inputs, sourceName, state);
		return copy(6, "copy source to Intermediary", inputs, state, "intermediary", source.repository(), state.sourceBranch,
				state.sourceHead,
				() -> mint(source.app(), source.installationId(), Set.of(source.repositoryId()), Map.of("contents", "read")),
				state.intermediary, state.sourceBranch);
	}

	private Check copyToWorking(Inputs inputs, State state) {
		Repo intermediary = state.intermediary;
		return copy(7, "copy Intermediary to Working", inputs, state, "working", intermediary.fullName(), state.sourceBranch,
				state.sourceHead, () -> this.apps.mint(App.PLATFORM, Set.of(intermediary.id()), Map.of("contents", "read")),
				state.working, state.sourceBranch);
	}

	private Check actionsDisabled(State state) {
		List<Repo> both = List.of(state.intermediary, state.working);
		return withPlatform(Set.of(state.intermediary.id(), state.working.id()), Map.of("administration", "read"), token -> {
			List<String> found = new ArrayList<>();
			boolean ok = true;
			int status = 0;
			for (Repo repository : both) {
				GitHubHttp.Answer answer = http().send("GET", "/repos/" + repository.fullName() + "/actions/permissions",
						token.value(), null);
				JsonNode enabled = answer.body().path("enabled");
				ok &= answer.ok() && enabled.isBoolean() && !enabled.asBoolean();
				status = Math.max(status, answer.status());
				found.add(repository.fullName() + " enabled=" + (enabled.isMissingNode() ? "absent (HTTP " + answer.status() + ")"
						: enabled.asString()));
			}
			return new Check(8, "Actions disabled on both", ok ? Outcome.OK : Outcome.FAIL, status, String.join(", ", found));
		});
	}

	/**
	 * What GitHub answers for a ruleset on the private Intermediary: OK when it is created, or when a 403 or
	 * 422 names the plan (Free has no rulesets on private repositories); anything else is a failure.
	 */
	private Check ruleset(State state) {
		Repo intermediary = state.intermediary;
		long appId = this.apps.appId(App.PLATFORM);
		return withPlatform(Set.of(intermediary.id()), Map.of("administration", "write"), token -> {
			GitHubHttp.Answer answer = http().send("POST", "/repos/" + intermediary.fullName() + "/rulesets", token.value(),
					Map.of("name", "s6-default-branch", "target", "branch", "enforcement", "active", "conditions",
							Map.of("ref_name", Map.of("include", List.of("~DEFAULT_BRANCH"), "exclude", List.of())), "rules",
							List.of(Map.of("type", "deletion"), Map.of("type", "non_fast_forward"), Map.of("type", "update")),
							"bypass_actors",
							List.of(Map.of("actor_id", appId, "actor_type", "Integration", "bypass_mode", "always"))));
			boolean created = answer.status() == 201;
			boolean plan = (answer.status() == 403 || answer.status() == 422) && PLAN_REFUSAL.matcher(message(answer)).find();
			return new Check(9, "ruleset on private Intermediary", created || plan ? Outcome.OK : Outcome.FAIL, answer.status(),
					created ? "created id=" + answer.body().path("id").asString("-") + " on the private Intermediary"
							: (plan ? "GitHub answers: " : "an answer that names no plan: ") + message(answer));
		});
	}

	private Check scan(Inputs inputs, String sourceName, State state) {
		Installed source = source(inputs, sourceName, state);
		String correlation = correlation("scan");
		Ran ran;
		InstallationToken token = mint(source.app(), source.installationId(), Set.of(source.repositoryId()),
				Map.of("contents", "read"));
		try {
			Map<String, String> workflowInputs = new LinkedHashMap<>();
			workflowInputs.put("correlation", correlation);
			workflowInputs.put("repository", source.repository());
			workflowInputs.put("branch", state.sourceBranch);
			workflowInputs.put("sha", state.sourceHead);
			workflowInputs.put("token", inputs.seal().seal(token.value()));
			workflowInputs.put("scanner_sha256", state.scannerSha256);
			workflowInputs.put("result_key", inputs.resultOpener().publicKey());
			ran = runWorkflow(state.runner, SCAN_WORKFLOW, correlation, workflowInputs, inputs.resultOpener(),
					List.of(token.value()));
		}
		finally {
			revoke(token);
		}
		state.scan = ran;
		state.timings.add("scan", ran);
		JsonNode output = ran.result() == null ? MissingNode.getInstance() : ran.result().path("output");
		boolean ok = ran.ok("SCANNED") && output.has("commits");
		return new Check(10, "scan in Actions", ok ? Outcome.OK : Outcome.FAIL, ran.status(),
				ran.describe() + (output.isObject() ? ", " + counts(output, state.sourceHead) : ""));
	}

	/** The scan as counts only: no path, value, or commit of a finding is printed. */
	private static String counts(JsonNode output, String head) {
		if (output.has("refused")) {
			return "refused=" + output.path("refused").asString();
		}
		int credentials = 0;
		int personal = 0;
		for (JsonNode found : output.path("found")) {
			if ("PERSONAL_DATA".equals(found.path("category").asString(""))) {
				personal++;
			}
			else {
				credentials++;
			}
		}
		String scanned = output.path("defaultBranchSha").asString("");
		return "commits=" + output.path("commits").asLong() + " scannedBlobs=" + output.path("scannedBlobs").asLong()
				+ " unscanned=" + output.path("unscannedCount").asLong() + " credentials=" + credentials + " personalData="
				+ personal + (output.has("stopped") ? " stopped=" + output.path("stopped").asString() : "") + " head "
				+ (head.equals(scanned) ? "as approved" : "moved to " + shortSha(scanned));
	}

	// Part c: the Customer and the handovers.

	/** @return whether the run stopped for the Customer to accept the invitation */
	private boolean partC(String organization, Invocation invocation, Inputs inputs, List<Check> checks) {
		String prefix = invocation.prefix();
		String customer = invocation.customer();
		this.out.println("Spike S6 part c on " + organization + ": " + customer + " on " + prefix
				+ "-intermediary, and two handovers from " + prefix + "-working");
		State state = new State();
		Check invited = check(1, "Customer invited with pull", () -> invite(organization, prefix, customer, state));
		record(checks, invited);
		if (invited.outcome() == Outcome.OK && state.invitationPending) {
			this.out.println("Spike S6 part c: " + customer + "'s invitation to " + state.intermediary.fullName()
					+ " is pending: accept, then run again (https://github.com/" + state.intermediary.fullName()
					+ "/invitations)");
			return true;
		}
		boolean accepted = invited.outcome() == Outcome.OK;
		record(checks, accepted ? check(2, "permissions read back", () -> permissions(customer, state))
				: skipped(2, "permissions read back"));
		Check h1 = accepted ? check(3, "commit H1 on Working", () -> commit(3, "commit H1 on Working", 1, prefix, state))
				: skipped(3, "commit H1 on Working");
		record(checks, h1);
		Check pull1 = h1.outcome() == Outcome.OK
				? check(4, "copy H1 to handover, pull request",
						() -> handover(4, "copy H1 to handover, pull request", 1, prefix, inputs, invocation.runner(), state))
				: skipped(4, "copy H1 to handover, pull request");
		record(checks, pull1);
		Check h2 = pull1.outcome() == Outcome.OK
				? check(5, "commit H2, a child of H1", () -> commit(5, "commit H2, a child of H1", 2, prefix, state))
				: skipped(5, "commit H2, a child of H1");
		record(checks, h2);
		Check pull2 = h2.outcome() == Outcome.OK
				? check(6, "copy H2 to handover, pull request",
						() -> handover(6, "copy H2 to handover, pull request", 2, prefix, inputs, invocation.runner(), state))
				: skipped(6, "copy H2 to handover, pull request");
		record(checks, pull2);
		Check merged = pull2.outcome() == Outcome.OK ? check(7, "merge pull request 2 at H2", () -> merge(state))
				: skipped(7, "merge pull request 2 at H2");
		record(checks, merged);
		record(checks, merged.outcome() == Outcome.OK ? check(8, "pull request 1 after the merge", () -> firstPull(state))
				: skipped(8, "pull request 1 after the merge"));
		return false;
	}

	private Check invite(String organization, String prefix, String customer, State state) {
		Repo intermediary = platformRepository(organization, prefix + "-intermediary");
		Repo working = platformRepository(organization, prefix + "-working");
		if (intermediary == null || working == null) {
			return new Check(1, "Customer invited with pull", Outcome.FAIL, 404, "no " + organization + "/" + prefix
					+ "-intermediary or -working: run part b with --prefix=" + prefix + " first");
		}
		state.intermediary = intermediary;
		state.working = working;
		return withPlatform(Set.of(intermediary.id()), Map.of("administration", "write"), token -> {
			String path = "/repos/" + intermediary.fullName();
			GitHubHttp.Answer collaborator = http().send("GET", path + "/collaborators/" + customer, token.value(), null);
			if (collaborator.status() == 204) {
				return new Check(1, "Customer invited with pull", Outcome.OK, 204,
						customer + " is a collaborator: the invitation was accepted");
			}
			GitHubHttp.Answer invitations = http().send("GET", path + "/invitations", token.value(), null);
			for (JsonNode invitation : invitations.body()) {
				if (invitation.path("invitee").path("login").asString("").equalsIgnoreCase(customer)) {
					state.invitationPending = true;
					return new Check(1, "Customer invited with pull", Outcome.OK, invitations.status(), "invitation "
							+ invitation.path("id").asString("-") + " pending, permissions="
							+ invitation.path("permissions").asString("-"));
				}
			}
			GitHubHttp.Answer invited = http().send("PUT", path + "/collaborators/" + customer, token.value(),
					Map.of("permission", "pull"));
			if (invited.status() == 201) {
				state.invitationPending = true;
				return new Check(1, "Customer invited with pull", Outcome.OK, 201, "invited with pull, invitation "
						+ invited.body().path("id").asString("-") + " permissions="
						+ invited.body().path("permissions").asString("-"));
			}
			return new Check(1, "Customer invited with pull", Outcome.FAIL, invited.status(), invited.status() == 204
					? "GitHub added " + customer + " at once: they are a member of " + organization
							+ "; the Customer must be outside it"
					: message(invited));
		});
	}

	private Check permissions(String customer, State state) {
		Repo intermediary = state.intermediary;
		Repo working = state.working;
		return withPlatform(Set.of(intermediary.id(), working.id()), Map.of("metadata", "read"), token -> {
			GitHubHttp.Answer onIntermediary = http().send("GET",
					"/repos/" + intermediary.fullName() + "/collaborators/" + customer + "/permission", token.value(), null);
			GitHubHttp.Answer onWorking = http().send("GET",
					"/repos/" + working.fullName() + "/collaborators/" + customer + "/permission", token.value(), null);
			String permission = onIntermediary.body().path("permission").asString("-");
			String workingPermission = onWorking.body().path("permission").asString("-");
			boolean ok = onIntermediary.ok() && "read".equals(permission)
					&& (onWorking.status() == 404 || "none".equals(workingPermission));
			return new Check(2, "permissions read back", ok ? Outcome.OK : Outcome.FAIL, onIntermediary.status(),
					"Intermediary permission=" + permission + " role_name="
							+ onIntermediary.body().path("role_name").asString("-") + "; Working "
							+ (onWorking.status() == 404 ? "HTTP 404" : "HTTP " + onWorking.status() + " permission=" + workingPermission));
		});
	}

	private Check commit(int number, String name, int handover, String prefix, State state) {
		Repo working = state.working;
		if (handover == 1) {
			String branch = handoverBranch(prefix, 1);
			GitHubHttp.Answer existing = withPlatform(Set.of(state.intermediary.id()), Map.of("contents", "read"),
					token -> http().send("GET", "/repos/" + state.intermediary.fullName() + "/git/ref/heads/" + branch,
							token.value(), null));
			if (existing.ok()) {
				return new Check(number, name, Outcome.FAIL, existing.status(), branch + " exists on "
						+ state.intermediary.fullName() + ": part c already ran on " + prefix
						+ "; run part b with a new --prefix");
			}
		}
		String file = "spike-s6/handover-" + handover + "-" + STAMP.format(this.clock.instant()) + ".md";
		return withPlatform(Set.of(working.id()), Map.of("contents", "write"), token -> {
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("message", "Spike S6: handover " + handover);
			body.put("content", Base64.getEncoder()
				.encodeToString(("# Spike S6, handover " + handover + "\n").getBytes(StandardCharsets.UTF_8)));
			body.put("branch", working.defaultBranch());
			GitHubHttp.Answer put = http().send("PUT", "/repos/" + working.fullName() + "/contents/" + file, token.value(), body);
			String sha = put.body().path("commit").path("sha").asString("");
			String parent = put.body().path("commit").path("parents").path(0).path("sha").asString("");
			boolean made = put.status() == 201 && SHA.matcher(sha).matches();
			if (made && handover == 1) {
				state.h1 = sha;
			}
			else if (made) {
				state.h2 = sha;
			}
			boolean ok = made && (handover == 1 || parent.equals(state.h1));
			return new Check(number, name, ok ? Outcome.OK : Outcome.FAIL, put.status(), made
					? "H" + handover + "=" + shortSha(sha) + " on " + working.fullName() + "/" + working.defaultBranch() + ", parent "
							+ shortSha(parent) + (handover == 1 ? "" : parent.equals(state.h1) ? " (H1)" : " (not H1)")
					: message(put));
		});
	}

	private Check handover(int number, String name, int handover, String prefix, Inputs inputs, String runnerName,
			State state) {
		runner(inputs, runnerName, state);
		Repo working = state.working;
		Repo intermediary = state.intermediary;
		String sha = handover == 1 ? state.h1 : state.h2;
		String branch = handoverBranch(prefix, handover);
		Check copied = copy(number, name, inputs, state, "handover-" + handover, working.fullName(), working.defaultBranch(),
				sha, () -> this.apps.mint(App.PLATFORM, Set.of(working.id()), Map.of("contents", "read")), intermediary,
				branch);
		if (copied.outcome() != Outcome.OK) {
			return copied;
		}
		return withPlatform(Set.of(intermediary.id()), Map.of("pull_requests", "write"), token -> {
			GitHubHttp.Answer opened = http().send("POST", "/repos/" + intermediary.fullName() + "/pulls", token.value(),
					Map.of("title", "Spike S6: handover " + handover, "head", branch, "base", intermediary.defaultBranch(), "body",
							"Spike S6, part c: handover " + handover + " of " + working.fullName() + " at " + sha + "."));
			int pull = opened.body().path("number").asInt(0);
			if (handover == 1) {
				state.pull1 = pull;
			}
			else {
				state.pull2 = pull;
			}
			boolean ok = opened.status() == 201 && pull > 0;
			return new Check(number, name, ok ? Outcome.OK : Outcome.FAIL, opened.status(),
					copied.detail() + (ok ? ", pull request #" + pull : ", pull request refused: " + message(opened)));
		});
	}

	private Check merge(State state) {
		Repo intermediary = state.intermediary;
		return withPlatform(Set.of(intermediary.id()), Map.of("contents", "write", "pull_requests", "write"), token -> {
			String pull = "/repos/" + intermediary.fullName() + "/pulls/" + state.pull2;
			GitHubHttp.Answer merged = http().send("PUT", pull + "/merge", token.value(),
					Map.of("merge_method", "merge", "sha", state.h2, "commit_title", "Spike S6: merge handover 2"));
			GitHubHttp.Answer readBack = http().send("GET", pull, token.value(), null);
			boolean ok = merged.status() == 200 && merged.body().path("merged").asBoolean(false)
					&& readBack.body().path("merged").asBoolean(false);
			return new Check(7, "merge pull request 2 at H2", ok ? Outcome.OK : Outcome.FAIL, merged.status(),
					"#" + state.pull2 + " merge_method=merge sha=" + shortSha(state.h2) + ": merged="
							+ merged.body().path("merged").asString("-") + " commit=" + shortSha(merged.body().path("sha").asString(""))
							+ ", read back merged=" + readBack.body().path("merged").asString("-")
							+ (merged.status() == 200 ? "" : " " + message(merged)));
		});
	}

	/** Pull request 1 once pull request 2 brought its commit in: GitHub's state, then closed if still open. */
	private Check firstPull(State state) {
		Repo intermediary = state.intermediary;
		return withPlatform(Set.of(intermediary.id()), Map.of("pull_requests", "write"), token -> {
			String pull = "/repos/" + intermediary.fullName() + "/pulls/" + state.pull1;
			GitHubHttp.Answer read = http().send("GET", pull, token.value(), null);
			String status = read.body().path("state").asString("-");
			boolean merged = read.body().path("merged").asBoolean(false);
			String detail = "after #" + state.pull2 + " merged, #" + state.pull1 + " state=" + status + " merged=" + merged;
			if (merged || !"open".equals(status)) {
				return new Check(8, "pull request 1 after the merge", read.ok() ? Outcome.OK : Outcome.FAIL, read.status(), detail);
			}
			GitHubHttp.Answer comment = http().send("POST",
					"/repos/" + intermediary.fullName() + "/issues/" + state.pull1 + "/comments", token.value(),
					Map.of("body", "Spike S6: closed, because pull request #" + state.pull2 + " brought this commit in."));
			GitHubHttp.Answer closed = http().send("PATCH", pull, token.value(), Map.of("state", "closed"));
			GitHubHttp.Answer readBack = http().send("GET", pull, token.value(), null);
			String after = readBack.body().path("state").asString("-");
			boolean ok = comment.status() == 201 && closed.ok() && "closed".equals(after);
			return new Check(8, "pull request 1 after the merge", ok ? Outcome.OK : Outcome.FAIL, closed.status(),
					detail + "; commented HTTP " + comment.status() + ", closed HTTP " + closed.status()
							+ ", read back state=" + after);
		});
	}

	// The runner: a copy run, a workflow run, its artifact and its logs.

	/**
	 * Copies {@code sha} of {@code sourceRepository}'s {@code sourceBranch} to {@code targetBranch} of
	 * {@code target} with {@value #COPY_WORKFLOW}, then reads the branch back. The run's two tokens are
	 * sealed into the dispatch and revoked once the run is over.
	 */
	private Check copy(int number, String name, Inputs inputs, State state, String kind, String sourceRepository,
			String sourceBranch, String sha, Supplier<InstallationToken> sourceMint, Repo target, String targetBranch) {
		String correlation = correlation(kind);
		List<InstallationToken> runTokens = new ArrayList<>();
		Ran ran;
		try {
			InstallationToken sourceToken = sourceMint.get();
			runTokens.add(sourceToken);
			InstallationToken targetToken = this.apps.mint(App.PLATFORM, Set.of(target.id()),
					Map.of("contents", "write", "workflows", "write"));
			runTokens.add(targetToken);
			Map<String, String> workflowInputs = new LinkedHashMap<>();
			workflowInputs.put("correlation", correlation);
			workflowInputs.put("source_repository", sourceRepository);
			workflowInputs.put("source_branch", sourceBranch);
			workflowInputs.put("sha", sha);
			workflowInputs.put("target_repository", target.fullName());
			workflowInputs.put("target_branch", targetBranch);
			workflowInputs.put("source_token", inputs.seal().seal(sourceToken.value()));
			workflowInputs.put("target_token", inputs.seal().seal(targetToken.value()));
			ran = runWorkflow(state.runner, COPY_WORKFLOW, correlation, workflowInputs, null,
					List.of(sourceToken.value(), targetToken.value()));
		}
		finally {
			runTokens.forEach(this::revoke);
		}
		state.timings.add("copy-" + kind, ran);
		if (!ran.ok("COPIED")) {
			return new Check(number, name, Outcome.FAIL, ran.status(), ran.describe());
		}
		String head = withPlatform(Set.of(target.id()), Map.of("contents", "read"),
				token -> http().send("GET", "/repos/" + target.fullName() + "/git/ref/heads/" + targetBranch, token.value(), null))
			.body()
			.path("object")
			.path("sha")
			.asString("");
		boolean ok = sha.equals(head);
		return new Check(number, name, ok ? Outcome.OK : Outcome.FAIL, ran.status(), ran.describe() + ", read back "
				+ target.fullName() + " " + targetBranch + "=" + shortSha(head) + (ok ? "" : " instead of " + shortSha(sha)));
	}

	/**
	 * Dispatches one run with the Runner App's Actions token, finds it, and waits for it. A run not
	 * completed in time is cancelled, and its artifact and logs stay. A completed run's
	 * {@code result-{correlation}} artifact, jobs, and timing are read; if it did not end as expected, its
	 * failed step's last lines are printed to stderr; then its artifact and logs are deleted.
	 * @param opener for a scan, whose {@code result.json} is an envelope: it must open, or the run does not
	 * count and nothing of it is printed; {@code null} for a copy, whose result is plain
	 * @param secrets the run's token values, cut from any log line printed
	 */
	private Ran runWorkflow(Installed runner, String workflow, String correlation, Map<String, String> inputs,
			RunnerSeal.@Nullable Opener opener, Collection<String> secrets) {
		String expected = SCAN_WORKFLOW.equals(workflow) ? "SCANNED" : "COPIED";
		return withApp(runner, Set.of(runner.repositoryId()), Map.of("actions", "write"), token -> {
			String actions = "/repos/" + runner.repository() + "/actions";
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("ref", DISPATCH_REF);
			body.put("inputs", inputs);
			body.put("return_run_details", true);
			String dispatches = actions + "/workflows/" + workflow + "/dispatches";
			int[] retries = { 0 };
			GitHubHttp.Answer dispatched = dispatch(dispatches, token, body, retries);
			String how = "";
			if (dispatched.status() == 422 && message(dispatched).contains("return_run_details")) {
				body.remove("return_run_details");
				dispatched = dispatch(dispatches, token, body, retries);
				how = "return_run_details refused (HTTP 422), ";
			}
			if (retries[0] > 0) {
				how += "dispatched after " + retries[0] + " retries on 404, ";
			}
			long runId = 0;
			if (dispatched.status() == 200) {
				Map.Entry<String, Long> found = runId(dispatched.body());
				if (found != null) {
					runId = found.getValue();
					how += "run ID in field " + found.getKey();
				}
				else {
					how += "200 without a run ID (fields " + fieldNames(dispatched.body()) + "), found by run-name";
				}
			}
			else if (dispatched.status() == 204) {
				how += "204, found by run-name";
			}
			else {
				return Ran.refused(dispatched.status(), how + "dispatch refused",
						message(dispatched).isEmpty() ? "HTTP " + dispatched.status() : message(dispatched));
			}
			if (runId == 0) {
				runId = findByRunName(actions, workflow, correlation, token);
				if (runId == 0) {
					return Ran.refused(dispatched.status(), how,
							"no run named " + correlation + " within " + this.waits.findWait().toSeconds() + " s");
				}
			}
			GitHubHttp.Answer[] last = { null };
			boolean completed = completed(actions, runId, token, last);
			JsonNode run = last[0] == null ? MissingNode.getInstance() : last[0].body();
			if (!completed) {
				int cancel = http().send("POST", actions + "/runs/" + runId + "/cancel", token.value(), null).status();
				String url = run.path("html_url").asString("https://github.com/" + runner.repository() + "/actions/runs/" + runId);
				return new Ran(dispatched.status(), runId, how, run.path("status").asString("running"), false, null,
						"not completed in " + this.waits.runWait().toMinutes() + " min; cancel HTTP " + cancel
								+ "; its artifact and logs stay: " + url,
						-1, Billed.UNKNOWN, false, 0, 0);
			}
			String conclusion = run.path("conclusion").asString("-");
			long artifactId = 0;
			GitHubHttp.Answer artifacts = http().send("GET", actions + "/runs/" + runId + "/artifacts?per_page=100",
					token.value(), null);
			for (JsonNode artifact : artifacts.body().path("artifacts")) {
				if (("result-" + correlation).equals(artifact.path("name").asString(""))) {
					artifactId = artifact.path("id").asLong();
				}
			}
			JsonNode result = null;
			String problem = null;
			if (artifactId == 0) {
				problem = "no artifact result-" + correlation + " (artifacts HTTP " + artifacts.status() + ")";
			}
			else {
				try {
					byte[] bytes = download(actions + "/artifacts/" + artifactId + "/zip", token, MAX_DOWNLOAD_BYTES, "the artifact");
					result = resultJson(bytes, opener == null ? MAX_RESULT_BYTES : MAX_SEALED_RESULT_BYTES);
					if (opener != null) {
						result = open(result, opener);
					}
					if (!correlation.equals(result.path("correlation").asString(""))) {
						problem = "result.json names another correlation";
					}
				}
				catch (GitHubException ex) {
					result = null;
					problem = ex.getMessage();
				}
			}
			JsonNode jobs = http().send("GET", actions + "/runs/" + runId + "/jobs?per_page=100", token.value(), null).body();
			JsonNode timing = http().send("GET", actions + "/runs/" + runId + "/timing", token.value(), null).body();
			long duration = duration(timing, run);
			Billed billed = billed(timing.path("billable"), jobs);
			if (result == null || !expected.equals(result.path("outcome").asString(""))) {
				printFailedStep(actions, runId, jobs, token, secrets);
			}
			int artifactDeleted = artifactId == 0 ? 0
					: http().send("DELETE", actions + "/artifacts/" + artifactId, token.value(), null).status();
			int logsDeleted = deleteLogs(actions, runId, token);
			return new Ran(dispatched.status(), runId, how, conclusion, true, result, problem, duration, billed,
					artifactId != 0, artifactDeleted, logsDeleted);
		});
	}

	/** One dispatch, tried again while it answers 404: GitHub may not know a workflow file just written yet. */
	private GitHubHttp.Answer dispatch(String path, InstallationToken token, Map<String, Object> body, int[] retries) {
		GitHubHttp.Answer answer = http().send("POST", path, token.value(), body);
		while (answer.status() == 404 && retries[0] < DISPATCH_RETRIES) {
			sleep(this.waits.findPoll());
			retries[0]++;
			answer = http().send("POST", path, token.value(), body);
		}
		return answer;
	}

	/** The run ID a 200 from the dispatch carries, and the field that carries it. */
	private static Map.@Nullable Entry<String, Long> runId(JsonNode body) {
		for (String field : List.of("workflow_run_id", "run_id", "id")) {
			long id = number(body.path(field));
			if (id > 0) {
				return Map.entry(field, id);
			}
		}
		long nested = number(body.path("workflow_run").path("id"));
		return nested > 0 ? Map.entry("workflow_run.id", nested) : null;
	}

	private static long number(JsonNode node) {
		if (node.isIntegralNumber()) {
			return node.longValue();
		}
		if (node.isString() && node.stringValue().matches("[1-9][0-9]{0,18}")) {
			return Long.parseLong(node.stringValue());
		}
		return 0;
	}

	private long findByRunName(String actions, String workflow, String correlation, InstallationToken token) {
		long waited = 0;
		while (true) {
			GitHubHttp.Answer runs = http().send("GET",
					actions + "/workflows/" + workflow + "/runs?event=workflow_dispatch&per_page=30", token.value(), null);
			for (JsonNode run : runs.body().path("workflow_runs")) {
				String title = run.path("display_title").asString(run.path("name").asString(""));
				if (title.contains(correlation)) {
					return run.path("id").asLong();
				}
			}
			if (waited >= this.waits.findWait().toMillis()) {
				return 0;
			}
			sleep(this.waits.findPoll());
			waited += this.waits.findPoll().toMillis();
		}
	}

	/** Whether the run completed within the wait; {@code last} holds the last read of it. */
	private boolean completed(String actions, long runId, InstallationToken token, GitHubHttp.Answer[] last) {
		long waited = 0;
		while (true) {
			GitHubHttp.Answer run = http().send("GET", actions + "/runs/" + runId, token.value(), null);
			if (run.ok()) {
				last[0] = run;
			}
			if (run.ok() && "completed".equals(run.body().path("status").asString(""))) {
				return true;
			}
			if (waited >= this.waits.runWait().toMillis()) {
				return false;
			}
			sleep(this.waits.runPoll());
			waited += this.waits.runPoll().toMillis();
		}
	}

	/** {@code run_duration_ms} from {@code /timing}, else the run's {@code run_started_at} to {@code updated_at}. */
	private static long duration(JsonNode timing, JsonNode run) {
		if (timing.path("run_duration_ms").isIntegralNumber()) {
			return timing.path("run_duration_ms").longValue();
		}
		Instant started = instant(run.path("run_started_at"));
		Instant updated = instant(run.path("updated_at"));
		return started == null || updated == null || updated.isBefore(started) ? -1
				: Duration.between(started, updated).toMillis();
	}

	/**
	 * What Free bills the run, each job rounded up to the whole minute: {@code timing.billable} when GitHub
	 * gives it, else each job's {@code started_at} to {@code completed_at}.
	 */
	private static Billed billed(JsonNode billable, JsonNode jobs) {
		if (billable.isObject() && billable.size() > 0) {
			long minutes = 0;
			List<String> parts = new ArrayList<>();
			for (Map.Entry<String, JsonNode> runner : billable.properties()) {
				JsonNode os = runner.getValue();
				long osMinutes = 0;
				boolean perJob = false;
				for (JsonNode job : os.path("job_runs")) {
					osMinutes += minutes(job.path("duration_ms").asLong(0));
					perJob = true;
				}
				minutes += perJob ? osMinutes : minutes(os.path("total_ms").asLong(0));
				parts.add(runner.getKey() + " total_ms=" + os.path("total_ms").asString("-") + " jobs="
						+ os.path("jobs").asString("-"));
			}
			return new Billed(minutes, "timing.billable", String.join(", ", parts));
		}
		long minutes = 0;
		int counted = 0;
		for (JsonNode job : jobs.path("jobs")) {
			Instant started = instant(job.path("started_at"));
			Instant completed = instant(job.path("completed_at"));
			if (started != null && completed != null && !completed.isBefore(started)) {
				minutes += minutes(Duration.between(started, completed).toMillis());
				counted++;
			}
		}
		return counted == 0 ? Billed.UNKNOWN : new Billed(minutes, "jobs", counted + " job(s), completed_at minus started_at");
	}

	private static long minutes(long ms) {
		return (Math.max(0, ms) + 59_999) / 60_000;
	}

	private static @Nullable Instant instant(JsonNode node) {
		if (!node.isString()) {
			return null;
		}
		try {
			return Instant.parse(node.stringValue());
		}
		catch (DateTimeParseException ex) {
			return null;
		}
	}

	/**
	 * Prints to stderr the last {@value #LOG_LINES} lines of the failed step's log, from the run's logs zip,
	 * fetched by hand from the redirect without the Authorization header, before the logs are deleted. The
	 * workflows mask every token; the run's own token values are cut from the lines as well.
	 */
	private void printFailedStep(String actions, long runId, JsonNode jobs, InstallationToken token,
			Collection<String> secrets) {
		String job = null;
		int step = 0;
		String stepName = "?";
		search: for (JsonNode candidate : jobs.path("jobs")) {
			for (JsonNode s : candidate.path("steps")) {
				if ("failure".equals(s.path("conclusion").asString(""))) {
					job = candidate.path("name").asString("");
					step = s.path("number").asInt(0);
					stepName = s.path("name").asString("?");
					break search;
				}
			}
		}
		byte[] zip;
		try {
			zip = download(actions + "/runs/" + runId + "/logs", token, MAX_LOG_BYTES, "the run's logs");
		}
		catch (GitHubException ex) {
			this.err.println("Spike S6: run " + runId + " did not succeed, and its logs could not be read: " + ex.getMessage());
			return;
		}
		String chosen = null;
		byte[] text = null;
		try (ZipInputStream entries = new ZipInputStream(new ByteArrayInputStream(zip))) {
			String largest = null;
			byte[] largestText = null;
			for (ZipEntry entry = entries.getNextEntry(); entry != null; entry = entries.getNextEntry()) {
				if (entry.isDirectory()) {
					continue;
				}
				byte[] content = entries.readNBytes(MAX_LOG_BYTES);
				String entryName = entry.getName();
				String last = entryName.substring(entryName.lastIndexOf('/') + 1);
				if (job != null && step > 0 && entryName.startsWith(job + "/") && last.startsWith(step + "_")) {
					chosen = entryName;
					text = content;
					break;
				}
				if (largestText == null || content.length > largestText.length) {
					largest = entryName;
					largestText = content;
				}
			}
			if (chosen == null) {
				chosen = largest;
				text = largestText;
			}
		}
		catch (IOException ex) {
			this.err.println("Spike S6: run " + runId + " did not succeed, and its logs are not a zip");
			return;
		}
		if (text == null) {
			this.err.println("Spike S6: run " + runId + " did not succeed, and its logs hold no file");
			return;
		}
		List<String> lines = new String(text, StandardCharsets.UTF_8).lines().toList();
		List<String> tail = lines.subList(Math.max(0, lines.size() - LOG_LINES), lines.size());
		this.err.println("Spike S6: run " + runId + " did not succeed; failed step " + (step > 0 ? step : "?") + " ("
				+ stepName + "), the last " + tail.size() + " lines of " + chosen + ":");
		for (String line : tail) {
			String shown = line;
			for (String secret : secrets) {
				shown = shown.replace(secret, "<redacted>");
			}
			this.err.println("  | " + shown);
		}
	}

	/** GitHub may still be writing a finished run's logs: one more try after a poll. */
	private int deleteLogs(String actions, long runId, InstallationToken token) {
		int status = http().send("DELETE", actions + "/runs/" + runId + "/logs", token.value(), null).status();
		if (status != 204) {
			sleep(this.waits.runPoll());
			status = http().send("DELETE", actions + "/runs/" + runId + "/logs", token.value(), null).status();
		}
		return status;
	}

	/**
	 * Follows a download's redirect by hand: the API answers 302 with a signed {@code Location} on blob
	 * storage, which is fetched without the Authorization header (the token goes to the API host only)
	 * and never printed.
	 */
	private byte[] download(String path, InstallationToken token, int maxBytes, String what) {
		URI api = URI.create(this.apps.properties().apiUrl().toString().replaceAll("/+$", "") + path);
		try {
			HttpResponse<Void> redirect = this.transfers.send(HttpRequest.newBuilder(api)
				.timeout(Duration.ofSeconds(30))
				.header("Accept", "application/vnd.github+json")
				.header("X-GitHub-Api-Version", "2022-11-28")
				.header("Authorization", "Bearer " + token.value())
				.GET()
				.build(), HttpResponse.BodyHandlers.discarding());
			if (redirect.statusCode() / 100 != 3) {
				throw new GitHubException(redirect.statusCode(),
						what + " answered HTTP " + redirect.statusCode() + ", not a redirect");
			}
			String location = redirect.headers()
				.firstValue("Location")
				.orElseThrow(() -> new GitHubException(redirect.statusCode(), what + "'s redirect names no Location"));
			URI blob = api.resolve(location);
			if (!"https".equals(blob.getScheme()) && !api.getScheme().equals(blob.getScheme())) {
				throw new GitHubException(0, what + "'s redirect is not https");
			}
			HttpResponse<InputStream> response = this.transfers
				.send(HttpRequest.newBuilder(blob).timeout(Duration.ofSeconds(60)).GET().build(),
						HttpResponse.BodyHandlers.ofInputStream());
			byte[] bytes;
			try (InputStream body = response.body()) {
				bytes = body.readNBytes(maxBytes + 1);
			}
			if (response.statusCode() != 200) {
				throw new GitHubException(response.statusCode(), "blob storage answered HTTP " + response.statusCode());
			}
			if (bytes.length > maxBytes) {
				throw new GitHubException(0, what + " is larger than " + maxBytes + " bytes");
			}
			return bytes;
		}
		catch (IOException | IllegalArgumentException ex) {
			throw new GitHubException(0, what + " could not be downloaded: " + ex.getClass().getSimpleName());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new GitHubException(0, "interrupted");
		}
	}

	/** The artifact's {@code result.json} (zipped, or as it is), at most {@code maxBytes}. */
	private static JsonNode resultJson(byte[] bytes, int maxBytes) {
		try {
			byte[] json = bytes.length > 1 && bytes[0] == 'P' && bytes[1] == 'K' ? resultEntry(bytes, maxBytes) : bytes;
			if (json.length > maxBytes) {
				throw new GitHubException(0, "result.json is larger than " + maxBytes + " bytes");
			}
			return GitHubHttp.JSON.readTree(json);
		}
		catch (IOException ex) {
			throw new GitHubException(0, "the artifact is not a zip");
		}
		catch (JacksonException ex) {
			throw new GitHubException(0, "result.json is not JSON");
		}
	}

	/**
	 * A scan's {@code result.json} is the envelope {@code {wrapped, iv, ciphertext, tag}} and nothing else:
	 * the tag is checked in constant time before it is decrypted (RunnerSeal.Opener). A refusal names no
	 * field and prints nothing decrypted.
	 */
	private static JsonNode open(JsonNode envelope, RunnerSeal.Opener opener) {
		List<String> fields = new ArrayList<>();
		envelope.properties().forEach(entry -> fields.add(entry.getKey()));
		if (!Set.copyOf(fields).equals(Set.of("wrapped", "iv", "ciphertext", "tag")) || fields.size() != 4
				|| !envelope.path("wrapped").isString() || !envelope.path("iv").isString()
				|| !envelope.path("ciphertext").isString() || !envelope.path("tag").isString()) {
			throw new GitHubException(0, "the scan result is not sealed as {wrapped, iv, ciphertext, tag}");
		}
		byte[] plain;
		try {
			plain = opener.open(envelope.path("wrapped").stringValue(), envelope.path("iv").stringValue(),
					envelope.path("ciphertext").stringValue(), envelope.path("tag").stringValue());
		}
		catch (IllegalArgumentException ex) {
			throw new GitHubException(0, "the scan result did not open: " + ex.getMessage());
		}
		if (plain.length > MAX_RESULT_BYTES) {
			throw new GitHubException(0, "the opened scan result is larger than 1 MB");
		}
		try {
			return GitHubHttp.JSON.readTree(plain);
		}
		catch (JacksonException ex) {
			throw new GitHubException(0, "the opened scan result is not JSON");
		}
	}

	private static byte[] resultEntry(byte[] zip, int maxBytes) throws IOException {
		try (ZipInputStream entries = new ZipInputStream(new ByteArrayInputStream(zip))) {
			for (ZipEntry entry = entries.getNextEntry(); entry != null; entry = entries.getNextEntry()) {
				if ("result.json".equals(entry.getName())) {
					byte[] json = entries.readNBytes(maxBytes + 1);
					if (json.length > maxBytes) {
						throw new GitHubException(0, "result.json is larger than " + maxBytes + " bytes");
					}
					return json;
				}
			}
		}
		throw new GitHubException(0, "the artifact holds no result.json");
	}

	/** Uploads the jar to the release's {@code upload_url}, refused unless {@link #uploadHostAllowed} accepts it. */
	private int upload(String template, String asset, Path jar, InstallationToken token) {
		URI target;
		try {
			target = URI.create(template.replaceFirst("\\{.*$", ""));
		}
		catch (IllegalArgumentException ex) {
			throw new GitHubException(0, "the release's upload_url is not a URL");
		}
		if (!uploadHostAllowed(this.apps.properties().apiUrl(), target)) {
			throw new GitHubException(0, "the release's upload_url is not GitHub's upload host: nothing was sent to "
					+ target.getHost());
		}
		try {
			HttpRequest request = HttpRequest
				.newBuilder(URI.create(target + "?name=" + URLEncoder.encode(asset, StandardCharsets.UTF_8)))
				.timeout(Duration.ofMinutes(15))
				.header("Accept", "application/vnd.github+json")
				.header("X-GitHub-Api-Version", "2022-11-28")
				.header("Authorization", "Bearer " + token.value())
				.header("Content-Type", "application/java-archive")
				.POST(HttpRequest.BodyPublishers.ofFile(jar))
				.build();
			return this.transfers.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
		}
		catch (IOException ex) {
			throw new GitHubException(0, "the scanner jar could not be uploaded: " + ex.getClass().getSimpleName());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new GitHubException(0, "interrupted");
		}
	}

	/**
	 * Whether a release's {@code upload_url} may receive the token: {@code https://uploads.github.com}, or
	 * the API host itself (scheme, host, and port), as a stand-in GitHub answers.
	 */
	static boolean uploadHostAllowed(URI api, URI target) {
		boolean apiHost = api.getScheme() != null && api.getScheme().equals(target.getScheme()) && api.getHost() != null
				&& api.getHost().equals(target.getHost()) && api.getPort() == target.getPort();
		boolean uploadsHost = "https".equals(target.getScheme()) && UPLOADS_HOST.equals(target.getHost())
				&& (target.getPort() == -1 || target.getPort() == 443);
		return apiHost || uploadsHost;
	}

	// Installations, tokens, and repositories.

	/** The Source Repository through the Source App: its installation, ID, default branch, and head. */
	private Installed source(Inputs inputs, String name, State state) {
		if (state.source == null || state.sourceHead == null) {
			Installed installed = install(inputs.sourceApp(), name);
			String head = withApp(installed, Set.of(installed.repositoryId()), Map.of("contents", "read"), token -> {
				GitHubHttp.Answer ref = http().send("GET",
						"/repos/" + installed.repository() + "/git/ref/heads/" + installed.defaultBranch(), token.value(), null);
				if (!ref.ok()) {
					throw new GitHubException(ref.status(), "the head of " + installed.repository() + "'s "
							+ installed.defaultBranch() + " could not be read: HTTP " + ref.status());
				}
				return ref.body().path("object").path("sha").asString("");
			});
			if (!SHA.matcher(head).matches()) {
				throw new GitHubException(0, installed.repository() + "'s default branch has no head");
			}
			state.source = installed;
			state.sourceBranch = installed.defaultBranch();
			state.sourceHead = head;
		}
		return state.source;
	}

	private Installed runner(Inputs inputs, String name, State state) {
		if (state.runner == null) {
			state.runner = install(inputs.runnerApp(), name);
		}
		return state.runner;
	}

	/**
	 * The App's installation on {@code repository}, read with its JWT, and the repository's numeric ID,
	 * read with an unnarrowed Metadata-only token (nothing can narrow it before the ID is known).
	 */
	private Installed install(ProbeApp app, String repository) {
		GitHubHttp.Answer installation = http().send("GET", "/repos/" + repository + "/installation", jwt(app), null);
		if (!installation.ok()) {
			throw new GitHubException(installation.status(), "the " + app.name() + " is not installed on " + repository
					+ ": HTTP " + installation.status() + " " + message(installation));
		}
		long installationId = installation.body().path("id").asLong();
		String selection = installation.body().path("repository_selection").asString("absent");
		GitHubHttp.Answer read = with(() -> mint(app, installationId, Set.of(), Map.of("metadata", "read")),
				token -> http().send("GET", "/repos/" + repository, token.value(), null));
		if (!read.ok()) {
			throw new GitHubException(read.status(), "the " + app.name() + " cannot read " + repository + ": HTTP "
					+ read.status());
		}
		return new Installed(app, installationId, selection, read.body().path("full_name").asString(repository),
				read.body().path("id").asLong(), read.body().path("default_branch").asString(""),
				read.body().path("private").asBoolean(false));
	}

	/** The repositories the installation reaches, with an unnarrowed Metadata-only token: what it may reach is the question. */
	private GitHubHttp.Answer listInstallation(Installed installed) {
		return withApp(installed, Set.of(), Map.of("metadata", "read"),
				token -> http().send("GET", "/installation/repositories?per_page=100", token.value(), null));
	}

	private @Nullable Repo platformRepository(String organization, String name) {
		GitHubHttp.Answer answer = withPlatform(Set.of(), Map.of("metadata", "read"),
				token -> http().send("GET", "/repos/" + organization + "/" + name, token.value(), null));
		if (answer.status() == 404) {
			return null;
		}
		if (!answer.ok()) {
			throw new GitHubException(answer.status(), "GET " + organization + "/" + name + ": HTTP " + answer.status());
		}
		return new Repo(answer.body().path("full_name").asString(organization + "/" + name), answer.body().path("id").asLong(),
				answer.body().path("default_branch").asString(DISPATCH_REF));
	}

	/** A token of a probe App's installation, minted with its JWT; unchecked, as {@link GitHubAppsClient#mint} is. */
	private InstallationToken mint(ProbeApp app, long installationId, Collection<Long> repositoryIds,
			Map<String, String> permissions) {
		Map<String, Object> body = new LinkedHashMap<>();
		if (!repositoryIds.isEmpty()) {
			body.put("repository_ids", List.copyOf(repositoryIds));
		}
		body.put("permissions", Map.copyOf(permissions));
		GitHubHttp.Answer answer = http().send("POST", "/app/installations/" + installationId + "/access_tokens", jwt(app),
				body);
		if (answer.status() != 201) {
			throw new GitHubException(answer.status(), "GitHub refused the " + app.name() + "'s installation token: HTTP "
					+ answer.status() + " " + message(answer));
		}
		return new InstallationToken(answer.body().path("token").asString(),
				Instant.parse(answer.body().path("expires_at").asString()), new HashSet<>(repositoryIds));
	}

	/**
	 * Revokes the token and reads GitHub's answer (GitHubAppsClient's revocation discards it): anything but
	 * 204 is printed, without the value, and fails the run.
	 */
	private void revoke(InstallationToken token) {
		int status = http().send("DELETE", "/installation/token", token.value(), null).status();
		if (status != 204) {
			this.unrevoked.add(status);
			this.err.println("Spike S6: token not revoked: HTTP " + status);
		}
	}

	/** Mints a token, runs one use of it, and revokes it whatever happened. */
	private <T> T with(Supplier<InstallationToken> mint, Function<InstallationToken, T> use) {
		InstallationToken token = mint.get();
		try {
			return use.apply(token);
		}
		finally {
			revoke(token);
		}
	}

	private <T> T withApp(Installed installed, Set<Long> repositoryIds, Map<String, String> permissions,
			Function<InstallationToken, T> use) {
		return with(() -> mint(installed.app(), installed.installationId(), repositoryIds, permissions), use);
	}

	private <T> T withPlatform(Set<Long> repositoryIds, Map<String, String> permissions, Function<InstallationToken, T> use) {
		// The dev-only probe also reads a repository's ID by name, so it mints unchecked (GitHubAppsClient.mint).
		return with(() -> this.apps.mint(App.PLATFORM, repositoryIds, permissions), use);
	}

	private String jwt(ProbeApp app) {
		return app.key().sign(this.clock.instant());
	}

	private GitHubHttp http() {
		return this.apps.http();
	}

	// The arguments and their files.

	private static @Nullable Invocation parse(ApplicationArguments args) {
		String part = single(args, "part");
		String source = single(args, "source");
		String customer = single(args, "customer");
		String runner = single(args, "runner");
		String prefix = single(args, "prefix");
		Long sourceAppId = id(single(args, "source-app-id"));
		Long runnerAppId = id(single(args, "runner-app-id"));
		Path sourceAppKey = path(single(args, "source-app-key-file"));
		Path runnerAppKey = path(single(args, "runner-app-key-file"));
		Path runnerPublicKey = path(single(args, "runner-public-key"));
		Path workflowsDir = path(single(args, "workflows-dir"));
		Path scannerJar = path(single(args, "scanner-jar"));
		Path resultPrivateKey = path(single(args, "result-private-key"));
		if (part == null) {
			return null;
		}
		return switch (part) {
			case "a" -> repository(source) && matches(LOGIN, customer) && sourceAppId != null && sourceAppKey != null
					? new Invocation(part, source, customer, null, null, sourceAppId, sourceAppKey, null, null, null, null, null,
							null)
					: null;
			// The scan's result comes back sealed, so a scan needs the key that opens it.
			case "b" -> repository(source) && repository(runner) && sourceAppId != null && sourceAppKey != null
					&& runnerAppId != null && runnerAppKey != null && runnerPublicKey != null && workflowsDir != null
					&& (prefix == null || matches(NAME, prefix)) && (scannerJar == null || resultPrivateKey != null)
							? new Invocation(part, source, null, runner, prefix, sourceAppId, sourceAppKey, runnerAppId,
									runnerAppKey, runnerPublicKey, workflowsDir, scannerJar,
									scannerJar == null ? null : resultPrivateKey)
							: null;
			case "c" -> matches(NAME, prefix) && matches(LOGIN, customer) && repository(runner) && runnerAppId != null
					&& runnerAppKey != null && runnerPublicKey != null
							? new Invocation(part, null, customer, runner, prefix, null, null, runnerAppId, runnerAppKey,
									runnerPublicKey, null, null, null)
							: null;
			default -> null;
		};
	}

	/** {@code owner/name}, refusing {@code owner/.} and {@code owner/..}, as RepositoryCopyJobHandler does. */
	private static boolean repository(@Nullable String value) {
		return matches(REPOSITORY, value) && !value.endsWith("/.") && !value.endsWith("/..");
	}

	private void usage() {
		String run = "--spring.profiles.active=dev,cli --vibefix.cli.command=" + COMMAND;
		this.err.println("Usage: " + run + " --part=a --source=<owner/name> --customer=<login>"
				+ " --source-app-id=<id> --source-app-key-file=<path>");
		this.err.println("   or: " + run + " --part=b --source=<owner/name> --runner=<owner/name> --source-app-id=<id>"
				+ " --source-app-key-file=<path> --runner-app-id=<id> --runner-app-key-file=<path>"
				+ " --runner-public-key=<path> --workflows-dir=<deploy/runner> [--prefix=<name>]"
				+ " [--scanner-jar=<path> --result-private-key=<path>]");
		this.err.println("   or: " + run + " --part=c --prefix=<part b's prefix> --customer=<login> --runner=<owner/name>"
				+ " --runner-app-id=<id> --runner-app-key-file=<path> --runner-public-key=<path>");
		this.err.println("   (parts b and c use the Platform App: vibefix.github.platform-app.id, installation-id, key-file;"
				+ " a prefix is letters, digits, and hyphens, starting with a letter or a digit)");
	}

	/** @throws IllegalArgumentException for a file that cannot be read or holds no usable key; never quoting it */
	private static Inputs read(Invocation invocation) {
		ProbeApp sourceApp = app("Source App", invocation.sourceAppId(), invocation.sourceAppKey());
		ProbeApp runnerApp = app("Runner App", invocation.runnerAppId(), invocation.runnerAppKey());
		RunnerSeal seal = invocation.runnerPublicKey() == null ? null : RunnerSeal.read(invocation.runnerPublicKey());
		Map<String, String> workflows = new LinkedHashMap<>();
		if (invocation.workflowsDir() != null) {
			for (String file : WORKFLOWS) {
				try {
					workflows.put(file, Files.readString(invocation.workflowsDir().resolve(file), StandardCharsets.UTF_8));
				}
				catch (IOException | RuntimeException ex) {
					throw new IllegalArgumentException("--workflows-dir holds no readable " + file);
				}
			}
		}
		String sha256 = invocation.scannerJar() == null ? null : sha256(invocation.scannerJar());
		RunnerSeal.Opener opener = invocation.resultPrivateKey() == null ? null
				: RunnerSeal.Opener.read(invocation.resultPrivateKey());
		return new Inputs(sourceApp, runnerApp, seal, workflows, invocation.scannerJar(), sha256, opener);
	}

	private static @Nullable ProbeApp app(String name, @Nullable Long id, @Nullable Path keyFile) {
		if (id == null || keyFile == null) {
			return null;
		}
		String pem;
		try {
			pem = Files.readString(keyFile, StandardCharsets.US_ASCII);
		}
		catch (IOException | RuntimeException ex) {
			throw new IllegalArgumentException("the " + name + "'s key file cannot be read");
		}
		try {
			return new ProbeApp(name, AppJwt.of(id, pem));
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalArgumentException("the " + name + "'s key file holds no usable RSA private key");
		}
	}

	private static String sha256(Path file) {
		try (InputStream in = Files.newInputStream(file)) {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] buffer = new byte[64 * 1024];
			for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
				digest.update(buffer, 0, read);
			}
			return HEX.formatHex(digest.digest());
		}
		catch (IOException | RuntimeException ex) {
			throw new IllegalArgumentException("--scanner-jar cannot be read");
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is missing", ex);
		}
	}

	/** {@code vibefix-scanner-{first 12 hex digits of its SHA-256}.jar}, as {@value #SCAN_WORKFLOW} downloads it. */
	static String scannerAssetName(String sha256) {
		return "vibefix-scanner-" + sha256.substring(0, 12) + ".jar";
	}

	private static long size(Path file) {
		try {
			return Files.size(file);
		}
		catch (IOException ex) {
			throw new GitHubException(0, "--scanner-jar cannot be read");
		}
	}

	private static String megabytes(long bytes) {
		return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
	}

	// Lines, checks, and small helpers.

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

	private void record(List<Check> checks, Check check) {
		checks.add(check);
		this.out.println(check.line());
	}

	private String correlation(String kind) {
		byte[] bytes = new byte[8];
		this.random.nextBytes(bytes);
		return kind + "-" + HEX.formatHex(bytes);
	}

	private static String handoverBranch(String prefix, int handover) {
		return "handover/" + prefix + "-" + handover;
	}

	private void sleep(Duration duration) {
		try {
			Thread.sleep(duration.toMillis());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new GitHubException(0, "interrupted");
		}
	}

	private static Map<String, String> permissions(JsonNode app) {
		Map<String, String> permissions = new TreeMap<>();
		app.path("permissions").properties().forEach(entry -> permissions.put(entry.getKey(), entry.getValue().asString()));
		return permissions;
	}

	private static List<String> events(JsonNode app) {
		List<String> events = new ArrayList<>();
		for (JsonNode event : app.path("events")) {
			events.add(event.asString());
		}
		return events;
	}

	/** The {@code field} of each element of a JSON array, for example the names of a directory's files. */
	private static List<String> names(JsonNode array, String field) {
		List<String> names = new ArrayList<>();
		if (array.isArray()) {
			for (JsonNode element : array) {
				names.add(element.path(field).asString(""));
			}
		}
		return names;
	}

	private static String decode(String base64) {
		try {
			return new String(Base64.getMimeDecoder().decode(base64), StandardCharsets.UTF_8);
		}
		catch (IllegalArgumentException ex) {
			return "";
		}
	}

	private static String message(GitHubHttp.Answer answer) {
		return answer.body().path("message").asString("");
	}

	private static String fieldNames(JsonNode body) {
		List<String> names = new ArrayList<>();
		body.properties().forEach(entry -> names.add(entry.getKey()));
		return names.toString();
	}

	private static String shortSha(@Nullable String sha) {
		return sha == null || sha.isEmpty() ? "-" : sha.length() > 12 ? sha.substring(0, 12) : sha;
	}

	private static boolean matches(Pattern pattern, @Nullable String value) {
		return value != null && pattern.matcher(value).matches();
	}

	private static @Nullable String single(ApplicationArguments args, String name) {
		List<String> values = args.getOptionValues(name);
		return values == null || values.size() != 1 ? null : values.getFirst();
	}

	private static @Nullable Long id(@Nullable String value) {
		return value != null && APP_ID.matcher(value).matches() ? Long.valueOf(value) : null;
	}

	private static @Nullable Path path(@Nullable String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return Path.of(value);
		}
		catch (InvalidPathException ex) {
			return null;
		}
	}

}
