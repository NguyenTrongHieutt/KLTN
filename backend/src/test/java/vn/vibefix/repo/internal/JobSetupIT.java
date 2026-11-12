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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import vn.vibefix.github.internal.GitHubStub;
import vn.vibefix.github.internal.TestKeys;
import vn.vibefix.identity.AccountLocked;
import vn.vibefix.identity.internal.CustomerTokens;
import vn.vibefix.identity.internal.AdministratorTestSupport;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.support.TestPeople;
import vn.vibefix.timers.internal.DeadlinePoller;
import vn.vibefix.job.CrewMemberRemoved;
import vn.vibefix.job.JobBusy;
import vn.vibefix.job.JobTransitioned;
import vn.vibefix.operations.Lane;
import vn.vibefix.operations.internal.OperationWorker;
import vn.vibefix.repo.internal.JobSetups.Setup;
import vn.vibefix.support.IntegrationTest;

/**
 * Story 7.2 (FR-18, AD-5, AD-11, AD-28): J6 of a Code Job sets up its repositories under the busy marker.
 * The Source Repository is read again with the Customer's token and its default branch's head through the
 * GitHub API (vibefix scans no code, Story 23.1). Story 23.3 (D1, D5): once the organization and the runner read
 * back clean, the Intermediary and the Working Repository are two private copies of exactly that commit, each
 * read back (private, features and forking off, Actions disabled) before anything is pushed; there is no fork
 * and no ruleset. A private Source Repository or lost admin rights block the start until the Customer fixes it
 * and taps "Thử lại". Then (Story 7.3)
 * the Mentor and the active Crew are invited with read; once each accepted with two-factor authentication
 * on, Verify raises them to write, reads it back, and only then applies J7. J8, J10, a locked account, or a
 * Crew change revokes access, read back (Story 7.7); 30 days after the end the final ZIP is stored and both
 * repositories are deleted. GitHub and git hosting are {@link GitHubStub}; the sandbox jobs run in process.
 */
class JobSetupIT extends IntegrationTest {

	private static final Path DIRECTORY;

	private static final GitHubStub GITHUB;

	private static final AtomicLong REPOSITORY_IDS = new AtomicLong(880000);

	private static final String RUNNER = "vibefix-runner";

	static {
		try {
			DIRECTORY = Files.createTempDirectory("job-setup-");
			GITHUB = new GitHubStub(Files.createDirectories(DIRECTORY.resolve("git")));
			Files.writeString(DIRECTORY.resolve("app-key"), TestKeys.pkcs1());
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	@Autowired
	private JdbcClient jdbc;

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
	}

	@AfterAll
	static void stop() {
		GITHUB.close();
	}

	@BeforeEach
	void aCodeJobStarting() throws Exception {
		this.customer = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		// A fake token, built at run time; the stub accepts any. The contributors check still reads with it (23.5b).
		CustomerTokens.keep(this.context, this.customer, String.join("", "gh", "o_", "x".repeat(36)));
		this.mentor = TestPeople.mentor(this.jdbc, "Đỗ Mạnh Hùng", List.of(TestPeople.REACT),
				"React");
		this.crew = TestPeople.student(this.jdbc, "Lê Thảo Vy", true, List.of(TestPeople.REACT), "React",
				LocalDate.parse("2026-12-31"));
		long repository = REPOSITORY_IDS.incrementAndGet();
		String name = "so-dat-ban-" + repository;
		this.source = "lan-dev/" + name;
		GITHUB.source("lan-dev", name);
		long owner = CustomerTokens.githubUserId(this.jdbc, this.customer);
		GITHUB.publish("lan-dev", name, repository, owner, true, false);
		// The Customer's Source App on exactly the Source Repository, and the published vibefix.yml on its default branch.
		this.installation = GITHUB.installSource("lan-dev", name, repository, owner);
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
		// AD-46: the runner, private with only its published workflow, the only repository whose Actions run.
		GITHUB.runner(RUNNER);
	}

	@Test
	void everyoneAcceptsWithTwoFactorOnThenWriteIsReadBackAndOnlyThenJ7Applies() throws Exception {
		String mentorLogin = TestPeople.login(this.jdbc, this.mentor);
		String crewLogin = TestPeople.login(this.jdbc, this.crew);
		j6();
		run();
		this.worker.runDue(Lane.GITHUB_SHORT);

		assertThat(this.grants.ofJob(this.job)).extracting(JobGrants.Grant::status).containsOnly("INVITED");
		assertThat(this.grants.ofJob(this.job)).hasSize(2).allSatisfy(grant -> assertThat(grant.expiresAt())
			.isEqualTo(this.clock.instant().plus(Duration.ofDays(7))));
		assertThat(notices("repo.github_invitation_sent")).isEqualTo(2);
		assertThat(this.jdbc.sql("SELECT count(*) FROM notification.todo WHERE todo_key = 'repo.accept_github_invitation'")
			.query(Long.class).single()).isEqualTo(2);
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/setup").with(as(this.customer, Role.CUSTOMER)))
			.andExpect(jsonPath("$.steps[3].state").value("CURRENT"))
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
		j6();
		run();
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
		j6();
		run();
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
		j6();
		run();
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
		j6();
		run();
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
		j6();
		run();
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
		j6();
		run();
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
		j6();
		run();
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
		j6();
		run();
		String intermediary = JobSetups.intermediaryName(this.job);
		String working = JobSetups.workingName(this.job);
		long intermediaryId = GITHUB.organizationRepositoryId(intermediary);
		long workingId = GITHUB.organizationRepositoryId(working);
		long crewId = CustomerTokens.githubUserId(this.jdbc, this.crew);
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
		j6();
		run();
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
		j6();
		run();
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

	/** AD-12, AD-46: the gate runs before anything is created; a difference waits 15 minutes and looks again. */
	@Test
	void aRepositorySelectionDifferenceHoldsNewSetupsBeforeAnythingIsCreated() throws Exception {
		GITHUB.repositorySelection = "selected";
		j6();
		run();

		Setup waiting = this.setups.latest(this.job).orElseThrow();
		assertThat(waiting.status()).as("waiting, not failed").isEqualTo("RUNNING");
		assertThat(waiting.checkedAt()).isNotNull();
		assertThat(waiting.intermediaryAt()).isNull();
		assertThat(GITHUB.organizationRepositoryId(JobSetups.intermediaryName(this.job))).as("nothing was created").isNull();
		assertThat(GITHUB.seen).noneMatch(request -> request.method().equals("POST")
				&& request.path().equals("/orgs/" + GitHubStub.ORGANIZATION + "/repos")
				&& request.body().path("name").asString("").equals(JobSetups.intermediaryName(this.job)));
		GITHUB.repositorySelection = "all";
		this.clock.advance(Duration.ofMinutes(16));
		this.worker.runDue(Lane.GITHUB_LONG);

		assertThat(this.setups.latest(this.job).orElseThrow().status()).isEqualTo("DONE");
	}

	/** AD-46, deferred-work 634: a runner that drifts holds new setups before anything is created, until it is fixed. */
	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.ValueSource(strings = { "public", "another workflow", "a collaborator",
			"an invitation", "Actions for all" })
	void aRunnerThatDriftsHoldsNewSetupsUntilItIsFixed(String drift) throws Exception {
		switch (drift) {
			case "public" -> GITHUB.setting(RUNNER, "private", false);
			case "another workflow" -> GITHUB.push(GitHubStub.ORGANIZATION, RUNNER, "main", ".github/workflows/ci.yml",
					"on: push\n");
			case "a collaborator" -> GITHUB.addCollaborator(RUNNER, "mallory", "write");
			case "an invitation" -> GITHUB.addInvitation(RUNNER, "mallory", "read");
			default -> GITHUB.actionsEnabled = "all";
		}
		try {
			j6();
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
		this.worker.runDue(Lane.GITHUB_LONG);

		assertThat(this.setups.latest(this.job).orElseThrow().status()).isEqualTo("DONE");
		awaitListeners();
	}

	/** D1: a repository whose settings read back wrong receives no code; the attempt is retried. */
	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.ValueSource(strings = { "intermediary", "working" })
	void aRepositoryThatReadsBackPublicReceivesNoCodeUntilItIsPrivate(String which) throws Exception {
		boolean working = which.equals("working");
		String name = working ? JobSetups.workingName(this.job) : JobSetups.intermediaryName(this.job);
		long id = GITHUB.platformRepository(name, "lan-dev", this.source.substring("lan-dev/".length()));
		GITHUB.setting(name, "private", false);
		j6();
		run();

		Setup waiting = this.setups.latest(this.job).orElseThrow();
		assertThat(waiting.status()).isEqualTo("RUNNING");
		assertThat(working ? waiting.workingId() : waiting.intermediaryId()).as("the earlier repository, found by name")
			.isEqualTo(id);
		if (working) {
			assertThat(waiting.intermediaryAt()).as("the Intermediary was copied").isNotNull();
			assertThat(waiting.workingAt()).as("the Working Repository was not").isNull();
		}
		else {
			assertThat(waiting.intermediaryAt()).as("nothing was copied").isNull();
		}
		assertThat(this.jdbc.sql("SELECT status || ':' || error_code FROM operations.external_operation WHERE type = 'repo.job_setup'")
			.query(String.class).single()).isEqualTo("PENDING:REPOSITORY_SETTINGS_DIFFER");
		assertThat(GITHUB.seen).as("no token that could push was minted for it")
			.noneMatch(request -> request.path().endsWith("/access_tokens")
					&& request.body().path("repository_ids").toString().equals("[" + id + "]")
					&& request.body().path("permissions").path("contents").asString("").equals("write"));

		GITHUB.setting(name, "private", true);
		this.clock.advance(JobSetupOperation.POLICY.delayAfter(1).plusSeconds(1));
		this.worker.runDue(Lane.GITHUB_LONG);

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
		j6();
		run();
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
		j6();
		run();
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
		assertThat(this.jdbc.sql("SELECT deleted_at FROM files.stored_file WHERE id = :id").param("id", archive)
			.query(Timestamp.class).single()).isNotNull();
		assertThat(this.jdbc.sql("SELECT count(*) FROM audit.audit_entry WHERE action = 'repo.repositories_deleted_with_product'"
				+ " AND job_id = :job").param("job", this.job).query(Long.class).single()).isOne();
	}

	/** Setup, the invitations, both acceptances, and Verify: an Active Job whose Mentor and Crew hold write. */
	private void active() {
		j6();
		run();
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
		long crewId = CustomerTokens.githubUserId(this.jdbc, this.crew);
		String oldLogin = TestPeople.login(this.jdbc, this.crew);
		GITHUB.logins.put(crewId, "thao-vy-moi");
		try {
			j6();
			run();
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
		j6();
		run();
		this.worker.runDue(Lane.GITHUB_SHORT);
		this.jdbc.sql("UPDATE repo.job_grant SET github_user_id = NULL WHERE job_id = :job").param("job", this.job).update();
		this.jdbc.sql("UPDATE repo.job_grant SET github_login = 'someone-else' WHERE account_id = :mentor")
			.param("mentor", this.mentor).update();

		GITHUB.accept(TestPeople.login(this.jdbc, this.crew));
		this.checks.check(this.job);

		assertThat(crewGrant().githubUserId()).isEqualTo(CustomerTokens.githubUserId(this.jdbc, this.crew));
		assertThat(crewGrant().status()).isEqualTo("ACCEPTED");
		JobGrants.Grant mentor = this.grants.ofJob(this.job).stream().filter(grant -> grant.accountId().equals(this.mentor))
			.findFirst().orElseThrow();
		assertThat(mentor.githubUserId()).as("a login that no longer matches is not trusted").isNull();
	}

	/** A rename after the invitation and before its acceptance: acceptance, two-factor, and the raise go by ID. */
	@Test
	void aGranteeWhoRenamesAfterTheInvitationIsAcceptedCheckedAndRaisedByTheirId() throws Exception {
		long crewId = CustomerTokens.githubUserId(this.jdbc, this.crew);
		try {
			j6();
			run();
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
		long crewId = CustomerTokens.githubUserId(this.jdbc, this.crew);
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
		long haiId = CustomerTokens.githubUserId(this.jdbc, hai);
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
		long crewId = CustomerTokens.githubUserId(this.jdbc, this.crew);
		String stored = TestPeople.login(this.jdbc, this.crew);
		GITHUB.goneUsers.add(crewId);
		try {
			j6();
			run();
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

	/**
	 * Story 23.5: the Customer uninstalled the Source App, so Setup blocks on it and so does a re-confirmation. The
	 * Customer's kept linking token plays no part: none of the Source checks reads with it.
	 */
	@Test
	void anUninstalledSourceAppBlocksSetupAndTheReconfirmation() throws Exception {
		this.jdbc.sql("DELETE FROM identity.customer_token WHERE account_id = :id").param("id", this.customer).update();
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
	}

	private JobGrants.Grant crewGrant() {
		return this.grants.ofJob(this.job).stream().filter(grant -> grant.accountId().equals(this.crew)).findFirst()
			.orElseThrow();
	}

	@Test
	void theDailyPollWarnsOncePerChangeAndARenameWaitsForTheCustomersReconfirmation() throws Exception {
		j6();
		run();
		this.jdbc.sql("UPDATE job.job SET state = 'ACTIVE' WHERE id = :id").param("id", this.job).update();
		String name = this.source.substring("lan-dev/".length());
		long id = this.jdbc.sql("SELECT repository_github_id FROM product.product WHERE id = :id").param("id", this.product)
			.query(Long.class).single();
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
		GITHUB.publish("lan-dev", name + "-v2", id, CustomerTokens.githubUserId(this.jdbc, this.customer), false, false);
		this.drift.poll();
		assertThat(this.drift.warnings(this.job)).extracting(SourceDrift.Warning::kind).contains(SourceDrift.Kind.ARCHIVED,
				SourceDrift.Kind.DELETED_OR_PRIVATE);
		awaitListeners();
	}

	@Test
	void anOrganizationMemberDriftsAndHoldsVerifyUntilTheyLeaveTheOrganization() throws Exception {
		AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		String mentorLogin = TestPeople.login(this.jdbc, this.mentor);
		String crewLogin = TestPeople.login(this.jdbc, this.crew);
		GITHUB.members.add(crewLogin);
		j6();
		run();
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
		j6();
		run();
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

	@Test
	void theSourcesHeadIsCopiedIntoTwoPrivateRepositoriesUnderTheBusyMarker() throws Exception {
		String head = GITHUB.head("lan-dev", this.source.substring("lan-dev/".length()), "main");

		j6();
		assertThat(this.busy.job(this.job).orElseThrow().busy()).hasValueSatisfying(
				marker -> assertThat(marker.purpose()).isEqualTo(JobBusy.Purpose.JOB_SETUP));
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/setup").with(as(this.customer, Role.CUSTOMER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("RUNNING"))
			.andExpect(jsonPath("$.steps[0].state").value("CURRENT"))
			.andExpect(jsonPath("$.steps[1].state").value("PENDING"));
		run();

		Setup done = this.setups.latest(this.job).orElseThrow();
		assertThat(done.status()).isEqualTo("DONE");
		assertThat(done.approvedSha()).as("the default branch's head, read through the GitHub API").isEqualTo(head);
		assertThat(done.sourceFullName()).isEqualTo(this.source);
		assertThat(done.defaultBranch()).isEqualTo("main");
		assertThat(GITHUB.seen).as("the head is read through the GitHub API, with no scan")
			.anyMatch(request -> request.method().equals("GET")
					&& request.path().equals("/repos/" + this.source + "/git/ref/heads/main"));
		String intermediary = JobSetups.intermediaryName(this.job);
		String working = JobSetups.workingName(this.job);
		assertThat(GITHUB.head(GitHubStub.ORGANIZATION, intermediary, "main")).as("a copy with the commit IDs kept")
			.isEqualTo(head);
		assertThat(GITHUB.head(GitHubStub.ORGANIZATION, working, "main")).as("a second copy, not a fork").isEqualTo(head);
		for (String name : List.of(intermediary, working)) {
			assertThat(GITHUB.isPrivate(name)).as(name + " is private").isTrue();
			for (String off : List.of("has_wiki", "has_issues", "has_projects", "has_discussions", "allow_forking")) {
				assertThat(GITHUB.setting(name, off)).as(name + " " + off).isEqualTo(false);
			}
			assertThat(GITHUB.seen).as("its settings and Actions are read back")
				.anyMatch(request -> request.method().equals("GET")
						&& request.path().equals("/repos/" + GitHubStub.ORGANIZATION + "/" + name + "/actions/permissions"))
				.noneMatch(request -> request.method().equals("PUT") && request.path().endsWith(name + "/actions/permissions"));
			long id = GITHUB.organizationRepositoryId(name);
			int readBack = indexOf(request -> request.path().equals("/repos/" + GitHubStub.ORGANIZATION + "/" + name
					+ "/actions/permissions"));
			int pushToken = indexOf(request -> request.path().endsWith("/access_tokens")
					&& request.body().path("repository_ids").toString().equals("[" + id + "]")
					&& request.body().path("permissions").path("contents").asString("").equals("write"));
			assertThat(readBack).as("read back before any code is pushed into " + name).isNotNegative().isLessThan(pushToken);
		}
		long intermediaryId = GITHUB.organizationRepositoryId(intermediary);
		long workingId = GITHUB.organizationRepositoryId(working);
		int workingPush = indexOf(request -> request.path().endsWith("/access_tokens")
				&& request.body().path("repository_ids").toString().equals("[" + workingId + "]")
				&& request.body().path("permissions").path("contents").asString("").equals("write"));
		GitHubStub.Seen beforeWorkingPush = List.copyOf(GITHUB.seen).get(workingPush - 1);
		assertThat(beforeWorkingPush.path()).as("the Working copy is cloned from the Intermediary, not the Source")
			.endsWith("/access_tokens");
		assertThat(beforeWorkingPush.body().path("repository_ids").toString()).isEqualTo("[" + intermediaryId + "]");
		assertThat(beforeWorkingPush.body().path("permissions").toString()).isEqualTo("{\"contents\":\"read\"}");
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
		assertThat(GITHUB.seen).as("no fork and no ruleset")
			.noneMatch(request -> request.path().endsWith("/forks") || request.path().contains("/rulesets"));
		assertThat(done.intermediaryName()).isEqualTo(GitHubStub.ORGANIZATION + "/" + intermediary);
		assertThat(this.busy.job(this.job).orElseThrow().busy()).as("the resolving transaction cleared it").isEmpty();
		assertThat(this.busy.job(this.job).orElseThrow().state()).as("J7 waits for Verify").isEqualTo("STARTING");
		assertThat(this.jdbc.sql("""
				SELECT action FROM audit.audit_entry WHERE job_id = :job AND (action LIKE 'repo.%' OR action LIKE 'job.busy_%')
				ORDER BY occurred_at, action
				""").param("job", this.job).query(String.class).list()).contains("repo.setup_started",
						"repo.setup_source_checked", "repo.intermediary_copied", "repo.setup_done", "job.busy_reserved",
						"job.busy_released");
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/setup").with(as(this.mentor, Role.MENTOR)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("DONE"))
			.andExpect(jsonPath("$.steps[2].state").value("DONE"))
			.andExpect(jsonPath("$.canRetry").value(false));
		UUID stranger = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/setup").with(as(stranger, Role.CUSTOMER)))
			.andExpect(status().isNotFound());
	}

	@Test
	void aGitHubFailureReadingTheHeadIsRetriedAndTheNextAttemptCopies() throws Exception {
		String prefix = "GET /repos/" + this.source + "/git/ref/heads/";
		GITHUB.unavailable.add(prefix);
		try {
			j6();
			this.worker.runDue(Lane.GITHUB_LONG);

			Setup waiting = this.setups.latest(this.job).orElseThrow();
			assertThat(waiting.status()).isEqualTo("RUNNING");
			assertThat(waiting.checkedAt()).as("step 1 is not done").isNull();
			assertThat(this.jdbc.sql("""
					SELECT status || ':' || error_code FROM operations.external_operation WHERE type = 'repo.job_setup'
					""").query(String.class).single()).isEqualTo("PENDING:GITHUB_HTTP_502");
			assertThat(GITHUB.organizationRepositoryId(JobSetups.intermediaryName(this.job))).as("nothing was created").isNull();
		}
		finally {
			GITHUB.unavailable.remove(prefix);
		}
		this.clock.advance(JobSetupOperation.POLICY.delayAfter(1).plusSeconds(1));
		this.worker.runDue(Lane.GITHUB_LONG);

		Setup done = this.setups.latest(this.job).orElseThrow();
		assertThat(done.status()).isEqualTo("DONE");
		assertThat(done.approvedSha()).isEqualTo(GITHUB.head("lan-dev", this.source.substring("lan-dev/".length()), "main"));
	}

	/**
	 * Story 23.5: the Customer changed {@code vibefix.yml} after registration, so Setup blocks before anything is copied
	 * with {@code WORKFLOW_MISMATCH}, until they add the published file again and try again. A repository that is not
	 * public reads through the Source App like any other.
	 */
	@Test
	void aChangedWorkflowFileBlocksBeforeAnyCopyUntilTheCustomerAddsThePublishedOneAndTriesAgain() throws Exception {
		String name = this.source.substring("lan-dev/".length());
		long id = this.jdbc.sql("SELECT repository_github_id FROM product.product WHERE id = :id").param("id", this.product)
			.query(Long.class).single();
		long owner = CustomerTokens.githubUserId(this.jdbc, this.customer);
		GITHUB.publish("lan-dev", name, id, owner, false, false);
		String published = GitHubStub.published(GitHubStub.CUSTOMER_WORKFLOW);
		GITHUB.sourceFiles.put(this.source + ":.github/workflows/vibefix.yml", published + "      # changed\n");

		j6();
		this.worker.runDue(Lane.GITHUB_LONG);

		Setup blocked = this.setups.latest(this.job).orElseThrow();
		assertThat(blocked.block()).isEqualTo(JobSetups.Block.WORKFLOW_MISMATCH);
		assertThat(blocked.approvedSha()).as("no head was read").isNull();
		assertThat(this.jdbc.sql("SELECT status FROM operations.external_operation WHERE type = 'repo.job_setup'")
			.query(String.class).single()).isEqualTo("ABORTED");
		assertThat(this.busy.job(this.job).orElseThrow().busy()).as("the block releases the marker").isEmpty();
		assertThat(this.jdbc.sql("SELECT params ->> 'reason' FROM notification.notification WHERE type = 'repo.job_start_blocked'")
			.query(String.class).list()).as("the Customer and the Mentor, each with the reason").hasSize(2)
			.containsOnly("file workflow vibefix.yml trong dự án gốc khác bản vibefix công bố");
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
			.andExpect(jsonPath("$.status").value("RUNNING"));
		run();

		Setup done = this.setups.latest(this.job).orElseThrow();
		assertThat(done.id()).as("a new attempt").isNotEqualTo(blocked.id());
		assertThat(done.status()).isEqualTo("DONE");
		assertThat(done.approvedSha()).isEqualTo(GITHUB.head("lan-dev", name, "main"));
		awaitListeners();
	}

	@Test
	void aFailedAttemptRetriedFromAdm7ChecksTheSourceAgainAndCopies() throws Exception {
		// The shape Story 23.1's migration leaves a Setup in that an open Intake Finding blocked: FAILED before step 1
		// ended, with its operation FAILED in ADM-7's queue.
		Setup failed = failedOnTheHeadRead();

		assertThat(this.failedOperations.retry(operationOf(failed))).isTrue();
		this.worker.runDue(Lane.GITHUB_LONG);

		Setup done = this.setups.latest(this.job).orElseThrow();
		assertThat(done.id()).as("the same attempt, resumed").isEqualTo(failed.id());
		assertThat(done.status()).isEqualTo("DONE");
		assertThat(done.approvedSha()).isEqualTo(GITHUB.head("lan-dev", this.source.substring("lan-dev/".length()), "main"));
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
		this.worker.runDue(Lane.GITHUB_LONG);

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
				this.worker.runDue(Lane.GITHUB_LONG);
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
		assertThat(this.busy.job(this.job).orElseThrow().busy()).as("the failure releases the marker").isEmpty();
		return failed;
	}

	private UUID operationOf(Setup setup) {
		return this.jdbc.sql("SELECT id FROM operations.external_operation WHERE operation_key = :key")
			.param("key", JobSetups.OPERATION + ":" + setup.id())
			.query(UUID.class)
			.single();
	}

	/** J6's event, as {@code job} publishes it in M1's cascade. */
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

	private void j6() {
		this.setups.started(new JobTransitioned(UUID.randomUUID(), UUID.randomUUID(), this.job, "J6", "SYSTEM",
				"AGREEMENT", "STARTING", 3, 1, Map.of("code", "true")));
	}

	/** Setup runs to its end in one attempt: it waits for no scan. */
	private void run() {
		this.worker.runDue(Lane.GITHUB_LONG);
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

}
