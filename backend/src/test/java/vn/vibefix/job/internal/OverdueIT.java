package vn.vibefix.job.internal;

import static org.assertj.core.api.Assertions.assertThat;
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
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.team.internal.TeamTestSupport;
import vn.vibefix.timers.internal.DeadlinePoller;

/**
 * FR-48, AD-7, AD-33: a held Milestone due on 20/01 reminds the Mentor and the active Crew on 19/01 at
 * 00:00 Vietnam time and is flagged overdue on 21/01 at 00:00, telling the three sides; the flag moves no
 * state and no money. A handover first leaves both obsolete, and a Change Request to a later date clears
 * the flag and schedules a new pair.
 */
class OverdueIT extends IntegrationTest {

	private static final LocalDate DUE = LocalDate.parse("2026-01-20");

	private static final LocalDate NEW_DUE = LocalDate.parse("2026-02-10");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private DueDates dueDates;

	@Autowired
	private DeadlinePoller poller;

	private UUID lan;

	private UUID hung;

	private UUID vy;

	private UUID milestone;

	@Test
	void aFundedMilestoneIsRemindedThenFlaggedOverdueWithoutMovingAnything() throws Exception {
		UUID job = world();
		this.dueDates.schedule(job, this.milestone);
		assertThat(dueAt("job.milestone_due_soon")).isEqualTo(Instant.parse("2026-01-18T17:00:00Z"));
		assertThat(dueAt("job.milestone_overdue")).isEqualTo(Instant.parse("2026-01-20T17:00:00Z"));

		this.clock.setInstant(Instant.parse("2026-01-19T03:00:00Z"));
		this.poller.runDue();
		assertThat(notifications(job, "job.milestone_due_soon")).isEqualTo(2);
		assertThat(notifications(job, "job.milestone_due_soon", this.lan)).isZero();
		assertThat(notifications(job, "job.milestone_overdue")).isZero();

		this.clock.setInstant(Instant.parse("2026-01-21T03:00:00Z"));
		this.poller.runDue();
		assertThat(notifications(job, "job.milestone_overdue")).isEqualTo(3);
		assertThat(text("SELECT state FROM job.milestone WHERE id = '" + this.milestone + "'")).isEqualTo("FUNDED");
		assertThat(count("SELECT count(*) FROM job.milestone WHERE id = '" + this.milestone + "' AND overdue_since IS NOT NULL"))
			.isEqualTo(1);
		assertThat(count("SELECT count(*) FROM payment.refund WHERE milestone_id = '" + this.milestone + "'")).isZero();
		this.mvc.perform(get("/api/v1/boards/" + job + "/progress").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.milestones[0].state").value("FUNDED"))
			.andExpect(jsonPath("$.milestones[0].overdueSince").value("2026-01-21T03:00:00Z"));
		assertThat(text("SELECT state FROM job.job WHERE id = '" + job + "'")).isEqualTo("ACTIVE");
	}

	@Test
	void aHandoverFirstLeavesBothObsolete() throws Exception {
		UUID job = world();
		this.dueDates.schedule(job, this.milestone);
		this.jdbc.sql("""
				UPDATE job.milestone SET state = 'SUBMITTED', submitted_at = now(), handover_id = :handover WHERE id = :id
				""").param("id", this.milestone).param("handover", UUID.randomUUID()).update();
		this.clock.setInstant(Instant.parse("2026-01-21T03:00:00Z"));
		this.poller.runDue();
		assertThat(notifications(job, "job.milestone_due_soon")).isZero();
		assertThat(notifications(job, "job.milestone_overdue")).isZero();
		assertThat(count("SELECT count(*) FROM timers.deadline WHERE subject_id = '" + this.milestone
				+ "' AND status = 'OBSOLETE'")).isEqualTo(2);
		assertThat(count("SELECT count(*) FROM job.milestone WHERE id = '" + this.milestone + "' AND overdue_since IS NULL"))
			.isEqualTo(1);
	}

	@Test
	void aChangeRequestToALaterDateClearsTheFlagAndSchedulesANewPair() throws Exception {
		UUID job = world();
		this.dueDates.schedule(job, this.milestone);
		this.clock.setInstant(Instant.parse("2026-01-21T03:00:00Z"));
		this.poller.runDue();
		assertThat(count("SELECT count(*) FROM job.milestone WHERE id = '" + this.milestone + "' AND overdue_since IS NOT NULL"))
			.isEqualTo(1);

		String body = """
				{"description":"Cần thêm thời gian","impact":"Thêm ba tuần","items":[{"milestoneId":"%s","newDueOn":"%s"}],
				 "version":%d}
				""".formatted(this.milestone, NEW_DUE, version(job));
		this.mvc.perform(post("/api/v1/boards/" + job + "/change-requests").with(as(this.hung, Role.MENTOR)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(body)).andExpect(status().isCreated());
		String request = JsonPath.read(this.mvc.perform(get("/api/v1/boards/" + job + "/change-requests")
			.with(as(this.lan, Role.CUSTOMER))).andReturn().getResponse().getContentAsString(), "$.open.id");
		command(this.lan, Role.CUSTOMER, request, "approve", job).andExpect(status().isOk());
		command(this.hung, Role.MENTOR, request, "confirm", job).andExpect(status().isOk());

		assertThat(count("SELECT count(*) FROM job.milestone WHERE id = '" + this.milestone + "' AND overdue_since IS NULL"))
			.isEqualTo(1);
		assertThat(count("SELECT count(*) FROM timers.deadline WHERE subject_id = '" + this.milestone + "' AND generation = '"
				+ NEW_DUE + "' AND status = 'PENDING'")).isEqualTo(2);
		this.mvc.perform(get("/api/v1/boards/" + job + "/progress").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.milestones[0].dueOn").value(NEW_DUE.toString()))
			.andExpect(jsonPath("$.milestones[0].overdueSince").doesNotExist());
	}

	/** An Active Job with a Board and a confirmed Agreement: its one Milestone, due 20/01, Funded. */
	private UUID world() {
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
				VALUES (:id, :product, :owner, 'ACTIVE', 'Sửa sổ đặt bàn', 'Lỗi', 'Đúng', 'Khách', ARRAY['BUG_FIX'],
				    'UNDER_2M', DATE '2026-03-01', '{}'::jsonb, 14, :now, :now, :mentor, :application, :now, 6, :now, :now)
				""").param("id", job).param("product", product).param("owner", this.lan).param("mentor", this.hung)
			.param("application", UUID.randomUUID()).param("now", now).update();
		this.milestone = UUID.randomUUID();
		String content = """
				{"scope":"Sửa lỗi","priceVnd":800000,"codeJob":true,"assessment":false,"skills":[],"externalAccess":[],
				 "milestones":[
				   {"id":"%s","title":"Sửa lỗi trùng giờ","priceVnd":800000,"dueOn":"%s","revisionRounds":2,"criteria":[]}]}
				""".formatted(this.milestone, DUE);
		this.jdbc.sql("""
				INSERT INTO job.agreement_version (job_id, version_no, status, content, author_account_id, created_at)
				VALUES (:job, 1, 'SHARED', CAST(:content AS JSONB), :mentor, :now)
				""").param("job", job).param("content", content).param("mentor", this.hung).param("now", now).update();
		this.jdbc.sql("""
				INSERT INTO job.confirmed_agreement (job_id, version_no, draft_version_no, content, crew_usernames,
				    freeze_committed, customer_account_id, approved_at, mentor_account_id, confirmed_at)
				VALUES (:job, 1, 1, CAST(:content AS JSONB), '{}', true, :owner, :now, :mentor, :now)
				""").param("job", job).param("content", content).param("owner", this.lan).param("mentor", this.hung)
			.param("now", now).update();
		this.jdbc.sql("UPDATE job.job SET agreement_version_no = 1 WHERE id = :id").param("id", job).update();
		this.jdbc.sql("""
				INSERT INTO job.milestone (id, job_id, position, title, price_vnd, due_on, revision_rounds, created_at, state,
				    funded_at, crew_terms_version_no)
				VALUES (:id, :job, 0, 'Sửa lỗi trùng giờ', 800000, :due, 2, :now, 'FUNDED', :now, 1)
				""").param("id", this.milestone).param("job", job).param("due", DUE).param("now", now).update();
		this.jdbc.sql("""
				INSERT INTO work.board (job_id, product_id, code, next_number, created_at) VALUES (:job, :product, 'SDB', 1, :now)
				""").param("job", job).param("product", product).param("now", now).update();
		Timestamp signed = Timestamp.from(this.clock.instant().minus(Duration.ofDays(5)));
		this.jdbc.sql("""
				INSERT INTO job.crew_row (id, job_id, member_account_id, status, signed_at, activated_at, created_at)
				VALUES (:id, :job, :student, 'ACTIVE', :signed, :signed, now())
				""").param("id", UUID.randomUUID()).param("job", job).param("student", this.vy).param("signed", signed).update();
		return job;
	}

	private Instant dueAt(String kind) {
		return this.jdbc.sql("SELECT due_at FROM timers.deadline WHERE kind = :kind AND subject_id = :id")
			.param("kind", kind)
			.param("id", this.milestone)
			.query((rs, n) -> rs.getTimestamp("due_at").toInstant())
			.single();
	}

	private ResultActions command(UUID account, Role role, String request, String action, UUID job) throws Exception {
		return this.mvc.perform(post("/api/v1/change-requests/" + request + "/" + action).with(as(account, role)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"version\":" + version(job) + "}"));
	}

	private long version(UUID job) {
		return this.jdbc.sql("SELECT version FROM job.job WHERE id = :id").param("id", job).query(Long.class).single();
	}

	private long notifications(UUID job, String type) {
		return count("SELECT count(*) FROM notification.notification WHERE type = '" + type + "' AND job_id = '" + job + "'");
	}

	private long notifications(UUID job, String type, UUID account) {
		return count("SELECT count(*) FROM notification.notification WHERE type = '" + type + "' AND job_id = '" + job
				+ "' AND account_id = '" + account + "'");
	}

	private long count(String sql) {
		return this.jdbc.sql(sql).query(Long.class).single();
	}

	private String text(String sql) {
		return this.jdbc.sql(sql).query(String.class).single();
	}

	private RequestPostProcessor as(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

}
