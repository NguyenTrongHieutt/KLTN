package vn.vibefix.work.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.team.internal.TeamTestSupport;

/**
 * Story 11.2 (FR-23, FR-26, AD-41, UX-DR37, UX-DR68), with {@code features.largeCrews} on: the Mentor's
 * picker lists the active, unlocked members and an inactive one disabled; the workload line counts each
 * active member's open Work Items and estimated hours for the Mentor only; JOB-1 names every member's
 * AD-41 state.
 */
@TestPropertySource(properties = "vibefix.features.large-crews=true")
class CrewWorkloadIT extends IntegrationTest {

	private static final UUID MILESTONE = UUID.randomUUID();

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private Boards boards;

	private UUID hung;

	private UUID vy;

	private UUID tung;

	private UUID minh;

	private UUID an;

	private UUID kim;

	private UUID job;

	@BeforeEach
	void aCrewOfSeveralMembersInEveryState() {
		UUID lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		this.hung = TeamTestSupport.mentor(this.jdbc, "Đỗ Mạnh Hùng",
				List.of(TeamTestSupport.REACT), "React");
		UUID product = WorkTestSupport.product(this.jdbc, lan, "Sổ đặt bàn Quán Mây");
		this.job = WorkTestSupport.activeJob(this.jdbc, product, lan, this.hung, List.of(MILESTONE),
				List.of(List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())), this.clock.instant());
		this.boards.seed(this.job, true);
		this.vy = WorkTestSupport.student(this.jdbc, "Trần Thảo Vy");
		this.tung = WorkTestSupport.student(this.jdbc, "Lê Minh Tùng");
		this.minh = WorkTestSupport.student(this.jdbc, "Nguyễn Văn Minh");
		this.an = WorkTestSupport.student(this.jdbc, "Phạm Thu An");
		this.kim = WorkTestSupport.student(this.jdbc, "Hoàng Kim Ngân");
		WorkTestSupport.crewRow(this.jdbc, this.job, this.vy, "ACTIVE", this.clock.instant().minus(Duration.ofMinutes(5)));
		WorkTestSupport.crewRow(this.jdbc, this.job, this.tung, "ACTIVE", this.clock.instant().minus(Duration.ofMinutes(5)));
		WorkTestSupport.crewRow(this.jdbc, this.job, this.minh, "INACTIVE", this.clock.instant().minus(Duration.ofMinutes(5)));
		IdentityTestSupport.lock(this.jdbc, this.minh, "test: locked");
		WorkTestSupport.crewRow(this.jdbc, this.job, this.an, "ASSIGNED", null);
		WorkTestSupport.crewRow(this.jdbc, this.job, this.kim, "ACTIVE", this.clock.instant().minus(Duration.ofMinutes(5)));
		this.jdbc.sql("""
				UPDATE job.crew_row SET status = 'RELEASED', window_end = :now, released_at = :now, released_reason = 'REMOVED'
				WHERE student_account_id = :kim
				""").param("now", Timestamp.from(this.clock.instant())).param("kim", this.kim).update();
		// Vy holds two open Work Items (2.5 h and 4 h) and a Done one; Tùng none.
		List<UUID> items = this.jdbc.sql("SELECT id FROM work.work_item WHERE job_id = :job ORDER BY number")
			.param("job", this.job)
			.query(UUID.class)
			.list();
		assign(items.get(0), this.vy, "2.5", "IN_PROGRESS");
		assign(items.get(1), this.vy, "4", "TODO");
		assign(items.get(2), this.vy, "8", "DONE");
	}

	@Test
	void theMentorPicksAnActiveMemberAndSeesEachMembersWorkload() throws Exception {
		this.mvc.perform(get("/api/v1/boards/" + this.job + "/work-items").with(as(this.hung, Role.MENTOR)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.crew.length()").value(3))
			.andExpect(jsonPath("$.crew[?(@.name == 'Trần Thảo Vy')].available").value(true))
			.andExpect(jsonPath("$.crew[?(@.name == 'Lê Minh Tùng')].available").value(true))
			.andExpect(jsonPath("$.crew[?(@.name == 'Nguyễn Văn Minh')].available").value(false))
			.andExpect(jsonPath("$.workload.length()").value(2))
			.andExpect(jsonPath("$.workload[?(@.name == 'Trần Thảo Vy')].openItems").value(2))
			.andExpect(jsonPath("$.workload[?(@.name == 'Trần Thảo Vy')].estimateHours").value(6.5))
			.andExpect(jsonPath("$.workload[?(@.name == 'Lê Minh Tùng')].openItems").value(0))
			.andExpect(jsonPath("$.workload[?(@.name == 'Lê Minh Tùng')].estimateHours").value(0));

		String seeded = this.jdbc.sql("SELECT id FROM work.work_item WHERE job_id = :job AND number = 1")
			.param("job", this.job).query(UUID.class).single().toString();
		String story = "{\"type\":\"STORY\",\"title\":\"Tiêu chí 1.1\",\"milestoneId\":\"" + MILESTONE
				+ "\",\"estimateHours\":2.5,\"assigneeId\":\"%s\",\"version\":%d}";
		update(seeded, story.formatted(this.minh, 2)).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.reason").value("NOT_CREW_MEMBER"));
		update(seeded, story.formatted(this.tung, 2)).andExpect(status().isOk())
			.andExpect(jsonPath("$.item.assignee.name").value("Lê Minh Tùng"));
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM notification.notification WHERE type = 'work.work_item_assigned' AND account_id = :tung
				""").param("tung", this.tung).query(Long.class).single()).isEqualTo(1);
		this.mvc.perform(get("/api/v1/boards/" + this.job + "/work-items").with(as(this.hung, Role.MENTOR)))
			.andExpect(jsonPath("$.workload[?(@.name == 'Trần Thảo Vy')].openItems").value(1))
			.andExpect(jsonPath("$.workload[?(@.name == 'Lê Minh Tùng')].openItems").value(1))
			.andExpect(jsonPath("$.workload[?(@.name == 'Lê Minh Tùng')].estimateHours").value(2.5));
	}

	@Test
	void aCrewMemberNeverSeesTheWorkloadLine() throws Exception {
		this.mvc.perform(get("/api/v1/boards/" + this.job + "/work-items").with(as(this.vy, Role.STUDENT)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.crew.length()").value(0))
			.andExpect(jsonPath("$.workload.length()").value(0));
	}

	@Test
	void theMentorsCrewListNamesEveryMembersState() throws Exception {
		this.mvc.perform(get("/api/v1/boards/" + this.job + "/progress").with(as(this.hung, Role.MENTOR)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.crew.length()").value(5))
			.andExpect(jsonPath("$.crew[?(@.name == 'Trần Thảo Vy')].state").value("ACTIVE"))
			.andExpect(jsonPath("$.crew[?(@.name == 'Trần Thảo Vy')].openItems.length()").value(2))
			.andExpect(jsonPath("$.crew[?(@.name == 'Lê Minh Tùng')].state").value("ACTIVE"))
			.andExpect(jsonPath("$.crew[?(@.name == 'Nguyễn Văn Minh')].state").value("INACTIVE"))
			.andExpect(jsonPath("$.crew[?(@.name == 'Phạm Thu An')].state").value("ASSIGNED"))
			.andExpect(jsonPath("$.crew[?(@.name == 'Hoàng Kim Ngân')].state").value("RELEASED"));
	}

	private void assign(UUID item, UUID assignee, String hours, String status) {
		this.jdbc.sql("""
				UPDATE work.work_item SET assignee_account_id = :assignee, estimate_hours = CAST(:hours AS NUMERIC),
				    status = :status, version = 2
				WHERE id = :id
				""").param("assignee", assignee).param("hours", hours).param("status", status).param("id", item).update();
	}

	private ResultActions update(String id, String body) throws Exception {
		return this.mvc.perform(put("/api/v1/work-items/" + id).with(as(this.hung, Role.MENTOR)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private RequestPostProcessor as(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

}
