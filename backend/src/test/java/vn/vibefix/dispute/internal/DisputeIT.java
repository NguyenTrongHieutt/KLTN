package vn.vibefix.dispute.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.identity.internal.AdministratorTestSupport;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.team.internal.TeamTestSupport;
import vn.vibefix.timers.DeadlineRequest;
import vn.vibefix.timers.Deadlines;

/**
 * FR-52, §4.0 freeze rule: the Customer or the Mentor opens one Dispute on a running Job, which records
 * what it concerns, freezes the Job, and pauses its pausable Deadlines in the same transaction; the other
 * side and every Administrator are told; a Crew member and a second Dispute are refused.
 */
class DisputeIT extends IntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private Deadlines deadlines;

	@Autowired
	private ApplicationContext context;

	@Autowired
	private TransactionTemplate transactions;

	private UUID lan;

	private UUID hung;

	private UUID vy;

	private UUID held;

	@Test
	void theCustomersDisputeFreezesTheJobAndItsPausableDeadlines() throws Exception {
		UUID job = world("ACTIVE");
		UUID admin = AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương").accountId();
		this.transactions.executeWithoutResult(status -> this.deadlines.schedule(new DeadlineRequest(
				"job.cancellation_answer", UUID.randomUUID(), "g", job, this.clock.instant().plus(Duration.ofDays(3)))));
		read(this.lan, Role.CUSTOMER, job).andExpect(jsonPath("$.canOpen").value(true));
		open(this.vy, Role.STUDENT, job).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.reason").value("MENTOR_ONLY"));

		open(this.lan, Role.CUSTOMER, job).andExpect(status().isCreated())
			.andExpect(jsonPath("$.open.openedBy").value("CUSTOMER"))
			.andExpect(jsonPath("$.open.concern").value("HELD_MILESTONE"))
			.andExpect(jsonPath("$.open.milestoneId").value(this.held.toString()))
			.andExpect(jsonPath("$.canOpen").value(false));
		assertThat(count("SELECT count(*) FROM job.job WHERE id = '" + job + "' AND frozen_at IS NOT NULL")).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM timers.frozen_job WHERE job_id = '" + job + "'")).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM timers.deadline WHERE job_id = '" + job + "' AND status = 'PAUSED'"))
			.isEqualTo(1);
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type = 'dispute.dispute_opened' AND account_id IN ('"
				+ this.hung + "', '" + admin + "')")).isEqualTo(2);
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type = 'dispute.dispute_opened' AND account_id = '"
				+ this.lan + "'")).isZero();
		assertThat(count("SELECT count(*) FROM audit.audit_entry WHERE action = 'dispute.dispute_opened' AND job_id = '" + job
				+ "'")).isEqualTo(1);
		open(this.hung, Role.MENTOR, job).andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("DISPUTE_ALREADY_OPEN"));
		read(this.vy, Role.STUDENT, job).andExpect(status().isOk())
			.andExpect(jsonPath("$.audience").value("CREW"))
			.andExpect(jsonPath("$.open.mine").value(false))
			.andExpect(jsonPath("$.canOpen").value(false));
	}

	@Test
	void aClosedJobCannotBeDisputedAndAJobWithoutAHeldMilestoneConcernsItsNextState() throws Exception {
		UUID closed = world("CLOSED");
		open(this.lan, Role.CUSTOMER, closed).andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("JOB_NOT_DISPUTABLE"));
		UUID agreement = world("AGREEMENT");
		this.jdbc.sql("UPDATE job.milestone SET state = 'PLANNED', funded_at = NULL, crew_terms_version_no = NULL WHERE job_id = :job")
			.param("job", agreement).update();
		open(this.hung, Role.MENTOR, agreement).andExpect(status().isCreated())
			.andExpect(jsonPath("$.open.concern").value("NEXT_STATE"));
	}

	private UUID world(String state) {
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
				VALUES (:id, :owner, 'Sổ đặt bàn', 'Đặt bàn.', ARRAY['LOVABLE'], :github, 'lan/so-dat-ban', 'User', 'main',
				    '1.0', '1.0', '1.0', now(), now())
				""").param("id", product).param("owner", this.lan)
			.param("github", ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE)).update();
		UUID job = UUID.randomUUID();
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, budget, deadline, rules, selection_days, opened_at, lapses_at, mentor_account_id,
				    selected_application_id, selected_at, version, created_at, updated_at)
				VALUES (:id, :product, :owner, :state, 'Sổ đặt bàn', 'Lỗi', 'Đúng', 'Khách', ARRAY['BUG_FIX'],
				    'UNDER_2M', DATE '2026-12-01', '{}'::jsonb, 14, :now, :now, :mentor, :application, :now, 6, :now, :now)
				""").param("id", job).param("product", product).param("owner", this.lan).param("mentor", this.hung)
			.param("state", state).param("application", UUID.randomUUID()).param("now", now).update();
		this.held = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO job.milestone (id, job_id, position, title, price_vnd, due_on, revision_rounds, created_at, state,
				    funded_at, crew_terms_version_no)
				VALUES (:id, :job, 0, 'Sửa lỗi trùng giờ', 800000, DATE '2026-11-20', 2, :now, 'FUNDED', :now, 1)
				""").param("id", this.held).param("job", job).param("now", now).update();
		Timestamp signed = Timestamp.from(this.clock.instant().minus(Duration.ofDays(5)));
		this.jdbc.sql("""
				INSERT INTO job.crew_row (id, job_id, member_account_id, status, signed_at, activated_at, created_at)
				VALUES (:id, :job, :student, 'ACTIVE', :signed, :signed, now())
				""").param("id", UUID.randomUUID()).param("job", job).param("student", this.vy).param("signed", signed).update();
		return job;
	}

	private ResultActions read(UUID account, Role role, UUID job) throws Exception {
		return this.mvc.perform(get("/api/v1/boards/" + job + "/dispute").with(as(account, role)));
	}

	private ResultActions open(UUID account, Role role, UUID job) throws Exception {
		return this.mvc.perform(post("/api/v1/boards/" + job + "/disputes").with(as(account, role)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"reason\":\"Giai đoạn chưa đạt mà vẫn bị đòi nghiệm thu\"}"));
	}

	private long count(String sql) {
		return this.jdbc.sql(sql).query(Long.class).single();
	}

	private RequestPostProcessor as(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

}
