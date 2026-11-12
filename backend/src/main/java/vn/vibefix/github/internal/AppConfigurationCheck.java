package vn.vibefix.github.internal;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import vn.vibefix.github.GitHubApps.App;
import vn.vibefix.github.GitHubConfigurationDrift;
import vn.vibefix.github.GitHubException;
import vn.vibefix.shared.IdGenerator;

/**
 * SG-18's daily configuration check (AD-10): for each configured App, {@code GET /app} against AD-10's
 * permissions and events ({@link AppExpectations}), and {@code GET /app/hook/config}: the webhook is
 * active at {@code vibefix.github.webhook-url} when that is set (the Merge App's too, though it subscribes
 * to no event), sends JSON, and verifies SSL. Any difference publishes {@link GitHubConfigurationDrift},
 * which {@code admin} turns into an alert for every Administrator.
 */
@Component
class AppConfigurationCheck {

	private static final Logger log = LoggerFactory.getLogger(AppConfigurationCheck.class);

	private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

	private final GitHubAppsClient apps;

	private final ApplicationEventPublisher events;

	private final TransactionTemplate transactions;

	private final IdGenerator ids;

	private final Clock clock;

	AppConfigurationCheck(GitHubAppsClient apps, ApplicationEventPublisher events, PlatformTransactionManager transactionManager,
			IdGenerator ids, Clock clock) {
		this.apps = apps;
		this.events = events;
		this.transactions = new TransactionTemplate(transactionManager);
		this.ids = ids;
		this.clock = clock;
	}

	void daily() {
		for (App app : App.values()) {
			if (this.apps.isConfigured(app)) {
				check(app);
			}
		}
	}

	/**
	 * Compares the App with AD-10 and publishes any difference.
	 * @return the differences, empty when the App matches
	 */
	List<String> check(App app) {
		List<String> differences = new ArrayList<>();
		try {
			differences.addAll(AppExpectations.differences(app, this.apps.registration(app)));
		}
		catch (GitHubException refused) {
			differences.add("GET /app: HTTP " + refused.status());
		}
		GitHubHttp.Answer hook = this.apps.http().send("GET", "/app/hook/config", this.apps.jwt(app), null);
		if (!hook.ok()) {
			differences.add("GET /app/hook/config: HTTP " + hook.status() + " (no active webhook)");
		}
		else {
			differences.addAll(webhook(hook.body()));
		}
		if (!differences.isEmpty()) {
			log.warn("The {} App differs from AD-10: {}", app, differences);
			LocalDate today = LocalDate.ofInstant(this.clock.instant(), VIETNAM);
			this.transactions.executeWithoutResult(status -> this.events
				.publishEvent(new GitHubConfigurationDrift(this.ids.newId(), app, today, differences)));
		}
		return differences;
	}

	@Component
	@ConditionalOnBooleanProperty(name = "vibefix.scheduling.enabled", matchIfMissing = true)
	static class Scheduler {

		private final AppConfigurationCheck check;

		Scheduler(AppConfigurationCheck check) {
			this.check = check;
		}

		@Scheduled(cron = "${vibefix.github.configuration-check-cron:0 10 4 * * *}", zone = "Asia/Ho_Chi_Minh")
		void run() {
			this.check.daily();
		}

	}

	private List<String> webhook(JsonNode config) {
		List<String> differences = new ArrayList<>();
		String url = config.path("url").asString("");
		if (url.isEmpty()) {
			differences.add("webhook URL: none (the webhook is not active)");
		}
		else if (this.apps.properties().webhookUrl() != null && !url.equals(this.apps.properties().webhookUrl().toString())) {
			differences.add("webhook URL: " + url + " instead of " + this.apps.properties().webhookUrl());
		}
		String contentType = config.path("content_type").asString("");
		if (!"json".equals(contentType)) {
			differences.add("webhook content type: " + contentType + " instead of json");
		}
		String insecure = config.path("insecure_ssl").asString("0");
		if (!"0".equals(insecure)) {
			differences.add("webhook SSL verification: off");
		}
		return differences;
	}

}
