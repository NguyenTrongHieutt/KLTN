package vn.vibefix.github.internal;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import vn.vibefix.github.ActionsRuns;
import vn.vibefix.github.GitHubApps.App;
import vn.vibefix.github.GitHubApps.InstallationToken;
import vn.vibefix.github.GitHubException;

/**
 * {@link ActionsRuns}, promoted from Spike S6's dispatch and run lookup (AD-44, AD-46). Every call to GitHub uses a
 * token of the dispatching App ({@link DispatchingApps}) narrowed to the target: Metadata and Contents read for the
 * target and its workflow file; for the dispatch, Actions write, with Contents read for the Source App (Story 23.6a);
 * for a run lookup or read, Actions read, with Contents read for the Source App. The Source App's tokens come from the
 * Customer's installation the dispatch names, which the correlation records. The run is dispatched with
 * {@code return_run_details}; when GitHub names no run, it is found by its title, which holds the correlation, among
 * the workflow's runs dispatched since the correlation was recorded and triggered by the App's bot, for up to
 * {@link ActionsRuns#FIND_WINDOW}. Once GitHub accepted the dispatch, a lookup that fails leaves the run to
 * {@link #run}: the dispatch does not fail, since a retry would start a second run. A run is read by its first attempt,
 * so a Customer's re-run changes nothing vibefix reads, and once its conclusion is recorded it is answered from the
 * correlation. The correlation's writes after the dispatch are best-effort. On the {@code dev} profile the dispatch
 * also names {@code exchange_url}, the cloudflared quick tunnel ({@code vibefix.github.exchange.public-url}); no other
 * environment knows an exchange URL yet (Story 13.1 owns the production host), so there nothing is dispatched.
 */
@Component
class GitHubActionsRuns implements ActionsRuns {

	private static final Logger log = LoggerFactory.getLogger(GitHubActionsRuns.class);

	/** How long a correlation stays open: the find window, the run's timeout, and the queue before it. */
	static final Duration CORRELATION_LIFETIME = FIND_WINDOW.plus(RUN_TIMEOUT).plusMinutes(3);

	/** How much earlier than the correlation a run is looked for, for the clocks of vibefix and GitHub to differ. */
	static final Duration CLOCK_SKEW = Duration.ofMinutes(1);

	/** How many pages of 100 runs a month's minutes are summed over: more runs than the runner can ever bill. */
	static final int MINUTES_PAGES = 30;

	/** The target's read, and its workflow file's. */
	private static final Map<String, String> TARGET_READ = Map.of("metadata", "read", "contents", "read");

	/** A run's page as GitHub links it: an absolute http(s) URL without spaces, which the table keeps. */
	private static final Pattern HTML_URL = Pattern.compile("https?://[^\\s\\p{Cntrl}]{1,2040}");

	/** A conclusion as GitHub names it, for example {@code success} or {@code timed_out}. */
	private static final Pattern CONCLUSION = Pattern.compile("[a-z_]{1,40}");

	/** How the dispatch waits for its run to show: each look, and how long in all. */
	record Waits(Duration findPoll, Duration findWindow) {

		static final Waits GITHUB = new Waits(Duration.ofSeconds(5), FIND_WINDOW);

	}

	private final GitHubAppsClient apps;

	private final DispatchingApps dispatching;

	private final ExchangeCorrelations correlations;

	private final ActionsReports reports;

	private final AcceptedWorkflows accepted;

	private final TransactionTemplate transactions;

	private final Environment environment;

	private final Clock clock;

	private final Waits waits;

	@Autowired
	GitHubActionsRuns(GitHubAppsClient apps, DispatchingApps dispatching, ExchangeCorrelations correlations,
			ActionsReports reports, PlatformTransactionManager transactionManager, Environment environment, Clock clock) {
		this(apps, dispatching, correlations, reports, transactionManager, environment, clock, Waits.GITHUB);
	}

	GitHubActionsRuns(GitHubAppsClient apps, DispatchingApps dispatching, ExchangeCorrelations correlations,
			ActionsReports reports, PlatformTransactionManager transactionManager, Environment environment, Clock clock,
			Waits waits) {
		this.apps = apps;
		this.dispatching = dispatching;
		this.correlations = correlations;
		this.reports = reports;
		this.accepted = new AcceptedWorkflows(apps.properties(), clock);
		this.transactions = new TransactionTemplate(transactionManager);
		this.environment = environment;
		this.clock = clock;
		this.waits = waits;
	}

	@Override
	public Dispatched dispatch(Dispatch dispatch) {
		String exchangeUrl = exchangeUrl();
		App app = dispatch.app();
		Long installation = dispatch.sourceInstallationId();
		Target target = this.dispatching.withToken(app, installation, dispatch.targetId(), TARGET_READ,
				token -> target(dispatch, token));
		UUID id = UUID.randomUUID();
		Instant now = this.clock.instant();
		// Committed before the dispatch, so the run's exchange finds it.
		this.transactions.executeWithoutResult(status -> this.correlations.insert(new ExchangeCorrelations.Row(id,
				dispatch.operation(), target.fullName(), dispatch.targetId(), target.defaultBranch(), dispatch.workflowPath(),
				app.name(), installation, dispatch.grant(), dispatch.operation().permissions(), dispatch.confirmedHeadSha(),
				Status.OPEN, null, null, null, null, null, now, now.plus(CORRELATION_LIFETIME), null, null)));
		Map<String, String> inputs = new LinkedHashMap<>(dispatch.inputs());
		inputs.put("correlation", id.toString());
		inputs.put("exchange_url", exchangeUrl);
		Found found;
		if (app == App.SOURCE) {
			// The Source App's dispatch token writes Actions and reads Contents; its lookup reads both, with a token of its own.
			Found answered = this.dispatching.withToken(app, installation, dispatch.targetId(), DispatchingApps.SOURCE_DISPATCH,
					token -> dispatched(target, dispatch.workflow(), inputs, token));
			found = answered.runId() > 0 ? answered
					: afterDispatch(id, () -> this.dispatching.withToken(app, installation, dispatch.targetId(),
							DispatchingApps.SOURCE_RUN_READ, token -> findByTitle(target.fullName(), dispatch.workflow(), id, now,
									this.dispatching.botLogin(app), token)));
		}
		else {
			found = this.dispatching.withToken(app, installation, dispatch.targetId(), Map.of("actions", "write"), token -> {
				Found answered = dispatched(target, dispatch.workflow(), inputs, token);
				return answered.runId() > 0 ? answered
						: afterDispatch(id, () -> findByTitle(target.fullName(), dispatch.workflow(), id, now,
								this.dispatching.botLogin(app), token));
			});
		}
		long runId = found.runId();
		if (runId > 0) {
			recordQuietly(id, "run", () -> this.correlations.runFound(id, runId, found.htmlUrl()));
		}
		log.info("Dispatched {} on {} through the {} App under correlation {}: run {}", dispatch.workflow(), target.fullName(),
				app, id, runId > 0 ? runId : "not found yet");
		return new Dispatched(id, runId);
	}

	@Override
	public Optional<Correlation> correlation(UUID id) {
		return this.correlations.find(id).map(ExchangeCorrelations.Row::correlation);
	}

	@Override
	public Optional<Run> run(UUID correlation) {
		ExchangeCorrelations.Row row = this.correlations.find(correlation).orElse(null);
		if (row == null) {
			return Optional.empty();
		}
		Long known = row.runId();
		if (known != null && row.runConclusion() != null) {
			// The first attempt's conclusion never changes: GitHub is not asked again.
			return Optional.of(new Run(known, row.runHtmlUrl(), RunStatus.COMPLETED, row.runConclusion(), row.runMarker()));
		}
		App app = row.dispatchingApp();
		Read read = this.dispatching.withToken(row, DispatchingApps.runRead(app), token -> {
			if (known != null) {
				return new Read(null, read(row.repository(), known, row.runHtmlUrl(), token));
			}
			Found lookedUp = titled(row.repository(), workflowFile(row.workflowPath()), row.id(), row.createdAt(),
					this.dispatching.botLogin(app), token);
			return lookedUp.runId() == 0 ? Read.NONE
					: new Read(lookedUp, read(row.repository(), lookedUp.runId(), lookedUp.htmlUrl(), token));
		});
		Found found = read.found();
		if (found != null) {
			recordQuietly(row.id(), "run", () -> this.correlations.runFound(row.id(), found.runId(), found.htmlUrl()));
		}
		Run run = read.run();
		if (run == null) {
			return Optional.empty();
		}
		recordQuietly(row.id(), "run read",
				() -> this.correlations.runRead(row.id(), run.id(), run.htmlUrl(), run.conclusion(), run.marker()));
		return Optional.of(run);
	}

	@Override
	public Optional<Report> report(UUID correlation) {
		return this.reports.find(correlation);
	}

	@Override
	public void close(UUID correlation) {
		ExchangeCorrelations.Row row = this.correlations.find(correlation).orElse(null);
		if (row == null) {
			return;
		}
		Boolean closed = this.transactions.execute(status -> this.correlations.close(correlation));
		Long runId = row.runId();
		log.info("Correlation {} given up: {}; its run {} is cancelled", correlation,
				Boolean.TRUE.equals(closed) ? "closed" : "no longer open (" + row.status() + ")", runId == null ? "(not found)" : runId);
		if (runId == null) {
			return;
		}
		App app = row.dispatchingApp();
		Map<String, String> cancel = app == App.SOURCE ? DispatchingApps.SOURCE_DISPATCH : Map.of("actions", "write");
		try {
			int status = this.dispatching.withToken(row, cancel, token -> this.apps.http()
				.send("POST", "/repos/" + row.repository() + "/actions/runs/" + runId + "/cancel", token.value(), null)
				.status());
			if (status >= 300 && status != 409) {
				// 409: the run already completed. Anything else leaves it to its own timeout.
				log.warn("GitHub refused to cancel run {} of correlation {}: HTTP {}", runId, correlation, status);
			}
		}
		catch (GitHubException failed) {
			log.warn("Run {} of correlation {} was not cancelled: HTTP {}", runId, correlation, failed.status());
		}
	}

	/**
	 * What a repository's runs billed since a moment (AD-46, R30, Story 23.8b).
	 * @param runs how many runs were listed
	 * @param minutes their billed minutes: each listed run's own duration, rounded up to the minute.
	 * {@code [ASSUMPTION; Story 23.12 confirms what GitHub reports]} A run's duration is its
	 * {@code run_duration_ms} where the listing carries one, else its {@code run_started_at} to
	 * {@code updated_at}, the fallback {@code SpikeS6Command} uses: GitHub documents
	 * {@code run_duration_ms} on {@code /runs/{id}/timing}, not on the listing, and one {@code /timing} request
	 * per run would be a month's worth of calls a day. GitHub bills each <em>job</em> rounded up, not each run,
	 * so a run of several jobs bills more than this sums; the alert then fires at the share or a little before it,
	 * never after GitHub's own count has passed it
	 */
	record Minutes(int runs, long minutes) {
	}

	/**
	 * Every run of {@code repository} created at or after {@code since}, with its billed minutes summed: the
	 * runner's monthly Actions minutes (AD-46). It reads with a Platform App token narrowed to that one repository,
	 * Actions read, revoked after use, and follows GitHub's pages up to {@value #MINUTES_PAGES}.
	 * @param repositoryId the repository's numeric ID, which the token is narrowed to
	 * @throws GitHubException when GitHub refuses a page or cannot be reached
	 */
	Minutes minutesSince(long repositoryId, String repository, Instant since) {
		String created = URLEncoder.encode(">=" + DateTimeFormatter.ISO_INSTANT.format(since.truncatedTo(ChronoUnit.SECONDS)),
				StandardCharsets.UTF_8);
		return this.dispatching.withToken(App.PLATFORM, null, repositoryId, Map.of("actions", "read"), token -> {
			int runs = 0;
			long minutes = 0;
			boolean more = false;
			for (int page = 1; page <= MINUTES_PAGES; page++) {
				GitHubHttp.Answer answer = this.apps.http()
					.send("GET", "/repos/" + repository + "/actions/runs?created=" + created + "&per_page=100&page=" + page,
							token.value(), null);
				if (!answer.ok()) {
					throw new GitHubException(answer.status(),
							"GitHub refused to list the runs of " + repository + ": HTTP " + answer.status());
				}
				JsonNode listed = answer.body().path("workflow_runs");
				if (!listed.isArray() || listed.isEmpty()) {
					more = false;
					break;
				}
				for (JsonNode run : listed) {
					runs++;
					minutes += Math.ceilDiv(durationMillis(run), 60_000L);
				}
				more = listed.size() >= 100;
				if (!more) {
					break;
				}
			}
			if (more) {
				// The cap was reached with a full page: the month holds more runs than this read, so the sum is a floor.
				log.warn("The minutes of {} are summed over the first {} pages of 100 runs, which were all full: the"
						+ " month has more runs, so {} minutes is a floor", repository, MINUTES_PAGES, minutes);
			}
			return new Minutes(runs, minutes);
		});
	}

	/**
	 * How long one listed run took, in milliseconds: its {@code run_duration_ms} where GitHub gives one, else its
	 * {@code run_started_at} to {@code updated_at} ({@code SpikeS6Command}'s own fallback); 0 when neither reads.
	 */
	private static long durationMillis(JsonNode run) {
		JsonNode reported = run.path("run_duration_ms");
		if (reported.isIntegralNumber()) {
			return Math.max(0, reported.longValue());
		}
		Instant started = instant(run.path("run_started_at"));
		Instant updated = instant(run.path("updated_at"));
		return started == null || updated == null || updated.isBefore(started) ? 0
				: Duration.between(started, updated).toMillis();
	}

	/** An ISO instant a run shows, or none for anything else. */
	private static @Nullable Instant instant(JsonNode node) {
		if (!node.isString()) {
			return null;
		}
		try {
			return Instant.parse(node.stringValue());
		}
		catch (DateTimeParseException unreadable) {
			return null;
		}
	}

	/** What a run read found: the run looked up by its title, when it was not known yet, and the run read. */
	private record Read(@Nullable Found found, @Nullable Run run) {

		static final Read NONE = new Read(null, null);

	}

	/**
	 * The run's first attempt as GitHub shows it now ({@code /actions/runs/{id}/attempts/1}), with the quota marker read
	 * from that attempt's jobs for a failure. Jobs that cannot be read leave the run unmarked.
	 * @param knownUrl the page recorded when the run was found, kept before the attempt's own
	 * @throws GitHubException when GitHub refuses the attempt or cannot be reached
	 */
	private Run read(String repository, long runId, @Nullable String knownUrl, InstallationToken token) {
		String attempt = "/repos/" + repository + "/actions/runs/" + runId + "/attempts/1";
		GitHubHttp.Answer answer = this.apps.http().send("GET", attempt, token.value(), null);
		if (!answer.ok()) {
			throw new GitHubException(answer.status(), "GitHub refused to read run " + runId + " of " + repository + ": HTTP "
					+ answer.status());
		}
		JsonNode run = answer.body();
		JsonNode shown = run.path("status");
		RunStatus status = RunStatus.of(shown.isString() ? shown.asString() : null);
		String conclusion = status == RunStatus.COMPLETED ? conclusion(run) : null;
		RunMarker marker = null;
		if ("failure".equals(conclusion)) {
			GitHubHttp.Answer jobs = this.apps.http().send("GET", attempt + "/jobs?per_page=100", token.value(), null);
			if (jobs.ok()) {
				marker = noJobStarted(jobs.body().path("jobs")) ? RunMarker.ACTIONS_QUOTA : null;
			}
			else {
				log.warn("The jobs of run {} of {} were not read: HTTP {}; the failure is not marked", runId, repository,
						jobs.status());
			}
		}
		String htmlUrl = knownUrl != null ? knownUrl : htmlUrl(run);
		return new Run(runId, htmlUrl, status, conclusion, marker);
	}

	/** Whether the jobs listed are some, and none of them started: no steps, and no runner. */
	private static boolean noJobStarted(JsonNode jobs) {
		if (!jobs.isArray() || jobs.isEmpty()) {
			return false;
		}
		for (JsonNode job : jobs) {
			boolean steps = job.path("steps").isArray() && !job.path("steps").isEmpty();
			boolean runner = job.path("runner_id").asLong(0) > 0 || !text(job.path("runner_name")).isBlank();
			if (steps || runner) {
				return false;
			}
		}
		return true;
	}

	/** The target as the dispatching App reads it, with its workflow file accepted on the default branch. */
	private record Target(String fullName, String defaultBranch) {
	}

	/** A run GitHub named, or found by its title: its ID (0 for none) and its page. */
	private record Found(long runId, @Nullable String htmlUrl) {

		static final Found NONE = new Found(0, null);

	}

	private Target target(Dispatch dispatch, InstallationToken token) {
		GitHubHttp.Answer repository = this.apps.http().send("GET", "/repositories/" + dispatch.targetId(), token.value(), null);
		if (!repository.ok()) {
			throw new GitHubException(repository.status(), "GitHub refused to read repository " + dispatch.targetId()
					+ ": HTTP " + repository.status());
		}
		String fullName = repository.body().path("full_name").asString("");
		String branch = repository.body().path("default_branch").asString("");
		if (fullName.isEmpty() || branch.isEmpty()) {
			throw new GitHubException(repository.status(), "Repository " + dispatch.targetId() + " shows no name or branch");
		}
		GitHubHttp.Answer file = this.apps.http()
			.send("GET", "/repos/" + fullName + "/contents/" + dispatch.workflowPath() + "?ref="
					+ URLEncoder.encode(branch, StandardCharsets.UTF_8), token.value(), null);
		AcceptedWorkflows.Checked checked = this.accepted.check(dispatch.workflowPath(), file);
		switch (checked.verdict()) {
			case ACCEPTED -> {
			}
			case MISSING -> throw new WorkflowRefused(WorkflowRefusal.WORKFLOW_MISSING,
					fullName + " holds no " + dispatch.workflowPath() + " on " + branch + " (HTTP " + file.status() + ")");
			case MISMATCH -> throw new WorkflowRefused(WorkflowRefusal.WORKFLOW_MISMATCH, fullName + "'s "
					+ dispatch.workflowPath() + " on " + branch + " is blob " + checked.blob() + ", which is not accepted");
		}
		return new Target(fullName, branch);
	}

	/** Dispatches the run: GitHub's answer names it, or {@link Found#NONE} when GitHub answers 204. */
	private Found dispatched(Target target, String workflow, Map<String, String> inputs, InstallationToken token) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("ref", target.defaultBranch());
		body.put("inputs", inputs);
		body.put("return_run_details", true);
		String path = "/repos/" + target.fullName() + "/actions/workflows/" + workflow + "/dispatches";
		GitHubHttp.Answer dispatched = this.apps.http().send("POST", path, token.value(), body);
		if (dispatched.status() == 422 && dispatched.body().path("message").asString("").contains("return_run_details")) {
			body.remove("return_run_details");
			dispatched = this.apps.http().send("POST", path, token.value(), body);
		}
		if (!dispatched.ok()) {
			throw new GitHubException(dispatched.status(), "GitHub refused to dispatch " + workflow + " on "
					+ target.fullName() + ": HTTP " + dispatched.status() + " " + dispatched.body().path("message").asString(""));
		}
		long runId = runId(dispatched.body());
		return runId > 0 ? new Found(runId, htmlUrl(dispatched.body())) : Found.NONE;
	}

	/**
	 * The lookup after a dispatch GitHub accepted: the run is live and its correlation open, so a lookup that fails
	 * (its token, or GitHub's answer) leaves the run for {@link #run} to find, rather than failing the dispatch, which a
	 * caller's retry would turn into a second run.
	 */
	private static Found afterDispatch(UUID id, Supplier<Found> lookup) {
		try {
			return lookup.get();
		}
		catch (GitHubException ex) {
			log.warn("The run dispatched under correlation {} was not looked up: HTTP {}; a run read finds it later", id,
					ex.status());
			return Found.NONE;
		}
	}

	/** The run ID a 200 from the dispatch carries. */
	private static long runId(JsonNode body) {
		for (String field : List.of("workflow_run_id", "run_id", "id")) {
			JsonNode value = body.path(field);
			if (value.isIntegralNumber() && value.longValue() > 0) {
				return value.longValue();
			}
		}
		JsonNode nested = body.path("workflow_run").path("id");
		return nested.isIntegralNumber() ? Math.max(0, nested.longValue()) : 0;
	}

	/** The run {@link #titled} finds, looked for every {@code findPoll} for {@code findWindow}. */
	private Found findByTitle(String repository, String workflow, UUID id, Instant since, String bot,
			InstallationToken token) {
		long waited = 0;
		while (true) {
			Found found = titled(repository, workflow, id, since, bot, token);
			if (found.runId() > 0 || waited >= this.waits.findWindow().toMillis()) {
				return found;
			}
			sleep(this.waits.findPoll());
			waited += Math.max(1, this.waits.findPoll().toMillis());
		}
	}

	/**
	 * One look at the workflow's runs dispatched since the correlation was recorded ({@code since}, less
	 * {@link #CLOCK_SKEW}), up to 100, for the one whose title holds the correlation and whose triggering actor is the
	 * dispatching App's bot: a run someone else dispatched with the correlation copied into its title is not it.
	 * @throws GitHubException when GitHub refuses the list or cannot be reached
	 */
	private Found titled(String repository, String workflow, UUID id, Instant since, String bot, InstallationToken token) {
		String created = URLEncoder.encode(">=" + DateTimeFormatter.ISO_INSTANT.format(since.minus(CLOCK_SKEW)
			.truncatedTo(ChronoUnit.SECONDS)), StandardCharsets.UTF_8);
		GitHubHttp.Answer runs = this.apps.http()
			.send("GET", "/repos/" + repository + "/actions/workflows/" + workflow + "/runs?event=workflow_dispatch&created="
					+ created + "&per_page=100", token.value(), null);
		if (!runs.ok()) {
			throw new GitHubException(runs.status(), "GitHub refused to list the runs of " + workflow + " on " + repository
					+ ": HTTP " + runs.status());
		}
		for (JsonNode run : runs.body().path("workflow_runs")) {
			if (run.path("display_title").asString(run.path("name").asString("")).contains(id.toString())
					&& bot.equalsIgnoreCase(text(run.path("triggering_actor").path("login")))) {
				return new Found(run.path("id").asLong(), htmlUrl(run));
			}
		}
		return Found.NONE;
	}

	/** The workflow's file name, from its path in {@code .github/workflows/}. */
	private static String workflowFile(String workflowPath) {
		return workflowPath.substring(workflowPath.lastIndexOf('/') + 1);
	}

	/** A write to the correlation after the dispatch, best-effort: what GitHub said is answered anyway. */
	private void recordQuietly(UUID id, String what, Runnable write) {
		try {
			this.transactions.executeWithoutResult(status -> write.run());
		}
		catch (RuntimeException failed) {
			log.warn("The {} of correlation {} was not recorded: {}", what, id, failed.getClass().getSimpleName());
		}
	}

	/** The {@code html_url} GitHub showed, when it is one the table keeps; none otherwise. */
	private static @Nullable String htmlUrl(JsonNode body) {
		String url = text(body.path("html_url"));
		return HTML_URL.matcher(url).matches() ? url : null;
	}

	/** The run's {@code conclusion}, when GitHub named one the table keeps; none otherwise. */
	private static @Nullable String conclusion(JsonNode run) {
		String conclusion = text(run.path("conclusion"));
		return CONCLUSION.matcher(conclusion).matches() ? conclusion : null;
	}

	/** A string node's text; empty for anything else, {@code null} included. */
	private static String text(JsonNode node) {
		return node.isString() ? node.asString() : "";
	}

	/**
	 * {@code vibefix.github.exchange.public-url} on the dev profile.
	 * @throws GitHubException elsewhere, or when it is not set: no run could reach the exchange
	 */
	private String exchangeUrl() {
		String url = this.apps.properties().exchange().publicUrl();
		if (this.environment.acceptsProfiles(Profiles.of("dev")) && url != null) {
			return url;
		}
		throw new GitHubException(0, "No exchange URL is known: on the dev stack set vibefix.github.exchange.public-url"
				+ " to the cloudflared quick tunnel; the production host comes with Story 13.1");
	}

	private static void sleep(Duration duration) {
		try {
			Thread.sleep(duration.toMillis());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new GitHubException(0, "interrupted while looking for the dispatched run");
		}
	}

}
