package vn.vibefix.delivery.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.jayway.jsonpath.JsonPath;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.job.JobTransitioned;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.team.internal.TeamTestSupport;

/**
 * FR-10, FR-32, AD-4, AD-23, AD-33, AD-39, SG-25: the Mentor hands a Funded Assessment Milestone over
 * once every Work Item is approved or explained and each criterion is marked with a note; M2 makes it
 * Submitted on the loaded version, tells the Customer, and moves the to-dos; the Delivery Report keeps
 * the Report version handed over; the Customer reads no Work Item note; only the Mentor hands over.
 */
class HandoverIT extends IntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private DeliveryListeners listeners;

	private UUID lan;

	private UUID hung;

	private UUID vy;

	private UUID job;

	private UUID milestone;

	private UUID criterion;

	private UUID story;

	private UUID task;

	/** The preview link a Bug fixing handover carries (Story 8.3); none for an Assessment. */
	private String previewUrl;

	@Test
	void theMentorHandsOverAnAssessmentMilestoneWithTheReportVersionOfThatMoment() throws Exception {
		world("ASSESSMENT");
		String page = JsonPath.read(this.mvc.perform(post("/api/v1/boards/" + this.job + "/pages").with(as(this.hung, Role.MENTOR))
			.with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"Báo cáo đánh giá\",\"body\":\"## Hiện trạng\\n\\nBản bàn giao.\",\"template\":\"ASSESSMENT_REPORT\"}"))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");

		this.mvc.perform(get("/api/v1/boards/" + this.job + "/handovers/preflight").param("milestoneId", this.milestone.toString())
			.with(as(this.hung, Role.MENTOR)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.problems").isEmpty())
			.andExpect(jsonPath("$.version").value(5))
			.andExpect(jsonPath("$.criteria[0].statement").value("Báo cáo nêu đủ chín mục"))
			.andExpect(jsonPath("$.workItems[?(@.key == 'BCD-1')].approved").value(true))
			.andExpect(jsonPath("$.workItems[?(@.key == 'BCD-2')].approved").value(false))
			.andExpect(jsonPath("$.report.versionNo").value(1));
		this.mvc.perform(get("/api/v1/boards/" + this.job + "/handovers").with(as(this.hung, Role.MENTOR)))
			.andExpect(jsonPath("$.waiting.number").value(1))
			.andExpect(jsonPath("$.handovers").isEmpty());

		handOver(this.hung, Role.MENTOR, 5, "Đạt, đã kiểm tra.", null, null).andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("WORK_ITEMS_NOT_DONE"))
			.andExpect(jsonPath("$.n").value(1));
		handOver(this.hung, Role.MENTOR, 5, " ", "Phần phụ lục làm ở giai đoạn sau.", null).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reason").value("CRITERION_MARK_MISSING"));
		handOver(this.hung, Role.MENTOR, 4, "Đạt, đã kiểm tra.", "Phần phụ lục làm ở giai đoạn sau.", null)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("STALE"));
		handOver(this.vy, Role.STUDENT, 5, "Đạt.", "Sau.", null).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.reason").value("MENTOR_ONLY"));
		handOver(this.lan, Role.CUSTOMER, 5, "Đạt.", "Sau.", null).andExpect(status().isForbidden());
		assertThat(count("SELECT count(*) FROM delivery.handover")).isZero();

		String handover = JsonPath.read(handOver(this.hung, Role.MENTOR, 5, "Đạt, đã kiểm tra.", "Phần phụ lục làm ở giai đoạn sau.",
				null)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("SUBMITTED"))
			.andExpect(jsonPath("$.round").value(1))
			.andExpect(jsonPath("$.criteria[0].met").value(true))
			.andExpect(jsonPath("$.criteria[0].note").value("Đạt, đã kiểm tra."))
			.andExpect(jsonPath("$.criteria[0].checkSteps").value("Mở báo cáo, đếm mục"))
			.andExpect(jsonPath("$.itemNotes[0].key").value("BCD-2"))
			.andExpect(jsonPath("$.report.versionNo").value(1))
			.andReturn().getResponse().getContentAsString(), "$.id");
		assertThat(this.jdbc.sql("SELECT state || ':' || handover_id FROM job.milestone WHERE id = :id")
			.param("id", this.milestone).query(String.class).single()).isEqualTo("SUBMITTED:" + handover);
		assertThat(count("SELECT version FROM job.job WHERE id = '" + this.job + "'")).isEqualTo(6);
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type = 'job.milestone_submitted' AND account_id = '"
				+ this.lan + "'")).isOne();
		assertThat(openTodos("delivery.review_handover", this.lan)).isOne();
		assertThat(openTodos("delivery.hand_over", this.hung)).isZero();
		assertThat(this.jdbc.sql("SELECT deadline_at FROM notification.todo WHERE todo_key = 'delivery.review_handover'")
			.query(Timestamp.class).single().toInstant()).isEqualTo(this.clock.instant().plus(Duration.ofDays(7)));

		this.mvc.perform(put("/api/v1/pages/" + page).with(as(this.hung, Role.MENTOR)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"Báo cáo đánh giá\",\"body\":\"## Hiện trạng\\n\\nĐã sửa sau bàn giao.\",\"version\":1}"))
			.andExpect(status().isOk());
		this.mvc.perform(get("/api/v1/handovers/" + handover).with(as(this.lan, Role.CUSTOMER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.report.versionNo").value(1))
			.andExpect(jsonPath("$.report.body").value(Matchers.containsString("Bản bàn giao.")))
			.andExpect(jsonPath("$.itemNotes").isEmpty())
			.andExpect(jsonPath("$.mentorName").value("Đỗ Mạnh Hùng"))
			.andExpect(content -> assertThat(content.getResponse().getContentAsString()).doesNotContain("Phần phụ lục", "BCD-2"));
		this.mvc.perform(get("/api/v1/handovers/" + handover).with(as(this.vy, Role.STUDENT)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.itemNotes[0].note").value("Phần phụ lục làm ở giai đoạn sau."));
		UUID stranger = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		this.mvc.perform(get("/api/v1/handovers/" + handover).with(as(stranger, Role.CUSTOMER))).andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/boards/" + this.job + "/handovers").with(as(this.hung, Role.MENTOR)))
			.andExpect(jsonPath("$.waiting").doesNotExist())
			.andExpect(jsonPath("$.handovers[0].id").value(handover));
		handOver(this.hung, Role.MENTOR, 7, "Đạt.", "Sau.", null).andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("MILESTONE_NOT_HELD"));
	}

	@Test
	void notesPassTheSensitiveCheckAndTheReportMustExist() throws Exception {
		world("ASSESSMENT");
		handOver(this.hung, Role.MENTOR, 5, "Đạt.", "Sau.", null).andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("REPORT_MISSING"));
		this.mvc.perform(post("/api/v1/boards/" + this.job + "/pages").with(as(this.hung, Role.MENTOR)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"Báo cáo đánh giá\",\"body\":\"\",\"template\":\"ASSESSMENT_REPORT\"}"))
			.andExpect(status().isCreated());
		handOver(this.hung, Role.MENTOR, 5, "Khóa thử " + fakeToken(), "Sau.", null).andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("SENSITIVE_CONTENT"))
			.andExpect(jsonPath("$.matches[0].kind").value("CREDENTIAL"));
		handOver(this.hung, Role.MENTOR, 5, "Khóa thử " + fakeToken(), "Sau.", "MASK").andExpect(status().isCreated());
		assertThat(this.jdbc.sql("SELECT note FROM delivery.handover_criterion").query(String.class).single())
			.startsWith("Khóa thử ").doesNotContain(fakeToken());
	}

	@Test
	void aCodeJobWaitsForItsHandoverCommit() throws Exception {
		world("BUG_FIX");
		this.previewUrl = "https://so-dat-ban.vercel.app";
		this.mvc.perform(get("/api/v1/boards/" + this.job + "/handovers/preflight").param("milestoneId", this.milestone.toString())
			.with(as(this.hung, Role.MENTOR)))
			.andExpect(jsonPath("$.problems").value(Matchers.hasItem("HANDOVER_COMMIT_MISSING")));
		handOver(this.hung, Role.MENTOR, 5, "Đạt.", "Sau.", null).andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("HANDOVER_COMMIT_MISSING"));
		assertThat(count("SELECT count(*) FROM delivery.handover")).isZero();
	}

	@Test
	void aFundedMilestoneOpensTheHandoverToDoDueAtTheEndOfItsDueDate() {
		world("ASSESSMENT");
		this.listeners.onJobTransitioned(new JobTransitioned(UUID.randomUUID(), UUID.randomUUID(), this.job, "M1", "SYSTEM",
				"PLANNED", "FUNDED", 6, 1, Map.of("milestoneId", this.milestone.toString(), "number", "1")));
		await().atMost(Duration.ofSeconds(10)).until(() -> openTodos("delivery.hand_over", this.hung) == 1);
		assertThat(this.jdbc.sql("SELECT deadline_at FROM notification.todo WHERE todo_key = 'delivery.hand_over'")
			.query(Timestamp.class).single().toInstant()).isEqualTo(Instant.parse("2026-11-20T17:00:00Z"));
	}

	/**
	 * An Active Job of {@code type} with Milestone 1 Funded and two Work Items on it: the seeded Story,
	 * approved, and a Task in progress. Vy is an active Crew member.
	 */
	private void world(String type) {
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		this.hung = TeamTestSupport.mentor(this.jdbc, "Đỗ Mạnh Hùng",
				List.of(TeamTestSupport.REACT), "React");
		this.vy = TeamTestSupport.student(this.jdbc, "Trần Thảo Vy", true, List.of(TeamTestSupport.REACT), "React",
				LocalDate.parse("2026-09-30"));
		UUID product = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO product.product (id, customer_account_id, name, description, ai_tools, repository_github_id,
				    repository_full_name, repository_owner_type, repository_default_branch, public_notice_version,
				    copy_permission_version, detection_notice_version, acknowledged_at, created_at)
				VALUES (:id, :owner, 'Báo cáo đặt bàn', 'Đặt bàn.', ARRAY['LOVABLE'], :github, 'lan/so-dat-ban', 'User', 'main',
				    '1.0', '1.0', '1.0', now(), now())
				""").param("id", product).param("owner", this.lan)
			.param("github", ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE)).update();
		this.job = UUID.randomUUID();
		this.milestone = UUID.randomUUID();
		this.criterion = UUID.randomUUID();
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, budget, deadline, rules, selection_days, opened_at, lapses_at, mentor_account_id,
				    selected_application_id, selected_at, version, created_at, updated_at)
				VALUES (:id, :product, :owner, 'ACTIVE', 'Đánh giá sổ đặt bàn', 'Lỗi', 'Đúng', 'Khách', ARRAY[:type],
				    'UNDER_2M', DATE '2026-12-01', '{}'::jsonb, 14, :now, :now, :mentor, :application, :now, 5, :now, :now)
				""").param("id", this.job).param("product", product).param("owner", this.lan).param("type", type)
			.param("mentor", this.hung).param("application", UUID.randomUUID()).param("now", now).update();
		String content = """
				{"scope":"Đánh giá","priceVnd":1500000,"codeJob":%s,"assessment":%s,"skills":[],"externalAccess":[],
				 "milestones":[{"id":"%s","title":"Báo cáo","priceVnd":1500000,"dueOn":"2026-11-20","revisionRounds":2,
				   "criteria":[{"id":"%s","statement":"Báo cáo nêu đủ chín mục","checkSteps":"Mở báo cáo, đếm mục"}]}]}
				""".formatted(!"ASSESSMENT".equals(type), "ASSESSMENT".equals(type), this.milestone, this.criterion);
		this.jdbc.sql("""
				INSERT INTO job.agreement_version (job_id, version_no, status, content, author_account_id, created_at)
				VALUES (:job, 1, 'SHARED', CAST(:content AS JSONB), :mentor, :now)
				""").param("job", this.job).param("content", content).param("mentor", this.hung).param("now", now).update();
		this.jdbc.sql("""
				INSERT INTO job.confirmed_agreement (job_id, version_no, draft_version_no, content, crew_usernames,
				    freeze_committed, customer_account_id, approved_at, mentor_account_id, confirmed_at)
				VALUES (:job, 1, 1, CAST(:content AS JSONB), '{}', false, :owner, :now, :mentor, :now)
				""").param("job", this.job).param("content", content).param("owner", this.lan).param("mentor", this.hung)
			.param("now", now).update();
		this.jdbc.sql("UPDATE job.job SET agreement_version_no = 1 WHERE id = :id").param("id", this.job).update();
		this.jdbc.sql("""
				INSERT INTO job.milestone (id, job_id, position, title, price_vnd, revision_rounds, created_at, state, funded_at,
				    crew_terms_version_no)
				VALUES (:id, :job, 0, 'Báo cáo', 1500000, 2, now(), 'FUNDED', now(), 1)
				""").param("id", this.milestone).param("job", this.job).update();
		this.jdbc.sql("""
				INSERT INTO job.crew_row (id, job_id, member_account_id, status, signed_at, activated_at, created_at)
				VALUES (:id, :job, :student, 'ACTIVE', :signed, :signed, now())
				""").param("id", UUID.randomUUID()).param("job", this.job).param("student", this.vy)
			.param("signed", Timestamp.from(this.clock.instant().minus(Duration.ofMinutes(5)))).update();
		this.jdbc.sql("""
				INSERT INTO work.board (job_id, product_id, code, next_number, created_at) VALUES (:job, :product, 'BCD', 3, :now)
				""").param("job", this.job).param("product", product).param("now", now).update();
		this.story = item(1, "STORY", "Báo cáo nêu đủ chín mục", "DONE", this.criterion);
		this.task = item(2, "TASK", "Phụ lục thuật ngữ", "IN_PROGRESS", null);
		this.jdbc.sql("""
				INSERT INTO work.work_item_history (id, work_item_id, job_id, actor_account_id, action, changes, comment, at)
				VALUES (:id, :item, :job, :mentor, 'APPROVED', '{}'::jsonb, 'Đạt yêu cầu', :now)
				""").param("id", UUID.randomUUID()).param("item", this.story).param("job", this.job).param("mentor", this.hung)
			.param("now", now).update();
	}

	private UUID item(int number, String type, String title, String status, UUID criterionId) {
		UUID id = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO work.work_item (id, job_id, number, type, title, description, criterion_id, milestone_id, status,
				    created_at, updated_at)
				VALUES (:id, :job, :number, :type, :title, '', :criterion, :milestone, :status, now(), now())
				""").param("id", id).param("job", this.job).param("number", number).param("type", type).param("title", title)
			.param("criterion", criterionId).param("milestone", this.milestone).param("status", status).update();
		return id;
	}

	private ResultActions handOver(UUID account, Role role, long version, String criterionNote, String taskNote,
			String sensitive) throws Exception {
		String json = "{\"milestoneId\":\"" + this.milestone + "\",\"version\":" + version + ",\"criteria\":[{\"criterionId\":\""
				+ this.criterion + "\",\"met\":true,\"note\":\"" + criterionNote + "\"}]"
				+ (taskNote == null ? "" : ",\"itemNotes\":[{\"workItemId\":\"" + this.task + "\",\"note\":\"" + taskNote + "\"}]")
				+ (sensitive == null ? "" : ",\"sensitive\":\"" + sensitive + "\"")
				+ (this.previewUrl == null ? "" : ",\"previewUrl\":\"" + this.previewUrl + "\"") + "}";
		return this.mvc.perform(post("/api/v1/boards/" + this.job + "/handovers").with(as(account, role)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(json));
	}

	private long count(String sql) {
		return this.jdbc.sql(sql).query(Long.class).single();
	}

	private long openTodos(String key, UUID account) {
		return this.jdbc.sql("SELECT count(*) FROM notification.todo WHERE todo_key = :key AND account_id = :id AND closed_at IS NULL")
			.param("key", key).param("id", account).query(Long.class).single();
	}

	/** A GitHub token shape built at run time, so no credential-shaped literal sits in the source. */
	private static String fakeToken() {
		String alphabet = "Q7mZ2xK9pL4vB8nR3tY6wC1dF5gH0jSaEuIoNcXbVk";
		StringBuilder token = new StringBuilder("ghp").append('_');
		for (int i = 0; i < 36; i++) {
			token.append(alphabet.charAt((i * 7 + 3) % alphabet.length()));
		}
		return token.toString();
	}

	private RequestPostProcessor as(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

}
