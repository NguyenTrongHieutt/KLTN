package vn.vibefix.work.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
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

import com.jayway.jsonpath.JsonPath;
import vn.vibefix.identity.MentorApprovalStatus;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.team.internal.TeamTestSupport;

/**
 * FR-23, FR-47, AD-23, AD-33, AD-39, AD-41: the Mentor and the active Crew create and edit Work Items
 * with a version and a history; only the Mentor assigns and sets the priority, to an active, unlocked
 * Crew member who is told; FR-47 holds a credential back until it is masked; a former member reads only
 * their Work Items as they stood; the Board is read-only once the Job leaves Active.
 */
class WorkItemsIT extends IntegrationTest {

	private static final UUID MILESTONE = UUID.randomUUID();

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private Boards boards;

	private UUID lan;

	private UUID hung;

	private UUID vy;

	private UUID job;

	@BeforeEach
	void anActiveJobWithItsBacklogAndCrew() {
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null, null);
		this.hung = TeamTestSupport.mentor(this.jdbc, MentorApprovalStatus.APPROVED, "Đỗ Mạnh Hùng",
				List.of(TeamTestSupport.REACT), "React");
		UUID product = WorkTestSupport.product(this.jdbc, this.lan, "Sổ đặt bàn Quán Mây");
		this.job = WorkTestSupport.activeJob(this.jdbc, product, this.lan, this.hung, List.of(MILESTONE),
				List.of(List.of(UUID.randomUUID(), UUID.randomUUID())), this.clock.instant());
		this.boards.seed(this.job, true);
		this.vy = WorkTestSupport.student(this.jdbc, "Trần Thảo Vy");
		WorkTestSupport.crewRow(this.jdbc, this.job, this.vy, "ACTIVE", this.clock.instant().minus(Duration.ofMinutes(5)));
	}

	@Test
	void anActiveMemberCreatesATaskAndTheHistoryRecordsIt() throws Exception {
		String body = create(this.vy, Role.STUDENT, task("Viết kiểm thử trùng giờ", ""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.item.key").value("SDB-3"))
			.andExpect(jsonPath("$.item.type").value("TASK"))
			.andExpect(jsonPath("$.item.seeded").value(false))
			.andExpect(jsonPath("$.item.checklistTotal").value(2))
			.andExpect(jsonPath("$.item.checklistDone").value(1))
			.andExpect(jsonPath("$.createdBy").value("Trần Thảo Vy"))
			.andExpect(jsonPath("$.history.length()").value(1))
			.andExpect(jsonPath("$.history[0].action").value("CREATED"))
			.andExpect(jsonPath("$.history[0].actor").value("Trần Thảo Vy"))
			.andReturn().getResponse().getContentAsString();
		String id = JsonPath.read(body, "$.item.id");

		this.mvc.perform(get("/api/v1/work-items/" + id).with(as(this.hung, Role.MENTOR)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.checklist[1].text").value("Chạy trên điện thoại"))
			.andExpect(jsonPath("$.readOnly").value(false));
		this.mvc.perform(get("/api/v1/boards/" + this.job + "/work-items").with(as(this.hung, Role.MENTOR)))
			.andExpect(jsonPath("$.items.length()").value(3))
			.andExpect(jsonPath("$.crew[0].name").value("Trần Thảo Vy"))
			.andExpect(jsonPath("$.readOnly").value(false));
		this.mvc.perform(get("/api/v1/boards/" + this.job + "/work-items").with(as(this.vy, Role.STUDENT)))
			.andExpect(jsonPath("$.crew.length()").value(0));
		for (RequestPostProcessor outsider : List.of(as(this.lan, Role.CUSTOMER),
				as(IdentityTestSupport.insertAccount(this.jdbc, Role.STUDENT, null, null), Role.STUDENT))) {
			this.mvc.perform(get("/api/v1/work-items/" + id).with(outsider)).andExpect(status().isNotFound());
			create(outsider, task("Xin chào", "")).andExpect(status().isNotFound());
		}
	}

	@Test
	void onlyTheMentorAssignsAndSetsThePriorityToAnActiveUnlockedMember() throws Exception {
		create(this.vy, Role.STUDENT, task("Việc của Vy", ",\"priority\":\"HIGH\"")).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.reason").value("MENTOR_ONLY"));
		String id = JsonPath.read(create(this.vy, Role.STUDENT, task("Việc của Vy", "")).andReturn().getResponse()
			.getContentAsString(), "$.item.id");
		update(this.vy, Role.STUDENT, id, task("Việc của Vy", ",\"assigneeId\":\"" + this.vy + "\",\"version\":1"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.reason").value("MENTOR_ONLY"));

		UUID tung = WorkTestSupport.student(this.jdbc, "Lê Minh Tùng");
		WorkTestSupport.crewRow(this.jdbc, this.job, tung, "ASSIGNED", null);
		update(this.hung, Role.MENTOR, id, task("Việc của Vy", ",\"assigneeId\":\"" + tung + "\",\"version\":1"))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.reason").value("NOT_CREW_MEMBER"));

		update(this.hung, Role.MENTOR, id,
				task("Việc của Vy", ",\"assigneeId\":\"" + this.vy + "\",\"priority\":\"HIGH\",\"estimateHours\":2.5,\"version\":1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.item.assignee.name").value("Trần Thảo Vy"))
			.andExpect(jsonPath("$.item.priority").value("HIGH"))
			.andExpect(jsonPath("$.item.estimateHours").value(2.5))
			.andExpect(jsonPath("$.item.version").value(2))
			.andExpect(jsonPath("$.history[1].assignedTo").value("Trần Thảo Vy"));
		assertThat(this.jdbc.sql("""
				SELECT params::text FROM notification.notification WHERE type = 'work.work_item_assigned' AND account_id = :vy
				""").param("vy", this.vy).query(String.class).single()).contains("Đỗ Mạnh Hùng").contains("SDB-3");

		UUID minh = WorkTestSupport.student(this.jdbc, "Nguyễn Văn Minh");
		WorkTestSupport.crewRow(this.jdbc, this.job, minh, "ACTIVE", this.clock.instant());
		IdentityTestSupport.lock(this.jdbc, minh, "test: locked");
		update(this.hung, Role.MENTOR, id, task("Việc của Vy", ",\"assigneeId\":\"" + minh + "\",\"version\":2"))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.reason").value("NOT_CREW_MEMBER"));
	}

	@Test
	void aStaleOrSeededChangeIsRefusedAndAnEditIsVersioned() throws Exception {
		String seeded = this.jdbc.sql("SELECT id FROM work.work_item WHERE job_id = :job AND number = 1")
			.param("job", this.job).query(UUID.class).single().toString();
		String story = "{\"type\":\"STORY\",\"title\":\"%s\",\"milestoneId\":\"" + MILESTONE + "\",\"version\":%d}";
		update(this.vy, Role.STUDENT, seeded, story.formatted("Tiêu chí đã rõ hơn", 1).replace("STORY", "TASK"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reason").value("SEEDED_FIELD_FIXED"));
		update(this.vy, Role.STUDENT, seeded, story.formatted("Tiêu chí đã rõ hơn", 1)).andExpect(status().isOk())
			.andExpect(jsonPath("$.item.version").value(2))
			.andExpect(jsonPath("$.item.seeded").value(true))
			.andExpect(jsonPath("$.history[1].fields[0]").value("title"));
		update(this.hung, Role.MENTOR, seeded, story.formatted("Một tên khác", 1)).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("STALE"));
		create(this.vy, Role.STUDENT, "{\"type\":\"BUG\",\"title\":\"Lỗi\",\"milestoneId\":\"" + UUID.randomUUID() + "\"}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reason").value("MILESTONE_UNKNOWN"));
	}

	@Test
	void aLikelyCredentialIsHeldBackUntilItIsMasked() throws Exception {
		String token = fakeToken();
		create(this.vy, Role.STUDENT, task("Khóa " + token, "")).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.reason").value("SENSITIVE_CONTENT"))
			.andExpect(jsonPath("$.matches[0].kind").value("CREDENTIAL"));
		assertThat(items()).isEqualTo(2);
		String body = create(this.vy, Role.STUDENT, task("Khóa " + token, ",\"sensitive\":\"MASK\""))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		assertThat(body).doesNotContain(token);
		assertThat(this.jdbc.sql("SELECT count(*) FROM work.work_item WHERE title LIKE :t").param("t", "%" + token + "%")
			.query(Long.class).single()).isZero();
	}

	@Test
	void aFormerMemberReadsOnlyTheirWorkItemsAsTheyStood() throws Exception {
		String mine = JsonPath.read(create(this.vy, Role.STUDENT, task("Việc Vy làm", "")).andReturn().getResponse()
			.getContentAsString(), "$.item.id");
		String other = JsonPath.read(create(this.hung, Role.MENTOR, task("Việc khác", "")).andReturn().getResponse()
			.getContentAsString(), "$.item.id");
		this.clock.advance(Duration.ofHours(1));
		this.jdbc.sql("""
				UPDATE job.crew_row SET status = 'RELEASED', window_end = :now, released_at = :now, released_reason = 'REMOVED'
				WHERE student_account_id = :vy
				""").param("now", Timestamp.from(this.clock.instant())).param("vy", this.vy).update();
		this.clock.advance(Duration.ofHours(1));
		update(this.hung, Role.MENTOR, mine, task("Hùng đã đổi tên", ",\"version\":1")).andExpect(status().isOk());

		this.mvc.perform(get("/api/v1/boards/" + this.job + "/work-items").with(as(this.vy, Role.STUDENT)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.readOnly").value(true))
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].title").value("Việc Vy làm"));
		this.mvc.perform(get("/api/v1/work-items/" + mine).with(as(this.vy, Role.STUDENT)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.item.title").value("Việc Vy làm"))
			.andExpect(jsonPath("$.history.length()").value(1));
		this.mvc.perform(get("/api/v1/work-items/" + other).with(as(this.vy, Role.STUDENT))).andExpect(status().isNotFound());
		update(this.vy, Role.STUDENT, mine, task("Vy sửa lại", ",\"version\":2")).andExpect(status().isNotFound());
	}

	@Test
	void theBoardIsReadOnlyOnceTheJobLeavesActive() throws Exception {
		this.jdbc.sql("UPDATE job.job SET state = 'CLOSING' WHERE id = :id").param("id", this.job).update();
		create(this.hung, Role.MENTOR, task("Muộn rồi", "")).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.reason").value("BOARD_READ_ONLY"));
		this.mvc.perform(get("/api/v1/boards/" + this.job + "/work-items").with(as(this.hung, Role.MENTOR)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.readOnly").value(true));
	}

	@Test
	void theMentorAndTheAssigneeMoveBetweenToDoAndInProgressOnceTheMilestoneIsHeld() throws Exception {
		this.jdbc.sql("""
				INSERT INTO job.milestone (id, job_id, position, title, price_vnd, revision_rounds, created_at)
				VALUES (:id, :job, 0, 'Giai đoạn 1', 1500000, 2, now())
				""").param("id", MILESTONE).param("job", this.job).update();
		String id = JsonPath.read(create(this.hung, Role.MENTOR, task("Việc của Vy", ",\"assigneeId\":\"" + this.vy + "\""))
			.andReturn().getResponse().getContentAsString(), "$.item.id");
		UUID tung = WorkTestSupport.student(this.jdbc, "Lê Minh Tùng");
		WorkTestSupport.crewRow(this.jdbc, this.job, tung, "ACTIVE", this.clock.instant());

		move(this.vy, Role.STUDENT, id, "IN_PROGRESS", 1).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.reason").value("MILESTONE_NOT_HELD"));
		this.jdbc.sql("UPDATE job.milestone SET state = 'FUNDED', funded_at = now(), crew_terms_version_no = 1 WHERE id = :id")
			.param("id", MILESTONE).update();
		this.mvc.perform(get("/api/v1/boards/" + this.job + "/work-items").with(as(this.vy, Role.STUDENT)))
			.andExpect(jsonPath("$.milestones[0].held").value(true))
			.andExpect(jsonPath("$.viewerId").value(this.vy.toString()));

		move(tung, Role.STUDENT, id, "IN_PROGRESS", 1).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.reason").value("NOT_ASSIGNEE"));
		move(this.vy, Role.STUDENT, id, "IN_PROGRESS", 1).andExpect(status().isOk())
			.andExpect(jsonPath("$.item.status").value("IN_PROGRESS"))
			.andExpect(jsonPath("$.item.version").value(2))
			.andExpect(jsonPath("$.history[1].action").value("MOVED"))
			.andExpect(jsonPath("$.history[1].fields[0]").value("status"));
		move(this.hung, Role.MENTOR, id, "TODO", 1).andExpect(status().isConflict());
		for (String to : List.of("IN_REVIEW", "DONE")) {
			move(this.vy, Role.STUDENT, id, to, 2).andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.reason").value("MOVE_NOT_ALLOWED"));
			move(this.hung, Role.MENTOR, id, to, 2).andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.reason").value("MOVE_NOT_ALLOWED"));
		}
		move(this.hung, Role.MENTOR, id, "TODO", 2).andExpect(status().isOk())
			.andExpect(jsonPath("$.item.status").value("TODO"));
		move(this.lan, Role.CUSTOMER, id, "IN_PROGRESS", 3).andExpect(status().isNotFound());
	}

	private ResultActions move(UUID account, Role role, String id, String to, long version) throws Exception {
		return this.mvc.perform(post("/api/v1/work-items/" + id + "/move").with(as(account, role)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content("{\"to\":\"" + to + "\",\"version\":" + version + "}"));
	}

	private String task(String title, String extra) {
		return "{\"type\":\"TASK\",\"title\":\"" + title + "\",\"description\":\"Mô tả\",\"checklist\":[{\"text\":\"Có kiểm thử\",\"done\":true},"
				+ "{\"text\":\"Chạy trên điện thoại\",\"done\":false}],\"milestoneId\":\"" + MILESTONE + "\"" + extra + "}";
	}

	private ResultActions create(UUID account, Role role, String body) throws Exception {
		return create(as(account, role), body);
	}

	private ResultActions create(RequestPostProcessor caller, String body) throws Exception {
		return this.mvc.perform(post("/api/v1/boards/" + this.job + "/work-items").with(caller).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private ResultActions update(UUID account, Role role, String id, String body) throws Exception {
		return this.mvc.perform(put("/api/v1/work-items/" + id).with(as(account, role)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private long items() {
		return this.jdbc.sql("SELECT count(*) FROM work.work_item WHERE job_id = :job").param("job", this.job)
			.query(Long.class).single();
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
