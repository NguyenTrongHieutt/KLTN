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
import vn.vibefix.identity.internal.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.MutableClock;
import vn.vibefix.team.internal.TeamTestSupport;
import vn.vibefix.timers.internal.DeadlinePoller;

/** FR-15, FR-18, AD-7, SM-8 "a selection declined": J4 by signature, J5 by decline or cancel. */
class NdaIT extends IntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private DeadlinePoller poller;

	private UUID lan;

	private UUID product;

	private UUID job;

	private UUID hung;

	private UUID ha;

	private UUID hungApplication;

	private UUID haApplication;

	@BeforeEach
	void aJobInSelectingWithAnotherApplicationOnHold() throws Exception {
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		this.product = product(this.lan);
		this.job = openJob();
		this.hung = mentor("Đỗ Mạnh Hùng");
		this.ha = mentor("Phạm Thu Hà");
		this.hungApplication = application(this.hung);
		this.haApplication = application(this.ha);
		this.mvc.perform(post("/api/v1/jobs/" + this.job + "/applications/" + this.hungApplication + "/select")
			.with(as(this.lan, Role.CUSTOMER)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}")).andExpect(status().isOk());
		await().atMost(Duration.ofSeconds(10)).until(() -> "ON_HOLD".equals(applicationStatus(this.haApplication)));
	}

	@Test
	void signingTheNdaMovesTheJobToAgreementAndClosesTheHolds() throws Exception {
		this.mvc.perform(get("/api/v1/mentor/jobs/" + this.job).with(as(this.hung, Role.MENTOR)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("SELECTING"))
			.andExpect(jsonPath("$.codeJob").value(true))
			.andExpect(jsonPath("$.commitEmailNoticeAcknowledged").value(false))
			.andExpect(jsonPath("$.ndaDueAt").exists());
		this.mvc.perform(get("/api/v1/mentor/jobs/" + this.job).with(as(this.ha, Role.MENTOR))).andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/mentor/jobs").with(as(this.hung, Role.MENTOR)))
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].title").value("Sửa lỗi mất đơn hàng"));

		sign(this.hung, "0.9", true).andExpect(status().isBadRequest()).andExpect(jsonPath("$.reason").value("NDA_VERSION_OUTDATED"));
		sign(this.hung, "1.0", false).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reason").value("COMMIT_EMAIL_NOT_ACKNOWLEDGED"));
		sign(this.ha, "1.0", true).andExpect(status().isNotFound());
		sign(this.hung, "1.0", true).andExpect(status().isNoContent());

		assertThat(jobState()).isEqualTo("AGREEMENT");
		assertThat(this.jdbc.sql("SELECT nda_version FROM matching.nda_signature WHERE job_id = :job AND account_id = :hung")
			.param("job", this.job).param("hung", this.hung).query(String.class).single()).isEqualTo("1.0");
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM identity.acknowledgment WHERE account_id = :hung AND notice = 'COMMIT_EMAIL'
				""").param("hung", this.hung).query(Long.class).single()).isOne();
		await().atMost(Duration.ofSeconds(10)).until(() -> "CLOSED".equals(applicationStatus(this.haApplication)));
		await().atMost(Duration.ofSeconds(10)).until(() -> this.jdbc.sql("""
				SELECT count(*) FROM notification.notification WHERE type = 'matching.applications_closed' AND account_id = :ha
				""").param("ha", this.ha).query(Long.class).single() == 1);
		assertThat(openTodos("matching.sign_nda")).isZero();
		sign(this.hung, "1.0", true).andExpect(status().isConflict());
	}

	@Test
	void aDeclinedSelectionReopensTheJobPostAndTheHolds() throws Exception {
		Instant declinedAt = this.clock.instant();

		this.mvc.perform(post("/api/v1/mentor/jobs/" + this.job + "/decline").with(as(this.hung, Role.MENTOR)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content("{\"version\":2}")).andExpect(status().isNoContent());

		assertThat(jobState()).isEqualTo("OPEN");
		assertThat(this.jdbc.sql("""
				SELECT mentor_account_id IS NULL AND opened_at = :now AND lapses_at = :lapses FROM job.job WHERE id = :id
				""").param("id", this.job).param("now", Timestamp.from(declinedAt))
			.param("lapses", Timestamp.from(declinedAt.plus(Duration.ofDays(14)))).query(Boolean.class).single())
			.as("the selection period restarts").isTrue();
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM timers.deadline WHERE kind = 'job.selection_lapse' AND subject_id = :id
				  AND generation = '3' AND status = 'PENDING'
				""").param("id", this.job).query(Long.class).single()).isOne();
		assertThat(this.jdbc.sql("SELECT count(*) FROM product.job_pin WHERE job_id = :id").param("id", this.job)
			.query(Long.class).single()).as("the pin is released").isZero();
		assertThat(this.jdbc.sql("""
				SELECT params ->> 'mentor' FROM notification.notification WHERE type = 'job.selection_declined' AND account_id = :lan
				""").param("lan", this.lan).query(String.class).single()).isEqualTo("Đỗ Mạnh Hùng");
		await().atMost(Duration.ofSeconds(10)).until(() -> "SUBMITTED".equals(applicationStatus(this.haApplication)));
		assertThat(this.jdbc.sql("SELECT status || ' ' || end_reason FROM matching.application WHERE id = :id")
			.param("id", this.hungApplication).query(String.class).single()).isEqualTo("CLOSED DECLINED");
		await().atMost(Duration.ofSeconds(10)).until(() -> this.jdbc.sql("""
				SELECT count(*) FROM notification.notification WHERE type = 'matching.applications_reopened' AND account_id = :ha
				""").param("ha", this.ha).query(Long.class).single() == 1);
		assertThat(openTodos("matching.sign_nda")).isZero();
		assertThat(this.jdbc.sql("""
				SELECT params ->> 'n' FROM notification.todo WHERE todo_key = 'matching.choose_assignee' AND subject_id = :job
				  AND closed_at IS NULL
				""").param("job", this.job).query(String.class).single()).isEqualTo("1");
	}

	@Test
	void afterTheSignaturePeriodTheCustomerIsToldAndMayCancelTheSelection() throws Exception {
		cancel(2).andExpect(status().is(422)).andExpect(jsonPath("$.reason").value("NDA_PERIOD_RUNNING"));
		this.mvc.perform(get("/api/v1/jobs/" + this.job).with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.canCancelSelection").value(false));

		this.clock.advance(Duration.ofDays(3).plusMinutes(1));
		this.poller.runDue();

		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM notification.notification WHERE type = 'matching.nda_overdue' AND account_id = :lan
				""").param("lan", this.lan).query(Long.class).single()).isOne();
		this.mvc.perform(get("/api/v1/jobs/" + this.job).with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.canCancelSelection").value(true));
		cancel(1).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE"));
		cancel(2).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("OPEN"));
		await().atMost(Duration.ofSeconds(10)).until(() -> this.jdbc.sql("""
				SELECT status || ' ' || end_reason FROM matching.application WHERE id = :id
				""").param("id", this.hungApplication).query(String.class).single().equals("CLOSED NOT_SIGNED"));
		assertThat(this.jdbc.sql("SELECT count(*) FROM notification.notification WHERE type = 'job.selection_declined'")
			.query(Long.class).single()).as("only a decline tells the Customer").isZero();
	}

	@Test
	void aMentorWithoutGitHubLinkedCannotSign() throws Exception {
		IdentityTestSupport.unlinkGitHub(this.jdbc, this.hung);

		sign(this.hung, "1.0", true).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.reason").value("GITHUB_NOT_LINKED"));
		assertThat(jobState()).isEqualTo("SELECTING");
	}

	private ResultActions sign(UUID mentor, String version, boolean acknowledged) throws Exception {
		return this.mvc.perform(post("/api/v1/mentor/jobs/" + this.job + "/nda").with(as(mentor, Role.MENTOR)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"ndaVersion\":\"" + version + "\",\"commitEmailNoticeAcknowledged\":" + acknowledged + "}"));
	}

	private ResultActions cancel(long version) throws Exception {
		return this.mvc.perform(post("/api/v1/jobs/" + this.job + "/selection/cancel").with(as(this.lan, Role.CUSTOMER))
			.with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + version + "}"));
	}

	private String jobState() {
		return this.jdbc.sql("SELECT state FROM job.job WHERE id = :id").param("id", this.job).query(String.class).single();
	}

	private long openTodos(String key) {
		return this.jdbc.sql("""
				SELECT count(*) FROM notification.todo WHERE todo_key = :key AND subject_id = :job AND closed_at IS NULL
				""").param("key", key).param("job", this.job).query(Long.class).single();
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

	private UUID mentor(String name) {
		UUID mentor = TeamTestSupport.mentor(this.jdbc, name, List.of(TeamTestSupport.REACT),
				"React");
		UUID team = UUID.randomUUID();
		this.jdbc.sql("INSERT INTO team.team (id, mentor_account_id, status, created_at) VALUES (:id, :mentor, 'OPEN', now())")
			.param("id", team)
			.param("mentor", mentor)
			.update();
		UUID student = TeamTestSupport.student(this.jdbc, "Sinh viên của " + name, true, List.of(TeamTestSupport.REACT),
				"React", LocalDate.parse("2026-09-30"));
		this.jdbc.sql("""
				INSERT INTO team.membership (id, team_id, student_account_id, joined_at) VALUES (:id, :team, :student, now())
				""").param("id", UUID.randomUUID()).param("team", team).param("student", student).update();
		return mentor;
	}

	private UUID openJob() {
		UUID id = UUID.randomUUID();
		Instant now = this.clock.instant();
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, budget, deadline, rules, selection_days, opened_at, lapses_at, version, created_at, updated_at)
				VALUES (:id, :product, :owner, 'OPEN', 'Sửa lỗi mất đơn hàng', 'Lỗi', 'Đúng', 'Khách', ARRAY['BUG_FIX'],
				    'UNDER_2M', DATE '2026-12-01', '{}'::jsonb, 14, :now, :lapses, 1, :now, :now)
				""")
			.param("id", id)
			.param("product", this.product)
			.param("owner", this.lan)
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
