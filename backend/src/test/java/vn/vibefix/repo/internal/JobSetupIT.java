package vn.vibefix.repo.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import vn.vibefix.github.ActionsRuns;
import vn.vibefix.github.SourcePushed;
import vn.vibefix.github.internal.ActionsExchangeSupport;
import vn.vibefix.github.internal.GitHubStub;
import vn.vibefix.github.internal.TestKeys;
import vn.vibefix.identity.AccountLocked;
import vn.vibefix.identity.internal.AdministratorTestSupport;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.support.TestPeople;
import vn.vibefix.timers.DeadlineRequest;
import vn.vibefix.timers.Deadlines;
import vn.vibefix.timers.internal.DeadlinePoller;
import vn.vibefix.job.CrewMemberRemoved;
import vn.vibefix.job.JobBusy;
import vn.vibefix.job.JobTransitioned;
import vn.vibefix.operations.Lane;
import vn.vibefix.operations.OperationPolicy;
import vn.vibefix.operations.OperationRequest;
import vn.vibefix.operations.internal.OperationWorker;
import vn.vibefix.product.ProductSources;
import vn.vibefix.repo.internal.JobSetups.Setup;
import vn.vibefix.shared.PersonalDataSection;
import vn.vibefix.support.IntegrationTest;

/**
 * Story 7.2 (FR-18, AD-5, AD-11, AD-28) and Story 23.6b (R4, AD-44, AD-46): J6 of a Code Job checks the Source
 * Repository again through the Customer's Source App (the binding, the admin right, {@code vibefix.yml}), reads its
 * default branch's head, and waits, with no busy marker, for the Customer to self-confirm that head (N-32); vibefix
 * scans no code. The confirmation at the current head is recorded and starts the copy under the busy marker: once the
 * organization and the runner read back clean, the private Intermediary and Working Repositories are created and read
 * back (private, features and forking off, Actions disabled), {@code copy} of {@code vibefix.yml} is dispatched in the
 * Customer's Source Repository through their Source App with a grant of the two repositories, its run is waited for,
 * never read on an interval: the Source App's signed {@code workflow_run} delivery, the exchange's refusal or the
 * run's {@code repo.actions_run_timeout} Deadline wakes the copy (Story 24.5a), and both repositories are read back at
 * exactly the confirmed head; there is no fork, no ruleset, and no copy in vibefix's
 * sandbox. A head that moved after the confirmation (the exchange's {@code sha}) asks the Customer again; any other
 * exchange refusal, the run's Actions quota, or a run that keeps failing blocks the start, and so do the Source checks,
 * until the Customer taps "Thử lại". Then (Story 7.3) the Mentor and the active Crew are invited with read; once each
 * accepted with two-factor authentication on, Verify raises them to write, reads it back, and only then applies J7.
 * J8, J10, a locked account, or a Crew change revokes access, read back (Story 7.7); 30 days after the end both
 * repositories are deleted, with nothing archived first (Story 23.10d). While the Job runs, the Source App's signed
 * {@code push}, {@code repository} and installation deliveries warn of Source drift (Story 24.6). GitHub and git
 * hosting are {@link GitHubStub}, which runs no
 * workflow: a test pushes the confirmed head into both repositories as the copy run would, and completes the run. The
 * run's ID token is signed by {@link ActionsExchangeSupport}'s stand-in issuer; the sandbox jobs run in process.
 */
@RecordApplicationEvents
class JobSetupIT extends IntegrationTest {

	private static final String TUNNEL = "https://exchange.example.test";

	private static final Path DIRECTORY;

	private static final GitHubStub GITHUB;

	private static final AtomicLong REPOSITORY_IDS = new AtomicLong(880000);

	private static final String RUNNER = "vibefix-runner";

	/** Where the Customer adds the published workflow (AD-45). */
	private static final String WORKFLOW_PATH = ".github/workflows/vibefix.yml";

	/**
	 * Story 23.11a (R6): a {@code vibefix.yml} that is still accepted but was published before the one-repository
	 * copy. It stands in for the real earlier versions, whose {@code copy} job checks {@code working} against
	 * {@code owner/name}, so an Assessment Job's copy would fail at the run's own input check.
	 */
	private static final String TWO_REPOSITORY_WORKFLOW;

	/** The Source App's webhook secret (Story 24.5a), fake and built at run time. */
	private static final byte[] SOURCE_SECRET;

	/** The Platform App's webhook secret, fake and built at run time: its deliveries are no Source drift (Story 24.6). */
	private static final byte[] PLATFORM_SECRET;

	/** The Platform App's ID. */
	private static final long PLATFORM_APP = 123456;

	static {
		try {
			DIRECTORY = Files.createTempDirectory("job-setup-");
			GITHUB = new GitHubStub(Files.createDirectories(DIRECTORY.resolve("git")));
			Files.writeString(DIRECTORY.resolve("app-key"), TestKeys.pkcs1());
			SOURCE_SECRET = ActionsExchangeSupport.webhookSecret(DIRECTORY.resolve("source-webhook-secret"));
			PLATFORM_SECRET = ActionsExchangeSupport.webhookSecret(DIRECTORY.resolve("platform-webhook-secret"));
			// The stand-in for GitHub Actions' OIDC issuer, whose ID tokens a copy run exchanges (Story 23.4).
			ActionsExchangeSupport.issue(GITHUB);
			TWO_REPOSITORY_WORKFLOW = GitHubStub.published(GitHubStub.CUSTOMER_WORKFLOW)
					+ "# published before the one-repository copy\n";
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private ActionsRuns runs;

	@Autowired
	private Deadlines deadlineSchedule;

	@Autowired
	private vn.vibefix.operations.ExternalOperations operations;

	@Autowired
	private JobSetups setups;

	@Autowired
	private JobBusy busy;

	@Autowired
	private OperationWorker worker;

	@Autowired
	private vn.vibefix.operations.FailedOperations failedOperations;

	@Autowired
	private MockMvc mvc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private ApplicationContext context;

	/** The events published during the test, a Source App push's among them (Story 24.6). */
	@Autowired
	private ApplicationEvents events;

	private UUID customer;

	private UUID mentor;

	private UUID crew;

	@Autowired
	private JobGrants grants;

	@Autowired
	private GrantChecks checks;

	@Autowired
	private DeadlinePoller deadlines;

	@Autowired
	private PlatformGuard guard;

	@Autowired
	private SourceDrift drift;

	@Autowired
	private Revocations revocations;

	@Autowired
	private RepoPersonalData personalData;

	@Autowired
	private org.springframework.transaction.PlatformTransactionManager transactions;

	/** timers' registry of Deadline kinds, one handler each (Story 24.5a). */
	@Autowired
	private List<vn.vibefix.timers.DeadlineHandler> deadlineHandlers;

	private UUID product;

	private UUID job;

	private String source;

	/** The Source Repository's name, without its owner {@code lan-dev}. */
	private String sourceName;

	/** The Source Repository's numeric ID. */
	private long sourceId;

	/** The Customer's Source App installation on the Source Repository (Story 23.5). */
	private long installation;

	@DynamicPropertySource
	static void github(DynamicPropertyRegistry registry) {
		registry.add("vibefix.github.api-url", () -> GITHUB.apiUrl().toString());
		registry.add("vibefix.github.git-url", () -> GITHUB.gitUrl().toString());
		registry.add("vibefix.github.platform-app.id", () -> String.valueOf(PLATFORM_APP));
		registry.add("vibefix.github.platform-app.webhook-secret-file",
				() -> DIRECTORY.resolve("platform-webhook-secret").toString());
		registry.add("vibefix.github.platform-app.installation-id", () -> String.valueOf(GitHubStub.INSTALLATION));
		registry.add("vibefix.github.platform-app.key-file", () -> DIRECTORY.resolve("app-key").toString());
		registry.add("vibefix.github.runner-repository", () -> RUNNER);
		registry.add("vibefix.github.operator-login", () -> "vibefix-operator");
		// Story 23.5: the Source checks read the Source Repository through the Customer's Source App installation.
		registry.add("vibefix.github.source-app.id", () -> "2222");
		registry.add("vibefix.github.source-app.key-file", () -> DIRECTORY.resolve("app-key").toString());
		// Story 24.5a: the Source App's webhook, which tells the copy that its run completed.
		registry.add("vibefix.github.source-app.webhook-secret-file",
				() -> DIRECTORY.resolve("source-webhook-secret").toString());
		// Story 23.6b: the copy is dispatched on the Customer's Actions, which reach the exchange through the dev tunnel.
		registry.add("vibefix.github.exchange.public-url", () -> TUNNEL);
		registry.add("vibefix.github.exchange.issuer", () -> GITHUB.apiUrl().toString());
		// Story 23.11a (R6): the accepted set as this IT needs it -- the two published files, and one earlier version of
		// vibefix.yml, still accepted, whose copy job refuses an empty working, so no Assessment Job may run it.
		registry.add("vibefix.github.exchange.accepted-blobs[0].path", () -> WORKFLOW_PATH);
		registry.add("vibefix.github.exchange.accepted-blobs[0].blob", () -> GitHubStub.blobSha(publishedWorkflow()));
		registry.add("vibefix.github.exchange.accepted-blobs[0].one-repository", () -> "true");
		registry.add("vibefix.github.exchange.accepted-blobs[1].path", () -> WORKFLOW_PATH);
		registry.add("vibefix.github.exchange.accepted-blobs[1].blob", () -> GitHubStub.blobSha(TWO_REPOSITORY_WORKFLOW));
		registry.add("vibefix.github.exchange.accepted-blobs[1].ends-on", () -> "2027-12-31");
		registry.add("vibefix.github.exchange.accepted-blobs[2].path", () -> ".github/workflows/s6-copy.yml");
		registry.add("vibefix.github.exchange.accepted-blobs[2].blob", () -> GitHubStub.blobSha(runnerWorkflow()));
	}

	/** The published {@code vibefix.yml}, the one version whose copy job takes an empty {@code working} (R6). */
	private static String publishedWorkflow() {
		try {
			return GitHubStub.published(GitHubStub.CUSTOMER_WORKFLOW);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static String runnerWorkflow() {
		try {
			return GitHubStub.published(GitHubStub.RUNNER_WORKFLOW);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	@AfterAll
	static void stop() {
		GITHUB.close();
	}

	@BeforeEach
	void aCodeJobStarting() throws Exception {
		this.customer = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		// No linking token is kept: every Source read goes through the Customer's Source App (Stories 23.5 and 23.5b).
		this.mentor = TestPeople.mentor(this.jdbc, "Đỗ Mạnh Hùng", List.of(TestPeople.REACT),
				"React");
		this.crew = TestPeople.student(this.jdbc, "Lê Thảo Vy", true, List.of(TestPeople.REACT), "React",
				LocalDate.parse("2026-12-31"));
		long repository = REPOSITORY_IDS.incrementAndGet();
		String name = "so-dat-ban-" + repository;
		this.source = "lan-dev/" + name;
		this.sourceName = name;
		this.sourceId = repository;
		GITHUB.source("lan-dev", name);
		long owner = githubUserId(this.customer);
		GITHUB.publish("lan-dev", name, repository, owner, true, false);
		// The Customer's Source App on exactly the Source Repository, and the published vibefix.yml on its default branch,
		// in its history too, where a dispatch finds the workflow it runs (Story 23.6b).
		this.installation = GITHUB.installSource("lan-dev", name, repository, owner);
		GITHUB.push("lan-dev", name, "main", ".github/workflows/vibefix.yml", GitHubStub.published(GitHubStub.CUSTOMER_WORKFLOW));
		GITHUB.sourceFiles.put(this.source + ":.github/workflows/vibefix.yml",
				GitHubStub.published(GitHubStub.CUSTOMER_WORKFLOW));
		this.product = UUID.randomUUID();
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO product.product (id, customer_account_id, name, description, ai_tools, repository_github_id,
				    repository_full_name, repository_owner_type, repository_default_branch, public_notice_version,
				    copy_permission_version, detection_notice_version, acknowledged_at, created_at)
				VALUES (:id, :owner, 'Sổ đặt bàn Quán Mây', 'Đặt bàn.', ARRAY['LOVABLE'], :github,
				    :name, 'User', 'main', '1.0', '1.0', '1.0', :now, :now)
				""")
			.param("id", this.product).param("owner", this.customer).param("github", repository).param("name", this.source)
			.param("now", now).update();
		this.job = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, budget, deadline, rules, selection_days, opened_at, lapses_at, version, created_at, updated_at,
				    mentor_account_id, selected_application_id, selected_at, agreement_version_no)
				VALUES (:id, :product, :owner, 'STARTING', 'Sửa lỗi đặt bàn', 'Lỗi', 'Đúng', 'Khách', ARRAY['BUG_FIX'],
				    'UNDER_2M', DATE '2026-12-01', '{"REVISION_ROUNDS":2}'::jsonb, 14, :now, :lapses, 3, :now, :now,
				    :mentor, :application, :now, 1)
				""")
			.param("id", this.job).param("product", this.product).param("owner", this.customer).param("now", now)
			.param("lapses", Timestamp.from(this.clock.instant().plus(Duration.ofDays(14)))).param("mentor", this.mentor)
			.param("application", UUID.randomUUID()).update();
		this.jdbc.sql("""
				INSERT INTO job.crew_row (id, job_id, member_account_id, status, signed_at, activated_at, created_at)
				VALUES (:id, :job, :student, 'ACTIVE', :now, :now, :now)
				""").param("id", UUID.randomUUID()).param("job", this.job).param("student", this.crew).param("now", now).update();
		GITHUB.members.clear();
		GITHUB.withoutTwoFactor.clear();
		GITHUB.pulls.clear();
		GITHUB.closedPulls.clear();
		GITHUB.eventActors.clear();
		GITHUB.repositorySelection = "all";
		GITHUB.runDetails = true;
		GITHUB.runsHidden = false;
		// AD-46: the runner, private with only its published workflow, the only repository whose Actions run.
		GITHUB.runner(RUNNER);
	}

	@Test
	void everyoneAcceptsWithTwoFactorOnThenWriteIsReadBackAndOnlyThenJ7Applies() throws Exception {
		String mentorLogin = TestPeople.login(this.jdbc, this.mentor);
		String crewLogin = TestPeople.login(this.jdbc, this.crew);
		String customerLogin = TestPeople.login(this.jdbc, this.customer);
		setUpTheRepositories();
		this.worker.runDue(Lane.GITHUB_SHORT);

		assertThat(this.grants.ofJob(this.job)).extracting(JobGrants.Grant::status).containsOnly("INVITED");
		assertThat(this.grants.ofJob(this.job)).hasSize(3).allSatisfy(grant -> assertThat(grant.expiresAt())
			.isEqualTo(this.clock.instant().plus(Duration.ofDays(7))));
		// Story 23.7 (R5): the team on the Working Repository, the Customer with read on the Intermediary.
		assertThat(teamGrants()).extracting(JobGrants.Grant::repositoryName)
			.containsOnly(GitHubStub.ORGANIZATION + "/" + JobSetups.workingName(this.job));
		assertThat(customerGrant().repositoryName()).isEqualTo(GitHubStub.ORGANIZATION + "/" + JobSetups.intermediaryName(this.job));
		assertThat(customerGrant().role()).isEqualTo("CUSTOMER");
		assertThat(GITHUB.seen).as("invited with read, like everyone")
			.anyMatch(seen -> seen.method().equals("PUT")
					&& seen.path().equals("/repos/" + GitHubStub.ORGANIZATION + "/" + JobSetups.intermediaryName(this.job)
							+ "/collaborators/" + customerLogin)
					&& seen.body().path("permission").asString("").equals("pull"));
		assertThat(notices("repo.github_invitation_sent")).as("the team's").isEqualTo(2);
		assertThat(this.jdbc.sql("""
				SELECT account_id || ' ' || link FROM notification.notification WHERE type = 'repo.intermediary_invitation_sent'
				""").query(String.class).list()).as("the Customer's own, leading on to GitHub's invitation")
			.containsExactly(this.customer + " /cong-viec/" + this.job + "/kho-trung-gian");
		assertThat(this.jdbc.sql("SELECT link FROM notification.todo WHERE todo_key = 'repo.accept_intermediary_invitation'")
			.query(String.class).single()).isEqualTo("/cong-viec/" + this.job + "/kho-trung-gian");
		assertThat(this.jdbc.sql("SELECT count(*) FROM notification.todo WHERE todo_key = 'repo.accept_github_invitation'")
			.query(Long.class).single()).isEqualTo(2);
		assertThat(this.jdbc.sql("""
				SELECT deadline_at FROM notification.todo
				WHERE todo_key = 'repo.accept_intermediary_invitation' AND account_id = :customer AND closed_at IS NULL
				""").param("customer", this.customer).query(Timestamp.class).single().toInstant())
			.as("the Customer's own to-do, due at the invitation's expiry")
			.isCloseTo(this.clock.instant().plus(Duration.ofDays(7)),
					org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.SECONDS));
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/setup").with(as(this.customer, Role.CUSTOMER)))
			.andExpect(jsonPath("$.steps[4].key").value("INVITE"))
			.andExpect(jsonPath("$.steps[4].state").value("CURRENT"))
			.andExpect(jsonPath("$.people[0].name").value("Đỗ Mạnh Hùng"))
			.andExpect(jsonPath("$.people[1].name").value("@" + crewLogin))
			.andExpect(jsonPath("$.people[1].state").value("INVITED"))
			.andExpect(jsonPath("$.people[1].githubUrl").doesNotExist())
			.andExpect(jsonPath("$.people[2].role").value("CUSTOMER"))
			.andExpect(jsonPath("$.people[2].name").value("@" + customerLogin))
			.andExpect(jsonPath("$.people[2].state").value("INVITED"))
			.andExpect(jsonPath("$.people[2].githubUrl").value(org.hamcrest.Matchers.endsWith("-intermediary/invitations")));
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/setup").with(as(this.crew, Role.STUDENT)))
			.andExpect(jsonPath("$.people[1].name").value("Lê Thảo Vy"))
			.andExpect(jsonPath("$.people[1].githubUrl").value(org.hamcrest.Matchers.endsWith("-working/invitations")))
			.andExpect(jsonPath("$.people[0].githubUrl").doesNotExist())
			.andExpect(jsonPath("$.people[2].role").value("CUSTOMER"))
			.andExpect(jsonPath("$.people[2].name").value(""))
			.andExpect(jsonPath("$.people[2].githubUrl").doesNotExist());

		GITHUB.accept(mentorLogin);
		this.checks.check(this.job);
		assertThat(this.busy.job(this.job).orElseThrow().busy()).as("Verify waits for everyone").isEmpty();
		GITHUB.accept(crewLogin);
		GITHUB.withoutTwoFactor.add(crewLogin.toLowerCase(java.util.Locale.ROOT));
		this.checks.check(this.job);
		assertThat(this.busy.job(this.job).orElseThrow().busy()).as("and for two-factor authentication").isEmpty();
		assertThat(notices("repo.two_factor_required")).isOne();
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/setup").with(as(this.customer, Role.CUSTOMER)))
			.andExpect(jsonPath("$.people[1].state").value("TWO_FACTOR_MISSING"));

		GITHUB.withoutTwoFactor.clear();
		this.checks.check(this.job);
		assertThat(this.busy.job(this.job).orElseThrow().busy()).as("the Customer's pending read holds nothing back (R5)")
			.hasValueSatisfying(marker -> assertThat(marker.purpose()).isEqualTo(JobBusy.Purpose.JOB_VERIFY));
		assertThat(this.busy.job(this.job).orElseThrow().state()).as("not Active before the read-back").isEqualTo("STARTING");
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/setup").with(as(this.mentor, Role.MENTOR)))
			.andExpect(jsonPath("$.steps[4].state").value("DONE"))
			.andExpect(jsonPath("$.people[2].state").value("INVITED"));
		this.worker.runDue(Lane.GITHUB_SHORT);

		assertThat(this.busy.job(this.job).orElseThrow().state()).isEqualTo("ACTIVE");
		assertThat(this.busy.job(this.job).orElseThrow().busy()).isEmpty();
		assertThat(teamGrants()).extracting(JobGrants.Grant::status).containsOnly("WRITE");
		assertThat(customerGrant().status()).as("never raised, never waited for").isEqualTo("INVITED");
		assertThat(notices("job.job_started")).as("the Customer, the Mentor, and the Crew").isEqualTo(3);
		assertThat(this.jdbc.sql("SELECT count(*) FROM audit.audit_entry WHERE action = 'repo.grant_raised' AND job_id = :job")
			.param("job", this.job).query(Long.class).single()).isEqualTo(2);

		// After J7 the Customer accepts: ACCEPTED is their settled state, read only, and their to-do closes.
		GITHUB.accept(customerLogin);
		this.checks.hinted(new vn.vibefix.github.GitHubWebhookReceived(UUID.randomUUID(), UUID.randomUUID().toString(),
				vn.vibefix.github.GitHubApps.App.PLATFORM, "member", "added", 7L, List.of(customerGrant().repositoryId()), 1L,
				null));
		this.worker.runDue(Lane.GITHUB_SHORT);
		assertThat(customerGrant().status()).isEqualTo("ACCEPTED");
		assertThat(GITHUB.seen).as("the Customer is never raised on the Intermediary")
			.noneMatch(seen -> seen.method().equals("PUT") && seen.path().endsWith("/collaborators/" + customerLogin)
					&& seen.body().path("permission").asString("").equals("push"));
		assertThat(this.grants.unsettledJobs()).as("settled at ACCEPTED").doesNotContain(this.job);
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM notification.todo
				WHERE todo_key = 'repo.accept_intermediary_invitation' AND account_id = :customer AND closed_at IS NULL
				""").param("customer", this.customer).query(Long.class).single()).isZero();
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM audit.audit_entry e
				WHERE e.action = 'repo.grant_accepted' AND e.job_id = :job
				""").param("job", this.job).query(Long.class).single()).as("the Customer's acceptance, beside the team's")
			.isEqualTo(3);
		assertThat(sweep()).as("the Customer's read collaborator on the Intermediary is expected").isEmpty();

		// The Customer's two-factor authentication off: audited only, never told, and their row reads its status.
		GITHUB.withoutTwoFactor.add(customerLogin.toLowerCase(java.util.Locale.ROOT));
		this.checks.check(this.job);
		assertThat(customerGrant().twoFactorMissing()).isTrue();
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM audit.audit_entry e JOIN audit.audit_entry_object o ON o.entry_id = e.id
				WHERE e.action = 'repo.grant_two_factor_missing' AND o.object_id = :grant
				""").param("grant", customerGrant().id()).query(Long.class).single()).isOne();
		assertThat(notices("repo.two_factor_required")).as("the Crew member's, earlier, only").isOne();
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/setup").with(as(this.customer, Role.CUSTOMER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.people[2].role").value("CUSTOMER"))
			.andExpect(jsonPath("$.people[2].state").value("ACCEPTED"));
		GITHUB.withoutTwoFactor.clear();
		awaitListeners();
	}

	/** Story 21.6: the member Verify waits for leaves, so the sweep starts it and J7 lands with the Mentor alone. */
	@Test
	void aMemberHoldingVerifyBackLeavesAndJ7LandsWithTheMentorAlone() throws Exception {
		setUpTheRepositories();
		this.worker.runDue(Lane.GITHUB_SHORT);
		GITHUB.accept(TestPeople.login(this.jdbc, this.mentor));
		this.checks.check(this.job);
		assertThat(this.busy.job(this.job).orElseThrow().busy()).as("Verify waits for the member").isEmpty();

		leave().andExpect(status().isNoContent());
		awaitListeners();
		assertThat(grantStatus(this.crew)).isEqualTo("REVOKING");

		this.checks.check(this.job);
		assertThat(this.busy.job(this.job).orElseThrow().busy()).hasValueSatisfying(
				marker -> assertThat(marker.purpose()).isEqualTo(JobBusy.Purpose.JOB_VERIFY));
		this.worker.runDue(Lane.GITHUB_SHORT);

		assertThat(this.busy.job(this.job).orElseThrow().state()).isEqualTo("ACTIVE");
		assertThat(grantStatus(this.mentor)).isEqualTo("WRITE");
		assertThat(grantStatus(this.crew)).isEqualTo("REVOKED");
		assertThat(notices("job.job_started")).as("the Customer and the Mentor").isEqualTo(2);
		awaitListeners();
	}

	/**
	 * Story 21.6: on a person no longer a collaborator a raise is an invitation nothing removes, so Verify reads the grant
	 * again just before raising, and tries again without them.
	 */
	@Test
	void aMemberWhoLeavesWhileVerifyRaisesIsNotInvitedBack() throws Exception {
		String mentorLogin = TestPeople.login(this.jdbc, this.mentor);
		String crewLogin = TestPeople.login(this.jdbc, this.crew);
		setUpTheRepositories();
		this.worker.runDue(Lane.GITHUB_SHORT);
		GITHUB.accept(mentorLogin);
		GITHUB.accept(crewLogin);
		this.checks.check(this.job);
		assertThat(this.busy.job(this.job).orElseThrow().busy()).isPresent();
		GITHUB.seen.clear();
		AtomicBoolean left = new AtomicBoolean();
		GITHUB.beforeAnswer = request -> {
			if (request.path().endsWith("/collaborators/" + mentorLogin + "/permission") && left.compareAndSet(false, true)) {
				try {
					leave().andExpect(status().isNoContent());
					awaitListeners();
				}
				catch (Exception failed) {
					throw new IllegalStateException(failed);
				}
			}
		};
		try {
			this.worker.runDue(Lane.GITHUB_SHORT);
		}
		finally {
			GITHUB.beforeAnswer = request -> {
			};
		}
		assertThat(left).isTrue();
		assertThat(this.busy.job(this.job).orElseThrow().state()).as("it tries again").isEqualTo("STARTING");

		this.clock.advance(Duration.ofMinutes(1));
		this.worker.runDue(Lane.GITHUB_SHORT);

		assertThat(this.busy.job(this.job).orElseThrow().state()).isEqualTo("ACTIVE");
		assertThat(grantStatus(this.crew)).isEqualTo("REVOKED");
		assertThat(GITHUB.seen).noneMatch(request -> "PUT".equals(request.method())
				&& request.path().endsWith("/collaborators/" + crewLogin));
		assertThat(GITHUB.seen).anyMatch(request -> "PUT".equals(request.method())
				&& request.path().endsWith("/collaborators/" + mentorLogin));
		awaitListeners();
	}

	private org.springframework.test.web.servlet.ResultActions leave() throws Exception {
		return this.mvc.perform(post("/api/v1/jobs/" + this.job + "/crew/leave").with(as(this.crew, Role.STUDENT)).with(csrf())
			.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
			.content("{}"));
	}

	@Test
	void aMemberACrewChangeAddsToAnActiveJobIsInvitedAndRaisedOnAcceptanceWithinThreeRenewals() throws Exception {
		AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		setUpTheRepositories();
		this.worker.runDue(Lane.GITHUB_SHORT);
		GITHUB.accept(TestPeople.login(this.jdbc, this.mentor));
		GITHUB.accept(TestPeople.login(this.jdbc, this.crew));
		this.checks.check(this.job);
		this.worker.runDue(Lane.GITHUB_SHORT);
		assertThat(this.busy.job(this.job).orElseThrow().state()).isEqualTo("ACTIVE");
		UUID hai = TestPeople.student(this.jdbc, "Phạm Văn Hải", true, List.of(TestPeople.REACT), "React",
				LocalDate.parse("2026-12-31"));
		UUID row = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO job.crew_row (id, job_id, member_account_id, status, signed_at, activated_at, created_at)
				VALUES (:id, :job, :student, 'ACTIVE', :now, :now, :now)
				""").param("id", row).param("job", this.job).param("student", hai)
			.param("now", Timestamp.from(this.clock.instant())).update();

		this.checks.added(new vn.vibefix.job.CrewMemberAdded(UUID.randomUUID(), this.job, row, hai, "ACTIVE"));
		this.worker.runDue(Lane.GITHUB_SHORT);
		JobGrants.Grant added = this.grants.ofJob(this.job).stream().filter(grant -> grant.accountId().equals(hai))
			.findFirst().orElseThrow();
		assertThat(added.status()).isEqualTo("INVITED");
		assertThat(added.crewChange()).isTrue();

		GITHUB.accept(TestPeople.login(this.jdbc, hai));
		long repository = added.repositoryId();
		this.checks.hinted(new vn.vibefix.github.GitHubWebhookReceived(UUID.randomUUID(), UUID.randomUUID().toString(),
				vn.vibefix.github.GitHubApps.App.PLATFORM, "member", "added", 7L, List.of(repository), 1L, null));
		this.worker.runDue(Lane.GITHUB_SHORT);

		assertThat(this.grants.find(added.id()).orElseThrow().status()).as("raised without a state change").isEqualTo("WRITE");
		assertThat(this.busy.job(this.job).orElseThrow().state()).isEqualTo("ACTIVE");

		UUID late = TestPeople.student(this.jdbc, "Ngô Minh", true, List.of(TestPeople.REACT), "React",
				LocalDate.parse("2026-12-31"));
		UUID lateRow = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO job.crew_row (id, job_id, member_account_id, status, signed_at, activated_at, created_at)
				VALUES (:id, :job, :student, 'ACTIVE', :now, :now, :now)
				""").param("id", lateRow).param("job", this.job).param("student", late)
			.param("now", Timestamp.from(this.clock.instant())).update();
		this.checks.added(new vn.vibefix.job.CrewMemberAdded(UUID.randomUUID(), this.job, lateRow, late, "ACTIVE"));
		this.worker.runDue(Lane.GITHUB_SHORT);
		for (int renewal = 0; renewal < JobGrants.CREW_CHANGE_RENEWALS; renewal++) {
			this.clock.advance(Duration.ofDays(7).plusMinutes(1));
			this.deadlines.runDue();
			this.worker.runDue(Lane.GITHUB_SHORT);
		}
		this.clock.advance(Duration.ofDays(7).plusMinutes(1));
		this.deadlines.runDue();

		assertThat(this.grants.ofJob(this.job).stream().filter(grant -> grant.accountId().equals(late)).findFirst()
			.orElseThrow().renewals()).isEqualTo(JobGrants.CREW_CHANGE_RENEWALS);
		assertThat(notices("repo.invitation_renewals_exhausted")).as("the Mentor and the Administrator").isEqualTo(2);
		awaitListeners();
	}

	/**
	 * Story 21.5 (FR-18, AD-11, SG-67): a member who signs while the Job is Starting, after its Setup, is invited with
	 * read permission as a late member, and Verify never waits for them: J7 applies once the Mentor and the first
	 * Crew accepted, and the late member is raised to write on their own acceptance, a consequence that changes no
	 * Job state.
	 */
	@Test
	void aMemberWhoJoinsAStartingJobAfterSetupIsGrantedApartAndVerifyNeverWaitsForThem() throws Exception {
		setUpTheRepositories();
		this.worker.runDue(Lane.GITHUB_SHORT);
		this.clock.advance(Duration.ofMinutes(1));
		UUID hai = TestPeople.student(this.jdbc, "Phạm Văn Hải", true, List.of(TestPeople.REACT), "React",
				LocalDate.parse("2026-12-31"));
		UUID row = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO job.crew_row (id, job_id, member_account_id, status, signed_at, activated_at, created_at)
				VALUES (:id, :job, :student, 'ACTIVE', :now, :now, :now)
				""").param("id", row).param("job", this.job).param("student", hai)
			.param("now", Timestamp.from(this.clock.instant())).update();

		this.checks.added(new vn.vibefix.job.CrewMemberAdded(UUID.randomUUID(), this.job, row, hai, "STARTING"));
		this.worker.runDue(Lane.GITHUB_SHORT);
		JobGrants.Grant late = this.grants.ofJob(this.job).stream().filter(grant -> grant.accountId().equals(hai))
			.findFirst().orElseThrow();
		assertThat(late.status()).isEqualTo("INVITED");
		assertThat(late.crewChange()).as("a late member's grant").isTrue();

		GITHUB.accept(TestPeople.login(this.jdbc, this.mentor));
		GITHUB.accept(TestPeople.login(this.jdbc, this.crew));
		this.checks.check(this.job);
		assertThat(this.busy.job(this.job).orElseThrow().busy()).as("Verify does not wait for the late member")
			.hasValueSatisfying(marker -> assertThat(marker.purpose()).isEqualTo(JobBusy.Purpose.JOB_VERIFY));
		this.worker.runDue(Lane.GITHUB_SHORT);
		assertThat(this.busy.job(this.job).orElseThrow().state()).isEqualTo("ACTIVE");
		assertThat(this.grants.find(late.id()).orElseThrow().status()).as("still waiting for its acceptance")
			.isEqualTo("INVITED");

		GITHUB.accept(TestPeople.login(this.jdbc, hai));
		this.checks.check(this.job);
		this.worker.runDue(Lane.GITHUB_SHORT);
		assertThat(this.grants.find(late.id()).orElseThrow().status()).as("raised without a state change").isEqualTo("WRITE");
		awaitListeners();
	}

	/**
	 * Story 21.5 (NFR-6): a late member who accepts while the Job is Starting holds no write yet. No raise is requested,
	 * since its operation would end obsolete and its fixed key would never run again; J7 applies, and the next look raises
	 * them.
	 */
	@Test
	void aLateMemberWhoAcceptsWhileStartingIsRaisedAfterJ7() throws Exception {
		setUpTheRepositories();
		this.worker.runDue(Lane.GITHUB_SHORT);
		this.clock.advance(Duration.ofMinutes(1));
		UUID hai = activeMember();
		this.checks.added(new vn.vibefix.job.CrewMemberAdded(UUID.randomUUID(), this.job, UUID.randomUUID(), hai, "STARTING"));
		this.worker.runDue(Lane.GITHUB_SHORT);
		JobGrants.Grant late = this.grants.ofJob(this.job).stream().filter(grant -> grant.accountId().equals(hai))
			.findFirst().orElseThrow();

		GITHUB.accept(TestPeople.login(this.jdbc, hai));
		this.checks.check(this.job);
		this.worker.runDue(Lane.GITHUB_SHORT);

		assertThat(this.grants.find(late.id()).orElseThrow().status()).isEqualTo("ACCEPTED");
		assertThat(this.jdbc.sql("SELECT count(*) FROM operations.external_operation WHERE type = :type")
			.param("type", JobGrants.RAISE).query(Long.class).single()).as("no raise before J7").isZero();

		GITHUB.accept(TestPeople.login(this.jdbc, this.mentor));
		GITHUB.accept(TestPeople.login(this.jdbc, this.crew));
		this.checks.check(this.job);
		this.worker.runDue(Lane.GITHUB_SHORT);
		assertThat(this.busy.job(this.job).orElseThrow().state()).isEqualTo("ACTIVE");
		assertThat(this.grants.find(late.id()).orElseThrow().status()).as("J7 raises those Verify waited for").isEqualTo("ACCEPTED");

		this.checks.sweep();
		this.worker.runDue(Lane.GITHUB_SHORT);
		assertThat(this.grants.find(late.id()).orElseThrow().status()).isEqualTo("WRITE");
		awaitListeners();
	}

	/** Story 21.5: "Verify never waits for a member added after Setup", a late grant that drifted included. */
	@Test
	void aLateMembersDriftDoesNotHoldVerifyBackButAFirstMembersDoes() throws Exception {
		setUpTheRepositories();
		this.worker.runDue(Lane.GITHUB_SHORT);
		this.clock.advance(Duration.ofMinutes(1));
		UUID hai = activeMember();
		GITHUB.members.add(TestPeople.login(this.jdbc, hai));
		this.checks.added(new vn.vibefix.job.CrewMemberAdded(UUID.randomUUID(), this.job, UUID.randomUUID(), hai, "STARTING"));
		this.worker.runDue(Lane.GITHUB_SHORT);
		assertThat(grantStatus(hai)).isEqualTo("MEMBER_DRIFT");

		GITHUB.accept(TestPeople.login(this.jdbc, this.mentor));
		GITHUB.accept(TestPeople.login(this.jdbc, this.crew));
		this.checks.check(this.job);
		assertThat(grantStatus(hai)).as("still a member of the organization").isEqualTo("MEMBER_DRIFT");
		assertThat(this.busy.job(this.job).orElseThrow().busy()).as("Verify does not wait for the late member")
			.hasValueSatisfying(marker -> assertThat(marker.purpose()).isEqualTo(JobBusy.Purpose.JOB_VERIFY));
		String verify = this.busy.job(this.job).orElseThrow().busy().orElseThrow().operationKey();
		new org.springframework.transaction.support.TransactionTemplate(this.transactions)
			.executeWithoutResult(status -> this.busy.release(this.job, verify));

		this.jdbc.sql("UPDATE repo.job_grant SET status = 'MEMBER_DRIFT' WHERE job_id = :job AND account_id = :crew")
			.param("job", this.job).param("crew", this.crew).update();
		JobSetups.Setup setup = this.setups.latest(this.job).orElseThrow();
		assertThat(this.grants.maybeVerify(this.job, setup)).as("a first member's drift still holds Verify").isFalse();
		awaitListeners();
	}

	/**
	 * Story 21.5: a signature committed during Setup's resolving transaction: its listener found Setup not done, and
	 * grantAll had read the Crew without them. The next look grants them as a late member, and Verify does not wait.
	 */
	@Test
	void aMemberWithNoGrantGetsOneOnTheNextLookAndVerifyDoesNotWait() throws Exception {
		setUpTheRepositories();
		this.worker.runDue(Lane.GITHUB_SHORT);
		UUID hai = activeMember();
		assertThat(this.grants.ofJob(this.job)).noneMatch(grant -> grant.accountId().equals(hai));

		this.checks.check(this.job);
		this.worker.runDue(Lane.GITHUB_SHORT);

		JobGrants.Grant late = this.grants.ofJob(this.job).stream().filter(grant -> grant.accountId().equals(hai))
			.findFirst().orElseThrow();
		assertThat(late.status()).isEqualTo("INVITED");
		assertThat(late.crewChange()).as("a late member's grant").isTrue();

		this.checks.check(this.job);
		assertThat(this.grants.ofJob(this.job).stream().filter(grant -> grant.accountId().equals(hai)).count())
			.as("an existing grant stays").isOne();
		GITHUB.accept(TestPeople.login(this.jdbc, this.mentor));
		GITHUB.accept(TestPeople.login(this.jdbc, this.crew));
		this.checks.check(this.job);
		assertThat(this.busy.job(this.job).orElseThrow().busy()).as("Verify does not wait for them")
			.hasValueSatisfying(marker -> assertThat(marker.purpose()).isEqualTo(JobBusy.Purpose.JOB_VERIFY));
		this.worker.runDue(Lane.GITHUB_SHORT);
		assertThat(this.busy.job(this.job).orElseThrow().state()).isEqualTo("ACTIVE");
		awaitListeners();
	}

	/**
	 * Story 23.3 (FR-19, AD-12): a foreign pull request is closed on the Intermediary only, since the private
	 * Working Repository is the team's; a {@code pull_request} or {@code member} event by anyone but the bot or a
	 * grantee alerts, and {@code push} is no longer a privileged event.
	 */
	@Test
	void foreignPullRequestsAreClosedOnTheIntermediaryAndOnlyTheSystemAndGranteesActWithoutAnAlert() throws Exception {
		AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		setUpTheRepositories();
		String intermediary = JobSetups.intermediaryName(this.job);
		String working = JobSetups.workingName(this.job);
		long intermediaryId = GITHUB.organizationRepositoryId(intermediary);
		long workingId = GITHUB.organizationRepositoryId(working);
		long crewId = githubUserId(this.crew);
		String crewLogin = TestPeople.login(this.jdbc, this.crew);
		GITHUB.pulls.put(intermediary, new java.util.LinkedHashMap<>(Map.of(1, "mallory", 2, "vibefix-dev-platform[bot]")));
		GITHUB.pulls.put(working, new java.util.LinkedHashMap<>(Map.of(3, crewLogin)));

		this.guard.hinted(webhook("pull_request", intermediaryId, "mallory"));
		this.guard.hinted(webhook("pull_request", workingId, crewLogin, crewId));

		assertThat(GITHUB.closedPulls).containsExactly(intermediary + "#1");
		assertThat(notices("github.unexpected_actor")).as("a grantee's pull request on the Working Repository is theirs")
			.isOne();
		this.guard.hinted(webhook("member", workingId, crewLogin, crewId));
		assertThat(notices("github.unexpected_actor")).as("a grantee acts freely").isOne();
		this.guard.hinted(webhook("pull_request", workingId, "outsider"));
		assertThat(notices("github.unexpected_actor")).as("a stranger on the private Working Repository alerts").isEqualTo(2);
		assertThat(GITHUB.closedPulls).as("nothing is closed on the Working Repository").containsExactly(intermediary + "#1");
		this.guard.hinted(webhook("member", intermediaryId, crewLogin, crewId));
		assertThat(notices("github.unexpected_actor")).as("nobody but the System is granted on the Intermediary").isEqualTo(3);
		this.guard.hinted(webhook("push", workingId, "mallory"));
		assertThat(notices("github.unexpected_actor")).as("push is no longer a privileged event").isEqualTo(3);
		this.guard.hinted(webhook("repository", workingId, "mallory"));
		assertThat(notices("github.unexpected_actor")).as("a stranger's repository event, such as making it public, alerts")
			.isEqualTo(4);
		this.guard.hinted(webhook("member", intermediaryId, "vibefix-operator"));
		assertThat(notices("github.operator_account_used")).isOne();
		assertThat(this.jdbc.sql("SELECT count(*) FROM audit.audit_entry WHERE action = 'repo.foreign_pull_request_closed'")
			.query(Long.class).single()).isOne();

		// Story 23.7 (AD-12): the Customer counts as granted for member events on their Intermediary only.
		long customerId = githubUserId(this.customer);
		String customerLogin = TestPeople.login(this.jdbc, this.customer);
		this.guard.hinted(webhook("member", intermediaryId, customerLogin, customerId));
		assertThat(notices("github.unexpected_actor")).as("the Customer's acceptance on their Intermediary").isEqualTo(4);
		this.guard.hinted(webhook("pull_request", intermediaryId, customerLogin, customerId));
		assertThat(notices("github.unexpected_actor")).as("but not their pull request there").isEqualTo(5);
		this.guard.hinted(webhook("member", workingId, customerLogin, customerId));
		assertThat(notices("github.unexpected_actor")).as("nor anything on the Working Repository").isEqualTo(6);
		awaitListeners();
	}

	/** Story 23.3: the sweep closes nothing on the private Working Repository, whoever opened the pull request. */
	@Test
	void theSweepClosesNoPullRequestOnTheWorkingRepository() throws Exception {
		setUpTheRepositories();
		String working = JobSetups.workingName(this.job);
		GITHUB.pulls.put(working, new java.util.LinkedHashMap<>(Map.of(7, TestPeople.login(this.jdbc, this.crew), 8, "outsider")));

		sweep();

		assertThat(GITHUB.closedPulls).noneMatch(closed -> closed.startsWith(working + "#"));
		assertThat(GITHUB.seen).as("its pull requests are not even listed")
			.noneMatch(request -> request.method().equals("GET")
					&& request.path().equals("/repos/" + GitHubStub.ORGANIZATION + "/" + working + "/pulls"));
		awaitListeners();
	}

	@Test
	void theSweepReadsThePlatformBackAndAlertsEachDriftOnceADay() throws Exception {
		AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		setUpTheRepositories();
		assertThat(sweep()).as("everything as vibefix expects").isEmpty();
		String intermediary = JobSetups.intermediaryName(this.job);
		String working = JobSetups.workingName(this.job);
		GITHUB.addCollaborator(intermediary, "mallory", "write");
		GITHUB.setting(working, "has_issues", true);
		GITHUB.setting(working, "private", false);
		GITHUB.setting(intermediary, "allow_forking", true);
		GITHUB.actionsRepositories.add(intermediary);
		GITHUB.addCollaborator(RUNNER, "eve", "admin");
		GITHUB.repositorySelection = "selected";
		GITHUB.eventActors.put(intermediary, List.of("vibefix-dev-platform[bot]", "mallory"));

		List<String> differences = sweep();

		assertThat(differences).anyMatch(difference -> difference.contains("collaborators {mallory=write} on the Intermediary"))
			.anyMatch(difference -> difference.equals(GitHubStub.ORGANIZATION + "/" + working + ": has_issues is on"))
			.anyMatch(difference -> difference.equals(GitHubStub.ORGANIZATION + "/" + working + ": public instead of private"))
			.anyMatch(difference -> difference.equals(GitHubStub.ORGANIZATION + "/" + intermediary
					+ ": allow_forking is true instead of false"))
			.anyMatch(difference -> difference.equals(GitHubStub.ORGANIZATION + "/" + intermediary
					+ ": GitHub Actions are enabled instead of disabled"))
			.anyMatch(difference -> difference.equals("GitHub Actions are enabled for vibefix-runner, " + intermediary
					+ " instead of the runner vibefix-runner only"))
			.anyMatch(difference -> difference.equals("the runner vibefix-dev/vibefix-runner has collaborators [eve]"))
			.anyMatch(difference -> difference.contains("repository selection: selected instead of all"))
			.anyMatch(difference -> difference.contains("recent changes by [mallory]"))
			.noneMatch(difference -> difference.contains("ruleset"));
		assertThat(GITHUB.seen).as("no ruleset is read").noneMatch(request -> request.path().contains("/rulesets"));
		assertThat(notices("github.platform_drift")).isOne();
		sweep();
		assertThat(notices("github.platform_drift")).as("once a day for the same differences").isOne();
		awaitListeners();
	}

	/**
	 * AD-12, AD-46: the gate runs in the copy stage, under the marker, before anything is created; a difference waits 15
	 * minutes and looks again.
	 */
	@Test
	void aRepositorySelectionDifferenceHoldsTheCopyBeforeAnythingIsCreated() throws Exception {
		GITHUB.repositorySelection = "selected";
		confirmTheHead();
		run();

		Setup waiting = this.setups.latest(this.job).orElseThrow();
		assertThat(waiting.status()).as("waiting, not failed").isEqualTo("RUNNING");
		assertThat(waiting.confirmedAt()).isNotNull();
		assertThat(waiting.intermediaryAt()).isNull();
		assertThat(waiting.correlationId()).as("nothing was dispatched").isNull();
		assertThat(GITHUB.organizationRepositoryId(JobSetups.intermediaryName(this.job))).as("nothing was created").isNull();
		assertThat(GITHUB.seen).noneMatch(request -> request.method().equals("POST")
				&& request.path().equals("/orgs/" + GitHubStub.ORGANIZATION + "/repos")
				&& request.body().path("name").asString("").equals(JobSetups.intermediaryName(this.job)));
		GITHUB.repositorySelection = "all";
		this.clock.advance(Duration.ofMinutes(16));
		run();
		copyRunSucceeds();

		assertThat(this.setups.latest(this.job).orElseThrow().status()).isEqualTo("DONE");
	}

	/** AD-46, deferred-work 634: a runner that drifts holds the copy before anything is created, until it is fixed. */
	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.ValueSource(strings = { "public", "another workflow", "a collaborator",
			"an invitation", "Actions for all" })
	void aRunnerThatDriftsHoldsTheCopyUntilItIsFixed(String drift) throws Exception {
		switch (drift) {
			case "public" -> GITHUB.setting(RUNNER, "private", false);
			case "another workflow" -> GITHUB.push(GitHubStub.ORGANIZATION, RUNNER, "main", ".github/workflows/ci.yml",
					"on: push\n");
			case "a collaborator" -> GITHUB.addCollaborator(RUNNER, "mallory", "write");
			case "an invitation" -> GITHUB.addInvitation(RUNNER, "mallory", "read");
			default -> GITHUB.actionsEnabled = "all";
		}
		try {
			confirmTheHead();
			run();

			assertThat(this.setups.latest(this.job).orElseThrow().status()).as("waiting, not failed").isEqualTo("RUNNING");
			assertThat(GITHUB.organizationRepositoryId(JobSetups.intermediaryName(this.job))).as("nothing was created").isNull();
		}
		finally {
			if (drift.equals("another workflow")) {
				GITHUB.push(GitHubStub.ORGANIZATION, RUNNER, "main", Map.of(), List.of(".github/workflows/ci.yml"), "Remove ci.yml",
						"ops@example.test");
			}
			GITHUB.runner(RUNNER);
		}
		this.clock.advance(Duration.ofMinutes(16));
		run();
		copyRunSucceeds();

		assertThat(this.setups.latest(this.job).orElseThrow().status()).isEqualTo("DONE");
		awaitListeners();
	}

	/** D1: no copy is dispatched into a repository whose settings read back wrong; the attempt is retried. */
	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.ValueSource(strings = { "intermediary", "working" })
	void aRepositoryThatReadsBackPublicIsNotCopiedIntoUntilItIsPrivate(String which) throws Exception {
		boolean working = which.equals("working");
		String name = working ? JobSetups.workingName(this.job) : JobSetups.intermediaryName(this.job);
		long id = GITHUB.platformRepository(name, "lan-dev", this.sourceName);
		GITHUB.setting(name, "private", false);
		confirmTheHead();
		run();

		Setup waiting = this.setups.latest(this.job).orElseThrow();
		assertThat(waiting.status()).isEqualTo("RUNNING");
		assertThat(working ? waiting.workingId() : waiting.intermediaryId()).as("the earlier repository, found by name")
			.isEqualTo(id);
		if (working) {
			assertThat(waiting.intermediaryAt()).as("the Intermediary was read back").isNotNull();
			assertThat(waiting.workingAt()).as("the Working Repository was not").isNull();
		}
		else {
			assertThat(waiting.intermediaryAt()).as("nothing was read back").isNull();
		}
		assertThat(waiting.correlationId()).as("no copy was dispatched").isNull();
		assertThat(this.jdbc.sql("SELECT status || ':' || error_code FROM operations.external_operation WHERE operation_key = :key")
			.param("key", JobSetups.OPERATION + ":" + waiting.confirmationId())
			.query(String.class).single()).isEqualTo("PENDING:REPOSITORY_SETTINGS_DIFFER");
		assertThat(sourceRuns()).as("nothing ran on the Customer's Actions").isEmpty();

		GITHUB.setting(name, "private", true);
		this.clock.advance(JobSetupOperation.POLICY.delayAfter(1).plusSeconds(1));
		run();
		copyRunSucceeds();

		assertThat(this.setups.latest(this.job).orElseThrow().status()).isEqualTo("DONE");
		awaitListeners();
	}

	/**
	 * FR-21, R17: J8 revokes the Mentor's and the Crew's grants, read back; Story 23.7 (R5): the Customer's read of the
	 * Intermediary stays, and "all revoked" does not count it.
	 */
	@Test
	void j8RevokesEveryTeamGrantAndReadsItBackWhileTheAdministratorsHearOfOneGitHubKeepsRefusing() throws Exception {
		AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		String crewLogin = TestPeople.login(this.jdbc, this.crew);
		active();
		GITHUB.accept(TestPeople.login(this.jdbc, this.customer));
		this.checks.check(this.job);
		assertThat(customerGrant().status()).isEqualTo("ACCEPTED");
		GITHUB.unavailable.add("DELETE /repos/" + GitHubStub.ORGANIZATION + "/" + JobSetups.workingName(this.job)
				+ "/collaborators/" + crewLogin);
		this.jdbc.sql("UPDATE job.job SET state = 'CLOSING' WHERE id = :id").param("id", this.job).update();

		this.revocations.transitioned(new JobTransitioned(UUID.randomUUID(), UUID.randomUUID(), this.job, "J8", "USER",
				"ACTIVE", "CLOSING", 6, 1, Map.of()));
		assertThat(teamGrants()).extracting(JobGrants.Grant::status).containsOnly("REVOKING");
		assertThat(customerGrant().status()).as("the Customer keeps reading the Intermediary").isEqualTo("ACCEPTED");
		this.worker.runDue(Lane.GITHUB_SHORT);

		assertThat(grantStatus(this.mentor)).isEqualTo("REVOKED");
		assertThat(grantStatus(this.crew)).as("GitHub refused; tried again in 30 seconds").isEqualTo("REVOKING");
		assertThat(notices("repo.access_revoked")).isOne();
		this.clock.advance(Revocations.DELAY.plusSeconds(1));
		this.deadlines.runDue();
		assertThat(notices("operations.revocation_delayed")).as("the Administrator, for the Crew member only").isOne();

		GITHUB.unavailable.clear();
		assertThat(this.context.getBean(FinalMerges.class).revoked(this.job)).as("one team grant is still revoking").isFalse();
		this.worker.runDue(Lane.GITHUB_SHORT);

		assertThat(teamGrants()).extracting(JobGrants.Grant::status).containsOnly("REVOKED");
		assertThat(teamGrants()).allSatisfy(grant -> assertThat(this.jdbc
			.sql("SELECT revoked_at FROM repo.job_grant WHERE id = :id").param("id", grant.id()).query(Timestamp.class).single())
			.isNotNull());
		assertThat(customerGrant().status()).isEqualTo("ACCEPTED");
		assertThat(this.context.getBean(FinalMerges.class).revoked(this.job)).as("the Customer's live read is not counted")
			.isTrue();
		assertThat(notices("repo.access_revoked")).as("the team only").isEqualTo(2);
		assertThat(this.jdbc.sql("SELECT count(*) FROM audit.audit_entry WHERE action = 'repo.grant_revoked' AND job_id = :job")
			.param("job", this.job).query(Long.class).single()).as("per person and repository").isEqualTo(2);
		assertThat(sweep()).as("nobody of the team is left, and the Customer reads the Intermediary").isEmpty();
		awaitListeners();
	}

	/**
	 * Story 23.7 (R17, AD-12): after J8 the sweep expects no team grant on the Working Repository, whatever the rows still
	 * say, while the Customer's read on the Intermediary stays expected.
	 */
	@Test
	void afterJ8TheSweepExpectsNoTeamGrantOnTheWorkingRepository() throws Exception {
		AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		active();
		String working = GitHubStub.ORGANIZATION + "/" + JobSetups.workingName(this.job);
		assertThat(sweep()).as("Active: the team writes, the Customer is invited to read").isEmpty();

		// J8 committed, and its revocation has not run: the team's rows still say WRITE.
		this.jdbc.sql("UPDATE job.job SET state = 'CLOSING' WHERE id = :id").param("id", this.job).update();

		assertThat(sweep()).singleElement().asString().startsWith(working + ": collaborators {").endsWith(" instead of {}");
		assertThat(notices("github.platform_drift")).isOne();
	}

	@Test
	void aLockedMemberAndAMemberACrewChangeRemovesLoseTheirAccessAndAPendingInvitationGoes() throws Exception {
		active();
		UUID hai = TestPeople.student(this.jdbc, "Phạm Văn Hải", true, List.of(TestPeople.REACT), "React",
				LocalDate.parse("2026-12-31"));
		UUID row = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO job.crew_row (id, job_id, member_account_id, status, signed_at, activated_at, created_at)
				VALUES (:id, :job, :student, 'ACTIVE', :now, :now, :now)
				""").param("id", row).param("job", this.job).param("student", hai)
			.param("now", Timestamp.from(this.clock.instant())).update();
		this.checks.added(new vn.vibefix.job.CrewMemberAdded(UUID.randomUUID(), this.job, row, hai, "ACTIVE"));
		this.worker.runDue(Lane.GITHUB_SHORT);
		assertThat(grantStatus(hai)).isEqualTo("INVITED");

		this.revocations.locked(new AccountLocked(UUID.randomUUID(), this.crew, this.clock.instant()));
		this.revocations.removed(new CrewMemberRemoved(UUID.randomUUID(), this.job, row, hai));
		this.worker.runDue(Lane.GITHUB_SHORT);

		assertThat(grantStatus(this.crew)).isEqualTo("REVOKED");
		assertThat(grantStatus(hai)).isEqualTo("REVOKED");
		assertThat(grantStatus(this.mentor)).isEqualTo("WRITE");
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM notification.todo
				WHERE todo_key = 'repo.accept_github_invitation' AND account_id = :account AND closed_at IS NULL
				""").param("account", hai).query(Long.class).single()).isZero();
		assertThat(customerGrant().status()).as("a member's lock or leaving revokes only their own").isEqualTo("INVITED");
		assertThat(sweep()).as("no collaborator or invitation is left behind").isEmpty();
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/setup").with(as(this.customer, Role.CUSTOMER)))
			.andExpect(jsonPath("$.people.length()").value(2))
			.andExpect(jsonPath("$.people[0].name").value("Đỗ Mạnh Hùng"))
			.andExpect(jsonPath("$.people[1].role").value("CUSTOMER"));
		awaitListeners();
	}

	/**
	 * Story 23.7 (FR-59, AD-19): a Customer's lock revokes their read on every Intermediary, read back, audited, with no
	 * {@code repo.access_revoked}; their erasure revokes it too before the rows go, from a payload that names nobody.
	 */
	@Test
	void aLockedOrErasedCustomerLosesTheirReadOnTheIntermediaryReadBack() throws Exception {
		AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		String customerLogin = TestPeople.login(this.jdbc, this.customer);
		String intermediary = JobSetups.intermediaryName(this.job);
		active();
		GITHUB.accept(customerLogin);
		this.checks.check(this.job);
		assertThat(customerGrant().status()).isEqualTo("ACCEPTED");

		this.revocations.locked(new AccountLocked(UUID.randomUUID(), this.customer, this.clock.instant()));
		assertThat(customerGrant().status()).isEqualTo("REVOKING");
		assertThat(teamGrants()).extracting(JobGrants.Grant::status).as("the team keeps working").containsOnly("WRITE");
		this.worker.runDue(Lane.GITHUB_SHORT);

		assertThat(customerGrant().status()).isEqualTo("REVOKED");
		assertThat(GITHUB.seen).anyMatch(seen -> seen.method().equals("DELETE")
				&& seen.path().equals("/repos/" + GitHubStub.ORGANIZATION + "/" + intermediary + "/collaborators/" + customerLogin));
		assertThat(notices("repo.access_revoked")).as("the Customer is not told").isZero();
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM audit.audit_entry WHERE action = 'repo.grant_revoked' AND job_id = :job
				""").param("job", this.job).query(Long.class).single()).isOne();
		assertThat(sweep()).as("nobody reads the Intermediary now").isEmpty();

		// As if the read were live again (an unlocked Customer reads no more, so only a test brings it back): the
		// Customer's erasure revokes it on GitHub before the rows go.
		this.jdbc.sql("UPDATE repo.job_grant SET status = 'ACCEPTED', revoked_at = NULL WHERE id = :id")
			.param("id", customerGrant().id()).update();
		GITHUB.addCollaborator(intermediary, customerLogin, "read");
		UUID grantId = customerGrant().id();
		new org.springframework.transaction.support.TransactionTemplate(this.transactions)
			.executeWithoutResult(status -> this.personalData.eraseAccount(this.customer));
		assertThat(this.jdbc.sql("SELECT count(*) FROM repo.job_grant WHERE account_id = :id").param("id", this.customer)
			.query(Long.class).single()).isZero();
		this.worker.runDue(Lane.GITHUB_SHORT);

		assertThat(this.jdbc.sql("""
				SELECT status || ' ' || payload::text FROM operations.external_operation
				WHERE type = 'repo.grant_revoke' AND payload ->> 'grantId' = :grant AND payload ->> 'repositoryId' IS NOT NULL
				""").param("grant", grantId.toString()).query(String.class).single())
			.as("read back, from a payload naming the repository, never the person")
			.startsWith("SUCCEEDED ")
			.doesNotContain(customerLogin);
		assertThat(sweep()).as("the Intermediary holds nobody").isEmpty();
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM audit.audit_entry WHERE action = 'repo.grant_revoked' AND job_id = :job
				""").param("job", this.job).query(Long.class).single()).isEqualTo(2);
		this.clock.advance(Revocations.DELAY.plusSeconds(1));
		this.deadlines.runDue();
		assertThat(notices("operations.revocation_delayed")).as("both read back in time").isZero();
		awaitListeners();
	}

	/**
	 * Story 23.7 (FR-21, AD-24): an erased Customer's revocation still not read back 15 minutes later alerts the
	 * Administrators as any delayed revocation does, with no grant row and no login of theirs; once the repository is
	 * gone, GitHub refuses a token narrowed to it (422), which means nothing is left to revoke.
	 */
	@Test
	void anErasedCustomersRevocationAlertsWhenDelayedAndEndsOnARepositoryThatIsGone() throws Exception {
		AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		String customerLogin = TestPeople.login(this.jdbc, this.customer);
		String intermediary = JobSetups.intermediaryName(this.job);
		active();
		GITHUB.accept(customerLogin);
		this.checks.check(this.job);
		UUID grantId = customerGrant().id();
		long intermediaryId = customerGrant().repositoryId();
		GITHUB.unavailable.add("GET /repos/" + GitHubStub.ORGANIZATION + "/" + intermediary + "/collaborators");

		new org.springframework.transaction.support.TransactionTemplate(this.transactions)
			.executeWithoutResult(status -> this.personalData.eraseAccount(this.customer));
		this.worker.runDue(Lane.GITHUB_SHORT);
		assertThat(erasureRevocation(grantId)).isEqualTo("PENDING");
		this.clock.advance(Revocations.DELAY.plusSeconds(1));
		this.deadlines.runDue();

		assertThat(this.jdbc.sql("SELECT params ->> 'login' FROM notification.notification WHERE type = 'operations.revocation_delayed'")
			.query(String.class).list()).as("the Administrator, naming no erased login").containsExactly(Revocations.ERASED_LOGIN);
		assertThat(audits("repo.revocation_delayed")).isOne();

		// The repository went meanwhile: a token narrowed to it is refused, so there is nothing to revoke.
		GITHUB.unavailable.clear();
		GITHUB.extra = (exchange, request, query) -> {
			if (request.method().equals("POST") && request.path().endsWith("/access_tokens")
					&& request.body().path("repository_ids").toString().equals("[" + intermediaryId + "]")) {
				byte[] body = "{\"message\":\"There is at least one repository that does not exist\"}"
					.getBytes(java.nio.charset.StandardCharsets.UTF_8);
				exchange.getResponseHeaders().add("Content-Type", "application/json");
				exchange.sendResponseHeaders(422, body.length);
				exchange.getResponseBody().write(body);
				exchange.close();
				return true;
			}
			return false;
		};
		try {
			this.clock.advance(GrantRevokeOperation.RETRY.plusSeconds(1));
			this.worker.runDue(Lane.GITHUB_SHORT);
		}
		finally {
			GITHUB.extra = (exchange, request, query) -> false;
		}

		assertThat(erasureRevocation(grantId)).isEqualTo("SUCCEEDED");
		assertThat(audits("repo.grant_revoked")).isOne();
		awaitListeners();
	}

	/** The status of the erased Customer's revocation of {@code grantId}. */
	private String erasureRevocation(UUID grantId) {
		return this.jdbc.sql("""
				SELECT status FROM operations.external_operation
				WHERE type = 'repo.grant_revoke' AND payload ->> 'grantId' = :grant AND payload ->> 'repositoryId' IS NOT NULL
				""").param("grant", grantId.toString()).query(String.class).single();
	}

	/**
	 * Story 23.7 (FR-19, SM-2, AD-12): on the Intermediary the live Customer's acceptance, their own {@code MemberEvent},
	 * changes nothing, but any other event of theirs is reported; and the Customer as a collaborator on the Working
	 * Repository is drift.
	 */
	@Test
	void theSweepExemptsOnlyTheLiveCustomersMemberEventAndReportsTheCustomerOnWorking() throws Exception {
		AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		String customerLogin = TestPeople.login(this.jdbc, this.customer);
		String intermediary = JobSetups.intermediaryName(this.job);
		String working = JobSetups.workingName(this.job);
		setUpTheRepositories();
		this.worker.runDue(Lane.GITHUB_SHORT);
		GITHUB.accept(customerLogin);
		this.checks.check(this.job);
		assertThat(customerGrant().status()).isEqualTo("ACCEPTED");

		GITHUB.eventActors.put(intermediary, List.of("vibefix-dev-platform[bot]", "MemberEvent:" + customerLogin));
		assertThat(sweep()).as("the bot, and the Customer's acceptance").isEmpty();

		GITHUB.eventActors.put(intermediary,
				List.of("vibefix-dev-platform[bot]", "MemberEvent:" + customerLogin, "PushEvent:" + customerLogin));
		assertThat(sweep()).singleElement().asString().isEqualTo(GitHubStub.ORGANIZATION + "/" + intermediary
				+ ": recent changes by [" + customerLogin + "], not the System (SM-2)");
		GITHUB.eventActors.remove(intermediary);

		GITHUB.addCollaborator(working, customerLogin, "read");
		assertThat(sweep()).singleElement().asString()
			.startsWith(GitHubStub.ORGANIZATION + "/" + working + ": collaborators {")
			.contains(customerLogin + "=read");
		awaitListeners();
	}

	/** Story 23.7 (R5, FR-80): a Customer with no linked GitHub account is not invited; the team still is. */
	@Test
	void aCustomerWithNoLinkedGitHubIsNotInvitedAndTheTeamStillIs() throws Exception {
		confirmTheHead();
		run();
		this.jdbc.sql("""
				UPDATE identity.account SET github_user_id = NULL, github_login = NULL, github_linked_at = NULL,
				    google_sub = :sub WHERE id = :id
				""").param("sub", "google-" + this.customer).param("id", this.customer).update();
		copyRunSucceeds();
		assertThat(this.setups.latest(this.job).orElseThrow().status()).isEqualTo("DONE");
		this.worker.runDue(Lane.GITHUB_SHORT);

		assertThat(this.grants.ofJob(this.job)).extracting(JobGrants.Grant::role).containsExactly("MENTOR", "CREW");
		assertThat(this.grants.ofJob(this.job)).extracting(JobGrants.Grant::status).containsOnly("INVITED");
		assertThat(notices("repo.github_invitation_sent")).isEqualTo(2);
		assertThat(notices("repo.intermediary_invitation_sent")).isZero();
		awaitListeners();
	}

	/**
	 * Story 25.2a part 4 (D11, AD-11, AD-47, B-10, R63): a Self-mentored Job grants no Mentor. Its Setup records the mode,
	 * the active Crew alone is invited to the Working Repository and counted for Verify, and the Customer who mentors
	 * holds their read on the Intermediary Repository and nothing else: no team grant, no invitation, and nothing of the
	 * Working Repository in the step tracker they read. The table refuses the rows no path writes.
	 */
	@Test
	void aSelfMentoredJobInvitesItsCrewAloneAndItsCustomerHoldsNothingOnTheWorkingRepository() throws Exception {
		selfMentored();
		String crewLogin = TestPeople.login(this.jdbc, this.crew);
		String customerLogin = TestPeople.login(this.jdbc, this.customer);
		String working = GitHubStub.ORGANIZATION + "/" + JobSetups.workingName(this.job);
		setUpTheRepositories();
		this.worker.runDue(Lane.GITHUB_SHORT);

		assertThat(this.jdbc.sql("SELECT self_mentored FROM repo.job_setup WHERE job_id = :job").param("job", this.job)
			.query(Boolean.class).single()).as("the Setup records the mode").isTrue();
		assertThat(this.grants.ofJob(this.job)).extracting(JobGrants.Grant::role).containsExactly("CREW", "CUSTOMER");
		assertThat(teamGrants()).singleElement().satisfies(grant -> {
			assertThat(grant.accountId()).isEqualTo(this.crew);
			assertThat(grant.repositoryName()).isEqualTo(working);
		});
		assertThat(customerGrant().repositoryName())
			.isEqualTo(GitHubStub.ORGANIZATION + "/" + JobSetups.intermediaryName(this.job));
		assertThat(GITHUB.seen).as("nobody invites the Customer to the Working Repository")
			.noneMatch(seen -> seen.path().equals("/repos/" + working + "/collaborators/" + customerLogin));
		assertThat(notices("repo.github_invitation_sent")).as("the member's alone").isOne();
		assertThat(notices("repo.github_invitation_sent", this.customer)).isZero();
		String tracker = this.mvc.perform(get("/api/v1/jobs/" + this.job + "/setup").with(as(this.customer, Role.CUSTOMER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.people.length()").value(2))
			.andExpect(jsonPath("$.people[0].role").value("CREW"))
			.andExpect(jsonPath("$.people[0].name").value("@" + crewLogin))
			.andExpect(jsonPath("$.people[0].githubUrl").doesNotExist())
			.andExpect(jsonPath("$.people[1].role").value("CUSTOMER"))
			.andExpect(jsonPath("$.people[1].githubUrl").value(org.hamcrest.Matchers.endsWith("-intermediary/invitations")))
			.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		assertThat(tracker).as("nothing of the Working Repository").doesNotContain(JobSetups.workingName(this.job));
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/setup").with(as(this.crew, Role.STUDENT)))
			.andExpect(jsonPath("$.people[0].githubUrl").value(org.hamcrest.Matchers.endsWith("-working/invitations")));

		JobBusy.BusyJob selfMentored = this.busy.job(this.job).orElseThrow();
		assertThat(selfMentored.selfMentored()).isTrue();
		assertThat(this.grants.counted(selfMentored)).extracting(JobGrants.Person::accountId).containsExactly(this.crew);
		vn.vibefix.github.PlatformRepositories.PlatformRepository repository = teamGrants().get(0).repository();
		long customerGitHub = githubUserId(this.customer);
		this.grants.grant(selfMentored, new JobGrants.Person(this.customer, "MENTOR", customerGitHub, customerLogin), repository,
				false, false);
		this.grants.grant(selfMentored, new JobGrants.Person(this.customer, "CREW", customerGitHub, customerLogin), repository,
				true, false);
		assertThat(this.grants.ofJob(this.job)).as("no team grant is written for the Customer").hasSize(2);
		assertThatThrownBy(() -> insertGrant(this.mentor, "MENTOR")).as("the table refuses a Mentor's grant in the mode")
			.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
			.hasMessageContaining("grants no Mentor");
		assertThatThrownBy(() -> insertGrant(this.customer, "CREW")).as("and a team grant for who holds the Customer's read")
			.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
			.hasMessageContaining("holds no grant with its team");
		assertThatThrownBy(() -> insertGrant(this.crew, "CUSTOMER")).as("and the Customer's read for a member of the team")
			.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
			.hasMessageContaining("holds no grant with its team");

		GITHUB.accept(crewLogin);
		this.checks.check(this.job);
		assertThat(this.busy.job(this.job).orElseThrow().busy()).as("Verify waits for the active Crew alone")
			.hasValueSatisfying(marker -> assertThat(marker.purpose()).isEqualTo(JobBusy.Purpose.JOB_VERIFY));
		awaitListeners();
	}

	/**
	 * Story 25.2a part 4 (D11, AD-11, R63): the invitation step sends nothing for a team grant that names the Job's
	 * Customer. The table's trigger knows the Customer by their read grant, so a Customer who holds none (no Setup yet
	 * here) is refused by the step itself: the operation ends obsolete before any call to GitHub.
	 */
	@Test
	void anInvitationForATeamGrantNamingTheCustomerIsNeverSent() throws Exception {
		UUID grant = insertGrant(this.customer, "CREW");
		assertThat(this.grants.teamGrantOfTheCustomer(this.grants.find(grant).orElseThrow())).isTrue();
		GITHUB.seen.clear();

		new TransactionTemplate(this.transactions).executeWithoutResult(status -> this.operations
			.request(new OperationRequest(JobGrants.INVITE, UUID.randomUUID(), Map.of("grantId", grant.toString(), "round", 0),
					null, null, 0)));
		this.worker.runDue(Lane.GITHUB_SHORT);

		assertThat(this.jdbc.sql("""
				SELECT status || ' ' || coalesce(error_code, '') FROM operations.external_operation WHERE type = :type
				""").param("type", JobGrants.INVITE).query(String.class).list())
			.containsExactly("OBSOLETE CUSTOMER_NOT_ON_THE_TEAM");
		assertThat(this.grants.find(grant).orElseThrow().status()).as("never invited").isEqualTo("INVITING");
		assertThat(GITHUB.seen).as("nothing was asked of GitHub").noneMatch(seen -> seen.path().contains("/collaborators/"));
	}

	/**
	 * Story 25.2a part 4 (D11, AD-11, R63): a Self-mentored Job grants no Mentor, whoever the row names. Before the Job
	 * has a Setup the table cannot know its mode, so the steps refuse such a row themselves: neither the invitation nor
	 * the raise, which on a person who is no collaborator is an invitation, calls GitHub.
	 */
	@Test
	void aMentorGrantOfASelfMentoredJobIsNeitherInvitedNorRaised() throws Exception {
		selfMentored();
		UUID grant = insertGrant(this.mentor, "MENTOR");
		assertThat(this.grants.teamGrantOfTheCustomer(this.grants.find(grant).orElseThrow())).isTrue();
		GITHUB.seen.clear();

		new TransactionTemplate(this.transactions).executeWithoutResult(status -> this.operations
			.request(new OperationRequest(JobGrants.INVITE, UUID.randomUUID(), Map.of("grantId", grant.toString(), "round", 0),
					null, null, 0)));
		this.grants.requestRaise(this.grants.find(grant).orElseThrow());
		this.worker.runDue(Lane.GITHUB_SHORT);

		assertThat(this.jdbc.sql("""
				SELECT type || ' ' || status || ' ' || coalesce(error_code, '') FROM operations.external_operation
				WHERE type IN (:invite, :raise) ORDER BY type
				""").param("invite", JobGrants.INVITE).param("raise", JobGrants.RAISE).query(String.class).list())
			.containsExactly(JobGrants.INVITE + " OBSOLETE CUSTOMER_NOT_ON_THE_TEAM",
					JobGrants.RAISE + " OBSOLETE CUSTOMER_NOT_ON_THE_TEAM");
		assertThat(GITHUB.seen).as("nothing was asked of GitHub").noneMatch(seen -> seen.path().contains("/collaborators/"));
	}

	/**
	 * Story 25.4 (FR-19, AD-12, AD-47 exception 9): the platform sweep expects nothing of the Customer on the Working
	 * Repository of a Self-mentored Job, so their account found there is a difference, as in any Job.
	 */
	@Test
	void theSweepReportsTheCustomerOfASelfMentoredJobOnItsWorkingRepository() throws Exception {
		AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		selfMentored();
		String customerLogin = TestPeople.login(this.jdbc, this.customer);
		String working = JobSetups.workingName(this.job);
		setUpTheRepositories();
		this.worker.runDue(Lane.GITHUB_SHORT);
		GITHUB.accept(customerLogin);
		this.checks.check(this.job);
		assertThat(customerGrant().status()).isEqualTo("ACCEPTED");
		assertThat(sweep()).as("the Crew's invitation and the Customer's read are expected").isEmpty();

		GITHUB.addCollaborator(working, customerLogin, "read");

		assertThat(sweep()).singleElement().asString()
			.startsWith(GitHubStub.ORGANIZATION + "/" + working + ": collaborators {")
			.contains(customerLogin + "=read");
		awaitListeners();
	}

	/**
	 * Story 25.4 (FR-18, FR-19, AD-11, AD-47 exceptions 9 and 12 b): a Self-mentored Assessment Job invites its Crew and
	 * its Customer to the one read-only copy, each through one grant, and nobody as the Mentor; when a member's read is
	 * never accepted, Administrators alone hear, because the notice is about the team's access.
	 */
	@Test
	void aSelfMentoredAssessmentJobInvitesNoMentorAndItsExhaustedRenewalsReachAdministratorsAlone() throws Exception {
		UUID administrator = AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương").accountId();
		assessmentJob();
		selfMentored();
		assessmentSetUpTheRepository();
		this.worker.runDue(Lane.GITHUB_SHORT);
		assertThat(this.busy.job(this.job).orElseThrow().state()).isEqualTo("ACTIVE");

		assertThat(this.grants.ofJob(this.job)).extracting(JobGrants.Grant::role).containsExactlyInAnyOrder("CREW", "CUSTOMER");
		assertThat(this.grants.ofJob(this.job)).as("one read each, on the one copy").allMatch(JobGrants.Grant::readOnly)
			.extracting(JobGrants.Grant::repositoryName).containsOnly(customerGrant().repositoryName());
		assertThat(notices("repo.github_invitation_sent", this.customer)).as("never invited as the Mentor").isZero();

		for (int renewal = 0; renewal < JobGrants.CREW_CHANGE_RENEWALS; renewal++) {
			this.clock.advance(Duration.ofDays(7).plusMinutes(1));
			this.deadlines.runDue();
			this.worker.runDue(Lane.GITHUB_SHORT);
		}
		this.clock.advance(Duration.ofDays(7).plusMinutes(1));
		this.deadlines.runDue();

		assertThat(crewGrant().renewals()).isEqualTo(JobGrants.CREW_CHANGE_RENEWALS);
		assertThat(this.jdbc.sql("""
				SELECT DISTINCT account_id FROM notification.notification WHERE type = 'repo.invitation_renewals_exhausted'
				""").query(UUID.class).list()).as("the Customer mentors, and is not told of the team's access")
			.containsExactly(administrator);
		awaitListeners();
	}

	/** The Job becomes a Self-mentored one, as J11 leaves it: its Customer is its Mentor, and no bid was selected. */
	private void selfMentored() {
		this.jdbc.sql("""
				UPDATE job.job SET self_mentored = TRUE, mentor_account_id = customer_account_id,
				    selected_application_id = NULL, self_mentored_notice_version = '1.0'
				WHERE id = :job
				""").param("job", this.job).update();
	}

	/** A grant row written as no path writes it, on a repository of its own: what the table's trigger sees. */
	private UUID insertGrant(UUID account, String role) {
		UUID id = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO repo.job_grant (id, job_id, product_id, account_id, role, github_login, github_user_id,
				    repository_github_id, repository_name, crew_change, read_only, status, created_at)
				VALUES (:id, :job, :product, :account, :role, :login, :githubUserId, :repository, 'vibefix-org/khong-ai-ghi',
				    FALSE, FALSE, 'INVITING', :now)
				""").param("id", id).param("job", this.job).param("product", this.product).param("account", account)
			.param("role", role).param("login", TestPeople.login(this.jdbc, account))
			.param("githubUserId", githubUserId(account)).param("repository", REPOSITORY_IDS.incrementAndGet())
			.param("now", Timestamp.from(this.clock.instant())).update();
		return id;
	}

	/**
	 * Story 23.7 (AD-24): the Customer's export holds their read of the Intermediary with the role CUSTOMER, beside the
	 * Mentor's grant on the Working Repository with the role MENTOR.
	 */
	@Test
	void theCustomersReadIsExportedWithItsRoleAndRepository() throws Exception {
		setUpTheRepositories();
		this.worker.runDue(Lane.GITHUB_SHORT);

		assertThat(this.personalData.export(this.customer).orElseThrow().data()).extractingByKey("repositoryGrants")
			.asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
			.singleElement()
			.asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
			.containsEntry("role", "CUSTOMER")
			.containsEntry("repository", GitHubStub.ORGANIZATION + "/" + JobSetups.intermediaryName(this.job))
			.containsEntry("status", "INVITED");
		assertThat(this.personalData.export(this.mentor).orElseThrow().data()).extractingByKey("repositoryGrants")
			.asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
			.singleElement()
			.asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
			.containsEntry("role", "MENTOR")
			.containsEntry("repository", GitHubStub.ORGANIZATION + "/" + JobSetups.workingName(this.job));
	}

	/**
	 * Story 23.7: an ended Job is swept for the Customer's pending read only; a team member it counts is never granted
	 * again, even with their revoked row gone, and nothing is sent to the Working Repository.
	 */
	@Test
	void anEndedJobSweptForTheCustomersPendingReadGrantsNobodyOfTheTeamAgain() throws Exception {
		setUpTheRepositories();
		this.worker.runDue(Lane.GITHUB_SHORT);
		this.jdbc.sql("UPDATE job.job SET state = 'CANCELLED' WHERE id = :id").param("id", this.job).update();
		this.revocations.transitioned(new JobTransitioned(UUID.randomUUID(), UUID.randomUUID(), this.job, "J10", "USER",
				"STARTING", "CANCELLED", 4, 1, Map.of()));
		this.worker.runDue(Lane.GITHUB_SHORT);
		assertThat(teamGrants()).extracting(JobGrants.Grant::status).containsOnly("REVOKED");
		this.jdbc.sql("DELETE FROM repo.job_grant WHERE job_id = :job AND account_id = :crew").param("job", this.job)
			.param("crew", this.crew).update();
		assertThat(this.grants.unsettledJobs()).as("the Customer's pending read").contains(this.job);
		GITHUB.seen.clear();

		this.checks.sweep();

		assertThat(this.grants.ofJob(this.job)).noneMatch(grant -> grant.accountId().equals(this.crew));
		assertThat(GITHUB.seen).noneMatch(seen -> seen.method().equals("PUT") && seen.path()
			.startsWith("/repos/" + GitHubStub.ORGANIZATION + "/" + JobSetups.workingName(this.job) + "/collaborators/"));
		awaitListeners();
	}

	@Test
	void thirtyDaysAfterTheJobEndsBothRepositoriesAreDeletedWithNoArchive() throws Exception {
		setUpTheRepositories();
		this.worker.runDue(Lane.GITHUB_SHORT);
		String working = JobSetups.workingName(this.job);
		String intermediary = JobSetups.intermediaryName(this.job);
		this.jdbc.sql("UPDATE job.job SET state = 'CANCELLED' WHERE id = :id").param("id", this.job).update();

		this.revocations.transitioned(new JobTransitioned(UUID.randomUUID(), UUID.randomUUID(), this.job, "J10", "USER",
				"STARTING", "CANCELLED", 4, 1, Map.of()));
		this.worker.runDue(Lane.GITHUB_SHORT);
		assertThat(teamGrants()).extracting(JobGrants.Grant::status).containsOnly("REVOKED");
		assertThat(customerGrant().status()).as("the Customer reads until the deletion (R5)").isEqualTo("INVITED");
		assertThat(sweep()).as("the ended Job's repositories are still read: nobody of the team, the Customer invited")
			.isEmpty();
		// Accepted on a Cancelled Job: recorded all the same until the deletion.
		GITHUB.accept(TestPeople.login(this.jdbc, this.customer));
		this.checks.sweep();
		assertThat(customerGrant().status()).isEqualTo("ACCEPTED");
		this.clock.advance(Duration.ofDays(29));
		this.deadlines.runDue();
		this.worker.runDue(Lane.GITHUB_LONG);
		assertThat(GITHUB.deleted).doesNotContain(working, intermediary);
		assertThat(customerGrant().status()).isEqualTo("ACCEPTED");

		this.clock.advance(Duration.ofDays(1).plusMinutes(1));
		this.deadlines.runDue();
		this.worker.runDue(Lane.GITHUB_LONG);

		assertThat(GITHUB.deleted).containsSubsequence(working, intermediary);
		// The deletion ends the Customer's read without a GitHub call: audited, its to-do closed.
		assertThat(customerGrant().status()).isEqualTo("REVOKED");
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM audit.audit_entry a
				WHERE a.action = 'repo.grant_revoked' AND a.job_id = :job
				""").param("job", this.job).query(Long.class).single()).as("the team's two and the Customer's").isEqualTo(3);
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM notification.todo
				WHERE todo_key = 'repo.accept_intermediary_invitation' AND account_id = :customer AND closed_at IS NULL
				""").param("customer", this.customer).query(Long.class).single()).isZero();
		assertThat(this.jdbc.sql("SELECT count(*) FROM repo.job_setup WHERE job_id = :job AND deleted_at IS NOT NULL")
			.param("job", this.job).query(Long.class).single()).isOne();
		// Story 23.10d: nothing is archived first, so the Customer owns no stored file and none was ever stored.
		assertThat(this.jdbc.sql("SELECT count(*) FROM files.stored_file WHERE account_id = :customer")
			.param("customer", this.customer).query(Long.class).single()).isZero();
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM audit.audit_entry WHERE action IN ('repo.final_zip_stored', 'repo.deletion_started')
				""").query(Long.class).single()).isZero();
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM audit.audit_entry WHERE action = 'repo.deletion_requested' AND job_id = :job
				""").param("job", this.job).query(Long.class).single()).isOne();
		assertThat(sweep()).as("deleted repositories are no longer read").isEmpty();
	}

	/**
	 * Story 23.10d: both requests derive the same operation id from the Setup, so a Product deleted after the
	 * 30-day deadline already queued its deletion deletes the repositories once, not twice.
	 */
	@Test
	void aProductDeletedAfterTheDeadlineFiredDeletesItsRepositoriesOnce() throws Exception {
		setUpTheRepositories();
		this.worker.runDue(Lane.GITHUB_SHORT);
		String working = JobSetups.workingName(this.job);
		String intermediary = JobSetups.intermediaryName(this.job);
		cancelTheJob();
		this.clock.advance(Duration.ofDays(30).plusMinutes(1));
		this.deadlines.runDue();
		assertThat(queuedDeletions()).as("the deadline queued it, and the lane has not run yet").isOne();

		new org.springframework.transaction.support.TransactionTemplate(this.transactions)
			.executeWithoutResult(status -> this.personalData.deleteProduct(this.product));

		assertThat(queuedDeletions()).as("the Product's request finds the deadline's, by the same derived id").isOne();
		this.worker.runDue(Lane.GITHUB_LONG);
		assertThat(GITHUB.deleted).filteredOn(name -> name.equals(working) || name.equals(intermediary))
			.as("each repository deleted once")
			.containsExactly(working, intermediary);
	}

	/**
	 * Story 23.10d: a deletion whose attempts ran out ends {@code FAILED}, which is terminal until an
	 * Administrator retries it (FR-64). A Product deletion derives the same operation id, so joining that dead row
	 * would queue nothing -- and the Product deletion goes on to delete the Setup rows that name the repositories,
	 * leaving the Customer's and the team's code on GitHub with nothing pointing at it. The second request revives
	 * it instead.
	 */
	@Test
	void aProductDeletionRevivesADeletionThatAlreadyFailed() throws Exception {
		setUpTheRepositories();
		this.worker.runDue(Lane.GITHUB_SHORT);
		String working = JobSetups.workingName(this.job);
		String intermediary = JobSetups.intermediaryName(this.job);
		String refused = "DELETE /repos/" + GitHubStub.ORGANIZATION + "/" + working;
		cancelTheJob();
		GITHUB.unavailable.add(refused);
		this.clock.advance(Duration.ofDays(30).plusMinutes(1));
		this.deadlines.runDue();
		exhaustTheDeletionAttempts();
		assertThat(deletionStatus()).as("GitHub refused every attempt").isEqualTo("FAILED");
		assertThat(GITHUB.deleted).doesNotContain(working, intermediary);

		new org.springframework.transaction.support.TransactionTemplate(this.transactions)
			.executeWithoutResult(status -> this.personalData.deleteProduct(this.product));

		assertThat(deletionStatus()).as("asking again revives it, rather than joining a dead row").isEqualTo("PENDING");
		assertThat(queuedDeletions()).as("still one operation for the Setup").isOne();
		GITHUB.unavailable.remove(refused);
		this.worker.runDue(Lane.GITHUB_LONG);

		assertThat(GITHUB.deleted).containsSubsequence(working, intermediary);
		assertThat(deletionStatus()).isEqualTo("SUCCEEDED");
	}

	/**
	 * Story 23.10d: the Intermediary's delete fails once the Working Repository is already gone, so the attempt
	 * ends {@code Transient} and the next one re-enters the loop. It must not delete the Working Repository again:
	 * on the real GitHub that mints a token narrowed to a numeric ID it no longer knows, which it refuses, and the
	 * operation would never finish.
	 */
	@Test
	void aRetryAfterTheWorkingRepositoryIsGoneDeletesOnlyWhatIsLeft() throws Exception {
		setUpTheRepositories();
		this.worker.runDue(Lane.GITHUB_SHORT);
		String working = JobSetups.workingName(this.job);
		String intermediary = JobSetups.intermediaryName(this.job);
		String refused = "DELETE /repos/" + GitHubStub.ORGANIZATION + "/" + intermediary;
		cancelTheJob();
		GITHUB.unavailable.add(refused);
		this.clock.advance(Duration.ofDays(30).plusMinutes(1));
		this.deadlines.runDue();

		this.worker.runDue(Lane.GITHUB_LONG);

		assertThat(GITHUB.deleted).contains(working).doesNotContain(intermediary);
		assertThat(deletionStatus()).as("transient: it waits for its next attempt").isEqualTo("PENDING");
		GITHUB.unavailable.remove(refused);
		this.clock.advance(OperationPolicy.DEFAULT.delayAfter(1).plusSeconds(1));

		this.worker.runDue(Lane.GITHUB_LONG);

		assertThat(GITHUB.deleted).filteredOn(working::equals).as("never deleted twice").hasSize(1);
		assertThat(GITHUB.deleted).contains(intermediary);
		assertThat(deletionStatus()).isEqualTo("SUCCEEDED");
		assertThat(this.jdbc.sql("SELECT count(*) FROM repo.job_setup WHERE job_id = :job AND deleted_at IS NOT NULL")
			.param("job", this.job).query(Long.class).single()).isOne();
	}

	/**
	 * Story 23.10d, matrix row 3: a {@code repo.repository_delete} queued before this story still carries
	 * {@code zip}, {@code customerId} and {@code branch} under the old {@code setupId:delete:true} key. Nothing
	 * reads those keys any more, and the handler has no {@code SETUP_GONE} arm left, so it deletes exactly the
	 * repositories its payload names.
	 */
	@Test
	void aDeletionQueuedBeforeTheUpgradeRunsWithoutItsZipKeys() throws Exception {
		setUpTheRepositories();
		this.worker.runDue(Lane.GITHUB_SHORT);
		Setup setup = this.setups.latest(this.job).orElseThrow();
		String working = JobSetups.workingName(this.job);
		String intermediary = JobSetups.intermediaryName(this.job);
		Map<String, Object> legacy = new java.util.LinkedHashMap<>();
		legacy.put("setupId", setup.id().toString());
		legacy.put("jobId", this.job.toString());
		legacy.put("zip", true);
		legacy.put("customerId", this.customer.toString());
		legacy.put("intermediaryId", java.util.Objects.requireNonNull(setup.intermediary()).id());
		legacy.put("intermediaryName", setup.intermediary().fullName());
		legacy.put("workingId", java.util.Objects.requireNonNull(setup.working()).id());
		legacy.put("workingName", setup.working().fullName());
		legacy.put("branch", "main");

		new org.springframework.transaction.support.TransactionTemplate(this.transactions)
			.executeWithoutResult(status -> this.operations.request(new OperationRequest(RepositoryDeletions.DELETE,
					UUID.nameUUIDFromBytes((setup.id() + ":delete:true").getBytes(StandardCharsets.UTF_8)), legacy, null,
					null, 0)));
		this.worker.runDue(Lane.GITHUB_LONG);

		assertThat(GITHUB.deleted).containsSubsequence(working, intermediary);
		assertThat(deletionStatus()).isEqualTo("SUCCEEDED");
	}

	/**
	 * Story 23.10d, matrix row 2: the 30-day deadline of a Job whose Setup rows went with its Product answers
	 * {@code OBSOLETE}. This exercises the {@code pending.isEmpty()} arm; the {@code !live} arm is unreachable
	 * today, because {@code deleteProduct} deletes the {@code repo.job_setup} rows in the same transaction that
	 * makes the Job not live. The guard stays all the same, for a Job that goes without its Setups some other way.
	 */
	@Test
	void theDeadlineIsObsoleteOnceTheSetupRowsWentWithTheProduct() throws Exception {
		setUpTheRepositories();
		this.worker.runDue(Lane.GITHUB_SHORT);
		cancelTheJob();
		new org.springframework.transaction.support.TransactionTemplate(this.transactions)
			.executeWithoutResult(status -> this.personalData.deleteProduct(this.product));
		this.worker.runDue(Lane.GITHUB_LONG);
		assertThat(queuedDeletions()).as("the Product's own request ran").isOne();

		this.clock.advance(Duration.ofDays(30).plusMinutes(1));
		this.deadlines.runDue();

		assertThat(this.jdbc.sql("SELECT status FROM timers.deadline WHERE job_id = :job AND kind = :kind")
			.param("job", this.job).param("kind", RepositoryDeletion.KEY).query(String.class).single())
			.isEqualTo("OBSOLETE");
		assertThat(queuedDeletions()).as("nothing more is queued").isOne();
	}

	/** J10 on a Cancelled Job: the team's grants are revoked and the 30-day deletion deadline is scheduled. */
	private void cancelTheJob() {
		this.jdbc.sql("UPDATE job.job SET state = 'CANCELLED' WHERE id = :id").param("id", this.job).update();
		this.revocations.transitioned(new JobTransitioned(UUID.randomUUID(), UUID.randomUUID(), this.job, "J10", "USER",
				"STARTING", "CANCELLED", 4, 1, Map.of()));
		this.worker.runDue(Lane.GITHUB_SHORT);
	}

	/** Runs the deletion's every attempt, so the operation ends {@code FAILED}. */
	private void exhaustTheDeletionAttempts() {
		for (int attempt = 1; attempt <= OperationPolicy.DEFAULT.maxAttempts(); attempt++) {
			this.worker.runDue(Lane.GITHUB_LONG);
			this.clock.advance(OperationPolicy.DEFAULT.delayAfter(attempt).plusSeconds(1));
		}
	}

	/** The Job's queued {@code repo.repository_delete} operations, however they were requested. */
	private long queuedDeletions() {
		return this.jdbc.sql("""
				SELECT count(*) FROM operations.external_operation
				WHERE type = 'repo.repository_delete' AND payload ->> 'jobId' = :job
				""").param("job", this.job.toString()).query(Long.class).single();
	}

	/** Where the Job's one {@code repo.repository_delete} operation stands. */
	private String deletionStatus() {
		return this.jdbc.sql("""
				SELECT status FROM operations.external_operation
				WHERE type = 'repo.repository_delete' AND payload ->> 'jobId' = :job
				""").param("job", this.job.toString()).query(String.class).single();
	}

	@Test
	void deletingTheProductDeletesItsRepositoriesAtOnce() throws Exception {
		setUpTheRepositories();
		this.worker.runDue(Lane.GITHUB_SHORT);
		UUID customerRead = customerGrant().id();
		org.springframework.transaction.support.TransactionTemplate transaction =
				new org.springframework.transaction.support.TransactionTemplate(this.transactions);

		transaction.executeWithoutResult(status -> this.personalData.deleteProduct(this.product));
		this.worker.runDue(Lane.GITHUB_LONG);

		assertThat(GITHUB.deleted).containsSubsequence(JobSetups.workingName(this.job), JobSetups.intermediaryName(this.job));
		assertThat(this.jdbc.sql("SELECT count(*) FROM repo.job_setup WHERE job_id = :job").param("job", this.job)
			.query(Long.class).single()).isZero();
		assertThat(this.jdbc.sql("SELECT count(*) FROM repo.source_confirmation WHERE job_id = :job").param("job", this.job)
			.query(Long.class).single()).as("the self-confirmations go with it").isZero();
		// Story 23.10d: the Product deletion requests the deletion itself, under one audit action.
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM audit.audit_entry WHERE action = 'repo.deletion_requested' AND job_id = :job
				""").param("job", this.job).query(Long.class).single()).isOne();
		assertThat(this.jdbc.sql("SELECT count(*) FROM audit.audit_entry WHERE action = 'repo.repositories_deleted_with_product'"
				+ " AND job_id = :job").param("job", this.job).query(Long.class).single()).isOne();
		// Story 23.7 (R5): the Customer's read ended with the Product, audited before its row went, and its to-do closed.
		assertThat(this.jdbc.sql("SELECT count(*) FROM repo.job_grant WHERE job_id = :job").param("job", this.job)
			.query(Long.class).single()).isZero();
		assertThat(this.jdbc.sql("""
				SELECT e.action FROM audit.audit_entry e JOIN audit.audit_entry_object o ON o.entry_id = e.id
				WHERE e.action IN ('repo.grant_ended_with_product', 'repo.grant_revoked') AND o.object_id = :grant
				""").param("grant", customerRead).query(String.class).list())
			.as("not revoked by a read-back: ended with the Product").containsExactly("repo.grant_ended_with_product");
		assertThat(openCustomerTodos()).isZero();
	}

	/** Setup, the invitations, both acceptances, and Verify: an Active Job whose Mentor and Crew hold write. */
	private void active() throws Exception {
		setUpTheRepositories();
		this.worker.runDue(Lane.GITHUB_SHORT);
		GITHUB.accept(TestPeople.login(this.jdbc, this.mentor));
		GITHUB.accept(TestPeople.login(this.jdbc, this.crew));
		this.checks.check(this.job);
		this.worker.runDue(Lane.GITHUB_SHORT);
		assertThat(this.busy.job(this.job).orElseThrow().state()).isEqualTo("ACTIVE");
	}

	private String grantStatus(UUID account) {
		return this.grants.ofJob(this.job).stream().filter(grant -> grant.accountId().equals(account)).findFirst()
			.orElseThrow().status();
	}

	private List<String> sweep() {
		return new org.springframework.transaction.support.TransactionTemplate(this.transactions).execute(status -> this.guard.sweep());
	}

	/** Where the first request {@code wanted} matches stands among every request GitHub received, or -1. */
	private static int indexOf(java.util.function.Predicate<GitHubStub.Seen> wanted) {
		List<GitHubStub.Seen> seen;
		synchronized (GITHUB.seen) {
			seen = List.copyOf(GITHUB.seen);
		}
		for (int index = 0; index < seen.size(); index++) {
			if (wanted.test(seen.get(index))) {
				return index;
			}
		}
		return -1;
	}

	private static vn.vibefix.github.GitHubWebhookReceived webhook(String event, long repository, String login) {
		return webhook(event, repository, login, 1L);
	}

	private static vn.vibefix.github.GitHubWebhookReceived webhook(String event, long repository, String login,
			long senderId) {
		return new vn.vibefix.github.GitHubWebhookReceived(UUID.randomUUID(), UUID.randomUUID().toString(),
				vn.vibefix.github.GitHubApps.App.PLATFORM, event, "opened", 7L, List.of(repository), senderId, login);
	}

	/**
	 * AD-10 "Logins by ID" (Story 19.2): a Crew member renamed their GitHub account, and vibefix still holds
	 * the old login. The invitation goes to the login resolved from the ID just before it is sent, the
	 * acceptance is matched by ID, the raise names the new login, and the webhook sender is recognized by
	 * ID whatever login it carries.
	 */
	@Test
	void aGranteeWhoRenamedTheirGitHubAccountIsInvitedRecognizedAndRaisedByTheirId() throws Exception {
		AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		long crewId = githubUserId(this.crew);
		String oldLogin = TestPeople.login(this.jdbc, this.crew);
		GITHUB.logins.put(crewId, "thao-vy-moi");
		try {
			setUpTheRepositories();
			this.worker.runDue(Lane.GITHUB_SHORT);

			JobGrants.Grant invited = crewGrant();
			assertThat(invited.status()).isEqualTo("INVITED");
			assertThat(invited.githubUserId()).isEqualTo(crewId);
			assertThat(invited.login()).as("resolved from the ID just before the invitation").isEqualTo("thao-vy-moi");
			assertThat(GITHUB.seen).anyMatch(seen -> seen.method().equals("GET") && seen.path().equals("/user/" + crewId));
			assertThat(GITHUB.seen).as("nobody invites the old login")
				.noneMatch(seen -> seen.method().equals("PUT") && seen.path().endsWith("/collaborators/" + oldLogin));
			assertThat(this.jdbc.sql("SELECT count(*) FROM audit.audit_entry WHERE action = 'repo.grant_login_renamed'")
				.query(Long.class).single()).isOne();

			GITHUB.accept(TestPeople.login(this.jdbc, this.mentor));
			GITHUB.accept("thao-vy-moi");
			this.checks.check(this.job);
			this.worker.runDue(Lane.GITHUB_SHORT);

			assertThat(this.busy.job(this.job).orElseThrow().state()).as("the acceptance was matched by ID").isEqualTo("ACTIVE");
			assertThat(teamGrants()).extracting(JobGrants.Grant::status).containsOnly("WRITE");

			long workingId = GITHUB.organizationRepositoryId(JobSetups.workingName(this.job));
			this.guard.hinted(webhook("pull_request", workingId, "thao-vy-moi", crewId));
			this.guard.hinted(webhook("pull_request", workingId, oldLogin, crewId));
			assertThat(notices("github.unexpected_actor")).as("the sender is recognized by ID").isZero();
			this.guard.hinted(webhook("pull_request", workingId, oldLogin, crewId + 1_000_000));
			assertThat(notices("github.unexpected_actor")).as("an old login under another ID is a stranger").isOne();
			awaitListeners();
		}
		finally {
			GITHUB.logins.remove(crewId);
		}
	}

	/** A grant from before Story 19.2 has no ID: it takes its account's while the login still matches. */
	@Test
	void aGrantWithoutAGitHubIdTakesItsAccountsWhileTheLoginMatches() throws Exception {
		setUpTheRepositories();
		this.worker.runDue(Lane.GITHUB_SHORT);
		this.jdbc.sql("UPDATE repo.job_grant SET github_user_id = NULL WHERE job_id = :job").param("job", this.job).update();
		this.jdbc.sql("UPDATE repo.job_grant SET github_login = 'someone-else' WHERE account_id = :mentor")
			.param("mentor", this.mentor).update();

		GITHUB.accept(TestPeople.login(this.jdbc, this.crew));
		this.checks.check(this.job);

		assertThat(crewGrant().githubUserId()).isEqualTo(githubUserId(this.crew));
		assertThat(crewGrant().status()).isEqualTo("ACCEPTED");
		JobGrants.Grant mentor = this.grants.ofJob(this.job).stream().filter(grant -> grant.accountId().equals(this.mentor))
			.findFirst().orElseThrow();
		assertThat(mentor.githubUserId()).as("a login that no longer matches is not trusted").isNull();
	}

	/** A rename after the invitation and before its acceptance: acceptance, two-factor, and the raise go by ID. */
	@Test
	void aGranteeWhoRenamesAfterTheInvitationIsAcceptedCheckedAndRaisedByTheirId() throws Exception {
		long crewId = githubUserId(this.crew);
		try {
			setUpTheRepositories();
			this.worker.runDue(Lane.GITHUB_SHORT);
			assertThat(crewGrant().status()).isEqualTo("INVITED");

			GITHUB.renameUser(crewId, "thao-vy-sau");
			GITHUB.accept(TestPeople.login(this.jdbc, this.mentor));
			GITHUB.accept("thao-vy-sau");
			GITHUB.withoutTwoFactor.add("thao-vy-sau");
			this.checks.check(this.job);

			assertThat(crewGrant().status()).as("the acceptance is matched by ID").isEqualTo("ACCEPTED");
			assertThat(crewGrant().twoFactorMissing()).as("two-factor is read by ID").isTrue();
			assertThat(this.busy.job(this.job).orElseThrow().busy()).as("Verify waits for two-factor").isEmpty();

			GITHUB.withoutTwoFactor.clear();
			this.checks.check(this.job);
			this.worker.runDue(Lane.GITHUB_SHORT);

			assertThat(this.busy.job(this.job).orElseThrow().state()).isEqualTo("ACTIVE");
			assertThat(crewGrant().status()).isEqualTo("WRITE");
			assertThat(crewGrant().login()).isEqualTo("thao-vy-sau");
			assertThat(GITHUB.seen).as("Verify raises the login GitHub uses now")
				.anyMatch(seen -> seen.method().equals("PUT") && seen.path().endsWith("/collaborators/thao-vy-sau")
						&& seen.body().path("permission").asString("").equals("push"));
		}
		finally {
			GITHUB.logins.remove(crewId);
		}
	}

	/** A rename once Active: the sweep reads it as no drift, and J8 revokes the new login. */
	@Test
	void aGranteeWhoRenamesOnceActiveIsNoDriftAndJ8RevokesTheirNewLogin() throws Exception {
		AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		long crewId = githubUserId(this.crew);
		try {
			active();
			GITHUB.renameUser(crewId, "thao-vy-moi");

			assertThat(sweep()).as("a renamed grantee is no drift").isEmpty();
			assertThat(crewGrant().login()).isEqualTo("thao-vy-moi");

			this.jdbc.sql("UPDATE job.job SET state = 'CLOSING' WHERE id = :id").param("id", this.job).update();
			this.revocations.transitioned(new JobTransitioned(UUID.randomUUID(), UUID.randomUUID(), this.job, "J8", "USER",
					"ACTIVE", "CLOSING", 6, 1, Map.of()));
			this.worker.runDue(Lane.GITHUB_SHORT);

			assertThat(grantStatus(this.crew)).isEqualTo("REVOKED");
			assertThat(GITHUB.seen).anyMatch(seen -> seen.method().equals("DELETE")
					&& seen.path().endsWith("/collaborators/thao-vy-moi"));
			assertThat(sweep()).as("nothing left behind").isEmpty();
			assertThat(notices("github.platform_drift")).isZero();
			awaitListeners();
		}
		finally {
			GITHUB.logins.remove(crewId);
		}
	}

	/** A Crew change member renames before accepting: the acceptance and the raise go by ID. */
	@Test
	void aCrewChangeMemberWhoRenamesBeforeTheRaiseIsRaisedUnderTheNewLogin() throws Exception {
		active();
		UUID hai = TestPeople.student(this.jdbc, "Phạm Văn Hải", true, List.of(TestPeople.REACT), "React",
				LocalDate.parse("2026-12-31"));
		long haiId = githubUserId(hai);
		UUID row = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO job.crew_row (id, job_id, member_account_id, status, signed_at, activated_at, created_at)
				VALUES (:id, :job, :student, 'ACTIVE', :now, :now, :now)
				""").param("id", row).param("job", this.job).param("student", hai)
			.param("now", Timestamp.from(this.clock.instant())).update();
		try {
			this.checks.added(new vn.vibefix.job.CrewMemberAdded(UUID.randomUUID(), this.job, row, hai, "ACTIVE"));
			this.worker.runDue(Lane.GITHUB_SHORT);
			JobGrants.Grant added = this.grants.ofJob(this.job).stream().filter(grant -> grant.accountId().equals(hai))
				.findFirst().orElseThrow();
			assertThat(added.status()).isEqualTo("INVITED");

			GITHUB.renameUser(haiId, "hai-pham");
			GITHUB.accept("hai-pham");
			this.checks.hinted(new vn.vibefix.github.GitHubWebhookReceived(UUID.randomUUID(), UUID.randomUUID().toString(),
					vn.vibefix.github.GitHubApps.App.PLATFORM, "member", "added", 7L, List.of(added.repositoryId()), 1L, null));
			this.worker.runDue(Lane.GITHUB_SHORT);

			JobGrants.Grant raised = this.grants.find(added.id()).orElseThrow();
			assertThat(raised.status()).isEqualTo("WRITE");
			assertThat(raised.login()).isEqualTo("hai-pham");
			assertThat(GITHUB.seen).anyMatch(seen -> seen.method().equals("PUT") && seen.path().endsWith("/collaborators/hai-pham")
					&& seen.body().path("permission").asString("").equals("push"));
		}
		finally {
			GITHUB.logins.remove(haiId);
		}
	}

	/** A GitHub account deleted on GitHub cannot be invited; its revocation names the last login. */
	@Test
	void aGitHubAccountThatIsGoneIsNotInvitedAndItsRevocationUsesTheStoredLogin() throws Exception {
		long crewId = githubUserId(this.crew);
		String stored = TestPeople.login(this.jdbc, this.crew);
		GITHUB.goneUsers.add(crewId);
		try {
			setUpTheRepositories();
			this.worker.runDue(Lane.GITHUB_SHORT);

			assertThat(this.jdbc.sql("""
					SELECT status || ' ' || error_code FROM operations.external_operation
					WHERE type = 'repo.grant_invite' AND payload ->> 'grantId' = :grant
					""").param("grant", crewGrant().id().toString()).query(String.class).single())
				.isEqualTo("ABORTED GITHUB_ACCOUNT_GONE");
			assertThat(GITHUB.seen).noneMatch(seen -> seen.method().equals("PUT") && seen.path().endsWith("/collaborators/" + stored));

			this.jdbc.sql("UPDATE job.job SET state = 'CANCELLED' WHERE id = :id").param("id", this.job).update();
			this.revocations.transitioned(new JobTransitioned(UUID.randomUUID(), UUID.randomUUID(), this.job, "J10", "USER",
					"STARTING", "CANCELLED", 4, 1, Map.of()));
			this.worker.runDue(Lane.GITHUB_SHORT);

			assertThat(GITHUB.seen).as("the revocation falls back to the stored login")
				.anyMatch(seen -> seen.method().equals("DELETE") && seen.path().endsWith("/collaborators/" + stored));
			assertThat(grantStatus(this.crew)).isEqualTo("REVOKED");
		}
		finally {
			GITHUB.goneUsers.remove(crewId);
		}
	}

	/** Story 23.5: the Customer uninstalled the Source App, so Setup blocks on it and so does a re-confirmation. */
	@Test
	void anUninstalledSourceAppBlocksSetupAndTheReconfirmation() throws Exception {
		GITHUB.mergeInstallations.remove(this.installation);
		GITHUB.repositoryInstallations.values().remove(this.installation);

		j6();
		this.worker.runDue(Lane.GITHUB_LONG);

		Setup blocked = this.setups.latest(this.job).orElseThrow();
		assertThat(blocked.status()).isEqualTo("BLOCKED");
		assertThat(blocked.block()).isEqualTo(JobSetups.Block.SOURCE_APP_MISSING);
		assertThat(this.jdbc.sql("SELECT params ->> 'reason' FROM notification.notification WHERE type = 'repo.job_start_blocked'")
			.query(String.class).list()).containsOnly("ứng dụng vibefix chưa được cài trên dự án gốc");
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/setup").with(as(this.customer, Role.CUSTOMER)))
			.andExpect(jsonPath("$.blockedReason").value("SOURCE_APP_MISSING"));

		this.jdbc.sql("UPDATE job.job SET state = 'ACTIVE' WHERE id = :id").param("id", this.job).update();
		this.jdbc.sql("""
				INSERT INTO repo.drift_warning (id, job_id, product_id, kind, full_name, detected_at)
				VALUES (:id, :job, :product, 'RENAMED', 'lan-dev/so-dat-ban-moi', :now)
				""").param("id", UUID.randomUUID()).param("job", this.job).param("product", this.product)
			.param("now", Timestamp.from(this.clock.instant())).update();
		this.mvc.perform(post("/api/v1/jobs/" + this.job + "/source/reconfirm").with(as(this.customer, Role.CUSTOMER))
			.with(csrf()))
			.andExpect(status().is(422))
			.andExpect(jsonPath("$.code").value("GUARD_FAILED"))
			.andExpect(jsonPath("$.reason").value("SOURCE_APP_MISSING"));
	}

	/** Story 23.5: the Customer deselected the Source Repository from the Source App, so Setup blocks on the selection. */
	@Test
	void aSourceRepositoryNoLongerSelectedBlocksSetupOnTheSelection() throws Exception {
		long repository = this.jdbc.sql("SELECT repository_github_id FROM product.product WHERE id = :id")
			.param("id", this.product).query(Long.class).single();
		GITHUB.mergeInstallations.get(this.installation).deselect(repository);

		j6();
		this.worker.runDue(Lane.GITHUB_LONG);

		Setup blocked = this.setups.latest(this.job).orElseThrow();
		assertThat(blocked.status()).isEqualTo("BLOCKED");
		assertThat(blocked.block()).isEqualTo(JobSetups.Block.SOURCE_APP_WRONG_REPOSITORY);
		assertThat(this.jdbc.sql("SELECT params ->> 'reason' FROM notification.notification WHERE type = 'repo.job_start_blocked'")
			.query(String.class).list()).containsOnly("ứng dụng vibefix phải được cài cho dự án gốc");
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/setup").with(as(this.customer, Role.CUSTOMER)))
			.andExpect(jsonPath("$.blockedReason").value("SOURCE_APP_WRONG_REPOSITORY"));
		// Story 23.5c: the Setup check records the Product's status, with its to-do.
		assertThat(sourceStatus()).isEqualTo("SOURCE_APP_WRONG_REPOSITORY");
		assertThat(productTodos()).containsExactly("product.install_source_app");
	}

	/**
	 * Story 24.6 (FR-20, FR-53, AD-12): the first Source App push to the default branch after the Customer's
	 * confirmation raises the Job's one {@code COMMITS} warning at once, from the payload alone and with no head, tells
	 * the Customer and the Mentor once, and requests one read of the Job; the same delivery again does nothing. A push to
	 * another branch, and one that deleted the default branch, do nothing and read nothing. Each later push adds its
	 * commits to that same warning's list, newest last, with no new row and no new notice, and its date stays the first
	 * push's; a reset that lists no commit counts its {@code after}, even one listed before, so "Có {n} thay đổi mới …
	 * kể từ khi công việc bắt đầu" stays true. Nothing reads a head from GitHub or compares one.
	 */
	@Test
	void pushesToTheDefaultBranchGrowTheJobsOneCommitsWarningAndTellOnce() throws Exception {
		activeAfterJ7();
		int setupRequests = GITHUB.seen.size();
		String first = GITHUB.push("lan-dev", this.sourceName, "main", "app.js", "console.log('Lovable')\n");
		String delivery = UUID.randomUUID().toString();
		byte[] push = pushEvent(first, List.of(first));
		Instant firstPush = this.clock.instant();

		sourceDelivery(delivery, "push", push).andExpect(status().isAccepted());
		awaitListeners();

		assertThat(this.drift.warnings(this.job)).singleElement().satisfies(warning -> {
			assertThat(warning.kind()).isEqualTo(SourceDrift.Kind.COMMITS);
			assertThat(warning.headSha()).as("no head is kept").isNull();
			assertThat(warning.commits()).as("stored as evidence").containsExactly(first);
			assertThat(warning.detectedAt()).isEqualTo(firstPush);
		});
		assertThat(notices("repo.drift_detected")).as("the Customer and the Mentor, before any read").isEqualTo(2);
		assertThat(driftReads(this.job)).as("one read of the Job, for that delivery").containsExactly("PENDING");

		sourceDelivery(delivery, "push", push).andExpect(status().isOk());
		awaitListeners();
		assertThat(this.drift.warnings(this.job).getFirst().commits()).as("the same delivery again").hasSize(1);
		assertThat(driftReads(this.job)).hasSize(1);
		// The same event published again, as the event registry republishes one not marked complete: the listener's
		// replay guard drops it.
		List<SourcePushed> published = this.events.stream(SourcePushed.class).toList();
		assertThat(published).as("published once, the delivery seen again dropped before it").hasSize(1);
		new TransactionTemplate(this.transactions)
			.executeWithoutResult(status -> this.context.publishEvent(published.getFirst()));
		awaitListeners();
		assertThat(this.drift.warnings(this.job).getFirst().commits()).as("the same event again").containsExactly(first);
		assertThat(driftReads(this.job)).hasSize(1);
		this.worker.runDue(Lane.GITHUB_SHORT);
		assertThat(driftReads(this.job)).containsExactly("SUCCEEDED");
		assertThat(this.drift.warnings(this.job)).as("the read finds nothing else").hasSize(1);

		sourceDelivers("push", GITHUB.pushEvent(this.installation, this.sourceId, "refs/heads/feature", "f".repeat(40),
				List.of("f".repeat(40))));
		sourceDelivers("push", pushEvent(SourcePushed.DELETED, List.of()));
		driftRead();
		assertThat(this.drift.warnings(this.job).getFirst().commits()).as("another branch, or the default branch deleted")
			.containsExactly(first);
		assertThat(driftReads(this.job)).as("and no read").hasSize(1);

		this.clock.advance(Duration.ofMinutes(3));
		String second = GITHUB.push("lan-dev", this.sourceName, "main", "app.js", "console.log('Lovable 2')\n");
		String third = GITHUB.push("lan-dev", this.sourceName, "main", "app.js", "console.log('Lovable 3')\n");
		sourceDelivers("push", pushEvent(third, List.of(second, third)));
		driftRead();
		// A reset back to the first commit lists no commit: its after counts, although it was listed before.
		this.clock.advance(Duration.ofMinutes(1));
		sourceDelivers("push", pushEvent(first, List.of()));
		driftRead();

		assertThat(this.drift.warnings(this.job)).as("the same warning, its list grown, newest last").singleElement()
			.satisfies(warning -> {
				assertThat(warning.commits()).containsExactly(first, second, third, first);
				assertThat(warning.headSha()).isNull();
				assertThat(warning.detectedAt()).as("the first push's date").isEqualTo(firstPush);
			});
		assertThat(notices("repo.drift_detected")).as("still once each").isEqualTo(2);
		assertThat(this.jdbc.sql("SELECT count(*) FROM audit.audit_entry WHERE action = 'repo.drift_detected'")
			.query(Long.class).single()).isOne();
		assertThat(driftReads(this.job)).as("each push read the Job").containsExactly("SUCCEEDED", "SUCCEEDED", "SUCCEEDED");
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/drift").with(as(this.customer, Role.CUSTOMER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.warnings.length()").value(1))
			.andExpect(jsonPath("$.warnings[0].kind").value("COMMITS"))
			.andExpect(jsonPath("$.warnings[0].commits").value(4))
			.andExpect(jsonPath("$.reconfirmNeeded").value(false));
		List<GitHubStub.Seen> seen = List.copyOf(GITHUB.seen);
		assertThat(seen.subList(setupRequests, seen.size())).as("no head read and no compare")
			.noneMatch(request -> request.path().contains("/compare/") || request.path().contains("/git/ref/"));
		awaitListeners();
	}

	/**
	 * Story 24.6 (FR-20): ten pushes to a running Job, an AI builder syncing as it works, are one warning: the Customer
	 * has one {@code repo.drift_detected} notice and one email for them, and so has the Mentor, while its list names
	 * every commit and each push is read.
	 */
	@Test
	void tenPushesAreOneNoticeAndOneEmailEach() throws Exception {
		this.jdbc.sql("UPDATE identity.account SET google_email = :email WHERE id = :id")
			.param("email", "lan-" + this.customer + "@example.test").param("id", this.customer).update();
		this.jdbc.sql("UPDATE identity.account SET google_email = :email WHERE id = :id")
			.param("email", "hung-" + this.mentor + "@example.test").param("id", this.mentor).update();
		activeAfterJ7();
		List<String> shas = new ArrayList<>();
		for (int i = 1; i <= 10; i++) {
			String sha = GITHUB.push("lan-dev", this.sourceName, "main", "app.js", "console.log(" + i + ")\n");
			shas.add(sha);
			pushed(sha);
		}

		assertThat(this.drift.warnings(this.job)).singleElement()
			.satisfies(warning -> assertThat(warning.commits()).isEqualTo(shas));
		assertThat(notices("repo.drift_detected", this.customer)).isOne();
		assertThat(emails("repo.drift_detected", this.customer)).isOne();
		assertThat(notices("repo.drift_detected", this.mentor)).isOne();
		assertThat(emails("repo.drift_detected", this.mentor)).isOne();
		assertThat(driftReads(this.job)).hasSize(10).containsOnly("SUCCEEDED");
		awaitListeners();
	}

	/**
	 * Story 24.6: a push whose {@code after} is the commit the Customer confirmed brings nothing past the copy -- a reset
	 * back to it, say -- so it adds nothing and warns nothing, but the Job is still read, since such a revert may
	 * restore {@code vibefix.yml}. The list never holds the confirmed commit, even when a push lists it among new ones,
	 * and a push that lists no commit counts its {@code after}.
	 */
	@Test
	void aPushEndingAtTheConfirmedCommitDoesNothingAndTheListNeverHoldsIt() throws Exception {
		activeAfterJ7();
		String approved = this.setups.latest(this.job).orElseThrow().approvedSha();

		sourceDelivers("push", pushEvent(approved, List.of()));
		driftRead();

		assertThat(this.drift.warnings(this.job)).as("the pinned commit").isEmpty();
		assertThat(driftReads(this.job)).as("but the Job is read").containsExactly("SUCCEEDED");
		assertThat(notices("repo.drift_detected")).isZero();

		String next = GITHUB.push("lan-dev", this.sourceName, "main", "app.js", "console.log('Lovable')\n");
		// A forced push back past the copy, listing the confirmed commit among the new one.
		pushed(next, List.of(approved, next));
		String reset = GITHUB.push("lan-dev", this.sourceName, "main", "app.js", "console.log('Lovable 2')\n");
		// A push that lists no commit, as a reset or a forced push to a commit already pushed does.
		pushed(reset, List.of());

		assertThat(this.drift.warnings(this.job)).singleElement()
			.satisfies(warning -> assertThat(warning.commits()).containsExactly(next, reset));
		assertThat(driftReads(this.job)).containsExactly("SUCCEEDED", "SUCCEEDED", "SUCCEEDED");
		awaitListeners();
	}

	/**
	 * Story 24.6: a push GitHub dates at or before the head read the Customer confirmed, delivered after the
	 * confirmation (late, or again), brings nothing that head does not hold, so it changes nothing: no commit is listed
	 * and nobody is told, and the Job is only read. A push dated after it counts.
	 */
	@Test
	void aPushDatedBeforeTheConfirmedHeadReadChangesNothing() throws Exception {
		activeAfterJ7();
		Instant headRead = this.setups.latest(this.job).orElseThrow().headReadAt();
		String late = GITHUB.push("lan-dev", this.sourceName, "main", "app.js", "console.log('Lovable')\n");

		this.clock.advance(Duration.ofMinutes(1));
		sourceDelivers("push", GITHUB.pushEvent(this.installation, this.sourceId, "refs/heads/main", late, List.of(late),
				headRead.minusSeconds(60).getEpochSecond(), "main"));
		sourceDelivers("push", GITHUB.pushEvent(this.installation, this.sourceId, "refs/heads/main", late, List.of(late),
				headRead.getEpochSecond(), "main"));
		driftRead();

		assertThat(this.drift.warnings(this.job)).as("dated before or at the head read").isEmpty();
		assertThat(notices("repo.drift_detected")).isZero();
		assertThat(driftReads(this.job)).containsExactly("SUCCEEDED", "SUCCEEDED");

		String after = GITHUB.push("lan-dev", this.sourceName, "main", "app.js", "console.log('Lovable 2')\n");
		sourceDelivers("push", GITHUB.pushEvent(this.installation, this.sourceId, "refs/heads/main", after, List.of(after),
				this.clock.instant().getEpochSecond(), "main"));
		driftRead();

		assertThat(this.drift.warnings(this.job)).singleElement()
			.satisfies(warning -> assertThat(warning.commits()).containsExactly(after));
		awaitListeners();
	}

	/**
	 * Story 24.6: a push counts on the branch the copy was made from, the default branch the Setup read: after the
	 * default branch is switched on GitHub, a push to the old one still counts and a push to the new one does nothing.
	 */
	@Test
	void aPushCountsOnTheBranchTheCopyWasMadeFrom() throws Exception {
		activeAfterJ7();
		assertThat(this.setups.latest(this.job).orElseThrow().defaultBranch()).isEqualTo("main");
		String sha = GITHUB.push("lan-dev", this.sourceName, "main", "app.js", "console.log('Lovable')\n");

		sourceDelivers("push", GITHUB.pushEvent(this.installation, this.sourceId, "refs/heads/develop", "d".repeat(40),
				List.of("d".repeat(40)), null, "develop"));
		driftRead();
		assertThat(this.drift.warnings(this.job)).as("the new default branch").isEmpty();
		assertThat(driftReads(this.job)).as("and no read").isEmpty();

		sourceDelivers("push", GITHUB.pushEvent(this.installation, this.sourceId, "refs/heads/main", sha, List.of(sha), null,
				"develop"));
		driftRead();
		assertThat(this.drift.warnings(this.job)).singleElement()
			.satisfies(warning -> assertThat(warning.commits()).containsExactly(sha));
		assertThat(driftReads(this.job)).containsExactly("SUCCEEDED");
		awaitListeners();
	}

	/**
	 * Story 24.6: two pushes handled at once both wait for the Job's lock, so neither reads the list the other is
	 * writing: while another transaction holds it, no warning is made; released, the one warning lists both pushes.
	 */
	@Test
	void twoPushesHandledAtOnceWaitForTheJobsLockAndAreBothListed() throws Exception {
		activeAfterJ7();
		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger holder = new AtomicInteger();
		CompletableFuture<Void> holding = CompletableFuture.runAsync(
				() -> new TransactionTemplate(this.transactions).executeWithoutResult(status -> {
					this.jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended('repo.source_drift:' || :job, 0))")
						.param("job", this.job.toString())
						.query((rs, n) -> 1)
						.list();
					holder.set(this.jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single());
					locked.countDown();
					await(release);
				}));
		assertThat(locked.await(15, TimeUnit.SECONDS)).isTrue();
		String one = GITHUB.push("lan-dev", this.sourceName, "main", "app.js", "console.log('Lovable')\n");
		String two = GITHUB.push("lan-dev", this.sourceName, "main", "app.js", "console.log('Lovable 2')\n");
		try {
			sourceDelivers("push", pushEvent(one, List.of(one)));
			sourceDelivers("push", pushEvent(two, List.of(two)));
			awaitBlockedBy(holder.get(), 2);
			assertThat(this.drift.warnings(this.job)).as("while the lock is held").isEmpty();
		}
		finally {
			release.countDown();
		}
		holding.get(15, TimeUnit.SECONDS);
		awaitListeners();

		assertThat(this.drift.warnings(this.job)).singleElement()
			.satisfies(warning -> assertThat(warning.commits()).containsExactlyInAnyOrder(one, two));
		assertThat(notices("repo.drift_detected")).isEqualTo(2);
	}

	/**
	 * Story 24.6: a push while the copy waits to be dispatched raises the new-commits warning; then the copy finds the
	 * head moved and asks the Customer again. The head they confirm holds those commits, so the confirmation removes
	 * the warning, and a later push raises a fresh one.
	 */
	@Test
	void aConfirmationOfAMovedHeadRemovesTheWarningAndALaterPushRaisesAFreshOne() throws Exception {
		confirmTheHead();
		String moved = GITHUB.push("lan-dev", this.sourceName, "main", "app.js", "console.log('Lovable')\n");
		pushed(moved);
		assertThat(this.drift.warnings(this.job)).singleElement()
			.satisfies(warning -> assertThat(warning.commits()).containsExactly(moved));

		run();
		Setup waiting = this.setups.latest(this.job).orElseThrow();
		assertThat(waiting.status()).as("the head moved before the copy").isEqualTo("CONFIRMING");
		assertThat(waiting.headSha()).isEqualTo(moved);
		confirm(moved).andExpect(status().isOk());

		assertThat(this.drift.warnings(this.job)).as("the head confirmed holds those commits").isEmpty();
		String later = GITHUB.push("lan-dev", this.sourceName, "main", "app.js", "console.log('Lovable 2')\n");
		pushed(later);

		assertThat(this.drift.warnings(this.job)).singleElement()
			.satisfies(warning -> assertThat(warning.commits()).containsExactly(later));
		assertThat(notices("repo.drift_detected")).as("told of each warning").isEqualTo(4);
		awaitListeners();
	}

	/** Story 24.6 (FR-53): the list keeps the newest 250 commits, and pushes past that still update it in place. */
	@Test
	void theListKeepsTheNewest250AndLaterPushesStillUpdateIt() throws Exception {
		activeAfterJ7();
		List<String> batch = shas(1, 200);
		List<String> more = shas(201, 100);

		sourceDelivers("push", pushEvent(batch.getLast(), batch));
		driftRead();
		sourceDelivers("push", pushEvent(more.getLast(), more));
		driftRead();

		List<String> kept = this.drift.warnings(this.job).getFirst().commits();
		assertThat(kept).hasSize(ProductSources.MAX_DRIFT_COMMITS);
		assertThat(kept.getFirst()).as("the oldest 50 dropped").isEqualTo(batch.get(50));
		assertThat(kept.getLast()).isEqualTo(more.getLast());

		String last = shas(301, 1).getFirst();
		sourceDelivers("push", pushEvent(last, List.of(last)));
		driftRead();

		assertThat(this.drift.warnings(this.job)).singleElement().satisfies(warning -> {
			assertThat(warning.commits()).hasSize(ProductSources.MAX_DRIFT_COMMITS);
			assertThat(warning.commits().getFirst()).isEqualTo(batch.get(51));
			assertThat(warning.commits().getLast()).isEqualTo(last);
		});
		assertThat(notices("repo.drift_detected")).isEqualTo(2);
		assertThat(driftReads(this.job)).containsExactly("SUCCEEDED", "SUCCEEDED", "SUCCEEDED");
		awaitListeners();
	}

	/**
	 * Story 24.6: drift is told from the Customer's confirmation on, J7 or not. A push while the copy runs (the Setup
	 * {@code RUNNING}, its run dispatched at the confirmed head) and one after it, while the Job is still Starting and
	 * waits for the invitations, both count on the Job's one warning, and each is read; the copy still pins the confirmed
	 * commit.
	 */
	@Test
	void pushesAfterTheConfirmationCountBeforeJ7() throws Exception {
		String head = confirmTheHead();
		run();
		Setup copying = this.setups.latest(this.job).orElseThrow();
		assertThat(copying.status()).isEqualTo("RUNNING");
		assertThat(copying.correlationId()).as("the copy run is out").isNotNull();
		assertThat(this.busy.job(this.job).orElseThrow().state()).isEqualTo("STARTING");

		String during = GITHUB.push("lan-dev", this.sourceName, "main", "app.js", "console.log('Lovable')\n");
		pushed(during);

		assertThat(this.drift.warnings(this.job)).singleElement()
			.satisfies(warning -> assertThat(warning.commits()).containsExactly(during));
		assertThat(notices("repo.drift_detected")).as("the Customer and the Mentor").isEqualTo(2);

		copyRunSucceeds();
		Setup done = this.setups.latest(this.job).orElseThrow();
		assertThat(done.status()).isEqualTo("DONE");
		assertThat(done.approvedSha()).as("the copy pins the confirmed commit").isEqualTo(head);
		assertThat(this.busy.job(this.job).orElseThrow().state()).as("waiting for the invitations").isEqualTo("STARTING");
		String after = GITHUB.push("lan-dev", this.sourceName, "main", "app.js", "console.log('Lovable 2')\n");
		pushed(after);

		assertThat(this.drift.warnings(this.job)).singleElement()
			.satisfies(warning -> assertThat(warning.commits()).containsExactly(during, after));
		assertThat(notices("repo.drift_detected")).isEqualTo(2);
		assertThat(driftReads(this.job)).containsExactly("SUCCEEDED", "SUCCEEDED");
		awaitListeners();
	}

	/**
	 * Story 24.6: every push is read too, so one that leaves {@code vibefix.yml} unpublished warns
	 * {@code WORKFLOW_CHANGED} and opens {@code product.add_workflow} once, however many pushes follow, and a push whose
	 * file still matches raises neither. A workflow that differs never hides the commits, which stay one warning.
	 */
	@Test
	void aPushThatLeavesTheWorkflowFileUnpublishedWarnsItOnceAndOpensItsToDo() throws Exception {
		activeAfterJ7();
		// From now on the file is served from the repository's own history, where the pushes change it.
		GITHUB.sourceFiles.remove(this.source + ":" + WORKFLOW_PATH);

		pushed(GITHUB.push("lan-dev", this.sourceName, "main", "app.js", "console.log('Lovable')\n"));

		assertThat(this.drift.warnings(this.job)).extracting(SourceDrift.Warning::kind)
			.containsExactly(SourceDrift.Kind.COMMITS);
		assertThat(sourceStatus()).as("a matching file").isEqualTo("READY");
		assertThat(productTodos()).isEmpty();
		assertThat(workflowCheckFailures()).isEmpty();

		pushed(GITHUB.push("lan-dev", this.sourceName, "main", WORKFLOW_PATH, publishedWorkflow() + "# changed\n"));
		pushed(GITHUB.push("lan-dev", this.sourceName, "main", "app.js", "console.log('Lovable 2')\n"));

		assertThat(this.drift.warnings(this.job)).extracting(SourceDrift.Warning::kind)
			.containsExactly(SourceDrift.Kind.COMMITS, SourceDrift.Kind.WORKFLOW_CHANGED);
		assertThat(this.drift.warnings(this.job).getFirst().commits()).hasSize(3);
		assertThat(this.drift.warnings(this.job).get(1).fullName()).isEqualTo(this.source);
		assertThat(sourceStatus()).isEqualTo("WORKFLOW_MISMATCH");
		assertThat(productTodos()).containsExactly("product.add_workflow");
		assertThat(workflowCheckFailures()).as("once, to the Product's Customer").containsExactly(this.customer);
		assertThat(notices("repo.drift_detected")).as("each warning once, to the Customer and the Mentor").isEqualTo(4);
		assertThat(this.jdbc.sql("SELECT count(*) FROM audit.audit_entry WHERE action = 'repo.drift_detected'")
			.query(Long.class).single()).isEqualTo(2);
		awaitListeners();
	}

	/**
	 * Story 24.6: a {@code repository} delivery is matched by the repository's permanent ID and read through the Source
	 * App. Renamed warns {@code RENAMED} once, however often it is hinted at, and opens {@code repo.reconfirm_source},
	 * which the Customer's re-confirmation closes, making the new name the Final Merge target; archived warns
	 * {@code ARCHIVED}. Made private (R7) and edited are no drift: they do nothing and read nothing. Every request under
	 * the Source Repository went through the Customer's Source App.
	 */
	@Test
	void aRenameWarnsOnceAndWaitsForTheCustomersReconfirmationAndMadePrivateIsNoDrift() throws Exception {
		activeAfterJ7();
		long id = this.sourceId;
		String name = this.sourceName;
		// What the Setup read is left out below; it read the Source through the Source App too (Story 23.6b).
		int setupRequests = GITHUB.seen.size();

		GITHUB.rename(id, "lan-dev", name + "-v2");
		sourceDelivers("repository", GITHUB.repositoryEvent("renamed", this.installation, id));
		driftRead();
		this.clock.advance(Duration.ofMinutes(1));
		sourceDelivers("repository", GITHUB.repositoryEvent("renamed", this.installation, id));
		driftRead();

		assertThat(this.drift.warnings(this.job)).singleElement().satisfies(warning -> {
			assertThat(warning.kind()).isEqualTo(SourceDrift.Kind.RENAMED);
			assertThat(warning.fullName()).isEqualTo("lan-dev/" + name + "-v2");
			assertThat(warning.commits()).isEmpty();
		});
		assertThat(driftReads(this.job)).as("each delivery read the Job").containsExactly("SUCCEEDED", "SUCCEEDED");
		assertThat(notices("repo.drift_detected")).as("the Customer and the Mentor, once").isEqualTo(2);
		assertThat(openReconfirmTodos()).isOne();
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/drift").with(as(this.customer, Role.CUSTOMER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.reconfirmNeeded").value(true))
			.andExpect(jsonPath("$.warnings[0].fullName").value("lan-dev/" + name + "-v2"));
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/drift").with(as(this.mentor, Role.MENTOR)))
			.andExpect(jsonPath("$.reconfirmNeeded").value(false));
		this.mvc.perform(post("/api/v1/jobs/" + this.job + "/source/reconfirm").with(as(this.customer, Role.CUSTOMER))
			.with(csrf()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.reconfirmNeeded").value(false));
		assertThat(this.jdbc.sql("SELECT repository_full_name FROM product.product WHERE id = :id").param("id", this.product)
			.query(String.class).single()).as("the new name is the Final Merge target now").isEqualTo("lan-dev/" + name + "-v2");
		assertThat(openReconfirmTodos()).isZero();

		GITHUB.archive(id);
		this.clock.advance(Duration.ofMinutes(1));
		sourceDelivers("repository", GITHUB.repositoryEvent("archived", this.installation, id));
		driftRead();
		assertThat(this.drift.warnings(this.job)).extracting(SourceDrift.Warning::kind)
			.containsExactly(SourceDrift.Kind.RENAMED, SourceDrift.Kind.ARCHIVED);

		// R7 (Story 23.5b): the Source App reads a private repository, so making it private is no drift.
		GITHUB.publish("lan-dev", name + "-v2", id, githubUserId(this.customer), false, false);
		this.clock.advance(Duration.ofMinutes(1));
		sourceDelivers("repository", GITHUB.repositoryEvent("privatized", this.installation, id));
		sourceDelivers("repository", GITHUB.repositoryEvent("edited", this.installation, id));
		driftRead();
		assertThat(this.drift.warnings(this.job)).extracting(SourceDrift.Warning::kind).as("made private warns nothing")
			.containsExactly(SourceDrift.Kind.RENAMED, SourceDrift.Kind.ARCHIVED);
		assertThat(driftReads(this.job)).as("and reads nothing").hasSize(3);

		// Since the Setup, every request under the Source Repository, by either name, went through the Customer's Source
		// App: a lookup with its JWT, else a token minted from its installation and narrowed to the Source. The Platform
		// App read no Source Repository.
		List<GitHubStub.Seen> seen = List.copyOf(GITHUB.seen);
		assertThat(seen.subList(setupRequests, seen.size())).filteredOn(request -> List.of(this.source, this.source + "-v2")
			.stream()
			.anyMatch(base -> request.path().equals("/repos/" + base) || request.path().startsWith("/repos/" + base + "/")))
			.isNotEmpty()
			.allSatisfy(request -> {
				if (request.path().endsWith("/installation")) {
					assertThat(GitHubStub.issuerOf(request.authorization())).isEqualTo(GitHubStub.SOURCE_APP);
					return;
				}
				String token = request.authorization().replaceFirst("^Bearer ", "");
				assertThat(GITHUB.tokensOf(this.installation)).as(request.method() + " " + request.path()).contains(token);
				assertThat(GITHUB.grantOf(token).path("repository_ids").toString()).isEqualTo("[" + id + "]");
			});
		// The copy's dispatch read its target by ID (Story 23.6a), with the Customer's Source App as well.
		assertThat(seen).filteredOn(request -> request.path().equals("/repositories/" + id))
			.allSatisfy(request -> assertThat(GITHUB.tokensOf(this.installation))
				.contains(request.authorization().replaceFirst("^Bearer ", "")));
		assertThat(GITHUB.revokedTokens).containsAll(GITHUB.tokensOf(this.installation));
		awaitListeners();
	}

	/**
	 * Story 24.6: an owner renamed on GitHub sends {@code installation_target} {@code renamed}, matched by the
	 * installation: the read finds the Source under its new owner and warns it once, as today's rules name a new owner
	 * ({@code TRANSFERRED}), and the Customer is asked to re-confirm it.
	 */
	@Test
	void anOwnerRenamedIsReadFromItsInstallationTargetDelivery() throws Exception {
		activeAfterJ7();
		long owner = githubUserId(this.customer);

		GITHUB.rename(this.sourceId, "lan-dev-moi", this.sourceName);
		sourceDelivers("installation_target",
				GITHUB.installationTargetEvent("renamed", this.installation, owner, "lan-dev-moi", "lan-dev"));
		driftRead();

		assertThat(this.drift.warnings(this.job)).singleElement().satisfies(warning -> {
			assertThat(warning.kind()).isEqualTo(SourceDrift.Kind.TRANSFERRED);
			assertThat(warning.fullName()).isEqualTo("lan-dev-moi/" + this.sourceName);
		});
		assertThat(driftReads(this.job)).containsExactly("SUCCEEDED");
		assertThat(openReconfirmTodos()).isOne();
		assertThat(notices("repo.drift_detected")).isEqualTo(2);
		awaitListeners();
	}

	/**
	 * Story 23.5b, Story 24.6: a Source Repository transferred to an account where the Source App is installed is still
	 * found by its old name (GitHub's 301 to its ID) when the {@code repository} delivery of the transfer is read, bound
	 * to that account's installation, and warned as transferred; the Customer re-confirms it.
	 */
	@Test
	void aSourceTransferredToAnAccountWithTheAppIsFoundByItsOldNameAndReconfirmed() throws Exception {
		activeAfterJ7();
		long id = this.sourceId;
		String name = this.sourceName;
		long owner = githubUserId(this.customer);

		GITHUB.mergeInstallations.get(this.installation).deselect(id);
		GITHUB.rename(id, "lan-shop", name);
		long moved = GITHUB.installSource("lan-shop", name, id, owner);
		sourceDelivers("repository", GITHUB.repositoryEvent("transferred", moved, id));
		driftRead();

		assertThat(this.drift.warnings(this.job)).singleElement().satisfies(warning -> {
			assertThat(warning.kind()).isEqualTo(SourceDrift.Kind.TRANSFERRED);
			assertThat(warning.fullName()).isEqualTo("lan-shop/" + name);
		});
		assertThat(GITHUB.seen).as("the old name's 301 is followed to the repository's ID")
			.anyMatch(request -> request.path().equals("/repositories/" + id + "/installation"));
		assertThat(this.jdbc.sql("SELECT installation_id FROM product.source_app_binding WHERE product_id = :id AND status = 'BOUND'")
			.param("id", this.product).query(Long.class).single()).as("bound to the new account's installation").isEqualTo(moved);
		assertThat(openReconfirmTodos()).isOne();
		this.mvc.perform(post("/api/v1/jobs/" + this.job + "/source/reconfirm").with(as(this.customer, Role.CUSTOMER))
			.with(csrf()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.reconfirmNeeded").value(false));
		assertThat(this.jdbc.sql("SELECT repository_full_name FROM product.product WHERE id = :id").param("id", this.product)
			.query(String.class).single()).isEqualTo("lan-shop/" + name);
		awaitListeners();
	}

	/**
	 * Story 24.6: an archived Source Repository warns {@code ARCHIVED}; a deleted one leaves the Source App nothing to
	 * reach, so it warns {@code GONE} under the name it was read by, opens {@code product.install_source_app}, and asks
	 * no re-confirmation.
	 */
	@Test
	void anArchivedSourceWarnsArchivedAndADeletedOneGone() throws Exception {
		activeAfterJ7();

		GITHUB.archive(this.sourceId);
		sourceDelivers("repository", GITHUB.repositoryEvent("archived", this.installation, this.sourceId));
		driftRead();
		assertThat(this.drift.warnings(this.job)).extracting(SourceDrift.Warning::kind)
			.containsExactly(SourceDrift.Kind.ARCHIVED);

		byte[] deleted = GITHUB.repositoryEvent("deleted", this.installation, this.sourceId);
		// GitHub takes a deleted repository out of every installation's selection.
		GITHUB.mergeInstallations.get(this.installation).deselect(this.sourceId);
		this.clock.advance(Duration.ofMinutes(1));
		sourceDelivers("repository", deleted);
		driftRead();

		assertThat(this.drift.warnings(this.job)).extracting(SourceDrift.Warning::kind)
			.containsExactly(SourceDrift.Kind.ARCHIVED, SourceDrift.Kind.GONE);
		assertThat(this.drift.warnings(this.job).get(1).fullName()).isEqualTo(this.source);
		assertThat(sourceStatus()).isEqualTo("SOURCE_APP_WRONG_REPOSITORY");
		assertThat(productTodos()).containsExactly("product.install_source_app");
		assertThat(openReconfirmTodos()).as("nothing to re-confirm").isZero();
		assertThat(notices("repo.drift_detected")).isEqualTo(4);
		awaitListeners();
	}

	/**
	 * Story 24.6, with Story 23.5c's status: the Source Repository removed from the Source App's selection warns
	 * {@code GONE}; the App suspended, then uninstalled, warns {@code APP_UNINSTALLED} once. Each read records the
	 * Product's status, which keeps {@code product.install_source_app} open and tells the Customer of each new failing
	 * status once. Installed again, the new installation's {@code created} delivery names the Source, so it finds the
	 * Product its old installation was bound on: the read binds it there and closes the to-do, warning nothing. With a
	 * {@code vibefix.yml} that differs, the next push moves the to-do to {@code product.add_workflow}. None asks a
	 * re-confirmation.
	 */
	@Test
	void theSourceAppDeselectedSuspendedOrUninstalledWarnsOnceAndOpensItsToDo() throws Exception {
		activeAfterJ7();
		long owner = githubUserId(this.customer);
		assertThat(sourceStatus()).as("the Setup check found it ready").isEqualTo("READY");
		assertThat(this.jdbc.sql("SELECT workflow_blob FROM product.product WHERE id = :id").param("id", this.product)
			.query(String.class).single()).as("with the blob it matched").matches("[0-9a-f]{40}");

		GITHUB.mergeInstallations.get(this.installation).deselect(this.sourceId);
		sourceDelivers("installation_repositories",
				GITHUB.installationRepositoriesEvent("removed", this.installation, List.of(this.sourceId)));
		driftRead();

		assertThat(this.drift.warnings(this.job)).extracting(SourceDrift.Warning::kind)
			.containsExactly(SourceDrift.Kind.GONE);
		assertThat(sourceStatus()).isEqualTo("SOURCE_APP_WRONG_REPOSITORY");
		assertThat(productTodos()).containsExactly("product.install_source_app");

		GITHUB.mergeInstallations.get(this.installation).repository(this.sourceId, this.source, owner).suspended = true;
		this.clock.advance(Duration.ofMinutes(1));
		sourceDelivers("installation", GITHUB.installationEvent("suspend", this.installation));
		driftRead();
		GITHUB.mergeInstallations.remove(this.installation);
		GITHUB.repositoryInstallations.values().remove(this.installation);
		this.clock.advance(Duration.ofMinutes(1));
		sourceDelivers("installation", GITHUB.installationEvent("deleted", this.installation));
		driftRead();

		assertThat(this.drift.warnings(this.job)).extracting(SourceDrift.Warning::kind)
			.containsExactly(SourceDrift.Kind.GONE, SourceDrift.Kind.APP_UNINSTALLED);
		assertThat(sourceStatus()).isEqualTo("SOURCE_APP_MISSING");
		assertThat(productTodos()).containsExactly("product.install_source_app");
		assertThat(workflowCheckFailures()).as("each new failing status once").containsExactly(this.customer, this.customer);
		assertThat(driftReads(this.job)).containsExactly("SUCCEEDED", "SUCCEEDED", "SUCCEEDED");
		assertThat(notices("repo.drift_detected")).as("once per new state, to the Customer and the Mentor").isEqualTo(4);
		assertThat(this.jdbc.sql("SELECT count(*) FROM notification.todo WHERE todo_key = 'repo.reconfirm_source'")
			.query(Long.class).single()).as("nothing to re-confirm").isZero();
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/drift").with(as(this.customer, Role.CUSTOMER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.warnings[0].kind").value("GONE"))
			.andExpect(jsonPath("$.warnings[0].fullName").value(this.source))
			.andExpect(jsonPath("$.warnings[1].kind").value("APP_UNINSTALLED"))
			.andExpect(jsonPath("$.reconfirmNeeded").value(false));

		// Installed again: a new installation, which the Product is not bound on, names the Source.
		long old = this.installation;
		this.installation = GITHUB.installSource("lan-dev", this.sourceName, this.sourceId, owner);
		this.clock.advance(Duration.ofMinutes(1));
		sourceDelivers("installation", GITHUB.installationEvent("created", this.installation, List.of(this.sourceId)));
		driftRead();

		assertThat(sourceStatus()).as("ready again").isEqualTo("READY");
		assertThat(productTodos()).as("its to-do closed").isEmpty();
		assertThat(this.jdbc.sql("SELECT installation_id FROM product.source_app_binding WHERE product_id = :id AND status = 'BOUND'")
			.param("id", this.product).query(Long.class).single()).as("bound on the new installation, not " + old)
			.isEqualTo(this.installation);
		assertThat(this.drift.warnings(this.job)).as("no warning for a fix").hasSize(2);

		GITHUB.sourceFiles.put(this.source + ":" + WORKFLOW_PATH, publishedWorkflow() + "# changed\n");
		pushed(GITHUB.push("lan-dev", this.sourceName, "main", "app.js", "console.log('Lovable')\n"));

		assertThat(sourceStatus()).isEqualTo("WORKFLOW_MISMATCH");
		assertThat(productTodos()).containsExactly("product.add_workflow");
		assertThat(workflowCheckFailures()).hasSize(3);
		assertThat(this.drift.warnings(this.job)).extracting(SourceDrift.Warning::kind)
			.containsExactlyInAnyOrder(SourceDrift.Kind.GONE, SourceDrift.Kind.APP_UNINSTALLED, SourceDrift.Kind.COMMITS,
					SourceDrift.Kind.WORKFLOW_CHANGED);
		awaitListeners();
	}

	/**
	 * Story 24.6: a fix on GitHub is read as soon as GitHub tells of it, so the Product's status and its to-do follow
	 * within seconds, and no warning is made for it. The Source App resumed ({@code unsuspend}) with a permission still
	 * to accept, then its permissions accepted ({@code new_permissions_accepted}), the Source selected again
	 * ({@code added}), and the Source unarchived each read the Job; once it is ready again,
	 * {@code product.install_source_app} closes.
	 */
	@Test
	void aFixOnGitHubIsReadAndClosesTheToDoWithNoWarning() throws Exception {
		activeAfterJ7();
		long owner = githubUserId(this.customer);
		GitHubStub.MergeInstallation installed = GITHUB.mergeInstallations.get(this.installation);

		installed.suspended = true;
		sourceDelivers("installation", GITHUB.installationEvent("suspend", this.installation));
		driftRead();
		assertThat(sourceStatus()).isEqualTo("SOURCE_APP_MISSING");
		assertThat(productTodos()).containsExactly("product.install_source_app");

		// Resumed, with a permission the App asks for not accepted yet.
		installed.suspended = false;
		installed.permissions.remove("actions");
		this.clock.advance(Duration.ofMinutes(1));
		sourceDelivers("installation", GITHUB.installationEvent("unsuspend", this.installation, List.of(this.sourceId)));
		driftRead();
		assertThat(sourceStatus()).as("resumed, a permission still to accept").isEqualTo("SOURCE_APP_MISSING");
		assertThat(productTodos()).containsExactly("product.install_source_app");
		installed.permissions.put("actions", "write");
		this.clock.advance(Duration.ofMinutes(1));
		sourceDelivers("installation", GITHUB.installationEvent("new_permissions_accepted", this.installation));
		driftRead();
		assertThat(sourceStatus()).as("its permissions accepted again").isEqualTo("READY");
		assertThat(productTodos()).isEmpty();

		installed.deselect(this.sourceId);
		this.clock.advance(Duration.ofMinutes(1));
		sourceDelivers("installation_repositories",
				GITHUB.installationRepositoriesEvent("removed", this.installation, List.of(this.sourceId)));
		driftRead();
		assertThat(productTodos()).containsExactly("product.install_source_app");
		installed.repository(this.sourceId, this.source, owner);
		this.clock.advance(Duration.ofMinutes(1));
		sourceDelivers("installation_repositories",
				GITHUB.installationRepositoriesEvent("added", this.installation, List.of(this.sourceId)));
		driftRead();
		assertThat(sourceStatus()).as("selected again").isEqualTo("READY");
		assertThat(productTodos()).isEmpty();

		GITHUB.archive(this.sourceId);
		this.clock.advance(Duration.ofMinutes(1));
		sourceDelivers("repository", GITHUB.repositoryEvent("archived", this.installation, this.sourceId));
		driftRead();
		GITHUB.unarchive(this.sourceId);
		this.clock.advance(Duration.ofMinutes(1));
		sourceDelivers("repository", GITHUB.repositoryEvent("unarchived", this.installation, this.sourceId));
		driftRead();

		assertThat(this.drift.warnings(this.job)).extracting(SourceDrift.Warning::kind).as("the drift, never its fix")
			.containsExactly(SourceDrift.Kind.APP_UNINSTALLED, SourceDrift.Kind.GONE, SourceDrift.Kind.ARCHIVED);
		assertThat(driftReads(this.job)).hasSize(7).containsOnly("SUCCEEDED");
		assertThat(sourceStatus()).isEqualTo("READY");
		assertThat(productTodos()).isEmpty();
		assertThat(notices("repo.drift_detected")).isEqualTo(6);
		awaitListeners();
	}

	/**
	 * Story 24.6, with Story 23.5c's status: the Source App uninstalled warns {@code APP_UNINSTALLED} at its
	 * {@code installation} {@code deleted} delivery and opens {@code product.install_source_app}, telling the Customer
	 * once; GitHub delivering it again under another ID reads the Job again and warns nothing more.
	 */
	@Test
	void anUninstalledSourceAppWarnsOnceAndOpensItsToDo() throws Exception {
		activeAfterJ7();
		GITHUB.mergeInstallations.remove(this.installation);
		GITHUB.repositoryInstallations.values().remove(this.installation);
		byte[] deleted = GITHUB.installationEvent("deleted", this.installation);

		sourceDelivers("installation", deleted);
		driftRead();
		this.clock.advance(Duration.ofMinutes(1));
		sourceDelivers("installation", deleted);
		driftRead();

		assertThat(this.drift.warnings(this.job)).singleElement().satisfies(warning -> {
			assertThat(warning.kind()).isEqualTo(SourceDrift.Kind.APP_UNINSTALLED);
			assertThat(warning.fullName()).isEqualTo(this.source);
		});
		assertThat(driftReads(this.job)).containsExactly("SUCCEEDED", "SUCCEEDED");
		assertThat(sourceStatus()).isEqualTo("SOURCE_APP_MISSING");
		assertThat(productTodos()).containsExactly("product.install_source_app");
		assertThat(workflowCheckFailures()).as("once, to the Product's Customer").containsExactly(this.customer);
		assertThat(notices("repo.drift_detected")).as("the Customer and the Mentor, once").isEqualTo(2);
		awaitListeners();
	}

	/**
	 * Story 24.6: GitHub not answering the read warns nothing yet, and the operation tries again after its backoff,
	 * when the archive is found.
	 */
	@Test
	void aReadGitHubCannotAnswerWarnsNothingYetAndIsTriedAgain() throws Exception {
		activeAfterJ7();
		GITHUB.archive(this.sourceId);
		GITHUB.unavailable.add("GET /repos/" + this.source);
		try {
			sourceDelivers("repository", GITHUB.repositoryEvent("archived", this.installation, this.sourceId));
			driftRead();
		}
		finally {
			GITHUB.unavailable.clear();
		}

		assertThat(this.drift.warnings(this.job)).as("nothing is known, so nothing is warned").isEmpty();
		assertThat(driftReads(this.job)).containsExactly("PENDING");
		assertThat(this.jdbc.sql("SELECT error_code FROM operations.external_operation WHERE type = :type")
			.param("type", SourceDrift.READ).query(String.class).single()).isEqualTo("GITHUB_HTTP_502");

		this.clock.advance(OperationPolicy.DEFAULT.delayAfter(1).plusSeconds(1));
		this.worker.runDue(Lane.GITHUB_SHORT);

		assertThat(this.drift.warnings(this.job)).extracting(SourceDrift.Warning::kind)
			.containsExactly(SourceDrift.Kind.ARCHIVED);
		assertThat(driftReads(this.job)).containsExactly("SUCCEEDED");
		assertThat(notices("repo.drift_detected")).isEqualTo(2);
		awaitListeners();
	}

	/**
	 * Story 24.6 (FR-64): a read GitHub never answers is tried under the default policy, then waits in the
	 * Administrators' failed queue, naming its Job; nothing is warned meanwhile.
	 */
	@Test
	void aReadThatExhaustsItsAttemptsLandsInTheFailedQueue() throws Exception {
		activeAfterJ7();
		GITHUB.archive(this.sourceId);
		GITHUB.unavailable.add("GET /repos/" + this.source);
		try {
			sourceDelivers("repository", GITHUB.repositoryEvent("archived", this.installation, this.sourceId));
			awaitListeners();
			for (int attempt = 1; attempt <= OperationPolicy.DEFAULT.maxAttempts(); attempt++) {
				this.worker.runDue(Lane.GITHUB_SHORT);
				this.clock.advance(OperationPolicy.DEFAULT.delayAfter(attempt).plusSeconds(1));
			}
		}
		finally {
			GITHUB.unavailable.clear();
		}

		assertThat(driftReads(this.job)).containsExactly("FAILED");
		assertThat(this.failedOperations.findAll()).filteredOn(failed -> failed.type().equals(SourceDrift.READ))
			.singleElement()
			.satisfies(failed -> {
				assertThat(failed.jobId()).isEqualTo(this.job);
				assertThat(failed.errorCode()).isEqualTo("GITHUB_HTTP_502");
				assertThat(failed.attempts()).isEqualTo(OperationPolicy.DEFAULT.maxAttempts());
			});
		assertThat(this.drift.warnings(this.job)).isEmpty();
		assertThat(notices("repo.drift_detected")).isZero();
		awaitListeners();
	}

	/**
	 * Story 24.6: nothing but a Code Job's own Source drift acts. A repository or an installation no Product is bound
	 * to, the Platform App's deliveries of the same events, and the Source App's other actions on the Job's own
	 * repository warn nothing and read nothing; so does a Job that ended. A read requested while the Job ran finds it
	 * Closed and is obsolete. No drift poll is left to read it later either.
	 */
	@Test
	void deliveriesThatAreNotACodeJobsDriftDoNothing() throws Exception {
		activeAfterJ7();
		long owner = githubUserId(this.customer);
		long other = REPOSITORY_IDS.incrementAndGet();
		GITHUB.publish("lan-dev", "khac-" + other, other, owner, false, false);
		String sha = GITHUB.push("lan-dev", this.sourceName, "main", "app.js", "console.log('Lovable')\n");
		byte[] ours = pushEvent(sha, List.of(sha));

		sourceDelivers("push", GITHUB.pushEvent(this.installation, other, "refs/heads/main", sha, List.of(sha)));
		sourceDelivers("repository", GITHUB.repositoryEvent("renamed", this.installation, other));
		sourceDelivers("installation", GITHUB.installationEvent("deleted", 424242, List.of(other)));
		sourceDelivers("installation_repositories", GITHUB.installationRepositoriesEvent("removed", 424242, List.of(other)));
		sourceDelivers("installation_target", GITHUB.installationTargetEvent("renamed", 424242, 77, "khac", "khac-cu"));
		platformDelivers("push", ours);
		platformDelivers("repository", GITHUB.repositoryEvent("archived", GitHubStub.INSTALLATION, this.sourceId));
		platformDelivers("installation", GITHUB.installationEvent("deleted", this.installation, List.of(this.sourceId)));
		platformDelivers("installation_repositories",
				GITHUB.installationRepositoriesEvent("removed", this.installation, List.of(this.sourceId)));
		sourceDelivers("repository", GITHUB.repositoryEvent("edited", this.installation, this.sourceId));
		sourceDelivers("repository", GITHUB.repositoryEvent("publicized", this.installation, this.sourceId));
		driftRead();

		assertThat(this.drift.warnings(this.job)).as("not this Job's drift").isEmpty();
		assertThat(driftReads(this.job)).as("and no read").isEmpty();

		this.jdbc.sql("UPDATE job.job SET state = 'CANCELLED' WHERE id = :id").param("id", this.job).update();
		sourceDelivers("push", ours);
		sourceDelivers("repository", GITHUB.repositoryEvent("archived", this.installation, this.sourceId));
		driftRead();
		assertThat(this.drift.warnings(this.job)).as("a Job that ended").isEmpty();
		assertThat(driftReads(this.job)).isEmpty();

		this.jdbc.sql("UPDATE job.job SET state = 'ACTIVE' WHERE id = :id").param("id", this.job).update();
		sourceDelivers("repository", GITHUB.repositoryEvent("archived", this.installation, this.sourceId));
		awaitListeners();
		this.jdbc.sql("UPDATE job.job SET state = 'CLOSED' WHERE id = :id").param("id", this.job).update();
		GITHUB.archive(this.sourceId);
		this.worker.runDue(Lane.GITHUB_SHORT);

		assertThat(driftReads(this.job)).as("requested while it ran, read once it ended").containsExactly("OBSOLETE");
		assertThat(this.drift.warnings(this.job)).isEmpty();
		assertThat(notices("repo.drift_detected")).isZero();
		assertThat(this.context.getEnvironment().getProperty("vibefix.repo.drift-cron")).as("no drift poll's schedule")
			.isNull();
		awaitListeners();
	}

	/**
	 * Story 24.6: a push or a rename before the Customer's confirmation warns nothing and reads nothing: the copy pins
	 * the commit the Customer confirms, so nothing before it is drift.
	 */
	@Test
	void aPushOrARenameBeforeTheConfirmationWarnsNothing() throws Exception {
		j6();
		String head = waitForTheCustomer();
		String pushed = GITHUB.push("lan-dev", this.sourceName, "main", "app.js", "console.log('Lovable')\n");

		sourceDelivers("push", pushEvent(pushed, List.of(pushed)));
		GITHUB.rename(this.sourceId, "lan-dev", this.sourceName + "-v2");
		sourceDelivers("repository", GITHUB.repositoryEvent("renamed", this.installation, this.sourceId));
		driftRead();

		Setup waiting = this.setups.latest(this.job).orElseThrow();
		assertThat(waiting.status()).as("still waiting for the Customer").isEqualTo("CONFIRMING");
		assertThat(waiting.approvedSha()).as("nothing confirmed yet").isNull();
		assertThat(head).isNotEqualTo(pushed);
		assertThat(this.drift.warnings(this.job)).isEmpty();
		assertThat(driftReads(this.job)).isEmpty();
		assertThat(notices("repo.drift_detected")).isZero();
		awaitListeners();
	}

	/**
	 * Story 23.11a, Story 24.6: an Assessment Job has a {@code DONE} Setup row too, but its one read-only copy is pinned
	 * to the confirmed commit and it has no Final Merge target, and {@code SourceDriftController} serves it neither its
	 * warnings nor a re-confirmation. So a push and a rename of its Source warn nothing, read nothing and open no to-do.
	 * The same Source, the same Setup and the same deliveries warn as soon as the Job is a Code Job, which is what makes
	 * the Job's kind the cause.
	 */
	@Test
	void anAssessmentJobsSourceWarnsNothingAndTheSameSourceWarnsOnceItIsACodeJob() throws Exception {
		assessmentJob();
		assessmentSetUpTheRepository();
		assertThat(this.busy.job(this.job).orElseThrow().state()).as("J7 applied, so the state guard passes")
			.isEqualTo("ACTIVE");
		String pushed = GITHUB.push("lan-dev", this.sourceName, "main", "app.js", "console.log('Lovable')\n");
		byte[] push = pushEvent(pushed, List.of(pushed));
		GITHUB.rename(this.sourceId, "lan-dev", this.sourceName + "-v2");
		byte[] renamed = GITHUB.repositoryEvent("renamed", this.installation, this.sourceId);

		sourceDelivers("push", push);
		sourceDelivers("repository", renamed);
		driftRead();

		assertThat(this.drift.warnings(this.job)).as("an Assessment Job's Source is not read").isEmpty();
		assertThat(driftReads(this.job)).isEmpty();
		assertThat(notices("repo.drift_detected")).isZero();
		assertThat(openReconfirmTodos()).as("a to-do nobody could ever close").isZero();
		// The Customer's own page still answers NOT_FOUND for an Assessment Job, as it did before.
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/drift").with(as(this.customer, Role.CUSTOMER)))
			.andExpect(status().isNotFound());

		this.jdbc.sql("UPDATE job.job SET job_types = ARRAY['BUG_FIX']::TEXT[] WHERE id = :id").param("id", this.job).update();
		this.clock.advance(Duration.ofMinutes(1));
		sourceDelivers("push", push);
		sourceDelivers("repository", renamed);
		driftRead();

		// The push's warning and the read's share a detected_at, so the kinds are what matters here, not their order.
		assertThat(this.drift.warnings(this.job)).as("the same Source warns once the Job is a Code Job")
			.extracting(SourceDrift.Warning::kind)
			.containsExactlyInAnyOrder(SourceDrift.Kind.COMMITS, SourceDrift.Kind.RENAMED);
		assertThat(openReconfirmTodos()).isOne();
		awaitListeners();
	}

	/**
	 * Story 24.6: each read is its own operation, so one that fails unexpectedly (here GitHub selects its Source
	 * Repository under a name with no owner) is tried again later, and the read another delivery requested for another
	 * Job still warns the same minute.
	 */
	@Test
	void oneJobsFailedReadDoesNotStopAnotherJobsRead() throws Exception {
		activeAfterJ7();
		long owner = githubUserId(this.customer);
		long repository = REPOSITORY_IDS.incrementAndGet();
		String name = "lan-dev/so-dat-ban-" + repository;
		GITHUB.publish("lan-dev", "so-dat-ban-" + repository, repository, owner, false, false);
		long installed = GITHUB.installSource("lan-dev", name.substring("lan-dev/".length()), repository, owner);
		GITHUB.mergeInstallations.get(installed).deselect(repository).repository(repository, "khong-co-chu", owner);
		UUID product = UUID.randomUUID();
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO product.product (id, customer_account_id, name, description, ai_tools, repository_github_id,
				    repository_full_name, repository_owner_type, repository_default_branch, public_notice_version,
				    copy_permission_version, detection_notice_version, acknowledged_at, created_at)
				VALUES (:id, :owner, 'Sổ khác', 'Đặt bàn.', ARRAY['LOVABLE'], :github, :name, 'User', 'main', '1.0', '1.0',
				    '1.0', :now, :now)
				""").param("id", product).param("owner", this.customer).param("github", repository).param("name", name)
			.param("now", now).update();
		this.jdbc.sql("""
				INSERT INTO product.source_app_binding (id, customer_account_id, repository_full_name, repository_github_id,
				    installation_id, account_github_id, account_login, account_type, product_id, status, created_at, bound_at)
				VALUES (:id, :owner, :name, :github, :installation, :account, 'lan-dev', 'User', :product, 'BOUND', :now, :now)
				""").param("id", UUID.randomUUID()).param("owner", this.customer).param("name", name).param("github", repository)
			.param("installation", installed).param("account", owner).param("product", product).param("now", now).update();
		UUID failing = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, budget, deadline, rules, selection_days, opened_at, lapses_at, version, created_at, updated_at,
				    mentor_account_id, selected_application_id, selected_at, agreement_version_no)
				VALUES (:id, :product, :owner, 'ACTIVE', 'Việc khác', 'Lỗi', 'Đúng', 'Khách', ARRAY['BUG_FIX'],
				    'UNDER_2M', DATE '2026-12-01', '{"REVISION_ROUNDS":2}'::jsonb, 14, :now, :now, 3, :now, :now,
				    :mentor, :application, :now, 1)
				""").param("id", failing).param("product", product).param("owner", this.customer).param("now", now)
			.param("mentor", this.mentor).param("application", UUID.randomUUID()).update();
		this.jdbc.sql("""
				INSERT INTO repo.job_setup (id, job_id, product_id, status, source_full_name, default_branch, head_sha,
				    head_read_at, approved_sha, confirmed_at, intermediary_github_id, intermediary_name, working_github_id,
				    working_name, checked_at, intermediary_at, working_at, copied_at, started_at, finished_at)
				VALUES (:id, :job, :product, 'DONE', :source, 'main', :sha, :now, :sha, :now, 1, 'vibefix-dev/khac-intermediary', 2,
				    'vibefix-dev/khac-working', :now, :now, :now, :now, :now, :now)
				""").param("id", UUID.randomUUID()).param("job", failing).param("product", product).param("source", name)
			.param("sha", "a".repeat(40)).param("now", now).update();

		sourceDelivers("installation_repositories",
				GITHUB.installationRepositoriesEvent("removed", installed, List.of(repository)));
		GITHUB.archive(this.sourceId);
		sourceDelivers("repository", GITHUB.repositoryEvent("archived", this.installation, this.sourceId));
		driftRead();

		assertThat(this.drift.warnings(failing)).isEmpty();
		assertThat(driftReads(failing)).as("tried again later").containsExactly("PENDING");
		assertThat(this.drift.warnings(this.job)).extracting(SourceDrift.Warning::kind)
			.containsExactly(SourceDrift.Kind.ARCHIVED);
		assertThat(driftReads(this.job)).containsExactly("SUCCEEDED");
		awaitListeners();
	}

	/** The Source App's {@code push} to the Source Repository's default branch, ending at {@code after} (Story 24.6). */
	private byte[] pushEvent(String after, List<String> commits) {
		return GITHUB.pushEvent(this.installation, this.sourceId, "refs/heads/main", after, commits);
	}

	/**
	 * {@code count} commit IDs, the {@code from}th first, as a push names them; nothing reads them but the drift's own
	 * list.
	 */
	private static List<String> shas(int from, int count) {
		List<String> shas = new ArrayList<>();
		for (int i = from; i < from + count; i++) {
			shas.add(String.format("%040x", i));
		}
		return shas;
	}

	/** {@code account}'s notifications of {@code type}. */
	private long notices(String type, UUID account) {
		return this.jdbc.sql("SELECT count(*) FROM notification.notification WHERE type = :type AND account_id = :account")
			.param("type", type)
			.param("account", account)
			.query(Long.class)
			.single();
	}

	/** At least {@code sessions} other sessions wait for a lock that {@code holder}'s transaction holds. */
	private void awaitBlockedBy(int holder, int sessions) {
		org.awaitility.Awaitility.await()
			.atMost(Duration.ofSeconds(15))
			.until(() -> this.jdbc.sql("SELECT count(*) FROM pg_locks WHERE NOT granted AND :holder = ANY(pg_blocking_pids(pid))")
				.param("holder", holder)
				.query(Long.class)
				.single() >= sessions);
	}

	private static void await(CountDownLatch latch) {
		try {
			assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

	/** The emails of {@code template} requested for {@code account}, sent or not. */
	private long emails(String template, UUID account) {
		return this.jdbc.sql("""
				SELECT count(*) FROM operations.external_operation
				WHERE type = 'notification.email' AND payload ->> 'template' = :template AND account_id = :account
				""").param("template", template).param("account", account).query(Long.class).single();
	}

	/** J6, the Customer's self-confirmation, the copy, and the Job Active after J7, as a test of drift needs it. */
	private void activeAfterJ7() throws Exception {
		setUpTheRepositories();
		this.jdbc.sql("UPDATE job.job SET state = 'ACTIVE' WHERE id = :id").param("id", this.job).update();
	}

	/**
	 * A push of {@code sha} to the Source Repository's default branch, delivered by the Source App a minute later; then
	 * the listeners and every drift read due.
	 */
	private void pushed(String sha) throws Exception {
		pushed(sha, List.of(sha));
	}

	/** The same, for a push ending at {@code after} that lists {@code commits}. */
	private void pushed(String after, List<String> commits) throws Exception {
		this.clock.advance(Duration.ofMinutes(1));
		sourceDelivers("push", pushEvent(after, commits));
		driftRead();
	}

	/** The Source App delivers {@code event}, signed with its secret, under a delivery ID of its own (Story 24.6). */
	private void sourceDelivers(String event, byte[] body) throws Exception {
		sourceDelivery(UUID.randomUUID().toString(), event, body).andExpect(status().isAccepted());
	}

	private ResultActions sourceDelivery(String deliveryId, String event, byte[] body) throws Exception {
		return this.mvc.perform(ActionsExchangeSupport.webhook(GitHubStub.SOURCE_APP, deliveryId, event, body, SOURCE_SECRET));
	}

	/** The Platform App delivers {@code event}, signed with its own secret. */
	private void platformDelivers(String event, byte[] body) throws Exception {
		this.mvc.perform(ActionsExchangeSupport.webhook(PLATFORM_APP, event, body, PLATFORM_SECRET))
			.andExpect(status().isAccepted());
	}

	/** The listeners finish, then every operation due in the GitHub-short lane runs, the drift reads among them. */
	private void driftRead() {
		awaitListeners();
		this.worker.runDue(Lane.GITHUB_SHORT);
	}

	/** The status of each {@value SourceDrift#READ} requested for {@code job}, oldest first. */
	private List<String> driftReads(UUID job) {
		return this.jdbc.sql("""
				SELECT status FROM operations.external_operation WHERE type = :type AND payload ->> 'jobId' = :job
				ORDER BY created_at, operation_key
				""").param("type", SourceDrift.READ).param("job", job.toString()).query(String.class).list();
	}

	/** The Product's recorded Source App and workflow status (Story 23.5c). */
	private String sourceStatus() {
		return this.jdbc.sql("SELECT source_status FROM product.product WHERE id = :id").param("id", this.product)
			.query(String.class).single();
	}

	/** The Product's open to-dos for its Customer, by key. */
	private List<String> productTodos() {
		return this.jdbc.sql("""
				SELECT todo_key FROM notification.todo
				WHERE subject_id = :product AND account_id = :customer AND closed_at IS NULL ORDER BY todo_key
				""").param("product", this.product).param("customer", this.customer).query(String.class).list();
	}

	/** Who got each {@code repo.workflow_check_failed} about the Product. */
	private List<UUID> workflowCheckFailures() {
		return this.jdbc.sql("""
				SELECT account_id FROM notification.notification WHERE type = 'repo.workflow_check_failed' AND link = :link
				""").param("link", "/san-pham/" + this.product).query(UUID.class).list();
	}

	private JobGrants.Grant crewGrant() {
		return this.grants.ofJob(this.job).stream().filter(grant -> grant.accountId().equals(this.crew)).findFirst()
			.orElseThrow();
	}

	/** The Mentor's and the Crew's grants: on the Working Repository, or on an Assessment Job's one copy (R6). */
	private List<JobGrants.Grant> teamGrants() {
		return this.grants.ofJob(this.job).stream().filter(JobGrants.Grant::team).toList();
	}

	/** The Customer's read grant on the Intermediary Repository (Story 23.7). */
	private JobGrants.Grant customerGrant() {
		return this.grants.ofJob(this.job).stream().filter(JobGrants.Grant::customer).findFirst().orElseThrow();
	}

	/** The Customer's open {@code repo.reconfirm_source} to-dos. */
	private long openReconfirmTodos() {
		return this.jdbc.sql("""
				SELECT count(*) FROM notification.todo WHERE todo_key = 'repo.reconfirm_source' AND closed_at IS NULL
				""").query(Long.class).single();
	}

	@Test
	void anOrganizationMemberDriftsAndHoldsVerifyUntilTheyLeaveTheOrganization() throws Exception {
		AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		String mentorLogin = TestPeople.login(this.jdbc, this.mentor);
		String crewLogin = TestPeople.login(this.jdbc, this.crew);
		GITHUB.members.add(crewLogin);
		setUpTheRepositories();
		this.worker.runDue(Lane.GITHUB_SHORT);

		assertThat(this.grants.ofJob(this.job)).extracting(JobGrants.Grant::status)
			.containsExactly("INVITED", "MEMBER_DRIFT", "INVITED");
		assertThat(notices("repo.member_drift")).isOne();
		GITHUB.accept(mentorLogin);
		this.checks.check(this.job);
		assertThat(this.busy.job(this.job).orElseThrow().busy()).as("a drifted grant holds Verify").isEmpty();

		GITHUB.members.remove(crewLogin);
		this.checks.check(this.job);
		this.worker.runDue(Lane.GITHUB_SHORT);

		assertThat(this.grants.ofJob(this.job)).extracting(JobGrants.Grant::status)
			.as("the Mentor, the Crew member, and the Customer last").containsExactly("ACCEPTED", "INVITED", "INVITED");
		assertThat(this.grants.ofJob(this.job).get(1).renewals()).isOne();
		awaitListeners();
	}

	@Test
	void anExpiredInvitationIsRenewedWithReadWhileTheJobIsStarting() throws Exception {
		setUpTheRepositories();
		this.worker.runDue(Lane.GITHUB_SHORT);
		this.clock.advance(Duration.ofDays(7).plusMinutes(1));

		this.deadlines.runDue();
		this.worker.runDue(Lane.GITHUB_SHORT);

		assertThat(this.grants.ofJob(this.job)).hasSize(3).allSatisfy(grant -> {
			assertThat(grant.status()).isEqualTo("INVITED");
			assertThat(grant.renewals()).isOne();
		});
		assertThat(notices("repo.github_invitation_sent")).as("each renewal is told").isEqualTo(4);
		assertThat(notices("repo.intermediary_invitation_sent")).as("the Customer hears of the first only").isOne();
		assertThat(customerTodoDue()).as("a renewal moves the Customer's to-do to its new expiry")
			.isCloseTo(this.clock.instant().plus(Duration.ofDays(7)),
					org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.SECONDS));
		awaitListeners();
	}

	/**
	 * Story 23.7 (AD-7, R5): the Customer's read invitation is renewed with read in any Job state, here after the Job
	 * ended, at most {@value JobGrants#CUSTOMER_RENEWALS} times, each moving the to-do's due date and telling nobody; then
	 * it stops quietly as {@code LAPSED}: its to-do closes, the event is audited, nobody is alerted, and it is settled.
	 */
	@Test
	void theCustomersReadInvitationIsRenewedInAnyStateAtMostTwelveTimesThenStopsQuietly() throws Exception {
		AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		String customerLogin = TestPeople.login(this.jdbc, this.customer);
		active();
		// J8, its revocation read back, then Closed: no deletion is scheduled within the renewals.
		this.jdbc.sql("UPDATE job.job SET state = 'CLOSING' WHERE id = :id").param("id", this.job).update();
		this.revocations.transitioned(new JobTransitioned(UUID.randomUUID(), UUID.randomUUID(), this.job, "J8", "USER",
				"ACTIVE", "CLOSING", 6, 1, Map.of()));
		this.worker.runDue(Lane.GITHUB_SHORT);
		this.jdbc.sql("UPDATE job.job SET state = 'CLOSED' WHERE id = :id").param("id", this.job).update();
		GITHUB.seen.clear();

		for (int renewal = 1; renewal <= JobGrants.CUSTOMER_RENEWALS; renewal++) {
			this.clock.advance(Duration.ofDays(7).plusMinutes(1));
			this.deadlines.runDue();
			this.worker.runDue(Lane.GITHUB_SHORT);
			assertThat(customerGrant().renewals()).isEqualTo(renewal);
			assertThat(customerGrant().status()).isEqualTo("INVITED");
			assertThat(customerTodoDue()).isEqualTo(customerGrant().expiresAt());
		}
		assertThat(notices("repo.intermediary_invitation_sent")).as("the first invitation only").isOne();
		assertThat(GITHUB.seen).as("each renewal is read only")
			.filteredOn(seen -> seen.method().equals("PUT") && seen.path().endsWith("/collaborators/" + customerLogin))
			.hasSize(JobGrants.CUSTOMER_RENEWALS)
			.allSatisfy(seen -> assertThat(seen.body().path("permission").asString("")).isEqualTo("pull"));

		this.clock.advance(Duration.ofDays(7).plusMinutes(1));
		this.deadlines.runDue();
		this.worker.runDue(Lane.GITHUB_SHORT);

		assertThat(customerGrant().status()).isEqualTo("LAPSED");
		assertThat(customerGrant().renewals()).as("no more renewals").isEqualTo(JobGrants.CUSTOMER_RENEWALS);
		assertThat(notices("repo.intermediary_invitation_sent")).isOne();
		assertThat(openCustomerTodos()).as("its to-do closes").isZero();
		assertThat(audits("repo.grant_renewals_exhausted")).isOne();
		assertThat(notices("repo.invitation_renewals_exhausted")).as("nobody is alerted").isZero();
		assertThat(sweep()).as("settled: the expired invitation GitHub still lists is no drift").isEmpty();
		assertThat(this.grants.unsettledJobs()).doesNotContain(this.job);
		this.clock.advance(Duration.ofDays(7).plusMinutes(1));
		this.deadlines.runDue();
		assertThat(customerGrant().renewals()).as("never renewed again").isEqualTo(JobGrants.CUSTOMER_RENEWALS);
		awaitListeners();
	}

	/**
	 * Story 23.7 (R5): a Customer who declines the read invitation on GitHub (no invitation left, no collaborator) lapses,
	 * quietly and settled; so does one who leaves the Intermediary after accepting. Neither is drift afterwards.
	 */
	@Test
	void aCustomerWhoDeclinesOrLeavesTheirReadLapsesQuietly() throws Exception {
		AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		String customerLogin = TestPeople.login(this.jdbc, this.customer);
		active();
		this.checks.check(this.job);
		assertThat(customerGrant().status()).as("a pending invitation is no decline").isEqualTo("INVITED");
		long told = this.jdbc.sql("SELECT count(*) FROM notification.notification").query(Long.class).single();

		GITHUB.decline(customerLogin);
		this.checks.check(this.job);

		assertThat(customerGrant().status()).isEqualTo("LAPSED");
		assertThat(audits("repo.grant_declined")).isOne();
		assertThat(openCustomerTodos()).isZero();
		assertThat(this.jdbc.sql("SELECT count(*) FROM notification.notification").query(Long.class).single())
			.as("nobody is told").isEqualTo(told);
		assertThat(sweep()).isEmpty();
		assertThat(this.grants.unsettledJobs()).doesNotContain(this.job);
		this.clock.advance(Duration.ofDays(7).plusMinutes(1));
		this.deadlines.runDue();
		this.worker.runDue(Lane.GITHUB_SHORT);
		assertThat(customerGrant().status()).as("never renewed").isEqualTo("LAPSED");
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/setup").with(as(this.customer, Role.CUSTOMER)))
			.andExpect(jsonPath("$.people[?(@.role == 'CUSTOMER')]").isEmpty());

		// Accepted, then left: the same, read on the member event GitHub sends.
		this.jdbc.sql("UPDATE repo.job_grant SET status = 'ACCEPTED', accepted_at = now() WHERE id = :id")
			.param("id", customerGrant().id()).update();
		GITHUB.addCollaborator(JobSetups.intermediaryName(this.job), customerLogin, "read");
		assertThat(sweep()).as("a read collaborator").isEmpty();
		GITHUB.leave(JobSetups.intermediaryName(this.job), customerLogin);
		this.checks.hinted(new vn.vibefix.github.GitHubWebhookReceived(UUID.randomUUID(), UUID.randomUUID().toString(),
				vn.vibefix.github.GitHubApps.App.PLATFORM, "member", "removed", 7L, List.of(customerGrant().repositoryId()), 1L,
				null));

		assertThat(customerGrant().status()).isEqualTo("LAPSED");
		assertThat(audits("repo.grant_left")).isOne();
		assertThat(sweep()).isEmpty();
		assertThat(this.grants.unsettledJobs()).doesNotContain(this.job);
		awaitListeners();
	}

	/** Story 23.7: the Customer's open read-invitation to-do, due at the invitation's expiry. */
	private Instant customerTodoDue() {
		return this.jdbc.sql("""
				SELECT deadline_at FROM notification.todo
				WHERE todo_key = 'repo.accept_intermediary_invitation' AND account_id = :customer AND closed_at IS NULL
				""").param("customer", this.customer).query(Timestamp.class).single().toInstant();
	}

	/** Story 23.7: the Customer's open read-invitation to-dos. */
	private long openCustomerTodos() {
		return this.jdbc.sql("""
				SELECT count(*) FROM notification.todo
				WHERE todo_key = 'repo.accept_intermediary_invitation' AND account_id = :customer AND closed_at IS NULL
				""").param("customer", this.customer).query(Long.class).single();
	}

	/** The Audit Log's entries of {@code action} on the Job. */
	private long audits(String action) {
		return this.jdbc.sql("SELECT count(*) FROM audit.audit_entry WHERE action = :action AND job_id = :job")
			.param("action", action).param("job", this.job).query(Long.class).single();
	}

	/**
	 * Story 23.6b (R4, AD-44, AD-46): the Source checked and its head read through the Source App, the Setup waits for
	 * the Customer with no marker, asked by a to-do due with the Starting stall and a notification naming the short SHA;
	 * their self-confirmation at that head is recorded and starts the copy under the marker; the two private repositories
	 * are created and read back before the copy is dispatched on the Customer's Actions through their Source App, with a
	 * grant of exactly those two; and only once both read back at the confirmed head is the Setup done.
	 */
	@Test
	void theConfirmedHeadIsCopiedOnTheCustomersActionsIntoTwoPrivateRepositoriesUnderTheBusyMarker() throws Exception {
		String head = GITHUB.head("lan-dev", this.sourceName, "main");
		Instant due = this.clock.instant().plus(Duration.ofDays(7));
		new org.springframework.transaction.support.TransactionTemplate(this.transactions)
			.executeWithoutResult(status -> this.deadlineSchedule.schedule(new DeadlineRequest("job.starting_deadline", this.job,
					this.job.toString(), this.job, due)));

		j6();
		assertThat(this.busy.job(this.job).orElseThrow().busy()).as("no marker while the Customer decides").isEmpty();
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/setup").with(as(this.customer, Role.CUSTOMER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CONFIRMING"))
			.andExpect(jsonPath("$.steps[0].key").value("CHECK"))
			.andExpect(jsonPath("$.steps[0].state").value("CURRENT"))
			.andExpect(jsonPath("$.head").doesNotExist());
		run();

		Setup waiting = this.setups.latest(this.job).orElseThrow();
		assertThat(waiting.status()).isEqualTo("CONFIRMING");
		assertThat(waiting.headSha()).as("the default branch's head").isEqualTo(head);
		assertThat(waiting.approvedSha()).as("nothing is approved before the Customer confirms").isNull();
		assertThat(this.busy.job(this.job).orElseThrow().busy()).as("still no marker").isEmpty();
		assertThat(GITHUB.organizationRepositoryId(JobSetups.intermediaryName(this.job))).as("nothing was created").isNull();
		assertThat(sourceRuns()).as("nothing was dispatched").isEmpty();
		assertThat(GITHUB.seen).as("the head is read through the Customer's Source App, with no scan")
			.filteredOn(request -> request.path().equals("/repos/" + this.source + "/git/ref/heads/main"))
			.isNotEmpty()
			.allSatisfy(request -> assertThat(GITHUB.tokensOf(this.installation))
				.contains(request.authorization().replaceFirst("^Bearer ", "")));
		assertThat(this.jdbc.sql("""
				SELECT deadline_at FROM notification.todo
				WHERE todo_key = 'repo.confirm_source_clean' AND account_id = :customer AND subject_id = :setup AND closed_at IS NULL
				""").param("customer", this.customer).param("setup", waiting.id()).query(Timestamp.class).single().toInstant())
			.as("due with the Starting stall (FR-40)")
			.isCloseTo(due, org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.SECONDS));
		assertThat(this.jdbc.sql("""
				SELECT account_id || ' ' || (params ->> 'sha') FROM notification.notification WHERE type = 'repo.self_confirmation_due'
				""").query(String.class).list()).containsExactly(this.customer + " " + head.substring(0, 7));
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/setup").with(as(this.customer, Role.CUSTOMER)))
			.andExpect(jsonPath("$.status").value("CONFIRMING"))
			.andExpect(jsonPath("$.steps[0].state").value("DONE"))
			.andExpect(jsonPath("$.steps[1].key").value("CONFIRM"))
			.andExpect(jsonPath("$.steps[1].state").value("CURRENT"))
			.andExpect(jsonPath("$.head.sha").value(head))
			.andExpect(jsonPath("$.confirmation").doesNotExist())
			.andExpect(jsonPath("$.run").doesNotExist());

		confirm(head).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("RUNNING"))
			.andExpect(jsonPath("$.confirmation.sha").value(head))
			.andExpect(jsonPath("$.steps[1].state").value("DONE"))
			.andExpect(jsonPath("$.steps[2].key").value("REPOSITORIES"))
			.andExpect(jsonPath("$.steps[2].state").value("CURRENT"));
		Setup confirmed = this.setups.latest(this.job).orElseThrow();
		assertThat(this.busy.job(this.job).orElseThrow().busy()).hasValueSatisfying(marker -> {
			assertThat(marker.purpose()).isEqualTo(JobBusy.Purpose.JOB_SETUP);
			assertThat(marker.operationKey()).isEqualTo(JobSetups.OPERATION + ":" + confirmed.confirmationId());
		});
		assertThat(this.jdbc.sql("""
				SELECT account_id || ' ' || head_sha || ' ' || notice_version FROM repo.source_confirmation WHERE setup_id = :setup
				""").param("setup", confirmed.id()).query(String.class).list()).containsExactly(this.customer + " " + head + " 1.1");
		assertThat(openConfirmTodos()).as("the to-do closes").isZero();

		run();
		Setup dispatched = this.setups.latest(this.job).orElseThrow();
		String intermediary = JobSetups.intermediaryName(this.job);
		String working = JobSetups.workingName(this.job);
		long intermediaryId = GITHUB.organizationRepositoryId(intermediary);
		long workingId = GITHUB.organizationRepositoryId(working);
		assertThat(dispatched.correlationId()).isNotNull();
		ActionsRuns.Correlation correlation = this.runs.correlation(dispatched.correlationId()).orElseThrow();
		assertThat(correlation.app()).isEqualTo(vn.vibefix.github.GitHubApps.App.SOURCE);
		assertThat(correlation.operation()).isEqualTo(ActionsRuns.Operation.COPY);
		assertThat(correlation.repositoryId()).isEqualTo(this.sourceId);
		assertThat(correlation.confirmedHeadSha()).isEqualTo(head);
		assertThat(correlation.grant()).as("the two new repositories, never the Source").containsExactlyInAnyOrder(intermediaryId,
				workingId);
		Map<String, Object> run = GITHUB.runs.get(correlation.runId());
		assertThat(run.get("repository_full_name")).as("on the Customer's own Actions").isEqualTo(this.source);
		assertThat(inputs(run)).containsEntry("operation", "copy")
			.containsEntry("sha", head)
			.containsEntry("branch", "main")
			.containsEntry("intermediary", GitHubStub.ORGANIZATION + "/" + intermediary)
			.containsEntry("working", GitHubStub.ORGANIZATION + "/" + working)
			.containsEntry("correlation", dispatched.correlationId().toString())
			.containsEntry("exchange_url", TUNNEL);
		assertThat(GITHUB.head(GitHubStub.ORGANIZATION, intermediary, "main")).as("vibefix pushes nothing itself").isNull();
		int dispatch = indexOf(request -> request.method().equals("POST")
				&& request.path().equals("/repos/" + this.source + "/actions/workflows/vibefix.yml/dispatches"));
		for (String name : List.of(intermediary, working)) {
			assertThat(GITHUB.isPrivate(name)).as(name + " is private").isTrue();
			for (String off : List.of("has_wiki", "has_issues", "has_projects", "has_discussions", "allow_forking")) {
				assertThat(GITHUB.setting(name, off)).as(name + " " + off).isEqualTo(false);
			}
			assertThat(GITHUB.seen).as("its Actions are never set, only read back")
				.noneMatch(request -> request.method().equals("PUT") && request.path().endsWith(name + "/actions/permissions"));
			int readBack = indexOf(request -> request.path().equals("/repos/" + GitHubStub.ORGANIZATION + "/" + name
					+ "/actions/permissions"));
			assertThat(readBack).as("read back before the copy is dispatched into " + name).isNotNegative().isLessThan(dispatch);
		}
		assertThat(GITHUB.seen.stream().filter(request -> request.method().equals("POST")
				&& request.path().equals("/orgs/" + GitHubStub.ORGANIZATION + "/repos")
				&& List.of(intermediary, working).contains(request.body().path("name").asString(""))))
			.as("both created private, with the features off; forking follows the organization's setting")
			.hasSize(2)
			.allSatisfy(request -> {
				assertThat(request.body().path("private").asBoolean()).isTrue();
				assertThat(request.body().has("allow_forking")).as("GitHub's create takes none").isFalse();
				assertThat(request.body().path("has_issues").asBoolean(true)).isFalse();
			});
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/setup").with(as(this.customer, Role.CUSTOMER)))
			.andExpect(jsonPath("$.steps[2].state").value("DONE"))
			.andExpect(jsonPath("$.steps[3].key").value("COPY"))
			.andExpect(jsonPath("$.steps[3].state").value("CURRENT"))
			.andExpect(jsonPath("$.run.state").value("DISPATCHED"))
			.andExpect(jsonPath("$.run.htmlUrl").value("https://github.com/" + this.source + "/actions/runs/" + correlation.runId()));
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/setup").with(as(this.mentor, Role.MENTOR)))
			.andExpect(jsonPath("$.run.state").value("DISPATCHED"))
			.andExpect(jsonPath("$.run.htmlUrl").doesNotExist());

		// The run trades its ID token for one token narrowed to the two repositories (AD-44).
		exchange(dispatched).andExpect(status().isOk());
		JsonNode grant = GITHUB.seen.stream()
			.filter(request -> request.method().equals("POST") && request.path().endsWith("/access_tokens"))
			.toList()
			.getLast()
			.body();
		assertThat(grant.path("repository_ids").toString()).isIn("[" + intermediaryId + "," + workingId + "]",
				"[" + workingId + "," + intermediaryId + "]");
		assertThat(grant.path("permissions").toString()).isIn("{\"contents\":\"write\",\"workflows\":\"write\"}",
				"{\"workflows\":\"write\",\"contents\":\"write\"}");

		// Story 24.5a: the copy waits for its run, its backstop the correlation's end of life, with one Deadline.
		assertThat(nextAttempt(dispatched)).as("no interval re-read: it waits for its backstop")
			.isEqualTo(dispatched.dispatchedAt().plus(ActionsRuns.CORRELATION_LIFETIME));
		assertThat(timeouts(dispatched)).as("one not pausable Deadline of the Job, at the run's own timeout")
			.containsExactly("PENDING " + this.job + " " + JobSetups.OPERATION + " false "
					+ Timestamp.from(dispatched.dispatchedAt().plus(ActionsRuns.RUN_TIMEOUT)).toInstant());
		assertThat(this.deadlineHandlers.stream().map(vn.vibefix.timers.DeadlineHandler::kind)
			.filter(kind -> kind.key().equals(ActionsRunWakes.KEY))).as("timers' registry lists it")
			.singleElement()
			.satisfies(kind -> {
				assertThat(kind.pausable()).isFalse();
				assertThat(kind.jobScoped()).isTrue();
			});
		GITHUB.startRun(correlation.runId());
		delivered(dispatched).andExpect(status().isAccepted());
		awaitListeners();
		assertThat(this.worker.runDue(Lane.GITHUB_LONG)).as("a run in progress wakes nothing").isZero();
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/setup").with(as(this.customer, Role.CUSTOMER)))
			.andExpect(jsonPath("$.status").value("RUNNING"))
			.andExpect(jsonPath("$.run.state").value("DISPATCHED"));

		copyRunSucceeds();

		Setup done = this.setups.latest(this.job).orElseThrow();
		assertThat(done.status()).isEqualTo("DONE");
		assertThat(done.approvedSha()).isEqualTo(head);
		assertThat(done.sourceFullName()).isEqualTo(this.source);
		assertThat(done.defaultBranch()).isEqualTo("main");
		assertThat(done.intermediaryName()).isEqualTo(GitHubStub.ORGANIZATION + "/" + intermediary);
		assertThat(GITHUB.head(GitHubStub.ORGANIZATION, intermediary, "main")).as("a copy with the commit IDs kept")
			.isEqualTo(head);
		assertThat(GITHUB.head(GitHubStub.ORGANIZATION, working, "main")).as("a second copy, not a fork").isEqualTo(head);
		for (long id : List.of(intermediaryId, workingId)) {
			String name = id == intermediaryId ? intermediary : working;
			int readBack = indexOf(request -> request.path().equals("/repos/" + GitHubStub.ORGANIZATION + "/" + name
					+ "/git/ref/heads/main"));
			assertThat(readBack).as(name + " is read back at the confirmed head").isNotNegative();
			assertThat(GITHUB.grantOf(List.copyOf(GITHUB.seen).get(readBack).authorization().replaceFirst("^Bearer ", ""))
				.path("repository_ids").toString()).as("with a token narrowed to it").isEqualTo("[" + id + "]");
		}
		assertThat(GITHUB.seen).as("no copy in vibefix's sandbox, so no push token for one repository")
			.noneMatch(request -> request.path().endsWith("/access_tokens")
					&& List.of("[" + intermediaryId + "]", "[" + workingId + "]").contains(request.body().path("repository_ids").toString())
					&& request.body().path("permissions").path("contents").asString("").equals("write"));
		assertThat(GITHUB.seen).as("no fork and no ruleset")
			.noneMatch(request -> request.path().endsWith("/forks") || request.path().contains("/rulesets"));
		assertThat(this.busy.job(this.job).orElseThrow().busy()).as("the resolving transaction cleared it").isEmpty();
		assertThat(this.busy.job(this.job).orElseThrow().state()).as("J7 waits for Verify").isEqualTo("STARTING");
		assertThat(this.grants.ofJob(this.job)).as("the Invitations start: the team, and the Customer's read").hasSize(3);
		assertThat(this.jdbc.sql("""
				SELECT action FROM audit.audit_entry WHERE job_id = :job AND (action LIKE 'repo.%' OR action LIKE 'job.busy_%')
				ORDER BY occurred_at, action
				""").param("job", this.job).query(String.class).list()).contains("repo.setup_started",
						"repo.setup_source_checked", "repo.source_confirmed", "repo.setup_repositories_ready",
						"repo.setup_copy_dispatched", "repo.setup_copied", "repo.setup_done", "job.busy_reserved",
						"job.busy_released");
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/setup").with(as(this.mentor, Role.MENTOR)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("DONE"))
			.andExpect(jsonPath("$.steps[3].state").value("DONE"))
			.andExpect(jsonPath("$.steps[4].key").value("INVITE"))
			.andExpect(jsonPath("$.run.state").value("COMPLETED"))
			.andExpect(jsonPath("$.run.conclusion").value("success"))
			.andExpect(jsonPath("$.canRetry").value(false));
		UUID stranger = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/setup").with(as(stranger, Role.CUSTOMER)))
			.andExpect(status().isNotFound());
		awaitListeners();

		// The copy no longer waits on that run, so its Deadline is obsolete when it comes.
		this.clock.advance(ActionsRuns.RUN_TIMEOUT);
		this.deadlines.runDue();
		assertThat(timeouts(dispatched)).singleElement().asString().startsWith("OBSOLETE ");
	}

	/**
	 * Story 23.6b: only the Job's Customer confirms, only while the Setup waits with its head read, only N-32's current
	 * version, and only at the head read again now: a head that changed after the page showed it answers {@code STALE}
	 * with the new head, which the Setup keeps. "Thử lại" meanwhile asks for the confirmation.
	 */
	@Test
	void onlyTheCustomerConfirmsAndOnlyTheCurrentHeadUnderTheCurrentNotice() throws Exception {
		j6();
		confirm("a".repeat(40)).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CONFLICT"))
			.andExpect(jsonPath("$.reason").value("SETUP_NOT_CONFIRMING"));
		String head = waitForTheCustomer();

		confirm(this.mentor, Role.MENTOR, head, "1.1").andExpect(status().isNotFound());
		confirm(this.crew, Role.STUDENT, head, "1.1").andExpect(status().isNotFound());
		confirm(this.customer, Role.CUSTOMER, head, "0.9").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION"))
			.andExpect(jsonPath("$.reason").value("NOTICE_REQUIRED"));
		this.mvc.perform(post("/api/v1/jobs/" + this.job + "/setup/retry").with(as(this.customer, Role.CUSTOMER)).with(csrf()))
			.andExpect(status().is(422))
			.andExpect(jsonPath("$.code").value("GUARD_FAILED"))
			.andExpect(jsonPath("$.reason").value("SELF_CONFIRMATION_MISSING"));

		String moved = GITHUB.push("lan-dev", this.sourceName, "main", "app.js", "console.log('Lovable')\n");
		confirm(head).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("STALE"))
			.andExpect(jsonPath("$.reason").doesNotExist())
			.andExpect(jsonPath("$.head").value(moved));
		Setup waiting = this.setups.latest(this.job).orElseThrow();
		assertThat(waiting.status()).isEqualTo("CONFIRMING");
		assertThat(waiting.headSha()).as("the head read now").isEqualTo(moved);
		assertThat(this.busy.job(this.job).orElseThrow().busy()).isEmpty();
		assertThat(this.jdbc.sql("SELECT count(*) FROM repo.source_confirmation WHERE job_id = :job").param("job", this.job)
			.query(Long.class).single()).as("nothing is recorded").isZero();
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/setup").with(as(this.customer, Role.CUSTOMER)))
			.andExpect(jsonPath("$.head.sha").value(moved));

		confirm(moved).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RUNNING"));
		confirm(moved).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("SETUP_NOT_CONFIRMING"));
		assertThat(this.setups.latest(this.job).orElseThrow().approvedSha()).isEqualTo(moved);
		assertThat(this.jdbc.sql("""
				SELECT actor_type || ' ' || actor_account_id FROM audit.audit_entry WHERE action = 'repo.source_confirmed' AND job_id = :job
				""").param("job", this.job).query(String.class).list()).as("the Customer's own act, audited")
			.containsExactly("USER " + this.customer);
		awaitListeners();
	}

	/**
	 * Story 23.6b (R4): the head moved after the confirmation, so the exchange refuses the copy {@code sha}; the Setup
	 * waits for the Customer again with the new head, its marker released and its to-do and notification sent again,
	 * and the earlier confirmation stays recorded. The Customer confirms the new head, and that one is copied.
	 */
	@Test
	void aHeadThatMovedAfterTheConfirmationIsRefusedShaAndTheCustomerConfirmsTheNewOne() throws Exception {
		String head = confirmTheHead();
		Setup first = this.setups.latest(this.job).orElseThrow();
		run();
		Setup dispatched = this.setups.latest(this.job).orElseThrow();
		// The head moves between the dispatch's own head read and the run's start, so the run starts at the new head.
		String moved = GITHUB.push("lan-dev", this.sourceName, "main", "app.js", "console.log('Lovable')\n");
		GITHUB.runs.get(runId(dispatched)).put("head_sha", moved);

		// Story 24.5a: the refusal itself wakes the copy, seconds later, with no delivery.
		this.clock.advance(Duration.ofSeconds(5));
		exchange(dispatched).andExpect(status().isForbidden()).andExpect(jsonPath("$.reason").value("SHA"));
		awaitListeners();
		run();

		Setup again = this.setups.latest(this.job).orElseThrow();
		assertThat(again.id()).as("the same attempt").isEqualTo(first.id());
		assertThat(again.status()).isEqualTo("CONFIRMING");
		assertThat(again.exchangeReason()).isEqualTo("sha");
		assertThat(again.headSha()).isEqualTo(moved);
		assertThat(again.approvedSha()).isNull();
		assertThat(again.correlationId()).isNull();
		assertThat(this.busy.job(this.job).orElseThrow().busy()).as("no marker while the Customer decides again").isEmpty();
		assertThat(this.setups.confirmations(first.id())).extracting(JobSetups.Confirmation::headSha)
			.as("the earlier confirmation stays").containsExactly(head);
		assertThat(openConfirmTodos()).as("the to-do is open again").isOne();
		assertThat(this.jdbc.sql("""
				SELECT params ->> 'sha' FROM notification.notification WHERE type = 'repo.self_confirmation_due' ORDER BY created_at, id
				""").query(String.class).list()).as("told again, for the new head")
			.containsExactlyInAnyOrder(head.substring(0, 7), moved.substring(0, 7));
		assertThat(this.jdbc.sql("SELECT status || ':' || error_code FROM operations.external_operation WHERE operation_key = :key")
			.param("key", JobSetups.OPERATION + ":" + first.confirmationId()).query(String.class).single())
			.isEqualTo("OBSOLETE:HEAD_MOVED");
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/setup").with(as(this.customer, Role.CUSTOMER)))
			.andExpect(jsonPath("$.status").value("CONFIRMING"))
			.andExpect(jsonPath("$.exchangeReason").value("sha"))
			.andExpect(jsonPath("$.head.sha").value(moved))
			.andExpect(jsonPath("$.confirmation").doesNotExist())
			.andExpect(jsonPath("$.steps[1].state").value("CURRENT"));

		confirm(moved).andExpect(status().isOk()).andExpect(jsonPath("$.exchangeReason").doesNotExist());
		run();
		assertThat(this.setups.latest(this.job).orElseThrow().correlationId()).as("a new correlation")
			.isNotEqualTo(dispatched.correlationId());
		copyRunSucceeds();

		Setup done = this.setups.latest(this.job).orElseThrow();
		assertThat(done.status()).isEqualTo("DONE");
		assertThat(done.approvedSha()).isEqualTo(moved);
		assertThat(GITHUB.head(GitHubStub.ORGANIZATION, JobSetups.workingName(this.job), "main")).isEqualTo(moved);
		assertThat(this.setups.confirmations(first.id())).extracting(JobSetups.Confirmation::headSha).containsExactly(head, moved);
		awaitListeners();
	}

	/**
	 * Story 23.6b: any other refusal of the copy's exchange blocks the start {@code EXCHANGE_REFUSED} with the reason
	 * stored: a workflow file that changed after the dispatch ({@code blob}), which the Customer fixes, or a run someone
	 * else started ({@code run}), which Administrators hear of (Story 23.4). "Thử lại" starts from the waiting stage.
	 */
	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.ValueSource(strings = { "blob", "run" })
	void anyOtherExchangeRefusalBlocksTheStartWithItsReason(String reason) throws Exception {
		confirmTheHead();
		run();
		Setup dispatched = this.setups.latest(this.job).orElseThrow();
		String published = GitHubStub.published(GitHubStub.CUSTOMER_WORKFLOW);
		if (reason.equals("blob")) {
			GITHUB.sourceFiles.put(this.source + ":.github/workflows/vibefix.yml", published + "# changed\n");
		}
		else {
			GITHUB.runs.get(runId(dispatched)).put("triggering_actor", Map.of("login", "mallory", "type", "User"));
		}

		exchange(dispatched).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.reason").value(reason.toUpperCase(java.util.Locale.ROOT)));
		GITHUB.completeRun(runId(dispatched), "failure");
		// The refused correlation's completed run names no correlation; the refusal already woke the copy.
		delivered(dispatched).andExpect(status().isAccepted());
		awaitListeners();
		run();

		Setup blocked = this.setups.latest(this.job).orElseThrow();
		assertThat(blocked.status()).isEqualTo("BLOCKED");
		assertThat(blocked.block()).isEqualTo(JobSetups.Block.EXCHANGE_REFUSED);
		assertThat(blocked.exchangeReason()).isEqualTo(reason);
		assertThat(this.busy.job(this.job).orElseThrow().busy()).as("the block releases the marker").isEmpty();
		assertThat(this.jdbc.sql("SELECT status || ':' || error_code FROM operations.external_operation WHERE operation_key = :key")
			.param("key", JobSetups.OPERATION + ":" + blocked.confirmationId()).query(String.class).single())
			.isEqualTo("ABORTED:EXCHANGE_REFUSED");
		String text = reason.equals("blob") ? JobSetups.EXCHANGE_REASONS.get("blob")
				: JobSetups.REASONS.get(JobSetups.Block.EXCHANGE_REFUSED);
		assertThat(this.jdbc.sql("SELECT params ->> 'reason' FROM notification.notification WHERE type = 'repo.job_start_blocked'")
			.query(String.class).list()).as("the Customer and the Mentor, each with the reason").hasSize(2).containsOnly(text);
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/setup").with(as(this.customer, Role.CUSTOMER)))
			.andExpect(jsonPath("$.status").value("BLOCKED"))
			.andExpect(jsonPath("$.blockedReason").value("EXCHANGE_REFUSED"))
			.andExpect(jsonPath("$.exchangeReason").value(reason))
			.andExpect(jsonPath("$.steps[3].state").value("BLOCKED"))
			.andExpect(jsonPath("$.canRetry").value(true));

		GITHUB.sourceFiles.put(this.source + ":.github/workflows/vibefix.yml", published);
		this.clock.advance(Duration.ofMinutes(1));
		this.mvc.perform(post("/api/v1/jobs/" + this.job + "/setup/retry").with(as(this.customer, Role.CUSTOMER)).with(csrf()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CONFIRMING"));
		Setup retried = this.setups.latest(this.job).orElseThrow();
		assertThat(retried.id()).as("a new attempt").isNotEqualTo(blocked.id());
		run();
		assertThat(this.setups.latest(this.job).orElseThrow().headSha()).as("it asks the Customer again")
			.isEqualTo(GITHUB.head("lan-dev", this.sourceName, "main"));
		awaitListeners();
	}

	/** Story 23.6b (R20): a run the Customer's Actions minutes stopped blocks the start at once, with no retry. */
	@Test
	void aRunTheCustomersActionsQuotaStoppedBlocksAtOnceWithNoRetry() throws Exception {
		confirmTheHead();
		run();
		Setup dispatched = this.setups.latest(this.job).orElseThrow();
		GITHUB.failBeforeAnyJob(runId(dispatched));
		runCompleted(dispatched);

		Setup blocked = this.setups.latest(this.job).orElseThrow();
		assertThat(blocked.status()).isEqualTo("BLOCKED");
		assertThat(blocked.block()).isEqualTo(JobSetups.Block.ACTIONS_QUOTA);
		assertThat(this.jdbc.sql("SELECT status || ':' || error_code FROM operations.external_operation WHERE operation_key = :key")
			.param("key", JobSetups.OPERATION + ":" + blocked.confirmationId()).query(String.class).single())
			.isEqualTo("ABORTED:ACTIONS_QUOTA");
		assertThat(this.busy.job(this.job).orElseThrow().busy()).isEmpty();
		assertThat(this.jdbc.sql("SELECT params ->> 'reason' FROM notification.notification WHERE type = 'repo.job_start_blocked'")
			.query(String.class).list()).hasSize(2).containsOnly(JobSetups.REASONS.get(JobSetups.Block.ACTIONS_QUOTA));
		this.clock.advance(Duration.ofHours(1));
		run();
		assertThat(sourceRuns()).as("dispatched once").hasSize(1);
		awaitListeners();
	}

	/**
	 * Story 24.5a: a run Actions' quota stops ends within seconds, so its completion can be delivered while the dispatch
	 * still looks for the run, before the Setup's row names the correlation: that hint woke nobody. The copy finds it
	 * recorded on the correlation as soon as it records the dispatch, and reads the run at once, not at its Deadline.
	 */
	@Test
	void aCompletionDeliveredWhileTheDispatchStillLooksForItsRunWakesTheCopyAtOnce() throws Exception {
		confirmTheHead();
		awaitListeners();
		AtomicBoolean delivered = new AtomicBoolean();
		// GitHub answers the dispatch 204, as it once did, so the dispatch looks for its run by title.
		GITHUB.runDetails = false;
		GITHUB.beforeAnswer = request -> {
			if (request.method().equals("GET")
					&& request.path().equals("/repos/" + this.source + "/actions/workflows/vibefix.yml/runs")
					&& delivered.compareAndSet(false, true)) {
				try {
					long runId = ((Number) sourceRuns().getLast().get("id")).longValue();
					GITHUB.failBeforeAnyJob(runId);
					this.mvc.perform(ActionsExchangeSupport.workflowRun(GITHUB, runId, GitHubStub.SOURCE_APP, this.installation,
							SOURCE_SECRET)).andExpect(status().isAccepted());
					awaitListeners();
				}
				catch (Exception failed) {
					throw new IllegalStateException(failed);
				}
			}
		};
		try {
			run();
		}
		finally {
			GITHUB.runDetails = true;
			GITHUB.beforeAnswer = request -> {
			};
		}

		assertThat(delivered).as("delivered before the Setup named the correlation").isTrue();
		Setup blocked = this.setups.latest(this.job).orElseThrow();
		assertThat(blocked.status()).as("read at once, with no time passing").isEqualTo("BLOCKED");
		assertThat(blocked.block()).isEqualTo(JobSetups.Block.ACTIONS_QUOTA);
		assertThat(timeouts(blocked)).as("its Deadline was not needed").singleElement().asString().startsWith("PENDING ");
		awaitListeners();
	}

	/**
	 * Story 23.6b (AD-46, FR-64): a run that concludes anything but {@code success} is dispatched again under a new
	 * correlation by the lane policy; once the attempts run out, the start is blocked {@code RUN_FAILED} and the
	 * operation waits in ADM-7's failed list, its Administrators told.
	 */
	@Test
	void aFailingRunIsDispatchedAgainUnderANewCorrelationUntilItBlocksRunFailedAndWaitsInFr64() throws Exception {
		AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		confirmTheHead();
		Set<UUID> correlations = runsKeepFailing();

		assertThat(correlations).as("each attempt under a new correlation").hasSize(JobSetupOperation.POLICY.maxAttempts());
		Setup blocked = this.setups.latest(this.job).orElseThrow();
		assertThat(blocked.status()).isEqualTo("BLOCKED");
		assertThat(blocked.block()).isEqualTo(JobSetups.Block.RUN_FAILED);
		String key = JobSetups.OPERATION + ":" + blocked.confirmationId();
		assertThat(this.jdbc.sql("SELECT status || ':' || error_code FROM operations.external_operation WHERE operation_key = :key")
			.param("key", key).query(String.class).single()).isEqualTo("FAILED:RUN_FAILED");
		assertThat(this.failedOperations.findAll()).as("in FR-64's failed list").anySatisfy(failed -> {
			assertThat(failed.key()).isEqualTo(key);
			assertThat(failed.jobId()).isNull();
		});
		assertThat(notices("operations.operation_failed")).as("the Administrator is told").isOne();
		assertThat(this.busy.job(this.job).orElseThrow().busy()).isEmpty();
		assertThat(this.jdbc.sql("SELECT params ->> 'reason' FROM notification.notification WHERE type = 'repo.job_start_blocked'")
			.query(String.class).list()).hasSize(2).containsOnly(JobSetups.REASONS.get(JobSetups.Block.RUN_FAILED));
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/setup").with(as(this.customer, Role.CUSTOMER)))
			.andExpect(jsonPath("$.blockedReason").value("RUN_FAILED"))
			.andExpect(jsonPath("$.run.conclusion").value("failure"))
			.andExpect(jsonPath("$.canRetry").value(true));
	}

	/**
	 * Story 23.6b (FR-64): an Administrator's retry of a copy blocked {@code RUN_FAILED} copies again for the same
	 * confirmation, under its marker reserved again, with a new run.
	 */
	@Test
	void aCopyBlockedRunFailedRetriedFromAdm7CopiesAgainUnderItsMarker() throws Exception {
		confirmTheHead();
		Set<UUID> correlations = runsKeepFailing();
		Setup blocked = this.setups.latest(this.job).orElseThrow();
		assertThat(blocked.block()).isEqualTo(JobSetups.Block.RUN_FAILED);
		String key = JobSetups.OPERATION + ":" + blocked.confirmationId();
		UUID operation = this.jdbc.sql("SELECT id FROM operations.external_operation WHERE operation_key = :key")
			.param("key", key).query(UUID.class).single();

		assertThat(this.failedOperations.retry(operation)).isTrue();
		run();

		Setup resumed = this.setups.latest(this.job).orElseThrow();
		assertThat(resumed.id()).as("the same attempt").isEqualTo(blocked.id());
		assertThat(resumed.status()).isEqualTo("RUNNING");
		assertThat(resumed.block()).isNull();
		assertThat(resumed.confirmationId()).as("for the same confirmation").isEqualTo(blocked.confirmationId());
		assertThat(resumed.correlationId()).as("a new run").isNotNull().isNotIn(correlations);
		assertThat(this.busy.job(this.job).orElseThrow().busy()).hasValueSatisfying(
				marker -> assertThat(marker.operationKey()).isEqualTo(key));
		copyRunSucceeds();

		assertThat(this.setups.latest(this.job).orElseThrow().status()).isEqualTo("DONE");
		awaitListeners();
	}

	/**
	 * Story 23.6b (AD-46): a run still not complete after its timeout is given up: its correlation is closed, so a late
	 * exchange is refused, the run is cancelled, and another is dispatched. Since Story 24.5a nothing reads it before:
	 * its {@code repo.actions_run_timeout} Deadline wakes the copy, which reads the run once and takes the timeout path,
	 * and the next dispatch gets a Deadline of its own.
	 */
	@Test
	void aRunThatDoesNotCompleteWithinItsTimeoutIsClosedCancelledAndDispatchedAgain() throws Exception {
		confirmTheHead();
		run();
		Setup first = this.setups.latest(this.job).orElseThrow();
		long firstRun = runId(first);
		GITHUB.startRun(firstRun);
		this.clock.advance(ActionsRuns.RUN_TIMEOUT.minusSeconds(1));
		assertThat(this.deadlines.runDue()).as("not yet").isZero();
		assertThat(this.worker.runDue(Lane.GITHUB_LONG)).isZero();

		this.clock.advance(Duration.ofSeconds(1));
		assertThat(this.deadlines.runDue()).isOne();
		assertThat(timeouts(first)).singleElement().asString().startsWith("FIRED ");
		run();

		Setup late = this.setups.latest(this.job).orElseThrow();
		assertThat(late.runStatus()).as("read once, still in progress").isEqualTo("IN_PROGRESS");
		assertThat(late.runConclusion()).isEqualTo(JobSetupOperation.TIMED_OUT);
		assertThat(this.jdbc.sql("SELECT status || ':' || error_code FROM operations.external_operation WHERE operation_key = :key")
			.param("key", JobSetups.OPERATION + ":" + late.confirmationId()).query(String.class).single())
			.isEqualTo("PENDING:RUN_FAILED");
		assertThat(this.runs.correlation(first.correlationId()).orElseThrow().status()).as("closed")
			.isEqualTo(ActionsRuns.Status.EXPIRED);
		assertThat(GITHUB.seen).as("and its run cancelled").anyMatch(request -> request.method().equals("POST")
				&& request.path().equals("/repos/" + this.source + "/actions/runs/" + firstRun + "/cancel"));
		assertThat(GITHUB.runs.get(firstRun).get("conclusion")).isEqualTo("cancelled");
		exchange(first).andExpect(status().isForbidden()).andExpect(jsonPath("$.reason").value("CORRELATION"));

		this.clock.advance(JobSetupOperation.POLICY.delayAfter(1).plusSeconds(1));
		run();
		Setup second = this.setups.latest(this.job).orElseThrow();
		assertThat(second.correlationId()).isNotEqualTo(first.correlationId());
		assertThat(timeouts(second)).as("the second run's own Deadline").singleElement().asString().startsWith("PENDING ");
		copyRunSucceeds();

		assertThat(this.setups.latest(this.job).orElseThrow().status()).isEqualTo("DONE");
		awaitListeners();
	}

	/**
	 * Story 24.5a: a run whose completion was never delivered (the webhooks not set up, or the delivery lost) is read once
	 * when its Deadline comes, and a completed one resolves as if its event had come.
	 */
	@Test
	void aCompletedRunWhoseEventNeverCameResolvesAtItsDeadline() throws Exception {
		confirmTheHead();
		run();
		Setup copying = this.setups.latest(this.job).orElseThrow();
		for (String name : List.of(JobSetups.intermediaryName(this.job), JobSetups.workingName(this.job))) {
			GITHUB.copyCommit("lan-dev", this.sourceName, copying.approvedSha(), name, copying.defaultBranch());
		}
		GITHUB.completeRun(runId(copying), "success");
		this.clock.advance(Duration.ofMinutes(5));
		assertThat(this.worker.runDue(Lane.GITHUB_LONG)).as("nothing told the copy").isZero();

		// The Deadline comes before the backstop, at the run's own timeout.
		this.clock.advance(ActionsRuns.RUN_TIMEOUT.minusMinutes(5));
		assertThat(this.deadlines.runDue()).isOne();
		run();

		Setup done = this.setups.latest(this.job).orElseThrow();
		assertThat(done.status()).isEqualTo("DONE");
		assertThat(done.runConclusion()).isEqualTo("success");
		assertThat(timeouts(copying)).singleElement().asString().startsWith("FIRED ");
		assertThat(sourceRuns()).as("dispatched once").hasSize(1);
		awaitListeners();
	}

	/**
	 * Story 24.5a: a run that is still out when its backstop comes, because its Deadline's handler never woke the copy,
	 * takes the timeout path all the same.
	 */
	@Test
	void aRunStillOutAtItsBackstopTakesTheTimeoutPath() throws Exception {
		confirmTheHead();
		run();
		Setup first = this.setups.latest(this.job).orElseThrow();
		GITHUB.startRun(runId(first));

		this.clock.advance(ActionsRuns.CORRELATION_LIFETIME);
		run();

		Setup late = this.setups.latest(this.job).orElseThrow();
		assertThat(late.runConclusion()).isEqualTo(JobSetupOperation.TIMED_OUT);
		assertThat(this.runs.correlation(first.correlationId()).orElseThrow().status()).isEqualTo(ActionsRuns.Status.EXPIRED);
		awaitListeners();
	}

	/**
	 * Story 23.6b: a run that succeeded but whose copy does not read back at the confirmed head is spent: another is
	 * dispatched, and that one is read back.
	 */
	@Test
	void aSuccessfulRunWhoseCopyDoesNotReadBackAtTheConfirmedHeadIsDispatchedAgain() throws Exception {
		String earlier = GITHUB.head("lan-dev", this.sourceName, "main");
		String head = GITHUB.push("lan-dev", this.sourceName, "main", "app.js", "console.log('Lovable')\n");
		assertThat(confirmTheHead()).isEqualTo(head);
		run();
		Setup dispatched = this.setups.latest(this.job).orElseThrow();
		GITHUB.copyCommit("lan-dev", this.sourceName, head, JobSetups.intermediaryName(this.job), "main");
		GITHUB.copyCommit("lan-dev", this.sourceName, earlier, JobSetups.workingName(this.job), "main");
		GITHUB.completeRun(runId(dispatched), "success");
		runCompleted(dispatched);

		Setup spent = this.setups.latest(this.job).orElseThrow();
		assertThat(spent.status()).isEqualTo("RUNNING");
		assertThat(spent.runConclusion()).isEqualTo(JobSetupOperation.NOT_READ_BACK);
		assertThat(this.jdbc.sql("SELECT status || ':' || error_code FROM operations.external_operation WHERE operation_key = :key")
			.param("key", JobSetups.OPERATION + ":" + dispatched.confirmationId()).query(String.class).single())
			.isEqualTo("PENDING:RUN_FAILED");

		this.clock.advance(JobSetupOperation.POLICY.delayAfter(1).plusSeconds(1));
		run();
		assertThat(this.setups.latest(this.job).orElseThrow().correlationId()).as("dispatched again")
			.isNotEqualTo(dispatched.correlationId());
		copyRunSucceeds();

		assertThat(this.setups.latest(this.job).orElseThrow().status()).isEqualTo("DONE");
		assertThat(sourceRuns()).hasSize(2);
		awaitListeners();
	}

	/**
	 * Story 23.6b: the head read just before the dispatch moved since the confirmation, so nothing is dispatched and the
	 * Customer is asked to confirm the new head.
	 */
	@Test
	void aHeadThatMovedBeforeTheDispatchIsNeverCopiedAndTheCustomerIsAskedAgain() throws Exception {
		String head = confirmTheHead();
		String moved = GITHUB.push("lan-dev", this.sourceName, "main", "app.js", "console.log('Lovable')\n");
		run();

		Setup again = this.setups.latest(this.job).orElseThrow();
		assertThat(again.status()).isEqualTo("CONFIRMING");
		assertThat(again.exchangeReason()).isEqualTo("sha");
		assertThat(again.headSha()).isEqualTo(moved);
		assertThat(sourceRuns()).as("nothing was dispatched").isEmpty();
		assertThat(this.busy.job(this.job).orElseThrow().busy()).isEmpty();
		assertThat(this.setups.confirmations(again.id())).extracting(JobSetups.Confirmation::headSha).containsExactly(head);
		assertThat(openConfirmTodos()).isOne();
		awaitListeners();
	}

	/**
	 * Story 23.6b: a Job that leaves Starting (here a cancellation alone after the Starting stall, J10) while its Setup
	 * waits for the Customer ends the wait, which is no failure, and its to-do; the tracker shows nothing done past it.
	 */
	@Test
	void aJobThatLeavesStartingWhileTheSetupWaitsEndsTheWaitAndItsToDo() throws Exception {
		j6();
		String head = waitForTheCustomer();
		Setup waiting = this.setups.latest(this.job).orElseThrow();
		assertThat(openConfirmTodos()).isOne();
		this.jdbc.sql("UPDATE job.job SET stall_deadline_passed_at = :now WHERE id = :id")
			.param("now", Timestamp.from(this.clock.instant())).param("id", this.job).update();

		this.mvc.perform(post("/api/v1/boards/" + this.job + "/cancellation/alone").with(as(this.customer, Role.CUSTOMER))
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"reason\":\"Chờ quá lâu\",\"version\":3}"))
			.andExpect(status().is2xxSuccessful());
		awaitListeners();

		assertThat(this.busy.job(this.job).orElseThrow().state()).isEqualTo("CANCELLED");
		assertThat(this.setups.find(waiting.id()).orElseThrow().status()).isEqualTo("ENDED");
		assertThat(openConfirmTodos()).isZero();
		assertThat(this.jdbc.sql("SELECT action FROM audit.audit_entry WHERE job_id = :job AND action LIKE 'repo.setup_%'")
			.param("job", this.job).query(String.class).list()).contains("repo.setup_ended").doesNotContain("repo.setup_failed");
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/setup").with(as(this.customer, Role.CUSTOMER)))
			.andExpect(jsonPath("$.status").value("ENDED"))
			.andExpect(jsonPath("$.steps[0].state").value("DONE"))
			.andExpect(jsonPath("$.steps[*].state").value(org.hamcrest.Matchers.contains("DONE", "PENDING", "PENDING",
					"PENDING", "PENDING", "PENDING")))
			.andExpect(jsonPath("$.canRetry").value(false));
		confirm(head).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("SETUP_NOT_CONFIRMING"));
	}

	// ------------------------------------------------- Story 23.11a: an Assessment Job's read-only copy (R6)

	/**
	 * Story 23.11a (R6, FR-18, AD-44): an Assessment Job runs the same Setup a Code Job does, and it makes exactly one
	 * private repository. J6 starts the attempt as {@code CONFIRMING}, the Customer self-confirms the head, the
	 * Intermediary Repository alone is created and read back -- {@code working_*} stay null -- the copy is dispatched
	 * with an empty {@code working} and a grant of that one repository, and J7 applies the moment it reads back, with
	 * no Verify. Story 23.11b (matrix row 1): the same resolving transaction grants the Mentor, the active Crew and the
	 * Customer read on that one repository, each row read-only and {@code INVITING} with its invitation requested, and
	 * J7 waits for none of them. The tracker serves five steps, and the Audit Log records the same chain a Code Job's
	 * does.
	 */
	@Test
	void anAssessmentJobCopiesIntoOneReadOnlyRepositoryAndReachesJ7WithItsInvitationsStillOut() throws Exception {
		assessmentJob();

		j6();
		Setup waiting = this.setups.latest(this.job).orElseThrow();
		assertThat(waiting.assessment()).as("the row says one repository").isTrue();
		assertThat(waiting.status()).isEqualTo("CONFIRMING");
		assertThat(this.busy.job(this.job).orElseThrow().state()).as("J7 waits for the copy").isEqualTo("STARTING");
		String head = waitForTheCustomer();
		assertThat(openConfirmTodos()).as("the Customer is asked to confirm, as for a Code Job").isOne();
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/setup").with(as(this.customer, Role.CUSTOMER)))
			.andExpect(jsonPath("$.assessment").value(true))
			.andExpect(jsonPath("$.steps[*].key").value(org.hamcrest.Matchers.contains("CHECK", "CONFIRM", "REPOSITORIES",
					"COPY", "INVITE")));

		confirm(head).andExpect(status().isOk());
		run();

		Setup copying = this.setups.latest(this.job).orElseThrow();
		assertThat(copying.intermediaryId()).isNotNull();
		assertThat(copying.intermediaryAt()).as("the one repository was read back").isNotNull();
		assertThat(copying.workingId()).as("no Working Repository, in any column").isNull();
		assertThat(copying.workingName()).isNull();
		assertThat(copying.workingAt()).isNull();
		assertThat(GITHUB.organizationRepositoryId(JobSetups.workingName(this.job)))
			.as("no Working Repository was created").isNull();
		assertThat(GITHUB.organizationRepositoryId(JobSetups.intermediaryName(this.job)))
			.as("the one read-only copy was created").isEqualTo(copying.intermediaryId());
		Map<String, Object> dispatchedRun = sourceRuns().stream().findFirst().orElseThrow();
		assertThat(inputs(dispatchedRun)).as("the copy has one target")
			.containsEntry("working", "")
			.containsEntry("intermediary", copying.intermediary().fullName());
		assertThat(this.runs.correlation(copying.correlationId()).orElseThrow().grant())
			.as("AD-44: the grant names the Intermediary alone")
			.containsExactly(copying.intermediaryId());
		exchange(copying).andExpect(status().isOk());

		GITHUB.copyCommit("lan-dev", this.sourceName, copying.approvedSha(), JobSetups.intermediaryName(this.job),
				copying.defaultBranch());
		GITHUB.completeRun(runId(copying), "success");
		runCompleted(copying);
		awaitListeners();

		Setup done = this.setups.latest(this.job).orElseThrow();
		assertThat(done.status()).isEqualTo("DONE");
		assertThat(done.copiedAt()).isNotNull();
		assertThat(this.busy.job(this.job).orElseThrow().state()).as("J7 applied in the resolving transaction")
			.isEqualTo("ACTIVE");
		assertThat(this.busy.job(this.job).orElseThrow().busy()).as("the marker is released").isEmpty();
		assertThat(this.grants.ofJob(this.job)).as("the Mentor, the Crew and the Customer, each reading the one copy")
			.hasSize(3)
			.allSatisfy(grant -> {
				assertThat(grant.readOnly()).as("read for the whole life of the grant").isTrue();
				assertThat(grant.status()).as("J7 waited for no acceptance (R6)").isEqualTo("INVITING");
				assertThat(grant.repositoryName()).isEqualTo(GitHubStub.ORGANIZATION + "/" + JobSetups.intermediaryName(this.job));
			});
		assertThat(this.grants.ofJob(this.job)).extracting(JobGrants.Grant::role)
			.containsExactly("MENTOR", "CREW", JobGrants.CUSTOMER);
		assertThat(this.jdbc.sql("SELECT count(*) FROM operations.external_operation WHERE type = :type")
			.param("type", JobGrants.INVITE).query(Long.class).single()).as("one invitation each").isEqualTo(3);
		assertThat(this.jdbc.sql("SELECT count(*) FROM operations.external_operation WHERE type IN (:verify, :raise)")
			.param("verify", JobGrants.VERIFY).param("raise", JobGrants.RAISE).query(Long.class).single())
			.as("no Verify and no raise is ever requested for an Assessment Job").isZero();
		// The test clock stands still within a step, so the rows share an occurred_at: the facts are asserted, not an order.
		assertThat(this.jdbc.sql("SELECT action FROM audit.audit_entry WHERE job_id = :job AND action LIKE 'repo.setup_%'")
			.param("job", this.job).query(String.class).list())
			.as("the same chain a Code Job records, with one repository, and J7 applied from the Setup itself")
			.containsExactlyInAnyOrder("repo.setup_started", "repo.setup_source_checked", "repo.setup_repositories_ready",
					"repo.setup_copy_dispatched", "repo.setup_copied", "repo.setup_done", "repo.setup_activated");
		assertThat(this.jdbc.sql("SELECT count(*) FROM audit.audit_entry WHERE job_id = :job AND action = 'repo.source_confirmed'")
			.param("job", this.job).query(Long.class).single()).isOne();
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/setup").with(as(this.mentor, Role.MENTOR)))
			.andExpect(jsonPath("$.assessment").value(true))
			.andExpect(jsonPath("$.status").value("DONE"))
			.andExpect(jsonPath("$.steps[*].state")
				.value(org.hamcrest.Matchers.contains("DONE", "DONE", "DONE", "DONE", "CURRENT")))
			.andExpect(jsonPath("$.people[0].role").value("MENTOR"))
			.andExpect(jsonPath("$.people[0].state").value("INVITING"));
	}

	/**
	 * Story 23.11a: an assessment copy whose J7 is moot -- the Job's row is no longer live, as an erased Customer's
	 * Product deletion leaves it during the read-back window -- still counts as copied. The row is {@code DONE}, the
	 * moot activation is audited the way {@code JobGrants.verified} audits Verify's, and the Job does not become
	 * Active. {@code done(...)} is called directly, because the operation's own guard reads the Job as gone first.
	 * Story 23.11b: such a Job grants nobody.
	 */
	@Test
	void anAssessmentCopyWhoseActivationIsMootIsStillDoneAndAudited() throws Exception {
		assessmentJob();
		assessmentConfirmTheHead();
		run();
		Setup copying = this.setups.latest(this.job).orElseThrow();
		assertThat(copying.status()).isEqualTo("RUNNING");
		assertThat(copying.intermediaryAt()).isNotNull();
		this.jdbc.sql("UPDATE job.job SET product_deleted_at = :now WHERE id = :id")
			.param("now", Timestamp.from(this.clock.instant())).param("id", this.job).update();

		this.setups.done(copying, null);

		assertThat(this.setups.find(copying.id()).orElseThrow()).satisfies(done -> {
			assertThat(done.status()).as("the copy still counts as done").isEqualTo("DONE");
			assertThat(done.copiedAt()).isNotNull();
		});
		assertThat(this.jdbc.sql("SELECT action FROM audit.audit_entry WHERE job_id = :job AND action LIKE 'repo.setup_%'")
			.param("job", this.job).query(String.class).list())
			.contains("repo.setup_activation_moot")
			.doesNotContain("repo.setup_activated");
		assertThat(this.jdbc.sql("SELECT state FROM job.job WHERE id = :id").param("id", this.job).query(String.class)
			.single()).as("J7 never applied").isEqualTo("STARTING");
		assertThat(this.grants.ofJob(this.job)).as("a Job whose row is no longer live grants nobody").isEmpty();
		awaitListeners();
	}

	/**
	 * Story 23.11a (R6, R12): a Source whose {@code vibefix.yml} is an accepted version published before the
	 * one-repository copy blocks an Assessment Job's Setup with {@code WORKFLOW_TOO_OLD} before anything is created:
	 * the Job stays in Starting with its funds held, the Customer and the Mentor are told why, and a retry after the
	 * published file is added again starts a new attempt. A Code Job on the same file is never blocked.
	 */
	@Test
	void anAssessmentSetupOnAnEarlierWorkflowBlocksWorkflowTooOldAndRunsAfterTheFileIsAddedAgain() throws Exception {
		assessmentJob();
		GITHUB.sourceFiles.put(this.source + ":" + WORKFLOW_PATH, TWO_REPOSITORY_WORKFLOW);

		j6();
		run();
		awaitListeners();

		Setup blocked = this.setups.latest(this.job).orElseThrow();
		assertThat(blocked.status()).isEqualTo("BLOCKED");
		assertThat(blocked.block()).isEqualTo(JobSetups.Block.WORKFLOW_TOO_OLD);
		assertThat(blocked.intermediaryId()).as("nothing was created").isNull();
		assertThat(this.busy.job(this.job).orElseThrow().state()).isEqualTo("STARTING");
		assertThat(this.busy.job(this.job).orElseThrow().busy()).isEmpty();
		assertThat(this.jdbc.sql("SELECT params ->> 'reason' FROM notification.notification WHERE type = 'repo.job_start_blocked'")
			.query(String.class).list()).as("the Customer and the Mentor, each with the reason").hasSize(2)
			.containsOnly(JobSetups.REASONS.get(JobSetups.Block.WORKFLOW_TOO_OLD));
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/setup").with(as(this.customer, Role.CUSTOMER)))
			.andExpect(jsonPath("$.assessment").value(true))
			.andExpect(jsonPath("$.blockedReason").value("WORKFLOW_TOO_OLD"))
			.andExpect(jsonPath("$.steps[0].state").value("BLOCKED"))
			.andExpect(jsonPath("$.canRetry").value(true));

		GITHUB.sourceFiles.put(this.source + ":" + WORKFLOW_PATH, GitHubStub.published(GitHubStub.CUSTOMER_WORKFLOW));
		this.clock.advance(Duration.ofMinutes(1));
		this.mvc.perform(post("/api/v1/jobs/" + this.job + "/setup/retry").with(as(this.customer, Role.CUSTOMER)).with(csrf()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CONFIRMING"));
		Setup retried = this.setups.latest(this.job).orElseThrow();
		assertThat(retried.id()).as("a new attempt").isNotEqualTo(blocked.id());
		run();
		assertThat(this.setups.latest(this.job).orElseThrow().headSha()).as("it asks the Customer again").isNotNull();
		awaitListeners();
	}

	/** Story 23.11a: a Code Job's copy has two targets, so an earlier {@code vibefix.yml} never blocks it. */
	@Test
	void aCodeJobOnAnEarlierWorkflowIsNeverBlockedWorkflowTooOld() throws Exception {
		GITHUB.sourceFiles.put(this.source + ":" + WORKFLOW_PATH, TWO_REPOSITORY_WORKFLOW);
		GITHUB.push("lan-dev", this.sourceName, "main", WORKFLOW_PATH, TWO_REPOSITORY_WORKFLOW);

		j6();
		String head = waitForTheCustomer();
		confirm(head).andExpect(status().isOk());
		run();

		Setup copying = this.setups.latest(this.job).orElseThrow();
		assertThat(copying.assessment()).isFalse();
		assertThat(copying.status()).isEqualTo("RUNNING");
		assertThat(copying.workingAt()).as("both repositories were read back").isNotNull();
		assertThat(inputs(sourceRuns().stream().findFirst().orElseThrow()))
			.containsEntry("working", copying.working().fullName());
		awaitListeners();
	}

	/**
	 * Story 23.11a (R4, R6): a head that moved after the confirmation sends an assessment attempt back to waiting with
	 * the new head, exactly as a Code Job's; nothing more is copied, and the Intermediary stays as it was.
	 */
	@Test
	void anAssessmentCopyWhoseHeadMovedGoesBackToWaitingForTheNewHead() throws Exception {
		assessmentJob();
		String head = assessmentConfirmTheHead();
		run();
		Setup dispatched = this.setups.latest(this.job).orElseThrow();
		assertThat(dispatched.correlationId()).isNotNull();
		// The head moves between the dispatch's own head read and the run's start, so the run starts at the new head.
		String moved = GITHUB.push("lan-dev", this.sourceName, "main", "app.js", "console.log('Lovable')\n");
		GITHUB.runs.get(runId(dispatched)).put("head_sha", moved);

		exchange(dispatched).andExpect(status().isForbidden()).andExpect(jsonPath("$.reason").value("SHA"));
		GITHUB.completeRun(runId(dispatched), "failure");
		// Story 24.5a: the refusal wakes the copy.
		awaitListeners();
		run();
		awaitListeners();

		Setup again = this.setups.latest(this.job).orElseThrow();
		assertThat(again.id()).as("the same attempt").isEqualTo(dispatched.id());
		assertThat(again.status()).isEqualTo("CONFIRMING");
		assertThat(again.exchangeReason()).isEqualTo(JobSetups.HEAD_MOVED);
		assertThat(again.headSha()).isEqualTo(moved).isNotEqualTo(head);
		assertThat(again.intermediaryAt()).as("the repository is read back again before the next copy").isNull();
		assertThat(this.busy.job(this.job).orElseThrow().busy()).as("the marker is released").isEmpty();
		assertThat(openConfirmTodos()).isOne();
	}

	/** Story 23.11a: an Assessment Job that leaves Starting while its Setup waits ends the wait, as a Code Job's does. */
	@Test
	void anAssessmentJobThatLeavesStartingWhileItWaitsEndsItsAttempt() throws Exception {
		assessmentJob();
		j6();
		waitForTheCustomer();
		Setup waiting = this.setups.latest(this.job).orElseThrow();

		this.setups.transitioned(new JobTransitioned(UUID.randomUUID(), UUID.randomUUID(), this.job, "J10", "USER",
				"STARTING", "CANCELLED", 4, 1, Map.of()));

		assertThat(this.setups.find(waiting.id()).orElseThrow().status()).isEqualTo("ENDED");
		assertThat(openConfirmTodos()).isZero();
	}

	/**
	 * Story 23.11a (matrix row 10): 30 days after an Assessment Job is Cancelled its one repository is deleted and the
	 * Setup row is marked deleted, with no Working Repository to delete.
	 */
	@Test
	void thirtyDaysAfterAnAssessmentJobEndsItsOneRepositoryIsDeleted() throws Exception {
		assessmentJob();
		assessmentSetUpTheRepository();
		String intermediary = JobSetups.intermediaryName(this.job);
		this.jdbc.sql("UPDATE job.job SET state = 'CANCELLED' WHERE id = :id").param("id", this.job).update();

		this.revocations.transitioned(new JobTransitioned(UUID.randomUUID(), UUID.randomUUID(), this.job, "J10", "USER",
				"ACTIVE", "CANCELLED", 5, 1, Map.of()));
		this.clock.advance(Duration.ofDays(30).plusMinutes(1));
		this.deadlines.runDue();
		this.worker.runDue(Lane.GITHUB_LONG);

		assertThat(GITHUB.deleted).contains(intermediary).doesNotContain(JobSetups.workingName(this.job));
		assertThat(this.jdbc.sql("SELECT count(*) FROM repo.job_setup WHERE job_id = :job AND deleted_at IS NOT NULL")
			.param("job", this.job).query(Long.class).single()).isOne();
	}

	// --------------------------------------- Story 23.11b: the read-only invitations on the copy (R6, R17)

	/**
	 * Story 23.11b (matrix rows 2, 4, 5 and 8): everyone invited to an Assessment Job's copy accepts a read, and that
	 * is where it ends. The invitations go out with read permission on the one Intermediary Repository while the Job is
	 * already Active (R6), the tracker's {@code INVITE} step reads the team rows alone -- current until each of them is
	 * accepted with two-factor authentication on, whatever the Job's state -- a member whose two-factor authentication
	 * is off hears the read wording of the notice and never the write one, no raise and no Verify is ever requested,
	 * and once every read is accepted the sweep drops the Job. The row's own CHECK would refuse the write anyway.
	 */
	@Test
	void theAssessmentTeamAcceptsReadsThatAreNeverRaisedAndSettleTheJob() throws Exception {
		assessmentJob();
		String mentorLogin = TestPeople.login(this.jdbc, this.mentor);
		String crewLogin = TestPeople.login(this.jdbc, this.crew);
		String customerLogin = TestPeople.login(this.jdbc, this.customer);
		assessmentSetUpTheRepository();
		this.worker.runDue(Lane.GITHUB_SHORT);

		String copy = GitHubStub.ORGANIZATION + "/" + JobSetups.intermediaryName(this.job);
		assertThat(this.grants.ofJob(this.job)).extracting(JobGrants.Grant::status).containsOnly("INVITED");
		assertThat(GITHUB.seen).as("the Mentor is invited to the copy with read")
			.anyMatch(seen -> seen.method().equals("PUT")
					&& seen.path().equals("/repos/" + copy + "/collaborators/" + mentorLogin)
					&& seen.body().path("permission").asString("").equals("pull"));
		assertThat(GITHUB.seen).as("nobody is ever asked for write on it")
			.noneMatch(seen -> seen.method().equals("PUT") && seen.path().startsWith("/repos/" + copy + "/collaborators/")
					&& !seen.body().path("permission").asString("").equals("pull"));
		assertThat(notices("repo.read_invitation_sent")).as("the Mentor's and the Crew member's read wording")
			.isEqualTo(2);
		assertThat(notices("repo.github_invitation_sent"))
			.as("never the Working Repository's, which promises write and a start that already happened").isZero();
		assertThat(notices("repo.intermediary_invitation_sent")).as("the Customer's own wording").isOne();
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/setup").with(as(this.mentor, Role.MENTOR)))
			.andExpect(jsonPath("$.jobState").value("ACTIVE"))
			.andExpect(jsonPath("$.steps[4].key").value("INVITE"))
			// The Job is Active already, and its invitations are still out, so the step reads the team rows alone.
			.andExpect(jsonPath("$.steps[4].state").value("CURRENT"))
			.andExpect(jsonPath("$.people[0].githubUrl").value(org.hamcrest.Matchers.endsWith("-intermediary/invitations")));

		GITHUB.accept(mentorLogin);
		GITHUB.accept(crewLogin);
		GITHUB.withoutTwoFactor.add(crewLogin.toLowerCase(java.util.Locale.ROOT));
		this.checks.check(this.job);
		this.worker.runDue(Lane.GITHUB_SHORT);

		assertThat(teamGrants()).extracting(JobGrants.Grant::status).containsOnly("ACCEPTED");
		assertThat(notices("repo.two_factor_required_read")).as("the read wording").isOne();
		assertThat(notices("repo.two_factor_required")).as("never the write one").isZero();
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/setup").with(as(this.mentor, Role.MENTOR)))
			// The Crew member's two-factor authentication is off, so their read does not count yet.
			.andExpect(jsonPath("$.steps[4].state").value("CURRENT"))
			.andExpect(jsonPath("$.people[1].state").value("TWO_FACTOR_MISSING"));
		assertThat(this.grants.unsettledJobs()).as("the Customer's read is still pending").contains(this.job);

		GITHUB.withoutTwoFactor.clear();
		GITHUB.accept(customerLogin);
		this.checks.check(this.job);
		this.worker.runDue(Lane.GITHUB_SHORT);

		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/setup").with(as(this.mentor, Role.MENTOR)))
			.andExpect(jsonPath("$.steps[4].state").value("DONE"))
			.andExpect(jsonPath("$.steps[*].key").value(org.hamcrest.Matchers.contains("CHECK", "CONFIRM", "REPOSITORIES",
					"COPY", "INVITE")));
		assertThat(this.grants.ofJob(this.job)).extracting(JobGrants.Grant::status)
			.as("a read settles at ACCEPTED, the Customer's and the team's alike")
			.containsOnly("ACCEPTED");
		assertThat(this.grants.unsettledJobs()).as("nothing is left to settle, so the sweep drops the Job")
			.doesNotContain(this.job);
		assertThat(this.jdbc.sql("SELECT count(*) FROM operations.external_operation WHERE type IN (:verify, :raise)")
			.param("verify", JobGrants.VERIFY).param("raise", JobGrants.RAISE).query(Long.class).single())
			.as("no Verify and no raise, by any path").isZero();
		assertThat(this.jdbc.sql("SELECT count(*) FROM audit.audit_entry WHERE action = 'repo.grant_raised' AND job_id = :job")
			.param("job", this.job).query(Long.class).single()).isZero();
		assertThat(this.busy.job(this.job).orElseThrow().busy()).isEmpty();
		assertThat(this.busy.job(this.job).orElseThrow().state()).isEqualTo("ACTIVE");
		UUID read = teamGrants().getFirst().id();
		assertThatThrownBy(() -> this.jdbc
			.sql("UPDATE repo.job_grant SET status = 'WRITE', write_at = :now WHERE id = :id")
			.param("now", Timestamp.from(this.clock.instant())).param("id", read).update())
			.as("job_grant_read_only_check refuses the write whatever the caller does")
			.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
		awaitListeners();
	}

	/**
	 * Story 23.11b (matrix row 2's error handling): a raise asked for a read on an Assessment Job's copy is refused
	 * {@code READ_ONLY_GRANT}, the reason the Customer's read already gets, and the grant stays as it was.
	 */
	@Test
	void aRaiseAskedForAnAssessmentReadIsRefusedReadOnlyGrant() throws Exception {
		assessmentJob();
		assessmentSetUpTheRepository();
		this.worker.runDue(Lane.GITHUB_SHORT);
		GITHUB.accept(TestPeople.login(this.jdbc, this.mentor));
		this.checks.check(this.job);
		JobGrants.Grant read = this.grants.ofJob(this.job).stream()
			.filter(grant -> grant.accountId().equals(this.mentor)).findFirst().orElseThrow();
		assertThat(read.status()).isEqualTo("ACCEPTED");
		assertThat(read.readOnly()).isTrue();

		this.grants.requestRaise(read);
		this.worker.runDue(Lane.GITHUB_SHORT);

		assertThat(this.jdbc.sql("""
				SELECT status || ' ' || coalesce(error_code, '') FROM operations.external_operation WHERE type = :type
				""").param("type", JobGrants.RAISE).query(String.class).list()).containsExactly("OBSOLETE READ_ONLY_GRANT");
		assertThat(this.grants.find(read.id()).orElseThrow().status()).as("still a read").isEqualTo("ACCEPTED");
		assertThat(GITHUB.seen).as("nothing was raised on GitHub")
			.noneMatch(seen -> seen.method().equals("PUT") && seen.path().endsWith("/collaborators/"
					+ TestPeople.login(this.jdbc, this.mentor)) && seen.body().path("permission").asString("").equals("push"));
		awaitListeners();
	}

	/**
	 * Story 23.11b (matrix row 3): a Crew member who becomes active after an Assessment Job's Setup is invited to read
	 * its copy as a crew change, and the raise the same change would ask for on a Code Job is never asked here.
	 */
	@Test
	void aCrewMemberWhoJoinsAnAssessmentJobAfterTheSetupReadsTheCopyAndIsNeverRaised() throws Exception {
		assessmentJob();
		assessmentSetUpTheRepository();
		this.worker.runDue(Lane.GITHUB_SHORT);
		this.clock.advance(Duration.ofMinutes(1));
		UUID hai = activeMember();

		this.checks.added(new vn.vibefix.job.CrewMemberAdded(UUID.randomUUID(), this.job, UUID.randomUUID(), hai, "ACTIVE"));
		this.worker.runDue(Lane.GITHUB_SHORT);

		JobGrants.Grant added = this.grants.ofJob(this.job).stream().filter(grant -> grant.accountId().equals(hai))
			.findFirst().orElseThrow();
		assertThat(added.status()).isEqualTo("INVITED");
		assertThat(added.crewChange()).isTrue();
		assertThat(added.readOnly()).as("a late member reads the copy, as everyone does").isTrue();
		assertThat(added.repositoryName()).isEqualTo(GitHubStub.ORGANIZATION + "/" + JobSetups.intermediaryName(this.job));

		GITHUB.accept(TestPeople.login(this.jdbc, hai));
		this.checks.check(this.job);
		this.worker.runDue(Lane.GITHUB_SHORT);

		assertThat(this.grants.find(added.id()).orElseThrow().status()).as("accepted, never raised").isEqualTo("ACCEPTED");
		assertThat(this.jdbc.sql("SELECT count(*) FROM operations.external_operation WHERE type = :type")
			.param("type", JobGrants.RAISE).query(Long.class).single()).isZero();
		awaitListeners();
	}

	/**
	 * Story 23.11b: an Assessment Job is Active while its invitations are out, so a read still pending when GitHub
	 * expires it is invited again there, under the cap a late member's invitation has; then the Mentor and the
	 * Administrators hear, as they do for a Crew change nobody accepted.
	 */
	@Test
	void anAssessmentReadStillPendingIsRenewedWhileTheJobIsActiveAndThenReported() throws Exception {
		AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		assessmentJob();
		assessmentSetUpTheRepository();
		this.worker.runDue(Lane.GITHUB_SHORT);
		assertThat(this.busy.job(this.job).orElseThrow().state()).isEqualTo("ACTIVE");

		this.clock.advance(Duration.ofDays(7).plusMinutes(1));
		this.deadlines.runDue();
		this.worker.runDue(Lane.GITHUB_SHORT);

		assertThat(crewGrant().renewals()).as("renewed although the Job is no longer Starting").isOne();
		assertThat(crewGrant().status()).isEqualTo("INVITED");
		for (int renewal = 1; renewal < JobGrants.CREW_CHANGE_RENEWALS; renewal++) {
			this.clock.advance(Duration.ofDays(7).plusMinutes(1));
			this.deadlines.runDue();
			this.worker.runDue(Lane.GITHUB_SHORT);
		}
		this.clock.advance(Duration.ofDays(7).plusMinutes(1));
		this.deadlines.runDue();

		assertThat(crewGrant().renewals()).isEqualTo(JobGrants.CREW_CHANGE_RENEWALS);
		List<UUID> heard = this.jdbc.sql("""
				SELECT DISTINCT account_id FROM notification.notification WHERE type = 'repo.invitation_renewals_exhausted'
				""").query(UUID.class).list();
		assertThat(heard).as("the Mentor hears of a read nobody accepted, their own invitation included")
			.contains(this.mentor);
		assertThat(heard).as("and an Administrator with them").hasSizeGreaterThan(1);
		awaitListeners();
	}

	/**
	 * Story 23.11b: the platform sweep expects the team's reads on an Assessment Job's copy, and their acceptance there
	 * is their own {@code member} event, as the Customer's is (FR-19, SM-2, AD-12). A write collaborator on that copy is
	 * still a difference, and a pull request by one of its readers still alerts: the copy is the System's to change.
	 */
	@Test
	void theSweepExpectsTheAssessmentTeamsReadsOnTheCopyAndStillGuardsIt() throws Exception {
		AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		assessmentJob();
		String mentorLogin = TestPeople.login(this.jdbc, this.mentor);
		assessmentSetUpTheRepository();
		this.worker.runDue(Lane.GITHUB_SHORT);
		GITHUB.accept(mentorLogin);
		GITHUB.accept(TestPeople.login(this.jdbc, this.crew));
		GITHUB.accept(TestPeople.login(this.jdbc, this.customer));
		this.checks.check(this.job);
		String copy = JobSetups.intermediaryName(this.job);
		GITHUB.eventActors.put(copy, List.of("vibefix-dev-platform[bot]", "MemberEvent:" + mentorLogin));

		assertThat(sweep()).as("three reads on the copy, each accepted, and a reader's own member event").isEmpty();

		GITHUB.eventActors.put(copy,
				List.of("vibefix-dev-platform[bot]", "MemberEvent:" + mentorLogin, "PushEvent:" + mentorLogin));
		assertThat(sweep()).as("but nothing else of theirs on the copy")
			.anyMatch(difference -> difference.contains("recent changes by [" + mentorLogin + "]"));
		GITHUB.eventActors.remove(copy);

		long copyId = GITHUB.organizationRepositoryId(copy);
		this.guard.hinted(webhook("pull_request", copyId, mentorLogin, githubUserId(this.mentor)));
		GITHUB.addCollaborator(copy, "mallory", "write");

		assertThat(sweep()).anyMatch(difference -> difference.contains("mallory=write"));
		assertThat(notices("github.unexpected_actor")).as("a reader's pull request on the copy still alerts").isOne();
		awaitListeners();
	}

	/**
	 * Story 23.11b (matrix row 5's error handling): the Customer's own read never holds "Mời đọc kho trung gian trên
	 * GitHub" (R5), on an Assessment Job as on a Code Job. With the Mentor's and the Crew member's reads accepted and
	 * the Customer's still out, the step is already done.
	 */
	@Test
	void theCustomersOwnReadDoesNotHoldTheAssessmentInviteStep() throws Exception {
		assessmentJob();
		assessmentSetUpTheRepository();
		this.worker.runDue(Lane.GITHUB_SHORT);
		GITHUB.accept(TestPeople.login(this.jdbc, this.mentor));
		GITHUB.accept(TestPeople.login(this.jdbc, this.crew));
		this.checks.check(this.job);
		this.worker.runDue(Lane.GITHUB_SHORT);

		assertThat(teamGrants()).extracting(JobGrants.Grant::status).containsOnly("ACCEPTED");
		assertThat(customerGrant().status()).as("the Customer has not accepted theirs").isEqualTo("INVITED");
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/setup").with(as(this.mentor, Role.MENTOR)))
			.andExpect(jsonPath("$.steps[4].key").value("INVITE"))
			.andExpect(jsonPath("$.steps[4].state").value("DONE"));
		awaitListeners();
	}

	/**
	 * Story 23.11b (matrix row 3's error handling): a Crew member who joins before the copy exists is granted nothing --
	 * the Setup is not done and there is no repository to read -- and the Setup's own resolving transaction invites them
	 * with everyone else when it gets there.
	 */
	@Test
	void aCrewMemberWhoJoinsAnAssessmentJobBeforeItsCopyIsGrantedNothing() throws Exception {
		assessmentJob();
		j6();
		waitForTheCustomer();
		UUID hai = activeMember();

		this.checks.added(new vn.vibefix.job.CrewMemberAdded(UUID.randomUUID(), this.job, UUID.randomUUID(), hai, "ACTIVE"));
		this.worker.runDue(Lane.GITHUB_SHORT);

		assertThat(this.setups.latest(this.job).orElseThrow().status()).as("no copy yet").isEqualTo("CONFIRMING");
		assertThat(this.grants.ofJob(this.job)).as("nothing to read, so nothing is granted").isEmpty();
		awaitListeners();
	}

	/**
	 * Story 23.11b: a read accepted while two-factor authentication was off keeps the Job in the sweep, because the
	 * Invite step waits for that flag and this sweep is the only thing that clears it -- which is what the read notice
	 * promises. Dropping the Job as soon as every row read {@code ACCEPTED} would leave the step current, and the
	 * member's own row reading {@code TWO_FACTOR_MISSING}, for the rest of the Job.
	 */
	@Test
	void anAssessmentReadAcceptedWithoutTwoFactorIsSweptAgainUntilItIsOn() throws Exception {
		assessmentJob();
		String crewLogin = TestPeople.login(this.jdbc, this.crew);
		assessmentSetUpTheRepository();
		this.worker.runDue(Lane.GITHUB_SHORT);
		GITHUB.withoutTwoFactor.add(crewLogin.toLowerCase(java.util.Locale.ROOT));
		GITHUB.accept(TestPeople.login(this.jdbc, this.mentor));
		GITHUB.accept(crewLogin);
		GITHUB.accept(TestPeople.login(this.jdbc, this.customer));

		this.checks.sweep();
		this.worker.runDue(Lane.GITHUB_SHORT);

		assertThat(this.grants.ofJob(this.job)).extracting(JobGrants.Grant::status).containsOnly("ACCEPTED");
		assertThat(crewGrant().twoFactorMissing()).isTrue();
		assertThat(this.grants.unsettledJobs()).as("a read waiting for two-factor authentication is not settled")
			.contains(this.job);
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/setup").with(as(this.mentor, Role.MENTOR)))
			.andExpect(jsonPath("$.steps[4].state").value("CURRENT"));

		GITHUB.withoutTwoFactor.clear();
		this.checks.sweep();
		this.worker.runDue(Lane.GITHUB_SHORT);

		assertThat(crewGrant().twoFactorMissing()).as("the next sweep found it on").isFalse();
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/setup").with(as(this.mentor, Role.MENTOR)))
			.andExpect(jsonPath("$.steps[4].state").value("DONE"));
		assertThat(this.grants.unsettledJobs()).as("and only then does the sweep let the Job go")
			.doesNotContain(this.job);
		awaitListeners();
	}

	/**
	 * Story 23.11b (matrix rows 9 and 10), which this story inherits and must not break: J8 revokes every team read on
	 * the copy and reads it back, the Customer's read stays, and the deletion 30 days after the end ends that read too.
	 * A revoked read still answers for the acceptance GitHub keeps listing among the copy's recent events, so the
	 * platform sweep of a closing Job reports no change by somebody else and nobody is alerted for 30 days.
	 */
	@Test
	void j8RevokesTheAssessmentTeamsReadsAndTheCustomersEndsWithTheCopy() throws Exception {
		AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		assessmentJob();
		String mentorLogin = TestPeople.login(this.jdbc, this.mentor);
		assessmentSetUpTheRepository();
		this.worker.runDue(Lane.GITHUB_SHORT);
		GITHUB.accept(mentorLogin);
		GITHUB.accept(TestPeople.login(this.jdbc, this.crew));
		GITHUB.accept(TestPeople.login(this.jdbc, this.customer));
		this.checks.check(this.job);
		String copy = JobSetups.intermediaryName(this.job);
		GITHUB.eventActors.put(copy, List.of("vibefix-dev-platform[bot]", "MemberEvent:" + mentorLogin));

		this.revocations.transitioned(new JobTransitioned(UUID.randomUUID(), UUID.randomUUID(), this.job, "J8", "SYSTEM",
				"ACTIVE", "CLOSING", 5, 1, Map.of()));
		this.worker.runDue(Lane.GITHUB_SHORT);

		assertThat(teamGrants()).extracting(JobGrants.Grant::status).as("R17: the team's read ends at J8")
			.containsOnly("REVOKED");
		assertThat(customerGrant().status()).as("R5: the Customer reads on until the deletion").isEqualTo("ACCEPTED");
		assertThat(sweep()).as("the acceptance of a read this platform revoked is still that read's own, so the daily "
				+ "drift report of a closing Job stays empty for the 30 days before the copy goes").isEmpty();
		GITHUB.eventActors.remove(copy);

		this.jdbc.sql("UPDATE job.job SET state = 'CLOSED' WHERE id = :id").param("id", this.job).update();
		this.revocations.transitioned(new JobTransitioned(UUID.randomUUID(), UUID.randomUUID(), this.job, "J9", "SYSTEM",
				"CLOSING", "CLOSED", 6, 1, Map.of()));
		this.clock.advance(Duration.ofDays(30).plusMinutes(1));
		this.deadlines.runDue();
		this.worker.runDue(Lane.GITHUB_LONG);

		assertThat(GITHUB.deleted).contains(JobSetups.intermediaryName(this.job));
		assertThat(this.grants.ofJob(this.job)).extracting(JobGrants.Grant::status)
			.as("every grant on the copy has ended").containsOnly("REVOKED");
		awaitListeners();
	}

	/**
	 * Story 23.6b (AD-24): a self-confirmation is the Customer's personal data, exported for them alone, and the Job's
	 * record: an erasure anonymizes it, keeping the head, the notice version and the time.
	 */
	@Test
	void theSelfConfirmationIsExportedForTheCustomerAndAnonymizedOnTheirErasure() throws Exception {
		String head = confirmTheHead();

		PersonalDataSection section = this.personalData.export(this.customer).orElseThrow();
		assertThat(section.data()).extractingByKey("sourceSelfConfirmations")
			.asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
			.singleElement()
			.isEqualTo(Map.of("jobId", this.job.toString(), "headSha", head, "noticeVersion", "1.1", "at",
					this.clock.instant().toString()));
		assertThat(this.personalData.export(this.mentor).map(PersonalDataSection::data).orElse(Map.of()))
			.doesNotContainKey("sourceSelfConfirmations");

		new org.springframework.transaction.support.TransactionTemplate(this.transactions)
			.executeWithoutResult(status -> this.personalData.eraseAccount(this.customer));

		assertThat(this.jdbc.sql("""
				SELECT coalesce(account_id::text, 'none') || ' ' || head_sha || ' ' || notice_version FROM repo.source_confirmation
				WHERE job_id = :job
				""").param("job", this.job).query(String.class).list()).containsExactly("none " + head + " 1.1");
		assertThat(this.personalData.export(this.customer).map(PersonalDataSection::data).orElse(Map.of()))
			.doesNotContainKey("sourceSelfConfirmations");
	}

	/**
	 * Story 23.6b: a Setup the migration sent back to waiting ({@code CONFIRMING}, unchecked) while its Job still held the
	 * old marker under the attempt's key: its waiting stage releases that marker, and the Customer confirms.
	 */
	@Test
	void aSetupTheMigrationSentBackToWaitingReleasesItsOldMarkerAndIsConfirmed() throws Exception {
		UUID setup = UUID.randomUUID();
		String key = JobSetups.OPERATION + ":" + setup;
		new org.springframework.transaction.support.TransactionTemplate(this.transactions).executeWithoutResult(status -> {
			this.jdbc.sql("""
					INSERT INTO repo.job_setup (id, job_id, product_id, status, started_at)
					VALUES (:id, :job, :product, 'CONFIRMING', :now)
					""").param("id", setup).param("job", this.job).param("product", this.product)
				.param("now", Timestamp.from(this.clock.instant())).update();
			this.busy.reserve(this.job, JobBusy.Purpose.JOB_SETUP, null, 3, key);
			this.operations.request(new vn.vibefix.operations.OperationRequest(JobSetups.OPERATION, setup,
					Map.of("setupId", setup.toString()), null, null, 0));
		});
		assertThat(this.busy.job(this.job).orElseThrow().busy()).hasValueSatisfying(
				marker -> assertThat(marker.operationKey()).isEqualTo(key));

		String head = waitForTheCustomer();

		assertThat(this.busy.job(this.job).orElseThrow().busy()).as("the old marker is gone").isEmpty();
		confirm(head).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RUNNING"));
		awaitListeners();
	}

	/**
	 * Story 23.6b: the confirmation's own head read finds the Source App gone, so the waiting Setup blocks, its to-do
	 * closes, and the Customer and the Mentor are told; nothing is recorded.
	 */
	@Test
	void aConfirmationThatFindsTheSourceAppGoneBlocksTheWaitingSetup() throws Exception {
		j6();
		String head = waitForTheCustomer();
		GITHUB.mergeInstallations.remove(this.installation);
		GITHUB.repositoryInstallations.values().remove(this.installation);

		confirm(head).andExpect(status().is(422))
			.andExpect(jsonPath("$.code").value("GUARD_FAILED"))
			.andExpect(jsonPath("$.reason").value("SOURCE_APP_MISSING"));

		Setup blocked = this.setups.latest(this.job).orElseThrow();
		assertThat(blocked.status()).isEqualTo("BLOCKED");
		assertThat(blocked.block()).isEqualTo(JobSetups.Block.SOURCE_APP_MISSING);
		assertThat(openConfirmTodos()).isZero();
		assertThat(this.jdbc.sql("SELECT params ->> 'reason' FROM notification.notification WHERE type = 'repo.job_start_blocked'")
			.query(String.class).list()).as("the Customer and the Mentor").hasSize(2)
			.containsOnly(JobSetups.REASONS.get(JobSetups.Block.SOURCE_APP_MISSING));
		assertThat(this.jdbc.sql("SELECT count(*) FROM repo.source_confirmation WHERE job_id = :job").param("job", this.job)
			.query(Long.class).single()).isZero();
		awaitListeners();
	}

	/** Story 23.6b: a confirmation while GitHub cannot be read records nothing, and the Setup keeps waiting. */
	@Test
	void aConfirmationWhileGitHubCannotBeReadRecordsNothing() throws Exception {
		j6();
		String head = waitForTheCustomer();
		String prefix = "GET /repos/" + this.source + "/git/ref/heads/";
		GITHUB.unavailable.add(prefix);
		try {
			confirm(head).andExpect(status().is(422))
				.andExpect(jsonPath("$.code").value("GUARD_FAILED"))
				.andExpect(jsonPath("$.reason").value("GITHUB_UNAVAILABLE"));
		}
		finally {
			GITHUB.unavailable.remove(prefix);
		}

		assertThat(this.setups.latest(this.job).orElseThrow().status()).isEqualTo("CONFIRMING");
		assertThat(this.jdbc.sql("SELECT count(*) FROM repo.source_confirmation WHERE job_id = :job").param("job", this.job)
			.query(Long.class).single()).isZero();
		assertThat(this.busy.job(this.job).orElseThrow().busy()).isEmpty();
		assertThat(openConfirmTodos()).isOne();
	}

	/** Story 23.6b (AD-13): the copy stage checks the admin right again, so a Customer who lost it dispatches nothing. */
	@Test
	void aCustomerWhoLostTheAdminRightAfterConfirmingBlocksTheCopyBeforeAnyDispatch() throws Exception {
		confirmTheHead();
		GITHUB.sourcePermissions.put(this.source, Map.of(GITHUB.loginOf(githubUserId(this.customer)), "write"));

		run();

		Setup blocked = this.setups.latest(this.job).orElseThrow();
		assertThat(blocked.status()).isEqualTo("BLOCKED");
		assertThat(blocked.block()).isEqualTo(JobSetups.Block.NO_ADMIN_PERMISSION);
		assertThat(sourceRuns()).as("nothing was dispatched").isEmpty();
		assertThat(this.busy.job(this.job).orElseThrow().busy()).isEmpty();
		awaitListeners();
	}

	/**
	 * Story 23.6b (deferred-work 638): the dispatch reads {@code vibefix.yml} itself, so a file that changed after the
	 * check blocks {@code WORKFLOW_MISMATCH}, with nothing dispatched.
	 */
	@Test
	void aWorkflowFileThatChangesBetweenTheCheckAndTheDispatchBlocksBeforeAnyRun() throws Exception {
		confirmTheHead();
		String file = this.source + ":.github/workflows/vibefix.yml";
		String changed = GitHubStub.published(GitHubStub.CUSTOMER_WORKFLOW) + "# changed\n";
		// Only the dispatch reads its target by ID: from then on, the file is another one.
		GITHUB.beforeAnswer = request -> {
			if (request.method().equals("GET") && request.path().equals("/repositories/" + this.sourceId)) {
				GITHUB.sourceFiles.put(file, changed);
			}
		};
		try {
			run();
		}
		finally {
			GITHUB.beforeAnswer = request -> {
			};
		}

		Setup blocked = this.setups.latest(this.job).orElseThrow();
		assertThat(blocked.status()).isEqualTo("BLOCKED");
		assertThat(blocked.block()).isEqualTo(JobSetups.Block.WORKFLOW_MISMATCH);
		assertThat(this.jdbc.sql("SELECT status FROM operations.external_operation WHERE operation_key = :key")
			.param("key", JobSetups.OPERATION + ":" + blocked.confirmationId()).query(String.class).single())
			.isEqualTo("ABORTED");
		assertThat(sourceRuns()).as("nothing was dispatched").isEmpty();
		assertThat(this.busy.job(this.job).orElseThrow().busy()).isEmpty();
		awaitListeners();
	}

	@Test
	void aGitHubFailureReadingTheHeadIsRetriedAndTheNextAttemptAsksTheCustomer() throws Exception {
		String prefix = "GET /repos/" + this.source + "/git/ref/heads/";
		GITHUB.unavailable.add(prefix);
		try {
			j6();
			run();

			Setup waiting = this.setups.latest(this.job).orElseThrow();
			assertThat(waiting.status()).isEqualTo("CONFIRMING");
			assertThat(waiting.checkedAt()).as("step 1 is not done").isNull();
			assertThat(this.jdbc.sql("""
					SELECT status || ':' || error_code FROM operations.external_operation WHERE type = 'repo.job_setup'
					""").query(String.class).single()).isEqualTo("PENDING:GITHUB_HTTP_502");
			assertThat(openConfirmTodos()).as("nothing to confirm yet").isZero();
		}
		finally {
			GITHUB.unavailable.remove(prefix);
		}
		this.clock.advance(JobSetupOperation.POLICY.delayAfter(1).plusSeconds(1));
		run();

		Setup waiting = this.setups.latest(this.job).orElseThrow();
		assertThat(waiting.status()).isEqualTo("CONFIRMING");
		assertThat(waiting.headSha()).isEqualTo(GITHUB.head("lan-dev", this.sourceName, "main"));
		assertThat(openConfirmTodos()).isOne();
		assertThat(GITHUB.organizationRepositoryId(JobSetups.intermediaryName(this.job))).as("nothing was created").isNull();
	}

	/**
	 * Story 23.5: the Customer changed {@code vibefix.yml} after registration, so Setup blocks before anything is
	 * confirmed or copied with {@code WORKFLOW_MISMATCH}, until they add the published file again and try again. A
	 * repository that is not public reads through the Source App like any other.
	 */
	@Test
	void aChangedWorkflowFileBlocksBeforeAnyCopyUntilTheCustomerAddsThePublishedOneAndTriesAgain() throws Exception {
		String name = this.sourceName;
		long id = this.sourceId;
		long owner = githubUserId(this.customer);
		GITHUB.publish("lan-dev", name, id, owner, false, false);
		String published = GitHubStub.published(GitHubStub.CUSTOMER_WORKFLOW);
		GITHUB.sourceFiles.put(this.source + ":.github/workflows/vibefix.yml", published + "      # changed\n");

		j6();
		run();

		Setup blocked = this.setups.latest(this.job).orElseThrow();
		assertThat(blocked.block()).isEqualTo(JobSetups.Block.WORKFLOW_MISMATCH);
		assertThat(blocked.headSha()).as("no head was read").isNull();
		assertThat(blocked.approvedSha()).isNull();
		assertThat(this.jdbc.sql("SELECT status FROM operations.external_operation WHERE type = 'repo.job_setup'")
			.query(String.class).single()).isEqualTo("ABORTED");
		assertThat(this.busy.job(this.job).orElseThrow().busy()).as("no marker was ever held").isEmpty();
		assertThat(this.jdbc.sql("SELECT params ->> 'reason' FROM notification.notification WHERE type = 'repo.job_start_blocked'")
			.query(String.class).list()).as("the Customer and the Mentor, each with the reason").hasSize(2)
			.containsOnly("file workflow vibefix.yml trong dự án gốc khác bản vibefix công bố");
		assertThat(notices("repo.self_confirmation_due")).as("nothing to confirm").isZero();
		assertThat(GITHUB.organizationRepositoryId(JobSetups.intermediaryName(this.job))).as("nothing was created").isNull();
		assertThat(GITHUB.revokedTokens).as("every Source App token is revoked")
			.containsAll(GITHUB.tokensOf(this.installation));
		assertThat(GITHUB.tokensOf(this.installation)).as("the selection read, then one token narrowed to the repository")
			.anySatisfy(token -> assertThat(GITHUB.grantOf(token).path("repository_ids").toString()).isEqualTo("[" + id + "]"));
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/setup").with(as(this.customer, Role.CUSTOMER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("BLOCKED"))
			.andExpect(jsonPath("$.steps[0].state").value("BLOCKED"))
			.andExpect(jsonPath("$.blockedReason").value("WORKFLOW_MISMATCH"))
			.andExpect(jsonPath("$.canRetry").value(true));

		GITHUB.publish("lan-dev", name, id, owner, true, false);
		GITHUB.sourceFiles.put(this.source + ":.github/workflows/vibefix.yml", published);
		// The new attempt starts later than the blocked one, as it would on a running clock.
		this.clock.advance(Duration.ofMinutes(1));
		this.mvc.perform(post("/api/v1/jobs/" + this.job + "/setup/retry").with(as(this.customer, Role.CUSTOMER)).with(csrf()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CONFIRMING"));
		String head = waitForTheCustomer();
		confirm(head).andExpect(status().isOk());
		run();
		copyRunSucceeds();

		Setup done = this.setups.latest(this.job).orElseThrow();
		assertThat(done.id()).as("a new attempt").isNotEqualTo(blocked.id());
		assertThat(done.status()).isEqualTo("DONE");
		assertThat(done.approvedSha()).isEqualTo(GITHUB.head("lan-dev", name, "main"));
		awaitListeners();
	}

	@Test
	void aFailedAttemptRetriedFromAdm7ChecksTheSourceAgainAndAsksTheCustomer() throws Exception {
		// The shape Story 23.1's migration leaves a Setup in that an open Intake Finding blocked: FAILED before step 1
		// ended, with its operation FAILED in ADM-7's queue.
		Setup failed = failedOnTheHeadRead();

		assertThat(this.failedOperations.retry(operationOf(failed))).isTrue();
		run();

		Setup waiting = this.setups.latest(this.job).orElseThrow();
		assertThat(waiting.id()).as("the same attempt, resumed").isEqualTo(failed.id());
		assertThat(waiting.status()).as("waiting for the Customer").isEqualTo("CONFIRMING");
		assertThat(waiting.headSha()).isEqualTo(GITHUB.head("lan-dev", this.sourceName, "main"));
		assertThat(this.busy.job(this.job).orElseThrow().busy()).isEmpty();
		confirm(waiting.headSha()).andExpect(status().isOk());
		run();
		copyRunSucceeds();
		assertThat(this.setups.latest(this.job).orElseThrow().status()).isEqualTo("DONE");
		awaitListeners();
	}

	/** Story 23.6b (FR-64): a copy that failed every attempt runs again, retried from ADM-7, under its own marker. */
	@Test
	void aCopyThatFailedRetriedFromAdm7RunsAgainUnderItsMarker() throws Exception {
		confirmTheHead();
		Setup confirmed = this.setups.latest(this.job).orElseThrow();
		String dispatches = "POST /repos/" + this.source + "/actions/workflows/vibefix.yml/dispatches";
		GITHUB.unavailable.add(dispatches);
		try {
			for (int attempt = 1; attempt <= JobSetupOperation.POLICY.maxAttempts(); attempt++) {
				run();
				this.clock.advance(JobSetupOperation.POLICY.delayAfter(attempt).plusSeconds(1));
			}
			awaitListeners();
		}
		finally {
			GITHUB.unavailable.remove(dispatches);
		}
		Setup failed = this.setups.latest(this.job).orElseThrow();
		assertThat(failed.status()).isEqualTo("FAILED");
		assertThat(failed.confirmationId()).isEqualTo(confirmed.confirmationId());
		assertThat(this.busy.job(this.job).orElseThrow().busy()).as("the failure releases the marker").isEmpty();
		String key = JobSetups.OPERATION + ":" + confirmed.confirmationId();
		UUID operation = this.jdbc.sql("SELECT id FROM operations.external_operation WHERE operation_key = :key")
			.param("key", key).query(UUID.class).single();

		assertThat(this.failedOperations.retry(operation)).isTrue();
		run();

		Setup resumed = this.setups.latest(this.job).orElseThrow();
		assertThat(resumed.id()).isEqualTo(failed.id());
		assertThat(resumed.status()).isEqualTo("RUNNING");
		assertThat(resumed.correlationId()).as("dispatched now").isNotNull();
		assertThat(this.busy.job(this.job).orElseThrow().busy()).hasValueSatisfying(
				marker -> assertThat(marker.operationKey()).isEqualTo(key));
		copyRunSucceeds();
		assertThat(this.setups.latest(this.job).orElseThrow().status()).isEqualTo("DONE");
		awaitListeners();
	}

	@Test
	void aFailedAttemptAnotherReplacedNeverRunsAgain() throws Exception {
		Setup failed = failedOnTheHeadRead();
		// The attempt the Customer started after a block ("Thử lại"), which Story 23.1's migration leaves beside the
		// old one.
		this.clock.advance(Duration.ofMinutes(1));
		this.jdbc.sql("""
				INSERT INTO repo.job_setup (id, job_id, product_id, status, blocked_reason, started_at, finished_at)
				VALUES (:id, :job, :product, 'BLOCKED', 'SOURCE_APP_MISSING', :now, :now)
				""").param("id", UUID.randomUUID()).param("job", this.job).param("product", this.product)
			.param("now", Timestamp.from(this.clock.instant())).update();

		assertThat(this.failedOperations.retry(operationOf(failed))).isTrue();
		run();

		assertThat(this.setups.find(failed.id()).orElseThrow().status()).isEqualTo("FAILED");
		assertThat(this.jdbc.sql("SELECT status || ':' || error_code FROM operations.external_operation WHERE id = :id")
			.param("id", operationOf(failed)).query(String.class).single()).isEqualTo("OBSOLETE:SETUP_REPLACED");
		assertThat(this.busy.job(this.job).orElseThrow().busy()).isEmpty();
		assertThat(GITHUB.organizationRepositoryId(JobSetups.intermediaryName(this.job))).as("nothing was created").isNull();
		awaitListeners();
	}

	/** J6, then every attempt fails reading the Source's head, so the attempt ends FAILED with step 1 not done. */
	private Setup failedOnTheHeadRead() {
		String prefix = "GET /repos/" + this.source + "/git/ref/heads/";
		GITHUB.unavailable.add(prefix);
		try {
			j6();
			for (int attempt = 1; attempt <= JobSetupOperation.POLICY.maxAttempts(); attempt++) {
				run();
				this.clock.advance(JobSetupOperation.POLICY.delayAfter(attempt).plusSeconds(1));
			}
			awaitListeners();
		}
		finally {
			GITHUB.unavailable.remove(prefix);
		}
		Setup failed = this.setups.latest(this.job).orElseThrow();
		assertThat(failed.status()).isEqualTo("FAILED");
		assertThat(failed.checkedAt()).isNull();
		assertThat(this.busy.job(this.job).orElseThrow().busy()).as("nothing waits under a marker").isEmpty();
		return failed;
	}

	/** The waiting stage's operation, keyed by the attempt. */
	private UUID operationOf(Setup setup) {
		return this.jdbc.sql("SELECT id FROM operations.external_operation WHERE operation_key = :key")
			.param("key", JobSetups.OPERATION + ":" + setup.id())
			.query(UUID.class)
			.single();
	}

	/** An {@code ACTIVE} Crew row for a new member, as a signature leaves it. */
	private UUID activeMember() {
		UUID member = TestPeople.student(this.jdbc, "Phạm Văn Hải", true, List.of(TestPeople.REACT), "React",
				LocalDate.parse("2026-12-31"));
		this.jdbc.sql("""
				INSERT INTO job.crew_row (id, job_id, member_account_id, status, signed_at, activated_at, created_at)
				VALUES (:id, :job, :student, 'ACTIVE', :now, :now, :now)
				""").param("id", UUID.randomUUID()).param("job", this.job).param("student", member)
			.param("now", Timestamp.from(this.clock.instant())).update();
		return member;
	}

	/** J6's event, as {@code job} publishes it in M1's cascade, for the Job as it stands now. */
	private void j6() {
		boolean code = this.busy.job(this.job).orElseThrow().code();
		this.setups.transitioned(new JobTransitioned(UUID.randomUUID(), UUID.randomUUID(), this.job, "J6", "SYSTEM",
				"AGREEMENT", "STARTING", 3, 1, Map.of("code", String.valueOf(code))));
	}

	/** Story 23.11a (R6): the Job becomes an Assessment Job, which has one private read-only copy and no Working. */
	private void assessmentJob() {
		this.jdbc.sql("UPDATE job.job SET job_types = ARRAY['ASSESSMENT']::TEXT[] WHERE id = :id")
			.param("id", this.job)
			.update();
	}

	/** J6, the waiting stage and the Customer's self-confirmation, for an Assessment Job. */
	private String assessmentConfirmTheHead() throws Exception {
		j6();
		String head = waitForTheCustomer();
		confirm(head).andExpect(status().isOk());
		return head;
	}

	/** An Assessment Job's whole Setup: its one read-only copy made and read back, so J7 applied. */
	private void assessmentSetUpTheRepository() throws Exception {
		assessmentConfirmTheHead();
		run();
		Setup copying = this.setups.latest(this.job).orElseThrow();
		assertThat(copying.correlationId()).as("a copy run is out").isNotNull();
		GITHUB.copyCommit("lan-dev", this.sourceName, copying.approvedSha(), JobSetups.intermediaryName(this.job),
				copying.defaultBranch());
		GITHUB.completeRun(runId(copying), "success");
		runCompleted(copying);
		assertThat(this.setups.latest(this.job).orElseThrow().status()).as("the Setup is done").isEqualTo("DONE");
	}

	/** Every Setup operation due now runs, in the GitHub-long lane. */
	private void run() {
		this.worker.runDue(Lane.GITHUB_LONG);
	}

	/** The waiting stage: the Source checked and its head read, the Setup waits for the Customer; returns the head. */
	private String waitForTheCustomer() {
		run();
		Setup waiting = this.setups.latest(this.job).orElseThrow();
		assertThat(waiting.status()).isEqualTo("CONFIRMING");
		assertThat(waiting.headSha()).as("the head to confirm").isNotNull();
		return waiting.headSha();
	}

	/** J6, the waiting stage, and the Customer's self-confirmation of the head it read, which starts the copy. */
	private String confirmTheHead() throws Exception {
		j6();
		String head = waitForTheCustomer();
		confirm(head).andExpect(status().isOk());
		return head;
	}

	/** The Customer's self-confirmation of {@code sha} under N-32's current version. */
	private ResultActions confirm(String sha) throws Exception {
		return confirm(this.customer, Role.CUSTOMER, sha, "1.1");
	}

	private ResultActions confirm(UUID account, Role role, String sha, String noticeVersion) throws Exception {
		return this.mvc.perform(post("/api/v1/jobs/" + this.job + "/setup/confirmation").with(as(account, role)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"sha\":\"" + sha + "\",\"noticeVersion\":\"" + noticeVersion + "\"}"));
	}

	/**
	 * What the copy run on the Customer's Actions does, and the stub does not: the confirmed head pushed into both
	 * repositories, and the run concluded {@code success}. Then the Setup reads the run, and both repositories back.
	 */
	private void copyRunSucceeds() throws Exception {
		Setup copying = this.setups.latest(this.job).orElseThrow();
		assertThat(copying.correlationId()).as("a copy run is out").isNotNull();
		for (String name : List.of(JobSetups.intermediaryName(this.job), JobSetups.workingName(this.job))) {
			GITHUB.copyCommit("lan-dev", this.sourceName, copying.approvedSha(), name, copying.defaultBranch());
		}
		GITHUB.completeRun(runId(copying), "success");
		runCompleted(copying);
	}

	/**
	 * Story 24.5a: the Source App delivers the run's {@code workflow_run} as the stub shows it now, signed with its
	 * secret, for the Customer's installation, at {@code POST /webhooks}.
	 */
	private ResultActions delivered(Setup setup) throws Exception {
		return this.mvc.perform(ActionsExchangeSupport.workflowRun(GITHUB, runId(setup), GitHubStub.SOURCE_APP,
				this.installation, SOURCE_SECRET));
	}

	/** The run's completion delivered, which wakes the copy, and the copy's next attempt. */
	private void runCompleted(Setup setup) throws Exception {
		delivered(setup).andExpect(status().isAccepted());
		awaitListeners();
		run();
	}

	/**
	 * The {@value ActionsRunWakes#KEY} Deadlines of the Setup's correlation, each as its status, Job, generation,
	 * whether it is pausable, and its due time while pending.
	 */
	private List<String> timeouts(Setup setup) {
		return this.jdbc.sql("""
				SELECT status, job_id, generation, pausable, due_at FROM timers.deadline
				WHERE kind = :kind AND subject_id = :correlation ORDER BY created_at
				""")
			.param("kind", ActionsRunWakes.KEY)
			.param("correlation", setup.correlationId())
			.query((rs, n) -> rs.getString("status") + " " + rs.getObject("job_id") + " " + rs.getString("generation") + " "
					+ rs.getBoolean("pausable") + " "
					+ (rs.getTimestamp("due_at") == null ? "-" : rs.getTimestamp("due_at").toInstant()))
			.list();
	}

	/** J6, the Customer's self-confirmation, and the copy on their Actions: the Setup is done, and the Invitations start. */
	private void setUpTheRepositories() throws Exception {
		confirmTheHead();
		run();
		copyRunSucceeds();
		assertThat(this.setups.latest(this.job).orElseThrow().status()).as("the Setup is done").isEqualTo("DONE");
	}

	/** The run dispatched last for the Setup. */
	private long runId(Setup setup) {
		Long id = this.runs.correlation(setup.correlationId()).orElseThrow().runId();
		assertThat(id).as("the run was found").isNotNull();
		return id;
	}

	/** The runs dispatched in the Source Repository. */
	private List<Map<String, Object>> sourceRuns() {
		synchronized (GITHUB.runs) {
			return GITHUB.runs.values().stream().filter(run -> this.source.equals(run.get("repository_full_name"))).toList();
		}
	}

	/** The run of the Setup's correlation exchanges its ID token. */
	private ResultActions exchange(Setup setup) throws Exception {
		return this.mvc.perform(ActionsExchangeSupport.exchange(
				ActionsExchangeSupport.idToken(GITHUB, this.clock.instant(), runId(setup), this.sourceId), setup.correlationId()));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> inputs(Map<String, Object> run) {
		return (Map<String, Object>) run.get("inputs");
	}

	/** Every attempt's run fails, until the attempts run out; returns each attempt's correlation. */
	private Set<UUID> runsKeepFailing() throws Exception {
		Set<UUID> correlations = new LinkedHashSet<>();
		for (int attempt = 1; attempt <= JobSetupOperation.POLICY.maxAttempts(); attempt++) {
			run();
			Setup dispatched = this.setups.latest(this.job).orElseThrow();
			assertThat(dispatched.status()).as("attempt " + attempt).isEqualTo("RUNNING");
			correlations.add(dispatched.correlationId());
			GITHUB.completeRun(runId(dispatched), attempt == 1 ? "cancelled" : "failure");
			runCompleted(dispatched);
			this.clock.advance(JobSetupOperation.POLICY.delayAfter(attempt).plusSeconds(1));
		}
		awaitListeners();
		return correlations;
	}

	/** When the copy stage's operation for the Setup's confirmation runs next. */
	private Instant nextAttempt(Setup setup) {
		return this.jdbc.sql("SELECT next_attempt_at FROM operations.external_operation WHERE operation_key = :key")
			.param("key", JobSetups.OPERATION + ":" + setup.confirmationId())
			.query(Timestamp.class)
			.single()
			.toInstant();
	}

	/** The Customer's open {@code repo.confirm_source_clean} to-dos. */
	private long openConfirmTodos() {
		return this.jdbc.sql("""
				SELECT count(*) FROM notification.todo
				WHERE todo_key = 'repo.confirm_source_clean' AND account_id = :customer AND closed_at IS NULL
				""").param("customer", this.customer).query(Long.class).single();
	}

	private long notices(String type) {
		return this.jdbc.sql("SELECT count(*) FROM notification.notification WHERE type = :type")
			.param("type", type)
			.query(Long.class)
			.single();
	}

	private void awaitListeners() {
		org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15))
			.until(() -> this.jdbc.sql("SELECT count(*) FROM platform.event_publication WHERE completion_date IS NULL")
				.query(Long.class)
				.single() == 0);
	}

	private RequestPostProcessor as(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

	private long githubUserId(UUID account) {
		return this.jdbc.sql("SELECT github_user_id FROM identity.account WHERE id = :id").param("id", account)
			.query(Long.class)
			.single();
	}

}
