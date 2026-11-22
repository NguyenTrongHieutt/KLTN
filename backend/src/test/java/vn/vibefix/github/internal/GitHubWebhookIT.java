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

import org.jspecify.annotations.Nullable;
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

import vn.vibefix.github.ActionsRunUpdated;
import vn.vibefix.github.ActionsRuns.Operation;
import vn.vibefix.github.ActionsRuns.Status;
import vn.vibefix.github.ExchangeReason;
import vn.vibefix.github.GitHubApps.App;
import vn.vibefix.github.GitHubConfigurationDrift;
import vn.vibefix.github.GitHubWebhookReceived;
import vn.vibefix.github.SourcePushed;
import vn.vibefix.identity.internal.AdministratorTestSupport;
import vn.vibefix.identity.internal.AdministratorTestSupport.Administrator;
import vn.vibefix.support.IntegrationTest;

/**
 * Story 7.1 (AD-12, SG-18): the webhooks at {@code POST /webhooks}, with no session and no CSRF token; since Story
 * 24.5a both Apps have one, each with its own secret. A delivery signed by its App is kept once and published; any
 * other, the other App's secret included, is refused unread. A completed {@code workflow_run} of a correlation of the
 * App that sent it, on its installation and for its run, also publishes {@link ActionsRunUpdated}; nothing else does.
 * Failed deliveries of the last 3 days are redelivered once from their latest attempt, each App's with its own JWT,
 * kept delivery IDs are forgotten after 30 days, and an App unlike AD-10 alerts every Administrator. GitHub is
 * {@link GitHubStub}; the webhook secrets are fake, built at run time.
 */
@RecordApplicationEvents
class GitHubWebhookIT extends IntegrationTest {

	private static final byte[] PLATFORM_SECRET = ("platform-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);

	private static final byte[] SOURCE_SECRET = ("source-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);

	/** A secret no App of this environment has, as another sender's would be. */
	private static final byte[] OTHER_SECRET = ("other-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);

	private static final String PLATFORM_ID = "123456";

	private static final String SOURCE_ID = String.valueOf(GitHubStub.SOURCE_APP);

	/** A Customer's Source App installation, as a correlation of a run it dispatched records it. */
	private static final long CUSTOMER_INSTALLATION = 4401;

	private static final Path DIRECTORY;

	private static final GitHubStub GITHUB;

	static {
		try {
			DIRECTORY = Files.createTempDirectory("github-webhooks-");
			GITHUB = new GitHubStub(Files.createDirectories(DIRECTORY.resolve("git")));
			// AD-46: the runner the organization read-back requires (Story 23.3).
			GITHUB.runner("vibefix-runner");
			Files.writeString(DIRECTORY.resolve("app-key"), TestKeys.pkcs1());
			Files.write(DIRECTORY.resolve("platform-webhook-secret"), PLATFORM_SECRET);
			Files.write(DIRECTORY.resolve("source-webhook-secret"), SOURCE_SECRET);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
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

	@Autowired
	private ExchangeCorrelations correlations;

	@DynamicPropertySource
	static void github(DynamicPropertyRegistry registry) {
		registry.add("vibefix.github.api-url", () -> GITHUB.apiUrl().toString());
		registry.add("vibefix.github.webhook-url", () -> "https://vibefix.test/webhooks");
		registry.add("vibefix.github.platform-app.id", () -> PLATFORM_ID);
		registry.add("vibefix.github.platform-app.installation-id", () -> String.valueOf(GitHubStub.INSTALLATION));
		registry.add("vibefix.github.platform-app.key-file", () -> DIRECTORY.resolve("app-key").toString());
		registry.add("vibefix.github.runner-repository", () -> "vibefix-runner");
		registry.add("vibefix.github.platform-app.webhook-secret-file",
				() -> DIRECTORY.resolve("platform-webhook-secret").toString());
		// Story 23.5: the Source App; Story 24.5a gave it a webhook with its own secret.
		registry.add("vibefix.github.source-app.id", () -> SOURCE_ID);
		registry.add("vibefix.github.source-app.key-file", () -> DIRECTORY.resolve("app-key").toString());
		registry.add("vibefix.github.source-app.webhook-secret-file",
				() -> DIRECTORY.resolve("source-webhook-secret").toString());
	}

	@AfterAll
	static void stop() {
		GITHUB.close();
	}

	@BeforeEach
	void aFreshGitHub() {
		GITHUB.seen.clear();
		GITHUB.deliveries.clear();
		GITHUB.sourceDeliveries.clear();
		GITHUB.redelivered.clear();
		GITHUB.sourceRedelivered.clear();
		GITHUB.app = registration(AppExpectations.PLATFORM_PERMISSIONS, AppExpectations.PLATFORM_EVENTS);
		GITHUB.sourceApp = registration(AppExpectations.SOURCE_PERMISSIONS, AppExpectations.SOURCE_EVENTS);
		GITHUB.hookConfig = AD10_HOOK;
		GITHUB.sourceHookConfig = AD10_HOOK;
	}

	@Test
	void aSignedDeliveryIsKeptOnceAndPublished() throws Exception {
		String delivery = UUID.randomUUID().toString();
		byte[] push = """
				{"ref":"refs/heads/main","installation":{"id":7},"repository":{"id":1001,"name":"quan-may"},
				"sender":{"id":42,"login":"lan-dev"}}""".getBytes(StandardCharsets.UTF_8);

		this.mvc.perform(webhook(PLATFORM_ID, delivery, "push", push, PLATFORM_SECRET)).andExpect(status().isAccepted());
		this.mvc.perform(webhook(PLATFORM_ID, delivery, "push", push, PLATFORM_SECRET)).andExpect(status().isOk());

		assertThat(this.events.stream(GitHubWebhookReceived.class)).singleElement().satisfies(received -> {
			assertThat(received.deliveryId()).isEqualTo(delivery);
			assertThat(received.app()).isEqualTo(App.PLATFORM);
			assertThat(received.event()).isEqualTo("push");
			assertThat(received.action()).isNull();
			assertThat(received.installationId()).isEqualTo(7L);
			assertThat(received.repositoryIds()).containsExactly(1001L);
			assertThat(received.senderId()).isEqualTo(42L);
		});
		assertThat(kept()).containsExactly(delivery + " PLATFORM push");
	}

	/** Story 24.5a: the Source App's deliveries are verified with its own secret, kept and published like the Platform App's. */
	@Test
	void aSourceAppDeliverySignedWithItsOwnSecretIsKeptOnceAndPublished() throws Exception {
		String delivery = UUID.randomUUID().toString();
		byte[] run = workflowRun("requested", 9001, "vibefix copy " + UUID.randomUUID(), CUSTOMER_INSTALLATION);

		this.mvc.perform(webhook(SOURCE_ID, delivery, "workflow_run", run, SOURCE_SECRET)).andExpect(status().isAccepted());
		this.mvc.perform(webhook(SOURCE_ID, delivery, "workflow_run", run, SOURCE_SECRET)).andExpect(status().isOk());

		assertThat(this.events.stream(GitHubWebhookReceived.class)).singleElement().satisfies(received -> {
			assertThat(received.app()).isEqualTo(App.SOURCE);
			assertThat(received.event()).isEqualTo("workflow_run");
			assertThat(received.action()).isEqualTo("requested");
			assertThat(received.installationId()).isEqualTo(CUSTOMER_INSTALLATION);
		});
		assertThat(kept()).containsExactly(delivery + " SOURCE workflow_run");
	}

	@Test
	void aDeliveryNotSignedByItsOwnAppIsRefusedUnread() throws Exception {
		byte[] body = "{\"zen\":\"Keep it logically awesome.\"}".getBytes(StandardCharsets.UTF_8);

		this.mvc.perform(webhook(PLATFORM_ID, UUID.randomUUID().toString(), "ping", body, null))
			.andExpect(status().isUnauthorized());
		this.mvc.perform(webhook(PLATFORM_ID, UUID.randomUUID().toString(), "ping", body, OTHER_SECRET))
			.andExpect(status().isUnauthorized());
		this.mvc.perform(webhook("999999", UUID.randomUUID().toString(), "ping", body, PLATFORM_SECRET))
			.andExpect(status().isUnauthorized());
		this.mvc.perform(webhook(null, UUID.randomUUID().toString(), "ping", body, PLATFORM_SECRET))
			.andExpect(status().isUnauthorized());
		byte[] other = "{\"zen\":\"Something else.\"}".getBytes(StandardCharsets.UTF_8);
		this.mvc.perform(webhook(PLATFORM_ID, UUID.randomUUID().toString(), "ping", other, PLATFORM_SECRET)
			.content(body)).andExpect(status().isUnauthorized());

		assertThat(this.events.stream(GitHubWebhookReceived.class)).isEmpty();
		assertThat(kept()).isEmpty();
	}

	/** Story 24.5a: each App's deliveries check out under its own secret only, so the other App's is refused. */
	@Test
	void aDeliverySignedWithTheOtherAppsSecretIsRefusedUnread() throws Exception {
		UUID correlation = correlation(App.SOURCE, CUSTOMER_INSTALLATION, 9101L, Status.EXCHANGED);
		byte[] run = workflowRun("completed", 9101, "vibefix copy " + correlation, CUSTOMER_INSTALLATION);

		this.mvc.perform(webhook(SOURCE_ID, UUID.randomUUID().toString(), "workflow_run", run, PLATFORM_SECRET))
			.andExpect(status().isUnauthorized());
		this.mvc.perform(webhook(PLATFORM_ID, UUID.randomUUID().toString(), "workflow_run", run, SOURCE_SECRET))
			.andExpect(status().isUnauthorized());

		assertThat(this.events.stream(GitHubWebhookReceived.class)).isEmpty();
		assertThat(this.events.stream(ActionsRunUpdated.class)).isEmpty();
		assertThat(kept()).isEmpty();
	}

	@Test
	void aSignedDeliveryWithoutItsHeadersOrJsonIsABadRequest() throws Exception {
		byte[] body = "{\"zen\":\"Half measures are as bad as nothing at all.\"}".getBytes(StandardCharsets.UTF_8);
		byte[] notJson = "zen=half".getBytes(StandardCharsets.UTF_8);

		this.mvc.perform(webhook(PLATFORM_ID, null, "ping", body, PLATFORM_SECRET)).andExpect(status().isBadRequest());
		this.mvc.perform(webhook(PLATFORM_ID, UUID.randomUUID().toString(), null, body, PLATFORM_SECRET))
			.andExpect(status().isBadRequest());
		this.mvc.perform(webhook(PLATFORM_ID, "x".repeat(101), "ping", body, PLATFORM_SECRET)).andExpect(status().isBadRequest());
		this.mvc.perform(webhook(PLATFORM_ID, UUID.randomUUID().toString(), "ping", notJson, PLATFORM_SECRET))
			.andExpect(status().isBadRequest());

		assertThat(kept()).isEmpty();
	}

	/**
	 * Story 24.5a: a completed run of an open or exchanged correlation, from the App that dispatched it on its
	 * installation, names that correlation, by the run it recorded or, while none is recorded, by the correlation in
	 * its title. The same delivery again publishes nothing more.
	 */
	@Test
	void aCompletedRunOfAnOpenOrExchangedCorrelationPublishesThatItWasUpdated() throws Exception {
		UUID copy = correlation(App.SOURCE, CUSTOMER_INSTALLATION, 9201L, Status.EXCHANGED);
		UUID merge = correlation(App.SOURCE, CUSTOMER_INSTALLATION, null, Status.OPEN);
		UUID handover = correlation(App.PLATFORM, null, 9203L, Status.EXCHANGED);
		String delivery = UUID.randomUUID().toString();
		// A title that names no correlation: the run the correlation recorded names it all the same.
		byte[] copyRun = workflowRun("completed", 9201, "Customer's own title", CUSTOMER_INSTALLATION);

		this.mvc.perform(webhook(SOURCE_ID, delivery, "workflow_run", copyRun, SOURCE_SECRET)).andExpect(status().isAccepted());
		this.mvc.perform(webhook(SOURCE_ID, delivery, "workflow_run", copyRun, SOURCE_SECRET)).andExpect(status().isOk());
		this.mvc.perform(webhook(SOURCE_ID, UUID.randomUUID().toString(), "workflow_run",
				workflowRun("completed", 9202, "vibefix final-merge " + merge, CUSTOMER_INSTALLATION), SOURCE_SECRET))
			.andExpect(status().isAccepted());
		this.mvc.perform(webhook(PLATFORM_ID, UUID.randomUUID().toString(), "workflow_run",
				workflowRun("completed", 9203, "s6-copy " + handover, GitHubStub.INSTALLATION), PLATFORM_SECRET))
			.andExpect(status().isAccepted());

		assertThat(this.events.stream(ActionsRunUpdated.class).map(ActionsRunUpdated::correlationId))
			.containsExactly(copy, merge, handover);
		assertThat(this.events.stream(GitHubWebhookReceived.class)).as("each delivery once").hasSize(3);
		assertThat(List.of(copy, merge, handover)).as("each recorded on its correlation")
			.allMatch(this.correlations::wasUpdated);
	}

	/**
	 * Story 24.5a: an exchanged correlation whose token the 30-second revocation read already revoked, or lost to a
	 * restart, is still the one its operation waits on: its completed run names it.
	 */
	@Test
	void aCompletedRunWhoseTokenWasAlreadyRevokedStillNamesItsCorrelation() throws Exception {
		UUID revoked = correlation(App.SOURCE, CUSTOMER_INSTALLATION, 9211L, Status.REVOKED);
		UUID unknown = correlation(App.PLATFORM, null, 9212L, Status.REVOKE_UNKNOWN);

		this.mvc.perform(webhook(SOURCE_ID, UUID.randomUUID().toString(), "workflow_run",
				workflowRun("completed", 9211, "vibefix copy " + revoked, CUSTOMER_INSTALLATION), SOURCE_SECRET))
			.andExpect(status().isAccepted());
		this.mvc.perform(webhook(PLATFORM_ID, UUID.randomUUID().toString(), "workflow_run",
				workflowRun("completed", 9212, "s6-copy " + unknown, GitHubStub.INSTALLATION), PLATFORM_SECRET))
			.andExpect(status().isAccepted());

		assertThat(this.events.stream(ActionsRunUpdated.class).map(ActionsRunUpdated::correlationId))
			.containsExactly(revoked, unknown);
	}

	/**
	 * Story 24.5a: anything but a completed run of an open or exchanged correlation (its token since revoked included)
	 * of the App that sent it, on its installation and for its run, is answered 2xx and names no correlation; a refused
	 * or expired one's run is never the one an operation waits on.
	 */
	@Test
	void aRunThatIsNotOursOrNotCompletedNamesNoCorrelation() throws Exception {
		UUID copy = correlation(App.SOURCE, CUSTOMER_INSTALLATION, 9301L, Status.EXCHANGED);
		UUID handover = correlation(App.PLATFORM, null, 9302L, Status.EXCHANGED);
		UUID expired = correlation(App.SOURCE, CUSTOMER_INSTALLATION, 9303L, Status.EXPIRED);
		UUID refused = correlation(App.SOURCE, CUSTOMER_INSTALLATION, null, Status.REFUSED);
		String copied = "vibefix copy " + copy;
		List<Delivery> notOurs = List.of(
				// Not completed yet.
				source(workflowRun("requested", 9301, copied, CUSTOMER_INSTALLATION)),
				source(workflowRun("in_progress", 9301, copied, CUSTOMER_INSTALLATION)),
				// Another installation of the Source App.
				source(workflowRun("completed", 9301, copied, 4402)),
				// The other App: the Platform App did not dispatch the copy, nor the Source App the handover copy.
				new Delivery(PLATFORM_ID, PLATFORM_SECRET, workflowRun("completed", 9301, copied, GitHubStub.INSTALLATION)),
				source(workflowRun("completed", 9302, "s6-copy " + handover, CUSTOMER_INSTALLATION)),
				// Another run whose title names a correlation that recorded its own run.
				source(workflowRun("completed", 9399, copied, CUSTOMER_INSTALLATION)),
				// No correlation at all, and correlations given up or refused.
				source(workflowRun("completed", 9398, "vibefix copy " + UUID.randomUUID(), CUSTOMER_INSTALLATION)),
				source(workflowRun("completed", 9303, "vibefix copy " + expired, CUSTOMER_INSTALLATION)),
				source(workflowRun("completed", 9304, "vibefix copy " + refused, CUSTOMER_INSTALLATION)));

		for (Delivery delivery : notOurs) {
			this.mvc.perform(webhook(delivery.app(), UUID.randomUUID().toString(), "workflow_run", delivery.body(),
					delivery.secret())).andExpect(status().isAccepted());
		}
		this.mvc.perform(webhook(SOURCE_ID, UUID.randomUUID().toString(), "check_run",
				workflowRun("completed", 9301, copied, CUSTOMER_INSTALLATION), SOURCE_SECRET))
			.andExpect(status().isAccepted());

		assertThat(this.events.stream(ActionsRunUpdated.class)).isEmpty();
		assertThat(this.events.stream(GitHubWebhookReceived.class)).as("each still kept and published")
			.hasSize(notOurs.size() + 1);
	}

	/**
	 * Story 24.6: a Source App push is published once more, as {@link SourcePushed}, with its installation, repository,
	 * ref, default branch, after commit and commit IDs in the payload's order, whichever ref it moved; the delivery seen
	 * again publishes nothing. A Platform App push, another Source App event, and a push whose payload names no
	 * repository ID or no commit publish none.
	 */
	@Test
	void aSourceAppPushIsPublishedWithItsCommits() throws Exception {
		String delivery = UUID.randomUUID().toString();
		String first = "1".repeat(40);
		String second = "2".repeat(40);
		byte[] push = push(1001, "refs/heads/main", "main", second, List.of(first, second));

		this.mvc.perform(webhook(SOURCE_ID, delivery, "push", push, SOURCE_SECRET)).andExpect(status().isAccepted());
		this.mvc.perform(webhook(SOURCE_ID, delivery, "push", push, SOURCE_SECRET)).andExpect(status().isOk());
		this.mvc.perform(webhook(SOURCE_ID, UUID.randomUUID().toString(), "push",
				push(1001, "refs/heads/feature", "main", first, List.of(first)), SOURCE_SECRET))
			.andExpect(status().isAccepted());

		assertThat(this.events.stream(SourcePushed.class)).hasSize(2).first().satisfies(pushed -> {
			assertThat(pushed.deliveryId()).isEqualTo(delivery);
			assertThat(pushed.installationId()).isEqualTo(CUSTOMER_INSTALLATION);
			assertThat(pushed.repositoryId()).isEqualTo(1001);
			assertThat(pushed.ref()).isEqualTo("refs/heads/main");
			assertThat(pushed.defaultBranch()).isEqualTo("main");
			assertThat(pushed.after()).isEqualTo(second);
			assertThat(pushed.commits()).containsExactly(first, second);
			assertThat(pushed.toDefaultBranch()).isTrue();
		});
		assertThat(this.events.stream(SourcePushed.class).toList().get(1).toDefaultBranch()).as("another branch").isFalse();
		assertThat(this.events.stream(GitHubWebhookReceived.class)).as("each kept and published as any delivery")
			.hasSize(2);

		this.mvc.perform(webhook(PLATFORM_ID, UUID.randomUUID().toString(), "push", push, PLATFORM_SECRET))
			.andExpect(status().isAccepted());
		this.mvc.perform(webhook(SOURCE_ID, UUID.randomUUID().toString(), "repository", push, SOURCE_SECRET))
			.andExpect(status().isAccepted());
		this.mvc.perform(webhook(SOURCE_ID, UUID.randomUUID().toString(), "push",
				push(0, "refs/heads/main", "main", second, List.of(second)), SOURCE_SECRET))
			.andExpect(status().isAccepted());
		this.mvc.perform(webhook(SOURCE_ID, UUID.randomUUID().toString(), "push",
				push(1001, "refs/heads/main", "main", "not-a-commit", List.of()), SOURCE_SECRET))
			.andExpect(status().isAccepted());

		assertThat(this.events.stream(SourcePushed.class)).hasSize(2);
		assertThat(this.events.stream(GitHubWebhookReceived.class)).hasSize(6);
		awaitListeners();
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

	/** Story 24.5a: the Source App's failed deliveries are redelivered too, read and asked for with its own JWT. */
	@Test
	void theSourceAppsFailedDeliveriesAreRedeliveredWithItsOwnJwt() {
		Instant now = Instant.parse("2026-11-21T03:00:00Z");
		this.clock.setInstant(now);
		GITHUB.sourceDeliveries.add(GitHubStub.delivery(611, "source-failed", at(now, Duration.ofHours(1)), "timed out"));
		GITHUB.sourceDeliveries.add(GitHubStub.delivery(610, "source-ok", at(now, Duration.ofHours(2)), "OK"));
		GITHUB.sourceDeliveries.add(GitHubStub.delivery(609, "source-old", at(now, Duration.ofDays(4)), "timed out"));
		GITHUB.deliveries.add(GitHubStub.delivery(511, "platform-failed", at(now, Duration.ofHours(1)), "timed out"));

		this.redeliveries.daily();

		assertThat(GITHUB.sourceRedelivered).as("the Source App's, with its JWT").containsExactly(611L);
		assertThat(GITHUB.redelivered).as("the Platform App's, with its own").containsExactly(511L);
		assertThat(GITHUB.seen).filteredOn(request -> request.path().startsWith("/app/hook/deliveries"))
			.as("each App's deliveries read and asked for with its own JWT")
			.anyMatch(request -> Long.valueOf(GitHubStub.SOURCE_APP).equals(GitHubStub.issuerOf(request.authorization())))
			.anyMatch(request -> Long.valueOf(123456).equals(GitHubStub.issuerOf(request.authorization())));
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

		assertThat(this.check.check(App.PLATFORM)).contains("GET /app/hook/config: HTTP 404 (no active webhook)");
		awaitListeners();
	}

	/**
	 * Story 24.5a: the Source App is compared as the Platform App is, its registration and its own webhook, each read
	 * with its JWT; one that sends no workflow_run, or has no active webhook, is a difference.
	 */
	@Test
	void theSourceAppIsComparedWithItsWebhookAsThePlatformAppIs() {
		assertThat(this.check.check(App.SOURCE)).isEmpty();

		assertThat(GITHUB.seen).filteredOn(request -> request.path().equals("/app") || request.path().equals("/app/hook/config"))
			.as("its registration and its webhook, read with its own JWT")
			.hasSize(2)
			.allSatisfy(request -> assertThat(GitHubStub.issuerOf(request.authorization())).isEqualTo(GitHubStub.SOURCE_APP));
		assertThat(this.events.stream(GitHubConfigurationDrift.class)).isEmpty();

		GITHUB.sourceApp = registration(AppExpectations.SOURCE_PERMISSIONS, Set.of());
		GITHUB.sourceHookConfig = null;

		assertThat(this.check.check(App.SOURCE)).containsExactly("events: [] instead of [push, repository, workflow_run]",
				"GET /app/hook/config: HTTP 404 (no active webhook)");
		assertThat(this.events.stream(GitHubConfigurationDrift.class)).singleElement()
			.satisfies(drift -> assertThat(drift.app()).isEqualTo(App.SOURCE));
		awaitListeners();
	}

	private void awaitListeners() {
		await().atMost(Duration.ofSeconds(15))
			.until(() -> this.jdbc.sql("SELECT count(*) FROM platform.event_publication WHERE completion_date IS NULL")
				.query(Long.class)
				.single() == 0);
	}

	/** One delivery: the App it names, the secret it is signed with, and its body. */
	private record Delivery(String app, byte[] secret, byte[] body) {
	}

	private static Delivery source(byte[] body) {
		return new Delivery(SOURCE_ID, SOURCE_SECRET, body);
	}

	/**
	 * A correlation of a run {@code app} dispatched, in {@code status}, with its run recorded or none: what
	 * {@link vn.vibefix.github.ActionsRuns#dispatch} leaves, and the exchange after it.
	 */
	private UUID correlation(App app, @Nullable Long installation, @Nullable Long runId, Status status) {
		UUID id = UUID.randomUUID();
		Instant now = this.clock.instant();
		Operation operation = app == App.SOURCE ? Operation.FINAL_MERGE : Operation.HANDOVER_COPY;
		this.correlations.insert(new ExchangeCorrelations.Row(id, operation, "lan-dev/quan-may", 1001, "main",
				".github/workflows/vibefix.yml", app.name(), installation, Set.of(2002L), operation.permissions(), null,
				Status.OPEN, null, null, null, null, null, now, now.plus(Duration.ofMinutes(20)), null, null));
		if (runId != null) {
			this.correlations.runFound(id, runId, null);
		}
		switch (status) {
			case OPEN -> {
			}
			case EXCHANGED -> assertThat(this.correlations.exchanged(id, runId, now)).isTrue();
			case REVOKED -> {
				assertThat(this.correlations.exchanged(id, runId, now)).isTrue();
				assertThat(this.correlations.revoked(id, now)).isTrue();
			}
			case REVOKE_UNKNOWN -> {
				assertThat(this.correlations.exchanged(id, runId, now)).isTrue();
				assertThat(this.correlations.revokeUnknown(id)).isTrue();
			}
			case EXPIRED -> assertThat(this.correlations.close(id)).isTrue();
			case REFUSED -> assertThat(this.correlations.refuse(id, ExchangeReason.RUN)).isTrue();
			default -> throw new IllegalArgumentException(status.name());
		}
		return id;
	}

	/** A {@code push} delivery's body, as GitHub sends it for the Customer's Source App installation (Story 24.6). */
	private static byte[] push(long repositoryId, String ref, String defaultBranch, String after, List<String> commits) {
		return GitHubHttp.JSON.writeValueAsBytes(Map.of("ref", ref, "before", "0".repeat(40), "after", after, "commits",
				commits.stream().map(id -> Map.of("id", id, "message", "Lovable sync")).toList(), "installation",
				Map.of("id", CUSTOMER_INSTALLATION), "repository",
				Map.of("id", repositoryId, "full_name", "lan-dev/quan-may", "default_branch", defaultBranch), "sender",
				Map.of("id", 42, "login", "lan-dev")));
	}

	/** A {@code workflow_run} delivery's body, as GitHub sends it for an App's installation. */
	private static byte[] workflowRun(String action, long runId, String title, long installation) {
		return GitHubHttp.JSON.writeValueAsBytes(Map.of("action", action, "workflow_run",
				Map.of("id", runId, "display_title", title, "status", action), "installation", Map.of("id", installation),
				"repository", Map.of("id", 1001, "full_name", "lan-dev/quan-may"), "sender",
				Map.of("id", 42, "login", "lan-dev")));
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
