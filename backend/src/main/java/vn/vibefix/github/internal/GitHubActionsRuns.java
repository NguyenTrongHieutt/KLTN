package vn.vibefix.github.internal;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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
 * {@link ActionsRuns} with the Platform App, promoted from Spike S6's dispatch and run lookup (AD-44, AD-46).
 * Every call to GitHub uses a token of the dispatching App narrowed to the target: Metadata and Contents read
 * for the target and its workflow file, Actions write for the dispatch. The run is dispatched with
 * {@code return_run_details}; when GitHub names no run, it is found by its title, which holds the correlation,
 * for up to {@link ActionsRuns#FIND_WINDOW}. On the {@code dev} profile the dispatch also names
 * {@code exchange_url}, the cloudflared quick tunnel ({@code vibefix.github.exchange.public-url}); no other
 * environment knows an exchange URL yet (Story 13.1 owns the production host), so there nothing is dispatched.
 */
@Component
class GitHubActionsRuns implements ActionsRuns {

	private static final Logger log = LoggerFactory.getLogger(GitHubActionsRuns.class);

	/** How long a correlation stays open: the find window, the run's timeout, and the queue before it. */
	static final Duration CORRELATION_LIFETIME = FIND_WINDOW.plus(RUN_TIMEOUT).plusMinutes(3);

	/** How the dispatch waits for its run to show: each look, and how long in all. */
	record Waits(Duration findPoll, Duration findWindow) {

		static final Waits GITHUB = new Waits(Duration.ofSeconds(5), FIND_WINDOW);

	}

	private final GitHubAppsClient apps;

	private final ExchangeCorrelations correlations;

	private final AcceptedWorkflows accepted;

	private final TransactionTemplate transactions;

	private final Environment environment;

	private final Clock clock;

	private final Waits waits;

	@Autowired
	GitHubActionsRuns(GitHubAppsClient apps, ExchangeCorrelations correlations, PlatformTransactionManager transactionManager,
			Environment environment, Clock clock) {
		this(apps, correlations, transactionManager, environment, clock, Waits.GITHUB);
	}

	GitHubActionsRuns(GitHubAppsClient apps, ExchangeCorrelations correlations, PlatformTransactionManager transactionManager,
			Environment environment, Clock clock, Waits waits) {
		this.apps = apps;
		this.correlations = correlations;
		this.accepted = new AcceptedWorkflows(apps.properties(), clock);
		this.transactions = new TransactionTemplate(transactionManager);
		this.environment = environment;
		this.clock = clock;
		this.waits = waits;
	}

	@Override
	public Dispatched dispatch(Dispatch dispatch) {
		String exchangeUrl = exchangeUrl();
		App app = App.PLATFORM;
		Target target = this.apps.withToken(app, List.of(dispatch.targetId()), Map.of("metadata", "read", "contents", "read"),
				token -> target(dispatch, token));
		UUID id = UUID.randomUUID();
		Instant now = this.clock.instant();
		// Committed before the dispatch, so the run's exchange finds it.
		this.transactions.executeWithoutResult(status -> this.correlations.insert(new ExchangeCorrelations.Row(id,
				dispatch.operation(), target.fullName(), dispatch.targetId(), target.defaultBranch(), dispatch.workflowPath(),
				app.name(), dispatch.grant(), dispatch.operation().permissions(), dispatch.confirmedHeadSha(),
				Status.OPEN, null, null, now, now.plus(CORRELATION_LIFETIME), null, null)));
		Map<String, String> inputs = new LinkedHashMap<>(dispatch.inputs());
		inputs.put("correlation", id.toString());
		inputs.put("exchange_url", exchangeUrl);
		long runId = this.apps.withToken(app, List.of(dispatch.targetId()), Map.of("actions", "write"),
				token -> dispatchAndFind(target, dispatch.workflow(), id, inputs, token));
		if (runId > 0) {
			this.transactions.executeWithoutResult(status -> this.correlations.runFound(id, runId));
		}
		log.info("Dispatched {} on {} under correlation {}: run {}", dispatch.workflow(), target.fullName(), id,
				runId > 0 ? runId : "not found yet");
		return new Dispatched(id, runId);
	}

	@Override
	public Optional<Correlation> correlation(UUID id) {
		return this.correlations.find(id).map(ExchangeCorrelations.Row::correlation);
	}

	/** The target as the dispatching App reads it, with its workflow file accepted on the default branch. */
	private record Target(String fullName, String defaultBranch) {
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

	/** The run's ID: GitHub's answer to the dispatch, else the run whose title holds the correlation; 0 for none. */
	private long dispatchAndFind(Target target, String workflow, UUID id, Map<String, String> inputs,
			InstallationToken token) {
		String actions = "/repos/" + target.fullName() + "/actions";
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("ref", target.defaultBranch());
		body.put("inputs", inputs);
		body.put("return_run_details", true);
		String path = actions + "/workflows/" + workflow + "/dispatches";
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
		return runId > 0 ? runId : findByTitle(actions, workflow, id, token);
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

	/** The run whose title holds the correlation, looked for every {@code findPoll} for {@code findWindow}. */
	private long findByTitle(String actions, String workflow, UUID id, InstallationToken token) {
		long waited = 0;
		while (true) {
			GitHubHttp.Answer runs = this.apps.http()
				.send("GET", actions + "/workflows/" + workflow + "/runs?event=workflow_dispatch&per_page=30", token.value(),
						null);
			for (JsonNode run : runs.body().path("workflow_runs")) {
				if (run.path("display_title").asString(run.path("name").asString("")).contains(id.toString())) {
					return run.path("id").asLong();
				}
			}
			if (waited >= this.waits.findWindow().toMillis()) {
				return 0;
			}
			sleep(this.waits.findPoll());
			waited += Math.max(1, this.waits.findPoll().toMillis());
		}
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
