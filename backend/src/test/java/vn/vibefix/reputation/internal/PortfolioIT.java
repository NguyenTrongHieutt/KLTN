package vn.vibefix.reputation.internal;

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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.job.JobTransitioned;
import vn.vibefix.reputation.PortfolioEntry;
import vn.vibefix.reputation.Reputation;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.team.internal.TeamTestSupport;
import vn.vibefix.timers.internal.DeadlinePoller;

/**
 * FR-50, FR-51, FR-66, FR-14: J9 creates a Portfolio Entry for the Mentor and each ever-active Crew
 * member of a Job with Accepted work, with facts from records; the Mentor drafts the summary and the
 * Customer approves, edits, or declines it in the Rating Window, or it lapses; an entry goes public only
 * after its person's data-deletion confirmation.
 */
class PortfolioIT extends IntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private ReputationListeners listeners;

	@Autowired
	private DeadlinePoller poller;

	@Autowired
	private Reputation reputation;

	private UUID lan;

	private UUID hung;

	private UUID vy;

	@Test
	void entriesFreezeFactsTheCustomerApprovesTheSummaryAndConfirmationPublishes() throws Exception {
		people();
		UUID job = job(true);
		assertThat(this.reputation.newcomer(this.hung)).isTrue();
		closes(job);
		await().atMost(Duration.ofSeconds(10))
			.until(() -> count("SELECT count(*) FROM reputation.portfolio_entry WHERE job_id = '" + job + "'") == 2);
		assertThat(openTodo("reputation.draft_portfolio_summary", this.hung, job)).isTrue();
		assertThat(this.reputation.newcomer(this.hung)).isFalse();

		read(this.hung, Role.MENTOR, job).andExpect(status().isOk())
			.andExpect(jsonPath("$.exists").value(true))
			.andExpect(jsonPath("$.summaryStatus").value("DRAFT_DUE"))
			.andExpect(jsonPath("$.open").value(true))
			.andExpect(jsonPath("$.productName").value("Sổ đặt bàn"))
			.andExpect(jsonPath("$.mine.role").value("MENTOR"))
			.andExpect(jsonPath("$.mine.milestones").value(1))
			.andExpect(jsonPath("$.mine.milestonesHandedOver").value(1))
			.andExpect(jsonPath("$.mine.reviewsWritten").value(2))
			.andExpect(jsonPath("$.mine.skills[0]").isNotEmpty())
			.andExpect(jsonPath("$.mine.publishedAt").doesNotExist());
		read(this.vy, Role.STUDENT, job).andExpect(jsonPath("$.mine.role").value("CREW"))
			.andExpect(jsonPath("$.mine.workItemsApproved").value(1))
			.andExpect(jsonPath("$.productName").doesNotExist());

		draft(this.lan, Role.CUSTOMER, job, "Không được").andExpect(status().isForbidden())
			.andExpect(jsonPath("$.reason").value("MENTOR_ONLY"));
		approve(this.vy, Role.STUDENT, job, "Không được", true).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.reason").value("CUSTOMER_ONLY"));
		approve(this.lan, Role.CUSTOMER, job, "Chưa có bản nháp", true).andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("SUMMARY_NOT_AWAITING"));
		draft(this.hung, Role.MENTOR, job, "Nhóm đánh giá toàn bộ luồng đặt bàn.").andExpect(status().isOk())
			.andExpect(jsonPath("$.summaryStatus").value("AWAITING_APPROVAL"));
		draft(this.hung, Role.MENTOR, job, "Lần nữa").andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("SUMMARY_NOT_AWAITING"));
		assertThat(openTodo("reputation.draft_portfolio_summary", this.hung, job)).isFalse();
		assertThat(openTodo("reputation.approve_portfolio_summary", this.lan, job)).isTrue();
		assertThat(count("""
				SELECT count(*) FROM notification.notification WHERE type = 'reputation.portfolio_summary_awaiting_approval'
				""")).isEqualTo(1);
		read(this.vy, Role.STUDENT, job).andExpect(jsonPath("$.summaryText").doesNotExist());
		approve(this.lan, Role.CUSTOMER, job, "Nhóm đánh giá kỹ toàn bộ luồng đặt bàn.", true).andExpect(status().isOk())
			.andExpect(jsonPath("$.summaryStatus").value("APPROVED"))
			.andExpect(jsonPath("$.showProductName").value(true));
		assertThat(openTodo("reputation.approve_portfolio_summary", this.lan, job)).isFalse();
		read(this.vy, Role.STUDENT, job).andExpect(jsonPath("$.summaryText").value("Nhóm đánh giá kỹ toàn bộ luồng đặt bàn."));

		assertThat(this.reputation.portfolio(this.vy)).isEmpty();
		this.mvc.perform(post("/api/v1/boards/" + job + "/ratings").with(as(this.hung, Role.MENTOR)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"rateeId\":\"" + this.vy + "\",\"stars\":4,\"comment\":\"Chăm chỉ\"}")).andExpect(status().isCreated());
		for (String item : List.of("NON_CODE_DATA", "PREVIEWS")) {
			this.mvc.perform(post("/api/v1/boards/" + job + "/data-deletion/" + item).with(as(this.vy, Role.STUDENT)).with(csrf()))
				.andExpect(status().isOk());
		}
		await().atMost(Duration.ofSeconds(10)).until(() -> !this.reputation.portfolio(this.vy).isEmpty());
		PortfolioEntry entry = this.reputation.portfolio(this.vy).getFirst();
		assertThat(entry.title()).isEqualTo("Đánh giá sổ đặt bàn");
		assertThat(entry.role()).isEqualTo("CREW");
		assertThat(entry.jobTypes()).containsExactly("ASSESSMENT");
		assertThat(entry.skills()).hasSize(1);
		assertThat(entry.workItemsApproved()).isEqualTo(1);
		assertThat(entry.summary()).isEqualTo("Nhóm đánh giá kỹ toàn bộ luồng đặt bàn.");
		assertThat(entry.productName()).isEqualTo("Sổ đặt bàn");
		assertThat(entry.rating()).isNull();
		assertThat(this.reputation.portfolio(this.hung)).isEmpty();
		assertThat(count("""
				SELECT count(*) FROM notification.notification
				WHERE type = 'reputation.portfolio_entry_published' AND account_id = '%s'
				""".formatted(this.vy))).isEqualTo(1);
		read(this.vy, Role.STUDENT, job).andExpect(jsonPath("$.mine.publishedAt").isNotEmpty());

		this.clock.advance(Duration.ofDays(15));
		this.poller.runDue();
		assertThat(this.reputation.portfolio(this.vy).getFirst().rating()).isEqualTo(4);
		assertThat(text("SELECT status FROM reputation.portfolio_summary WHERE job_id = '" + job + "'")).isEqualTo("APPROVED");
	}

	@Test
	void aJobWithoutAcceptedWorkHasNoEntry() throws Exception {
		people();
		UUID job = job(false);
		closes(job);
		await().atMost(Duration.ofSeconds(10))
			.until(() -> count("SELECT count(*) FROM reputation.rating_window WHERE job_id = '" + job + "'") == 1);
		assertThat(count("SELECT count(*) FROM reputation.portfolio_entry WHERE job_id = '" + job + "'")).isZero();
		read(this.hung, Role.MENTOR, job).andExpect(jsonPath("$.exists").value(false));
		draft(this.hung, Role.MENTOR, job, "Không có").andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("NO_PORTFOLIO"));
		assertThat(this.reputation.newcomer(this.hung)).isTrue();
	}

	@Test
	void aDeclinedSummaryStaysAwayAndAnUndecidedOneLapses() throws Exception {
		people();
		UUID declined = job(true);
		closes(declined);
		UUID silent = job(true);
		closes(silent);
		await().atMost(Duration.ofSeconds(10))
			.until(() -> count("SELECT count(*) FROM reputation.portfolio_summary WHERE job_id IN ('" + declined + "', '"
					+ silent + "')") == 2);
		draft(this.hung, Role.MENTOR, declined, "Một bản tóm tắt.").andExpect(status().isOk());
		this.mvc.perform(post("/api/v1/boards/" + declined + "/portfolio/summary/decline").with(as(this.lan, Role.CUSTOMER))
			.with(csrf())).andExpect(status().isOk())
			.andExpect(jsonPath("$.summaryStatus").value("DECLINED"))
			.andExpect(jsonPath("$.summaryText").doesNotExist());
		draft(this.hung, Role.MENTOR, silent, "Bản chưa ai duyệt.").andExpect(status().isOk());

		this.clock.advance(Duration.ofDays(15));
		this.poller.runDue();
		assertThat(text("SELECT status FROM reputation.portfolio_summary WHERE job_id = '" + declined + "'")).isEqualTo("DECLINED");
		assertThat(text("SELECT status || ':' || (text IS NULL) FROM reputation.portfolio_summary WHERE job_id = '" + silent + "'"))
			.isEqualTo("LAPSED:true");
		assertThat(count("""
				SELECT count(*) FROM notification.todo WHERE todo_key LIKE 'reputation.%_portfolio_summary' AND closed_at IS NULL
				""")).isZero();
		approve(this.lan, Role.CUSTOMER, silent, "Muộn", false).andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("RATING_WINDOW_CLOSED"));
	}

	private void people() {
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		this.hung = TeamTestSupport.mentor(this.jdbc, "Đỗ Mạnh Hùng",
				List.of(TeamTestSupport.REACT), "React");
		this.vy = TeamTestSupport.student(this.jdbc, "Trần Thảo Vy", true, List.of(TeamTestSupport.REACT), "React",
				LocalDate.parse("2026-09-30"));
	}

	private void closes(UUID job) {
		this.listeners.onJobTransitioned(new JobTransitioned(UUID.randomUUID(), UUID.randomUUID(), job, "J9", "SYSTEM",
				"CLOSING", "CLOSED", 7, 1, Map.of("ratingWindowDays", "14")));
	}

	/** A Closed Assessment Job with one Milestone, accepted or not, and the facts its records hold. */
	private UUID job(boolean accepted) {
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
				VALUES (:id, :product, :owner, 'ACTIVE', 'Đánh giá sổ đặt bàn', 'Lỗi', 'Đúng', 'Khách', ARRAY['ASSESSMENT'],
				    'UNDER_2M', DATE '2026-12-01', '{}'::jsonb, 14, :now, :now, :mentor, :application, :now, 5, :now, :now)
				""").param("id", job).param("product", product).param("owner", this.lan).param("mentor", this.hung)
			.param("application", UUID.randomUUID()).param("now", now).update();
		this.jdbc.sql("UPDATE job.job SET state = 'CLOSED', closing_at = :now, closed_at = :now WHERE id = :id")
			.param("id", job).param("now", now).update();
		String content = "{\"scope\":\"Đánh giá\",\"skills\":[{\"id\":\"" + TeamTestSupport.REACT
				+ "\",\"name\":\"React\"}],\"milestones\":[]}";
		this.jdbc.sql("""
				INSERT INTO job.agreement_version (job_id, version_no, status, content, author_account_id, created_at)
				VALUES (:job, 1, 'SHARED', CAST(:content AS JSONB), :mentor, :now)
				""").param("job", job).param("content", content).param("mentor", this.hung).param("now", now).update();
		this.jdbc.sql("""
				INSERT INTO job.confirmed_agreement (job_id, version_no, draft_version_no, content, crew_usernames,
				    freeze_committed, customer_account_id, approved_at, mentor_account_id, confirmed_at)
				VALUES (:job, 1, 1, CAST(:content AS JSONB), '{}', false, :owner, :now, :mentor, :now)
				""")
			.param("job", job)
			.param("content", content)
			.param("owner", this.lan)
			.param("mentor", this.hung)
			.param("now", now)
			.update();
		UUID milestone = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO job.milestone (id, job_id, position, title, price_vnd, due_on, revision_rounds, created_at, state,
				    funded_at, crew_terms_version_no, accepted_at)
				VALUES (:id, :job, 0, 'Báo cáo đánh giá', 800000, DATE '2026-11-20', 2, :now, :state, :now, 1, :accepted)
				""")
			.param("id", milestone)
			.param("job", job)
			.param("now", now)
			.param("state", accepted ? "ACCEPTED" : "CANCELLED")
			.param("accepted", accepted ? now : null)
			.update();
		Timestamp signed = Timestamp.from(this.clock.instant().minus(Duration.ofDays(5)));
		this.jdbc.sql("""
				INSERT INTO job.crew_row (id, job_id, member_account_id, status, signed_at, activated_at, window_end, released_at,
				    released_reason, created_at)
				VALUES (:id, :job, :student, 'RELEASED', :signed, :signed, :now, :now, 'JOB_ENDED', now())
				""").param("id", UUID.randomUUID()).param("job", job).param("student", this.vy).param("signed", signed)
			.param("now", now).update();
		this.jdbc.sql("""
				INSERT INTO work.board (job_id, product_id, code, next_number, created_at) VALUES (:job, :product, 'SDB', 3, :now)
				""").param("job", job).param("product", product).param("now", now).update();
		UUID done = item(job, 1, "DONE", now);
		UUID returned = item(job, 2, "IN_PROGRESS", now);
		history(done, job, this.vy, "SUBMITTED", now);
		history(done, job, this.hung, "APPROVED", now);
		history(returned, job, this.vy, "SUBMITTED", now);
		history(returned, job, this.hung, "RETURNED", now);
		this.jdbc.sql("""
				INSERT INTO delivery.handover (id, job_id, product_id, milestone_id, milestone_number, milestone_title, round,
				    mentor_account_id, status, job_version, transition_id, check_by, created_at)
				VALUES (:id, :job, :product, :milestone, 1, 'Báo cáo đánh giá', 1, :mentor, 'SUBMITTED', 5, :transition, :now, :now)
				""")
			.param("id", UUID.randomUUID())
			.param("job", job)
			.param("product", product)
			.param("milestone", milestone)
			.param("mentor", this.hung)
			.param("transition", UUID.randomUUID())
			.param("now", now)
			.update();
		return job;
	}

	private UUID item(UUID job, int number, String status, Timestamp now) {
		UUID item = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO work.work_item (id, job_id, number, type, title, description, status, assignee_account_id, created_at,
				    updated_at)
				VALUES (:id, :job, :number, 'TASK', 'Kiểm tra luồng', 'Kiểm tra.', :status, :assignee, :now, :now)
				""").param("id", item).param("job", job).param("number", number).param("status", status)
			.param("assignee", this.vy).param("now", now).update();
		return item;
	}

	private void history(UUID item, UUID job, UUID actor, String action, Timestamp at) {
		this.jdbc.sql("""
				INSERT INTO work.work_item_history (id, work_item_id, job_id, actor_account_id, action, changes, at)
				VALUES (:id, :item, :job, :actor, :action, '{}'::jsonb, :at)
				""").param("id", UUID.randomUUID()).param("item", item).param("job", job).param("actor", actor)
			.param("action", action).param("at", at).update();
	}

	private ResultActions read(UUID account, Role role, UUID job) throws Exception {
		return this.mvc.perform(get("/api/v1/boards/" + job + "/portfolio").with(as(account, role)));
	}

	private ResultActions draft(UUID account, Role role, UUID job, String text) throws Exception {
		return this.mvc.perform(post("/api/v1/boards/" + job + "/portfolio/summary").with(as(account, role)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"text\":\"" + text + "\"}"));
	}

	private ResultActions approve(UUID account, Role role, UUID job, String text, boolean show) throws Exception {
		return this.mvc.perform(post("/api/v1/boards/" + job + "/portfolio/summary/approve").with(as(account, role))
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"text\":\"" + text + "\",\"showProductName\":" + show + "}"));
	}

	private boolean openTodo(String key, UUID account, UUID job) {
		return this.jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM notification.todo WHERE todo_key = :key AND account_id = :account AND subject_id = :job
				    AND closed_at IS NULL)
				""").param("key", key).param("account", account).param("job", job).query(Boolean.class).single();
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
