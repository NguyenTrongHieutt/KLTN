package vn.vibefix.repo.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.job.JobTransitioned;
import vn.vibefix.repo.DataDeletions;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.team.internal.TeamTestSupport;

/**
 * FR-66, AD-19, AD-41, UX-DR51: J8 asks the Mentor and every Crew member who was ever active, a released
 * one included, to confirm data deletion; each item is confirmed once after the Job ended, audited, and
 * both close the to-do; the Customer reads everyone's status but has no duty; {@code reputation} reads
 * the outcome through the query API.
 */
class DataDeletionIT extends IntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private RepoListeners listeners;

	@Autowired
	private DataDeletions deletions;

	@Test
	void theMentorAndEveryEverActiveMemberConfirmAfterTheJobEnds() throws Exception {
		UUID lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		UUID hung = TeamTestSupport.mentor(this.jdbc, "Đỗ Mạnh Hùng",
				List.of(TeamTestSupport.REACT), "React");
		UUID vy = student("Trần Thảo Vy");
		UUID hai = student("Lê Văn Hải");
		UUID never = student("Ngô Chưa Ký");
		UUID job = job(lan, hung);
		crewRow(job, vy, "ACTIVE", true);
		crewRow(job, hai, "RELEASED", true);
		crewRow(job, never, "RELEASED", false);

		ticks(vy, Role.STUDENT, job, "NON_CODE_DATA").andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("JOB_NOT_ENDED"));

		this.jdbc.sql("UPDATE job.job SET state = 'CLOSING' WHERE id = :id").param("id", job).update();
		this.listeners.onJobTransitioned(new JobTransitioned(UUID.randomUUID(), UUID.randomUUID(), job, "J8", "USER", "ACTIVE",
				"CLOSING", 6, 1, Map.of()));
		await().atMost(Duration.ofSeconds(10)).until(() -> openTodos() == 3);
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM notification.notification WHERE type = 'repo.data_deletion_confirmation_pending'
				""").query(Long.class).single()).isEqualTo(3);
		assertThat(this.jdbc.sql("SELECT count(*) FROM notification.todo WHERE account_id = :never")
			.param("never", never).query(Long.class).single()).isZero();

		ticks(hai, Role.STUDENT, job, "NON_CODE_DATA").andExpect(status().isOk())
			.andExpect(jsonPath("$.ended").value(true))
			.andExpect(jsonPath("$.mine[?(@.item == 'NON_CODE_DATA')].confirmedAt").isNotEmpty())
			.andExpect(jsonPath("$.parties.length()").value(1))
			.andExpect(jsonPath("$.parties[0].confirmed").value(false));
		ticks(hai, Role.STUDENT, job, "NON_CODE_DATA").andExpect(status().isOk());
		ticks(hai, Role.STUDENT, job, "PREVIEWS").andExpect(status().isOk())
			.andExpect(jsonPath("$.parties[0].confirmed").value(true));
		assertThat(this.deletions.confirmed(job, hai)).isTrue();
		assertThat(this.deletions.confirmed(job, hung)).isFalse();
		assertThat(openTodos()).isEqualTo(2);
		assertThat(this.jdbc.sql("""
				SELECT action FROM audit.audit_entry WHERE action LIKE 'repo.data_deletion%' ORDER BY occurred_at, action
				""").query(String.class).list())
			.containsExactlyInAnyOrder("repo.data_deletion_item_confirmed", "repo.data_deletion_item_confirmed",
					"repo.data_deletion_confirmed");

		// The Customer reads the Mentor and the Crew as one party: Customer screens never name a Student.
		this.mvc.perform(get("/api/v1/boards/" + job + "/data-deletion").with(as(lan, Role.CUSTOMER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.mine").doesNotExist())
			.andExpect(jsonPath("$.parties.length()").value(2))
			.andExpect(jsonPath("$.parties[0].party").value("MENTOR"))
			.andExpect(jsonPath("$.parties[0].name").value("Đỗ Mạnh Hùng"))
			.andExpect(jsonPath("$.parties[1].party").value("TEAM"))
			.andExpect(jsonPath("$.parties[1].name").value(""))
			.andExpect(jsonPath("$.parties[1].confirmed").value(false));
		this.mvc.perform(get("/api/v1/boards/" + job + "/data-deletion").with(as(hung, Role.MENTOR)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.parties.length()").value(3))
			.andExpect(jsonPath("$.parties[?(@.name == 'Lê Văn Hải')].confirmed").value(true));
		ticks(lan, Role.CUSTOMER, job, "PREVIEWS").andExpect(status().isForbidden())
			.andExpect(jsonPath("$.reason").value("NOT_A_DUTY"));
		this.mvc.perform(get("/api/v1/boards/" + job + "/data-deletion").with(as(never, Role.STUDENT)))
			.andExpect(status().isNotFound());
	}

	private ResultActions ticks(UUID account, Role role, UUID job, String item) throws Exception {
		return this.mvc.perform(post("/api/v1/boards/" + job + "/data-deletion/" + item).with(as(account, role)).with(csrf()));
	}

	private long openTodos() {
		return this.jdbc.sql("SELECT count(*) FROM notification.todo WHERE todo_key = 'repo.confirm_data_deletion' AND closed_at IS NULL")
			.query(Long.class).single();
	}

	private UUID student(String name) {
		return TeamTestSupport.student(this.jdbc, name, true, List.of(TeamTestSupport.REACT), "React", LocalDate.parse("2026-09-30"));
	}

	private UUID job(UUID owner, UUID mentor) {
		UUID product = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO product.product (id, customer_account_id, name, description, ai_tools, repository_github_id,
				    repository_full_name, repository_owner_type, repository_default_branch, public_notice_version,
				    copy_permission_version, detection_notice_version, acknowledged_at, created_at)
				VALUES (:id, :owner, 'Sổ đặt bàn', 'Đặt bàn.', ARRAY['LOVABLE'], :github, 'lan/so-dat-ban', 'User', 'main',
				    '1.0', '1.0', '1.0', now(), now())
				""").param("id", product).param("owner", owner)
			.param("github", ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE)).update();
		UUID job = UUID.randomUUID();
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, budget, deadline, rules, selection_days, opened_at, lapses_at, mentor_account_id,
				    selected_application_id, selected_at, version, created_at, updated_at)
				VALUES (:id, :product, :owner, 'ACTIVE', 'Đánh giá sổ đặt bàn', 'Lỗi', 'Đúng', 'Khách', ARRAY['ASSESSMENT'],
				    'UNDER_2M', DATE '2026-12-01', '{}'::jsonb, 14, :now, :now, :mentor, :application, :now, 5, :now, :now)
				""").param("id", job).param("product", product).param("owner", owner).param("mentor", mentor)
			.param("application", UUID.randomUUID()).param("now", now).update();
		return job;
	}

	private void crewRow(UUID job, UUID student, String status, boolean wasActive) {
		Timestamp signed = Timestamp.from(this.clock.instant().minus(Duration.ofDays(5)));
		boolean released = "RELEASED".equals(status);
		this.jdbc.sql("""
				INSERT INTO job.crew_row (id, job_id, member_account_id, status, signed_at, activated_at, window_end, released_at,
				    released_reason, created_at)
				VALUES (:id, :job, :student, :status, :signed, :activated, :end, :released, :reason, now())
				""")
			.param("id", UUID.randomUUID())
			.param("job", job)
			.param("student", student)
			.param("status", status)
			.param("signed", wasActive ? signed : null)
			.param("activated", wasActive ? signed : null)
			.param("end", released && wasActive ? Timestamp.from(this.clock.instant()) : null)
			.param("released", released ? Timestamp.from(this.clock.instant()) : null)
			.param("reason", released ? (wasActive ? "REMOVED" : "DECLINED") : null)
			.update();
	}

	private RequestPostProcessor as(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

}
