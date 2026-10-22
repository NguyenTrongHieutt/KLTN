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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import com.jayway.jsonpath.JsonPath;
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
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.github.internal.GitHubStub;
import vn.vibefix.github.internal.GitHubStub.MergeInstallation;
import vn.vibefix.github.internal.TestKeys;
import vn.vibefix.identity.MentorApprovalStatus;
import vn.vibefix.identity.internal.CustomerTokens;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.job.JobCompletion;
import vn.vibefix.operations.Lane;
import vn.vibefix.operations.internal.OperationWorker;
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;

/**
 * Story 8.8 (FR-37, AD-5, AD-11, SG-9, SG-15, SG-19): a Code Job in Closing whose Final Merge
 * authorization is bound gets its check: the Source Repository read again, every grant revoked, J9's
 * guard, the whole Job's Sensitive Changes, the workflow files already in the Source Repository, and the
 * trial merge. The Customer confirms; the merge pushes a new branch, merges it through a pull request,
 * reads it back, deletes the branch, and the resolving transaction sets the authorization USED and
 * applies J9. A conflict, a refusal that is not transient, or a failed guard routes to the ZIP Fallback
 * instead. GitHub and git hosting are {@link GitHubStub}; the sandbox jobs run in process.
 */
class FinalMergeIT extends IntegrationTest {

	private static final Path DIRECTORY;

	private static final GitHubStub GITHUB;

	private static final AtomicLong IDS = new AtomicLong(770000);

	private static final String TOKEN = "gho_" + "customer_token_never_printed";

	static {
		try {
			DIRECTORY = Files.createTempDirectory("final-merge-it-");
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
	private ApplicationContext context;

	@Autowired
	private OperationWorker worker;

	@Autowired
	private TransactionTemplate transactions;

	@Autowired
	private FinalMergeAuthorizations authorizations;

	@Autowired
	private JobCompletion completion;

	private UUID lan;

	private UUID hung;

	private UUID job;

	private String owner;

	private String name;

	private long repository;

	private long ownerId;

	private String intermediary;

	private long installation;

	@DynamicPropertySource
	static void github(DynamicPropertyRegistry registry) {
		registry.add("vibefix.github.api-url", () -> GITHUB.apiUrl().toString());
		registry.add("vibefix.github.git-url", () -> GITHUB.gitUrl().toString());
		registry.add("vibefix.github.platform-app.id", () -> "123456");
		registry.add("vibefix.github.platform-app.installation-id", () -> String.valueOf(GitHubStub.INSTALLATION));
		registry.add("vibefix.github.platform-app.key-file", () -> DIRECTORY.resolve("app-key").toString());
		registry.add("vibefix.github.merge-app.id", () -> "654321");
		registry.add("vibefix.github.merge-app.key-file", () -> DIRECTORY.resolve("app-key").toString());
	}

	@AfterAll
	static void stop() {
		GITHUB.close();
	}

	@BeforeEach
	void aClosingCodeJobWithOneMergedHandover() throws Exception {
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null, null);
		CustomerTokens.keep(this.context, this.lan, TOKEN);
		this.hung = IdentityTestSupport.insertAccount(this.jdbc, Role.MENTOR, MentorApprovalStatus.APPROVED, null);
		long id = IDS.incrementAndGet();
		// The Customer's own repository: GitHub shows them as its owner, and the Merge App is installed on their account.
		this.ownerId = CustomerTokens.githubUserId(this.jdbc, this.lan);
		this.owner = "quan-may-" + id;
		this.name = "so-dat-ban";
		this.repository = id;
		GITHUB.source(this.owner, this.name);
		GITHUB.publish(this.owner, this.name, this.repository, this.ownerId, true, true);
		String approved = GITHUB.head(this.owner, this.name, "main");
		this.intermediary = "job-" + id + "-intermediary";
		long intermediaryId = GITHUB.platformRepository(this.intermediary, this.owner, this.name);
		long workingId = GITHUB.platformRepository("job-" + id + "-working", GitHubStub.ORGANIZATION, this.intermediary);
		// One accepted handover, merged into the Intermediary default branch by repo.
		String handed = GITHUB.push(GitHubStub.ORGANIZATION, this.intermediary, "main", "package.json",
				"{\"name\":\"quan-may\"}\n", "Bàn giao Giai đoạn 1", "hung@example.test");

		UUID product = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO product.product (id, customer_account_id, name, description, ai_tools, repository_github_id,
				    repository_full_name, repository_owner_type, repository_default_branch, public_notice_version,
				    copy_permission_version, detection_notice_version, acknowledged_at, created_at)
				VALUES (:id, :owner, 'Sổ đặt bàn Quán Mây', 'Đặt bàn.', ARRAY['LOVABLE'], :github, :name, 'User', 'main',
				    '1.0', '1.0', '1.0', now(), now())
				""").param("id", product).param("owner", this.lan).param("github", this.repository)
			.param("name", this.owner + "/" + this.name).update();
		this.job = UUID.randomUUID();
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, budget, deadline, rules, selection_days, opened_at, lapses_at, mentor_account_id,
				    selected_application_id, selected_at, version, created_at, updated_at, closing_at)
				VALUES (:id, :product, :owner, 'CLOSING', 'Sửa lỗi đặt bàn', 'Lỗi', 'Đúng', 'Khách', ARRAY['BUG_FIX'],
				    'UNDER_2M', DATE '2026-12-01', '{}'::jsonb, 14, :now, :now, :mentor, :application, :now, 8, :now, :now, :now)
				""").param("id", this.job).param("product", product).param("owner", this.lan).param("mentor", this.hung)
			.param("application", UUID.randomUUID()).param("now", now).update();
		this.jdbc.sql("""
				INSERT INTO repo.job_setup (id, job_id, product_id, status, source_full_name, default_branch, approved_sha,
				    intermediary_github_id, intermediary_name, working_github_id, working_name, checked_at, intermediary_at,
				    working_at, started_at, finished_at)
				VALUES (:id, :job, :product, 'DONE', :source, 'main', :sha, :intermediaryId, :intermediary, :workingId, :working,
				    :now, :now, :now, :now, :now)
				""").param("id", UUID.randomUUID()).param("job", this.job).param("product", product)
			.param("source", this.owner + "/" + this.name).param("sha", approved).param("intermediaryId", intermediaryId)
			.param("intermediary", GitHubStub.ORGANIZATION + "/" + this.intermediary).param("workingId", workingId)
			.param("working", GitHubStub.ORGANIZATION + "/job-" + id + "-working").param("now", now).update();
		this.jdbc.sql("""
				INSERT INTO repo.handover_merge (job_id, pull_number, handover_sha, head_after, merged_at)
				VALUES (:job, 1, :sha, :sha, :now)
				""").param("job", this.job).param("sha", handed).param("now", now).update();

		this.authorizations.open(this.job);
		this.installation = IDS.incrementAndGet();
		GITHUB.mergeInstallations.put(this.installation, new MergeInstallation(this.ownerId, this.owner)
			.repository(this.repository, this.owner + "/" + this.name, this.ownerId));
	}

	@Test
	void theBoundAuthorizationIsCheckedConfirmedMergedAndTheJobCloses() throws Exception {
		bind();
		assertThat(mergeStatus()).as("binding in Closing starts the check").isEqualTo("CHECKING");
		this.worker.runDue(Lane.GITHUB_LONG);

		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/final-merge").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.status").value("BOUND"))
			.andExpect(jsonPath("$.merge.status").value("READY"))
			.andExpect(jsonPath("$.merge.sensitive[0].path").value("package.json"))
			.andExpect(jsonPath("$.merge.sensitive[0].kind").value("DEPENDENCIES"))
			.andExpect(jsonPath("$.merge.workflows[0]").value(".github/workflows/ci.yml"));
		confirm(false).andExpect(status().isBadRequest());
		confirm(true).andExpect(status().isOk()).andExpect(jsonPath("$.merge.status").value("CONFIRMED"));
		assertThat(this.jdbc.sql("SELECT busy_purpose FROM job.job WHERE id = :id").param("id", this.job).query(String.class)
			.single()).isEqualTo("FINAL_MERGE");
		assertThat(openTodos()).isZero();
		confirm(true).andExpect(status().isConflict());

		this.worker.runDue(Lane.GITHUB_LONG);

		assertThat(mergeStatus()).isEqualTo("MERGED");
		assertThat(authorizationStatus()).isEqualTo("USED");
		assertThat(jobState()).isEqualTo("CLOSED");
		assertThat(released()).as("the marker is released").isTrue();
		assertThat(GITHUB.hasFile(this.owner, this.name, "main", "package.json")).as("the code reached the project").isTrue();
		assertThat(GITHUB.head(this.owner, this.name, FinalMergeOperation.BRANCH_PREFIX + authorizationId()))
			.as("the branch is deleted").isNull();
		assertThat(GITHUB.sourcePulls.get(this.owner + "/" + this.name)).hasSize(1);
		for (String token : GITHUB.tokensOf(this.installation)) {
			assertThat(GITHUB.grantOf(token).path("repository_ids").toString())
				.as("every Final Merge token names the Source Repository alone")
				.isIn("[" + this.repository + "]", "");
		}
		assertThat(GITHUB.removedInstallations).contains(this.installation);
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM notification.notification WHERE type = 'repo.final_merge_completed'
				    AND account_id IN (:lan, :hung)
				""").param("lan", this.lan).param("hung", this.hung).query(Long.class).single()).isEqualTo(2);
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/final-merge").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.status").value("USED"))
			.andExpect(jsonPath("$.merge.status").value("MERGED"))
			.andExpect(jsonPath("$.merge.pullUrl").value("https://github.com/" + this.owner + "/" + this.name + "/pull/1"));
	}

	@Test
	void aTrialMergeConflictRoutesToTheZipFallbackAndPushesNothing() throws Exception {
		GITHUB.push(this.owner, this.name, "main", "package.json", "{\"name\":\"khac\"}\n");
		bind();
		this.worker.runDue(Lane.GITHUB_LONG);

		assertThat(mergeStatus()).isEqualTo("CONFLICT");
		assertThat(authorizationStatus()).isEqualTo("ENDED");
		assertThat(this.jdbc.sql("SELECT end_reason FROM repo.final_merge_authorization WHERE job_id = :job")
			.param("job", this.job).query(String.class).single()).isEqualTo("ZIP_FALLBACK");
		assertThat(GITHUB.sourcePulls.getOrDefault(this.owner + "/" + this.name, java.util.Map.of())).isEmpty();
		assertThat(jobState()).isEqualTo("CLOSING");
	}

	@Test
	void aRulesetRefusalRoutesToTheZipFallbackNotTheFailedQueue() throws Exception {
		bind();
		this.worker.runDue(Lane.GITHUB_LONG);
		confirm(true).andExpect(status().isOk());
		GITHUB.sourceMergeRefusal = 405;
		try {
			this.worker.runDue(Lane.GITHUB_LONG);
		}
		finally {
			GITHUB.sourceMergeRefusal = null;
		}

		assertThat(mergeStatus()).isEqualTo("REFUSED");
		assertThat(authorizationStatus()).isEqualTo("ENDED");
		assertThat(released()).isTrue();
		assertThat(this.jdbc.sql("SELECT status FROM operations.external_operation WHERE type = 'repo.final_merge'")
			.query(String.class).single()).isEqualTo("ABORTED");
	}

	@Test
	void aHeadThatMovedOutsideTheHandoversFailsTheGuard() throws Exception {
		GITHUB.push(GitHubStub.ORGANIZATION, this.intermediary, "main", "extra.txt", "x\n");
		bind();
		this.worker.runDue(Lane.GITHUB_LONG);

		assertThat(mergeStatus()).isEqualTo("REFUSED");
		assertThat(this.jdbc.sql("SELECT refusal FROM repo.final_merge WHERE job_id = :job").param("job", this.job)
			.query(String.class).single()).isEqualTo("J9_GUARD");
	}

	@Test
	void anAccessNotYetRevokedMakesTheCheckWait() throws Exception {
		this.jdbc.sql("""
				INSERT INTO repo.job_grant (id, job_id, product_id, account_id, role, github_login, repository_github_id,
				    repository_name, crew_change, status, created_at)
				SELECT :id, :job, product_id, :mentor, 'MENTOR', 'hung', 1, 'x/y', false, 'INVITING', now()
				FROM job.job WHERE id = :job
				""").param("id", UUID.randomUUID()).param("job", this.job).param("mentor", this.hung).update();
		bind();
		this.worker.runDue(Lane.GITHUB_LONG);
		assertThat(mergeStatus()).isEqualTo("CHECKING");
		assertThat(this.jdbc.sql("SELECT status FROM operations.external_operation WHERE type = 'repo.final_merge_check'")
			.query(String.class).single()).isEqualTo("PENDING");
	}

	@Test
	void aCodeJobClosesOnlyWithItsFinishAndEndsCancelledAfterACancellation() {
		assertThat(jobState()).isEqualTo("CLOSING");
		this.jdbc.sql("""
				INSERT INTO job.cancellation_proposal (id, job_id, scope, reason, proposer, proposer_account_id, status, answer_by,
				    created_at, decided_at)
				VALUES (:id, :job, 'JOB', 'Không cần nữa', 'CUSTOMER', :lan, 'CONFIRMED', now(), now(), now())
				""").param("id", UUID.randomUUID()).param("job", this.job).param("lan", this.lan).update();
		Boolean finished = this.transactions
			.execute(status -> this.completion.finish(this.job, JobCompletion.Finish.ZIP_FALLBACK, null));
		assertThat(finished).isTrue();
		assertThat(jobState()).isEqualTo("CANCELLED");
	}

	private void bind() throws Exception {
		String url = JsonPath.read(this.mvc.perform(post("/api/v1/jobs/" + this.job + "/final-merge/install-link")
			.with(as(this.lan, Role.CUSTOMER)).with(csrf())).andExpect(status().isOk()).andReturn().getResponse()
			.getContentAsString(), "$.url");
		this.mvc.perform(get("/api/v1/github/merge-app/setup").param("installation_id", String.valueOf(this.installation))
			.param("setup_action", "install").param("state", url.substring(url.indexOf("state=") + "state=".length()))
			.with(as(this.lan, Role.CUSTOMER)))
			.andExpect(status().isSeeOther());
		assertThat(authorizationStatus()).isEqualTo("BOUND");
	}

	private ResultActions confirm(boolean agreed) throws Exception {
		return this.mvc.perform(post("/api/v1/jobs/" + this.job + "/final-merge/confirm").with(as(this.lan, Role.CUSTOMER))
			.with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"agreed\":" + agreed + "}"));
	}

	private boolean released() {
		return this.jdbc.sql("SELECT busy_operation_key IS NULL FROM job.job WHERE id = :id").param("id", this.job)
			.query(Boolean.class).single();
	}

	private UUID authorizationId() {
		return this.jdbc.sql("SELECT id FROM repo.final_merge_authorization WHERE job_id = :job").param("job", this.job)
			.query(UUID.class).single();
	}

	private String mergeStatus() {
		return this.jdbc.sql("SELECT status FROM repo.final_merge WHERE job_id = :job").param("job", this.job)
			.query(String.class).optional().orElse("NONE");
	}

	private String authorizationStatus() {
		return this.jdbc.sql("SELECT status FROM repo.final_merge_authorization WHERE job_id = :job").param("job", this.job)
			.query(String.class).single();
	}

	private String jobState() {
		return this.jdbc.sql("SELECT state FROM job.job WHERE id = :id").param("id", this.job).query(String.class).single();
	}

	private long openTodos() {
		return this.jdbc.sql("""
				SELECT count(*) FROM notification.todo WHERE todo_key = 'repo.authorize_final_merge' AND account_id = :id
				    AND closed_at IS NULL
				""").param("id", this.lan).query(Long.class).single();
	}

	private RequestPostProcessor as(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

}
