package vn.vibefix.delivery.internal;

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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.team.internal.TeamTestSupport;
import vn.vibefix.timers.Deadlines;
import vn.vibefix.timers.internal.DeadlinePoller;

/**
 * FR-35, AD-5, AD-7, SG-26: M2 schedules the pausable Auto-acceptance Deadline and two reminders from
 * the Job's copied days; each reminder restates the date from the Deadline's due time, also after a
 * pause; when it is due, M5 applies as AUTO with the split release, labeled as automatic; a decision or a
 * freeze stops it.
 */
class AutoAcceptanceIT extends IntegrationTest {

	private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("dd/MM");

	private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private DeadlinePoller poller;

	@Autowired
	private Deadlines deadlines;

	@Autowired
	private TransactionTemplate transactions;

	private UUID lan;

	private UUID hung;

	private UUID vy;

	private UUID job;

	private UUID milestone;

	private UUID criterion;

	@Test
	void silenceIsRemindedTwiceThenAcceptedAutomaticallyWithTheRelease() throws Exception {
		world();
		Instant handedOver = this.clock.instant();
		String handover = handOver();
		assertThat(this.jdbc.sql("""
				SELECT kind || ':' || generation || ':' || pausable FROM timers.deadline WHERE subject_id = :id ORDER BY due_at
				""").param("id", UUID.fromString(handover)).query(String.class).list())
			.containsExactly("delivery.acceptance_reminder:1:true", "delivery.acceptance_reminder:2:true",
					"delivery.auto_acceptance:" + handover + ":true");
		String date = DAY_MONTH.format(handedOver.plus(Duration.ofDays(7)).atZone(VIETNAM));
		assertThat(this.jdbc.sql("SELECT params ->> 'date' FROM notification.todo WHERE todo_key = 'delivery.review_handover'")
			.query(String.class).single()).isEqualTo(date);

		this.clock.advance(Duration.ofDays(3));
		this.poller.runDue();
		assertThat(reminders()).containsExactly("4:1:" + date);
		this.clock.advance(Duration.ofDays(3));
		this.poller.runDue();
		assertThat(reminders()).containsExactly("4:1:" + date, "1:1:" + date);
		assertThat(text("SELECT state FROM job.milestone WHERE id = '" + this.milestone + "'")).isEqualTo("SUBMITTED");

		this.clock.advance(Duration.ofDays(1));
		this.poller.runDue();
		assertThat(text("SELECT state || ':' || auto_accepted FROM job.milestone WHERE id = '" + this.milestone + "'"))
			.isEqualTo("ACCEPTED:true");
		assertThat(text("SELECT status || ':' || auto || ':' || (decided_by IS NULL) FROM delivery.handover"))
			.isEqualTo("ACCEPTED:true:true");
		assertThat(this.jdbc.sql("SELECT amount_vnd FROM payment.ledger_entry WHERE entry_type = 'RELEASE' ORDER BY amount_vnd DESC")
			.query(Long.class).list()).containsExactly(1_050_000L, 450_000L);
		assertThat(this.jdbc.sql("""
				SELECT DISTINCT params ->> 'how' FROM notification.notification WHERE type = 'job.milestone_accepted'
				""").query(String.class).list()).containsExactly("tự nghiệm thu");
		assertThat(count("SELECT count(*) FROM audit.audit_entry WHERE action = 'job.milestone_auto_accepted'")).isOne();
		assertThat(count("SELECT count(*) FROM notification.todo WHERE todo_key = 'delivery.review_handover' AND closed_at IS NULL"))
			.isZero();
		this.mvc.perform(get("/api/v1/handovers/" + handover).with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.status").value("ACCEPTED"))
			.andExpect(jsonPath("$.auto").value(true));
		this.mvc.perform(get("/api/v1/boards/" + this.job + "/progress").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.milestones[0].autoAccepted").value(true));
	}

	@Test
	void aDecisionMakesTheDeadlinesObsoleteAndAFreezePausesThem() throws Exception {
		world();
		String handover = handOver();
		this.clock.advance(Duration.ofDays(2));
		this.mvc.perform(post("/api/v1/handovers/" + handover + "/accept").with(as(this.lan, Role.CUSTOMER)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content("{\"version\":6}"))
			.andExpect(status().isOk());
		this.clock.advance(Duration.ofDays(6));
		this.poller.runDue();
		assertThat(reminders()).isEmpty();
		assertThat(this.jdbc.sql("SELECT DISTINCT status FROM timers.deadline WHERE subject_id = :id")
			.param("id", UUID.fromString(handover)).query(String.class).list()).containsExactly("OBSOLETE");
		assertThat(count("SELECT count(*) FROM payment.ledger_entry WHERE entry_type = 'RELEASE'")).isEqualTo(2);
		assertThat(text("SELECT auto::text FROM delivery.handover")).isEqualTo("false");

		world();
		String frozen = handOver();
		this.jdbc.sql("UPDATE job.job SET frozen_at = now() WHERE id = :id").param("id", this.job).update();
		this.clock.advance(Duration.ofDays(3));
		this.poller.runDue();
		assertThat(this.jdbc.sql("""
				SELECT status FROM timers.deadline WHERE subject_id = :id AND kind = 'delivery.acceptance_reminder' AND generation = '1'
				""").param("id", UUID.fromString(frozen)).query(String.class).single()).isEqualTo("PAUSED");
		assertThat(reminders()).isEmpty();
	}

	@Test
	void aReminderAfterAPauseRestatesTheLaterDate() throws Exception {
		world();
		Instant handedOver = this.clock.instant();
		handOver();
		this.clock.advance(Duration.ofDays(1));
		this.transactions.executeWithoutResult(status -> this.deadlines.freeze(this.job));
		this.clock.advance(Duration.ofDays(2));
		this.transactions.executeWithoutResult(status -> this.deadlines.unfreeze(this.job));
		this.clock.advance(Duration.ofDays(2));
		this.poller.runDue();
		String later = DAY_MONTH.format(handedOver.plus(Duration.ofDays(9)).atZone(VIETNAM));
		assertThat(reminders()).containsExactly("4:1:" + later);
	}

	private List<String> reminders() {
		return this.jdbc.sql("""
				SELECT (params ->> 'n') || ':' || (params ->> 'm') || ':' || (params ->> 'date') FROM notification.notification
				WHERE type = 'delivery.acceptance_reminder' AND account_id = :lan ORDER BY created_at, params ->> 'n' DESC
				""").param("lan", this.lan).query(String.class).list();
	}

	/**
	 * An Active Assessment Job with Milestone 1 Funded at 1,500,000 under Crew Terms version 1 (the Mentor
	 * 70, Vy 30), its seeded Story approved and the Assessment Report written; the Job's copied rules are
	 * the defaults (7 days, reminders on days 3 and 6).
	 */
	private void world() throws Exception {
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
				VALUES (:id, :owner, 'Báo cáo đặt bàn', 'Đặt bàn.', ARRAY['LOVABLE'], :github, 'lan/so-dat-ban', 'User', 'main',
				    '1.0', '1.0', '1.0', now(), now())
				""").param("id", product).param("owner", this.lan)
			.param("github", ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE)).update();
		this.job = UUID.randomUUID();
		this.milestone = UUID.randomUUID();
		this.criterion = UUID.randomUUID();
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, budget, deadline, rules, selection_days, opened_at, lapses_at, mentor_account_id,
				    selected_application_id, selected_at, version, created_at, updated_at)
				VALUES (:id, :product, :owner, 'ACTIVE', 'Đánh giá sổ đặt bàn', 'Lỗi', 'Đúng', 'Khách', ARRAY['ASSESSMENT'],
				    'UNDER_2M', DATE '2026-12-01',
				    '{"AUTO_ACCEPTANCE_DAYS":7,"AUTO_ACCEPTANCE_FIRST_REMINDER_DAY":3,"AUTO_ACCEPTANCE_SECOND_REMINDER_DAY":6}'::jsonb,
				    14, :now, :now, :mentor, :application, :now, 5, :now, :now)
				""").param("id", this.job).param("product", product).param("owner", this.lan).param("mentor", this.hung)
			.param("application", UUID.randomUUID()).param("now", now).update();
		String content = """
				{"scope":"Đánh giá","priceVnd":1500000,"codeJob":false,"assessment":true,"skills":[],"externalAccess":[],
				 "milestones":[{"id":"%s","title":"Báo cáo","priceVnd":1500000,"dueOn":"2026-11-20","revisionRounds":2,
				   "criteria":[{"id":"%s","statement":"Báo cáo nêu đủ chín mục","checkSteps":"Mở báo cáo, đếm mục"}]}]}
				""".formatted(this.milestone, this.criterion);
		this.jdbc.sql("""
				INSERT INTO job.agreement_version (job_id, version_no, status, content, author_account_id, created_at)
				VALUES (:job, 1, 'SHARED', CAST(:content AS JSONB), :mentor, :now)
				""").param("job", this.job).param("content", content).param("mentor", this.hung).param("now", now).update();
		this.jdbc.sql("""
				INSERT INTO job.confirmed_agreement (job_id, version_no, draft_version_no, content, crew_usernames,
				    freeze_committed, customer_account_id, approved_at, mentor_account_id, confirmed_at)
				VALUES (:job, 1, 1, CAST(:content AS JSONB), '{}', false, :owner, :now, :mentor, :now)
				""").param("job", this.job).param("content", content).param("owner", this.lan).param("mentor", this.hung)
			.param("now", now).update();
		this.jdbc.sql("UPDATE job.job SET agreement_version_no = 1 WHERE id = :id").param("id", this.job).update();
		UUID row = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO job.crew_row (id, job_id, member_account_id, status, signed_at, activated_at, created_at)
				VALUES (:id, :job, :student, 'ACTIVE', :signed, :signed, now())
				""").param("id", row).param("job", this.job).param("student", this.vy)
			.param("signed", Timestamp.from(this.clock.instant().minus(Duration.ofMinutes(5)))).update();
		this.jdbc.sql("""
				INSERT INTO job.crew_terms_version (job_id, version_no, status, cap_percent, author_account_id, created_at)
				VALUES (:job, 1, 'IN_FORCE', 100, :mentor, now())
				""").param("job", this.job).param("mentor", this.hung).update();
		this.jdbc.sql("""
				INSERT INTO job.crew_terms_share (job_id, version_no, party_account_id, crew_row_id, percent)
				VALUES (:job, 1, :mentor, NULL, 70), (:job, 1, :vy, :row, 30)
				""").param("job", this.job).param("mentor", this.hung).param("vy", this.vy).param("row", row).update();
		this.jdbc.sql("""
				INSERT INTO job.milestone (id, job_id, position, title, price_vnd, revision_rounds, created_at, state, funded_at,
				    crew_terms_version_no)
				VALUES (:id, :job, 0, 'Báo cáo', 1500000, 2, now(), 'FUNDED', now(), 1)
				""").param("id", this.milestone).param("job", this.job).update();
		this.jdbc.sql("""
				INSERT INTO payment.ledger_account (milestone_id, job_id, product_id, created_at) VALUES (:id, :job, :product, now())
				""").param("id", this.milestone).param("job", this.job).param("product", product).update();
		this.jdbc.sql("""
				INSERT INTO payment.ledger_entry (id, operation_key, entry_type, bucket, job_id, product_id, milestone_id, amount_vnd,
				    vnp_transaction_no, created_at)
				VALUES (:id, :key, 'FUND', 'MILESTONE', :job, :product, :milestone, 1500000, :no, now())
				""").param("id", UUID.randomUUID()).param("key", "FUND:" + UUID.randomUUID()).param("job", this.job)
			.param("product", product).param("milestone", this.milestone)
			.param("no", String.valueOf(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE))).update();
		this.jdbc.sql("""
				INSERT INTO work.board (job_id, product_id, code, next_number, created_at) VALUES (:job, :product, 'BCD', 2, :now)
				""").param("job", this.job).param("product", product).param("now", now).update();
		UUID story = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO work.work_item (id, job_id, number, type, title, description, criterion_id, milestone_id, status,
				    created_at, updated_at)
				VALUES (:id, :job, 1, 'STORY', 'Báo cáo nêu đủ chín mục', '', :criterion, :milestone, 'DONE', now(), now())
				""").param("id", story).param("job", this.job).param("criterion", this.criterion).param("milestone", this.milestone)
			.update();
		this.jdbc.sql("""
				INSERT INTO work.work_item_history (id, work_item_id, job_id, actor_account_id, action, changes, comment, at)
				VALUES (:id, :item, :job, :mentor, 'APPROVED', '{}'::jsonb, 'Đạt yêu cầu', :now)
				""").param("id", UUID.randomUUID()).param("item", story).param("job", this.job).param("mentor", this.hung)
			.param("now", now).update();
		this.mvc.perform(post("/api/v1/boards/" + this.job + "/pages").with(as(this.hung, Role.MENTOR)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"Báo cáo đánh giá\",\"body\":\"## Hiện trạng\\n\\nỔn.\",\"template\":\"ASSESSMENT_REPORT\"}"))
			.andExpect(status().isCreated());
	}

	private String handOver() throws Exception {
		String json = "{\"milestoneId\":\"" + this.milestone + "\",\"version\":5,\"criteria\":[{\"criterionId\":\""
				+ this.criterion + "\",\"met\":true,\"note\":\"Đã rà đủ chín mục.\"}]}";
		return JsonPath.read(this.mvc.perform(post("/api/v1/boards/" + this.job + "/handovers").with(as(this.hung, Role.MENTOR))
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(json)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
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
