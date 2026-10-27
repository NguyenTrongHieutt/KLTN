package vn.vibefix.work.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.team.internal.TeamTestSupport;

/**
 * SG-53, FR-24, FR-25, AD-39: every Board move (from, to) for the Mentor, the assignee, another Crew
 * member, and the Customer, through the command that move would use: the move menu between "Cần làm"
 * and "Đang làm", the submission into "Chờ duyệt", the review out of it, and the move command for
 * everything else. The expectation is the status code and the reason, or the new status.
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

	private Map<String, UUID> people;

	private Map<String, Role> roles;

	private UUID item;

	@BeforeEach
	void aHeldMilestoneWithAnAssignedWorkItem() throws Exception {
		UUID lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		UUID hung = TeamTestSupport.mentor(this.jdbc, "Đỗ Mạnh Hùng",
				List.of(TeamTestSupport.REACT), "React");
		UUID product = WorkTestSupport.product(this.jdbc, lan, "Sổ đặt bàn Quán Mây");
		UUID job = WorkTestSupport.activeJob(this.jdbc, product, lan, hung, List.of(MILESTONE),
				List.of(List.of(UUID.randomUUID())), this.clock.instant());
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

	private String status() {
		return this.jdbc.sql("SELECT status FROM work.work_item WHERE id = :id").param("id", this.item)
			.query(String.class).single();
	}

}
