package vn.vibefix.delivery.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.job.JobTransitioned;
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.team.internal.TeamTestSupport;

/**
 * FR-11, FR-9: M5 on an Assessment Job opens the Mentor's follow-up to-do; the Mentor drafts follow-up
 * Jobs citing the accepted Report's numbered recommendations and submits them; the Customer opens each as
 * a Draft Job Post or discards it, and nothing is published without their own J1.
 */
class FollowUpIT extends IntegrationTest {

	private static final String REPORT = """
			# Báo cáo đánh giá

			## Đề xuất

			Xếp theo thứ tự ưu tiên.

			1. Sửa lỗi hai khách đặt trùng bàn.
			2. Thêm nhắc lịch trước giờ hẹn.
			3. Viết hướng dẫn cho nhân viên.

			## Giải thích thuật ngữ

			- **Đặt bàn**: giữ chỗ.
			""";

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

	@Test
	void theMentorDraftsFromTheAcceptedReportAndTheCustomerOpensOrDiscards() throws Exception {
		people();
		UUID job = job("ACCEPTED");
		this.listeners.onAssessmentAccepted(new JobTransitioned(UUID.randomUUID(), UUID.randomUUID(), job, "M5", "USER",
				"SUBMITTED", "ACCEPTED", 9, 1, Map.of()));
		await().atMost(Duration.ofSeconds(10)).until(() -> openTodo("delivery.draft_follow_ups", this.hung, job));

		read(this.hung, Role.MENTOR, job).andExpect(status().isOk())
			.andExpect(jsonPath("$.available").value(true))
			.andExpect(jsonPath("$.reportVersionNo").value(1))
			.andExpect(jsonPath("$.recommendations.length()").value(3))
			.andExpect(jsonPath("$.recommendations[1].text").value("Thêm nhắc lịch trước giờ hẹn."))
			.andExpect(jsonPath("$.skillIds[0]").value(TeamTestSupport.REACT));
		read(this.vy, Role.STUDENT, job).andExpect(status().isNotFound());
		create(this.hung, Role.MENTOR, job, 7, "Không có").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reason").value("RECOMMENDATION_UNKNOWN"));
		create(this.lan, Role.CUSTOMER, job, 1, "Không được").andExpect(status().isForbidden())
			.andExpect(jsonPath("$.reason").value("MENTOR_ONLY"));

		create(this.hung, Role.MENTOR, job, 1, "Sửa lỗi đặt trùng bàn").andExpect(status().isCreated());
		String body = create(this.hung, Role.MENTOR, job, 3, "Hướng dẫn nhân viên").andExpect(status().isCreated())
			.andExpect(jsonPath("$.followUps.length()").value(2))
			.andReturn()
			.getResponse()
			.getContentAsString();
		String second = JsonPath.read(body, "$.followUps[1].id");
		String extra = JsonPath.read(create(this.hung, Role.MENTOR, job, 2, "Nhắc lịch").andReturn().getResponse()
			.getContentAsString(), "$.followUps[1].id");
		this.mvc.perform(delete("/api/v1/follow-ups/" + extra).with(as(this.hung, Role.MENTOR)).with(csrf()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.followUps.length()").value(2));
		this.mvc.perform(put("/api/v1/follow-ups/" + second).with(as(this.hung, Role.MENTOR)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(content(3, "Viết hướng dẫn cho nhân viên", "DOCUMENTATION")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.followUps[1].title").value("Viết hướng dẫn cho nhân viên"));
		read(this.lan, Role.CUSTOMER, job).andExpect(jsonPath("$.followUps.length()").value(0))
			.andExpect(jsonPath("$.submitted").value(false));

		submit(this.hung, job).andExpect(status().isOk()).andExpect(jsonPath("$.submitted").value(true));
		submit(this.hung, job).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("FOLLOW_UPS_SUBMITTED"));
		create(this.hung, Role.MENTOR, job, 2, "Muộn").andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("FOLLOW_UPS_SUBMITTED"));
		assertThat(openTodo("delivery.draft_follow_ups", this.hung, job)).isFalse();
		assertThat(openTodo("delivery.approve_follow_ups", this.lan, job)).isTrue();
		assertThat(this.jdbc.sql("""
				SELECT params ->> 'n' FROM notification.notification WHERE type = 'delivery.follow_up_drafts_ready'
				""").query(String.class).single()).isEqualTo("2");

		String customerView = read(this.lan, Role.CUSTOMER, job).andExpect(jsonPath("$.followUps.length()").value(2))
			.andReturn()
			.getResponse()
			.getContentAsString();
		String first = JsonPath.read(customerView, "$.followUps[0].id");
		this.mvc.perform(post("/api/v1/follow-ups/" + first + "/open").with(as(this.hung, Role.MENTOR)).with(csrf()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.reason").value("CUSTOMER_ONLY"));
		String opened = this.mvc.perform(post("/api/v1/follow-ups/" + first + "/open").with(as(this.lan, Role.CUSTOMER))
			.with(csrf())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		String draft = JsonPath.read(opened, "$.draftJobId");
		assertThat(this.jdbc.sql("""
				SELECT state || ':' || title || ':' || array_to_string(job_types, ',') || ':' || (problem IS NOT NULL)
				FROM job.job WHERE id = :id
				""").param("id", UUID.fromString(draft)).query(String.class).single())
			.isEqualTo("DRAFT:Sửa lỗi đặt trùng bàn:BUG_FIX:true");
		this.mvc.perform(post("/api/v1/follow-ups/" + first + "/open").with(as(this.lan, Role.CUSTOMER)).with(csrf()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("FOLLOW_UP_NOT_AWAITING"));
		assertThat(openTodo("delivery.approve_follow_ups", this.lan, job)).isTrue();
		this.mvc.perform(post("/api/v1/follow-ups/" + second + "/discard").with(as(this.lan, Role.CUSTOMER)).with(csrf()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.followUps[0].status").value("OPENED"))
			.andExpect(jsonPath("$.followUps[0].draftJobId").value(draft))
			.andExpect(jsonPath("$.followUps[1].status").value("DISCARDED"));
		assertThat(openTodo("delivery.approve_follow_ups", this.lan, job)).isFalse();
	}

	@Test
	void nothingIsDraftedBeforeTheReportIsAccepted() throws Exception {
		people();
		UUID job = job("SUBMITTED");
		this.listeners.onAssessmentAccepted(new JobTransitioned(UUID.randomUUID(), UUID.randomUUID(), job, "M5", "USER",
				"SUBMITTED", "ACCEPTED", 9, 1, Map.of()));
		read(this.hung, Role.MENTOR, job).andExpect(jsonPath("$.available").value(false))
			.andExpect(jsonPath("$.recommendations.length()").value(0));
		create(this.hung, Role.MENTOR, job, 1, "Sớm").andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("REPORT_NOT_ACCEPTED"));
		assertThat(openTodo("delivery.draft_follow_ups", this.hung, job)).isFalse();
	}

	@Test
	void theRecommendationsAreTheNumberedItemsUnderDeXuat() {
		assertThat(FollowUps.parse(REPORT)).extracting(FollowUps.Recommendation::number).containsExactly(1, 2, 3);
		assertThat(FollowUps.parse("## Hiện trạng\n\n1. Không phải đề xuất.\n")).isEmpty();
	}

	private void people() {
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		this.hung = TeamTestSupport.mentor(this.jdbc, "Đỗ Mạnh Hùng",
				List.of(TeamTestSupport.REACT), "React");
		this.vy = TeamTestSupport.student(this.jdbc, "Trần Thảo Vy", true, List.of(TeamTestSupport.REACT), "React",
				LocalDate.parse("2026-09-30"));
	}

	/** An Active Assessment Job whose one handover, with the Report pinned, is in {@code handoverStatus}. */
	private UUID job(String handoverStatus) {
		UUID product = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO product.product (id, customer_account_id, name, description, ai_tools, repository_github_id,
				    repository_full_name, repository_owner_type, repository_default_branch, public_notice_version,
				    copy_permission_version, detection_notice_version, acknowledged_at, created_at)
				VALUES (:id, :owner, 'Sổ đặt bàn', 'Đặt bàn.', ARRAY['LOVABLE'], :github, 'lan/so-dat-ban', 'User', 'main',
				    '1.0', '1.0', '1.0', now(), now())
				""").param("id", product).param("owner", this.lan)
			.param("github", ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE)).update();
		UUID job = UUID.randomUUID();
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, skill_ids, skill_names, budget, deadline, rules, selection_days, opened_at, lapses_at,
				    mentor_account_id, selected_application_id, selected_at, version, created_at, updated_at)
				VALUES (:id, :product, :owner, 'ACTIVE', 'Đánh giá sổ đặt bàn', 'Lỗi', 'Đúng', 'Khách', ARRAY['ASSESSMENT'],
				    ARRAY[CAST(:react AS UUID)], ARRAY['React'], 'UNDER_2M', DATE '2026-12-01', '{}'::jsonb, 14, :now, :now,
				    :mentor, :application, :now, 9, :now, :now)
				""").param("id", job).param("product", product).param("owner", this.lan).param("mentor", this.hung)
			.param("react", TeamTestSupport.REACT).param("application", UUID.randomUUID()).param("now", now).update();
		Timestamp signed = Timestamp.from(this.clock.instant().minus(Duration.ofDays(5)));
		this.jdbc.sql("""
				INSERT INTO job.crew_row (id, job_id, student_account_id, status, signed_at, activated_at, created_at)
				VALUES (:id, :job, :student, 'ACTIVE', :signed, :signed, now())
				""").param("id", UUID.randomUUID()).param("job", job).param("student", this.vy).param("signed", signed).update();
		this.jdbc.sql("""
				INSERT INTO work.board (job_id, product_id, code, next_number, created_at) VALUES (:job, :product, 'SDB', 1, :now)
				""").param("job", job).param("product", product).param("now", now).update();
		UUID page = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO work.page (id, job_id, title, version_no, created_by_account_id, created_at, updated_at)
				VALUES (:id, :job, 'Báo cáo đánh giá', 1, :mentor, :now, :now)
				""").param("id", page).param("job", job).param("mentor", this.hung).param("now", now).update();
		this.jdbc.sql("""
				INSERT INTO work.page_version (page_id, version_no, title, body, author_account_id, created_at)
				VALUES (:id, 1, 'Báo cáo đánh giá', :body, :mentor, :now)
				""").param("id", page).param("body", REPORT).param("mentor", this.hung).param("now", now).update();
		boolean accepted = "ACCEPTED".equals(handoverStatus);
		this.jdbc.sql("""
				INSERT INTO delivery.handover (id, job_id, product_id, milestone_id, milestone_number, milestone_title, round,
				    mentor_account_id, status, job_version, transition_id, report_page_id, report_version_no, check_by,
				    created_at, decided_at, decided_by)
				VALUES (:id, :job, :product, :milestone, 1, 'Báo cáo đánh giá', 1, :mentor, :status, 7, :transition, :page, 1,
				    :now, :now, :decided, :decider)
				""")
			.param("id", UUID.randomUUID())
			.param("job", job)
			.param("product", product)
			.param("milestone", UUID.randomUUID())
			.param("mentor", this.hung)
			.param("status", handoverStatus)
			.param("transition", UUID.randomUUID())
			.param("page", page)
			.param("now", now)
			.param("decided", accepted ? now : null)
			.param("decider", accepted ? this.lan : null)
			.update();
		return job;
	}

	private ResultActions read(UUID account, Role role, UUID job) throws Exception {
		return this.mvc.perform(get("/api/v1/boards/" + job + "/follow-ups").with(as(account, role)));
	}

	private ResultActions create(UUID account, Role role, UUID job, int recommendation, String title) throws Exception {
		return this.mvc.perform(post("/api/v1/boards/" + job + "/follow-ups").with(as(account, role)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(content(recommendation, title, recommendation == 3 ? "DOCUMENTATION" : "BUG_FIX")));
	}

	private ResultActions submit(UUID account, UUID job) throws Exception {
		return this.mvc.perform(post("/api/v1/boards/" + job + "/follow-ups/submit").with(as(account, Role.MENTOR)).with(csrf()));
	}

	private static String content(int recommendation, String title, String type) {
		return """
				{"recommendationNo":%d,"title":"%s","jobTypes":["%s"],"skillIds":["%s"],"scope":"Phạm vi của việc.",
				 "criteria":["Hai khách không đặt trùng bàn","Có thông báo khi trùng"],"effortDays":5}
				""".formatted(recommendation, title, type, TeamTestSupport.REACT);
	}

	private boolean openTodo(String key, UUID account, UUID job) {
		return this.jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM notification.todo WHERE todo_key = :key AND account_id = :account AND subject_id = :job
				    AND closed_at IS NULL)
				""").param("key", key).param("account", account).param("job", job).query(Boolean.class).single();
	}

	private RequestPostProcessor as(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

}
