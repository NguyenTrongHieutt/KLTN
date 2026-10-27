package vn.vibefix.matching.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

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
import vn.vibefix.support.MutableClock;
import vn.vibefix.team.internal.TeamTestSupport;

/** FR-15, AD-4, AD-5, AD-30, SG-52: J3 selects an Application, and the others go on hold. */
class SelectionIT extends IntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	private UUID lan;

	private UUID product;

	private UUID job;

	@BeforeEach
	void aCustomerWithAnOpenJobPost() {
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		this.product = product(this.lan);
		this.job = openJob("Sửa lỗi mất đơn hàng");
	}

	@Test
	void selectingRecordsTheMentorPinsTheProductAndPutsTheOthersOnHold() throws Exception {
		UUID hung = mentor("Đỗ Mạnh Hùng", true);
		UUID ha = mentor("Phạm Thu Hà", true);
		UUID chosen = application(hung);
		UUID other = application(ha);
		Instant ndaDue = this.clock.instant().plus(Duration.ofDays(3));

		select(chosen, 1).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));

		assertThat(this.jdbc.sql("SELECT state || ' ' || mentor_account_id || ' ' || selected_application_id FROM job.job WHERE id = :id")
			.param("id", this.job).query(String.class).single()).isEqualTo("SELECTING " + hung + " " + chosen);
		assertThat(this.jdbc.sql("SELECT count(*) FROM product.job_pin WHERE product_id = :p AND job_id = :j")
			.param("p", this.product).param("j", this.job).query(Long.class).single()).isOne();
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM notification.notification WHERE type = 'job.assignee_selected' AND account_id = :hung
				""").param("hung", hung).query(Long.class).single()).isOne();
		await().atMost(Duration.ofSeconds(10)).until(() -> "ON_HOLD".equals(applicationStatus(other)) && "SELECTED".equals(applicationStatus(chosen)));
		await().atMost(Duration.ofSeconds(10)).until(() -> this.jdbc.sql("""
				SELECT count(*) FROM notification.notification WHERE type = 'matching.applications_on_hold' AND account_id = :ha
				""").param("ha", ha).query(Long.class).single() == 1);
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM notification.todo WHERE todo_key = 'matching.sign_nda' AND account_id = :hung
				  AND closed_at IS NULL AND deadline_at = :due
				""").param("hung", hung).param("due", Timestamp.from(ndaDue)).query(Long.class).single()).isOne();

		this.mvc.perform(get("/api/v1/jobs/" + this.job).with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.state").value("SELECTING"))
			.andExpect(jsonPath("$.mentor.displayName").value("Đỗ Mạnh Hùng"))
			.andExpect(jsonPath("$.ndaDueAt").value(ndaDue.toString()));
		this.mvc.perform(get("/api/v1/mentor/capacity").with(as(hung, Role.MENTOR)))
			.andExpect(jsonPath("$.activeJobs").value(1));
		select(other, 2).andExpect(status().isConflict());
	}

	@Test
	void aDecisionOnAnOlderVersionIsStaleAndChangesNothing() throws Exception {
		UUID chosen = application(mentor("Đỗ Mạnh Hùng", true));

		select(chosen, 0).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE"));

		assertThat(this.jdbc.sql("SELECT state FROM job.job WHERE id = :id").param("id", this.job)
			.query(String.class).single()).isEqualTo("OPEN");
		assertThat(applicationStatus(chosen)).isEqualTo("SUBMITTED");
	}

	@Test
	void j3ChecksTheMentorAgain() throws Exception {
		UUID unlinked = mentor("Đã bỏ liên kết", true);
		UUID gone = application(unlinked);
		IdentityTestSupport.unlinkGitHub(this.jdbc, unlinked);
		select(gone, 1).andExpect(status().is(422)).andExpect(jsonPath("$.reason").value("MENTOR_NOT_AVAILABLE"));

		UUID busy = application(mentor("Nhóm chưa xác minh", false));
		select(busy, 1).andExpect(status().is(422)).andExpect(jsonPath("$.reason").value("MENTOR_NO_FREE_STUDENT"));

		UUID full = mentor("Đủ việc", true);
		UUID atLimit = application(full);
		this.jdbc.sql("UPDATE settings.rule_value SET value = 1 WHERE rule_key = 'MENTOR_ACTIVE_JOB_LIMIT'").update();
		try {
			UUID running = openJob("Đang chạy");
			this.jdbc.sql("""
					UPDATE job.job SET state = 'SELECTING', mentor_account_id = :m, selected_application_id = :a,
					    selected_at = now(), opened_at = NULL, lapses_at = NULL WHERE id = :id
					""").param("m", full).param("a", UUID.randomUUID()).param("id", running).update();
			select(atLimit, 1).andExpect(status().is(422)).andExpect(jsonPath("$.reason").value("MENTOR_AT_JOB_LIMIT"));
		}
		finally {
			this.jdbc.sql("UPDATE settings.rule_value SET value = 3 WHERE rule_key = 'MENTOR_ACTIVE_JOB_LIMIT'").update();
		}

		UUID other = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		this.mvc.perform(post("/api/v1/jobs/" + this.job + "/applications/" + atLimit + "/select")
			.with(as(other, Role.CUSTOMER)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}")).andExpect(status().isNotFound());
		assertThat(this.jdbc.sql("SELECT state FROM job.job WHERE id = :id").param("id", this.job)
			.query(String.class).single()).isEqualTo("OPEN");
	}

	@Test
	void j3RefusesAMentorWithACountedCrewRowOnTheJobBeforeAnyOtherGuard() throws Exception {
		// AD-43: no command puts a Crew row on an Open Job, so the row is written directly. The Mentor's Team has no
		// verified Student, so any later guard would refuse with MENTOR_NO_FREE_STUDENT instead.
		UUID crewed = mentor("Đang trong nhóm", false);
		UUID chosen = application(crewed);
		UUID row = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO job.crew_row (id, job_id, member_account_id, status, created_at)
				VALUES (:id, :job, :member, 'ASSIGNED', now())
				""").param("id", row).param("job", this.job).param("member", crewed).update();

		select(chosen, 1).andExpect(status().is(422))
			.andExpect(jsonPath("$.code").value("GUARD_FAILED"))
			.andExpect(jsonPath("$.reason").value("ROLE_TAKEN"));
		this.jdbc.sql("""
				UPDATE job.crew_row SET status = 'RELEASED', signed_at = now(), activated_at = now(), window_end = now(),
				    released_at = now(), released_reason = 'JOB_ENDED' WHERE id = :id
				""").param("id", row).update();
		// A row released after it was activated still counts.
		select(chosen, 1).andExpect(status().is(422)).andExpect(jsonPath("$.reason").value("ROLE_TAKEN"));
		this.jdbc.sql("UPDATE job.crew_row SET signed_at = NULL, activated_at = NULL, window_end = NULL, released_reason = 'DECLINED'"
				+ " WHERE id = :id").param("id", row).update();
		// A row released without activation holds no role, so the next guard decides.
		select(chosen, 1).andExpect(status().is(422)).andExpect(jsonPath("$.reason").value("MENTOR_NO_FREE_STUDENT"));
		assertThat(this.jdbc.sql("SELECT state FROM job.job WHERE id = :id").param("id", this.job)
			.query(String.class).single()).isEqualTo("OPEN");
	}

	private ResultActions select(UUID application, long version) throws Exception {
		return this.mvc.perform(post("/api/v1/jobs/" + this.job + "/applications/" + application + "/select")
			.with(as(this.lan, Role.CUSTOMER))
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"version\":" + version + "}"));
	}

	private String applicationStatus(UUID application) {
		return this.jdbc.sql("SELECT status FROM matching.application WHERE id = :id").param("id", application)
			.query(String.class).single();
	}

	private UUID application(UUID mentor) {
		UUID id = UUID.randomUUID();
		this.clock.advance(Duration.ofSeconds(1));
		this.jdbc.sql("""
				INSERT INTO matching.application (id, job_id, product_id, customer_account_id, mentor_account_id, status,
				    price_vnd, timeline_days, approach, public_code_notice_version, submitted_at)
				VALUES (:id, :job, :product, :owner, :mentor, 'SUBMITTED', 800000, 7, 'Cách làm', '1.0', :now)
				""")
			.param("id", id)
			.param("job", this.job)
			.param("product", this.product)
			.param("owner", this.lan)
			.param("mentor", mentor)
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
		return id;
	}

	/** An approved Mentor with a Team of one Student, verified or not. */
	private UUID mentor(String name, boolean verifiedStudent) {
		UUID mentor = TeamTestSupport.mentor(this.jdbc, name, List.of(TeamTestSupport.REACT),
				"React");
		UUID team = UUID.randomUUID();
		this.jdbc.sql("INSERT INTO team.team (id, mentor_account_id, status, created_at) VALUES (:id, :mentor, 'OPEN', now())")
			.param("id", team)
			.param("mentor", mentor)
			.update();
		UUID student = TeamTestSupport.student(this.jdbc, "Sinh viên của " + name, true, List.of(TeamTestSupport.REACT),
				"React", verifiedStudent ? LocalDate.parse("2026-09-30") : null);
		this.jdbc.sql("""
				INSERT INTO team.membership (id, team_id, student_account_id, joined_at) VALUES (:id, :team, :student, now())
				""").param("id", UUID.randomUUID()).param("team", team).param("student", student).update();
		return mentor;
	}

	private UUID openJob(String title) {
		UUID id = UUID.randomUUID();
		Instant now = this.clock.instant();
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, budget, deadline, rules, selection_days, opened_at, lapses_at, version, created_at, updated_at)
				VALUES (:id, :product, :owner, 'OPEN', :title, 'Lỗi', 'Đúng', 'Khách', ARRAY['BUG_FIX'], 'UNDER_2M',
				    DATE '2026-12-01', '{}'::jsonb, 14, :now, :lapses, 1, :now, :now)
				""")
			.param("id", id)
			.param("product", this.product)
			.param("owner", this.lan)
			.param("title", title)
			.param("now", Timestamp.from(now))
			.param("lapses", Timestamp.from(now.plus(Duration.ofDays(14))))
			.update();
		return id;
	}

	private UUID product(UUID owner) {
		UUID id = UUID.randomUUID();
		Timestamp now = Timestamp.from(MutableClock.DEFAULT_INSTANT);
		this.jdbc.sql("""
				INSERT INTO product.product (id, customer_account_id, name, description, ai_tools, repository_github_id,
				    repository_full_name, repository_owner_type, repository_default_branch, public_notice_version,
				    copy_permission_version, detection_notice_version, acknowledged_at, created_at)
				VALUES (:id, :owner, 'Sổ đặt bàn Quán Mây', 'Đặt bàn cho quán.', ARRAY['LOVABLE'], :github,
				    'lan/so-dat-ban', 'User', 'main', '1.0', '1.0', '1.0', :now, :now)
				""")
			.param("id", id)
			.param("owner", owner)
			.param("github", ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE))
			.param("now", now)
			.update();
		return id;
	}

	private RequestPostProcessor as(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

}
