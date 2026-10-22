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
import org.springframework.mock.web.MockMultipartFile;
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
 * and the Pages updated. When M2 refuses, the pull request is closed again. A Bug fixing handover carries a
 * preview that responds, or a reason with a recording, and up to five screenshots (Story 8.3). A push
 * after the handover voids it through M3 (Story 8.4). GitHub and
 * git hosting are {@link GitHubStub}; the sandbox jobs run in process; the preview check is
 * {@link ScriptedPreviewChecks}.
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

	@Autowired
	private ScriptedPreviewChecks previews;

	@Autowired
	private HandoverVoids voids;

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
			.andExpect(jsonPath("$.preview.preview.domain").value("so-dat-ban-gd1.vercel.app"))
			.andExpect(jsonPath("$.preview.preview.checkedAt").exists())
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
			.andExpect(jsonPath("$.preview.preview.url").value("https://so-dat-ban-gd1.vercel.app"))
			.andExpect(jsonPath("$.preview.preview.checkedAt").doesNotExist())
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

	@Test
	void aBugFixNeedsAPreviewThatRespondsOrAReasonWithARecording() throws Exception {
		GITHUB.push(GitHubStub.ORGANIZATION, this.working, "main", "src/booking.js", "export const free = true;\n", "Sửa lỗi",
				"hung@example.test");
		preflight().andExpect(jsonPath("$.previewRequired").value(true));
		this.worker.runDue(Lane.SCAN);

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
		String report = handOverWith(",\"recordingUrl\":\"https://www.loom.com/share/abc\",\"noPreviewReason\":\"Ứng dụng chỉ chạy"
				+ " trên máy của quán\",\"screenshotIds\":[\"" + second + "\",\"" + first + "\"]")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.preview.preview").doesNotExist())
			.andExpect(jsonPath("$.preview.recording.domain").value("www.loom.com"))
			.andExpect(jsonPath("$.preview.noPreviewReason").value("Ứng dụng chỉ chạy trên máy của quán"))
			.andExpect(jsonPath("$.screenshots[0].fileId").value(second))
			.andReturn().getResponse().getContentAsString();
		String handover = com.jayway.jsonpath.JsonPath.read(report, "$.id");
		this.mvc.perform(get("/api/v1/files/" + first).with(as(this.lan, Role.CUSTOMER))).andExpect(status().isOk());
		UUID outsider = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null, null);
		this.mvc.perform(get("/api/v1/files/" + first).with(as(outsider, Role.CUSTOMER))).andExpect(status().isNotFound());
		preflight().andExpect(jsonPath("$.screenshots.length()").value(0));
		this.mvc.perform(get("/api/v1/handovers/" + handover).with(as(this.lan, Role.CUSTOMER)))
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
		try (var in = getClass().getResourceAsStream("/authz/bodies/student-card.png")) {
			png = java.util.Objects.requireNonNull(in).readAllBytes();
		}
		String body = this.mvc.perform(multipart("/api/v1/boards/" + this.job + "/handovers/screenshots")
			.file(new MockMultipartFile("file", "dat-ban.png", "image/png", png)).with(as(this.hung, Role.MENTOR)).with(csrf()))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		return com.jayway.jsonpath.JsonPath.read(body, "$.fileId");
	}

	private ResultActions handOverWith(String evidence) throws Exception {
		return this.mvc.perform(post("/api/v1/boards/" + this.job + "/handovers").with(as(this.hung, Role.MENTOR)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"milestoneId\":\"" + this.milestone + "\",\"version\":5,\"criteria\":[{\"criterionId\":\"" + this.criterion
					+ "\",\"met\":true,\"note\":\"Đạt.\"}]" + evidence + "}"));
	}

	@Test
	void aPushAfterTheHandoverVoidsItAndTheCustomerCanNoLongerDecideOnIt() throws Exception {
		GITHUB.push(GitHubStub.ORGANIZATION, this.working, "main", "src/booking.js", "export const free = true;\n", "Sửa lỗi",
				"hung@example.test");
		preflight();
		this.worker.runDue(Lane.SCAN);
		String report = handOver(5).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		UUID handover = UUID.fromString(com.jayway.jsonpath.JsonPath.read(report, "$.id"));
		long version = this.jdbc.sql("SELECT version FROM job.job WHERE id = :id").param("id", this.job).query(Long.class).single();

		GITHUB.push(GitHubStub.ORGANIZATION, this.working, "main", "src/booking.js", "export const free = false;\n",
				"Sửa thêm sau khi bàn giao", "hung@example.test");
		// The Mentor's next look reads the head back; repo tells delivery it moved.
		preflight();

		org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).until(() -> "VOIDED".equals(this.jdbc
			.sql("SELECT status FROM delivery.handover WHERE id = :id").param("id", handover).query(String.class).single()));
		assertThat(this.jdbc.sql("SELECT state FROM job.milestone WHERE id = :id").param("id", this.milestone)
			.query(String.class).single()).as("back to where it was handed over from").isEqualTo("FUNDED");
		org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).until(() -> openTodos("delivery.hand_over", this.hung) == 1);
		assertThat(openTodos("delivery.review_handover", this.lan)).isZero();
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM notification.notification WHERE type = 'job.handover_voided' AND account_id IN (:lan, :hung)
				""").param("lan", this.lan).param("hung", this.hung).query(Long.class).single()).isEqualTo(2);
		this.mvc.perform(get("/api/v1/handovers/" + handover).with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.status").value("VOIDED"));
		this.mvc.perform(post("/api/v1/handovers/" + handover + "/accept").with(as(this.lan, Role.CUSTOMER)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"version\":" + (version + 1) + ",\"checks\":[{\"criterionId\":\"" + this.criterion + "\",\"met\":true}]}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("STALE"))
			.andExpect(jsonPath("$.reason").value("HANDOVER_VOIDED"));
	}

	@Test
	void theTenMinuteLookVoidsAHandoverWhoseRecordedHeadMoved() throws Exception {
		GITHUB.push(GitHubStub.ORGANIZATION, this.working, "main", "src/booking.js", "export const free = true;\n", "Sửa lỗi",
				"hung@example.test");
		preflight();
		this.worker.runDue(Lane.SCAN);
		String report = handOver(5).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		UUID handover = UUID.fromString(com.jayway.jsonpath.JsonPath.read(report, "$.id"));
		this.voids.sweep();
		assertThat(this.jdbc.sql("SELECT state FROM job.milestone WHERE id = :id").param("id", this.milestone)
			.query(String.class).single()).as("the same head changes nothing").isEqualTo("SUBMITTED");

		this.jdbc.sql("UPDATE repo.branch_head SET sha = :sha WHERE job_id = :job AND ref = 'refs/heads/main'")
			.param("sha", "f".repeat(40)).param("job", this.job).update();
		this.voids.sweep();

		assertThat(this.jdbc.sql("SELECT state FROM job.milestone WHERE id = :id").param("id", this.milestone)
			.query(String.class).single()).isEqualTo("FUNDED");
		org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).until(() -> "VOIDED".equals(this.jdbc
			.sql("SELECT status FROM delivery.handover WHERE id = :id").param("id", handover).query(String.class).single()));
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
		assertThat(releases()).isEqualTo(2);
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

	@Test
	void aHeadThatMovedBeforeTheMergeVoidsTheHandoverAndReleasesNothing() throws Exception {
		UUID handover = handedOver();
		fundForRelease();
		accept(handover, version()).andExpect(status().isOk());
		GITHUB.push(GitHubStub.ORGANIZATION, this.working, "main", "src/booking.js", "export const free = false;\n",
				"Sửa thêm trong lúc nghiệm thu", "hung@example.test");

		this.worker.runDue(Lane.GITHUB_SHORT);

		assertThat(GITHUB.mergedPulls).noneMatch(merged -> merged.startsWith(this.intermediary + "#"));
		assertThat(milestoneState()).as("M3: back to where it was handed over from").isEqualTo("FUNDED");
		assertThat(releases()).isZero();
		assertThat(acceptance(handover)).isEqualTo("VOIDED");
		org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).until(() -> "VOIDED".equals(this.jdbc
			.sql("SELECT status FROM delivery.handover WHERE id = :id").param("id", handover).query(String.class).single()));
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/busy").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.busy").value(false));
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
		assertThat(releases()).isEqualTo(2);
	}

	/** The handover of one scanned commit. */
	private UUID handedOver() throws Exception {
		GITHUB.push(GitHubStub.ORGANIZATION, this.working, "main", "src/booking.js", "export const free = true;\n", "Sửa lỗi",
				"hung@example.test");
		preflight();
		this.worker.runDue(Lane.SCAN);
		String report = handOver(5).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return UUID.fromString(com.jayway.jsonpath.JsonPath.read(report, "$.id"));
	}

	/** Crew Terms version 1 (the Mentor 70, Vy 30) and the Milestone's funding in the ledger. */
	private void fundForRelease() {
		UUID product = this.jdbc.sql("SELECT product_id FROM job.job WHERE id = :id").param("id", this.job).query(UUID.class)
			.single();
		Map<String, Object> row = this.jdbc.sql("SELECT id, student_account_id FROM job.crew_row WHERE job_id = :id")
			.param("id", this.job).query().singleRow();
		this.jdbc.sql("""
				INSERT INTO job.crew_terms_version (job_id, version_no, status, cap_percent, author_account_id, created_at)
				VALUES (:job, 1, 'IN_FORCE', 100, :mentor, now())
				""").param("job", this.job).param("mentor", this.hung).update();
		this.jdbc.sql("""
				INSERT INTO job.crew_terms_share (job_id, version_no, party_account_id, crew_row_id, percent)
				VALUES (:job, 1, :mentor, NULL, 70), (:job, 1, :vy, :row, 30)
				""").param("job", this.job).param("mentor", this.hung).param("vy", row.get("student_account_id"))
			.param("row", row.get("id")).update();
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
		return this.jdbc.sql("SELECT count(*) FROM payment.ledger_entry WHERE entry_type = 'RELEASE' AND job_id = :job")
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
