package vn.vibefix.github.internal;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import vn.vibefix.github.ActionsRunUpdated;
import vn.vibefix.github.ActionsRuns.Status;
import vn.vibefix.github.GitHubApps.App;
import vn.vibefix.github.internal.GitHubProperties.AppProperties;
import vn.vibefix.shared.IdGenerator;

/**
 * {@link ActionsRunUpdated}, published in the caller's transaction (Story 24.5a), and only here. A webhook delivery
 * ({@link WebhookController}) becomes one only for a {@code workflow_run} whose action is {@code completed} and which
 * matches a correlation whose run may have run, open or exchanged (its token since revoked included), in all of these:
 * <ul>
 * <li>the App that sent it is the one that dispatched the run;</li>
 * <li>its installation is the correlation's: the Customer's Source App installation the correlation records, or the
 * Platform App's own;</li>
 * <li>its run is the correlation's: the run the correlation recorded, or, while none is recorded, the one whose title
 * holds the correlation, as every published workflow's {@code run-name} writes it.</li>
 * </ul>
 * Anything else publishes nothing; a refused or expired correlation's run is never the one an operation waits on. An
 * accepted path report ({@link ActionsReports}) and the exchange's refusal of a run's ID token ({@link ActionsExchange})
 * publish one too, through {@link #updated}, since either decides what the waiting operation does next. Each one is
 * also recorded on the correlation, so an operation that names the correlation only after the event was handled still
 * sees it ({@link vn.vibefix.github.ActionsRuns#updated}).
 */
@Component
class ActionsRunUpdates {

	private static final Logger log = LoggerFactory.getLogger(ActionsRunUpdates.class);

	/** The event and action a completed run is delivered with. */
	static final String EVENT = "workflow_run";

	static final String COMPLETED = "completed";

	/** The correlations whose run may have run: an exchanged one stays so once its token is revoked (or lost). */
	private static final Set<Status> LIVE = Set.of(Status.OPEN, Status.EXCHANGED, Status.REVOKED, Status.REVOKE_UNKNOWN);

	/** A correlation as a run's title holds it. */
	private static final Pattern CORRELATION = Pattern
		.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

	private final ExchangeCorrelations correlations;

	private final GitHubProperties properties;

	private final ApplicationEventPublisher events;

	private final IdGenerator ids;

	private final Clock clock;

	ActionsRunUpdates(ExchangeCorrelations correlations, GitHubProperties properties, ApplicationEventPublisher events,
			IdGenerator ids, Clock clock) {
		this.correlations = correlations;
		this.properties = properties;
		this.events = events;
		this.ids = ids;
		this.clock = clock;
	}

	/**
	 * A delivery seen for the first time, whose signature checked out with {@code app}'s secret: a completed run of a
	 * correlation of that App publishes {@link ActionsRunUpdated}; anything else nothing.
	 * @return how many correlations it named, 0 or 1
	 */
	int delivered(App app, String event, JsonNode payload) {
		if (!EVENT.equals(event) || !COMPLETED.equals(text(payload.path("action")))) {
			return 0;
		}
		JsonNode run = payload.path("workflow_run");
		long runId = run.path("id").isIntegralNumber() ? run.path("id").asLong() : 0;
		Long installation = payload.path("installation").path("id").isIntegralNumber()
				? payload.path("installation").path("id").asLong() : null;
		if (runId <= 0 || installation == null) {
			return 0;
		}
		String title = text(run.path("display_title"));
		Map<UUID, ExchangeCorrelations.Row> candidates = new LinkedHashMap<>();
		this.correlations.byRun(runId).forEach(row -> candidates.put(row.id(), row));
		Matcher named = CORRELATION.matcher(title);
		while (named.find()) {
			UUID id = UUID.fromString(named.group());
			if (!candidates.containsKey(id)) {
				this.correlations.find(id).ifPresent(row -> candidates.put(row.id(), row));
			}
		}
		int published = 0;
		for (ExchangeCorrelations.Row row : candidates.values()) {
			if (matches(row, app, installation, runId, title)) {
				log.info("Run {} of correlation {} completed, as the {} App delivered it", runId, row.id(), app);
				updated(row.id());
				published++;
			}
		}
		return published;
	}

	/**
	 * The correlation's run is to be read again: recorded on the correlation and published, in the caller's
	 * transaction. {@link #delivered}, {@link ActionsReports} and {@link ActionsExchange} call it.
	 */
	void updated(UUID correlation) {
		this.correlations.markUpdated(correlation, this.clock.instant());
		this.events.publishEvent(new ActionsRunUpdated(this.ids.newId(), correlation));
	}

	private boolean matches(ExchangeCorrelations.Row row, App app, long installation, long runId, String title) {
		if (!LIVE.contains(row.status())) {
			return false;
		}
		if (row.dispatchingApp() != app || !Objects.equals(installationOf(row), installation)) {
			return false;
		}
		Long recorded = row.runId();
		return recorded != null ? recorded == runId : title.contains(row.id().toString());
	}

	/** The installation that dispatched the correlation's run: the Customer's, or the Platform App's own. */
	private @Nullable Long installationOf(ExchangeCorrelations.Row row) {
		if (row.dispatchingApp() == App.SOURCE) {
			return row.sourceInstallationId();
		}
		AppProperties platform = this.properties.platformApp();
		return platform == null ? null : platform.installationId();
	}

	private static String text(JsonNode node) {
		return node.isString() ? node.asString() : "";
	}

}
