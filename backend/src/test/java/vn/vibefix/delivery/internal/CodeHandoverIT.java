package vn.vibefix.delivery.internal;

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

import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import vn.vibefix.github.internal.GitHubStub;
import vn.vibefix.github.internal.TestKeys;
import vn.vibefix.identity.MentorApprovalStatus;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.operations.Lane;
import vn.vibefix.operations.internal.OperationWorker;
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.team.internal.TeamTestSupport;

/**
 * Story 8.2 (FR-8, FR-23, FR-32, AD-11, AD-18, AD-33, NFR-3): a Code Job's handover covers the commits on
 * the Working Repository's default branch since the commit Setup copied. It needs them scanned by a push
 * scan, at least one of them, and no change under {@code .github/workflows/}. "Gửi bàn giao" pins the head
 * on {@code handover/{id}} in the Intermediary Repository with the System's pull request, applies M2, and
 * records each commit with its author's GitHub ID and the Work Items it mentions, the Sensitive Changes,
 * and the Pages updated. When M2 refuses, the pull request is closed again. GitHub and git hosting are
 * {@link GitHubStub}; the sandbox jobs run in process.
 */
class CodeHandoverIT extends IntegrationTest {

	private static final Path DIRECTORY;

	private static final GitHubStub GITHUB;

	private static final AtomicLong REPOSITORY_IDS = new AtomicLong(990000);

	static {
		try {
			DIRECTORY = Files.createTempDirectory("code-handover-");
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
	private OperationWorker worker;

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
	}

	@AfterAll
	static void stop() {
		GITHUB.close();
	}

	@BeforeEach
	void anActiveCodeJobAfterItsSetup() throws Exception {
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null, null);
		this.hung = TeamTestSupport.mentor(this.jdbc, MentorApprovalStatus.APPROVED, "Đỗ Mạnh Hùng", List.of(TeamTestSupport.REACT),
				"React");
		UUID vy = TeamTestSupport.student(this.jdbc, "Trần Thảo Vy", true, List.of(TeamTestSupport.REACT), "React",
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
				INSERT INTO job.milestone (id, job_id, position, title, price_vnd, revision_rounds, created_at, state, funded_at,
				    crew_terms_version_no)
				VALUES (:id, :job, 0, 'Chặn đặt trùng bàn', 1500000, 2, now(), 'FUNDED', now(), 1)
				""").param("id", this.milestone).param("job", this.job).update();
		this.jdbc.sql("""
				INSERT INTO job.crew_row (id, job_id, student_account_id, status, signed_at, activated_at, created_at)
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
		// The repositories as Setup left them: the copy and its fork, at the commit the J7 scan approved.
		this.intermediary = "job-" + id + "-intermediary";
		this.working = "job-" + id + "-working";
		long intermediaryId = GITHUB.platformRepository(this.intermediary, "lan-dev", source);
		long workingId = GITHUB.platformRepository(this.working, GitHubStub.ORGANIZATION, this.intermediary);
		String approved = GITHUB.head("lan-dev", source, "main");
		this.jdbc.sql("""
				INSERT INTO repo.job_setup (id, job_id, product_id, status, source_full_name, default_branch, approved_sha,
				    intermediary_github_id, intermediary_name, working_github_id, working_name, checked_at, intermediary_at,
				    working_at, started_at, finished_at)
				VALUES (:id, :job, :product, 'DONE', :source, 'main', :sha, :intermediaryId, :intermediary, :workingId, :working,
				    :now, :now, :now, :now, :now)
				""").param("id", UUID.randomUUID()).param("job", this.job).param("product", product)
			.param("source", "lan-dev/" + source).param("sha", approved).param("intermediaryId", intermediaryId)
			.param("intermediary", GitHubStub.ORGANIZATION + "/" + this.intermediary).param("workingId", workingId)
			.param("working", GitHubStub.ORGANIZATION + "/" + this.working).param("now", now).update();
		this.jdbc.sql("""
				INSERT INTO repo.branch_head (repository_github_id, ref, job_id, sha, updated_at)
				VALUES (:id, 'refs/heads/main', :job, :sha, :now)
				""").param("id", workingId).param("job", this.job).param("sha", approved).param("now", now).update();
		GITHUB.userIds.put("hung@example.test", 4242L);
	}

	@Test
	void theHeadIsScannedPinnedAndHandedOverWithItsCommitsSensitiveChangesAndPages() throws Exception {
		preflight().andExpect(jsonPath("$.problems").value(Matchers.contains("NO_NEW_COMMITS")));
		// NFR-3: a commit message is text, wherever it shows.
		String message = "SDB-1 Chặn đặt trùng bàn <img src=x onerror=alert(1)>";
		GITHUB.push(GitHubStub.ORGANIZATION, this.working, "main", "src/booking.js", "export const free = true;\n", message,
				"hung@example.test");
		String head = GITHUB.push(GitHubStub.ORGANIZATION, this.working, "main", "package.json", "{\"name\":\"quan-may\"}\n",
				"Thêm thư viện nhắc lịch", "unknown@example.test");

		preflight().andExpect(jsonPath("$.problems").value(Matchers.contains("COMMITS_NOT_SCANNED")));
		this.worker.runDue(Lane.SCAN);
		preflight().andExpect(jsonPath("$.problems").isEmpty())
			.andExpect(jsonPath("$.commitCount").value(2))
			.andExpect(jsonPath("$.sensitiveChanges[0].path").value("package.json"))
			.andExpect(jsonPath("$.sensitiveChanges[0].kind").value("DEPENDENCIES"));

		String report = handOver(5).andExpect(status().isCreated())
			.andExpect(jsonPath("$.code.handoverCommit").value(head))
			.andExpect(jsonPath("$.code.commits.length()").value(2))
			.andExpect(jsonPath("$.code.commits[0].message").value(message))
			.andExpect(jsonPath("$.code.commits[0].workItemKeys[0]").value("SDB-1"))
			.andExpect(jsonPath("$.code.unattributed").value(1))
			.andReturn().getResponse().getContentAsString();
		String handover = com.jayway.jsonpath.JsonPath.read(report, "$.id");
		int pull = com.jayway.jsonpath.JsonPath.read(report, "$.code.pullNumber");

		assertThat(GITHUB.head(GitHubStub.ORGANIZATION, this.intermediary, "handover/" + handover)).isEqualTo(head);
		assertThat(GITHUB.pullHeads.get(this.intermediary)).containsEntry(pull, "handover/" + handover);
		assertThat(this.jdbc.sql("SELECT author_github_id FROM delivery.handover_commit WHERE handover_id = :id ORDER BY position")
			.param("id", UUID.fromString(handover)).query(Long.class).list()).containsExactly(4242L, null);
		assertThat(this.jdbc.sql("SELECT count(*) FROM work.work_item_commit WHERE work_item_id = :item").param("item", this.story)
			.query(Long.class).single()).isOne();
		assertThat(this.jdbc.sql("SELECT state FROM job.milestone WHERE id = :id").param("id", this.milestone)
			.query(String.class).single()).isEqualTo("SUBMITTED");
		this.mvc.perform(get("/api/v1/handovers/" + handover).with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.code.pages.length()").value(1))
			.andExpect(jsonPath("$.code.pages[0].title").value("Hướng dẫn dùng"))
			.andExpect(jsonPath("$.code.sensitiveChanges[0].kind").value("DEPENDENCIES"));
		this.mvc.perform(get("/api/v1/handovers/" + handover).with(as(this.hung, Role.MENTOR)))
			.andExpect(jsonPath("$.code.pages.length()").value(2));
	}

	@Test
	void aChangeUnderTheWorkflowsFolderIsRefusedAndNothingIsPinned() throws Exception {
		GITHUB.push(GitHubStub.ORGANIZATION, this.working, "main", ".github/workflows/ci.yml", "on: [push, pull_request]\n",
				"Chạy kiểm thử", "hung@example.test");
		preflight();
		this.worker.runDue(Lane.SCAN);

		preflight().andExpect(jsonPath("$.problems").value(Matchers.contains("WORKFLOW_FILES_CHANGED")));
		handOver(5).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.reason").value("WORKFLOW_FILES_CHANGED"));
		assertThat(this.jdbc.sql("SELECT count(*) FROM delivery.handover WHERE job_id = :job").param("job", this.job)
			.query(Long.class).single()).isZero();
		assertThat(GITHUB.pullHeads.getOrDefault(this.intermediary, Map.of())).isEmpty();
	}

	@Test
	void whenM2RefusesThePullRequestIsClosedAgain() throws Exception {
		GITHUB.push(GitHubStub.ORGANIZATION, this.working, "main", "src/booking.js", "export const free = true;\n", "Sửa lỗi",
				"hung@example.test");
		preflight();
		this.worker.runDue(Lane.SCAN);

		handOver(4).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE"));

		assertThat(GITHUB.pullHeads.get(this.intermediary)).hasSize(1);
		int pull = GITHUB.pullHeads.get(this.intermediary).keySet().iterator().next();
		assertThat(GITHUB.closedPulls).contains(this.intermediary + "#" + pull);
		assertThat(this.jdbc.sql("SELECT count(*) FROM delivery.handover WHERE job_id = :job").param("job", this.job)
			.query(Long.class).single()).isZero();
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
					+ this.criterion + "\",\"met\":true,\"note\":\"Đạt: không còn đặt trùng.\"}]}"));
	}

	private RequestPostProcessor as(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

}
