package vn.vibefix.work.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.team.internal.TeamTestSupport;

/**
 * FR-28, AD-33, AD-39: JOB-1's progress per Milestone counts criteria only through approvals; the
 * Customer sees no Work Item, the Mentor sees the Crew's open work, a Crew member their own; nobody else
 * reads it.
 */
class ProgressIT extends IntegrationTest {

	private static final List<UUID> MILESTONES = List.of(UUID.randomUUID(), UUID.randomUUID());

	private static final List<List<UUID>> CRITERIA = List.of(List.of(UUID.randomUUID(), UUID.randomUUID()),
			List.of(UUID.randomUUID()));

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
	void milestoneOneFundedWithOneCriterionApproved() {
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		this.hung = TeamTestSupport.mentor(this.jdbc, "Đỗ Mạnh Hùng",
				List.of(TeamTestSupport.REACT), "React");
		UUID product = WorkTestSupport.product(this.jdbc, this.lan, "Sổ đặt bàn Quán Mây");
		this.job = WorkTestSupport.activeJob(this.jdbc, product, this.lan, this.hung, MILESTONES, CRITERIA,
				this.clock.instant(), "BUG_FIX");
		this.jdbc.sql("""
				INSERT INTO job.milestone (id, job_id, position, title, price_vnd, revision_rounds, created_at, state, funded_at,
				    crew_terms_version_no)
				VALUES (:id, :job, 0, 'Giai đoạn 1', 1500000, 2, now(), 'FUNDED', now(), 1)
				""").param("id", MILESTONES.get(0)).param("job", this.job).update();
		this.jdbc.sql("""
				INSERT INTO job.milestone (id, job_id, position, title, price_vnd, revision_rounds, created_at)
				VALUES (:id, :job, 1, 'Giai đoạn 2', 1500000, 2, now())
				""").param("id", MILESTONES.get(1)).param("job", this.job).update();
		this.boards.seed(this.job, true);
		this.vy = WorkTestSupport.student(this.jdbc, "Trần Thảo Vy");
		WorkTestSupport.crewRow(this.jdbc, this.job, this.vy, "ACTIVE", this.clock.instant().minus(Duration.ofMinutes(5)));
		this.jdbc.sql("UPDATE work.work_item SET assignee_account_id = :vy WHERE job_id = :job").param("vy", this.vy)
			.param("job", this.job).update();
		UUID approved = item(CRITERIA.get(0).get(0));
		this.jdbc.sql("UPDATE work.work_item SET status = 'DONE' WHERE id = :id").param("id", approved).update();
		this.jdbc.sql("""
				INSERT INTO work.work_item_history (id, work_item_id, job_id, actor_account_id, action, changes, comment, at)
				VALUES (:id, :item, :job, :mentor, 'APPROVED', '{"status":{"from":"IN_REVIEW","to":"DONE"}}', 'Đạt', :at)
				""").param("id", UUID.randomUUID()).param("item", approved).param("job", this.job).param("mentor", this.hung)
			.param("at", Timestamp.from(this.clock.instant())).update();
		this.jdbc.sql("UPDATE work.work_item SET status = 'IN_REVIEW' WHERE id = :id").param("id", item(CRITERIA.get(0).get(1)))
			.update();
	}

	@Test
	void theCustomerSeesEachMilestonesCriteriaDoneAndNoWorkItem() throws Exception {
		String body = this.mvc.perform(get("/api/v1/boards/" + this.job + "/progress").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.audience").value("CUSTOMER"))
			.andExpect(jsonPath("$.productName").value("Sổ đặt bàn Quán Mây"))
			.andExpect(jsonPath("$.mentorName").value("Đỗ Mạnh Hùng"))
			.andExpect(jsonPath("$.milestones.length()").value(2))
			.andExpect(jsonPath("$.milestones[0].state").value("FUNDED"))
			.andExpect(jsonPath("$.milestones[0].done").value(1))
			.andExpect(jsonPath("$.milestones[0].total").value(2))
			.andExpect(jsonPath("$.milestones[0].priceVnd").value(1500000))
			.andExpect(jsonPath("$.milestones[0].dueOn").value("2026-11-20"))
			.andExpect(jsonPath("$.milestones[0].inReview").value(0))
			.andExpect(jsonPath("$.milestones[1].state").value("PLANNED"))
			.andExpect(jsonPath("$.milestones[1].done").value(0))
			.andExpect(jsonPath("$.crew.length()").value(0))
			.andExpect(jsonPath("$.mine.length()").value(0))
			.andReturn().getResponse().getContentAsString();
		assertThat(body).doesNotContain("SDB-").doesNotContain("Tiêu chí 1.1");
	}

	@Test
	void theMentorSeesTheCrewsOpenWorkAndAMemberTheirOwn() throws Exception {
		this.mvc.perform(get("/api/v1/boards/" + this.job + "/progress").with(as(this.hung, Role.MENTOR)))
			.andExpect(jsonPath("$.audience").value("MENTOR"))
			.andExpect(jsonPath("$.milestones[0].inReview").value(1))
			.andExpect(jsonPath("$.crew[0].name").value("Trần Thảo Vy"))
			.andExpect(jsonPath("$.crew[0].openItems.length()").value(2))
			.andExpect(jsonPath("$.crew[0].openItems[0].key").value("SDB-2"));
		this.mvc.perform(get("/api/v1/boards/" + this.job + "/progress").with(as(this.vy, Role.STUDENT)))
			.andExpect(jsonPath("$.audience").value("CREW"))
			.andExpect(jsonPath("$.crew.length()").value(0))
			.andExpect(jsonPath("$.mine.length()").value(2))
			.andExpect(jsonPath("$.mine[0].status").value("IN_REVIEW"));
	}

	@Test
	void nobodyElseReadsTheProgress() throws Exception {
		UUID otherCustomer = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		UUID tung = WorkTestSupport.student(this.jdbc, "Lê Minh Tùng");
		WorkTestSupport.crewRow(this.jdbc, this.job, tung, "ASSIGNED", null);
		UUID admin = IdentityTestSupport.insertAccount(this.jdbc, Role.ADMINISTRATOR, null);
		for (RequestPostProcessor outsider : List.of(as(otherCustomer, Role.CUSTOMER), as(tung, Role.STUDENT),
				as(admin, Role.ADMINISTRATOR))) {
			this.mvc.perform(get("/api/v1/boards/" + this.job + "/progress").with(outsider)).andExpect(status().isNotFound());
		}
	}

	private UUID item(UUID criterion) {
		return this.jdbc.sql("SELECT id FROM work.work_item WHERE criterion_id = :c").param("c", criterion)
			.query(UUID.class).single();
	}

	private RequestPostProcessor as(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

}
