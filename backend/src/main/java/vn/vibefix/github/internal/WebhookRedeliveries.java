package vn.vibefix.github.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import vn.vibefix.github.GitHubApps.App;

/**
 * AD-12's daily redelivery: GitHub does not redeliver a failed webhook by itself, so each configured
 * App's deliveries of the last 3 days are read with its JWT ({@code GET /app/hook/deliveries}, page by
 * page) and every delivery GUID with no {@code OK} attempt is redelivered once, from its latest attempt
 * ({@code POST /app/hook/deliveries/{id}/attempts}). The signature and the delivery ID still decide on
 * arrival. It also purges kept delivery IDs older than 30 days.
 */
@Component
class WebhookRedeliveries {

	static final Duration LOOK_BACK = Duration.ofDays(3);

	static final Duration KEEP = Duration.ofDays(30);

	private static final Logger log = LoggerFactory.getLogger(WebhookRedeliveries.class);

	private final GitHubAppsClient apps;

	private final JdbcClient jdbc;

	private final Clock clock;

	WebhookRedeliveries(GitHubAppsClient apps, JdbcClient jdbc, Clock clock) {
		this.apps = apps;
		this.jdbc = jdbc;
		this.clock = clock;
	}

	void daily() {
		for (App app : App.values()) {
			if (this.apps.isConfigured(app)) {
				redeliver(app);
			}
		}
		purge();
	}

	/**
	 * Redelivers the App's failed deliveries of the last 3 days.
	 * @return how many were asked for again
	 */
	int redeliver(App app) {
		Instant since = this.clock.instant().minus(LOOK_BACK);
		// The latest attempt of each GUID, and whether any attempt of it succeeded.
		Map<String, Long> latest = new LinkedHashMap<>();
		Map<String, Boolean> delivered = new LinkedHashMap<>();
		String cursor = null;
		boolean older = false;
		do {
			GitHubHttp.Answer page = this.apps.http()
				.send("GET", "/app/hook/deliveries?per_page=100" + (cursor == null ? "" : "&cursor=" + cursor),
						this.apps.jwt(app), null);
			if (!page.ok()) {
				log.warn("GitHub refused GET /app/hook/deliveries for the {} App: HTTP {}", app, page.status());
				return 0;
			}
			for (JsonNode delivery : page.body()) {
				Instant at = instant(delivery.path("delivered_at").asString(""));
				if (at == null || at.isBefore(since)) {
					older = true;
					continue;
				}
				String guid = delivery.path("guid").asString("");
				long id = delivery.path("id").asLong();
				boolean ok = "OK".equals(delivery.path("status").asString(""));
				delivered.merge(guid, ok, Boolean::logicalOr);
				latest.merge(guid, id, Math::max);
			}
			cursor = page.nextCursor().orElse(null);
		}
		while (cursor != null && !older);
		int asked = 0;
		for (Map.Entry<String, Long> attempt : latest.entrySet()) {
			if (delivered.get(attempt.getKey())) {
				continue;
			}
			GitHubHttp.Answer answer = this.apps.http()
				.send("POST", "/app/hook/deliveries/" + attempt.getValue() + "/attempts", this.apps.jwt(app), null);
			if (answer.ok()) {
				asked++;
			}
			else {
				log.warn("GitHub refused to redeliver a {} App delivery: HTTP {}", app, answer.status());
			}
		}
		if (asked > 0) {
			log.info("Asked GitHub to redeliver {} failed {} App webhook deliveries", asked, app);
		}
		return asked;
	}

	/** Forgets delivery IDs older than {@link #KEEP}. */
	int purge() {
		return this.jdbc.sql("DELETE FROM github.webhook_delivery WHERE received_at < :cutoff")
			.param("cutoff", Timestamp.from(this.clock.instant().minus(KEEP)))
			.update();
	}

	@Component
	@ConditionalOnBooleanProperty(name = "vibefix.scheduling.enabled", matchIfMissing = true)
	static class Scheduler {

		private final WebhookRedeliveries redeliveries;

		Scheduler(WebhookRedeliveries redeliveries) {
			this.redeliveries = redeliveries;
		}

		@Scheduled(cron = "${vibefix.github.redelivery-cron:0 0 4 * * *}", zone = "Asia/Ho_Chi_Minh")
		void run() {
			this.redeliveries.daily();
		}

	}

	private static @Nullable Instant instant(String text) {
		try {
			return text.isEmpty() ? null : Instant.parse(text);
		}
		catch (DateTimeParseException ex) {
			return null;
		}
	}

}
