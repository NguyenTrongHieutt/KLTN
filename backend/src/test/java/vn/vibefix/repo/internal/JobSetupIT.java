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
import vn.vibefix.identity.internal.IdentityTestSupport;
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
 * or lost admin rights block the start until the Customer fixes it and taps "Thử lại". GitHub and git
 * hosting are {@link GitHubStub}; the sandbox jobs run in process.
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
		this.mentor = IdentityTestSupport.insertAccount(this.jdbc, Role.MENTOR, MentorApprovalStatus.APPROVED, null);
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

	private RequestPostProcessor as(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

}
