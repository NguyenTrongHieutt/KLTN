package vn.vibefix.github.internal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
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
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.MissingNode;
import vn.vibefix.github.GitHubApps.App;
import vn.vibefix.github.GitHubApps.InstallationToken;
import vn.vibefix.github.GitHubApps.Registration;
import vn.vibefix.github.GitHubException;
import vn.vibefix.github.PlatformRepositories;

/**
 * Spike S6's probe, as revised on 2026-11-10: under {@code dev} with {@code cli},
 * {@code --vibefix.cli.command=spike-s6 --part=a|b|c} tries the design in which vibefix scans nothing,
 * the J7 copy and the Final Merge run on GitHub Actions in the Customer's own Source Repository (the
 * published {@code deploy/customer/vibefix.yml}, which the Customer adds as
 * {@code .github/workflows/vibefix.yml}), and the handover copy runs on the one repository of
 * {@code vibefix-dev} whose Actions may run, {@code vibefix.github.runner-repository}
 * ({@code deploy/runner/s6-copy.yml}). The Source App dispatches the Customer's runs and the Platform App
 * the runner's. No workflow holds a secret: each run exchanges its GitHub Actions OIDC ID token at the
 * probe's own {@link OidcExchange} ({@code --exchange-url}, a cloudflared quick tunnel to
 * {@code --exchange-port} in dev) for a Platform App token narrowed to its correlation's repositories.
 * <ul>
 * <li>{@code a}: the Source App's registration (Contents and Metadata read, Actions write, no event), its
 * installation on {@code --source} and its selection, a Contents-read token narrowed to the source
 * (private, default branch, head), the Customer's {@code admin} permission there (AD-13), a refused ref
 * creation, and the blob of the Customer's {@code vibefix.yml} against the published file;</li>
 * <li>{@code b}: {@code vibefix-dev}'s settings with the runner set; the Platform App's registration and
 * the runner (private, on {@value #DISPATCH_REF}, Actions on, its workflows folder exactly
 * {@value #COPY_WORKFLOW}, written here); the Source App and the Customer's file; two private
 * repositories; the J7 copy on the Customer's Actions and its run verified; Actions disabled on both
 * new repositories; one ruleset on the private Intermediary; with {@code --tampered-ref}, a run of a
 * changed {@code vibefix.yml} refused a token; every run's duration and billed minutes; and with
 * {@code --scan}, the optional gitleaks scan on the Customer's Actions ({@code operation=scan}, in a job
 * that may only read and holds no id-token permission), its run read back as the published file, and
 * of its logs only the count line {@value #COUNT_PREFIX}{@code  head=<sha> findings=<n>}: the Customer's
 * own run's report, not evidence the platform checked;</li>
 * <li>{@code c}: the Customer invited to the Intermediary with {@code pull} (a pending invitation stops
 * the run: "accept, then run again", exit 0), the permissions read back, two Contents-API commits on the
 * Working Repository each copied by the runner to {@code handover/{prefix}-n} with a pull request, the
 * second merged at its commit, the first one's state, and the Final Merge brought back by the Customer's
 * Actions as {@code vibefix/final-{prefix}}, read back with the Source App.</li>
 * </ul>
 * <b>Tokens.</b> Every token the probe mints is for one check or one run, and revoked after it through
 * {@code DELETE /installation/token}, whose answer is read: anything but 204 is printed ("token not
 * revoked: HTTP n") and fails the run. A token the exchange issues is revoked when its run ends, or when
 * the probe exits. Tokens are narrowed by numeric {@code repository_ids}, except those nothing can narrow,
 * as in Spike S4: a repository's lookup by name for its ID, the creation of the two repositories, the
 * organization reads, and the installations' repository listings. No ID token and no token is ever
 * printed, and a failed step's log lines are printed with anything that looks like either cut out.
 * <p>
 * <b>Runs.</b> A run is dispatched with {@code return_run_details} (a 404 is retried while GitHub learns a
 * workflow file just written), found by its {@code run-name} after a 204, and polled every 10 s for up to
 * 20 minutes (the job's own limit is 15); one not completed then is cancelled. A run that did not
 * succeed has its failed step's last lines printed to stderr, except a scan: its logs are searched for
 * the count line only and never printed, so no finding's file, rule or value reaches the probe. The
 * runner's run logs are deleted after that; a Customer's run logs stay in the Customer's repository.
 * <p>
 * Exit code 0 when every check is {@code OK} or {@code SKIPPED} (or the invitation is pending), 1 for a
 * {@code FAIL}, a token not revoked, or a refusal (any stack other than {@code dev} whatever the
 * arguments, no organization or runner configured, or an exchange port that cannot be bound), 2 for a
 * wrong invocation or a file that cannot be read.
 */
@Component
@Profile("cli")
class SpikeS6Command implements ApplicationRunner, ExitCodeGenerator {

	static final String COMMAND = "spike-s6";

	/** The Customer's workflow file, as published in {@code deploy/customer/}. */
	static final String CUSTOMER_WORKFLOW = "vibefix.yml";

	/** The runner's workflow file, as published in {@code deploy/runner/}. */
	static final String COPY_WORKFLOW = "s6-copy.yml";

	/** What the runner's {@code .github/workflows/} holds, and nothing else. */
	static final List<String> RUNNER_WORKFLOWS = List.of(COPY_WORKFLOW);

	/** Every runner dispatch runs the workflows of the runner's {@code main}. */
	static final String DISPATCH_REF = "main";

	/** How many lines of a failed step's log are printed. */
	static final int LOG_LINES = 40;

	/** How often a dispatch answered 404 is tried again, {@code findPoll} apart. */
	static final int DISPATCH_RETRIES = 6;

	/** The Source App's registration: Contents and Metadata read, Actions write, nothing else, and no events. */
	static final Map<String, String> SOURCE_APP_PERMISSIONS = Map.of("contents", "read", "metadata", "read", "actions",
			"write");

	/** What the J7 copy and a handover copy get: their two repositories, contents and workflows write. */
	static final Map<String, String> COPY_PERMISSIONS = Map.of("contents", "write", "workflows", "write");

	/** What the Final Merge gets: the Intermediary Repository, contents read. */
	static final Map<String, String> FINAL_MERGE_PERMISSIONS = Map.of("contents", "read");

	/** Part b's check 12, run with {@code --scan}: a name that fits the printed column. */
	static final String SCAN_CHECK = "gitleaks on Customer's Actions";

	/** What a scan run's log holds once, followed by {@code " head=<sha> findings=<n>"}. */
	static final String COUNT_PREFIX = "VIBEFIX_SCAN";

	/** The count line as GitHub's logs show it: the line's timestamp, then the scan's own {@code echo}. */
	private static final Pattern COUNT_LINE = Pattern
		.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?Z " + Pattern.quote(COUNT_PREFIX)
				+ "(?: head=(\\S{0,80}))? findings=(0|[1-9][0-9]{0,8})");

	/** What a log file may start with. */
	private static final String BOM = String.valueOf((char) 0xFEFF);

	private static final int MAX_LOG_BYTES = 16 * 1024 * 1024;

	/** A refusal that names the plan: what a Free organization answers for a ruleset on a private repository. */
	private static final Pattern PLAN_REFUSAL = Pattern.compile("upgrade|\\bpro\\b|\\bteam\\b|private repositor",
			Pattern.CASE_INSENSITIVE);

	/**
	 * What a printed log line must not show: an installation or user token, or a JWT. Installation tokens
	 * read {@code ghs_<App ID>_<JWT>} since GitHub's 2026 rollout, so a token runs on through dots and dashes.
	 */
	private static final Pattern SECRET = Pattern
		.compile("gh[pousr]_[A-Za-z0-9_.-]{16,}|eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]*");

	private static final Pattern REPOSITORY = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})/[A-Za-z0-9._-]{1,100}");

	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9-]{0,79}");

	private static final Pattern LOGIN = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})");

	private static final Pattern APP_ID = Pattern.compile("[1-9][0-9]{0,17}");

	private static final Pattern PORT = Pattern.compile("[1-9][0-9]{0,4}");

	private static final Pattern BRANCH = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,199}");

	/** An exchange host, as the workflows' {@code url_re} allows it. */
	private static final Pattern HOST = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?");

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

		/** The job's own timeout is 15 minutes; the wait adds the queue. */
		static final Waits GITHUB = new Waits(Duration.ofSeconds(10), Duration.ofMinutes(20), Duration.ofSeconds(5),
				Duration.ofSeconds(60));

	}

	/**
	 * Whose ID tokens the exchange accepts, and where it listens: GitHub's, on every address (the probe
	 * runs in a container whose port is published to the host), unless a test names others.
	 */
	record Listening(OidcExchange.Issuer issuer, @Nullable InetAddress address) {

		static final Listening GITHUB = new Listening(OidcExchange.Issuer.GITHUB, null);

	}

	/** The Source App, of the probe's arguments. */
	private record ProbeApp(String name, AppJwt key) {
	}

	/**
	 * One repository as an App's installation reaches it, with its numeric ID.
	 * @param accepted the permissions the installation accepted, which lag a changed registration
	 */
	private record Installed(ProbeApp app, long installationId, String selection, String repository, long repositoryId,
			String defaultBranch, boolean privateRepository, Map<String, String> accepted) {
	}

	/** A repository of the platform organization. */
	private record Repo(String fullName, long id, String defaultBranch, boolean privateRepository) {
	}

	/**
	 * The arguments of one part; those another part needs are left out.
	 * @param scan whether part b runs the gitleaks scan ({@code --scan})
	 */
	private record Invocation(String part, @Nullable String source, @Nullable String customer, @Nullable String prefix,
			@Nullable Long sourceAppId, @Nullable Path sourceAppKey, @Nullable Path customerWorkflow,
			@Nullable Path workflowsDir, @Nullable String exchangeUrl, int exchangePort, @Nullable String tamperedRef,
			boolean scan) {
	}

	/**
	 * What the arguments' files hold, read before any call to GitHub: each published workflow with its line
	 * ends as LF, and its blob SHA ({@link OidcExchange#blobSha}).
	 */
	private record Inputs(@Nullable ProbeApp sourceApp, @Nullable String customerWorkflow, @Nullable String customerBlob,
			@Nullable String runnerWorkflow, @Nullable String runnerBlob) {
	}

	/** Mints a token of one App's installation. */
	@FunctionalInterface
	private interface Minter {

		InstallationToken mint(Set<Long> repositoryIds, Map<String, String> permissions);

	}

	/**
	 * Where a workflow runs: its repository, default branch, file and published blob, the bot that
	 * dispatches it, whether its run logs are deleted, and the tokens of that repository's own App.
	 */
	private record Actions(String repository, long repositoryId, String defaultBranch, String workflow, String blob,
			String bot, boolean deletesLogs, Minter tokens) {

		String path() {
			return ".github/workflows/" + this.workflow;
		}

	}

	/** A run's first failed step: its job, and the step's number and name. */
	private record FailedStep(String job, int number, String name) {
	}

	/**
	 * What a scan run's logs give: its one count of findings and the head it names, or -1 with why there is
	 * none.
	 * @param incomplete whether another read of the logs may still find the line
	 * @param failedStep for a run that did not succeed, its failed step or why it is not known
	 */
	private record Count(int findings, @Nullable String head, @Nullable String problem, boolean incomplete,
			@Nullable String failedStep) {

		static Count missing(String problem, boolean incomplete, @Nullable String failedStep) {
			return new Count(-1, null, problem, incomplete, failedStep);
		}

		boolean found() {
			return this.findings >= 0;
		}

	}

	/** What Free bills a run, and where the figure comes from ({@code timing.billable}, {@code jobs}, or unknown). */
	private record Billed(long minutes, String from, String detail) {

		static final Billed UNKNOWN = new Billed(-1, "unknown", "neither timing.billable nor the jobs' times");

	}

	/**
	 * What one workflow run gave back; {@code problem} is why it does not count.
	 * @param run the last read of the run
	 * @param logsDeleted the logs' deletion status, or {@link #KEPT} for a Customer's run
	 * @param exchange what the exchange did for the run's correlation
	 * @param unmatched the attempts the exchange refused meanwhile without holding them against any
	 * correlation: refused before verification, or naming none it expects
	 */
	private record Ran(int status, long runId, String how, String conclusion, boolean completed, JsonNode run,
			@Nullable String problem, long durationMs, Billed billed, int logsDeleted, OidcExchange.Result exchange,
			int unmatched) {

		static final int KEPT = -1;

		static Ran refused(int status, String how, String problem, OidcExchange.Result exchange) {
			return new Ran(status, 0, how, "-", false, MissingNode.getInstance(), problem, -1, Billed.UNKNOWN, KEPT, exchange,
					0);
		}

		Ran withUnmatched(int count) {
			return new Ran(this.status, this.runId, this.how, this.conclusion, this.completed, this.run, this.problem,
					this.durationMs, this.billed, this.logsDeleted, this.exchange, count);
		}

		boolean cleanupFailed() {
			return this.completed && this.logsDeleted != KEPT && this.logsDeleted != 204;
		}

		/** The run succeeded with the one token the exchange issued it, and that token was revoked. */
		boolean ok() {
			return this.problem == null && this.completed && "success".equals(this.conclusion) && this.exchange.issued()
					&& this.exchange.runId() == this.runId && this.exchange.revoked() == 204 && !cleanupFailed();
		}

		String exchanged() {
			OidcExchange.Result result = this.exchange;
			String refusals = result.refusals() + (result.dropped() > 0 ? " and " + result.dropped() + " more" : "");
			String unmatchedAttempts = this.unmatched > 0
					? "; " + this.unmatched + " attempt(s) refused meanwhile, unverified or naming no expected correlation" : "";
			if (result.issued()) {
				return "token issued to run " + result.runId() + ", revoked HTTP " + result.revoked()
						+ (result.refusals().isEmpty() ? "" : "; later attempts refused " + refusals) + unmatchedAttempts;
			}
			return (result.refusals().isEmpty() ? "no token issued: no verified attempt named the run's correlation"
					: "no token issued: the exchange refused " + refusals) + unmatchedAttempts;
		}

		String describe() {
			StringBuilder line = new StringBuilder(this.how);
			if (this.runId > 0) {
				line.append(", run ").append(this.runId).append(' ').append(this.conclusion);
			}
			line.append(", ").append(exchanged());
			if (this.problem != null) {
				line.append(", ").append(this.problem);
			}
			if (this.durationMs >= 0) {
				line.append(", ").append(this.durationMs).append(" ms");
			}
			if (this.completed) {
				line.append(this.logsDeleted == KEPT ? ", logs kept (a Customer's run)" : ", logs deleted HTTP " + this.logsDeleted);
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

		/** The Source App's bot, {@code <slug>[bot]}: the triggering actor of the Customer's runs. */
		@Nullable String sourceBot;

		@Nullable Repo runner;

		/** The Platform App's bot: the triggering actor of the runner's runs. */
		@Nullable String platformBot;

		@Nullable Repo intermediary;

		@Nullable Repo working;

		@Nullable Ran j7;

		boolean invitationPending;

		@Nullable String h1;

		@Nullable String h2;

		int pull1;

		int pull2;

		@Nullable String mergeCommit;

		final Timings timings = new Timings();

	}

	private final GitHubAppsClient apps;

	private final PlatformRepositories repositories;

	private final Environment environment;

	private final Clock clock;

	private final Waits waits;

	private final Listening listening;

	private final PrintStream out;

	private final PrintStream err;

	private final SecureRandom random = new SecureRandom();

	/** For the logs' redirect: GitHubHttp sends JSON to the API host only. */
	private final HttpClient transfers = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(10))
		.followRedirects(HttpClient.Redirect.NEVER)
		.build();

	/** The statuses of revocations answered otherwise than 204, in this invocation; the exchange's threads add to it too. */
	private final List<Integer> unrevoked = Collections.synchronizedList(new ArrayList<>());

	private volatile int exitCode;

	@Autowired
	SpikeS6Command(GitHubAppsClient apps, PlatformRepositories repositories, Environment environment, Clock clock) {
		this(apps, repositories, environment, clock, Waits.GITHUB, Listening.GITHUB, System.out, System.err);
	}

	SpikeS6Command(GitHubAppsClient apps, PlatformRepositories repositories, Environment environment, Clock clock,
			Waits waits, Listening listening, PrintStream out, PrintStream err) {
		this.apps = apps;
		this.repositories = repositories;
		this.environment = environment;
		this.clock = clock;
		this.waits = waits;
		this.listening = listening;
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
		// Plain http on loopback reaches only a stand-in issuer's runs: GitHub's runs need https.
		Invocation invocation = parse(args, !OidcExchange.Issuer.GITHUB.equals(this.listening.issuer()));
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
		String runner = this.apps.properties().runnerRepository();
		if (runner == null && !"a".equals(invocation.part())) {
			this.err.println("Refused: vibefix.github.runner-repository is not configured; parts b and c need the runner"
					+ " (VIBEFIX_GITHUB_RUNNER_REPOSITORY in deploy/.env)");
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
		if ("a".equals(invocation.part())) {
			partA(invocation, inputs, checks);
		}
		else {
			OidcExchange exchange = new OidcExchange(this.listening.issuer(), this.clock, this::revoke);
			try {
				exchange.listen(new InetSocketAddress(this.listening.address(), invocation.exchangePort()));
			}
			catch (IOException | RuntimeException ex) {
				this.err.println("Refused: the exchange cannot listen on port " + invocation.exchangePort());
				this.exitCode = 1;
				return;
			}
			// Ctrl+C or docker stop: the hook revokes every token still out before the JVM ends.
			Thread closer = new Thread(exchange::close, "spike-s6-exchange-close");
			Runtime.getRuntime().addShutdownHook(closer);
			try {
				if ("b".equals(invocation.part())) {
					partB(organization, runner, invocation, inputs, exchange, checks);
				}
				else {
					waiting = partC(organization, runner, invocation, inputs, exchange, checks);
				}
			}
			finally {
				// Every token the exchange issued and no run has given back is revoked here.
				exchange.close();
				try {
					Runtime.getRuntime().removeShutdownHook(closer);
				}
				catch (IllegalStateException shuttingDown) {
					// The hook is running already; its close waited for this one and does nothing more.
				}
			}
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
		record(checks, check(1, "Source App registration", () -> registration(app, state)));
		record(checks, check(2, "Source App installation", () -> sourceSelection(app, source, state)));
		boolean installed = state.source != null;
		record(checks, installed ? check(3, "contents read narrowed to source", () -> sourceRead(state))
				: skipped(3, "contents read narrowed to source"));
		record(checks, installed
				? check(4, "Customer's permission on source", () -> customerPermission(state, invocation.customer()))
				: skipped(4, "Customer's permission on source"));
		record(checks, installed && state.sourceHead != null ? check(5, "ref creation refused", () -> refRefused(state))
				: skipped(5, "ref creation refused"));
		record(checks, installed && state.sourceBranch != null
				? check(6, "customer workflow file", () -> customerFile(inputs, state))
				: skipped(6, "customer workflow file"));
	}

	private Check registration(ProbeApp app, State state) {
		GitHubHttp.Answer answer = http().send("GET", "/app", jwt(app), null);
		Map<String, String> permissions = permissions(answer.body());
		List<String> events = events(answer.body());
		String slug = answer.body().path("slug").asString("");
		boolean ok = answer.status() == 200 && permissions.equals(SOURCE_APP_PERMISSIONS) && events.isEmpty() && !slug.isEmpty();
		if (!slug.isEmpty()) {
			state.sourceBot = slug + "[bot]";
		}
		return new Check(1, "Source App registration", ok ? Outcome.OK : Outcome.FAIL, answer.status(),
				"slug=" + (slug.isEmpty() ? "-" : slug) + " permissions=" + permissions + " events=" + events
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

	/** The Customer's {@code .github/workflows/vibefix.yml} on the default branch, by blob, against the published file. */
	private Check customerFile(Inputs inputs, State state) {
		Installed source = state.source;
		return withApp(source, Set.of(source.repositoryId()), Map.of("contents", "read"), token -> {
			GitHubHttp.Answer file = customerFileAt(source, state.sourceBranch, token);
			String blob = file.body().path("sha").asString("");
			boolean ok = file.status() == 200 && inputs.customerBlob().equals(blob);
			return new Check(6, "customer workflow file", ok ? Outcome.OK : Outcome.FAIL, file.status(),
					fileDetail(file, blob, inputs.customerBlob(), state.sourceBranch));
		});
	}

	private GitHubHttp.Answer customerFileAt(Installed source, String ref, InstallationToken token) {
		return http().send("GET", "/repos/" + source.repository() + "/contents/.github/workflows/" + CUSTOMER_WORKFLOW
				+ "?ref=" + URLEncoder.encode(ref, StandardCharsets.UTF_8), token.value(), null);
	}

	private static String fileDetail(GitHubHttp.Answer file, String blob, String published, String branch) {
		if (file.status() == 404) {
			return ".github/workflows/" + CUSTOMER_WORKFLOW + " is not on " + branch
					+ ": the Customer adds deploy/customer/" + CUSTOMER_WORKFLOW + " there unchanged";
		}
		return ".github/workflows/" + CUSTOMER_WORKFLOW + " on " + branch + " blob=" + shortSha(blob)
				+ (published.equals(blob) ? ", the published file's"
						: " while the published file's is " + shortSha(published)
								+ ": the Customer copies deploy/customer/" + CUSTOMER_WORKFLOW + " unchanged");
	}

	// Part b: the J7 copy on the Customer's Actions.

	private void partB(String organization, String runnerName, Invocation invocation, Inputs inputs, OidcExchange exchange,
			List<Check> checks) {
		String prefix = invocation.prefix() != null ? invocation.prefix() : "s6-" + PREFIX.format(this.clock.instant());
		String source = invocation.source();
		this.out.println("Spike S6 part b on " + organization + ": " + source + " on its own Actions into " + organization + "/"
				+ prefix + "-intermediary and " + prefix + "-working, the runner " + organization + "/" + runnerName
				+ ", the exchange " + invocation.exchangeUrl() + " on port " + invocation.exchangePort());
		State state = new State();
		Check settings = check(1, "organization settings", () -> organizationSettings(organization, runnerName));
		record(checks, settings);
		Check runner = check(2, "Platform App and runner", () -> platformAndRunner(organization, runnerName, inputs, state));
		record(checks, runner);
		Check customer = check(3, "Source App and customer file", () -> sourceAndFile(inputs, source, state));
		record(checks, customer);
		// Nothing is created unless the organization, the runner and the Customer's side are as the design needs them.
		boolean create = settings.outcome() == Outcome.OK && runner.outcome() == Outcome.OK && customer.outcome() == Outcome.OK;
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
		Check copy = copies
				? check(6, "J7 copy on Customer's Actions", () -> j7Copy(inputs, exchange, invocation.exchangeUrl(), state))
				: skipped(6, "J7 copy on Customer's Actions");
		record(checks, copy);
		record(checks, copy.outcome() == Outcome.OK && state.j7 != null ? check(7, "J7 run verified", () -> verified(state))
				: skipped(7, "J7 run verified"));
		record(checks, state.intermediary != null && state.working != null
				? check(8, "Actions disabled on both", () -> actionsDisabled(state))
				: skipped(8, "Actions disabled on both"));
		record(checks, state.intermediary != null ? check(9, "ruleset on private Intermediary", () -> ruleset(state))
				: skipped(9, "ruleset on private Intermediary"));
		record(checks, invocation.tamperedRef() == null
				? new Check(10, "tampered workflow refused", Outcome.SKIPPED, 0, "no --tampered-ref")
				: copies ? check(10, "tampered workflow refused",
						() -> tampered(inputs, exchange, invocation.exchangeUrl(), invocation.tamperedRef(), state))
						: skipped(10, "tampered workflow refused"));
		record(checks, durations(11, state));
		// The scan needs only the Customer's side: it creates nothing and holds no token.
		record(checks, !invocation.scan() ? new Check(12, SCAN_CHECK, Outcome.SKIPPED, 0, "no --scan")
				: customer.outcome() == Outcome.OK ? check(12, SCAN_CHECK, () -> scan(inputs, exchange, state))
						: skipped(12, SCAN_CHECK));
	}

	/** AD-11's settings through PlatformRepositories (the runner set), and whether members may fork private repositories. */
	private Check organizationSettings(String organization, String runner) {
		List<String> differences = this.repositories.organizationDifferences();
		GitHubHttp.Answer org = withPlatform(Set.of(), Map.of("organization_administration", "read"),
				token -> http().send("GET", "/orgs/" + organization, token.value(), null));
		JsonNode fork = org.body().path("members_can_fork_private_repositories");
		boolean ok = org.ok() && differences.isEmpty() && fork.isBoolean() && !fork.asBoolean();
		return new Check(1, "organization settings", ok ? Outcome.OK : Outcome.FAIL, org.status(),
				"2FA required, base permission none, Actions on " + runner + " only: "
						+ (differences.isEmpty() ? "as AD-11 needs" : String.join("; ", differences))
						+ ", members_can_fork_private_repositories=" + (fork.isMissingNode() ? "absent" : fork.asString()));
	}

	/**
	 * The Platform App with AD-10's permissions and Actions write, a private runner on
	 * {@value #DISPATCH_REF} with Actions on, its workflow written with a Platform token narrowed to it
	 * (contents and workflows write), and nothing else in its workflows folder. Nothing is written
	 * unless the App, its installation's accepted permissions, the runner, and the folder are right.
	 */
	private Check platformAndRunner(String organization, String runnerName, Inputs inputs, State state) {
		Registration registration = this.apps.registration(App.PLATFORM);
		List<String> appDifferences = AppExpectations.differences(App.PLATFORM, registration);
		// What the installation accepted: a changed registration waits for an owner to accept it.
		GitHubHttp.Answer installation = this.apps.installation(App.PLATFORM);
		String accepted = installation.body().path("permissions").path("actions").asString("-");
		String app = "slug=" + registration.slug() + " permissions=" + new TreeMap<>(registration.permissions())
				+ "; installation actions=" + accepted + (installation.ok() ? "" : " (HTTP " + installation.status() + ")");
		Repo runner = platformRepository(organization, runnerName);
		if (runner == null) {
			return new Check(2, "Platform App and runner", Outcome.FAIL, 404, app + "; no " + organization + "/" + runnerName
					+ ": create it private, with a README on " + DISPATCH_REF + "; nothing written");
		}
		JsonNode enabled = withPlatform(Set.of(runner.id()), Map.of("administration", "read"),
				token -> http().send("GET", "/repos/" + runner.fullName() + "/actions/permissions", token.value(), null))
			.body()
			.path("enabled");
		boolean actionsOn = enabled.isBoolean() && enabled.asBoolean();
		boolean shaped = runner.privateRepository() && DISPATCH_REF.equals(runner.defaultBranch());
		String found = app + "; " + runner.fullName() + " id=" + runner.id() + " private=" + runner.privateRepository()
				+ " default_branch=" + runner.defaultBranch() + " actions enabled="
				+ (enabled.isMissingNode() ? "absent" : enabled.asString());
		boolean acceptedActions = installation.ok() && "write".equals(accepted);
		if (!appDifferences.isEmpty() || !acceptedActions || !shaped || !actionsOn) {
			List<String> wrong = new ArrayList<>();
			if (!appDifferences.isEmpty()) {
				wrong.add("the Platform App must hold AD-10's permissions and actions=write: " + String.join("; ", appDifferences));
			}
			if (!acceptedActions) {
				wrong.add("the Platform App's installation has not accepted actions write: accept the changed permissions"
						+ " in " + organization + "'s Settings › GitHub Apps");
			}
			if (!shaped) {
				wrong.add("the runner must be private, with the default branch " + DISPATCH_REF);
			}
			if (!actionsOn) {
				wrong.add("Actions must be enabled on the runner");
			}
			return new Check(2, "Platform App and runner", Outcome.FAIL, 200, found + "; nothing written: " + String.join("; ", wrong));
		}
		state.runner = runner;
		state.platformBot = registration.slug() + "[bot]";
		List<String> written = new ArrayList<>();
		int[] status = { 0 };
		List<String> files = new ArrayList<>();
		List<String> before = new ArrayList<>();
		boolean[] refused = { false };
		boolean ok = withPlatform(Set.of(runner.id()), COPY_PERMISSIONS, token -> {
			String directory = "/repos/" + runner.fullName() + "/contents/.github/workflows";
			// The folder first: nothing is written to a runner that holds any other workflow.
			GitHubHttp.Answer listed = http().send("GET", directory + "?ref=" + DISPATCH_REF, token.value(), null);
			if (listed.status() != 404) {
				before.addAll(names(listed.body(), "name"));
			}
			if ((listed.status() != 404 && !listed.ok()) || !RUNNER_WORKFLOWS.containsAll(before)) {
				status[0] = listed.status();
				refused[0] = true;
				written.add("nothing written: .github/workflows/ already holds " + before
						+ (listed.ok() ? "" : " (HTTP " + listed.status() + ")"));
				return false;
			}
			String path = directory + "/" + COPY_WORKFLOW;
			GitHubHttp.Answer current = http().send("GET", path + "?ref=" + DISPATCH_REF, token.value(), null);
			boolean put = true;
			if (current.status() == 200 && inputs.runnerWorkflow().equals(decode(current.body().path("content").asString("")))) {
				written.add(COPY_WORKFLOW + "=unchanged");
				status[0] = 200;
			}
			else {
				Map<String, Object> body = new LinkedHashMap<>();
				body.put("message", "Spike S6: " + COPY_WORKFLOW);
				body.put("content", Base64.getEncoder().encodeToString(inputs.runnerWorkflow().getBytes(StandardCharsets.UTF_8)));
				body.put("branch", DISPATCH_REF);
				if (current.status() == 200) {
					body.put("sha", current.body().path("sha").asString(""));
				}
				GitHubHttp.Answer answer = http().send("PUT", path, token.value(), body);
				status[0] = answer.status();
				put = answer.status() == 200 || answer.status() == 201;
				written.add(COPY_WORKFLOW + "=" + (answer.status() == 201 ? "created" : answer.status() == 200 ? "updated"
						: "HTTP " + answer.status() + " " + message(answer)));
			}
			GitHubHttp.Answer listing = http().send("GET", directory + "?ref=" + DISPATCH_REF, token.value(), null);
			files.addAll(names(listing.body(), "name"));
			return put && listing.ok() && files.equals(RUNNER_WORKFLOWS);
		});
		if (refused[0]) {
			return new Check(2, "Platform App and runner", Outcome.FAIL, status[0],
					found + "; " + written.getFirst() + "; it must hold " + RUNNER_WORKFLOWS + " only");
		}
		return new Check(2, "Platform App and runner", ok ? Outcome.OK : Outcome.FAIL, status[0],
				found + "; " + String.join(", ", written) + "; .github/workflows/ holds " + files
						+ (ok ? "" : " (it must hold " + RUNNER_WORKFLOWS + " only)"));
	}

	/**
	 * The Source App as the design needs it (exactly Contents and Metadata read and Actions write, no event,
	 * installed on the source only), the source's head, and its {@code vibefix.yml} as published.
	 */
	private Check sourceAndFile(Inputs inputs, String sourceName, State state) {
		ProbeApp app = inputs.sourceApp();
		GitHubHttp.Answer registration = http().send("GET", "/app", jwt(app), null);
		Map<String, String> permissions = permissions(registration.body());
		List<String> events = events(registration.body());
		String slug = registration.body().path("slug").asString("");
		boolean registered = registration.status() == 200 && permissions.equals(SOURCE_APP_PERMISSIONS) && events.isEmpty()
				&& !slug.isEmpty();
		Installed installed = install(app, sourceName);
		GitHubHttp.Answer listed = listInstallation(installed);
		List<String> names = names(listed.body().path("repositories"), "full_name");
		boolean selected = listed.ok() && "selected".equals(installed.selection()) && names.size() == 1
				&& names.getFirst().equalsIgnoreCase(installed.repository());
		boolean acceptedActions = "write".equals(installed.accepted().get("actions"));
		String[] head = { "" };
		GitHubHttp.Answer file = withApp(installed, Set.of(installed.repositoryId()), Map.of("contents", "read"), token -> {
			GitHubHttp.Answer ref = http().send("GET",
					"/repos/" + installed.repository() + "/git/ref/heads/" + installed.defaultBranch(), token.value(), null);
			head[0] = ref.body().path("object").path("sha").asString("");
			return customerFileAt(installed, installed.defaultBranch(), token);
		});
		String blob = file.body().path("sha").asString("");
		boolean headRead = SHA.matcher(head[0]).matches();
		boolean published = file.status() == 200 && inputs.customerBlob().equals(blob);
		boolean ok = registered && selected && acceptedActions && headRead && published;
		if (ok) {
			state.source = installed;
			state.sourceBranch = installed.defaultBranch();
			state.sourceHead = head[0];
			state.sourceBot = slug + "[bot]";
		}
		return new Check(3, "Source App and customer file", ok ? Outcome.OK : Outcome.FAIL, registration.status(),
				"slug=" + (slug.isEmpty() ? "-" : slug) + " permissions=" + permissions + " events=" + events
						+ (registered ? "" : " (expected permissions=" + new TreeMap<>(SOURCE_APP_PERMISSIONS) + " events=[])")
						+ "; repository_selection=" + installed.selection() + " repositories=" + names
						+ (selected ? "" : " (it must be installed on " + installed.repository() + " only)")
						+ (acceptedActions ? "" : "; the Source App's installation has not accepted actions write (accepted "
								+ installed.accepted() + "): the Customer accepts the changed permissions")
						+ "; "
						+ installed.repository() + " private=" + installed.privateRepository() + " head=" + shortSha(head[0]) + "; "
						+ fileDetail(file, blob, inputs.customerBlob(), installed.defaultBranch()));
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
					repository.path("id").asLong(), repository.path("default_branch").asString(DISPATCH_REF), isPrivate);
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

	/** J7: the Customer's run copies the source's head to both new repositories, each read back at that commit. */
	private Check j7Copy(Inputs inputs, OidcExchange exchange, String exchangeUrl, State state) {
		Actions where = customerActions(inputs, state);
		String correlation = correlation("j7-copy");
		Ran ran = runWorkflow(where, where.defaultBranch(), correlation, copyInputs(correlation, exchangeUrl, state),
				copyGrant(state), exchange);
		state.j7 = ran;
		state.timings.add("j7-copy", ran);
		if (!ran.ok()) {
			return new Check(6, "J7 copy on Customer's Actions", Outcome.FAIL, ran.status(), ran.describe());
		}
		String intermediaryHead = head(state.intermediary, state.sourceBranch);
		String workingHead = head(state.working, state.sourceBranch);
		boolean ok = state.sourceHead.equals(intermediaryHead) && state.sourceHead.equals(workingHead);
		return new Check(6, "J7 copy on Customer's Actions", ok ? Outcome.OK : Outcome.FAIL, ran.status(),
				ran.describe() + ", read back " + state.intermediary.fullName() + " " + state.sourceBranch + "="
						+ shortSha(intermediaryHead) + " and " + state.working.fullName() + " " + state.sourceBranch + "="
						+ shortSha(workingHead) + (ok ? "" : " instead of " + shortSha(state.sourceHead)));
	}

	/**
	 * The J7 run as the probe reads it (the Customer's file, dispatched by the Source App on the default
	 * branch at the approved head), and what the exchange did: one token, for that run, requested for the
	 * two repositories with contents and workflows write (the probe's request, not GitHub's answer),
	 * revoked when the run ended.
	 */
	private Check verified(State state) {
		Ran ran = state.j7;
		OidcExchange.Result exchanged = ran.exchange();
		JsonNode run = ran.run();
		String path = run.path("path").asString("-");
		String actor = run.path("triggering_actor").path("login").asString("-");
		String event = run.path("event").asString("-");
		String headSha = run.path("head_sha").asString("");
		boolean read = (".github/workflows/" + CUSTOMER_WORKFLOW).equals(path) && actor.equals(state.sourceBot)
				&& "workflow_dispatch".equals(event) && headSha.equals(state.sourceHead);
		OidcExchange.Grant grant = exchanged.grant();
		boolean narrowed = exchanged.issued() && exchanged.runId() == ran.runId() && copyGrant(state).equals(grant);
		boolean revoked = exchanged.revoked() == 204;
		boolean ok = read && narrowed && revoked;
		return new Check(7, "J7 run verified", ok ? Outcome.OK : Outcome.FAIL, 0, "run " + ran.runId() + " path=" + path
				+ " event=" + event + " triggering_actor=" + actor + " head_sha=" + shortSha(headSha) + "; the exchange issued "
				+ (exchanged.issued() ? "one token for run " + exchanged.runId() : "no token") + ", requested for "
				+ (grant == null ? "-" : new TreeSet<>(grant.repositoryIds()) + " " + new TreeMap<>(grant.permissions()))
				+ ", revoked HTTP " + exchanged.revoked()
				+ (exchanged.refusals().isEmpty() ? "" : "; other attempts refused " + exchanged.refusals())
				+ (read ? "" : " (expected " + CUSTOMER_WORKFLOW + " dispatched by " + state.sourceBot + " at "
						+ shortSha(state.sourceHead) + ")"));
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

	/**
	 * A run of a changed {@code vibefix.yml}, dispatched on {@code --tampered-ref}: OK when the exchange
	 * refused it ({@code ref} or {@code blob}), issued nothing, and both repositories stayed where they were.
	 */
	private Check tampered(Inputs inputs, OidcExchange exchange, String exchangeUrl, String tamperedRef, State state) {
		if (tamperedRef.equals(state.sourceBranch)) {
			return new Check(10, "tampered workflow refused", Outcome.FAIL, 0, "--tampered-ref names the default branch "
					+ tamperedRef + ": it must name another branch, whose " + CUSTOMER_WORKFLOW + " differs");
		}
		String intermediaryBefore = readHead(state.intermediary, state.sourceBranch);
		String workingBefore = readHead(state.working, state.sourceBranch);
		Actions where = customerActions(inputs, state);
		String correlation = correlation("tampered");
		Ran ran = runWorkflow(where, tamperedRef, correlation, copyInputs(correlation, exchangeUrl, state), copyGrant(state),
				exchange);
		state.timings.add("tampered", ran);
		String intermediaryAfter = readHead(state.intermediary, state.sourceBranch);
		String workingAfter = readHead(state.working, state.sourceBranch);
		List<String> refusals = ran.exchange().refusals();
		boolean refused = !ran.exchange().issued() && (refusals.contains("ref") || refusals.contains("blob"));
		// A head that could not be read proves nothing either way.
		boolean read = intermediaryBefore != null && workingBefore != null && intermediaryAfter != null && workingAfter != null;
		boolean unchanged = read && intermediaryBefore.equals(intermediaryAfter) && workingBefore.equals(workingAfter);
		return new Check(10, "tampered workflow refused", refused && unchanged ? Outcome.OK : Outcome.FAIL, ran.status(),
				"dispatched on " + tamperedRef + ": " + ran.describe() + "; " + state.intermediary.fullName() + " "
						+ state.sourceBranch + "=" + headLabel(intermediaryAfter) + " and " + state.working.fullName() + " "
						+ state.sourceBranch + "=" + headLabel(workingAfter)
						+ (!read ? ", a head could not be read (before " + headLabel(intermediaryBefore) + " and "
								+ headLabel(workingBefore) + ")"
								: unchanged ? ", unchanged"
										: ", MOVED from " + shortSha(intermediaryBefore) + " and " + shortSha(workingBefore)));
	}

	private static String headLabel(@Nullable String head) {
		return head == null ? "unreadable" : shortSha(head);
	}

	private Map<String, String> copyInputs(String correlation, String exchangeUrl, State state) {
		Map<String, String> inputs = new LinkedHashMap<>();
		inputs.put("operation", "copy");
		inputs.put("correlation", correlation);
		inputs.put("exchange_url", exchangeUrl);
		inputs.put("sha", state.sourceHead);
		inputs.put("branch", state.sourceBranch);
		inputs.put("intermediary", state.intermediary.fullName());
		inputs.put("working", state.working.fullName());
		return inputs;
	}

	private static OidcExchange.Grant copyGrant(State state) {
		return new OidcExchange.Grant(Set.of(state.intermediary.id(), state.working.id()), COPY_PERMISSIONS);
	}

	private static Check durations(int number, State state) {
		return state.timings.isEmpty()
				? new Check(number, "run durations and billed minutes", Outcome.SKIPPED, 0, "no run completed")
				: new Check(number, "run durations and billed minutes", Outcome.OK, 0, state.timings.describe());
	}

	/**
	 * The gitleaks scan on the Customer's Actions: the Source App dispatches {@code operation=scan} on the
	 * default branch, where the workflow's scan job may only read and holds no id-token permission, and the
	 * probe waits as for the other runs. The exchange expects nothing for it. The run is read back as the
	 * Customer's file: its {@code path}, and the blob at its {@code head_sha} against the published file.
	 * OK when those hold and the run's logs hold one count line, naming a 40-hex head, whose count agrees
	 * with the conclusion: success at 0, failure above 0. That count is the Customer's own run's report, not
	 * evidence the platform checked. The probe never reads the run's summary and prints no line of its
	 * logs, which stay in the Customer's repository.
	 */
	private Check scan(Inputs inputs, OidcExchange exchange, State state) {
		Installed source = state.source;
		Actions where = customerActions(inputs, state);
		String correlation = correlation("scan");
		Map<String, String> workflowInputs = new LinkedHashMap<>();
		workflowInputs.put("operation", "scan");
		workflowInputs.put("correlation", correlation);
		Ran ran = withRepository(where, Map.of("actions", "write"),
				token -> dispatchAndWait(where, where.defaultBranch(), correlation, workflowInputs, exchange, token, false));
		if (ran.problem() != null) {
			return new Check(12, SCAN_CHECK, Outcome.FAIL, ran.status(), ran.how() + ", no exchange expected, " + ran.problem());
		}
		// The run as the Customer's file: its path, and its blob where the run read it.
		String path = ran.run().path("path").asString("-");
		String headSha = ran.run().path("head_sha").asString("");
		GitHubHttp.Answer file = SHA.matcher(headSha).matches() ? withApp(source, Set.of(source.repositoryId()),
				Map.of("contents", "read"), token -> customerFileAt(source, headSha, token)) : null;
		String blob = file == null ? "" : file.body().path("sha").asString("");
		boolean genuine = where.path().equals(path) && file != null && file.status() == 200
				&& inputs.customerBlob().equals(blob);
		Count count = scanCount(where, ran);
		String head = count.head();
		boolean headed = head != null && SHA.matcher(head).matches();
		boolean agrees = count.found() && headed
				&& (count.findings() == 0 ? "success".equals(ran.conclusion()) : "failure".equals(ran.conclusion()));
		StringBuilder detail = new StringBuilder(ran.how()).append(", run ")
			.append(ran.runId())
			.append(" conclusion=")
			.append(ran.conclusion())
			.append(" findings=")
			.append(count.found() ? String.valueOf(count.findings()) : "-")
			.append(" head=")
			.append(head == null || head.isEmpty() ? "-" : shortSha(head))
			.append(", read back path=")
			.append(path)
			.append(" head_sha=")
			.append(shortSha(headSha))
			.append(" blob=")
			.append(shortSha(blob))
			.append(genuine ? ", the published file's"
					: !where.path().equals(path) ? " (expected " + where.path() + ")"
							: file == null ? " (a head_sha that is not 40 hex digits)"
									: file.status() != 200 ? " (the file at head_sha answered HTTP " + file.status() + ")"
											: " while the published file's is " + shortSha(inputs.customerBlob()))
			.append(", no exchange expected, ")
			.append(ran.durationMs() >= 0 ? ran.durationMs() + " ms" : "? ms")
			.append(" billed=")
			.append(ran.billed().minutes() >= 0 ? ran.billed().minutes() + " min" : "? min")
			.append(" (")
			.append(ran.billed().from())
			.append("), logs kept (a Customer's run)");
		if (count.failedStep() != null) {
			detail.append(", ").append(count.failedStep());
		}
		if (!count.found()) {
			detail.append(", ").append(count.problem());
		}
		else if (!headed) {
			detail.append(", a count line whose head is missing or not 40 hex digits");
		}
		else if (!agrees) {
			detail.append(", a conclusion that does not match the count (success at 0, failure above 0)");
		}
		return new Check(12, SCAN_CHECK, genuine && agrees ? Outcome.OK : Outcome.FAIL, ran.status(), detail.toString());
	}

	/**
	 * The scan run's count from its logs, read with a Source App token narrowed to the source, Actions read,
	 * and read once more after a poll while they hold no count line (GitHub may still be writing them); for
	 * a run that did not succeed, also its failed step. No line is printed.
	 */
	private Count scanCount(Actions where, Ran ran) {
		String actions = "/repos/" + where.repository() + "/actions";
		return withRepository(where, Map.of("actions", "read"), token -> {
			String failed = null;
			if (!"success".equals(ran.conclusion())) {
				GitHubHttp.Answer jobs = http().send("GET", actions + "/runs/" + ran.runId() + "/jobs?per_page=100",
						token.value(), null);
				FailedStep step = jobs.ok() ? failedStep(jobs.body()) : null;
				failed = !jobs.ok() ? "jobs unreadable: HTTP " + jobs.status()
						: step == null ? "no failed step named" : "failed at step " + step.number() + " (" + step.name() + ")";
			}
			Count count = logsCount(actions, ran.runId(), token, failed);
			if (count.incomplete()) {
				sleep(this.waits.runPoll());
				count = logsCount(actions, ran.runId(), token, failed);
			}
			return count;
		});
	}

	private Count logsCount(String actions, long runId, InstallationToken token, @Nullable String failed) {
		byte[] zip;
		try {
			zip = download(actions + "/runs/" + runId + "/logs", token, MAX_LOG_BYTES, "the run's logs");
		}
		catch (GitHubException ex) {
			return Count.missing("its logs could not be read: " + ex.getMessage(), true, failed);
		}
		return count(zip, failed);
	}

	/**
	 * The one count line in a scan run's logs: lines split on LF only, each starting with GitHub's
	 * timestamp. GitHub's zip holds the whole job's log and each step's, so the line may appear in two
	 * files; no file may hold it twice, and every file must agree.
	 */
	private static Count count(byte[] zip, @Nullable String failed) {
		Set<String> found = new TreeSet<>();
		boolean repeated = false;
		boolean read = false;
		try (ZipInputStream entries = new ZipInputStream(new ByteArrayInputStream(zip))) {
			for (ZipEntry entry = entries.getNextEntry(); entry != null; entry = entries.getNextEntry()) {
				if (entry.isDirectory()) {
					continue;
				}
				read = true;
				int inEntry = 0;
				for (String line : new String(entries.readNBytes(MAX_LOG_BYTES), StandardCharsets.UTF_8).split("\n", -1)) {
					String text = line.startsWith(BOM) ? line.substring(BOM.length()) : line;
					Matcher matcher = COUNT_LINE.matcher(text.endsWith("\r") ? text.substring(0, text.length() - 1) : text);
					if (matcher.matches()) {
						found.add((matcher.group(1) == null ? "" : matcher.group(1)) + " " + matcher.group(2));
						inEntry++;
					}
				}
				repeated |= inEntry > 1;
			}
		}
		catch (IOException ex) {
			return Count.missing("its logs are not a zip", true, failed);
		}
		if (!read) {
			return Count.missing("its logs hold no file", true, failed);
		}
		if (found.isEmpty()) {
			return Count.missing("no " + COUNT_PREFIX + " line in the run's logs", true, failed);
		}
		if (repeated || found.size() > 1) {
			return Count.missing("more than one " + COUNT_PREFIX + " line in the run's logs", false, failed);
		}
		String[] line = found.iterator().next().split(" ", 2);
		return new Count(Integer.parseInt(line[1]), line[0].isEmpty() ? null : line[0], null, false, failed);
	}

	// Part c: the Customer, the handovers on the runner, and the Final Merge on the Customer's Actions.

	/** @return whether the run stopped for the Customer to accept the invitation */
	private boolean partC(String organization, String runnerName, Invocation invocation, Inputs inputs,
			OidcExchange exchange, List<Check> checks) {
		String prefix = invocation.prefix();
		String customer = invocation.customer();
		this.out.println("Spike S6 part c on " + organization + ": " + customer + " on " + prefix + "-intermediary, two"
				+ " handovers from " + prefix + "-working on the runner " + organization + "/" + runnerName
				+ ", and the Final Merge back to " + invocation.source());
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
				? check(4, "copy H1 to handover, pull request", () -> handover(4, "copy H1 to handover, pull request", 1,
						prefix, organization, runnerName, invocation.exchangeUrl(), inputs, exchange, state))
				: skipped(4, "copy H1 to handover, pull request");
		record(checks, pull1);
		Check h2 = pull1.outcome() == Outcome.OK
				? check(5, "commit H2, a child of H1", () -> commit(5, "commit H2, a child of H1", 2, prefix, state))
				: skipped(5, "commit H2, a child of H1");
		record(checks, h2);
		Check pull2 = h2.outcome() == Outcome.OK
				? check(6, "copy H2 to handover, pull request", () -> handover(6, "copy H2 to handover, pull request", 2,
						prefix, organization, runnerName, invocation.exchangeUrl(), inputs, exchange, state))
				: skipped(6, "copy H2 to handover, pull request");
		record(checks, pull2);
		Check merged = pull2.outcome() == Outcome.OK ? check(7, "merge pull request 2 at H2", () -> merge(state))
				: skipped(7, "merge pull request 2 at H2");
		record(checks, merged);
		record(checks, merged.outcome() == Outcome.OK ? check(8, "pull request 1 after the merge", () -> firstPull(state))
				: skipped(8, "pull request 1 after the merge"));
		record(checks, merged.outcome() == Outcome.OK && state.mergeCommit != null
				? check(9, "Final Merge on Customer's Actions",
						() -> finalMerge(inputs, exchange, invocation.exchangeUrl(), invocation.source(), prefix, state))
				: skipped(9, "Final Merge on Customer's Actions"));
		record(checks, durations(10, state));
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
			if (!head(state.intermediary, branch).isEmpty()) {
				return new Check(number, name, Outcome.FAIL, 200, branch + " exists on " + state.intermediary.fullName()
						+ ": part c already ran on " + prefix + "; run part b with a new --prefix");
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

	/**
	 * Copies H{@code n} to {@code handover/{prefix}-n} of the Intermediary with the runner's
	 * {@value #COPY_WORKFLOW}, its token narrowed to the Working and Intermediary Repositories, reads the
	 * branch back, and opens its pull request.
	 */
	private Check handover(int number, String name, int handover, String prefix, String organization, String runnerName,
			String exchangeUrl, Inputs inputs, OidcExchange exchange, State state) {
		Actions where = runnerActions(organization, runnerName, inputs, state);
		Repo working = state.working;
		Repo intermediary = state.intermediary;
		String sha = handover == 1 ? state.h1 : state.h2;
		String branch = handoverBranch(prefix, handover);
		String correlation = correlation("handover-" + handover);
		Map<String, String> workflowInputs = new LinkedHashMap<>();
		workflowInputs.put("correlation", correlation);
		workflowInputs.put("exchange_url", exchangeUrl);
		workflowInputs.put("source_repository", working.fullName());
		workflowInputs.put("source_branch", working.defaultBranch());
		workflowInputs.put("sha", sha);
		workflowInputs.put("target_repository", intermediary.fullName());
		workflowInputs.put("target_branch", branch);
		Ran ran = runWorkflow(where, DISPATCH_REF, correlation, workflowInputs,
				new OidcExchange.Grant(Set.of(working.id(), intermediary.id()), COPY_PERMISSIONS), exchange);
		state.timings.add("handover-" + handover, ran);
		if (!ran.ok()) {
			return new Check(number, name, Outcome.FAIL, ran.status(), ran.describe());
		}
		String head = head(intermediary, branch);
		if (!sha.equals(head)) {
			return new Check(number, name, Outcome.FAIL, ran.status(), ran.describe() + ", read back " + intermediary.fullName()
					+ " " + branch + "=" + shortSha(head) + " instead of " + shortSha(sha));
		}
		StringBuilder detail = new StringBuilder(ran.describe()).append(", read back ")
			.append(intermediary.fullName())
			.append(' ')
			.append(branch)
			.append('=')
			.append(shortSha(head));
		Map<String, Object> request = Map.of("title", "Spike S6: handover " + handover, "head", branch, "base",
				intermediary.defaultBranch(), "body",
				"Spike S6, part c: handover " + handover + " of " + working.fullName() + " at " + sha + ".");
		// GitHub documents pull_requests write alone for this, as vibefix's own handover asks. The S6 run of
		// 2026-11-11 had it answer 422 on a private Intermediary, so a 422 is asked again as it was, telling a late
		// branch apart, then with contents read added, telling a token that cannot see the branch apart.
		GitHubHttp.Answer opened = openPull(intermediary, Map.of("pull_requests", "write"), request);
		if (opened.status() == 422) {
			detail.append(", pull_requests write alone: HTTP 422 ").append(message(opened));
			sleep(this.waits.findPoll());
			opened = openPull(intermediary, Map.of("pull_requests", "write"), request);
			detail.append(", again after ").append(this.waits.findPoll().toMillis()).append(" ms: HTTP ").append(opened.status());
			if (opened.status() == 422) {
				detail.append(' ').append(message(opened));
				opened = openPull(intermediary, Map.of("contents", "read", "pull_requests", "write"), request);
				detail.append(", with contents read added: HTTP ").append(opened.status());
			}
		}
		int pull = opened.body().path("number").asInt(0);
		if (handover == 1) {
			state.pull1 = pull;
		}
		else {
			state.pull2 = pull;
		}
		boolean ok = opened.status() == 201 && pull > 0;
		detail.append(ok ? ", pull request #" + pull : ", pull request refused: " + message(opened));
		return new Check(number, name, ok ? Outcome.OK : Outcome.FAIL, opened.status(), detail.toString());
	}

	private GitHubHttp.Answer openPull(Repo intermediary, Map<String, String> permissions, Map<String, Object> request) {
		return withPlatform(Set.of(intermediary.id()), permissions,
				token -> http().send("POST", "/repos/" + intermediary.fullName() + "/pulls", token.value(), request));
	}

	private Check merge(State state) {
		Repo intermediary = state.intermediary;
		return withPlatform(Set.of(intermediary.id()), Map.of("contents", "write", "pull_requests", "write"), token -> {
			String pull = "/repos/" + intermediary.fullName() + "/pulls/" + state.pull2;
			GitHubHttp.Answer merged = http().send("PUT", pull + "/merge", token.value(),
					Map.of("merge_method", "merge", "sha", state.h2, "commit_title", "Spike S6: merge handover 2"));
			GitHubHttp.Answer readBack = http().send("GET", pull, token.value(), null);
			String commit = merged.body().path("sha").asString("");
			boolean ok = merged.status() == 200 && merged.body().path("merged").asBoolean(false)
					&& readBack.body().path("merged").asBoolean(false) && SHA.matcher(commit).matches();
			if (ok) {
				state.mergeCommit = commit;
			}
			return new Check(7, "merge pull request 2 at H2", ok ? Outcome.OK : Outcome.FAIL, merged.status(),
					"#" + state.pull2 + " merge_method=merge sha=" + shortSha(state.h2) + ": merged="
							+ merged.body().path("merged").asString("-") + " commit=" + shortSha(commit)
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

	/**
	 * The Final Merge on the Customer's Actions: the run reads the Intermediary's merge commit with a token
	 * narrowed to the Intermediary (contents read) and pushes it as the new branch
	 * {@code vibefix/final-{prefix}} with its own {@code GITHUB_TOKEN}; the Source App reads it back.
	 */
	private Check finalMerge(Inputs inputs, OidcExchange exchange, String exchangeUrl, String sourceName, String prefix,
			State state) {
		Installed source = source(inputs, sourceName, state);
		String branch = "vibefix/final-" + prefix;
		String existing = sourceHead(source, branch);
		if (!existing.isEmpty()) {
			return new Check(9, "Final Merge on Customer's Actions", Outcome.FAIL, 200, branch + " exists on "
					+ source.repository() + " at " + shortSha(existing) + ": part c already ran on " + prefix);
		}
		Actions where = customerActions(inputs, state);
		String correlation = correlation("final-merge");
		Map<String, String> workflowInputs = new LinkedHashMap<>();
		workflowInputs.put("operation", "final-merge");
		workflowInputs.put("correlation", correlation);
		workflowInputs.put("exchange_url", exchangeUrl);
		workflowInputs.put("sha", state.mergeCommit);
		workflowInputs.put("branch", branch);
		workflowInputs.put("intermediary", state.intermediary.fullName());
		Ran ran = runWorkflow(where, where.defaultBranch(), correlation, workflowInputs,
				new OidcExchange.Grant(Set.of(state.intermediary.id()), FINAL_MERGE_PERMISSIONS), exchange);
		state.timings.add("final-merge", ran);
		if (!ran.ok()) {
			return new Check(9, "Final Merge on Customer's Actions", Outcome.FAIL, ran.status(), ran.describe());
		}
		String head = sourceHead(source, branch);
		boolean ok = state.mergeCommit.equals(head);
		return new Check(9, "Final Merge on Customer's Actions", ok ? Outcome.OK : Outcome.FAIL, ran.status(),
				ran.describe() + ", read back with the Source App " + source.repository() + " " + branch + "=" + shortSha(head)
						+ (ok ? ", the Intermediary's merge commit" : " instead of " + shortSha(state.mergeCommit)));
	}

	// Workflow runs and the exchange.

	private Actions customerActions(Inputs inputs, State state) {
		Installed source = state.source;
		return new Actions(source.repository(), source.repositoryId(), source.defaultBranch(), CUSTOMER_WORKFLOW,
				inputs.customerBlob(), state.sourceBot, false,
				(repositoryIds, permissions) -> mint(source.app(), source.installationId(), repositoryIds, permissions));
	}

	private Actions runnerActions(String organization, String runnerName, Inputs inputs, State state) {
		if (state.runner == null) {
			Repo runner = platformRepository(organization, runnerName);
			if (runner == null) {
				throw new GitHubException(404, "no " + organization + "/" + runnerName + ": run part b first");
			}
			state.runner = runner;
		}
		if (state.platformBot == null) {
			state.platformBot = this.apps.registration(App.PLATFORM).slug() + "[bot]";
		}
		Repo runner = state.runner;
		return new Actions(runner.fullName(), runner.id(), runner.defaultBranch(), COPY_WORKFLOW, inputs.runnerBlob(),
				state.platformBot, true, (repositoryIds, permissions) -> this.apps.mint(App.PLATFORM, repositoryIds, permissions));
	}

	/**
	 * Lets the run exchange its ID token once for a Platform App token narrowed to {@code grant}, dispatches
	 * it with an Actions-write token of the repository's own App, and waits for it. The exchange's
	 * correlation is finished once the run is over (completed or cancelled): the token it issued is revoked
	 * then, and whatever happens, before this returns.
	 */
	private Ran runWorkflow(Actions where, String ref, String correlation, Map<String, String> inputs,
			OidcExchange.Grant grant, OidcExchange exchange) {
		exchange.expect(new OidcExchange.Expected(correlation, where.repository(), where.repositoryId(), where.defaultBranch(),
				where.path(), where.blob(), where.bot(), grant, reads(where),
				() -> this.apps.mint(App.PLATFORM, grant.repositoryIds(), grant.permissions())));
		try {
			int unmatched = exchange.unmatched();
			Ran ran = withRepository(where, Map.of("actions", "write"),
					token -> dispatchAndWait(where, ref, correlation, inputs, exchange, token, true));
			return ran.withUnmatched(exchange.unmatched() - unmatched);
		}
		finally {
			exchange.finish(correlation);
		}
	}

	/** @param printFailure whether a run that did not succeed has its failed step's last lines printed (never a scan's) */
	private Ran dispatchAndWait(Actions where, String ref, String correlation, Map<String, String> inputs,
			OidcExchange exchange, InstallationToken token, boolean printFailure) {
		String actions = "/repos/" + where.repository() + "/actions";
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("ref", ref);
		body.put("inputs", inputs);
		body.put("return_run_details", true);
		String dispatches = actions + "/workflows/" + where.workflow() + "/dispatches";
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
					message(dispatched).isEmpty() ? "HTTP " + dispatched.status() : message(dispatched),
					exchange.finish(correlation));
		}
		if (runId == 0) {
			runId = findByRunName(actions, where.workflow(), correlation, token);
			if (runId == 0) {
				return Ran.refused(dispatched.status(), how,
						"no run named " + correlation + " within " + this.waits.findWait().toSeconds() + " s",
						exchange.finish(correlation));
			}
		}
		GitHubHttp.Answer[] last = { null };
		boolean completed = completed(actions, runId, token, last);
		JsonNode run = last[0] == null ? MissingNode.getInstance() : last[0].body();
		if (!completed) {
			int cancel = http().send("POST", actions + "/runs/" + runId + "/cancel", token.value(), null).status();
			OidcExchange.Result exchanged = exchange.finish(correlation);
			String url = run.path("html_url").asString("https://github.com/" + where.repository() + "/actions/runs/" + runId);
			return new Ran(dispatched.status(), runId, how, run.path("status").asString("running"), false, run,
					"not completed in " + this.waits.runWait().toMinutes() + " min; cancel HTTP " + cancel
							+ "; its logs stay: " + url,
					-1, Billed.UNKNOWN, Ran.KEPT, exchanged, 0);
		}
		// The run is over: the token it was issued is revoked now.
		OidcExchange.Result exchanged = exchange.finish(correlation);
		String conclusion = run.path("conclusion").asString("-");
		JsonNode jobs = http().send("GET", actions + "/runs/" + runId + "/jobs?per_page=100", token.value(), null).body();
		JsonNode timing = http().send("GET", actions + "/runs/" + runId + "/timing", token.value(), null).body();
		long duration = duration(timing, run);
		Billed billed = billed(timing.path("billable"), jobs);
		if (printFailure && !"success".equals(conclusion)) {
			printFailedStep(actions, runId, jobs, token);
		}
		int logsDeleted = where.deletesLogs() ? deleteLogs(actions, runId, token) : Ran.KEPT;
		return new Ran(dispatched.status(), runId, how, conclusion, true, run, null, duration, billed, logsDeleted, exchanged,
				0);
	}

	/** What the exchange reads to check a run: its run and its workflow file's blob, with the repository's own App. */
	private OidcExchange.Reads reads(Actions where) {
		return new OidcExchange.Reads() {

			@Override
			public @Nullable JsonNode run(long runId) {
				GitHubHttp.Answer answer = withRepository(where, Map.of("actions", "read"), token -> http().send("GET",
						"/repos/" + where.repository() + "/actions/runs/" + runId, token.value(), null));
				return answer.status() == 200 ? answer.body() : null;
			}

			@Override
			public @Nullable String blob(String path, String commit) {
				GitHubHttp.Answer answer = withRepository(where, Map.of("contents", "read"), token -> http().send("GET",
						"/repos/" + where.repository() + "/contents/" + path + "?ref=" + commit, token.value(), null));
				JsonNode sha = answer.body().path("sha");
				return answer.status() == 200 && sha.isString() ? sha.stringValue() : null;
			}

		};
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
	 * fetched by hand from the redirect without the Authorization header. GitHub masks what the workflows
	 * mask; anything that still looks like a token or a JWT is cut from the lines as well.
	 */
	private void printFailedStep(String actions, long runId, JsonNode jobs, InstallationToken token) {
		FailedStep failed = failedStep(jobs);
		String job = failed == null ? null : failed.job();
		int step = failed == null ? 0 : failed.number();
		String stepName = failed == null ? "?" : failed.name();
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
			this.err.println("  | " + SECRET.matcher(line).replaceAll("<redacted>"));
		}
	}

	/** The first step of the run's jobs whose conclusion is {@code failure}, or {@code null}. */
	private static @Nullable FailedStep failedStep(JsonNode jobs) {
		for (JsonNode job : jobs.path("jobs")) {
			for (JsonNode step : job.path("steps")) {
				if ("failure".equals(step.path("conclusion").asString(""))) {
					return new FailedStep(job.path("name").asString(""), step.path("number").asInt(0),
							step.path("name").asString("?"));
				}
			}
		}
		return null;
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

	// Installations, tokens, and repositories.

	/** The Source Repository through the Source App: its installation, ID, default branch, head, and the App's bot. */
	private Installed source(Inputs inputs, String name, State state) {
		if (state.source == null || state.sourceHead == null || state.sourceBot == null) {
			ProbeApp app = inputs.sourceApp();
			GitHubHttp.Answer registration = http().send("GET", "/app", jwt(app), null);
			String slug = registration.body().path("slug").asString("");
			if (registration.status() != 200 || slug.isEmpty()) {
				throw new GitHubException(registration.status(), "the Source App's GET /app answered HTTP " + registration.status());
			}
			Installed installed = install(app, name);
			String head = sourceHead(installed, installed.defaultBranch());
			if (!SHA.matcher(head).matches()) {
				throw new GitHubException(0, installed.repository() + "'s default branch has no head");
			}
			state.source = installed;
			state.sourceBranch = installed.defaultBranch();
			state.sourceHead = head;
			state.sourceBot = slug + "[bot]";
		}
		return state.source;
	}

	/** A branch's head on the Source Repository, read with the Source App; empty when there is none. */
	private String sourceHead(Installed source, String branch) {
		GitHubHttp.Answer ref = withApp(source, Set.of(source.repositoryId()), Map.of("contents", "read"),
				token -> http().send("GET", "/repos/" + source.repository() + "/git/ref/heads/" + branch, token.value(), null));
		String sha = ref.body().path("object").path("sha").asString("");
		return ref.status() == 200 && SHA.matcher(sha).matches() ? sha : "";
	}

	/** A branch's head on a platform repository, read with a Platform token narrowed to it; empty when there is none. */
	private String head(Repo repository, String branch) {
		String head = readHead(repository, branch);
		return head == null ? "" : head;
	}

	/**
	 * As {@link #head}, telling a failed read apart: empty for no such branch (404) or an empty repository
	 * (409), {@code null} for any other answer.
	 */
	private @Nullable String readHead(Repo repository, String branch) {
		GitHubHttp.Answer ref = withPlatform(Set.of(repository.id()), Map.of("contents", "read"),
				token -> http().send("GET", "/repos/" + repository.fullName() + "/git/ref/heads/" + branch, token.value(), null));
		String sha = ref.body().path("object").path("sha").asString("");
		if (ref.status() == 200 && SHA.matcher(sha).matches()) {
			return sha;
		}
		return ref.status() == 404 || ref.status() == 409 ? "" : null;
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
				read.body().path("private").asBoolean(false), permissions(installation.body()));
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
				answer.body().path("default_branch").asString(DISPATCH_REF), answer.body().path("private").asBoolean(false));
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
	 * @return GitHub's status
	 */
	private int revoke(InstallationToken token) {
		int status = http().send("DELETE", "/installation/token", token.value(), null).status();
		if (status != 204) {
			this.unrevoked.add(status);
			this.err.println("Spike S6: token not revoked: HTTP " + status);
		}
		return status;
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

	/** A token of the run repository's own App, narrowed to that repository. */
	private <T> T withRepository(Actions where, Map<String, String> permissions, Function<InstallationToken, T> use) {
		return with(() -> where.tokens().mint(Set.of(where.repositoryId()), permissions), use);
	}

	private String jwt(ProbeApp app) {
		return app.key().sign(this.clock.instant());
	}

	private GitHubHttp http() {
		return this.apps.http();
	}

	// The arguments and their files.

	/** @param loopbackAllowed whether {@code --exchange-url} may be plain http on loopback (a stand-in issuer only) */
	private static @Nullable Invocation parse(ApplicationArguments args, boolean loopbackAllowed) {
		for (String optional : List.of("prefix", "exchange-port", "tampered-ref")) {
			if (args.containsOption(optional) && single(args, optional) == null) {
				return null;
			}
		}
		// --scan is a flag of part b, with no value.
		List<String> scanValues = args.getOptionValues("scan");
		boolean scan = scanValues != null;
		if (scan && !scanValues.isEmpty()) {
			return null;
		}
		String part = single(args, "part");
		String source = single(args, "source");
		String customer = single(args, "customer");
		String prefix = single(args, "prefix");
		Long sourceAppId = id(single(args, "source-app-id"));
		Path sourceAppKey = path(single(args, "source-app-key-file"));
		Path customerWorkflow = path(single(args, "customer-workflow"));
		Path workflowsDir = path(single(args, "workflows-dir"));
		String exchangeUrl = exchangeUrl(single(args, "exchange-url"), loopbackAllowed);
		int port = port(single(args, "exchange-port"));
		String tamperedRef = single(args, "tampered-ref");
		if (part == null) {
			return null;
		}
		boolean sourceArguments = repository(source) && sourceAppId != null && sourceAppKey != null && customerWorkflow != null;
		boolean exchangeArguments = workflowsDir != null && exchangeUrl != null && port > 0;
		return switch (part) {
			case "a" -> sourceArguments && matches(LOGIN, customer) && !scan
					? new Invocation(part, source, customer, null, sourceAppId, sourceAppKey, customerWorkflow, null, null, 0, null,
							false)
					: null;
			case "b" -> sourceArguments && exchangeArguments && (prefix == null || matches(NAME, prefix))
					&& (tamperedRef == null || branch(tamperedRef))
							? new Invocation(part, source, null, prefix, sourceAppId, sourceAppKey, customerWorkflow, workflowsDir,
									exchangeUrl, port, tamperedRef, scan)
							: null;
			case "c" -> sourceArguments && exchangeArguments && matches(NAME, prefix) && matches(LOGIN, customer) && !scan
					? new Invocation(part, source, customer, prefix, sourceAppId, sourceAppKey, customerWorkflow, workflowsDir,
							exchangeUrl, port, null, false)
					: null;
			default -> null;
		};
	}

	/** {@code owner/name}, refusing {@code owner/.} and {@code owner/..}, as RepositoryCopyJobHandler does. */
	private static boolean repository(@Nullable String value) {
		return matches(REPOSITORY, value) && !value.endsWith("/.") && !value.endsWith("/..");
	}

	/** A branch name, as the workflows' {@code branch_re} and git allow it. */
	private static boolean branch(String value) {
		return BRANCH.matcher(value).matches() && !value.contains("..") && !value.contains("//") && !value.endsWith("/")
				&& !value.endsWith(".lock");
	}

	/**
	 * The exchange's origin, as the workflows append {@code /exchange} to it: {@code https://host[:port]},
	 * or, when {@code loopbackAllowed}, {@code http} on a loopback host (a stand-in GitHub's runs);
	 * {@code null} for anything else.
	 */
	static @Nullable String exchangeUrl(@Nullable String value, boolean loopbackAllowed) {
		if (value == null) {
			return null;
		}
		URI uri;
		try {
			uri = new URI(value);
		}
		catch (URISyntaxException ex) {
			return null;
		}
		String host = uri.getHost();
		String path = uri.getRawPath();
		if (host == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
				|| (path != null && !path.isEmpty() && !path.equals("/"))) {
			return null;
		}
		boolean https = "https".equals(uri.getScheme()) && HOST.matcher(host).matches();
		boolean loopback = loopbackAllowed && "http".equals(uri.getScheme())
				&& (host.equals("127.0.0.1") || host.equals("localhost"));
		if (!https && !loopback) {
			return null;
		}
		return uri.getScheme() + "://" + host + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
	}

	/** {@code --exchange-port}: {@value OidcExchange#DEFAULT_PORT} when absent, 0 when it is no port. */
	private static int port(@Nullable String value) {
		if (value == null) {
			return OidcExchange.DEFAULT_PORT;
		}
		if (!PORT.matcher(value).matches() || Integer.parseInt(value) > 65535) {
			return 0;
		}
		return Integer.parseInt(value);
	}

	private void usage() {
		String run = "--spring.profiles.active=dev,cli --vibefix.cli.command=" + COMMAND;
		String source = " --source=<owner/name> --source-app-id=<id> --source-app-key-file=<path>"
				+ " --customer-workflow=<deploy/customer/vibefix.yml>";
		String exchange = " --workflows-dir=<deploy/runner> --exchange-url=<https://…> [--exchange-port=" + OidcExchange.DEFAULT_PORT
				+ "]";
		this.err.println("Usage: " + run + " --part=a --customer=<login>" + source);
		this.err.println("   or: " + run + " --part=b" + source + exchange + " [--prefix=<name>] [--tampered-ref=<branch>]"
				+ " [--scan]");
		this.err.println("   or: " + run + " --part=c --prefix=<part b's prefix> --customer=<login>" + source + exchange);
		this.err.println("   (parts b and c use the Platform App, vibefix.github.platform-app.id, installation-id, key-file,"
				+ " and the runner, vibefix.github.runner-repository; a prefix is letters, digits, and hyphens, starting"
				+ " with a letter or a digit; --scan, part b only and with no value, adds check 12, the gitleaks scan on"
				+ " the Customer's Actions)");
	}

	/** @throws IllegalArgumentException for a file that cannot be read or holds no usable key; never quoting it */
	private static Inputs read(Invocation invocation) {
		ProbeApp sourceApp = app("Source App", invocation.sourceAppId(), invocation.sourceAppKey());
		String customer = invocation.customerWorkflow() == null ? null
				: workflow(invocation.customerWorkflow(), "--customer-workflow cannot be read");
		String runner = null;
		if (invocation.workflowsDir() != null) {
			Path file;
			try {
				file = invocation.workflowsDir().resolve(COPY_WORKFLOW);
			}
			catch (InvalidPathException ex) {
				throw new IllegalArgumentException("--workflows-dir holds no readable " + COPY_WORKFLOW);
			}
			runner = workflow(file, "--workflows-dir holds no readable " + COPY_WORKFLOW);
		}
		return new Inputs(sourceApp, customer, customer == null ? null : OidcExchange.blobSha(customer), runner,
				runner == null ? null : OidcExchange.blobSha(runner));
	}

	/** A published workflow, its CRLF line ends read as LF, as GitHub stores it. */
	private static String workflow(Path file, String refusal) {
		try {
			String content = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
			if (content.isBlank()) {
				throw new IllegalArgumentException(refusal);
			}
			return content;
		}
		catch (IOException | RuntimeException ex) {
			throw new IllegalArgumentException(refusal);
		}
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

	/** GitHub's message, with what its {@code errors} name: a 422 says little without them. */
	private static String message(GitHubHttp.Answer answer) {
		String message = answer.body().path("message").asString("");
		List<String> errors = new ArrayList<>();
		for (JsonNode error : answer.body().path("errors")) {
			if (errors.size() == 5) {
				break;
			}
			String text = error.isObject() ? error.path("message").asString("") : error.asString("");
			if (text.isEmpty() && error.isObject()) {
				text = (error.path("resource").asString("") + "." + error.path("field").asString("") + " "
						+ error.path("code").asString("")).strip();
			}
			if (!text.isEmpty()) {
				errors.add(text.length() > 200 ? text.substring(0, 200) : text);
			}
		}
		return errors.isEmpty() ? message : message + " " + errors;
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
