package vn.vibefix.delivery.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import com.jayway.jsonpath.JsonPath;

import tools.jackson.databind.JsonNode;
import vn.vibefix.dispute.internal.DisputeTestSupport;
import vn.vibefix.github.internal.ActionsExchangeSupport;
import vn.vibefix.github.internal.GitHubStub;
import vn.vibefix.github.internal.TestKeys;
import vn.vibefix.identity.internal.AdministratorTestSupport;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.job.JobFreezes;
import vn.vibefix.operations.Lane;
import vn.vibefix.operations.internal.OperationWorker;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.TestPeople;
import vn.vibefix.timers.Deadlines;
import vn.vibefix.timers.internal.DeadlinePoller;

/**
 * Story 8.2 (FR-23, FR-32, AD-11, AD-18, AD-33, NFR-3): a Code Job's handover covers the commits on the
 * Working Repository's default branch since the commit Setup copied. It needs at least one of them, and no
 * change under {@code .github/workflows/}, which the folder's tree at both ends shows even when GitHub's list
 * of changed files stops at 300; vibefix scans no code (Story 23.1), so nothing waits for a scan. "Gửi bàn
 * giao" pins the head on {@code handover/{id}} in the Intermediary Repository with the System's pull request,
 * applies M2, and
 * records each commit with its author's GitHub ID and the Work Items it mentions, the Sensitive Changes,
 * and the Pages updated. When M2 refuses, the pull request is closed again. A Bug fixing handover carries a
 * preview that responds, or a reason with a recording, and up to five screenshots (Story 8.3). Heads are
 * compared only at acceptance (Story 23.2, D9): a push to the Working Repository voids nothing, and only the
 * acceptance merge's 409, when {@code handover/{id}} no longer holds the Handover Commit, applies M3 with an
 * Administrator alert. M3 and M4 close the older pull request with a reason comment and "(lần {n})" in its
 * title, once, and leave one GitHub shows merged as it is. GitHub and git hosting are {@link GitHubStub}; the
 * sandbox jobs run in process; the preview check is {@link ScriptedPreviewChecks}.
 */
class CodeHandoverIT extends IntegrationTest {

	private static final Path DIRECTORY;

	private static final GitHubStub GITHUB;

	private static final AtomicLong REPOSITORY_IDS = new AtomicLong(990000);

	/** One client address per call of a run, so the exchange's abuse limits never cross tests (AD-34). */
	private static final AtomicLong ADDRESSES = new AtomicLong();

	/** The title the handover's pull request opens with: the Milestone's number and title. */
	private static final String PULL_TITLE = "Bàn giao Giai đoạn 1: Chặn đặt trùng bàn";

	/** AD-46: the one repository of the organization whose GitHub Actions may run. */
	private static final String RUNNER = "vibefix-runner";

	/** The runner's numeric ID, which the copy run's ID token names (Story 23.8a). */
	private static final long RUNNER_ID;

	/** The dev stack's exchange host, which every dispatch names (AD-44). */
	private static final String TUNNEL = "https://exchange.example.test";

	/** How long the copy operation waits before it reads its run again (Story 23.8a). */
	private static final Duration RUN_POLL = Duration.ofSeconds(11);

	static {
		try {
			DIRECTORY = Files.createTempDirectory("code-handover-");
			GITHUB = new GitHubStub(Files.createDirectories(DIRECTORY.resolve("git")));
			// AD-46: the runner the organization read-back requires (Story 23.3), which runs the handover copy.
			GITHUB.runner(RUNNER);
			RUNNER_ID = GITHUB.organizationRepositoryId(RUNNER);
			Files.writeString(DIRECTORY.resolve("app-key"), TestKeys.pkcs1());
			// The stand-in for GitHub Actions' OIDC issuer, whose ID tokens a copy run exchanges (Story 23.4).
			ActionsExchangeSupport.issue(GITHUB);
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
	private OperationWorker worker;

	@Autowired
	private ScriptedPreviewChecks previews;

	@Autowired
	private DeadlinePoller poller;

	@Autowired
	private Deadlines deadlines;

	@Autowired
	private JobFreezes freezes;

	@Autowired
	private TransactionTemplate transactions;

	@Autowired
	private ApplicationContext context;

	@Autowired
	private vn.vibefix.github.ActionsRuns runs;

	private UUID lan;

	private UUID hung;

	private UUID job;

	private UUID milestone;

	private UUID criterion;

	private UUID story;

	private String intermediary;

	private String working;

	@DynamicPropertySource
	static void github(DynamicPropertyRegistry registry) {
		registry.add("vibefix.github.api-url", () -> GITHUB.apiUrl().toString());
		registry.add("vibefix.github.git-url", () -> GITHUB.gitUrl().toString());
		registry.add("vibefix.github.platform-app.id", () -> "123456");
		registry.add("vibefix.github.platform-app.installation-id", () -> String.valueOf(GitHubStub.INSTALLATION));
		registry.add("vibefix.github.platform-app.key-file", () -> DIRECTORY.resolve("app-key").toString());
		registry.add("vibefix.github.runner-repository", () -> RUNNER);
		// Story 23.8a: the handover copy runs on the runner's Actions, which reach the exchange through the dev tunnel.
		registry.add("vibefix.github.exchange.public-url", () -> TUNNEL);
		registry.add("vibefix.github.exchange.issuer", () -> GITHUB.apiUrl().toString());
	}

	@AfterAll
	static void stop() {
		GITHUB.close();
	}

	@BeforeEach
	void anActiveCodeJobAfterItsSetup() throws Exception {
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		this.hung = TestPeople.mentor(this.jdbc, "Đỗ Mạnh Hùng", List.of(TestPeople.REACT),
				"React");
		UUID vy = TestPeople.student(this.jdbc, "Trần Thảo Vy", true, List.of(TestPeople.REACT), "React",
				LocalDate.parse("2026-09-30"));
		long id = REPOSITORY_IDS.incrementAndGet();
		String source = "so-dat-ban-" + id;
		GITHUB.source("lan-dev", source);
		UUID product = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO product.product (id, customer_account_id, name, description, ai_tools, repository_github_id,
				    repository_full_name, repository_owner_type, repository_default_branch, public_notice_version,
				    copy_permission_version, detection_notice_version, acknowledged_at, created_at)
				VALUES (:id, :owner, 'Sổ đặt bàn Quán Mây', 'Đặt bàn.', ARRAY['LOVABLE'], :github, :name, 'User', 'main',
				    '1.0', '1.0', '1.0', now(), now())
				""").param("id", product).param("owner", this.lan).param("github", id).param("name", "lan-dev/" + source).update();
		this.job = UUID.randomUUID();
		this.milestone = UUID.randomUUID();
		this.criterion = UUID.randomUUID();
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, budget, deadline, rules, selection_days, opened_at, lapses_at, mentor_account_id,
				    selected_application_id, selected_at, version, created_at, updated_at)
				VALUES (:id, :product, :owner, 'ACTIVE', 'Sửa lỗi đặt bàn', 'Lỗi', 'Đúng', 'Khách', ARRAY['BUG_FIX'],
				    'UNDER_2M', DATE '2026-12-01', '{}'::jsonb, 14, :now, :now, :mentor, :application, :now, 5, :now, :now)
				""").param("id", this.job).param("product", product).param("owner", this.lan).param("mentor", this.hung)
			.param("application", UUID.randomUUID()).param("now", now).update();
		String content = """
				{"scope":"Sửa lỗi","priceVnd":1500000,"codeJob":true,"assessment":false,"skills":[],"externalAccess":[],
				 "milestones":[{"id":"%s","title":"Chặn đặt trùng bàn","priceVnd":1500000,"dueOn":"2026-11-20","revisionRounds":2,
				   "criteria":[{"id":"%s","statement":"Hai khách không đặt trùng bàn","checkSteps":"Đặt hai lần cùng giờ"}]}]}
				""".formatted(this.milestone, this.criterion);
		this.jdbc.sql("""
				INSERT INTO job.agreement_version (job_id, version_no, status, content, author_account_id, created_at)
				VALUES (:job, 1, 'SHARED', CAST(:content AS JSONB), :mentor, :now)
				""").param("job", this.job).param("content", content).param("mentor", this.hung).param("now", now).update();
		this.jdbc.sql("""
				INSERT INTO job.confirmed_agreement (job_id, version_no, draft_version_no, content, crew_usernames,
				    freeze_committed, customer_account_id, approved_at, mentor_account_id, confirmed_at)
				VALUES (:job, 1, 1, CAST(:content AS JSONB), '{}', true, :owner, :now, :mentor, :now)
				""").param("job", this.job).param("content", content).param("owner", this.lan).param("mentor", this.hung)
			.param("now", now).update();
		this.jdbc.sql("UPDATE job.job SET agreement_version_no = 1 WHERE id = :id").param("id", this.job).update();
		this.jdbc.sql("""
				INSERT INTO job.milestone (id, job_id, position, title, price_vnd, revision_rounds, created_at, state, funded_at)
				VALUES (:id, :job, 0, 'Chặn đặt trùng bàn', 1500000, 2, now(), 'FUNDED', :funded)
				""").param("id", this.milestone).param("job", this.job)
			.param("funded", Timestamp.from(this.clock.instant().minus(Duration.ofMinutes(1)))).update();
		this.jdbc.sql("""
				INSERT INTO job.crew_row (id, job_id, member_account_id, status, signed_at, activated_at, created_at)
				VALUES (:id, :job, :student, 'ACTIVE', :signed, :signed, now())
				""").param("id", UUID.randomUUID()).param("job", this.job).param("student", vy)
			.param("signed", Timestamp.from(this.clock.instant().minus(Duration.ofMinutes(5)))).update();
		this.jdbc.sql("""
				INSERT INTO work.board (job_id, product_id, code, next_number, created_at) VALUES (:job, :product, 'SDB', 2, :now)
				""").param("job", this.job).param("product", product).param("now", now).update();
		this.story = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO work.work_item (id, job_id, number, type, title, description, criterion_id, milestone_id, status,
				    created_at, updated_at)
				VALUES (:id, :job, 1, 'STORY', 'Hai khách không đặt trùng bàn', '', :criterion, :milestone, 'DONE', now(), now())
				""").param("id", this.story).param("job", this.job).param("criterion", this.criterion)
			.param("milestone", this.milestone).update();
		this.jdbc.sql("""
				INSERT INTO work.work_item_history (id, work_item_id, job_id, actor_account_id, action, changes, comment, at)
				VALUES (:id, :item, :job, :mentor, 'APPROVED', '{}'::jsonb, 'Đạt', :now)
				""").param("id", UUID.randomUUID()).param("item", this.story).param("job", this.job).param("mentor", this.hung)
			.param("now", now).update();
		page("Hướng dẫn dùng", "CUSTOMER");
		page("Ghi chú nhóm", "TEAM");
		// The repositories as Setup leaves them (Story 23.3): two private copies at the commit Setup approved, so every
		// pull-request call and the Working head read need contents read, and the pin clones Working with a read token.
		this.intermediary = "job-" + id + "-intermediary";
		this.working = "job-" + id + "-working";
		long intermediaryId = GITHUB.platformRepository(this.intermediary, "lan-dev", source);
		long workingId = GITHUB.platformRepository(this.working, GitHubStub.ORGANIZATION, this.intermediary);
		String approved = GITHUB.head("lan-dev", source, "main");
		this.jdbc.sql("""
				INSERT INTO repo.job_setup (id, job_id, product_id, status, source_full_name, default_branch, head_sha,
				    head_read_at, approved_sha, confirmed_at, intermediary_github_id, intermediary_name, working_github_id,
				    working_name, checked_at, intermediary_at, working_at, copied_at, started_at, finished_at)
				VALUES (:id, :job, :product, 'DONE', :source, 'main', :sha, :now, :sha, :now, :intermediaryId, :intermediary,
				    :workingId, :working, :now, :now, :now, :now, :now, :now)
				""").param("id", UUID.randomUUID()).param("job", this.job).param("product", product)
			.param("source", "lan-dev/" + source).param("sha", approved).param("intermediaryId", intermediaryId)
			.param("intermediary", GitHubStub.ORGANIZATION + "/" + this.intermediary).param("workingId", workingId)
			.param("working", GitHubStub.ORGANIZATION + "/" + this.working).param("now", now).update();
		GITHUB.userIds.put("hung@example.test", 4242L);
	}

	@Test
	void theHandoverIsCopiedOnTheRunnerAndRecordedWithItsCommitsSensitiveChangesAndPages() throws Exception {
		preflight().andExpect(jsonPath("$.problems").value(Matchers.contains("NO_NEW_COMMITS")));
		// NFR-3: a commit message is text, wherever it shows.
		String message = "SDB-1 Chặn đặt trùng bàn <img src=x onerror=alert(1)>";
		GITHUB.push(GitHubStub.ORGANIZATION, this.working, "main", "src/booking.js", "export const free = true;\n", message,
				"hung@example.test");
		String head = GITHUB.push(GitHubStub.ORGANIZATION, this.working, "main", "package.json", "{\"name\":\"quan-may\"}\n",
				"Thêm thư viện nhắc lịch", "unknown@example.test");

		preflight().andExpect(jsonPath("$.problems").isEmpty())
			.andExpect(jsonPath("$.commitCount").value(2))
			.andExpect(jsonPath("$.sensitiveChanges[0].path").value("package.json"))
			.andExpect(jsonPath("$.sensitiveChanges[0].kind").value("DEPENDENCIES"));

		// Story 23.8a: "Gửi bàn giao" answers 202 with its pending submission; no handover exists yet.
		String handover = JsonPath.read(handOver(5).andExpect(status().isAccepted())
			.andExpect(jsonPath("$.state").value("PENDING"))
			.andExpect(jsonPath("$.handoverId").doesNotExist())
			.andReturn().getResponse().getContentAsString(), "$.id");
		UUID submission = UUID.fromString(handover);
		assertThat(this.jdbc.sql("SELECT count(*) FROM delivery.handover WHERE job_id = :job").param("job", this.job)
			.query(Long.class).single()).as("no handover before the copy").isZero();
		// AD-46: the copy holds no busy marker, so acceptance, chat and work go on while the runner copies.
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/busy").with(as(this.hung, Role.MENTOR)))
			.andExpect(jsonPath("$.busy").value(false));

		dispatchCopy();
		UUID correlation = correlation(submission);
		Map<String, Object> run = GITHUB.runs.get(runId(correlation));
		assertThat(run.get("repository_full_name")).isEqualTo(GitHubStub.ORGANIZATION + "/" + RUNNER);
		assertThat(inputs(run)).containsEntry("source_repository", GitHubStub.ORGANIZATION + "/" + this.working)
			.containsEntry("target_repository", GitHubStub.ORGANIZATION + "/" + this.intermediary)
			.containsEntry("target_branch", "handover/" + submission)
			.containsEntry("sha", head)
			.containsEntry("from_sha", startSha());
		copyRunSucceeds(submission, head, List.of("src/booking.js", "package.json"));

		assertThat(GITHUB.head(GitHubStub.ORGANIZATION, this.intermediary, "handover/" + submission)).isEqualTo(head);
		assertThat(submissionState(submission)).isEqualTo("DONE");
		this.mvc.perform(get("/api/v1/boards/" + this.job + "/handovers/submission").with(as(this.hung, Role.MENTOR)))
			.andExpect(jsonPath("$.state").value("DONE"))
			.andExpect(jsonPath("$.handoverId").value(handover));
		int pull = pull(submission);
		assertThat(GITHUB.pullHeads.get(this.intermediary)).containsEntry(pull, "handover/" + submission);
		assertThat(this.jdbc.sql("SELECT author_github_id FROM delivery.handover_commit WHERE handover_id = :id ORDER BY position")
			.param("id", submission).query(Long.class).list()).containsExactly(4242L, null);
		assertThat(this.jdbc.sql("SELECT count(*) FROM work.work_item_commit WHERE work_item_id = :item").param("item", this.story)
			.query(Long.class).single()).isOne();
		assertThat(this.jdbc.sql("SELECT state FROM job.milestone WHERE id = :id").param("id", this.milestone)
			.query(String.class).single()).isEqualTo("SUBMITTED");
		assertThat(this.jdbc.sql("SELECT commits_truncated FROM delivery.handover WHERE id = :id").param("id", submission)
			.query(Boolean.class).single()).as("a reported list is never truncated").isFalse();
		this.mvc.perform(get("/api/v1/handovers/" + handover).with(as(this.hung, Role.MENTOR)))
			.andExpect(jsonPath("$.code.handoverCommit").value(head))
			.andExpect(jsonPath("$.code.commits.length()").value(2))
			.andExpect(jsonPath("$.code.commits[0].message").value(message))
			.andExpect(jsonPath("$.code.commits[0].workItemKeys[0]").value("SDB-1"))
			.andExpect(jsonPath("$.code.unattributed").value(1))
			.andExpect(jsonPath("$.preview.preview.domain").value("so-dat-ban-gd1.vercel.app"))
			.andExpect(jsonPath("$.preview.preview.checkedAt").exists())
			.andExpect(jsonPath("$.code.pages.length()").value(2));
		this.mvc.perform(get("/api/v1/handovers/" + handover).with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.preview.preview.url").value("https://so-dat-ban-gd1.vercel.app"))
			.andExpect(jsonPath("$.preview.preview.checkedAt").doesNotExist())
			.andExpect(jsonPath("$.code.pages.length()").value(1))
			.andExpect(jsonPath("$.code.pages[0].title").value("Hướng dẫn dùng"))
			.andExpect(jsonPath("$.code.sensitiveChanges[0].kind").value("DEPENDENCIES"))
			.andExpect(jsonPath("$.code.sensitiveChangeCount").value(1));
	}

	/** The form's own hint: GitHub's compare shows the change, so "Gửi bàn giao" never starts a copy. */
	@Test
	void aChangeTheCompareShowsUnderTheWorkflowsFolderIsRefusedOnTheForm() throws Exception {
		GITHUB.push(GitHubStub.ORGANIZATION, this.working, "main", ".github/workflows/ci.yml", "on: [push, pull_request]\n",
				"Chạy kiểm thử", "hung@example.test");

		preflight().andExpect(jsonPath("$.problems").value(Matchers.contains("WORKFLOW_FILES_CHANGED")));
		handOver(5).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.reason").value("WORKFLOW_FILES_CHANGED"));
		assertThat(this.jdbc.sql("SELECT count(*) FROM delivery.handover_submission").query(Long.class).single()).isZero();
		assertThat(GITHUB.pullHeads.getOrDefault(this.intermediary, Map.of())).isEmpty();
	}

	/**
	 * FR-32, N-16: GitHub's compare lists 300 files at most, so a workflow change can hide past it. The run reads the
	 * whole range with {@code git diff --quiet} and reports it; the submission is refused and nothing is pushed.
	 */
	@Test
	void aWorkflowChangeHiddenBehind300ListedFilesIsRefusedByTheRunsReport() throws Exception {
		Map<String, String> files = padding("pad");
		files.put(".github/workflows/ci.yml", "on: [push, pull_request]\n");
		String head = GITHUB.push(GitHubStub.ORGANIZATION, this.working, "main", files, List.of(), "Chạy kiểm thử",
				"hung@example.test");

		preflight().andExpect(jsonPath("$.problems").isEmpty());
		UUID submission = UUID.fromString(JsonPath.read(handOver(5).andExpect(status().isAccepted())
			.andReturn().getResponse().getContentAsString(), "$.id"));
		dispatchCopy();
		copyRunReports(submission, true, List.of(".github/workflows/ci.yml"));
		GITHUB.completeRun(runId(correlation(submission)), "failure");
		pollCopy();

		assertThat(submissionState(submission)).isEqualTo("REFUSED:WORKFLOW_FILES_CHANGED");
		assertThat(this.jdbc.sql("SELECT count(*) FROM delivery.handover WHERE job_id = :job").param("job", this.job)
			.query(Long.class).single()).isZero();
		assertThat(GITHUB.pullHeads.getOrDefault(this.intermediary, Map.of())).isEmpty();
		assertThat(GITHUB.head(GitHubStub.ORGANIZATION, this.intermediary, "handover/" + submission)).isNull();
		this.mvc.perform(get("/api/v1/boards/" + this.job + "/handovers/submission").with(as(this.hung, Role.MENTOR)))
			.andExpect(jsonPath("$.state").value("REFUSED"))
			.andExpect(jsonPath("$.reason").value("WORKFLOW_FILES_CHANGED"))
			.andExpect(jsonPath("$.reasonModule").value("delivery"));
		assertThat(head).isNotBlank();
	}

	/** FR-32: the Sensitive Changes come from the run's full list, not from the compare's 300. */
	@Test
	void theSensitiveChangesComeFromTheRunsFullList() throws Exception {
		Map<String, String> files = padding("pad");
		files.put("package.json", "{\"name\":\"quan-may\"}\n");
		String head = GITHUB.push(GitHubStub.ORGANIZATION, this.working, "main", files, List.of(), "Dọn dẹp",
				"hung@example.test");
		preflight().andExpect(jsonPath("$.sensitiveChanges").isEmpty());

		UUID submission = UUID.fromString(JsonPath.read(handOver(5).andExpect(status().isAccepted())
			.andReturn().getResponse().getContentAsString(), "$.id"));
		dispatchCopy();
		copyRunSucceeds(submission, head, List.of("-pad/000.txt", "package.json"));

		assertThat(this.jdbc.sql("""
				SELECT path || ' ' || kind FROM delivery.handover_sensitive_change WHERE handover_id = :id ORDER BY position
				""").param("id", submission).query(String.class).list()).containsExactly("package.json DEPENDENCIES");
	}

	/** A run that failed after its attempts, and one the exchange refused, each end the submission with its reason. */
	@Test
	void aFailedRunEndsTheSubmissionWithItsReasonAndNoHandover() throws Exception {
		String head = GITHUB.push(GitHubStub.ORGANIZATION, this.working, "main", "src/booking.js", "export const free = true;\n",
				"Sửa lỗi", "hung@example.test");
		UUID submission = UUID.fromString(JsonPath.read(handOver(5).andExpect(status().isAccepted())
			.andReturn().getResponse().getContentAsString(), "$.id"));

		for (int attempt = 1; attempt <= 8 && "PENDING".equals(submissionState(submission)); attempt++) {
			dispatchCopy();
			GITHUB.completeRun(runId(correlation(submission)), "failure");
			pollCopy();
			this.clock.advance(Duration.ofHours(1));
		}
		awaitPublications();

		assertThat(submissionState(submission)).isEqualTo("REFUSED:RUN_FAILED");
		assertThat(this.jdbc.sql("SELECT count(*) FROM delivery.handover WHERE job_id = :job").param("job", this.job)
			.query(Long.class).single()).isZero();
		assertThat(this.jdbc.sql("""
				SELECT status FROM operations.external_operation WHERE type = 'repo.handover_copy'
				    AND payload ->> 'submissionId' = :id
				""").param("id", submission.toString()).query(String.class).single()).as("FR-64").isEqualTo("FAILED");
		assertThat(head).isNotBlank();
		// The Mentor sends it again once the runner works.
		preflight().andExpect(jsonPath("$.problems").isEmpty());
	}

	/** A push to Working after the submission changes nothing: the run copies exactly the Handover Commit. */
	@Test
	void theRunCopiesTheCommitTheMentorSentEvenAfterAPush() throws Exception {
		String head = GITHUB.push(GitHubStub.ORGANIZATION, this.working, "main", "src/booking.js", "export const free = true;\n",
				"Sửa lỗi", "hung@example.test");
		UUID submission = UUID.fromString(JsonPath.read(handOver(5).andExpect(status().isAccepted())
			.andReturn().getResponse().getContentAsString(), "$.id"));
		dispatchCopy();
		GITHUB.push(GitHubStub.ORGANIZATION, this.working, "main", "src/booking.js", "export const free = 'later';\n",
				"Sửa thêm", "hung@example.test");

		copyRunSucceeds(submission, head, List.of("src/booking.js"));

		assertThat(handoverCommit(submission)).isEqualTo(head);
		assertThat(GITHUB.head(GitHubStub.ORGANIZATION, this.intermediary, "handover/" + submission)).isEqualTo(head);
	}

	/** M2 refused at the end of the copy: the submission ends with its reason and the pull request is closed again. */
	@Test
	void whenM2RefusesTheSubmissionEndsAndThePullRequestIsClosedAgain() throws Exception {
		String head = GITHUB.push(GitHubStub.ORGANIZATION, this.working, "main", "src/booking.js", "export const free = true;\n",
				"Sửa lỗi", "hung@example.test");
		UUID submission = UUID.fromString(JsonPath.read(handOver(5).andExpect(status().isAccepted())
			.andReturn().getResponse().getContentAsString(), "$.id"));
		dispatchCopy();
		// The Job's version moved while the runner copied, so M2 answers STALE.
		this.jdbc.sql("UPDATE job.job SET version = version + 1 WHERE id = :id").param("id", this.job).update();

		copyRunSucceeds(submission, head, List.of("src/booking.js"));

		assertThat(submissionState(submission)).isEqualTo("REFUSED:STATE_CHANGED");
		this.mvc.perform(get("/api/v1/boards/" + this.job + "/handovers/submission").with(as(this.hung, Role.MENTOR)))
			.andExpect(jsonPath("$.reason").value("STATE_CHANGED"))
			// M2's own reason, which the screen reads in job's catalogue, not delivery's.
			.andExpect(jsonPath("$.reasonModule").value("job"));
		assertThat(this.jdbc.sql("SELECT count(*) FROM delivery.handover WHERE job_id = :job").param("job", this.job)
			.query(Long.class).single()).isZero();
		assertThat(GITHUB.pullHeads.get(this.intermediary)).hasSize(1);
		int pull = GITHUB.pullHeads.get(this.intermediary).keySet().iterator().next();
		assertThat(GITHUB.closedPulls).contains(this.intermediary + "#" + pull);
	}

	/** A report vibefix refuses is no report: the run fails, and the handover is never recorded from a part list. */
	@Test
	void aSecondReportOfOneRunIsARefusedReplay() throws Exception {
		String head = GITHUB.push(GitHubStub.ORGANIZATION, this.working, "main", "src/booking.js", "export const free = true;\n",
				"Sửa lỗi", "hung@example.test");
		UUID submission = UUID.fromString(JsonPath.read(handOver(5).andExpect(status().isAccepted())
			.andReturn().getResponse().getContentAsString(), "$.id"));
		dispatchCopy();
		UUID correlation = correlation(submission);
		exchangeIdToken(correlation);

		report(correlation, false, List.of("src/booking.js")).andExpect(status().isNoContent());
		report(correlation, true, List.of(".github/workflows/ci.yml")).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.reason").value("REPLAY"));

		GITHUB.copyCommit(GitHubStub.ORGANIZATION, this.working, head, this.intermediary, "handover/" + submission);
		GITHUB.completeRun(runId(correlation), "success");
		pollCopy();
		awaitHandover(submission);
		assertThat(this.jdbc.sql("""
				SELECT path FROM delivery.handover_sensitive_change WHERE handover_id = :id
				""").param("id", submission).query(String.class).list()).as("the first report decides").isEmpty();
	}

	@Test
	void noWorkflowsFolderAtEitherEndIsAllowed() throws Exception {
		// Setup copied a commit without the folder.
		startAt(GITHUB.push(GitHubStub.ORGANIZATION, this.working, "main", Map.of(), List.of(".github/workflows"),
				"Bỏ thư mục cấu hình", "hung@example.test"));
		GITHUB.push(GitHubStub.ORGANIZATION, this.working, "main", "src/booking.js", "export const free = true;\n", "Sửa lỗi",
				"hung@example.test");

		preflight().andExpect(jsonPath("$.problems").isEmpty());
		handOver(5).andExpect(status().isAccepted());
	}

	@Test
	void whenGitHubCannotReadTheRangeTheRepositoryIsUnreadableAndNothingIsSubmitted() throws Exception {
		GITHUB.push(GitHubStub.ORGANIZATION, this.working, "main", "src/booking.js", "export const free = true;\n", "Sửa lỗi",
				"hung@example.test");

		GITHUB.unavailable.add("GET /repos/" + GitHubStub.ORGANIZATION + "/" + this.working + "/compare/");
		try {
			preflight().andExpect(jsonPath("$.problems").value(Matchers.contains("REPOSITORY_UNREADABLE")));
			handOver(5).andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.reason").value("REPOSITORY_UNREADABLE"));
		}
		finally {
			GITHUB.unavailable.remove("GET /repos/" + GitHubStub.ORGANIZATION + "/" + this.working + "/compare/");
		}
		assertThat(this.jdbc.sql("SELECT count(*) FROM delivery.handover_submission").query(Long.class).single()).isZero();
		assertThat(GITHUB.pullHeads.getOrDefault(this.intermediary, Map.of())).isEmpty();
		preflight().andExpect(jsonPath("$.problems").isEmpty());
	}

	/** One copy at a time: a second "Gửi bàn giao" while the runner copies is refused. */
	@Test
	void aSecondHandoverWhileTheRunnerCopiesIsRefused() throws Exception {
		GITHUB.push(GitHubStub.ORGANIZATION, this.working, "main", "src/booking.js", "export const free = true;\n", "Sửa lỗi",
				"hung@example.test");
		handOver(5).andExpect(status().isAccepted());

		preflight().andExpect(jsonPath("$.problems").value(Matchers.contains("HANDOVER_COPYING")));
		handOver(5).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.reason").value("HANDOVER_COPYING"));
	}

	/**
	 * The workflow's new-branch guard refuses every later run once {@code handover/{id}} exists, so a run that
	 * pushed and then failed is not copied again: the next attempt reads the branch back and carries on.
	 */
	@Test
	void aRunThatPushedAndThenFailedReachesThePullRequestOnTheNextAttempt() throws Exception {
		String head = GITHUB.push(GitHubStub.ORGANIZATION, this.working, "main", "src/booking.js",
				"export const free = true;\n", "Sửa lỗi", "hung@example.test");
		UUID submission = UUID.fromString(JsonPath.read(handOver(5).andExpect(status().isAccepted())
			.andReturn().getResponse().getContentAsString(), "$.id"));
		dispatchCopy();
		UUID first = correlation(submission);
		copyRunReports(submission, false, List.of("src/booking.js"));
		GITHUB.copyCommit(GitHubStub.ORGANIZATION, this.working, head, this.intermediary, "handover/" + submission);
		// The run pushed, then its own read-back step failed, so GitHub shows it failed.
		GITHUB.completeRun(runId(first), "failure");
		pollCopy();
		assertThat(submissionState(submission)).as("the attempt is spent, not refused").isEqualTo("PENDING");

		this.clock.advance(Duration.ofMinutes(5));
		this.worker.runDue(Lane.GITHUB_LONG);
		awaitSubmission(submission);

		assertThat(correlation(submission)).as("no second run was dispatched").isEqualTo(first);
		assertThat(submissionState(submission)).isEqualTo("DONE");
		assertThat(GITHUB.pullHeads.get(this.intermediary)).containsEntry(pull(submission), "handover/" + submission);
		assertThat(handoverCommit(submission)).isEqualTo(head);
	}

	/** AD-46: a run that says success but left another commit on the branch opens nothing and records nothing. */
	@Test
	void aSuccessfulRunWhoseBranchHoldsAnotherCommitIsNotReadBack() throws Exception {
		String head = GITHUB.push(GitHubStub.ORGANIZATION, this.working, "main", "src/booking.js",
				"export const free = true;\n", "Sửa lỗi", "hung@example.test");
		String other = GITHUB.push(GitHubStub.ORGANIZATION, this.working, "main", "src/booking.js",
				"export const free = 'other';\n", "Sửa thêm", "hung@example.test");
		UUID submission = UUID.fromString(JsonPath.read(handOver(5).andExpect(status().isAccepted())
			.andReturn().getResponse().getContentAsString(), "$.id"));
		dispatchCopy();
		copyRunReports(submission, false, List.of("src/booking.js"));
		// The run pushed another commit than the Handover Commit, so the read-back differs.
		GITHUB.copyCommit(GitHubStub.ORGANIZATION, this.working, head, this.intermediary, "handover/" + submission);
		GITHUB.completeRun(runId(correlation(submission)), "success");
		pollCopy();

		assertThat(other).isNotEqualTo(head);
		assertThat(handoverCommitOf(submission)).as("the Mentor sent the later head").isEqualTo(other);
		assertThat(runConclusion(submission)).isEqualTo("not_read_back");
		assertThat(submissionState(submission)).isEqualTo("PENDING");
		assertThat(GITHUB.pullHeads.getOrDefault(this.intermediary, Map.of())).isEmpty();
		assertThat(this.jdbc.sql("SELECT count(*) FROM delivery.handover WHERE job_id = :job").param("job", this.job)
			.query(Long.class).single()).isZero();
	}

	/** A correlation the exchange refused ends the submission: a second run would be refused the same way. */
	@Test
	void aCorrelationTheExchangeRefusesEndsTheSubmission() throws Exception {
		GITHUB.push(GitHubStub.ORGANIZATION, this.working, "main", "src/booking.js", "export const free = true;\n",
				"Sửa lỗi", "hung@example.test");
		UUID submission = UUID.fromString(JsonPath.read(handOver(5).andExpect(status().isAccepted())
			.andReturn().getResponse().getContentAsString(), "$.id"));
		dispatchCopy();
		UUID correlation = correlation(submission);
		// The run's ID token names another runner than the correlation's target, as a forged one would.
		this.mvc
			.perform(ActionsExchangeSupport.exchange(
					ActionsExchangeSupport.idToken(GITHUB, this.clock.instant(), runId(correlation), RUNNER_ID + 1),
					correlation, address()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.reason").value("REPOSITORY"));
		GITHUB.completeRun(runId(correlation), "failure");
		pollCopy();
		awaitSubmission(submission);

		assertThat(submissionState(submission)).isEqualTo("REFUSED:EXCHANGE_REFUSED");
		assertThat(GITHUB.pullHeads.getOrDefault(this.intermediary, Map.of())).isEmpty();
		assertThat(GITHUB.head(GitHubStub.ORGANIZATION, this.intermediary, "handover/" + submission)).isNull();
	}

	/** FR-32: a run that pushed but reported no readable list records no handover and opens no pull request. */
	@Test
	void aRunWithNoReadableReportEndsTheSubmissionAndOpensNoPullRequest() throws Exception {
		for (boolean corrupt : new boolean[] { false, true }) {
			String head = GITHUB.push(GitHubStub.ORGANIZATION, this.working, "main", "src/booking.js",
					"export const free = " + corrupt + ";\n", "Sửa lỗi", "hung@example.test");
			UUID submission = UUID.fromString(JsonPath.read(handOver(version()).andExpect(status().isAccepted())
				.andReturn().getResponse().getContentAsString(), "$.id"));
			dispatchCopy();
			UUID correlation = correlation(submission);
			if (corrupt) {
				// A report that was taken whole and no longer reads back: its stored bytes are not gzip any more.
				copyRunReports(submission, false, List.of("src/booking.js"));
				this.jdbc.sql("DELETE FROM github.exchange_report WHERE correlation_id = :id")
					.param("id", correlation)
					.update();
				this.jdbc.sql("""
						INSERT INTO github.exchange_report (correlation_id, workflows_changed, paths_gzip, path_count,
						    reported_at)
						VALUES (:id, FALSE, :bytes, 1, now())
						""").param("id", correlation).param("bytes", new byte[] { 1, 2, 3 }).update();
			}
			else {
				exchangeIdToken(correlation);
			}
			GITHUB.copyCommit(GitHubStub.ORGANIZATION, this.working, head, this.intermediary, "handover/" + submission);
			GITHUB.completeRun(runId(correlation), "success");
			pollCopy();
			awaitSubmission(submission);

			assertThat(submissionState(submission)).as("corrupt: %s", corrupt).isEqualTo("REFUSED:PATH_LIST_UNREADABLE");
			assertThat(this.jdbc.sql("SELECT count(*) FROM delivery.handover WHERE id = :id").param("id", submission)
				.query(Long.class).single()).isZero();
			// A list that does not read back is read before the pull request, so none was opened to close.
			assertThat(GITHUB.pullHeads.getOrDefault(this.intermediary, Map.of())).as("corrupt: %s", corrupt).isEmpty();
		}
	}

	/**
	 * The partial unique index is what keeps a second copy of one Milestone out, now that no busy marker does
	 * (AD-46): a submission the preflight did not see, as a race would leave it, is refused rather than a 500.
	 */
	@Test
	void aSecondSubmissionOfOneMilestoneIsRefusedByItsIndex() throws Exception {
		GITHUB.push(GitHubStub.ORGANIZATION, this.working, "main", "src/booking.js", "export const free = true;\n",
				"Sửa lỗi", "hung@example.test");
		// A pending submission of this Milestone under another Job, which copying(jobId) does not see.
		this.jdbc.sql("""
				INSERT INTO delivery.handover_submission (id, job_id, product_id, milestone_id, milestone_number,
				    mentor_account_id, customer_account_id, job_version, range_start, handover_commit, draft, status, created_at)
				VALUES (:id, :job, :product, :milestone, 1, :mentor, :customer, 5, :from, :commit, '{}'::JSONB, 'PENDING', now())
				""")
			.param("id", UUID.randomUUID())
			.param("job", UUID.randomUUID())
			.param("product", UUID.randomUUID())
			.param("milestone", this.milestone)
			.param("mentor", this.hung)
			.param("customer", this.lan)
			.param("from", "1f".repeat(20))
			.param("commit", "2e".repeat(20))
			.update();

		handOver(5).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.reason").value("HANDOVER_COPYING"));

		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM delivery.handover_submission WHERE job_id = :job
				""").param("job", this.job).query(Long.class).single()).as("nothing was written").isZero();
	}

	/** 300 files whose paths sort before {@code .github}, so GitHub's compare lists them and stops. */
	private static Map<String, String> padding(String content) {
		Map<String, String> files = new LinkedHashMap<>();
		for (int file = 0; file < 300; file++) {
			files.put("-pad/%03d.txt".formatted(file), content + " " + file + "\n");
		}
		return files;
	}

	/** The range starts at {@code sha}, as if Setup had copied that commit. */
	private void startAt(String sha) {
		this.jdbc.sql("UPDATE repo.job_setup SET approved_sha = :sha WHERE job_id = :job").param("sha", sha).param("job", this.job)
			.update();
	}

	/** Where the Job's first handover range starts: the commit Setup copied. */
	private String startSha() {
		return this.jdbc.sql("SELECT approved_sha FROM repo.job_setup WHERE job_id = :job").param("job", this.job)
			.query(String.class).single();
	}

	@Test
	void aBugFixNeedsAPreviewThatRespondsOrAReasonWithARecording() throws Exception {
		GITHUB.push(GitHubStub.ORGANIZATION, this.working, "main", "src/booking.js", "export const free = true;\n", "Sửa lỗi",
				"hung@example.test");
		preflight().andExpect(jsonPath("$.previewRequired").value(true));

		handOverWith("").andExpect(status().isBadRequest()).andExpect(jsonPath("$.reason").value("PREVIEW_REQUIRED"));
		handOverWith(",\"previewUrl\":\"http://so-dat-ban.vercel.app\"").andExpect(status().isBadRequest());
		handOverWith(",\"previewUrl\":\"https://a.vercel.app\",\"recordingUrl\":\"https://www.loom.com/share/x\","
				+ "\"noPreviewReason\":\"Không có máy chủ thử\"")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reason").value("PREVIEW_OR_RECORDING"));
		handOverWith(",\"recordingUrl\":\"https://www.loom.com/share/x\"").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reason").value("RECORDING_REASON_MISSING"));
		String dead = "https://dead-" + UUID.randomUUID() + ".vercel.app";
		this.previews.unreachable(dead);
		this.mvc.perform(post("/api/v1/boards/" + this.job + "/handovers/preview-check").with(as(this.hung, Role.MENTOR))
			.with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"" + dead + "\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.reachable").value(false))
			.andExpect(jsonPath("$.domain").value(dead.substring("https://".length())));
		handOverWith(",\"previewUrl\":\"" + dead + "\"").andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("PREVIEW_UNREACHABLE"));
		assertThat(GITHUB.pullHeads.getOrDefault(this.intermediary, Map.of())).as("nothing pinned").isEmpty();

		String first = upload();
		String second = upload();
		preflight().andExpect(jsonPath("$.screenshots.length()").value(2));
		UUID submission = UUID.fromString(JsonPath.read(
				handOverWith(",\"recordingUrl\":\"https://www.loom.com/share/abc\",\"noPreviewReason\":\"Ứng dụng chỉ chạy"
						+ " trên máy của quán\",\"screenshotIds\":[\"" + second + "\",\"" + first + "\"]")
					.andExpect(status().isAccepted())
					.andReturn().getResponse().getContentAsString(), "$.id"));
		dispatchCopy();
		copyRunSucceeds(submission, GITHUB.head(GitHubStub.ORGANIZATION, this.working, "main"), List.of("src/booking.js"));
		this.mvc.perform(get("/api/v1/files/" + first).with(as(this.lan, Role.CUSTOMER))).andExpect(status().isOk());
		UUID outsider = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		this.mvc.perform(get("/api/v1/files/" + first).with(as(outsider, Role.CUSTOMER))).andExpect(status().isNotFound());
		preflight().andExpect(jsonPath("$.screenshots.length()").value(0));
		this.mvc.perform(get("/api/v1/handovers/" + submission).with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.preview.preview").doesNotExist())
			.andExpect(jsonPath("$.preview.recording.domain").value("www.loom.com"))
			.andExpect(jsonPath("$.preview.noPreviewReason").value("Ứng dụng chỉ chạy trên máy của quán"))
			.andExpect(jsonPath("$.screenshots[0].fileId").value(second))
			.andExpect(jsonPath("$.screenshots.length()").value(2));
	}

	@Test
	void anUploadIsTheMentorsAloneUntilAHandoverCarriesIt() throws Exception {
		String upload = upload();
		this.mvc.perform(get("/api/v1/files/" + upload).with(as(this.hung, Role.MENTOR))).andExpect(status().isOk());
		this.mvc.perform(get("/api/v1/files/" + upload).with(as(this.lan, Role.CUSTOMER))).andExpect(status().isNotFound());
		this.mvc.perform(delete("/api/v1/boards/" + this.job + "/handovers/screenshots/" + upload).with(as(this.hung, Role.MENTOR))
			.with(csrf())).andExpect(status().isNoContent());
		this.mvc.perform(get("/api/v1/files/" + upload).with(as(this.hung, Role.MENTOR))).andExpect(status().isNotFound());
	}

	private String upload() throws Exception {
		byte[] png;
		try (var in = getClass().getResourceAsStream("/authz/bodies/upload.png")) {
			png = java.util.Objects.requireNonNull(in).readAllBytes();
		}
		String body = this.mvc.perform(multipart("/api/v1/boards/" + this.job + "/handovers/screenshots")
			.file(new MockMultipartFile("file", "dat-ban.png", "image/png", png)).with(as(this.hung, Role.MENTOR)).with(csrf()))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.fileId");
	}

	private ResultActions handOverWith(String evidence) throws Exception {
		return this.mvc.perform(post("/api/v1/boards/" + this.job + "/handovers").with(as(this.hung, Role.MENTOR)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"milestoneId\":\"" + this.milestone + "\",\"version\":5,\"criteria\":[{\"criterionId\":\"" + this.criterion
					+ "\",\"met\":true,\"note\":\"Đạt.\"}]" + evidence + "}"));
	}

	@Test
	void aPushToWorkingVoidsNothingAndOnlyAMovedHandoverBranchVoidsItAtAcceptance() throws Exception {
		UUID admin = AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương").accountId();
		UUID handover = handedOver();
		int pull = pull(handover);
		Instant checkBy = ((Timestamp) autoAcceptance(handover).get("due_at")).toInstant();

		GITHUB.push(GitHubStub.ORGANIZATION, this.working, "main", "src/booking.js", "export const free = false;\n",
				"Sửa thêm sau khi bàn giao", "hung@example.test");
		preflight();

		// D9: the team keeps working while the Customer reviews; nothing is read or voided.
		assertThat(milestoneState()).isEqualTo("SUBMITTED");
		assertThat(handoverStatus(handover)).isEqualTo("SUBMITTED");
		assertThat(autoAcceptance(handover)).containsEntry("status", "PENDING");

		// FR-33: only the handover branch no longer at the Handover Commit makes the merge answer 409.
		voidAtAcceptance(handover);

		assertThat(GITHUB.mergedPulls).doesNotContain(this.intermediary + "#" + pull);
		assertThat(milestoneState()).as("M3: back to where it was handed over from").isEqualTo("FUNDED");
		assertThat(acceptance(handover)).isEqualTo("VOIDED");
		assertThat(releases()).isZero();
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/busy").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.busy").value(false));
		org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).until(() -> openTodos("delivery.hand_over", this.hung) == 1);
		assertThat(openTodos("delivery.review_handover", this.lan)).isZero();
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM notification.notification WHERE type = 'job.handover_voided' AND account_id IN (:lan, :hung)
				""").param("lan", this.lan).param("hung", this.hung).query(Long.class).single()).isEqualTo(2);
		assertThat(this.jdbc.sql("""
				SELECT (params ->> 'n') || ' ' || (params ->> 'job') FROM notification.notification
				WHERE type = 'repo.acceptance_head_moved' AND account_id = :admin
				""").param("admin", admin).query(String.class).list()).containsExactly("1 Sửa lỗi đặt bàn");

		// R26: the voided handover's pull request is closed with M3's comment and its round; the branch stays.
		closePullRequests(handover);
		assertThat(GITHUB.closedPulls).contains(this.intermediary + "#" + pull);
		assertThat(GITHUB.pullTitles.get(this.intermediary + "#" + pull)).isEqualTo(PULL_TITLE + " (lần 1)");
		assertThat(GITHUB.pullComments.get(this.intermediary + "#" + pull)).singleElement()
			.satisfies(comment -> assertThat(comment).startsWith(HandoverPullCloses.voidComment(handover))
				.contains("handover/" + handover)
				.contains("<!-- vibefix:handover-closed:" + handover + " -->"));
		assertThat(GITHUB.head(GitHubStub.ORGANIZATION, this.intermediary, "handover/" + handover)).isNotNull();

		// The Customer's decision on the voided handover is stale, shown inline (UX-DR26).
		this.mvc.perform(get("/api/v1/handovers/" + handover).with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.status").value("VOIDED"));
		accept(handover, version()).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("STALE"))
			.andExpect(jsonPath("$.reason").value("HANDOVER_VOIDED"));
		// Its Auto-acceptance ends at its time through the generation check.
		this.clock.setInstant(checkBy.plus(Duration.ofMinutes(1)));
		this.poller.runDue();
		assertThat(autoAcceptance(handover)).containsEntry("status", "OBSOLETE");
		assertThat(milestoneState()).isEqualTo("FUNDED");
	}

	/** Auto-acceptance merges as the Customer's acceptance would, so its 409 applies M3 the same way (FR-35). */
	@Test
	void anAutoAcceptanceWhoseHandoverBranchMovedVoidsTheHandover() throws Exception {
		UUID admin = AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương").accountId();
		UUID handover = handedOver();
		moveHandoverBranch(handover);
		Instant due = ((Timestamp) autoAcceptance(handover).get("due_at")).toInstant();
		this.clock.setInstant(due.plus(Duration.ofMinutes(1)));
		this.poller.runDue();
		assertThat(autoAcceptance(handover)).containsEntry("status", "FIRED");
		assertThat(acceptance(handover)).isEqualTo("MERGING");

		this.worker.runDue(Lane.GITHUB_SHORT);

		assertThat(acceptance(handover)).isEqualTo("VOIDED");
		assertThat(milestoneState()).isEqualTo("FUNDED");
		org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).until(() -> "VOIDED".equals(handoverStatus(handover)));
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM notification.notification WHERE type = 'repo.acceptance_head_moved' AND account_id = :admin
				""").param("admin", admin).query(Long.class).single()).isOne();
		closePullRequests(handover);
		assertThat(GITHUB.pullTitles.get(this.intermediary + "#" + pull(handover))).endsWith(" (lần 1)");
	}

	/**
	 * A Dispute froze the Job between the merge's frozen check and M3: M3, the alert, and the void's notices all roll
	 * back, and the merge waits for the thaw without using an attempt.
	 */
	@Test
	void a409WhileTheJobFrozeIsTriedAgainAfterTheThaw() throws Exception {
		UUID admin = AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương").accountId();
		UUID handover = handedOver();
		int pull = pull(handover);
		moveHandoverBranch(handover);
		accept(handover, version()).andExpect(status().isOk());
		java.util.concurrent.atomic.AtomicBoolean froze = new java.util.concurrent.atomic.AtomicBoolean();
		GITHUB.beforeAnswer = request -> {
			if ("PUT".equals(request.method()) && request.path().endsWith("/pulls/" + pull + "/merge")
					&& froze.compareAndSet(false, true)) {
				this.freezes.freeze(this.job);
			}
		};
		try {
			this.worker.runDue(Lane.GITHUB_SHORT);
		}
		finally {
			GITHUB.beforeAnswer = request -> {
			};
		}

		assertThat(froze).isTrue();
		assertThat(acceptance(handover)).as("nothing resolved").isEqualTo("MERGING");
		assertThat(milestoneState()).isEqualTo("SUBMITTED");
		assertThat(handoverStatus(handover)).isEqualTo("SUBMITTED");
		assertThat(this.jdbc.sql("SELECT busy_operation_key IS NOT NULL FROM job.job WHERE id = :id").param("id", this.job)
			.query(Boolean.class).single()).as("the marker is still the merge's").isTrue();
		awaitPublications();
		assertThat(notifications("repo.acceptance_head_moved", admin)).as("the alert rolled back").isZero();
		assertThat(notifications("job.handover_voided", this.lan) + notifications("job.handover_voided", this.hung)).isZero();
		assertThat(closeOperation(handover)).as("no M3, so nothing to close").isNull();
		// Deferred while frozen: no attempt used, the next one after the frozen wait.
		assertThat(this.jdbc.sql("""
				SELECT status || ' ' || attempt_count FROM operations.external_operation
				WHERE type = 'delivery.acceptance_merge' AND payload ->> 'handoverId' = :handover AND next_attempt_at = :due
				""").param("handover", handover.toString())
			.param("due", Timestamp.from(this.clock.instant().plus(AcceptanceMergeOperation.FROZEN_WAIT)))
			.query(String.class).list()).containsExactly("PENDING 0");

		// Still frozen at that time: it waits again.
		this.clock.advance(AcceptanceMergeOperation.FROZEN_WAIT.plusMinutes(1));
		this.worker.runDue(Lane.GITHUB_SHORT);
		assertThat(acceptance(handover)).isEqualTo("MERGING");
		assertThat(milestoneState()).isEqualTo("SUBMITTED");

		this.freezes.unfreeze(this.job);
		this.clock.advance(AcceptanceMergeOperation.FROZEN_WAIT.plusMinutes(1));
		this.worker.runDue(Lane.GITHUB_SHORT);

		assertThat(acceptance(handover)).isEqualTo("VOIDED");
		assertThat(milestoneState()).isEqualTo("FUNDED");
		assertThat(mergeOperation(handover)).isEqualTo("ABORTED HEAD_MOVED");
		org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).until(() -> "VOIDED".equals(handoverStatus(handover)));
		assertThat(notifications("repo.acceptance_head_moved", admin)).isOne();
		org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).until(() -> closeOperation(handover) != null);
		awaitPublications();
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM operations.external_operation
				WHERE type = 'delivery.handover_pull_close' AND payload ->> 'handoverId' = :handover
				""").param("handover", handover.toString()).query(Long.class).single()).isOne();
	}

	/**
	 * Only GitHub's 409 means the handover branch moved: a merge GitHub refuses as not mergeable (405) or invalid
	 * (422) is a failed merge as built, never M3.
	 */
	@Test
	void aMergeRefusedWithAnythingButA409IsNoMovedHead() throws Exception {
		UUID admin = AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương").accountId();
		UUID handover = handedOver();
		accept(handover, version()).andExpect(status().isOk());
		for (int refusal : new int[] { 405, 422 }) {
			GITHUB.mergeRefusals.put(this.intermediary, refusal);
			try {
				this.clock.advance(Duration.ofHours(1));
				this.worker.runDue(Lane.GITHUB_SHORT);
			}
			finally {
				GITHUB.mergeRefusals.remove(this.intermediary);
			}
			assertThat(mergeOperation(handover)).as("HTTP %d", refusal).isEqualTo("PENDING GITHUB_UNAVAILABLE");
			assertThat(acceptance(handover)).isEqualTo("MERGING");
			assertThat(handoverStatus(handover)).isEqualTo("SUBMITTED");
			assertThat(milestoneState()).isEqualTo("SUBMITTED");
		}
		awaitPublications();
		assertThat(notifications("repo.acceptance_head_moved", admin)).isZero();
		assertThat(notifications("job.handover_voided", this.lan)).isZero();
		assertThat(closeOperation(handover)).isNull();
	}

	/** Each round's pull request gets its own round: the second revision adds "(lần 2)", the first keeps "(lần 1)". */
	@Test
	void eachClosedPullRequestCarriesItsOwnRound() throws Exception {
		UUID first = handedOver();
		requestRevision(first).andExpect(status().isOk());
		closePullRequests(first);
		String head = GITHUB.push(GitHubStub.ORGANIZATION, this.working, "main", "src/booking.js",
				"export const free = 'again';\n", "Sửa theo yêu cầu", "hung@example.test");
		preflight();
		UUID second = copied(version(), head, List.of("src/booking.js"));
		requestRevision(second).andExpect(status().isOk());
		closePullRequests(second);

		assertThat(pull(second)).isNotEqualTo(pull(first));
		assertThat(GITHUB.pullTitles.get(this.intermediary + "#" + pull(second))).isEqualTo(PULL_TITLE + " (lần 2)");
		assertThat(GITHUB.pullTitles.get(this.intermediary + "#" + pull(first))).isEqualTo(PULL_TITLE + " (lần 1)");
		assertThat(GITHUB.closedPulls).contains(this.intermediary + "#" + pull(first), this.intermediary + "#" + pull(second));
	}

	/** A default branch GitHub does not show is unreadable, never the commit Setup copied. */
	@Test
	void aMissingDefaultBranchMakesTheRepositoryUnreadable() throws Exception {
		this.jdbc.sql("UPDATE repo.job_setup SET default_branch = 'khong-co' WHERE job_id = :job").param("job", this.job)
			.update();

		preflight().andExpect(jsonPath("$.problems").value(Matchers.contains("REPOSITORY_UNREADABLE")));
	}

	@Test
	void aRevisionClosesTheOlderPullRequestWithItsCommentAndRoundAndKeepsItsBranch() throws Exception {
		UUID handover = handedOver();
		int pull = pull(handover);

		requestRevision(handover).andExpect(status().isOk());
		closePullRequests(handover);

		assertThat(milestoneState()).isEqualTo("REVISION_REQUESTED");
		assertThat(GITHUB.closedPulls).contains(this.intermediary + "#" + pull);
		assertThat(GITHUB.pullTitles.get(this.intermediary + "#" + pull)).isEqualTo(PULL_TITLE + " (lần 1)");
		assertThat(GITHUB.pullComments.get(this.intermediary + "#" + pull)).singleElement()
			.satisfies(comment -> assertThat(comment).startsWith(HandoverPullCloses.revisionComment(handover))
				.contains("<!-- vibefix:handover-closed:" + handover + " -->"));
		assertThat(GITHUB.head(GitHubStub.ORGANIZATION, this.intermediary, "handover/" + handover)).as("kept as evidence")
			.isEqualTo(handoverCommit(handover));
		assertThat(closeOperation(handover)).isEqualTo("SUCCEEDED");
	}

	@Test
	void aRetriedCloseCommentsOnceAndAddsTheRoundOnce() throws Exception {
		UUID handover = handedOver();
		int pull = pull(handover);
		requestRevision(handover).andExpect(status().isOk());
		org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).until(() -> closeOperation(handover) != null);
		String patch = "PATCH /repos/" + GitHubStub.ORGANIZATION + "/" + this.intermediary + "/pulls/";
		GITHUB.unavailable.add(patch);
		try {
			this.worker.runDue(Lane.GITHUB_SHORT);
		}
		finally {
			GITHUB.unavailable.remove(patch);
		}
		assertThat(GITHUB.pullComments.get(this.intermediary + "#" + pull)).hasSize(1);
		assertThat(GITHUB.closedPulls).doesNotContain(this.intermediary + "#" + pull);
		assertThat(closeOperation(handover)).isEqualTo("PENDING");

		this.clock.advance(Duration.ofMinutes(1));
		this.worker.runDue(Lane.GITHUB_SHORT);

		assertThat(GITHUB.pullComments.get(this.intermediary + "#" + pull)).as("the marker found the first comment").hasSize(1);
		assertThat(GITHUB.closedPulls).contains(this.intermediary + "#" + pull);
		assertThat(GITHUB.pullTitles.get(this.intermediary + "#" + pull)).isEqualTo(PULL_TITLE + " (lần 1)");
		assertThat(closeOperation(handover)).isEqualTo("SUCCEEDED");
	}

	@Test
	void aPullRequestGitHubShowsMergedIsLeftAsItIs() throws Exception {
		UUID handover = handedOver();
		int pull = pull(handover);
		GITHUB.mergedPulls.add(this.intermediary + "#" + pull);

		requestRevision(handover).andExpect(status().isOk());
		closePullRequests(handover);

		assertThat(closeOperation(handover)).isEqualTo("SUCCEEDED");
		assertThat(GITHUB.pullComments.getOrDefault(this.intermediary + "#" + pull, List.of())).isEmpty();
		assertThat(GITHUB.closedPulls).doesNotContain(this.intermediary + "#" + pull);
		assertThat(GITHUB.pullTitles.get(this.intermediary + "#" + pull)).isEqualTo(PULL_TITLE);
	}

	@Test
	void acceptingMergesExactlyTheHandoverCommitAndOnlyThenReleasesTheMilestone() throws Exception {
		UUID handover = handedOver();
		fundForRelease();
		String commit = this.jdbc.sql("SELECT handover_commit FROM delivery.handover WHERE id = :id").param("id", handover)
			.query(String.class).single();
		long version = version();

		accept(handover, version).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("SUBMITTED"))
			.andExpect(jsonPath("$.acceptance").value("MERGING"))
			.andExpect(jsonPath("$.decision").doesNotExist());
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/busy").with(as(this.hung, Role.MENTOR)))
			.andExpect(jsonPath("$.busy").value(true))
			.andExpect(jsonPath("$.purpose").value("ACCEPTANCE"))
			.andExpect(jsonPath("$.milestone").value(1));
		accept(handover, version).andExpect(jsonPath("$.code").value("JOB_BUSY"));
		assertThat(milestoneState()).as("nothing is accepted before the merge").isEqualTo("SUBMITTED");
		assertThat(releases()).isZero();

		this.worker.runDue(Lane.GITHUB_SHORT);

		int pull = this.jdbc.sql("SELECT pull_number FROM delivery.handover WHERE id = :id").param("id", handover)
			.query(Integer.class).single();
		assertThat(GITHUB.mergedPulls).contains(this.intermediary + "#" + pull);
		assertThat(GITHUB.head(GitHubStub.ORGANIZATION, this.intermediary, "main")).isEqualTo(commit);
		assertThat(milestoneState()).isEqualTo("ACCEPTED");
		assertThat(releases()).isOne();
		this.mvc.perform(get("/api/v1/handovers/" + handover).with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.status").value("ACCEPTED"))
			.andExpect(jsonPath("$.checks[0].met").value(true))
			.andExpect(jsonPath("$.acceptance").doesNotExist());
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/busy").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.busy").value(false));
		assertThat(acceptance(handover)).isEqualTo("DONE");
		assertThat(this.jdbc.sql("SELECT decided_by FROM delivery.handover WHERE id = :id").param("id", handover)
			.query(UUID.class).single()).isEqualTo(this.lan);
	}

	/** D9: the merge names the Handover Commit and never reads the Working head, so the team's push is no 409. */
	@Test
	void aPushToWorkingDuringTheAcceptanceMergesTheHandoverAsHandedOver() throws Exception {
		UUID handover = handedOver();
		fundForRelease();
		String commit = handoverCommit(handover);
		accept(handover, version()).andExpect(status().isOk());
		GITHUB.push(GitHubStub.ORGANIZATION, this.working, "main", "src/booking.js", "export const free = false;\n",
				"Sửa thêm trong lúc nghiệm thu", "hung@example.test");
		GITHUB.seen.clear();

		this.worker.runDue(Lane.GITHUB_SHORT);

		assertThat(GITHUB.mergedPulls).contains(this.intermediary + "#" + pull(handover));
		assertThat(GITHUB.head(GitHubStub.ORGANIZATION, this.intermediary, "main")).isEqualTo(commit);
		assertThat(milestoneState()).isEqualTo("ACCEPTED");
		assertThat(releases()).isOne();
		assertThat(acceptance(handover)).isEqualTo("DONE");
		assertThat(List.copyOf(GITHUB.seen)).as("the Working Repository is not read at acceptance")
			.noneMatch(request -> request.path().contains("/" + this.working + "/"));
		assertThat(List.copyOf(GITHUB.seen)).filteredOn(request -> "PUT".equals(request.method()))
			.singleElement()
			.satisfies(merge -> {
				assertThat(merge.body().path("sha").asString()).isEqualTo(commit);
				assertThat(merge.body().path("merge_method").asString()).isEqualTo("merge");
			});
	}

	@Test
	void whenGitHubCannotMergeTheAcceptanceFailsAndTheMilestoneWaitsForTheCustomer() throws Exception {
		UUID handover = handedOver();
		fundForRelease();
		long version = version();
		accept(handover, version).andExpect(status().isOk());
		String prefix = "PUT /repos/" + GitHubStub.ORGANIZATION + "/" + this.intermediary + "/pulls";
		GITHUB.unavailable.add(prefix);
		try {
			for (int attempt = 0; attempt < 8; attempt++) {
				this.jdbc.sql("""
						UPDATE operations.external_operation SET next_attempt_at = :now
						WHERE type = 'delivery.acceptance_merge' AND status = 'PENDING' AND payload ->> 'handoverId' = :handover
						""").param("now", Timestamp.from(this.clock.instant())).param("handover", handover.toString()).update();
				this.worker.runDue(Lane.GITHUB_SHORT);
			}
			assertThat(this.jdbc.sql("""
					SELECT status FROM operations.external_operation
					WHERE type = 'delivery.acceptance_merge' AND payload ->> 'handoverId' = :handover
					""").param("handover", handover.toString()).query(String.class).single()).isEqualTo("FAILED");
			org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).until(() -> "FAILED".equals(acceptance(handover)));
		}
		finally {
			GITHUB.unavailable.remove(prefix);
		}
		assertThat(milestoneState()).isEqualTo("SUBMITTED");
		assertThat(releases()).isZero();
		this.mvc.perform(get("/api/v1/handovers/" + handover).with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.status").value("SUBMITTED"))
			.andExpect(jsonPath("$.acceptance").value("FAILED"))
			.andExpect(jsonPath("$.decision").exists());
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/busy").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.busy").value(false));

		// The Customer tries again once GitHub answers.
		accept(handover, version).andExpect(status().isOk()).andExpect(jsonPath("$.acceptance").value("MERGING"));
		this.worker.runDue(Lane.GITHUB_SHORT);
		assertThat(milestoneState()).isEqualTo("ACCEPTED");
		assertThat(releases()).isOne();
	}

	/** A-02: no refusal from the busy marker, no rollback and no pushed due time; the merge waits for the thaw. */
	@Test
	void anAutoAcceptanceRacingADisputePausesWithItsTrueRemainingTimeAndMergesAfterTheThaw() throws Exception {
		UUID handover = handedOver();
		fundForRelease();
		Map<String, Object> deadline = autoAcceptance(handover);
		Instant due = ((Timestamp) deadline.get("due_at")).toInstant();
		this.clock.setInstant(due.minus(Duration.ofHours(12)));
		this.poller.runDue();

		this.clock.setInstant(due);
		DisputeTestSupport.openRacingThePoller(this.context, this.job, this.lan, (UUID) deadline.get("id"),
				due.minus(Duration.ofHours(1)));
		this.clock.setInstant(due.plus(Duration.ofMinutes(1)));
		this.poller.runDue();

		assertThat(autoAcceptance(handover)).as("no rollback loop").containsEntry("status", "PAUSED")
			.containsEntry("remaining_ms", Duration.ofHours(1).toMillis());
		assertThat(this.jdbc.sql("SELECT busy_operation_key IS NULL FROM job.job WHERE id = :id").param("id", this.job)
			.query(Boolean.class).single()).as("nothing reserved").isTrue();
		assertThat(this.jdbc.sql("SELECT count(*) FROM delivery.handover_acceptance WHERE handover_id = :id")
			.param("id", handover).query(Long.class).single()).isZero();
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM operations.external_operation WHERE type = 'delivery.acceptance_merge'
				    AND payload ->> 'handoverId' = :handover
				""").param("handover", handover.toString()).query(Long.class).single()).isZero();

		this.clock.advance(Duration.ofDays(1));
		this.transactions.executeWithoutResult(status -> {
			this.freezes.unfreeze(this.job);
			this.deadlines.unfreeze(this.job);
		});
		assertThat(autoAcceptance(handover)).containsEntry("status", "PENDING")
			.containsEntry("due_at", Timestamp.from(this.clock.instant().plus(Duration.ofHours(1))));
		this.clock.advance(Duration.ofHours(1));
		this.poller.runDue();

		assertThat(autoAcceptance(handover)).containsEntry("status", "FIRED");
		assertThat(acceptance(handover)).isEqualTo("MERGING");
		assertThat(milestoneState()).as("nothing is accepted before the merge").isEqualTo("SUBMITTED");
		this.worker.runDue(Lane.GITHUB_SHORT);
		assertThat(milestoneState()).isEqualTo("ACCEPTED");
		assertThat(releases()).isOne();
	}

	private Map<String, Object> autoAcceptance(UUID handover) {
		return this.jdbc.sql("SELECT * FROM timers.deadline WHERE subject_id = :id AND kind = 'delivery.auto_acceptance'")
			.param("id", handover).query().singleRow();
	}

	/** The handover of one commit, copied on the runner and recorded. */
	private UUID handedOver() throws Exception {
		String head = GITHUB.push(GitHubStub.ORGANIZATION, this.working, "main", "src/booking.js",
				"export const free = true;\n", "Sửa lỗi", "hung@example.test");
		preflight();
		return copied(version(), head, List.of("src/booking.js"));
	}

	/** "Gửi bàn giao" and the whole copy on the runner: the handover's ID, which is its submission's. */
	private UUID copied(long version, String head, List<String> paths) throws Exception {
		UUID submission = UUID.fromString(JsonPath.read(handOver(version)
			.andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString(), "$.id"));
		dispatchCopy();
		copyRunSucceeds(submission, head, paths);
		return submission;
	}

	/** The copy operation's first attempt: it dispatches the run on the runner. */
	private void dispatchCopy() {
		this.worker.runDue(Lane.GITHUB_LONG);
	}

	/** The copy operation's next attempt, once its run is over. */
	private void pollCopy() {
		this.clock.advance(RUN_POLL);
		this.worker.runDue(Lane.GITHUB_LONG);
		awaitPublications();
	}

	/**
	 * What the copy run on the runner does, and the stub does not: its ID token exchanged, its path report posted,
	 * the Handover Commit pushed to {@code handover/{id}}, and the run concluded. Then the operation reads it, opens
	 * the pull request, and {@code delivery} records the handover.
	 */
	private void copyRunSucceeds(UUID submission, String head, List<String> paths) throws Exception {
		copyRunReports(submission, false, paths);
		GITHUB.copyCommit(GitHubStub.ORGANIZATION, this.working, head, this.intermediary, "handover/" + submission);
		GITHUB.completeRun(runId(correlation(submission)), "success");
		pollCopy();
		awaitSubmission(submission);
	}

	/** The run exchanges its ID token and posts its path report. */
	private void copyRunReports(UUID submission, boolean workflowsChanged, List<String> paths) throws Exception {
		UUID correlation = correlation(submission);
		exchangeIdToken(correlation);
		report(correlation, workflowsChanged, paths).andExpect(status().isNoContent());
	}

	private void exchangeIdToken(UUID correlation) throws Exception {
		this.mvc
			.perform(ActionsExchangeSupport.exchange(
					ActionsExchangeSupport.idToken(GITHUB, this.clock.instant(), runId(correlation), RUNNER_ID), correlation,
					address()))
			.andExpect(status().isOk());
	}

	private ResultActions report(UUID correlation, boolean workflowsChanged, List<String> paths) throws Exception {
		return this.mvc.perform(ActionsExchangeSupport.report(
				ActionsExchangeSupport.idToken(GITHUB, this.clock.instant(), runId(correlation), RUNNER_ID), correlation,
				workflowsChanged, paths, address()));
	}

	/**
	 * AD-34: the exchange's abuse limits count per client address and per minute, in memory for the whole test
	 * JVM, while every test starts at the same instant. Each call comes from an address of its own, and the minute
	 * moves on, so one test's runs never use up another's budget.
	 */
	private String address() {
		this.clock.advance(Duration.ofSeconds(61));
		return "198.51.100." + (ADDRESSES.incrementAndGet() % 250 + 1);
	}

	/** The exchange correlation of the run dispatched last for the submission. */
	private UUID correlation(UUID submission) {
		return this.jdbc.sql("SELECT correlation_id FROM repo.handover_copy WHERE submission_id = :id")
			.param("id", submission)
			.query(UUID.class)
			.single();
	}

	private long runId(UUID correlation) {
		Long id = this.runs.correlation(correlation).orElseThrow().runId();
		assertThat(id).as("the run was found").isNotNull();
		return id;
	}

	/** The submission's state, with its reason when it was refused. */
	private String submissionState(UUID submission) {
		return this.jdbc.sql("""
				SELECT status || coalesce(':' || reason, '') FROM delivery.handover_submission WHERE id = :id
				""").param("id", submission).query(String.class).single();
	}

	private void awaitSubmission(UUID submission) {
		org.awaitility.Awaitility.await()
			.atMost(Duration.ofSeconds(15))
			.until(() -> !"PENDING".equals(submissionState(submission)));
	}

	private void awaitHandover(UUID submission) {
		org.awaitility.Awaitility.await()
			.atMost(Duration.ofSeconds(15))
			.until(() -> this.jdbc.sql("SELECT count(*) FROM delivery.handover WHERE id = :id").param("id", submission)
				.query(Long.class).single() == 1);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> inputs(Map<String, Object> run) {
		return (Map<String, Object>) run.get("inputs");
	}

	/** Someone writes to {@code handover/{id}} in the Intermediary, so it no longer holds the Handover Commit. */
	private void moveHandoverBranch(UUID handover) throws Exception {
		GITHUB.push(GitHubStub.ORGANIZATION, this.intermediary, "handover/" + handover, "src/booking.js",
				"export const free = 'moved';\n", "Đổi nhánh bàn giao", "operator@example.test");
	}

	/** The handover branch moved, then the Customer accepts: the merge answers 409 and M3 voids the handover. */
	private void voidAtAcceptance(UUID handover) throws Exception {
		moveHandoverBranch(handover);
		accept(handover, version()).andExpect(status().isOk());
		this.worker.runDue(Lane.GITHUB_SHORT);
		org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).until(() -> "VOIDED".equals(handoverStatus(handover)));
	}

	/** Waits for the close that M3 or M4 requested, then runs it. */
	private void closePullRequests(UUID handover) {
		org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).until(() -> closeOperation(handover) != null);
		this.worker.runDue(Lane.GITHUB_SHORT);
	}

	private ResultActions requestRevision(UUID handover) throws Exception {
		return this.mvc.perform(post("/api/v1/handovers/" + handover + "/revision").with(as(this.lan, Role.CUSTOMER)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"version\":" + version() + ",\"checks\":[{\"criterionId\":\"" + this.criterion
					+ "\",\"met\":false,\"comment\":\"Vẫn đặt trùng khi hai người bấm cùng lúc.\"}]}"));
	}

	/** The close operation's status, or {@code null} before it is requested. */
	private String closeOperation(UUID handover) {
		return this.jdbc.sql("""
				SELECT status FROM operations.external_operation
				WHERE type = 'delivery.handover_pull_close' AND payload ->> 'handoverId' = :handover
				""").param("handover", handover.toString()).query(String.class).optional().orElse(null);
	}

	/** Waits until every listener of the events published so far has run. */
	private void awaitPublications() {
		org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15))
			.until(() -> this.jdbc.sql("SELECT count(*) FROM platform.event_publication WHERE completion_date IS NULL")
				.query(Long.class)
				.single() == 0);
	}

	private long notifications(String type, UUID account) {
		return this.jdbc.sql("SELECT count(*) FROM notification.notification WHERE type = :type AND account_id = :account")
			.param("type", type).param("account", account).query(Long.class).single();
	}

	/** The acceptance merge's status and its last error code. */
	private String mergeOperation(UUID handover) {
		return this.jdbc.sql("""
				SELECT status || ' ' || coalesce(error_code, '-') FROM operations.external_operation
				WHERE type = 'delivery.acceptance_merge' AND payload ->> 'handoverId' = :handover
				""").param("handover", handover.toString()).query(String.class).single();
	}

	private int pull(UUID handover) {
		return this.jdbc.sql("SELECT pull_number FROM delivery.handover WHERE id = :id").param("id", handover)
			.query(Integer.class).single();
	}

	/** The Handover Commit the submission carries, before any handover row exists. */
	private String handoverCommitOf(UUID submission) {
		return this.jdbc.sql("SELECT handover_commit FROM delivery.handover_submission WHERE id = :id")
			.param("id", submission)
			.query(String.class)
			.single();
	}

	/** What the copy last read of its run. */
	private String runConclusion(UUID submission) {
		return this.jdbc.sql("SELECT run_conclusion FROM repo.handover_copy WHERE submission_id = :id")
			.param("id", submission)
			.query(String.class)
			.single();
	}

	private String handoverCommit(UUID handover) {
		return this.jdbc.sql("SELECT handover_commit FROM delivery.handover WHERE id = :id").param("id", handover)
			.query(String.class).single();
	}

	private String handoverStatus(UUID handover) {
		return this.jdbc.sql("SELECT status FROM delivery.handover WHERE id = :id").param("id", handover).query(String.class)
			.single();
	}

	/** The Milestone's funding in the ledger. */
	private void fundForRelease() {
		UUID product = this.jdbc.sql("SELECT product_id FROM job.job WHERE id = :id").param("id", this.job).query(UUID.class)
			.single();
		this.jdbc.sql("""
				INSERT INTO payment.ledger_account (milestone_id, job_id, product_id, created_at) VALUES (:id, :job, :product, now())
				""").param("id", this.milestone).param("job", this.job).param("product", product).update();
		this.jdbc.sql("""
				INSERT INTO payment.ledger_entry (id, operation_key, entry_type, bucket, job_id, product_id, milestone_id, amount_vnd,
				    vnp_transaction_no, created_at)
				VALUES (:id, :key, 'FUND', 'MILESTONE', :job, :product, :milestone, 1500000, :no, now())
				""").param("id", UUID.randomUUID()).param("key", "FUND:" + UUID.randomUUID()).param("job", this.job)
			.param("product", product).param("milestone", this.milestone)
			.param("no", String.valueOf(REPOSITORY_IDS.incrementAndGet() * 7919)).update();
	}

	private ResultActions accept(UUID handover, long version) throws Exception {
		return this.mvc.perform(post("/api/v1/handovers/" + handover + "/accept").with(as(this.lan, Role.CUSTOMER)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"version\":" + version + ",\"checks\":[{\"criterionId\":\"" + this.criterion + "\",\"met\":true}]}"));
	}

	private long version() {
		return this.jdbc.sql("SELECT version FROM job.job WHERE id = :id").param("id", this.job).query(Long.class).single();
	}

	private String milestoneState() {
		return this.jdbc.sql("SELECT state FROM job.milestone WHERE id = :id").param("id", this.milestone).query(String.class)
			.single();
	}

	private long releases() {
		return this.jdbc.sql("SELECT count(*) FROM payment.ledger_entry WHERE entry_type = 'RELEASE_POOLED' AND job_id = :job")
			.param("job", this.job).query(Long.class).single();
	}

	private String acceptance(UUID handover) {
		return this.jdbc.sql("""
				SELECT status FROM delivery.handover_acceptance WHERE handover_id = :id ORDER BY seq DESC LIMIT 1
				""").param("id", handover).query(String.class).single();
	}

	private long openTodos(String key, UUID account) {
		return this.jdbc.sql("SELECT count(*) FROM notification.todo WHERE todo_key = :key AND account_id = :id AND closed_at IS NULL")
			.param("key", key).param("id", account).query(Long.class).single();
	}

	private void page(String title, String visibility) {
		UUID page = UUID.randomUUID();
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO work.page (id, job_id, title, version_no, created_at, updated_at, visibility)
				VALUES (:id, :job, :title, 1, :now, :now, :visibility)
				""").param("id", page).param("job", this.job).param("title", title).param("now", now)
			.param("visibility", visibility).update();
		this.jdbc.sql("""
				INSERT INTO work.page_version (page_id, version_no, title, body, created_at)
				VALUES (:id, 1, :title, 'Nội dung', :now)
				""").param("id", page).param("title", title).param("now", now).update();
	}

	private ResultActions preflight() throws Exception {
		return this.mvc.perform(get("/api/v1/boards/" + this.job + "/handovers/preflight").param("milestoneId",
				this.milestone.toString()).with(as(this.hung, Role.MENTOR)));
	}

	private ResultActions handOver(long version) throws Exception {
		return this.mvc.perform(post("/api/v1/boards/" + this.job + "/handovers").with(as(this.hung, Role.MENTOR)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"milestoneId\":\"" + this.milestone + "\",\"version\":" + version + ",\"criteria\":[{\"criterionId\":\""
					+ this.criterion + "\",\"met\":true,\"note\":\"Đạt: không còn đặt trùng.\"}],"
					+ "\"previewUrl\":\"https://so-dat-ban-gd1.vercel.app\"}"));
	}

	private RequestPostProcessor as(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

}
