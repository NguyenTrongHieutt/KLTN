package vn.vibefix.work.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
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
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.team.internal.TeamTestSupport;
import vn.vibefix.work.CriteriaDone;
import vn.vibefix.work.CriteriaDone.Progress;

/**
 * FR-25, FR-28, AD-33, AD-39: the assignee submits with all four Self-check answers; the Mentor returns
 * it with a reason ("Bị trả lại", email, to-do) and, after a new submission, approves it; each step is a
 * history entry with its comment and answers, tells the other side, and opens or closes the to-dos; a
 * seeded Story counts as a criterion done only once approved.
 */
class ReviewGateIT extends IntegrationTest {

	private static final UUID MILESTONE = UUID.randomUUID();

	private static final UUID CRITERION = UUID.randomUUID();

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private Boards boards;

	@Autowired
	private CriteriaDone criteriaDone;

	private UUID hung;

	private UUID vy;

	private UUID job;

	private UUID story;

	@BeforeEach
	void aSeededStoryAssignedToVyInProgress() throws Exception {
		UUID lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		this.hung = TeamTestSupport.mentor(this.jdbc, "Đỗ Mạnh Hùng",
				List.of(TeamTestSupport.REACT), "React");
		UUID product = WorkTestSupport.product(this.jdbc, lan, "Sổ đặt bàn Quán Mây");
		this.job = WorkTestSupport.activeJob(this.jdbc, product, lan, this.hung, List.of(MILESTONE),
				List.of(List.of(CRITERION, UUID.randomUUID())), this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO job.milestone (id, job_id, position, title, price_vnd, revision_rounds, created_at, state, funded_at,
				    crew_terms_version_no)
				VALUES (:id, :job, 0, 'Giai đoạn 1', 1500000, 2, now(), 'FUNDED', now(), 1)
				""").param("id", MILESTONE).param("job", this.job).update();
		this.boards.seed(this.job, true);
		this.vy = WorkTestSupport.student(this.jdbc, "Trần Thảo Vy");
		WorkTestSupport.crewRow(this.jdbc, this.job, this.vy, "ACTIVE", this.clock.instant().minus(Duration.ofMinutes(5)));
		this.story = this.jdbc.sql("SELECT id FROM work.work_item WHERE criterion_id = :c").param("c", CRITERION)
			.query(UUID.class).single();
		this.mvc.perform(put("/api/v1/work-items/" + this.story).with(as(this.hung, Role.MENTOR)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"type\":\"STORY\",\"title\":\"Tiêu chí 1.1\",\"description\":\"Cách kiểm tra 1.1\",\"milestoneId\":\""
					+ MILESTONE + "\",\"assigneeId\":\"" + this.vy + "\",\"version\":1}"))
			.andExpect(status().isOk());
		this.mvc.perform(post("/api/v1/work-items/" + this.story + "/move").with(as(this.vy, Role.STUDENT)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content("{\"to\":\"IN_PROGRESS\",\"version\":2}"))
			.andExpect(status().isOk());
	}

	@Test
	void aSubmissionIsReturnedWithAReasonThenApprovedAndCountsAsDone() throws Exception {
		submit(3, "{\"meetsCriteria\":true,\"tested\":true,\"docsUpdated\":false,\"noSecrets\":true}", "")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reason").value("SELF_CHECK_INCOMPLETE"));
		submit(3, ALL, "Em đã thêm kiểm thử").andExpect(status().isOk())
			.andExpect(jsonPath("$.item.status").value("IN_REVIEW"))
			.andExpect(jsonPath("$.review.action").value("SUBMITTED"))
			.andExpect(jsonPath("$.review.by").value("Trần Thảo Vy"))
			.andExpect(jsonPath("$.review.comment").value("Em đã thêm kiểm thử"))
			.andExpect(jsonPath("$.review.selfCheck.docsUpdated").value(true));
		assertThat(notifications("work.review_requested", this.hung)).isOne();
		assertThat(openTodos("work.review_work_item", this.hung)).isOne();
		assertThat(this.criteriaDone.criteriaDone(this.job, MILESTONE)).isEqualTo(new Progress(0, 2));

		review(this.vy, Role.STUDENT, 4, "APPROVE", "Tự duyệt").andExpect(status().isForbidden())
			.andExpect(jsonPath("$.reason").value("MENTOR_ONLY"));
		review(this.hung, Role.MENTOR, 4, "RETURN", "  ").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reason").value("REVIEW_COMMENT_REQUIRED"));
		review(this.hung, Role.MENTOR, 4, "RETURN", "Hãy thêm kiểm thử cho trường hợp xóa đơn").andExpect(status().isOk())
			.andExpect(jsonPath("$.item.status").value("IN_PROGRESS"))
			.andExpect(jsonPath("$.item.returnedReason").value("Hãy thêm kiểm thử cho trường hợp xóa đơn"));
		assertThat(openTodos("work.review_work_item", this.hung)).isZero();
		assertThat(openTodos("work.fix_returned_item", this.vy)).isOne();
		assertThat(notifications("work.work_item_returned", this.vy)).isOne();
		this.mvc.perform(get("/api/v1/boards/" + this.job + "/work-items").with(as(this.vy, Role.STUDENT)))
			.andExpect(jsonPath("$.items[0].returnedReason").value("Hãy thêm kiểm thử cho trường hợp xóa đơn"));

		submit(5, ALL, "").andExpect(status().isOk())
			.andExpect(jsonPath("$.item.returnedReason").doesNotExist());
		assertThat(openTodos("work.fix_returned_item", this.vy)).isZero();
		review(this.hung, Role.MENTOR, 6, "APPROVE", "Đạt yêu cầu").andExpect(status().isOk())
			.andExpect(jsonPath("$.item.status").value("DONE"))
			.andExpect(jsonPath("$.history[?(@.action == 'APPROVED')].comment").value("Đạt yêu cầu"));
		assertThat(notifications("work.work_item_approved", this.vy)).isOne();
		assertThat(openTodos("work.review_work_item", this.hung)).isZero();
		assertThat(this.criteriaDone.criteriaDone(this.job, MILESTONE)).isEqualTo(new Progress(1, 2));
		assertThat(this.jdbc.sql("""
				SELECT array_agg(action ORDER BY seq)::text FROM work.work_item_history WHERE work_item_id = :id
				""").param("id", this.story).query(String.class).single())
			.isEqualTo("{SEEDED,UPDATED,MOVED,SUBMITTED,RETURNED,SUBMITTED,APPROVED}");
	}

	@Test
	void aStoryMovedToDoneOtherwiseNeverCountsAndTheMoveCommandRefusesDone() throws Exception {
		this.mvc.perform(post("/api/v1/work-items/" + this.story + "/move").with(as(this.hung, Role.MENTOR)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content("{\"to\":\"DONE\",\"version\":3}"))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.reason").value("MOVE_NOT_ALLOWED"));
		this.jdbc.sql("UPDATE work.work_item SET status = 'DONE' WHERE id = :id").param("id", this.story).update();
		assertThat(this.criteriaDone.criteriaDone(this.job, MILESTONE)).as("Done without an approval")
			.isEqualTo(new Progress(0, 2));
	}

	private static final String ALL = "{\"meetsCriteria\":true,\"tested\":true,\"docsUpdated\":true,\"noSecrets\":true}";

	private ResultActions submit(long version, String selfCheck, String note) throws Exception {
		return this.mvc.perform(post("/api/v1/work-items/" + this.story + "/submit").with(as(this.vy, Role.STUDENT)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"version\":" + version + ",\"selfCheck\":" + selfCheck + ",\"note\":\"" + note + "\"}"));
	}

	private ResultActions review(UUID account, Role role, long version, String decision, String comment) throws Exception {
		return this.mvc.perform(post("/api/v1/work-items/" + this.story + "/review").with(as(account, role)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"version\":" + version + ",\"decision\":\"" + decision + "\",\"comment\":\"" + comment + "\"}"));
	}

	private long notifications(String type, UUID account) {
		return this.jdbc.sql("SELECT count(*) FROM notification.notification WHERE type = :type AND account_id = :id")
			.param("type", type).param("id", account).query(Long.class).single();
	}

	private long openTodos(String key, UUID account) {
		return this.jdbc.sql("SELECT count(*) FROM notification.todo WHERE todo_key = :key AND account_id = :id AND closed_at IS NULL")
			.param("key", key).param("id", account).query(Long.class).single();
	}

	private RequestPostProcessor as(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

}
