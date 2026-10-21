package vn.vibefix.github.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import vn.vibefix.github.GitHubApps.App;
import vn.vibefix.github.GitHubConfigurationDrift;
import vn.vibefix.github.GitHubWebhookReceived;
import vn.vibefix.identity.internal.AdministratorTestSupport;
import vn.vibefix.identity.internal.AdministratorTestSupport.Administrator;
import vn.vibefix.support.IntegrationTest;

/**
 * Story 7.1 (AD-12, SG-18): both Apps' webhooks at {@code POST /webhooks}, with no session and no CSRF
 * token. A delivery signed by its App is kept once and published; any other is refused unread. Failed
 * deliveries of the last 3 days are redelivered once from their latest attempt, kept delivery IDs are
 * forgotten after 30 days, and an App unlike AD-10 alerts every Administrator. GitHub is
 * {@link GitHubStub}; the webhook secrets are fake, built at run time.
 */
@RecordApplicationEvents
class GitHubWebhookIT extends IntegrationTest {

	private static final byte[] PLATFORM_SECRET = ("platform-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);

	private static final byte[] MERGE_SECRET = ("merge-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);

	private static final Path DIRECTORY;

	private static final GitHubStub GITHUB;

	static {
		try {
			DIRECTORY = Files.createTempDirectory("github-webhooks-");
			GITHUB = new GitHubStub(Files.createDirectories(DIRECTORY.resolve("git")));
			Files.writeString(DIRECTORY.resolve("app-key"), TestKeys.pkcs1());
			Files.write(DIRECTORY.resolve("platform-webhook-secret"), PLATFORM_SECRET);
			Files.write(DIRECTORY.resolve("merge-webhook-secret"), MERGE_SECRET);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static final String AD10_HOOK = "{\"url\":\"https://vibefix.test/webhooks\",\"content_type\":\"json\",\"insecure_ssl\":\"0\"}";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private ApplicationEvents events;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private ApplicationContext context;

	@Autowired
	private WebhookRedeliveries redeliveries;

	@Autowired
	private AppConfigurationCheck check;

	@DynamicPropertySource
	static void github(DynamicPropertyRegistry registry) {
		registry.add("vibefix.github.api-url", () -> GITHUB.apiUrl().toString());
		registry.add("vibefix.github.webhook-url", () -> "https://vibefix.test/webhooks");
		registry.add("vibefix.github.platform-app.id", () -> "123456");
		registry.add("vibefix.github.platform-app.installation-id", () -> String.valueOf(GitHubStub.INSTALLATION));
		registry.add("vibefix.github.platform-app.key-file", () -> DIRECTORY.resolve("app-key").toString());
		registry.add("vibefix.github.platform-app.webhook-secret-file",
				() -> DIRECTORY.resolve("platform-webhook-secret").toString());
		registry.add("vibefix.github.merge-app.id", () -> "654321");
		registry.add("vibefix.github.merge-app.key-file", () -> DIRECTORY.resolve("app-key").toString());
		registry.add("vibefix.github.merge-app.webhook-secret-file", () -> DIRECTORY.resolve("merge-webhook-secret").toString());
	}

	@AfterAll
	static void stop() {
		GITHUB.close();
	}

	@BeforeEach
	void aFreshGitHub() {
		GITHUB.seen.clear();
		GITHUB.deliveries.clear();
		GITHUB.redelivered.clear();
		GITHUB.app = registration(AppExpectations.PLATFORM_PERMISSIONS, AppExpectations.PLATFORM_EVENTS);
		GITHUB.hookConfig = AD10_HOOK;
	}

	@Test
	void aSignedDeliveryIsKeptOnceAndPublished() throws Exception {
		String delivery = UUID.randomUUID().toString();
		byte[] push = """
				{"ref":"refs/heads/main","installation":{"id":7},"repository":{"id":1001,"name":"quan-may"},
				"sender":{"id":42,"login":"lan-dev"}}""".getBytes(StandardCharsets.UTF_8);

		this.mvc.perform(webhook("123456", delivery, "push", push, PLATFORM_SECRET)).andExpect(status().isAccepted());
		this.mvc.perform(webhook("123456", delivery, "push", push, PLATFORM_SECRET)).andExpect(status().isOk());

		assertThat(this.events.stream(GitHubWebhookReceived.class)).singleElement().satisfies(received -> {
			assertThat(received.deliveryId()).isEqualTo(delivery);
			assertThat(received.app()).isEqualTo(App.PLATFORM);
			assertThat(received.event()).isEqualTo("push");
			assertThat(received.action()).isNull();
			assertThat(received.installationId()).isEqualTo(7L);
			assertThat(received.repositoryIds()).containsExactly(1001L);
			assertThat(received.senderId()).isEqualTo(42L);
			assertThat(received.requesterId()).isNull();
		});
		assertThat(kept()).containsExactly(delivery + " PLATFORM push");
	}

	@Test
	void aMergeAppInstallationNamesEveryRepositoryAndItsRequester() throws Exception {
		String delivery = UUID.randomUUID().toString();
		byte[] added = """
				{"action":"added","installation":{"id":88},"repositories_added":[{"id":2001},{"id":2002}],
				"repositories_removed":[{"id":2003},{"id":2001}],"requester":{"id":77},"sender":{"id":78}}"""
			.getBytes(StandardCharsets.UTF_8);

		this.mvc.perform(webhook("654321", delivery, "installation_repositories", added, MERGE_SECRET))
			.andExpect(status().isAccepted());

		assertThat(this.events.stream(GitHubWebhookReceived.class)).singleElement().satisfies(received -> {
			assertThat(received.app()).isEqualTo(App.MERGE);
			assertThat(received.action()).isEqualTo("added");
			assertThat(received.installationId()).isEqualTo(88L);
			assertThat(received.repositoryIds()).containsExactly(2001L, 2002L, 2003L);
			assertThat(received.requesterId()).isEqualTo(77L);
			assertThat(received.senderId()).isEqualTo(78L);
		});
	}

	@Test
	void aDeliveryNotSignedByItsOwnAppIsRefusedUnread() throws Exception {
		byte[] body = "{\"zen\":\"Keep it logically awesome.\"}".getBytes(StandardCharsets.UTF_8);

		this.mvc.perform(webhook("123456", UUID.randomUUID().toString(), "ping", body, null))
			.andExpect(status().isUnauthorized());
		this.mvc.perform(webhook("123456", UUID.randomUUID().toString(), "ping", body, MERGE_SECRET))
			.andExpect(status().isUnauthorized());
		this.mvc.perform(webhook("999999", UUID.randomUUID().toString(), "ping", body, PLATFORM_SECRET))
			.andExpect(status().isUnauthorized());
		this.mvc.perform(webhook(null, UUID.randomUUID().toString(), "ping", body, PLATFORM_SECRET))
			.andExpect(status().isUnauthorized());
		byte[] other = "{\"zen\":\"Something else.\"}".getBytes(StandardCharsets.UTF_8);
		this.mvc.perform(webhook("123456", UUID.randomUUID().toString(), "ping", other, PLATFORM_SECRET)
			.content(body)).andExpect(status().isUnauthorized());

		assertThat(this.events.stream(GitHubWebhookReceived.class)).isEmpty();
		assertThat(kept()).isEmpty();
	}

	@Test
	void aSignedDeliveryWithoutItsHeadersOrJsonIsABadRequest() throws Exception {
		byte[] body = "{\"zen\":\"Half measures are as bad as nothing at all.\"}".getBytes(StandardCharsets.UTF_8);
		byte[] notJson = "zen=half".getBytes(StandardCharsets.UTF_8);

		this.mvc.perform(webhook("123456", null, "ping", body, PLATFORM_SECRET)).andExpect(status().isBadRequest());
		this.mvc.perform(webhook("123456", UUID.randomUUID().toString(), null, body, PLATFORM_SECRET))
			.andExpect(status().isBadRequest());
		this.mvc.perform(webhook("123456", "x".repeat(101), "ping", body, PLATFORM_SECRET)).andExpect(status().isBadRequest());
		this.mvc.perform(webhook("123456", UUID.randomUUID().toString(), "ping", notJson, PLATFORM_SECRET))
			.andExpect(status().isBadRequest());

		assertThat(kept()).isEmpty();
	}

	@Test
	void failedDeliveriesOfTheLastThreeDaysAreRedeliveredOnceFromTheirLatestAttempt() {
		Instant now = Instant.parse("2026-10-21T03:00:00Z");
		this.clock.setInstant(now);
		// Newest first, as GitHub lists them: a full page of delivered ones, then the failures on page 2.
		for (int i = 0; i < 100; i++) {
			GITHUB.deliveries.add(GitHubStub.delivery(5000 - i, "ok-" + i, at(now, Duration.ofMinutes(10)), "OK"));
		}
		GITHUB.deliveries.add(GitHubStub.delivery(901, "twice-failed", at(now, Duration.ofHours(2)), "Invalid HTTP Response: 503"));
		GITHUB.deliveries.add(GitHubStub.delivery(900, "twice-failed", at(now, Duration.ofHours(3)), "Invalid HTTP Response: 503"));
		GITHUB.deliveries.add(GitHubStub.delivery(801, "delivered-later", at(now, Duration.ofHours(4)), "OK"));
		GITHUB.deliveries.add(GitHubStub.delivery(800, "delivered-later", at(now, Duration.ofHours(5)), "timed out"));
		GITHUB.deliveries.add(GitHubStub.delivery(700, "too-old", at(now, Duration.ofDays(4)), "timed out"));

		assertThat(this.redeliveries.redeliver(App.PLATFORM)).isOne();

		assertThat(GITHUB.redelivered).containsExactly(901L);
		assertThat(GITHUB.seen).filteredOn(request -> request.path().equals("/app/hook/deliveries"))
			.hasSize(2)
			.allSatisfy(request -> assertThat(request.authorization()).startsWith("Bearer ey"));
	}

	@Test
	void keptDeliveryIdsAreForgottenAfterThirtyDays() {
		Instant now = Instant.parse("2026-10-21T03:00:00Z");
		this.clock.setInstant(now);
		keep("old", now.minus(Duration.ofDays(31)));
		keep("recent", now.minus(Duration.ofDays(29)));

		assertThat(this.redeliveries.purge()).isOne();

		assertThat(kept()).containsExactly("recent PLATFORM push");
	}

	@Test
	void anAppLikeAd10PublishesNothing() {
		Set<String> withMeta = new TreeSet<>(AppExpectations.PLATFORM_EVENTS);
		withMeta.add("meta");
		GITHUB.app = registration(AppExpectations.PLATFORM_PERMISSIONS, withMeta);

		assertThat(this.check.check(App.PLATFORM)).isEmpty();

		assertThat(this.events.stream(GitHubConfigurationDrift.class)).isEmpty();
	}

	@Test
	void anAppUnlikeAd10AlertsEveryAdministrator() {
		Administrator ha = AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		this.clock.setInstant(Instant.parse("2026-10-20T21:30:00Z"));
		GITHUB.app = registration(Map.of("contents", "write", "metadata", "read"), Set.of("push", "pull_request"));
		GITHUB.hookConfig = "{\"url\":\"https://old.example/webhooks\",\"content_type\":\"form\",\"insecure_ssl\":\"1\"}";

		List<String> differences = this.check.check(App.PLATFORM);

		assertThat(differences).hasSize(5);
		assertThat(differences).filteredOn(difference -> difference.startsWith("webhook"))
			.containsExactly("webhook URL: https://old.example/webhooks instead of https://vibefix.test/webhooks",
					"webhook content type: form instead of json", "webhook SSL verification: off");
		assertThat(this.events.stream(GitHubConfigurationDrift.class)).singleElement().satisfies(drift -> {
			assertThat(drift.app()).isEqualTo(App.PLATFORM);
			assertThat(drift.day()).as("the Vietnam day").isEqualTo(LocalDate.parse("2026-10-21"));
			assertThat(drift.differences()).isEqualTo(differences);
		});
		awaitListeners();
		assertThat(this.jdbc.sql("""
				SELECT params ->> 'app' FROM notification.notification
				WHERE type = 'github.app_configuration_drift' AND account_id = :id
				""").param("id", ha.accountId()).query(String.class).list()).containsExactly("Platform App");
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM operations.external_operation WHERE type = 'notification.email'
				AND payload ->> 'template' = 'github.app_configuration_drift'
				""").query(Long.class).single()).isOne();
	}

	@Test
	void anInactiveWebhookIsADifference() {
		GITHUB.hookConfig = null;

		assertThat(this.check.check(App.MERGE)).contains("GET /app/hook/config: HTTP 404 (no active webhook)");
		awaitListeners();
	}

	private void awaitListeners() {
		await().atMost(Duration.ofSeconds(15))
			.until(() -> this.jdbc.sql("SELECT count(*) FROM platform.event_publication WHERE completion_date IS NULL")
				.query(Long.class)
				.single() == 0);
	}

	private static MockHttpServletRequestBuilder webhook(String target, String delivery, String event, byte[] body,
			byte[] secret) {
		MockHttpServletRequestBuilder request = post("/webhooks").contentType(MediaType.APPLICATION_JSON).content(body);
		if (target != null) {
			request.header("X-GitHub-Hook-Installation-Target-ID", target)
				.header("X-GitHub-Hook-Installation-Target-Type", "integration");
		}
		if (delivery != null) {
			request.header("X-GitHub-Delivery", delivery);
		}
		if (event != null) {
			request.header("X-GitHub-Event", event);
		}
		if (secret != null) {
			request.header("X-Hub-Signature-256", WebhookSignaturesTest.header(secret, body));
		}
		return request;
	}

	private static String registration(Map<String, String> permissions, Set<String> events) {
		return GitHubHttp.JSON.writeValueAsString(
				Map.of("slug", "vibefix-dev-platform", "permissions", permissions, "events", new ArrayList<>(events)));
	}

	private static String at(Instant now, Duration ago) {
		return now.minus(ago).toString();
	}

	private void keep(String delivery, Instant at) {
		this.jdbc.sql("""
				INSERT INTO github.webhook_delivery (delivery_id, app, event, received_at)
				VALUES (:id, 'PLATFORM', 'push', :at)
				""").param("id", delivery).param("at", Timestamp.from(at)).update();
	}

	private List<String> kept() {
		return this.jdbc.sql("SELECT delivery_id || ' ' || app || ' ' || event FROM github.webhook_delivery ORDER BY delivery_id")
			.query(String.class)
			.list();
	}

}
