package vn.vibefix.work.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.jayway.jsonpath.JsonPath;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.team.internal.TeamTestSupport;
import vn.vibefix.work.CriteriaDone;
import vn.vibefix.work.MilestoneWork;
import vn.vibefix.work.WorkFacts;

/**
 * SG-53, FR-24, FR-25, AD-39: every Board move (from, to) for the Mentor, the assignee, another Crew
 * member, and the Customer, through the command that move would use: the move menu between "Cần làm"
 * and "Đang làm", the submission into "Chờ duyệt", the review out of it, and the move command for
 * everything else. The expectation is the status code and the reason, or the new status. The no-Crew rule (FR-25, R8, X7,
 * Story 21.6) has its own rows: the Mentor's own Work Item goes from "Đang làm" straight to "Xong" with the Self-check
 * answers, whether or not a member has joined since, and a new self-assignment is refused while a member is active.
 */
class BoardMovesTableIT extends IntegrationTest {

	private static final UUID MILESTONE = UUID.randomUUID();

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

	@Autowired
	private MilestoneWork milestoneWork;

	@Autowired
	private WorkFacts workFacts;

	private UUID criterion;

	private Map<String, UUID> people;

	private Map<String, Role> roles;

	private UUID item;

	private UUID job;

	@BeforeEach
	void aHeldMilestoneWithAnAssignedWorkItem() throws Exception {
		UUID lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		UUID hung = TeamTestSupport.mentor(this.jdbc, "Đỗ Mạnh Hùng",
				List.of(TeamTestSupport.REACT), "React");
		UUID product = WorkTestSupport.product(this.jdbc, lan, "Sổ đặt bàn Quán Mây");
		UUID criterion = UUID.randomUUID();
		UUID job = WorkTestSupport.activeJob(this.jdbc, product, lan, hung, List.of(MILESTONE),
				List.of(List.of(criterion)), this.clock.instant());
		this.job = job;
		this.criterion = criterion;
		this.jdbc.sql("""
				INSERT INTO job.milestone (id, job_id, position, title, price_vnd, revision_rounds, created_at, state, funded_at,
				    crew_terms_version_no)
				VALUES (:id, :job, 0, 'Giai đoạn 1', 1500000, 2, now(), 'FUNDED', now(), 1)
				""").param("id", MILESTONE).param("job", job).update();
		this.boards.seed(job, true);
		UUID vy = WorkTestSupport.student(this.jdbc, "Trần Thảo Vy");
		WorkTestSupport.crewRow(this.jdbc, job, vy, "ACTIVE", this.clock.instant().minus(Duration.ofMinutes(5)));
		UUID tung = WorkTestSupport.student(this.jdbc, "Lê Minh Tùng");
		WorkTestSupport.crewRow(this.jdbc, job, tung, "ACTIVE", this.clock.instant().minus(Duration.ofMinutes(5)));
		this.people = Map.of("MENTOR", hung, "ASSIGNEE", vy, "OTHER_CREW", tung, "CUSTOMER", lan);
		this.roles = Map.of("MENTOR", Role.MENTOR, "ASSIGNEE", Role.STUDENT, "OTHER_CREW", Role.STUDENT, "CUSTOMER",
				Role.CUSTOMER);
		String created = this.mvc.perform(post("/api/v1/boards/" + job + "/work-items")
			.with(IdentityTestSupport.signedIn(this.sessions, hung, Role.MENTOR, this.clock.instant())).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"type\":\"TASK\",\"title\":\"Việc của Vy\",\"milestoneId\":\"" + MILESTONE + "\",\"assigneeId\":\"" + vy
					+ "\"}"))
			.andReturn().getResponse().getContentAsString();
		this.item = UUID.fromString(JsonPath.read(created, "$.item.id"));
	}

	@ParameterizedTest(name = "{0}: {1} -> {2} answers {3}")
	@CsvSource({
		// The Mentor: moves between Cần làm and Đang làm, reviews out of Chờ duyệt, never submits.
		"MENTOR, TODO, IN_PROGRESS, IN_PROGRESS", "MENTOR, TODO, IN_REVIEW, 422 MOVE_NOT_ALLOWED",
		"MENTOR, TODO, DONE, 422 MOVE_NOT_ALLOWED", "MENTOR, IN_PROGRESS, TODO, TODO",
		"MENTOR, IN_PROGRESS, IN_REVIEW, 403 NOT_ASSIGNEE", "MENTOR, IN_PROGRESS, DONE, 422 MOVE_NOT_ALLOWED",
		"MENTOR, IN_REVIEW, TODO, 422 MOVE_NOT_ALLOWED", "MENTOR, IN_REVIEW, IN_PROGRESS, IN_PROGRESS",
		"MENTOR, IN_REVIEW, DONE, DONE", "MENTOR, DONE, TODO, 422 MOVE_NOT_ALLOWED",
		"MENTOR, DONE, IN_PROGRESS, 422 MOVE_NOT_ALLOWED", "MENTOR, DONE, IN_REVIEW, 422 MOVE_NOT_ALLOWED",
		// The assignee: moves between Cần làm and Đang làm, submits, never reviews.
		"ASSIGNEE, TODO, IN_PROGRESS, IN_PROGRESS", "ASSIGNEE, TODO, IN_REVIEW, 422 MOVE_NOT_ALLOWED",
		"ASSIGNEE, TODO, DONE, 422 MOVE_NOT_ALLOWED", "ASSIGNEE, IN_PROGRESS, TODO, TODO",
		"ASSIGNEE, IN_PROGRESS, IN_REVIEW, IN_REVIEW", "ASSIGNEE, IN_PROGRESS, DONE, 422 MOVE_NOT_ALLOWED",
		"ASSIGNEE, IN_REVIEW, TODO, 422 MOVE_NOT_ALLOWED", "ASSIGNEE, IN_REVIEW, IN_PROGRESS, 403 MENTOR_ONLY",
		"ASSIGNEE, IN_REVIEW, DONE, 403 MENTOR_ONLY", "ASSIGNEE, DONE, TODO, 422 MOVE_NOT_ALLOWED",
		"ASSIGNEE, DONE, IN_PROGRESS, 422 MOVE_NOT_ALLOWED", "ASSIGNEE, DONE, IN_REVIEW, 422 MOVE_NOT_ALLOWED",
		// Another active Crew member: no move on someone else's Work Item.
		"OTHER_CREW, TODO, IN_PROGRESS, 403 NOT_ASSIGNEE", "OTHER_CREW, TODO, IN_REVIEW, 403 NOT_ASSIGNEE",
		"OTHER_CREW, TODO, DONE, 403 NOT_ASSIGNEE", "OTHER_CREW, IN_PROGRESS, TODO, 403 NOT_ASSIGNEE",
		"OTHER_CREW, IN_PROGRESS, IN_REVIEW, 403 NOT_ASSIGNEE", "OTHER_CREW, IN_PROGRESS, DONE, 403 NOT_ASSIGNEE",
		"OTHER_CREW, IN_REVIEW, TODO, 403 NOT_ASSIGNEE", "OTHER_CREW, IN_REVIEW, IN_PROGRESS, 403 MENTOR_ONLY",
		"OTHER_CREW, IN_REVIEW, DONE, 403 MENTOR_ONLY", "OTHER_CREW, DONE, TODO, 403 NOT_ASSIGNEE",
		"OTHER_CREW, DONE, IN_PROGRESS, 403 NOT_ASSIGNEE", "OTHER_CREW, DONE, IN_REVIEW, 403 NOT_ASSIGNEE",
		// The Customer: the Board does not exist for them.
		"CUSTOMER, TODO, IN_PROGRESS, 404", "CUSTOMER, TODO, IN_REVIEW, 404", "CUSTOMER, TODO, DONE, 404",
		"CUSTOMER, IN_PROGRESS, TODO, 404", "CUSTOMER, IN_PROGRESS, IN_REVIEW, 404", "CUSTOMER, IN_PROGRESS, DONE, 404",
		"CUSTOMER, IN_REVIEW, TODO, 404", "CUSTOMER, IN_REVIEW, IN_PROGRESS, 404", "CUSTOMER, IN_REVIEW, DONE, 404",
		"CUSTOMER, DONE, TODO, 404", "CUSTOMER, DONE, IN_PROGRESS, 404", "CUSTOMER, DONE, IN_REVIEW, 404" })
	void everyBoardMoveAnswersAsTheRulesSay(String role, String from, String to, String expected) throws Exception {
		this.jdbc.sql("UPDATE work.work_item SET status = :from WHERE id = :id").param("from", from).param("id", this.item)
			.update();
		long version = this.jdbc.sql("SELECT version FROM work.work_item WHERE id = :id").param("id", this.item)
			.query(Long.class).single();
		String path;
		String body;
		if ("IN_PROGRESS".equals(from) && "IN_REVIEW".equals(to)) {
			path = "submit";
			body = "{\"version\":%d,\"selfCheck\":{\"meetsCriteria\":true,\"tested\":true,\"docsUpdated\":true,\"noSecrets\":true}}"
				.formatted(version);
		}
		else if ("IN_REVIEW".equals(from) && ("DONE".equals(to) || "IN_PROGRESS".equals(to))) {
			path = "review";
			body = "{\"version\":%d,\"decision\":\"%s\",\"comment\":\"Đã xem\"}".formatted(version,
					"DONE".equals(to) ? "APPROVE" : "RETURN");
		}
		else {
			path = "move";
			body = "{\"to\":\"%s\",\"version\":%d}".formatted(to, version);
		}
		MockHttpServletResponse response = this.mvc.perform(post("/api/v1/work-items/" + this.item + "/" + path)
			.with(IdentityTestSupport.signedIn(this.sessions, this.people.get(role), this.roles.get(role), this.clock.instant()))
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(body)).andReturn().getResponse();
		String answer = response.getContentAsString();
		if (expected.matches("\\d{3}.*")) {
			String[] parts = expected.split(" ");
			assertThat(response.getStatus()).as(answer).isEqualTo(Integer.parseInt(parts[0]));
			if (parts.length > 1) {
				assertThat((String) JsonPath.read(answer, "$.reason")).isEqualTo(parts[1]);
			}
			assertThat(status()).as("a refused move changes nothing").isEqualTo(from);
		}
		else {
			assertThat(response.getStatus()).as(answer).isEqualTo(200);
			assertThat(status()).isEqualTo(expected);
		}
	}

	/**
	 * R8, X7: keyed on the assignee, not on the Crew at the move. The Mentor's own Work Item moves between "Cần làm" and
	 * "Đang làm", and its submission is a move to "Xong" with no review; a move command to "Xong" stays refused, and a
	 * leaver's submission the Mentor took over is reviewed as usual.
	 */
	@ParameterizedTest(name = "{0}: {1} -> {2} by {3} answers {4}")
	@CsvSource({ "NO_CREW, TODO, IN_PROGRESS, move, IN_PROGRESS", "NO_CREW, IN_PROGRESS, TODO, move, TODO",
		"NO_CREW, IN_PROGRESS, DONE, submit, DONE", "NO_CREW, IN_PROGRESS, DONE, move, 422 MOVE_NOT_ALLOWED",
		"NO_CREW, TODO, DONE, submit, 422 MOVE_NOT_ALLOWED", "NO_CREW, TODO, DONE, move, 422 MOVE_NOT_ALLOWED",
		"NO_CREW, IN_REVIEW, DONE, review, DONE", "NO_CREW, IN_REVIEW, IN_PROGRESS, review, IN_PROGRESS",
		"NO_CREW, DONE, IN_PROGRESS, move, 422 MOVE_NOT_ALLOWED",
		"MEMBER_BACK, TODO, IN_PROGRESS, move, IN_PROGRESS", "MEMBER_BACK, IN_PROGRESS, TODO, move, TODO",
		"MEMBER_BACK, IN_PROGRESS, DONE, submit, DONE", "MEMBER_BACK, IN_PROGRESS, DONE, move, 422 MOVE_NOT_ALLOWED",
		"MEMBER_BACK, TODO, DONE, submit, 422 MOVE_NOT_ALLOWED", "MEMBER_BACK, IN_REVIEW, DONE, review, DONE" })
	void theMentorsOwnWorkItemMovesAsTheNoCrewRuleSays(String crew, String from, String to, String command, String expected)
			throws Exception {
		if ("NO_CREW".equals(crew)) {
			noCrewLeft();
		}
		this.jdbc.sql("UPDATE work.work_item SET status = :from, assignee_account_id = :mentor WHERE id = :id")
			.param("from", from).param("mentor", this.people.get("MENTOR")).param("id", this.item).update();
		long version = version();
		String body = switch (command) {
			case "submit" -> "{\"version\":%d,\"selfCheck\":{\"meetsCriteria\":true,\"tested\":true,\"docsUpdated\":true,\"noSecrets\":true}}"
				.formatted(version);
			case "review" -> "{\"version\":%d,\"decision\":\"%s\",\"comment\":\"Đã xem\"}".formatted(version,
					"DONE".equals(to) ? "APPROVE" : "RETURN");
			default -> "{\"to\":\"%s\",\"version\":%d}".formatted(to, version);
		};
		MockHttpServletResponse response = mentor("/api/v1/work-items/" + this.item + "/" + command, body);
		String answer = response.getContentAsString();
		if (expected.matches("\\d{3}.*")) {
			String[] parts = expected.split(" ");
			assertThat(response.getStatus()).as(answer).isEqualTo(Integer.parseInt(parts[0]));
			assertThat((String) JsonPath.read(answer, "$.reason")).isEqualTo(parts[1]);
			assertThat(status()).as("a refused move changes nothing").isEqualTo(from);
		}
		else {
			assertThat(response.getStatus()).as(answer).isEqualTo(200);
			assertThat(status()).isEqualTo(expected);
		}
		assertThat(this.jdbc.sql("SELECT count(*) FROM notification.notification WHERE account_id = :mentor AND type LIKE 'work.%'")
			.param("mentor", this.people.get("MENTOR")).query(Long.class).single()).as("the Mentor hears nothing of their own")
			.isZero();
	}

	/** An unreviewed move needs the four answers, reads as such in the history, and counts in {@code criteriaDone}. */
	@Test
	void anUnreviewedMoveNeedsTheSelfCheckAndCountsWhereAnApprovalCounts() throws Exception {
		noCrewLeft();
		UUID story = this.jdbc.sql("SELECT id FROM work.work_item WHERE criterion_id = :criterion").param("criterion", this.criterion)
			.query(UUID.class).single();
		this.jdbc.sql("UPDATE work.work_item SET status = 'IN_PROGRESS', assignee_account_id = :mentor WHERE id = :id")
			.param("mentor", this.people.get("MENTOR")).param("id", story).update();
		this.item = story;
		String incomplete = "{\"version\":%d,\"selfCheck\":{\"meetsCriteria\":true,\"tested\":false,\"docsUpdated\":true,\"noSecrets\":true}}"
			.formatted(version());
		assertThat(mentor("/api/v1/work-items/" + story + "/submit", incomplete).getStatus()).isEqualTo(400);
		assertThat(this.criteriaDone.criteriaDone(this.job, MILESTONE).done()).isZero();

		String complete = "{\"version\":%d,\"selfCheck\":{\"meetsCriteria\":true,\"tested\":true,\"docsUpdated\":true,\"noSecrets\":true},\"note\":\"Mình tự làm\"}"
			.formatted(version());
		MockHttpServletResponse done = mentor("/api/v1/work-items/" + story + "/submit", complete);

		assertThat(done.getStatus()).as(done.getContentAsString()).isEqualTo(200);
		assertThat((String) JsonPath.read(done.getContentAsString(), "$.item.status")).isEqualTo("DONE");
		assertThat((String) JsonPath.read(done.getContentAsString(), "$.review.action")).isEqualTo("UNREVIEWED");
		assertThat((Boolean) JsonPath.read(done.getContentAsString(), "$.review.selfCheck.tested")).isTrue();
		assertThat(this.jdbc.sql("SELECT action FROM work.work_item_history WHERE work_item_id = :id ORDER BY seq DESC LIMIT 1")
			.param("id", story).query(String.class).single()).isEqualTo("UNREVIEWED");
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM notification.todo WHERE todo_key = 'work.review_work_item' AND closed_at IS NULL
				""").query(Long.class).single()).as("nobody reviews it").isZero();
		assertThat(this.criteriaDone.criteriaDone(this.job, MILESTONE).done()).isOne();
		assertThat(this.milestoneWork.items(this.job, MILESTONE)).filteredOn(state -> state.id().equals(story))
			.singleElement().satisfies(state -> assertThat(state.approved()).isTrue());
		// Portfolio's and ADM-9's counts stay with approvals: nobody reviewed this one.
		assertThat(this.workFacts.approvedItems(this.job)).isEmpty();
	}

	/** R8, X7: with no counted member the Mentor assigns themself, unnotified; with one back it is refused, and nothing moves. */
	@Test
	void theMentorAssignsThemselfOnlyWhileNoMemberIsActive() throws Exception {
		String self = "{\"type\":\"TASK\",\"title\":\"Việc của tôi\",\"milestoneId\":\"%s\",\"assigneeId\":\"%s\"}"
			.formatted(MILESTONE, this.people.get("MENTOR"));
		MockHttpServletResponse refused = mentor("/api/v1/boards/" + this.job + "/work-items", self);
		assertThat(refused.getStatus()).as(refused.getContentAsString()).isEqualTo(422);
		assertThat((String) JsonPath.read(refused.getContentAsString(), "$.reason")).isEqualTo("NOT_CREW_MEMBER");
		assertThat(board("$.noCrew", Boolean.class)).isFalse();

		noCrewLeft();
		assertThat(board("$.noCrew", Boolean.class)).isTrue();
		MockHttpServletResponse created = mentor("/api/v1/boards/" + this.job + "/work-items", self);
		assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
		UUID own = UUID.fromString(JsonPath.read(created.getContentAsString(), "$.item.id"));
		assertThat((String) JsonPath.read(created.getContentAsString(), "$.item.assignee.name")).isEqualTo("Đỗ Mạnh Hùng");
		MockHttpServletResponse taken = put(this.item, this.people.get("MENTOR"));
		assertThat(taken.getStatus()).as(taken.getContentAsString()).isEqualTo(200);
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM notification.notification WHERE type = 'work.work_item_assigned' AND account_id = :mentor
				""").param("mentor", this.people.get("MENTOR")).query(Long.class).single()).as("the Mentor hears nothing of it").isZero();

		// A member signs: nothing is reassigned, the Mentor's own stay, and a new self-assignment is refused.
		UUID an = WorkTestSupport.student(this.jdbc, "Phan Văn An");
		WorkTestSupport.crewRow(this.jdbc, this.job, an, "ACTIVE", this.clock.instant());
		assertThat(board("$.noCrew", Boolean.class)).isFalse();
		assertThat(this.jdbc.sql("SELECT assignee_account_id FROM work.work_item WHERE id = :id").param("id", own)
			.query(UUID.class).single()).isEqualTo(this.people.get("MENTOR"));
		this.item = own;
		MockHttpServletResponse kept = put(own, this.people.get("MENTOR"));
		assertThat(kept.getStatus()).as("keeping it is no new assignment: " + kept.getContentAsString()).isEqualTo(200);
		MockHttpServletResponse again = mentor("/api/v1/boards/" + this.job + "/work-items", self);
		assertThat(again.getStatus()).isEqualTo(422);
		assertThat((String) JsonPath.read(again.getContentAsString(), "$.reason")).isEqualTo("NOT_CREW_MEMBER");
		// A locked member is not counted: the rule reads identity.lockedAmong, as J6 does.
		IdentityTestSupport.lock(this.jdbc, an, "Vi phạm quy tắc");
		assertThat(board("$.noCrew", Boolean.class)).isTrue();
	}

	/** A return of the Mentor's own Work Item opens no to-do for them, and CREW_ACTIVE never reaches a Mentor. */
	@Test
	void returningTheirOwnWorkItemTellsTheMentorNothing() throws Exception {
		noCrewLeft();
		this.jdbc.sql("UPDATE work.work_item SET status = 'IN_REVIEW', assignee_account_id = :mentor WHERE id = :id")
			.param("mentor", this.people.get("MENTOR")).param("id", this.item).update();

		MockHttpServletResponse returned = mentor("/api/v1/work-items/" + this.item + "/review",
				"{\"version\":%d,\"decision\":\"RETURN\",\"comment\":\"Làm lại\"}".formatted(version()));

		assertThat(returned.getStatus()).as(returned.getContentAsString()).isEqualTo(200);
		assertThat(this.jdbc.sql("SELECT count(*) FROM notification.todo WHERE todo_key = 'work.fix_returned_item'")
			.query(Long.class).single()).isZero();
	}

	private void noCrewLeft() {
		this.jdbc.sql("""
				UPDATE job.crew_row SET status = 'RELEASED', released_reason = 'LEFT', released_at = now(), window_end = now()
				WHERE job_id = :job
				""").param("job", this.job).update();
	}

	private long version() {
		return this.jdbc.sql("SELECT version FROM work.work_item WHERE id = :id").param("id", this.item).query(Long.class).single();
	}

	private MockHttpServletResponse mentor(String path, String body) throws Exception {
		return this.mvc.perform(post(path)
			.with(IdentityTestSupport.signedIn(this.sessions, this.people.get("MENTOR"), Role.MENTOR, this.clock.instant()))
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(body)).andReturn().getResponse();
	}

	private MockHttpServletResponse put(UUID workItem, UUID assignee) throws Exception {
		String title = this.jdbc.sql("SELECT title FROM work.work_item WHERE id = :id").param("id", workItem).query(String.class).single();
		long version = this.jdbc.sql("SELECT version FROM work.work_item WHERE id = :id").param("id", workItem).query(Long.class).single();
		return this.mvc.perform(MockMvcRequestBuilders.put("/api/v1/work-items/" + workItem)
			.with(IdentityTestSupport.signedIn(this.sessions, this.people.get("MENTOR"), Role.MENTOR, this.clock.instant()))
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"type\":\"TASK\",\"title\":\"%s\",\"milestoneId\":\"%s\",\"assigneeId\":\"%s\",\"version\":%d}"
				.formatted(title, MILESTONE, assignee, version))).andReturn().getResponse();
	}

	private <T> T board(String path, Class<T> type) throws Exception {
		String body = this.mvc.perform(get("/api/v1/boards/" + this.job + "/work-items")
			.with(IdentityTestSupport.signedIn(this.sessions, this.people.get("MENTOR"), Role.MENTOR, this.clock.instant())))
			.andReturn().getResponse().getContentAsString();
		return JsonPath.parse(body).read(path, type);
	}

	private String status() {
		return this.jdbc.sql("SELECT status FROM work.work_item WHERE id = :id").param("id", this.item)
			.query(String.class).single();
	}

}
