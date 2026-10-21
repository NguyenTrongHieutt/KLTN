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
import vn.vibefix.identity.MentorApprovalStatus;
import vn.vibefix.identity.internal.CustomerTokens;
import vn.vibefix.identity.internal.AdministratorTestSupport;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.team.internal.TeamTestSupport;
import vn.vibefix.timers.internal.DeadlinePoller;
import vn.vibefix.job.JobBusy;
import vn.vibefix.job.JobTransitioned;
import vn.vibefix.operations.Lane;
import vn.vibefix.operations.internal.OperationWorker;
import vn.vibefix.repo.internal.JobSetups.Setup;
import vn.vibefix.scanning.Scans;
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;

/**
 * Story 7.2 (FR-18, AD-5, AD-11, AD-28): J6 of a Code Job sets up its repositories under the busy marker.
 * The Source Repository is read again with the Customer's token and scanned again (purpose {@code J7});
 * the Intermediary Repository gets exactly the commit that scan approved, the Working Repository is its
 * fork, and AD-11's rulesets are read back before the marker clears. A Finding, a private repository,
 * or lost admin rights block the start until the Customer fixes it and taps "Thử lại". Then (Story 7.3)
 * the Mentor and the active Crew are invited with read; once each accepted with two-factor authentication
 * on, Verify raises them to write, reads it back, and only then applies J7. GitHub and git hosting are
 * {@link GitHubStub}; the sandbox jobs run in process.
 */
class JobSetupIT extends IntegrationTest {

	private static final Path DIRECTORY;

	private static final GitHubStub GITHUB;

	private static final AtomicLong REPOSITORY_IDS = new AtomicLong(880000);

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
	private Scans scans;

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
	private PushScans pushScans;

	@Autowired
	private PlatformGuard guard;

	@Autowired
	private org.springframework.transaction.PlatformTransactionManager transactions;

	private UUID product;

	private UUID job;

	private String source;

	@DynamicPropertySource
	static void github(DynamicPropertyRegistry registry) {
		registry.add("vibefix.github.api-url", () -> GITHUB.apiUrl().toString());
		registry.add("vibefix.github.git-url", () -> GITHUB.gitUrl().toString());
		registry.add("vibefix.github.platform-app.id", () -> "123456");
		registry.add("vibefix.github.platform-app.installation-id", () -> String.valueOf(GitHubStub.INSTALLATION));
		registry.add("vibefix.github.platform-app.key-file", () -> DIRECTORY.resolve("app-key").toString());
		registry.add("vibefix.github.fork-poll", () -> "10ms");
		registry.add("vibefix.github.fork-wait", () -> "5s");
		registry.add("vibefix.github.operator-login", () -> "vibefix-operator");
	}

	@AfterAll
	static void stop() {
		GITHUB.close();
	}

	@BeforeEach
	void aCodeJobStarting() throws Exception {
		this.customer = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null, null);
		// A fake token, built at run time; the stub accepts any.
		CustomerTokens.keep(this.context, this.customer, String.join("", "gh", "o_", "x".repeat(36)));
		this.mentor = TeamTestSupport.mentor(this.jdbc, MentorApprovalStatus.APPROVED, "Đỗ Mạnh Hùng", List.of(TeamTestSupport.REACT),
				"React");
		this.crew = TeamTestSupport.student(this.jdbc, "Lê Thảo Vy", true, List.of(TeamTestSupport.REACT), "React",
				LocalDate.parse("2026-12-31"));
		long repository = REPOSITORY_IDS.incrementAndGet();
		String name = "so-dat-ban-" + repository;
		this.source = "lan-dev/" + name;
		GITHUB.source("lan-dev", name);
		GITHUB.publish("lan-dev", name, repository, CustomerTokens.githubUserId(this.jdbc, this.customer), true, false);
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
				INSERT INTO job.crew_row (id, job_id, student_account_id, status, signed_at, activated_at, created_at)
				VALUES (:id, :job, :student, 'ACTIVE', :now, :now, :now)
				""").param("id", UUID.randomUUID()).param("job", this.job).param("student", this.crew).param("now", now).update();
		GITHUB.members.clear();
		GITHUB.withoutTwoFactor.clear();
		GITHUB.pulls.clear();
		GITHUB.closedPulls.clear();
		GITHUB.eventActors.clear();
		GITHUB.issuesOn.clear();
		GITHUB.repositorySelection = "all";
	}

	@Test
	void everyoneAcceptsWithTwoFactorOnThenWriteIsReadBackAndOnlyThenJ7Applies() throws Exception {
		String mentorLogin = TeamTestSupport.login(this.jdbc, this.mentor);
		String crewLogin = TeamTestSupport.login(this.jdbc, this.crew);
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

	@Test
	void aMemberACrewChangeAddsToAnActiveJobIsInvitedAndRaisedOnAcceptanceWithinThreeRenewals() throws Exception {
		AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		j6();
		run();
		this.worker.runDue(Lane.GITHUB_SHORT);
		GITHUB.accept(TeamTestSupport.login(this.jdbc, this.mentor));
		GITHUB.accept(TeamTestSupport.login(this.jdbc, this.crew));
		this.checks.check(this.job);
		this.worker.runDue(Lane.GITHUB_SHORT);
		assertThat(this.busy.job(this.job).orElseThrow().state()).isEqualTo("ACTIVE");
		UUID hai = TeamTestSupport.student(this.jdbc, "Phạm Văn Hải", true, List.of(TeamTestSupport.REACT), "React",
				LocalDate.parse("2026-12-31"));
		UUID row = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO job.crew_row (id, job_id, student_account_id, status, signed_at, activated_at, created_at)
				VALUES (:id, :job, :student, 'ACTIVE', :now, :now, :now)
				""").param("id", row).param("job", this.job).param("student", hai)
			.param("now", Timestamp.from(this.clock.instant())).update();

		this.checks.added(new vn.vibefix.job.CrewMemberAdded(UUID.randomUUID(), this.job, row, hai, "ACTIVE"));
		this.worker.runDue(Lane.GITHUB_SHORT);
		JobGrants.Grant added = this.grants.ofJob(this.job).stream().filter(grant -> grant.accountId().equals(hai))
			.findFirst().orElseThrow();
		assertThat(added.status()).isEqualTo("INVITED");
		assertThat(added.crewChange()).isTrue();

		GITHUB.accept(TeamTestSupport.login(this.jdbc, hai));
		long repository = added.repositoryId();
		this.checks.hinted(new vn.vibefix.github.GitHubWebhookReceived(UUID.randomUUID(), UUID.randomUUID().toString(),
				vn.vibefix.github.GitHubApps.App.PLATFORM, "member", "added", 7L, List.of(repository), 1L, null));
		this.worker.runDue(Lane.GITHUB_SHORT);

		assertThat(this.grants.find(added.id()).orElseThrow().status()).as("raised without a state change").isEqualTo("WRITE");
		assertThat(this.busy.job(this.job).orElseThrow().state()).isEqualTo("ACTIVE");

		UUID late = TeamTestSupport.student(this.jdbc, "Ngô Minh", true, List.of(TeamTestSupport.REACT), "React",
				LocalDate.parse("2026-12-31"));
		UUID lateRow = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO job.crew_row (id, job_id, student_account_id, status, signed_at, activated_at, created_at)
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

	@Test
	void aPushIsScannedAndItsCredentialGoesToTheMentorWhoConfirmsTheRotation() throws Exception {
		j6();
		run();
		String working = JobSetups.workingName(this.job);
		this.pushScans.sweep();
		assertThat(this.jdbc.sql("SELECT count(*) FROM repo.push_scan WHERE job_id = :job").param("job", this.job)
			.query(Long.class).single()).as("the copy itself is not scanned again").isZero();
		// A fake credential, built at run time.
		String key = "sk" + "_live_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
		GITHUB.push(GitHubStub.ORGANIZATION, working, "main", "pay.js", "const stripe = require('stripe')('" + key + "')\n");
		long repository = GITHUB.organizationRepositoryId(working);

		this.pushScans.hinted(new vn.vibefix.github.GitHubWebhookReceived(UUID.randomUUID(), UUID.randomUUID().toString(),
				vn.vibefix.github.GitHubApps.App.PLATFORM, "push", null, 7L, List.of(repository), 1L, null));
		UUID run = this.jdbc.sql("SELECT scan_run_id FROM repo.push_scan WHERE job_id = :job").param("job", this.job)
			.query(UUID.class).single();
		assertThat(this.jdbc.sql("SELECT purpose FROM scanning.scan_run WHERE id = :id").param("id", run).query(String.class)
			.single()).isEqualTo("PUSH");
		this.worker.runDue(Lane.SCAN);
		awaitListeners();

		UUID finding = this.jdbc.sql("SELECT id FROM scanning.finding WHERE scan_run_id = :run").param("run", run)
			.query(UUID.class).single();
		assertThat(this.scans.mayResolve(finding, this.mentor)).as("the Mentor resolves a pushed credential").isTrue();
		assertThat(this.scans.mayResolve(finding, this.customer)).isFalse();
		assertThat(this.jdbc.sql("SELECT count(*) FROM notification.todo WHERE todo_key = 'scanning.resolve_work_finding'")
			.query(Long.class).single()).isOne();
		assertThat(notices("scanning.finding_opened")).as("the Customer, the Mentor, and the Crew").isEqualTo(3);
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/findings").with(as(this.customer, Role.CUSTOMER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].type").value("Khóa bí mật Stripe"))
			.andExpect(jsonPath("$[0].canRotate").value(false))
			.andExpect(jsonPath("$[0].maskedPreview").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(key))));
		this.mvc.perform(post("/api/v1/jobs/" + this.job + "/findings/" + finding + "/rotation").with(as(this.mentor, Role.MENTOR))
			.with(csrf()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$").isEmpty());
		assertThat(this.jdbc.sql("SELECT count(*) FROM notification.todo WHERE todo_key = 'scanning.resolve_work_finding' AND closed_at IS NULL")
			.query(Long.class).single()).as("resolution closes the to-do").isZero();
	}

	@Test
	void aPushScanWaitingMoreThanTenMinutesAlertsTheAdministrators() throws Exception {
		AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		j6();
		run();
		String working = JobSetups.workingName(this.job);
		GITHUB.push(GitHubStub.ORGANIZATION, working, "main", "README.md", "# Quán Mây v2\n");

		this.pushScans.sweep();
		this.clock.advance(PushScans.DELAY.plusMinutes(1));
		this.deadlines.runDue();

		assertThat(notices("scanning.push_scan_delayed")).isOne();
		this.pushScans.sweep();
		assertThat(this.jdbc.sql("SELECT count(*) FROM repo.push_scan WHERE job_id = :job").param("job", this.job)
			.query(Long.class).single()).as("unchanged heads request nothing").isOne();
		awaitListeners();
	}

	@Test
	void foreignPullRequestsAreClosedAndOnlyTheSystemAndGranteesActWithoutAnAlert() throws Exception {
		AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		j6();
		run();
		String intermediary = JobSetups.intermediaryName(this.job);
		String working = JobSetups.workingName(this.job);
		long intermediaryId = GITHUB.organizationRepositoryId(intermediary);
		long workingId = GITHUB.organizationRepositoryId(working);
		GITHUB.pulls.put(intermediary, new java.util.LinkedHashMap<>(Map.of(1, "mallory", 2, "vibefix-dev-platform[bot]")));
		GITHUB.pulls.put(working, new java.util.LinkedHashMap<>(Map.of(3, "outsider")));

		this.guard.hinted(webhook("pull_request", intermediaryId, "mallory"));
		this.guard.hinted(webhook("pull_request", workingId, "outsider"));

		assertThat(GITHUB.closedPulls).containsExactlyInAnyOrder(intermediary + "#1", working + "#3");
		assertThat(notices("github.unexpected_actor")).as("an outsider's pull request on the Working Repository is not alerted")
			.isOne();
		this.guard.hinted(webhook("push", workingId, TeamTestSupport.login(this.jdbc, this.crew)));
		assertThat(notices("github.unexpected_actor")).as("a grantee pushes freely").isOne();
		this.guard.hinted(webhook("push", workingId, "mallory"));
		assertThat(notices("github.unexpected_actor")).isEqualTo(2);
		this.guard.hinted(webhook("push", intermediaryId, "vibefix-operator"));
		assertThat(notices("github.operator_account_used")).isOne();
		assertThat(this.jdbc.sql("SELECT count(*) FROM audit.audit_entry WHERE action = 'repo.foreign_pull_request_closed'")
			.query(Long.class).single()).isOne();
		awaitListeners();
	}

	@Test
	void theSweepReadsThePlatformBackAndAlertsEachDriftOnceADay() throws Exception {
		AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		j6();
		run();
		assertThat(sweep()).as("everything as vibefix expects").isEmpty();
		String intermediary = JobSetups.intermediaryName(this.job);
		GITHUB.addCollaborator(intermediary, "mallory", "write");
		GITHUB.issuesOn.add(JobSetups.workingName(this.job));
		GITHUB.repositorySelection = "selected";
		GITHUB.eventActors.put(intermediary, List.of("vibefix-dev-platform[bot]", "mallory"));

		List<String> differences = sweep();

		assertThat(differences).anyMatch(difference -> difference.contains("collaborators [mallory] on the Intermediary"))
			.anyMatch(difference -> difference.contains("has_issues is on"))
			.anyMatch(difference -> difference.contains("repository selection: selected instead of all"))
			.anyMatch(difference -> difference.contains("recent changes by [mallory]"));
		assertThat(notices("github.platform_drift")).isOne();
		sweep();
		assertThat(notices("github.platform_drift")).as("once a day for the same differences").isOne();
		awaitListeners();
	}

	@Test
	void aRepositorySelectionDifferenceHoldsNewSetupsUntilItIsFixed() throws Exception {
		GITHUB.repositorySelection = "selected";
		j6();
		run();

		assertThat(this.setups.latest(this.job).orElseThrow().status()).as("waiting, not failed").isEqualTo("RUNNING");
		assertThat(this.setups.latest(this.job).orElseThrow().intermediaryAt()).isNotNull();
		GITHUB.repositorySelection = "all";
		this.clock.advance(Duration.ofMinutes(16));
		this.worker.runDue(Lane.GITHUB_LONG);

		assertThat(this.setups.latest(this.job).orElseThrow().status()).isEqualTo("DONE");
	}

	private List<String> sweep() {
		return new org.springframework.transaction.support.TransactionTemplate(this.transactions).execute(status -> this.guard.sweep());
	}

	private static vn.vibefix.github.GitHubWebhookReceived webhook(String event, long repository, String login) {
		return new vn.vibefix.github.GitHubWebhookReceived(UUID.randomUUID(), UUID.randomUUID().toString(),
				vn.vibefix.github.GitHubApps.App.PLATFORM, event, "opened", 7L, List.of(repository), 1L, null, login);
	}

	@Test
	void anOrganizationMemberDriftsAndHoldsVerifyUntilTheyLeaveTheOrganization() throws Exception {
		AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		String mentorLogin = TeamTestSupport.login(this.jdbc, this.mentor);
		String crewLogin = TeamTestSupport.login(this.jdbc, this.crew);
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
	void aCleanSourceIsCopiedAtTheApprovedCommitForkedAndProtectedUnderTheBusyMarker() throws Exception {
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
		assertThat(done.approvedSha()).as("the J7 scan approved the head").isEqualTo(head);
		assertThat(this.jdbc.sql("SELECT purpose FROM scanning.scan_run WHERE id = :id").param("id", done.scanRunId())
			.query(String.class).single()).isEqualTo("J7");
		String intermediary = JobSetups.intermediaryName(this.job);
		String working = JobSetups.workingName(this.job);
		assertThat(GITHUB.head(GitHubStub.ORGANIZATION, intermediary, "main")).as("a copy with the commit IDs kept")
			.isEqualTo(head);
		assertThat(GITHUB.head(GitHubStub.ORGANIZATION, working, "main")).isEqualTo(head);
		assertThat(GITHUB.rulesetNames(intermediary)).containsExactlyInAnyOrder("vibefix-default-branch", "vibefix-handover");
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
		UUID stranger = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null, null);
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/setup").with(as(stranger, Role.CUSTOMER)))
			.andExpect(status().isNotFound());
	}

	@Test
	void anOpenFindingBlocksTheStartUntilTheCustomerResolvesItAndTriesAgain() throws Exception {
		UUID finding = openFinding();

		j6();
		run();

		Setup blocked = this.setups.latest(this.job).orElseThrow();
		assertThat(blocked.status()).isEqualTo("BLOCKED");
		assertThat(blocked.block()).isEqualTo(JobSetups.Block.INTAKE_FINDING_OPEN);
		assertThat(blocked.openFindings()).isOne();
		assertThat(this.busy.job(this.job).orElseThrow().busy()).isEmpty();
		assertThat(this.jdbc.sql("""
				SELECT params ->> 'reason' FROM notification.notification WHERE type = 'repo.job_start_blocked' ORDER BY account_id
				""").query(String.class).list()).hasSize(2).containsOnly("còn 1 vấn đề bảo mật chưa xử lý");
		assertThat(GITHUB.organizationRepositoryId(JobSetups.intermediaryName(this.job))).as("nothing was created").isNull();
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/setup").with(as(this.mentor, Role.MENTOR)))
			.andExpect(jsonPath("$.steps[0].state").value("BLOCKED"))
			.andExpect(jsonPath("$.blockedReason").value("INTAKE_FINDING_OPEN"))
			.andExpect(jsonPath("$.canRetry").value(false));

		this.scans.confirmRotation(this.product, finding, this.customer);
		this.mvc.perform(post("/api/v1/jobs/" + this.job + "/setup/retry").with(as(this.customer, Role.CUSTOMER))
			.with(csrf()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("RUNNING"));
		run();

		assertThat(this.setups.latest(this.job).orElseThrow().status()).isEqualTo("DONE");
	}

	@Test
	void aPrivateSourceBlocksBeforeAnyScan() throws Exception {
		String name = this.source.substring("lan-dev/".length());
		long id = this.jdbc.sql("SELECT repository_github_id FROM product.product WHERE id = :id").param("id", this.product)
			.query(Long.class).single();
		GITHUB.publish("lan-dev", name, id, CustomerTokens.githubUserId(this.jdbc, this.customer), false, false);

		j6();
		this.worker.runDue(Lane.GITHUB_LONG);

		Setup blocked = this.setups.latest(this.job).orElseThrow();
		assertThat(blocked.block()).isEqualTo(JobSetups.Block.SOURCE_NOT_PUBLIC);
		assertThat(blocked.scanRunId()).isNull();
		assertThat(this.jdbc.sql("SELECT status FROM operations.external_operation WHERE type = 'repo.job_setup'")
			.query(String.class).single()).isEqualTo("ABORTED");
	}

	/** J6's event, as {@code job} publishes it in M1's cascade. */
	private void j6() {
		this.setups.started(new JobTransitioned(UUID.randomUUID(), UUID.randomUUID(), this.job, "J6", "SYSTEM",
				"AGREEMENT", "STARTING", 3, 1, Map.of("crewTermsVersionNo", "1", "code", "true")));
	}

	/** Setup asks for the J7 scan and waits; the scan runs; Setup goes on. */
	private void run() {
		this.worker.runDue(Lane.GITHUB_LONG);
		this.worker.runDue(Lane.SCAN);
		this.clock.advance(JobSetupOperation.SCAN_WAIT.plusSeconds(1));
		this.worker.runDue(Lane.GITHUB_LONG);
	}

	private UUID openFinding() {
		UUID run = UUID.randomUUID();
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO scanning.scan_run (id, product_id, customer_account_id, product_name, repository_full_name,
				    repository_github_id, purpose, status, rules_version, found_count, new_findings, requested_at, finished_at)
				VALUES (:id, :product, :owner, 'Sổ đặt bàn Quán Mây', :name, 1, 'INTAKE', 'SUCCEEDED', 2, 1, 1, :now, :now)
				""").param("id", run).param("product", this.product).param("owner", this.customer).param("name", this.source)
			.param("now", now).update();
		UUID finding = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO scanning.finding (id, product_id, scan_run_id, category, rule_id, rule_type, file_path,
				    commit_sha, committed_at, masked_preview, fingerprint, status, created_at)
				VALUES (:id, :product, :run, 'CREDENTIAL', 'stripe-secret-key', 'Khóa bí mật Stripe', 'server/pay.js',
				    repeat('a', 40), :now, 'sk_l••••••••Q7mZ', repeat('c', 64), 'OPEN', :now)
				""").param("id", finding).param("product", this.product).param("run", run).param("now", now).update();
		this.jdbc.sql("INSERT INTO scanning.finding_resolver (finding_id, account_id) VALUES (:f, :a)")
			.param("f", finding).param("a", this.customer).update();
		return finding;
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
