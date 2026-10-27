package vn.vibefix.repo.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.github.GitHubApps.App;
import vn.vibefix.github.GitHubApps.InstallationToken;
import vn.vibefix.github.GitHubWebhookReceived;
import vn.vibefix.github.internal.GitHubStub;
import vn.vibefix.github.internal.GitHubStub.MergeInstallation;
import vn.vibefix.github.internal.TestKeys;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.job.ClosureProposalChanged;
import vn.vibefix.job.JobTransitioned;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.timers.internal.DeadlinePoller;

/**
 * Story 8.7 (FR-37, FR-67, NFR-5, AD-10): a Code Job's Final Merge authorization opens at the first
 * closure proposal, gets its FR-37 deadline at J8, and binds to the pair (Merge App installation, Source
 * Repository) only after AD-10's checks: a setup return with its single-use state, or an installation
 * event whose requester is the Customer. A wider selection is refused, an install request is followed
 * until it is approved or vanishes, a deleted installation returns the authorization to REQUESTED, the
 * deadline ends what was never bound, every Final Merge token is narrowed to the Source Repository, and
 * an installation nothing needs is removed. GitHub is {@link GitHubStub}.
 */
class FinalMergeAuthorizationIT extends IntegrationTest {

	private static final Path DIRECTORY;

	private static final GitHubStub GITHUB;

	private static final AtomicLong IDS = new AtomicLong(880000);

	static {
		try {
			DIRECTORY = Files.createTempDirectory("final-merge-");
			GITHUB = new GitHubStub(Files.createDirectories(DIRECTORY.resolve("git")));
			Files.writeString(DIRECTORY.resolve("app-key"), TestKeys.pkcs1());
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private TransactionTemplate transactions;

	@Autowired
	private ApplicationEventPublisher events;

	@Autowired
	private FinalMergeAuthorizations authorizations;

	@Autowired
	private MergeAppBindings bindings;

	@Autowired
	private FinalMergeTokens tokens;

	@Autowired
	private DeadlinePoller poller;

	private UUID lan;

	private long lanGithub;

	private UUID job;

	private long repository;

	private String owner;

	private long ownerId;

	@DynamicPropertySource
	static void github(DynamicPropertyRegistry registry) {
		registry.add("vibefix.github.api-url", () -> GITHUB.apiUrl().toString());
		registry.add("vibefix.github.git-url", () -> GITHUB.gitUrl().toString());
		registry.add("vibefix.github.merge-app.id", () -> "654321");
		registry.add("vibefix.github.merge-app.key-file", () -> DIRECTORY.resolve("app-key").toString());
	}

	@AfterAll
	static void stop() {
		GITHUB.close();
	}

	@BeforeEach
	void anActiveCodeJob() {
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		this.lanGithub = this.jdbc.sql("SELECT github_user_id FROM identity.account WHERE id = :id").param("id", this.lan)
			.query(Long.class).single();
		UUID mentor = IdentityTestSupport.insertAccount(this.jdbc, Role.MENTOR, null);
		this.repository = IDS.incrementAndGet();
		this.ownerId = IDS.incrementAndGet();
		this.owner = "quan-may-" + this.ownerId;
		UUID product = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO product.product (id, customer_account_id, name, description, ai_tools, repository_github_id,
				    repository_full_name, repository_owner_type, repository_default_branch, public_notice_version,
				    copy_permission_version, detection_notice_version, acknowledged_at, created_at)
				VALUES (:id, :owner, 'Sổ đặt bàn Quán Mây', 'Đặt bàn.', ARRAY['LOVABLE'], :github, :name, 'Organization', 'main',
				    '1.0', '1.0', '1.0', now(), now())
				""").param("id", product).param("owner", this.lan).param("github", this.repository)
			.param("name", this.owner + "/so-dat-ban").update();
		this.job = UUID.randomUUID();
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, budget, deadline, rules, selection_days, opened_at, lapses_at, mentor_account_id,
				    selected_application_id, selected_at, version, created_at, updated_at)
				VALUES (:id, :product, :owner, 'ACTIVE', 'Sửa lỗi đặt bàn', 'Lỗi', 'Đúng', 'Khách', ARRAY['BUG_FIX'],
				    'UNDER_2M', DATE '2026-12-01', '{"FINAL_MERGE_AUTHORIZATION_DAYS":5}'::jsonb, 14, :now, :now, :mentor,
				    :application, :now, 5, :now, :now)
				""").param("id", this.job).param("product", product).param("owner", this.lan).param("mentor", mentor)
			.param("application", UUID.randomUUID()).param("now", now).update();
	}

	@Test
	void theFirstProposalOpensItAndJ8SetsItsDeadlineTheReminderAndThePendingNotice() {
		publish(new ClosureProposalChanged(UUID.randomUUID(), this.job, UUID.randomUUID(), ClosureProposalChanged.Status.OPEN,
				true));
		await().atMost(Duration.ofSeconds(15)).until(() -> authorizationStatus().equals("REQUESTED"));
		publish(new ClosureProposalChanged(UUID.randomUUID(), this.job, UUID.randomUUID(), ClosureProposalChanged.Status.OPEN,
				false));
		assertThat(openTodos()).isOne();

		this.jdbc.sql("UPDATE job.job SET state = 'CLOSING' WHERE id = :id").param("id", this.job).update();
		publish(new JobTransitioned(UUID.randomUUID(), UUID.randomUUID(), this.job, "J8", "USER", "ACTIVE", "CLOSING", 6, 1,
				Map.of()));
		await().atMost(Duration.ofSeconds(15)).until(() -> this.jdbc
			.sql("SELECT deadline_at IS NOT NULL FROM repo.final_merge_authorization WHERE job_id = :job")
			.param("job", this.job).query(Boolean.class).single());

		assertThat(this.jdbc.sql("SELECT count(*) FROM repo.final_merge_authorization WHERE job_id = :job")
			.param("job", this.job).query(Long.class).single()).as("one per Job").isOne();
		assertThat(this.jdbc.sql("SELECT deadline_at FROM repo.final_merge_authorization WHERE job_id = :job")
			.param("job", this.job).query(Timestamp.class).single().toInstant())
			.as("the Job's copied 5 days").isEqualTo(this.clock.instant().plus(Duration.ofDays(5)));
		assertThat(this.jdbc.sql("""
				SELECT kind FROM timers.deadline WHERE job_id = :job AND kind LIKE 'repo.final_merge_%' ORDER BY due_at
				""").param("job", this.job).query(String.class).list())
			.containsExactly("repo.final_merge_reminder", "repo.final_merge_deadline");
		assertThat(notifications("repo.final_merge_authorization_pending")).isOne();
		assertThat(this.jdbc.sql("""
				SELECT deadline_at IS NOT NULL FROM notification.todo WHERE todo_key = 'repo.authorize_final_merge'
				    AND account_id = :id AND closed_at IS NULL
				""").param("id", this.lan).query(Boolean.class).single()).as("the to-do carries the deadline").isTrue();
	}

	@Test
	void aDeclinedProposalEndsItWhileTheJobIsActive() {
		this.authorizations.open(this.job);
		publish(new ClosureProposalChanged(UUID.randomUUID(), this.job, UUID.randomUUID(),
				ClosureProposalChanged.Status.DECLINED, false));
		await().atMost(Duration.ofSeconds(15)).until(() -> authorizationStatus().equals("ENDED"));
		assertThat(this.jdbc.sql("SELECT end_reason FROM repo.final_merge_authorization WHERE job_id = :job")
			.param("job", this.job).query(String.class).single()).isEqualTo("PROPOSAL_ENDED");
		assertThat(openTodos()).isZero();
	}

	@Test
	void theSetupReturnBindsTheSourceRepositoryOnlyAndRefusesAWiderSelection() throws Exception {
		this.authorizations.open(this.job);
		String state = installState();
		long all = IDS.incrementAndGet();
		MergeInstallation everything = new MergeInstallation(this.ownerId, this.owner);
		everything.all = true;
		GITHUB.mergeInstallations.put(all, everything);

		this.mvc.perform(get("/api/v1/github/merge-app/setup").param("installation_id", String.valueOf(all))
			.param("setup_action", "install").param("state", state).with(as(this.lan, Role.CUSTOMER)))
			.andExpect(status().isSeeOther())
			.andExpect(header().string("Location", "/cong-viec/" + this.job));
		assertThat(authorizationStatus()).isEqualTo("REQUESTED");
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/final-merge").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.refusal").value("MERGE_APP_WRONG_REPOSITORY"));
		this.mvc.perform(get("/api/v1/github/merge-app/setup").param("installation_id", String.valueOf(all))
			.param("state", state).with(as(this.lan, Role.CUSTOMER)))
			.andExpect(status().isSeeOther())
			.andExpect(header().string("Location", "/cong-viec"));

		long extra = IDS.incrementAndGet();
		long wider = IDS.incrementAndGet();
		GITHUB.mergeInstallations.put(wider, new MergeInstallation(this.ownerId, this.owner)
			.repository(this.repository, this.owner + "/so-dat-ban", this.ownerId)
			.repository(extra, this.owner + "/khac", this.ownerId));
		setup(wider, installState());
		assertThat(authorizationStatus()).as("another repository without an authorization").isEqualTo("REQUESTED");

		long exact = IDS.incrementAndGet();
		GITHUB.mergeInstallations.put(exact, new MergeInstallation(this.ownerId, this.owner)
			.repository(this.repository, this.owner + "/so-dat-ban", this.ownerId));
		setup(exact, installState());
		assertThat(authorizationStatus()).isEqualTo("BOUND");
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/final-merge").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.status").value("BOUND"))
			.andExpect(jsonPath("$.repository").value(this.owner + "/so-dat-ban"))
			.andExpect(jsonPath("$.refusal").doesNotExist());
		UUID other = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/final-merge").with(as(other, Role.CUSTOMER)))
			.andExpect(status().isNotFound());
	}

	@Test
	void anOwnersLaterApprovalBindsThroughTheEventOnlyForTheCustomersRequest() throws Exception {
		this.authorizations.open(this.job);
		GITHUB.installationRequest(IDS.incrementAndGet(), this.ownerId, this.owner, this.lanGithub);
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/final-merge").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.requestWaiting").value(true))
			.andExpect(jsonPath("$.requestOrg").value(this.owner));

		long installation = IDS.incrementAndGet();
		GITHUB.mergeInstallations.put(installation, new MergeInstallation(this.ownerId, this.owner)
			.repository(this.repository, this.owner + "/so-dat-ban", this.ownerId));
		publish(webhook("installation", "created", installation, IDS.incrementAndGet(), IDS.incrementAndGet()));
		Thread.sleep(500);
		assertThat(authorizationStatus()).as("someone else's request binds nothing").isEqualTo("REQUESTED");

		publish(webhook("installation", "created", installation, IDS.incrementAndGet(), this.lanGithub));
		await().atMost(Duration.ofSeconds(15)).until(() -> authorizationStatus().equals("BOUND"));
		assertThat(this.jdbc.sql("""
				SELECT params ->> 'org' FROM notification.notification WHERE type = 'repo.final_merge_authorized' AND account_id = :id
				""").param("id", this.lan).query(String.class).single()).isEqualTo(this.owner);

		publish(webhook("installation", "deleted", installation, this.ownerId, null));
		await().atMost(Duration.ofSeconds(15)).until(() -> authorizationStatus().equals("REQUESTED"));
	}

	@Test
	void aRequestThatVanishesWithNothingBoundCountsAsRefused() throws Exception {
		this.authorizations.open(this.job);
		GITHUB.installationRequest(IDS.incrementAndGet(), this.ownerId, this.owner, this.lanGithub);
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/final-merge").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.requestWaiting").value(true));
		GITHUB.installationRequests.clear();

		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/final-merge").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.status").value("REQUESTED"))
			.andExpect(jsonPath("$.requestWaiting").value(false))
			.andExpect(jsonPath("$.requestRefused").value(true));
		assertThat(notifications("repo.merge_app_request_refused")).isOne();
	}

	@Test
	void theDeadlineEndsAnAuthorizationNeverBound() {
		this.authorizations.open(this.job);
		this.jdbc.sql("UPDATE job.job SET state = 'CLOSING' WHERE id = :id").param("id", this.job).update();
		this.authorizations.closing(this.job);
		this.clock.advance(Duration.ofDays(3).plusMinutes(1));
		this.poller.runDue();
		assertThat(notifications("repo.final_merge_authorization_reminder")).isOne();
		this.clock.advance(Duration.ofDays(2));
		this.poller.runDue();

		assertThat(authorizationStatus()).isEqualTo("ENDED");
		assertThat(this.jdbc.sql("SELECT end_reason FROM repo.final_merge_authorization WHERE job_id = :job")
			.param("job", this.job).query(String.class).single()).isEqualTo("DEADLINE");
		assertThat(openTodos()).isZero();
		// Story 8.9: the ZIP Fallback starts, saying nothing was authorized in time.
		await().atMost(Duration.ofSeconds(15)).until(() -> this.jdbc
			.sql("SELECT count(*) FROM repo.zip_fallback WHERE job_id = :job").param("job", this.job).query(Long.class)
			.single() == 1);
		assertThat(this.jdbc.sql("SELECT reason FROM repo.zip_fallback WHERE job_id = :job").param("job", this.job)
			.query(String.class).single()).isEqualTo("NOT_AUTHORIZED");
	}

	@Test
	void everyTokenIsNarrowedToTheSourceRepositoryAndAWiderSelectionGetsNone() throws Exception {
		this.authorizations.open(this.job);
		long installation = IDS.incrementAndGet();
		MergeInstallation installed = new MergeInstallation(this.ownerId, this.owner)
			.repository(this.repository, this.owner + "/so-dat-ban", this.ownerId);
		GITHUB.mergeInstallations.put(installation, installed);
		setup(installation, installState());
		UUID authorization = this.authorizations.openOf(this.job).orElseThrow().id();

		InstallationToken token = this.tokens.mint(authorization, Map.of("contents", "write", "pull_requests", "write"))
			.orElseThrow();
		assertThat(GITHUB.grantOf(token.value()).path("repository_ids").toString()).isEqualTo("[" + this.repository + "]");
		assertThat(token.repositoryIds()).containsExactly(this.repository);

		installed.repository(IDS.incrementAndGet(), this.owner + "/khac", this.ownerId);
		assertThat(this.tokens.mint(authorization, Map.of("contents", "write"))).isEmpty();
		assertThat(notifications("repo.merge_app_selection_widened")).isOne();
	}

	@Test
	void theReconciliationRemovesAnInstallationNoOpenAuthorizationNeeds() throws Exception {
		this.authorizations.open(this.job);
		long installation = IDS.incrementAndGet();
		GITHUB.mergeInstallations.put(installation, new MergeInstallation(this.ownerId, this.owner)
			.repository(this.repository, this.owner + "/so-dat-ban", this.ownerId));
		setup(installation, installState());
		this.bindings.reconcile();
		assertThat(GITHUB.removedInstallations).as("still needed").doesNotContain(installation);

		UUID authorization = this.authorizations.openOf(this.job).orElseThrow().id();
		this.authorizations.end(authorization, "CANCELLED");
		this.bindings.reconcile();
		assertThat(GITHUB.removedInstallations).contains(installation);
		assertThat(this.jdbc.sql("SELECT status FROM repo.merge_installation WHERE installation_id = :id")
			.param("id", installation).query(String.class).single()).isEqualTo("REMOVED");
	}

	private String installState() throws Exception {
		String url = JsonPath.read(this.mvc.perform(post("/api/v1/jobs/" + this.job + "/final-merge/install-link")
			.with(as(this.lan, Role.CUSTOMER)).with(csrf())).andExpect(status().isOk()).andReturn().getResponse()
			.getContentAsString(), "$.url");
		assertThat(url).contains("/apps/").contains("/installations/new?state=");
		return url.substring(url.indexOf("state=") + "state=".length());
	}

	private void setup(long installation, String state) throws Exception {
		this.mvc.perform(get("/api/v1/github/merge-app/setup").param("installation_id", String.valueOf(installation))
			.param("setup_action", "install").param("state", state).with(as(this.lan, Role.CUSTOMER)))
			.andExpect(status().isSeeOther());
	}

	private GitHubWebhookReceived webhook(String event, String action, long installation, long sender, Long requester) {
		return new GitHubWebhookReceived(UUID.randomUUID(), UUID.randomUUID().toString(), App.MERGE, event, action, installation,
				List.of(this.repository), sender, requester);
	}

	private void publish(Object event) {
		this.transactions.executeWithoutResult(status -> this.events.publishEvent(event));
	}

	private String authorizationStatus() {
		return this.jdbc.sql("""
				SELECT status FROM repo.final_merge_authorization WHERE job_id = :job ORDER BY requested_at DESC LIMIT 1
				""").param("job", this.job).query(String.class).optional().orElse("NONE");
	}

	private long openTodos() {
		return this.jdbc.sql("""
				SELECT count(*) FROM notification.todo WHERE todo_key = 'repo.authorize_final_merge' AND account_id = :id
				    AND closed_at IS NULL
				""").param("id", this.lan).query(Long.class).single();
	}

	private long notifications(String type) {
		return this.jdbc.sql("SELECT count(*) FROM notification.notification WHERE type = :type AND account_id = :id")
			.param("type", type).param("id", this.lan).query(Long.class).single();
	}

	private RequestPostProcessor as(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

}
