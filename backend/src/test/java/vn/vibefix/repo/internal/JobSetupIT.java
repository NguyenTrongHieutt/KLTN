package vn.vibefix.repo.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import tools.jackson.databind.JsonNode;
import vn.vibefix.github.ActionsRuns;
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
import vn.vibefix.operations.internal.OperationWorker;
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
 * Customer's Source Repository through their Source App with a grant of the two repositories, its run is polled, and
 * both repositories are read back at exactly the confirmed head; there is no fork, no ruleset, and no copy in vibefix's
 * sandbox. A head that moved after the confirmation (the exchange's {@code sha}) asks the Customer again; any other
 * exchange refusal, the run's Actions quota, or a run that keeps failing blocks the start, and so do the Source checks,
 * until the Customer taps "Thử lại". Then (Story 7.3) the Mentor and the active Crew are invited with read; once each
 * accepted with two-factor authentication on, Verify raises them to write, reads it back, and only then applies J7.
 * J8, J10, a locked account, or a Crew change revokes access, read back (Story 7.7); 30 days after the end the final
 * ZIP is stored and both repositories are deleted. GitHub and git hosting are {@link GitHubStub}, which runs no
 * workflow: a test pushes the confirmed head into both repositories as the copy run would, and completes the run. The
 * run's ID token is signed by {@link ActionsExchangeSupport}'s stand-in issuer; the sandbox jobs run in process.
 */
class JobSetupIT extends IntegrationTest {

	private static final String TUNNEL = "https://exchange.example.test";

	private static final Path DIRECTORY;

	private static final GitHubStub GITHUB;

	private static final AtomicLong REPOSITORY_IDS = new AtomicLong(880000);

	private static final String RUNNER = "vibefix-runner";

	static {
		try {
			DIRECTORY = Files.createTempDirectory("job-setup-");
			GITHUB = new GitHubStub(Files.createDirectories(DIRECTORY.resolve("git")));
			Files.writeString(DIRECTORY.resolve("app-key"), TestKeys.pkcs1());
			// The stand-in for GitHub Actions' OIDC issuer, whose ID tokens a copy run exchanges (Story 23.4).
			ActionsExchangeSupport.issue(GITHUB);
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
	private RepositoryDeletions deletions;

	@Autowired
	private RepoPersonalData personalData;

	@Autowired
	private org.springframework.transaction.PlatformTransactionManager transactions;

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
		registry.add("vibefix.github.platform-app.id", () -> "123456");
		registry.add("vibefix.github.platform-app.installation-id", () -> String.valueOf(GitHubStub.INSTALLATION));
		registry.add("vibefix.github.platform-app.key-file", () -> DIRECTORY.resolve("app-key").toString());
		registry.add("vibefix.github.runner-repository", () -> RUNNER);
		registry.add("vibefix.github.operator-login", () -> "vibefix-operator");
		// Story 23.5: the Source checks read the Source Repository through the Customer's Source App installation.
		registry.add("vibefix.github.source-app.id", () -> "2222");
		registry.add("vibefix.github.source-app.key-file", () -> DIRECTORY.resolve("app-key").toString());
		// Story 23.6b: the copy is dispatched on the Customer's Actions, which reach the exchange through the dev tunnel.
		registry.add("vibefix.github.exchange.public-url", () -> TUNNEL);
		registry.add("vibefix.github.exchange.issuer", () -> GITHUB.apiUrl().toString());
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
		setUpTheRepositories();
		this.worker.runDue(Lane.GITHUB_SHORT);

		assertThat(this.grants.ofJob(this.job)).extracting(JobGrants.Grant::status).containsOnly("INVITED");
		assertThat(this.grants.ofJob(this.job)).hasSize(2).allSatisfy(grant -> assertThat(grant.expiresAt())
			.isEqualTo(this.clock.instant().plus(Duration.ofDays(7))));
		assertThat(notices("repo.github_invitation_sent")).isEqualTo(2);
		assertThat(this.jdbc.sql("SELECT count(*) FROM notification.todo WHERE todo_key = 'repo.accept_github_invitation'")
			.query(Long.class).single()).isEqualTo(2);
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/setup").with(as(this.customer, Role.CUSTOMER)))
			.andExpect(jsonPath("$.steps[4].key").value("INVITE"))
			.andExpect(jsonPath("$.steps[4].state").value("CURRENT"))
			.andExpect(jsonPath("$.people[0].name").value("Đỗ Mạnh Hùng"))
			.andExpect(jsonPath("$.people[1].name").value("@" + crewLogin))
			.andExpect(jsonPath("$.people[1].state").value("INVITED"))
			.andExpect(jsonPath("$.people[1].githubUrl").doesNotExist());
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/setup").with(as(this.crew, Role.STUDENT)))
			.andExpect(jsonPath("$.people[1].name").value("Lê Thảo Vy"))
			.andExpect(jsonPath("$.people[1].githubUrl").value(org.hamcrest.Matchers.endsWith("-working/invitations")))
			.andExpect(jsonPath("$.people[0].githubUrl").doesNotExist());

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
		assertThat(this.busy.job(this.job).orElseThrow().busy()).hasValueSatisfying(
				marker -> assertThat(marker.purpose()).isEqualTo(JobBusy.Purpose.JOB_VERIFY));
		assertThat(this.busy.job(this.job).orElseThrow().state()).as("not Active before the read-back").isEqualTo("STARTING");
		this.worker.runDue(Lane.GITHUB_SHORT);

		assertThat(this.busy.job(this.job).orElseThrow().state()).isEqualTo("ACTIVE");
		assertThat(this.busy.job(this.job).orElseThrow().busy()).isEmpty();
		assertThat(this.grants.ofJob(this.job)).extracting(JobGrants.Grant::status).containsOnly("WRITE");
		assertThat(notices("job.job_started")).as("the Customer, the Mentor, and the Crew").isEqualTo(3);
		assertThat(this.jdbc.sql("SELECT count(*) FROM audit.audit_entry WHERE action = 'repo.grant_raised' AND job_id = :job")
			.param("job", this.job).query(Long.class).single()).isEqualTo(2);
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

		assertThat(differences).anyMatch(difference -> difference.contains("collaborators [mallory] on the Intermediary"))
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

	@Test
	void j8RevokesEveryGrantAndReadsItBackWhileTheAdministratorsHearOfOneGitHubKeepsRefusing() throws Exception {
		AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		String crewLogin = TestPeople.login(this.jdbc, this.crew);
		active();
		GITHUB.unavailable.add("DELETE /repos/" + GitHubStub.ORGANIZATION + "/" + JobSetups.workingName(this.job)
				+ "/collaborators/" + crewLogin);
		this.jdbc.sql("UPDATE job.job SET state = 'CLOSING' WHERE id = :id").param("id", this.job).update();

		this.revocations.transitioned(new JobTransitioned(UUID.randomUUID(), UUID.randomUUID(), this.job, "J8", "USER",
				"ACTIVE", "CLOSING", 6, 1, Map.of()));
		assertThat(this.grants.ofJob(this.job)).extracting(JobGrants.Grant::status).containsOnly("REVOKING");
		this.worker.runDue(Lane.GITHUB_SHORT);

		assertThat(grantStatus(this.mentor)).isEqualTo("REVOKED");
		assertThat(grantStatus(this.crew)).as("GitHub refused; tried again in 30 seconds").isEqualTo("REVOKING");
		assertThat(notices("repo.access_revoked")).isOne();
		this.clock.advance(Revocations.DELAY.plusSeconds(1));
		this.deadlines.runDue();
		assertThat(notices("operations.revocation_delayed")).as("the Administrator, for the Crew member only").isOne();

		GITHUB.unavailable.clear();
		this.worker.runDue(Lane.GITHUB_SHORT);

		assertThat(this.grants.ofJob(this.job)).extracting(JobGrants.Grant::status).containsOnly("REVOKED");
		assertThat(this.grants.ofJob(this.job)).allSatisfy(grant -> assertThat(this.jdbc
			.sql("SELECT revoked_at FROM repo.job_grant WHERE id = :id").param("id", grant.id()).query(Timestamp.class).single())
			.isNotNull());
		assertThat(notices("repo.access_revoked")).isEqualTo(2);
		assertThat(this.jdbc.sql("SELECT count(*) FROM audit.audit_entry WHERE action = 'repo.grant_revoked' AND job_id = :job")
			.param("job", this.job).query(Long.class).single()).as("per person and repository").isEqualTo(2);
		assertThat(sweep()).as("nobody is left on the repositories").isEmpty();
		awaitListeners();
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
		assertThat(sweep()).as("no collaborator or invitation is left behind").isEmpty();
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/setup").with(as(this.customer, Role.CUSTOMER)))
			.andExpect(jsonPath("$.people.length()").value(1))
			.andExpect(jsonPath("$.people[0].name").value("Đỗ Mạnh Hùng"));
		awaitListeners();
	}

	@Test
	void thirtyDaysAfterTheJobEndsTheFinalZipIsStoredForTheCustomerThenBothRepositoriesAreDeleted() throws Exception {
		setUpTheRepositories();
		this.worker.runDue(Lane.GITHUB_SHORT);
		String working = JobSetups.workingName(this.job);
		String intermediary = JobSetups.intermediaryName(this.job);
		this.jdbc.sql("UPDATE job.job SET state = 'CANCELLED' WHERE id = :id").param("id", this.job).update();

		this.revocations.transitioned(new JobTransitioned(UUID.randomUUID(), UUID.randomUUID(), this.job, "J10", "USER",
				"STARTING", "CANCELLED", 4, 1, Map.of()));
		this.worker.runDue(Lane.GITHUB_SHORT);
		assertThat(this.grants.ofJob(this.job)).extracting(JobGrants.Grant::status).containsOnly("REVOKED");
		assertThat(sweep()).as("the ended Job's repositories are still read, and hold nobody").isEmpty();
		this.clock.advance(Duration.ofDays(29));
		this.deadlines.runDue();
		this.worker.runDue(Lane.GITHUB_LONG);
		assertThat(GITHUB.deleted).doesNotContain(working, intermediary);

		this.clock.advance(Duration.ofDays(1).plusMinutes(1));
		this.deadlines.runDue();
		this.worker.runDue(Lane.GITHUB_LONG);

		assertThat(GITHUB.deleted).containsSubsequence(working, intermediary);
		UUID archive = this.jdbc.sql("SELECT archive_file_id FROM repo.job_setup WHERE job_id = :job AND deleted_at IS NOT NULL")
			.param("job", this.job).query(UUID.class).single();
		assertThat(this.jdbc.sql("SELECT purpose || ' ' || account_id || ' ' || kind FROM files.stored_file WHERE id = :id")
			.param("id", archive).query(String.class).single()).isEqualTo("repo.final_zip " + this.customer + " ARCHIVE");
		byte[] zip = this.mvc.perform(get("/api/v1/files/" + archive).with(as(this.customer, Role.CUSTOMER)))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsByteArray();
		try (java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
			java.util.Map<String, String> entries = new java.util.TreeMap<>();
			for (java.util.zip.ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
				entries.put(entry.getName(), new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
			}
			assertThat(entries).containsEntry("README.md", "# Quán Mây\n");
		}
		this.mvc.perform(get("/api/v1/files/" + archive).with(as(this.mentor, Role.MENTOR))).andExpect(status().isNotFound());
		assertThat(sweep()).as("deleted repositories are no longer read").isEmpty();
	}

	@Test
	void deletingTheProductDeletesItsRepositoriesAtOnceWithoutAZipAndAnyStoredZip() throws Exception {
		setUpTheRepositories();
		Setup setup = this.setups.latest(this.job).orElseThrow();
		org.springframework.transaction.support.TransactionTemplate transaction =
				new org.springframework.transaction.support.TransactionTemplate(this.transactions);
		transaction.executeWithoutResult(status -> this.deletions.archived(setup.id(), this.customer, "ban-cuoi.zip",
				"PK".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		UUID archive = this.jdbc.sql("SELECT archive_file_id FROM repo.job_setup WHERE id = :id").param("id", setup.id())
			.query(UUID.class).single();

		transaction.executeWithoutResult(status -> this.personalData.deleteProduct(this.product));
		this.worker.runDue(Lane.GITHUB_LONG);

		assertThat(GITHUB.deleted).containsSubsequence(JobSetups.workingName(this.job), JobSetups.intermediaryName(this.job));
		assertThat(this.jdbc.sql("SELECT count(*) FROM repo.job_setup WHERE job_id = :job").param("job", this.job)
			.query(Long.class).single()).isZero();
		assertThat(this.jdbc.sql("SELECT count(*) FROM repo.source_confirmation WHERE job_id = :job").param("job", this.job)
			.query(Long.class).single()).as("the self-confirmations go with it").isZero();
		assertThat(this.jdbc.sql("SELECT deleted_at FROM files.stored_file WHERE id = :id").param("id", archive)
			.query(Timestamp.class).single()).isNotNull();
		assertThat(this.jdbc.sql("SELECT count(*) FROM audit.audit_entry WHERE action = 'repo.repositories_deleted_with_product'"
				+ " AND job_id = :job").param("job", this.job).query(Long.class).single()).isOne();
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
				vn.vibefix.github.GitHubApps.App.PLATFORM, event, "opened", 7L, List.of(repository), senderId, null, login);
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
			assertThat(this.grants.ofJob(this.job)).extracting(JobGrants.Grant::status).containsOnly("WRITE");

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
			.query(String.class).list()).containsOnly("ứng dụng vibefix phải được cài cho đúng một kho là dự án gốc");
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/setup").with(as(this.customer, Role.CUSTOMER)))
			.andExpect(jsonPath("$.blockedReason").value("SOURCE_APP_WRONG_REPOSITORY"));
		// Story 23.5c: the Setup check records the Product's status, with its to-do.
		assertThat(sourceStatus()).isEqualTo("SOURCE_APP_WRONG_REPOSITORY");
		assertThat(productTodos()).containsExactly("product.install_source_app");
	}

	/**
	 * Story 23.5c: the daily drift read records the Product's status, so an uninstalled Source App opens
	 * {@code product.install_source_app} and tells the Customer once, however often the poll reads it; the file changed
	 * afterwards moves the to-do to {@code product.add_workflow} with one more notification.
	 */
	@Test
	void theDriftReadRecordsTheStatusAndOpensItsToDoOnce() throws Exception {
		setUpTheRepositories();
		this.jdbc.sql("UPDATE job.job SET state = 'ACTIVE' WHERE id = :id").param("id", this.job).update();
		assertThat(sourceStatus()).as("the Setup check found it ready").isEqualTo("READY");
		assertThat(this.jdbc.sql("SELECT workflow_blob FROM product.product WHERE id = :id").param("id", this.product)
			.query(String.class).single()).as("with the blob it matched").matches("[0-9a-f]{40}");

		GITHUB.mergeInstallations.remove(this.installation);
		GITHUB.repositoryInstallations.values().remove(this.installation);
		this.drift.poll();
		this.clock.advance(Duration.ofDays(1));
		this.drift.poll();

		assertThat(sourceStatus()).isEqualTo("SOURCE_APP_MISSING");
		assertThat(productTodos()).containsExactly("product.install_source_app");
		assertThat(workflowCheckFailures()).as("once, to the Product's Customer").containsExactly(this.customer);

		long owner = githubUserId(this.customer);
		long repository = this.jdbc.sql("SELECT repository_github_id FROM product.product WHERE id = :id")
			.param("id", this.product).query(Long.class).single();
		GITHUB.installSource("lan-dev", this.source.substring(this.source.indexOf('/') + 1), repository, owner);
		GITHUB.sourceFiles.put(this.source + ":.github/workflows/vibefix.yml",
				GitHubStub.published(GitHubStub.CUSTOMER_WORKFLOW) + "# changed\n");
		this.clock.advance(Duration.ofDays(1));
		this.drift.poll();

		assertThat(sourceStatus()).isEqualTo("WORKFLOW_MISMATCH");
		assertThat(productTodos()).containsExactly("product.add_workflow");
		assertThat(workflowCheckFailures()).hasSize(2);
		awaitListeners();
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

	@Test
	void theDailyPollWarnsOncePerChangeAndARenameWaitsForTheCustomersReconfirmation() throws Exception {
		setUpTheRepositories();
		this.jdbc.sql("UPDATE job.job SET state = 'ACTIVE' WHERE id = :id").param("id", this.job).update();
		String name = this.source.substring("lan-dev/".length());
		long id = this.jdbc.sql("SELECT repository_github_id FROM product.product WHERE id = :id").param("id", this.product)
			.query(Long.class).single();
		// What the Setup read is left out below; it read the Source through the Source App too (Story 23.6b).
		int setupRequests = GITHUB.seen.size();
		this.drift.poll();
		assertThat(this.drift.warnings(this.job)).as("nothing changed since the copy").isEmpty();

		String pushed = GITHUB.push("lan-dev", name, "main", "app.js", "console.log('Lovable')\n");
		this.drift.poll();
		this.drift.poll();

		assertThat(this.drift.warnings(this.job)).singleElement().satisfies(warning -> {
			assertThat(warning.kind()).isEqualTo(SourceDrift.Kind.COMMITS);
			assertThat(warning.commits()).as("stored as evidence").containsExactly(pushed);
		});
		assertThat(notices("repo.drift_detected")).as("the Customer and the Mentor, once").isEqualTo(2);

		GITHUB.rename(id, "lan-dev", name + "-v2");
		this.clock.advance(Duration.ofDays(1));
		this.drift.poll();
		assertThat(this.drift.warnings(this.job)).extracting(SourceDrift.Warning::kind)
			.containsExactly(SourceDrift.Kind.COMMITS, SourceDrift.Kind.RENAMED);
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/drift").with(as(this.customer, Role.CUSTOMER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.reconfirmNeeded").value(true))
			.andExpect(jsonPath("$.warnings[1].fullName").value("lan-dev/" + name + "-v2"));
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/drift").with(as(this.mentor, Role.MENTOR)))
			.andExpect(jsonPath("$.reconfirmNeeded").value(false));
		this.mvc.perform(post("/api/v1/jobs/" + this.job + "/source/reconfirm").with(as(this.customer, Role.CUSTOMER))
			.with(csrf()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.reconfirmNeeded").value(false));
		assertThat(this.jdbc.sql("SELECT repository_full_name FROM product.product WHERE id = :id").param("id", this.product)
			.query(String.class).single()).as("the new name is the Final Merge target now").isEqualTo("lan-dev/" + name + "-v2");
		assertThat(this.jdbc.sql("SELECT count(*) FROM notification.todo WHERE todo_key = 'repo.reconfirm_source' AND closed_at IS NULL")
			.query(Long.class).single()).isZero();

		GITHUB.archive(id);
		this.clock.advance(Duration.ofDays(1));
		this.drift.poll();
		assertThat(this.drift.warnings(this.job)).extracting(SourceDrift.Warning::kind)
			.containsExactly(SourceDrift.Kind.COMMITS, SourceDrift.Kind.RENAMED, SourceDrift.Kind.ARCHIVED);
		// R7 (Story 23.5b): the Source App reads a private repository, so making it private is no drift.
		GITHUB.publish("lan-dev", name + "-v2", id, githubUserId(this.customer), false, false);
		this.clock.advance(Duration.ofDays(1));
		this.drift.poll();
		assertThat(this.drift.warnings(this.job)).extracting(SourceDrift.Warning::kind).as("made private warns nothing")
			.containsExactly(SourceDrift.Kind.COMMITS, SourceDrift.Kind.RENAMED, SourceDrift.Kind.ARCHIVED);

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
	 * Story 23.5b (R7, EXPERIENCE.md "Drift detected"): the poll reads through the Source App, so the published
	 * {@code vibefix.yml} changed or removed, the Source Repository no longer selected, and the App uninstalled each warn
	 * once with a notification, and ask no re-confirmation. GitHub not answering warns nothing, and the next poll reads
	 * again.
	 */
	@Test
	void theDailyPollWarnsOnceWhenTheWorkflowFileTheSelectionOrTheSourceAppGoes() throws Exception {
		setUpTheRepositories();
		this.jdbc.sql("UPDATE job.job SET state = 'ACTIVE' WHERE id = :id").param("id", this.job).update();
		long id = this.jdbc.sql("SELECT repository_github_id FROM product.product WHERE id = :id").param("id", this.product)
			.query(Long.class).single();
		String workflow = this.source + ":.github/workflows/vibefix.yml";
		String published = GitHubStub.published(GitHubStub.CUSTOMER_WORKFLOW);

		GITHUB.unavailable.add("GET /repos/" + this.source + "/compare/");
		try {
			this.drift.poll();
		}
		finally {
			GITHUB.unavailable.clear();
		}
		assertThat(this.drift.warnings(this.job)).as("GitHub down: nothing is known, so nothing is warned").isEmpty();
		this.drift.poll();
		assertThat(this.drift.warnings(this.job)).as("read again: nothing changed").isEmpty();

		GITHUB.sourceFiles.put(workflow, published + "# changed\n");
		this.clock.advance(Duration.ofDays(1));
		this.drift.poll();
		this.drift.poll();
		GITHUB.sourceFiles.remove(workflow);
		this.clock.advance(Duration.ofDays(1));
		this.drift.poll();
		assertThat(this.drift.warnings(this.job)).as("changed, then removed: one warning").singleElement()
			.satisfies(warning -> {
				assertThat(warning.kind()).isEqualTo(SourceDrift.Kind.WORKFLOW_CHANGED);
				assertThat(warning.fullName()).isEqualTo(this.source);
			});
		assertThat(notices("repo.drift_detected")).as("the Customer and the Mentor, once").isEqualTo(2);
		GITHUB.sourceFiles.put(workflow, published);

		long owner = githubUserId(this.customer);
		GITHUB.mergeInstallations.get(this.installation).deselect(id);
		this.clock.advance(Duration.ofDays(1));
		this.drift.poll();
		this.drift.poll();
		assertThat(this.drift.warnings(this.job)).extracting(SourceDrift.Warning::kind)
			.containsExactly(SourceDrift.Kind.WORKFLOW_CHANGED, SourceDrift.Kind.GONE);
		GITHUB.mergeInstallations.get(this.installation).repository(id, this.source, owner);

		GITHUB.mergeInstallations.remove(this.installation);
		this.clock.advance(Duration.ofDays(1));
		this.drift.poll();
		this.drift.poll();
		assertThat(this.drift.warnings(this.job)).extracting(SourceDrift.Warning::kind)
			.containsExactly(SourceDrift.Kind.WORKFLOW_CHANGED, SourceDrift.Kind.GONE, SourceDrift.Kind.APP_UNINSTALLED);
		assertThat(notices("repo.drift_detected")).as("once per new state, to the Customer and the Mentor").isEqualTo(6);
		assertThat(this.jdbc.sql("SELECT count(*) FROM notification.todo WHERE todo_key = 'repo.reconfirm_source'")
			.query(Long.class).single()).as("nothing to re-confirm").isZero();
		assertThat(this.jdbc.sql("SELECT count(*) FROM audit.audit_entry WHERE action = 'repo.drift_detected'")
			.query(Long.class).single()).isEqualTo(3);
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/drift").with(as(this.customer, Role.CUSTOMER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.warnings[1].kind").value("GONE"))
			.andExpect(jsonPath("$.warnings[1].fullName").value(this.source))
			.andExpect(jsonPath("$.warnings[2].kind").value("APP_UNINSTALLED"))
			.andExpect(jsonPath("$.reconfirmNeeded").value(false));
		awaitListeners();
	}

	/**
	 * Story 23.5b: a Source Repository transferred to an account where the Source App is installed is still found by its
	 * old name (GitHub's 301 to its ID), bound to that account's installation, and warned as transferred; the Customer
	 * re-confirms it.
	 */
	@Test
	void aSourceTransferredToAnAccountWithTheAppIsFoundByItsOldNameAndReconfirmed() throws Exception {
		setUpTheRepositories();
		this.jdbc.sql("UPDATE job.job SET state = 'ACTIVE' WHERE id = :id").param("id", this.job).update();
		long id = this.jdbc.sql("SELECT repository_github_id FROM product.product WHERE id = :id").param("id", this.product)
			.query(Long.class).single();
		String name = this.source.substring("lan-dev/".length());
		long owner = githubUserId(this.customer);

		GITHUB.mergeInstallations.get(this.installation).deselect(id);
		GITHUB.rename(id, "lan-shop", name);
		long moved = GITHUB.installSource("lan-shop", name, id, owner);
		this.drift.poll();

		assertThat(this.drift.warnings(this.job)).singleElement().satisfies(warning -> {
			assertThat(warning.kind()).isEqualTo(SourceDrift.Kind.TRANSFERRED);
			assertThat(warning.fullName()).isEqualTo("lan-shop/" + name);
		});
		assertThat(GITHUB.seen).as("the old name's 301 is followed to the repository's ID")
			.anyMatch(request -> request.path().equals("/repositories/" + id + "/installation"));
		assertThat(this.jdbc.sql("SELECT installation_id FROM product.source_app_binding WHERE product_id = :id AND status = 'BOUND'")
			.param("id", this.product).query(Long.class).single()).as("bound to the new account's installation").isEqualTo(moved);
		this.mvc.perform(post("/api/v1/jobs/" + this.job + "/source/reconfirm").with(as(this.customer, Role.CUSTOMER))
			.with(csrf()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.reconfirmNeeded").value(false));
		assertThat(this.jdbc.sql("SELECT repository_full_name FROM product.product WHERE id = :id").param("id", this.product)
			.query(String.class).single()).isEqualTo("lan-shop/" + name);
		awaitListeners();
	}

	/**
	 * Story 23.5b: a commit that changes {@code vibefix.yml} warns twice over, as the workflow file changed and as new
	 * commits carrying its SHA: a workflow that differs never hides the other changes.
	 */
	@Test
	void aCommitThatChangesTheWorkflowFileWarnsTheWorkflowAndTheCommits() throws Exception {
		setUpTheRepositories();
		this.jdbc.sql("UPDATE job.job SET state = 'ACTIVE' WHERE id = :id").param("id", this.job).update();
		String name = this.source.substring("lan-dev/".length());
		// From now on the file is served from the repository's own history, where the push changes it.
		GITHUB.sourceFiles.remove(this.source + ":.github/workflows/vibefix.yml");
		String pushed = GITHUB.push("lan-dev", name, "main", ".github/workflows/vibefix.yml",
				GitHubStub.published(GitHubStub.CUSTOMER_WORKFLOW) + "# changed\n");

		this.drift.poll();
		this.drift.poll();

		assertThat(this.drift.warnings(this.job)).extracting(SourceDrift.Warning::kind)
			.containsExactlyInAnyOrder(SourceDrift.Kind.WORKFLOW_CHANGED, SourceDrift.Kind.COMMITS);
		assertThat(this.drift.warnings(this.job)).filteredOn(warning -> warning.kind() == SourceDrift.Kind.COMMITS)
			.singleElement()
			.satisfies(warning -> assertThat(warning.commits()).as("stored as evidence").containsExactly(pushed));
		assertThat(notices("repo.drift_detected")).as("each, once, to the Customer and the Mentor").isEqualTo(4);
		awaitListeners();
	}

	/**
	 * Story 23.5b: one Job whose read fails unexpectedly (here GitHub lists its Source Repository under a name with no
	 * owner) is logged and skipped; the poll still reads the next Job and warns of its change the same day.
	 */
	@Test
	void oneJobsFailedReadDoesNotStopTheNextJobsWarning() throws Exception {
		setUpTheRepositories();
		this.jdbc.sql("UPDATE job.job SET state = 'ACTIVE' WHERE id = :id").param("id", this.job).update();
		String pushed = GITHUB.push("lan-dev", this.source.substring("lan-dev/".length()), "main", "app.js",
				"console.log('Lovable')\n");

		long owner = githubUserId(this.customer);
		long repository = REPOSITORY_IDS.incrementAndGet();
		String name = "lan-dev/so-dat-ban-" + repository;
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
		// Its ID sorts first, so the poll meets its failure before this Job.
		UUID failing = UUID.fromString("00000000-0000-0000-0000-000000000001");
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

		this.drift.poll();

		assertThat(this.drift.warnings(failing)).isEmpty();
		assertThat(this.drift.warnings(this.job)).singleElement().satisfies(warning -> {
			assertThat(warning.kind()).isEqualTo(SourceDrift.Kind.COMMITS);
			assertThat(warning.commits()).containsExactly(pushed);
		});
		awaitListeners();
	}

	@Test
	void anOrganizationMemberDriftsAndHoldsVerifyUntilTheyLeaveTheOrganization() throws Exception {
		AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		String mentorLogin = TestPeople.login(this.jdbc, this.mentor);
		String crewLogin = TestPeople.login(this.jdbc, this.crew);
		GITHUB.members.add(crewLogin);
		setUpTheRepositories();
		this.worker.runDue(Lane.GITHUB_SHORT);

		assertThat(this.grants.ofJob(this.job)).extracting(JobGrants.Grant::status).containsExactly("INVITED", "MEMBER_DRIFT");
		assertThat(notices("repo.member_drift")).isOne();
		GITHUB.accept(mentorLogin);
		this.checks.check(this.job);
		assertThat(this.busy.job(this.job).orElseThrow().busy()).as("a drifted grant holds Verify").isEmpty();

		GITHUB.members.remove(crewLogin);
		this.checks.check(this.job);
		this.worker.runDue(Lane.GITHUB_SHORT);

		assertThat(this.grants.ofJob(this.job)).extracting(JobGrants.Grant::status).containsExactly("ACCEPTED", "INVITED");
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

		assertThat(this.grants.ofJob(this.job)).allSatisfy(grant -> {
			assertThat(grant.status()).isEqualTo("INVITED");
			assertThat(grant.renewals()).isOne();
		});
		assertThat(notices("repo.github_invitation_sent")).as("each renewal is told").isEqualTo(4);
		awaitListeners();
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
				""").param("setup", confirmed.id()).query(String.class).list()).containsExactly(this.customer + " " + head + " 1.0");
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

		GITHUB.startRun(correlation.runId());
		this.clock.advance(JobSetupOperation.RUN_POLL.plusSeconds(1));
		run();
		assertThat(this.setups.latest(this.job).orElseThrow().runStatus()).isEqualTo("IN_PROGRESS");
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/setup").with(as(this.customer, Role.CUSTOMER)))
			.andExpect(jsonPath("$.status").value("RUNNING"))
			.andExpect(jsonPath("$.run.state").value("IN_PROGRESS"));

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
		assertThat(this.grants.ofJob(this.job)).as("the Invitations start").hasSize(2);
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

		confirm(this.mentor, Role.MENTOR, head, "1.0").andExpect(status().isNotFound());
		confirm(this.crew, Role.STUDENT, head, "1.0").andExpect(status().isNotFound());
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

		exchange(dispatched).andExpect(status().isForbidden()).andExpect(jsonPath("$.reason").value("SHA"));
		GITHUB.completeRun(runId(dispatched), "failure");
		this.clock.advance(JobSetupOperation.RUN_POLL.plusSeconds(1));
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
		this.clock.advance(JobSetupOperation.RUN_POLL.plusSeconds(1));
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
		this.clock.advance(JobSetupOperation.RUN_POLL.plusSeconds(1));
		run();

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
	 * exchange is refused, the run is cancelled, and another is dispatched. It is read every 10 s for 2 minutes, then
	 * every 30 s.
	 */
	@Test
	void aRunThatDoesNotCompleteWithinItsTimeoutIsClosedCancelledAndDispatchedAgain() throws Exception {
		confirmTheHead();
		run();
		Setup first = this.setups.latest(this.job).orElseThrow();
		long firstRun = runId(first);
		GITHUB.startRun(firstRun);
		this.clock.advance(JobSetupOperation.RUN_POLL.plusSeconds(1));
		run();
		assertThat(this.setups.latest(this.job).orElseThrow().runStatus()).isEqualTo("IN_PROGRESS");
		assertThat(nextAttempt(first)).as("every 10 s at first").isEqualTo(this.clock.instant().plus(JobSetupOperation.RUN_POLL));
		this.clock.advance(JobSetupOperation.RUN_POLL_FAST);
		run();
		assertThat(nextAttempt(first)).as("then every 30 s")
			.isEqualTo(this.clock.instant().plus(JobSetupOperation.RUN_POLL_LATER));

		this.clock.advance(ActionsRuns.RUN_TIMEOUT);
		run();

		Setup late = this.setups.latest(this.job).orElseThrow();
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
		assertThat(this.setups.latest(this.job).orElseThrow().correlationId()).isNotEqualTo(first.correlationId());
		copyRunSucceeds();

		assertThat(this.setups.latest(this.job).orElseThrow().status()).isEqualTo("DONE");
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
		this.clock.advance(JobSetupOperation.RUN_POLL.plusSeconds(1));
		run();

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
			.isEqualTo(Map.of("jobId", this.job.toString(), "headSha", head, "noticeVersion", "1.0", "at",
					this.clock.instant().toString()));
		assertThat(this.personalData.export(this.mentor).map(PersonalDataSection::data).orElse(Map.of()))
			.doesNotContainKey("sourceSelfConfirmations");

		new org.springframework.transaction.support.TransactionTemplate(this.transactions)
			.executeWithoutResult(status -> this.personalData.eraseAccount(this.customer));

		assertThat(this.jdbc.sql("""
				SELECT coalesce(account_id::text, 'none') || ' ' || head_sha || ' ' || notice_version FROM repo.source_confirmation
				WHERE job_id = :job
				""").param("job", this.job).query(String.class).list()).containsExactly("none " + head + " 1.0");
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

	/** J6's event, as {@code job} publishes it in M1's cascade. */
	private void j6() {
		this.setups.transitioned(new JobTransitioned(UUID.randomUUID(), UUID.randomUUID(), this.job, "J6", "SYSTEM",
				"AGREEMENT", "STARTING", 3, 1, Map.of("code", "true")));
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
		return confirm(this.customer, Role.CUSTOMER, sha, "1.0");
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
		this.clock.advance(JobSetupOperation.RUN_POLL.plusSeconds(1));
		run();
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
	private Set<UUID> runsKeepFailing() {
		Set<UUID> correlations = new LinkedHashSet<>();
		for (int attempt = 1; attempt <= JobSetupOperation.POLICY.maxAttempts(); attempt++) {
			run();
			Setup dispatched = this.setups.latest(this.job).orElseThrow();
			assertThat(dispatched.status()).as("attempt " + attempt).isEqualTo("RUNNING");
			correlations.add(dispatched.correlationId());
			GITHUB.completeRun(runId(dispatched), attempt == 1 ? "cancelled" : "failure");
			this.clock.advance(JobSetupOperation.RUN_POLL.plusSeconds(1));
			run();
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
