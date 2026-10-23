package vn.vibefix.job.internal;

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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import vn.vibefix.identity.MentorApprovalStatus;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.team.internal.TeamTestSupport;
import vn.vibefix.timers.internal.DeadlinePoller;

/**
 * FR-37, FR-67, AD-4, AD-7, AD-41, UX-DR55: the last acceptance proposes closure to both sides with an
 * auto-confirm Deadline; both confirming, or the Deadline, closes an Assessment Job in one cascade (J8,
 * M6, J9) that releases every Crew row as JOB_ENDED; a person's proposal waits for the other side, who
 * may decline; a Held Milestone blocks a proposal; a Crew member may not answer.
 */
class ClosureIT extends IntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private DeadlinePoller poller;

	private UUID lan;

	private UUID hung;

	private UUID vy;

	private UUID job;

	private UUID first;

	private UUID second;

	private UUID criterion;

	@Test
	void theLastAcceptanceProposesClosureAndBothConfirmationsCloseTheAssessmentJob() throws Exception {
		world("FUNDED", false);
		accept(handOver());
		assertThat(count("SELECT count(*) FROM job.closure_proposal WHERE kind = 'AUTOMATIC' AND status = 'OPEN'")).isOne();
		assertThat(notifications("job.closure_proposed")).isEqualTo(2);
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM notification.todo WHERE todo_key = 'job.answer_closure' AND tier = 1 AND closed_at IS NULL
				""").query(Long.class).single()).isEqualTo(2);
		assertThat(count("SELECT count(*) FROM timers.deadline WHERE kind = 'job.closure_auto_confirm' AND status = 'PENDING'"))
			.isOne();
		this.mvc.perform(get("/api/v1/boards/" + this.job + "/closure").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.proposal.kind").value("AUTOMATIC"))
			.andExpect(jsonPath("$.proposal.proposer").value("SYSTEM"))
			.andExpect(jsonPath("$.proposal.answerable").value(true))
			.andExpect(jsonPath("$.canPropose").value(false))
			.andExpect(jsonPath("$.steps.length()").value(2))
			.andExpect(jsonPath("$.steps[?(@.party == 'CUSTOMER')].done").value(false));
		this.mvc.perform(get("/api/v1/boards/" + this.job + "/closure").with(as(this.vy, Role.STUDENT)))
			.andExpect(jsonPath("$.audience").value("CREW"))
			.andExpect(jsonPath("$.proposal.answerable").value(false));

		command(this.vy, Role.STUDENT, "confirm", 7).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.reason").value("MENTOR_ONLY"));
		command(this.lan, Role.CUSTOMER, "confirm", 6).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("STALE"));
		command(this.lan, Role.CUSTOMER, "confirm", 7).andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("ACTIVE"))
			.andExpect(jsonPath("$.proposal.customerConfirmed").value(true));
		command(this.lan, Role.CUSTOMER, "confirm", 7).andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("CLOSURE_ANSWERED"));
		command(this.hung, Role.MENTOR, "confirm", 7).andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("CLOSED"))
			.andExpect(jsonPath("$.proposal.status").value("CONFIRMED"))
			.andExpect(jsonPath("$.steps[?(@.party == 'MENTOR')].done").value(true));

		assertThat(text("SELECT state || ':' || (closing_at IS NOT NULL) || ':' || (closed_at IS NOT NULL) FROM job.job WHERE id = '"
				+ this.job + "'")).isEqualTo("CLOSED:true:true");
		assertThat(count("SELECT version FROM job.job WHERE id = '" + this.job + "'")).isEqualTo(8);
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM job.crew_row WHERE job_id = :job AND status <> 'RELEASED'
				""").param("job", this.job).query(Long.class).single()).isZero();
		assertThat(text("SELECT released_reason || ':' || (window_end IS NOT NULL) FROM job.crew_row WHERE job_id = '" + this.job
				+ "'")).isEqualTo("JOB_ENDED:true");
		// AD-41: the released member keeps JOB-1 for the post-Job duties, read-only, with nothing from after their time.
		this.mvc.perform(get("/api/v1/boards/" + this.job + "/closure").with(as(this.vy, Role.STUDENT)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.audience").value("CREW"))
			.andExpect(jsonPath("$.state").value("CLOSED"));
		command(this.vy, Role.STUDENT, "decline", 8).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.reason").value("MENTOR_ONLY"));
		this.mvc.perform(get("/api/v1/boards/" + this.job + "/progress").with(as(this.vy, Role.STUDENT)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.audience").value("FORMER"))
			.andExpect(jsonPath("$.state").value("CLOSED"))
			.andExpect(jsonPath("$.milestones.length()").value(0))
			.andExpect(jsonPath("$.pages.length()").value(0))
			.andExpect(jsonPath("$.mine.length()").value(0));
		assertThat(notifications("job.job_closing")).isEqualTo(3);
		assertThat(notifications("job.job_closed")).isEqualTo(3);
		assertThat(count("SELECT count(*) FROM notification.todo WHERE todo_key = 'job.answer_closure' AND closed_at IS NULL"))
			.isZero();
		await().atMost(Duration.ofSeconds(10)).until(() -> count("""
				SELECT count(*) FROM reputation.rating_window WHERE job_id = '%s' AND ends_at = opened_at + interval '14 days'
				""".formatted(this.job)) == 1);
	}

	@Test
	void nobodyAnsweringForSevenDaysClosesTheJob() throws Exception {
		world("FUNDED", false);
		accept(handOver());
		this.clock.advance(Duration.ofDays(7));
		this.poller.runDue();
		assertThat(text("SELECT state FROM job.job WHERE id = '" + this.job + "'")).isEqualTo("CLOSED");
		assertThat(text("SELECT status FROM job.closure_proposal")).isEqualTo("CONFIRMED");
		assertThat(count("SELECT count(*) FROM audit.audit_entry WHERE action = 'job.transition_j8'")).isOne();
	}

	@Test
	void aPersonsProposalWaitsForTheOtherSideWhoMayDeclineThenCloseWithTheRestCancelled() throws Exception {
		world("ACCEPTED", true);
		command(this.vy, Role.STUDENT, "", 5).andExpect(status().isForbidden());
		command(this.lan, Role.CUSTOMER, "", 5).andExpect(status().isCreated())
			.andExpect(jsonPath("$.proposal.kind").value("PROPOSED"))
			.andExpect(jsonPath("$.proposal.proposer").value("CUSTOMER"))
			.andExpect(jsonPath("$.proposal.answerable").value(false));
		assertThat(this.jdbc.sql("""
				SELECT tier FROM notification.todo WHERE todo_key = 'job.answer_closure' AND account_id = :hung AND closed_at IS NULL
				""").param("hung", this.hung).query(Integer.class).single()).isEqualTo(2);
		command(this.lan, Role.CUSTOMER, "", 5).andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("CLOSURE_OPEN"));
		command(this.hung, Role.MENTOR, "decline", 5).andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("ACTIVE"))
			.andExpect(jsonPath("$.canPropose").value(true));
		assertThat(this.jdbc.sql("SELECT count(*) FROM notification.notification WHERE type = 'job.closure_declined' AND account_id = :lan")
			.param("lan", this.lan).query(Long.class).single()).isOne();

		command(this.hung, Role.MENTOR, "", 5).andExpect(status().isCreated());
		command(this.lan, Role.CUSTOMER, "confirm", 5).andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("CLOSED"));
		assertThat(text("SELECT state || ':' || (cancelled_at IS NOT NULL) FROM job.milestone WHERE id = '" + this.second + "'"))
			.isEqualTo("CANCELLED:true");
		assertThat(count("SELECT count(*) FROM payment.ledger_entry WHERE job_id = '" + this.job + "'")).isZero();
	}

	@Test
	void aHeldMilestoneBlocksAProposal() throws Exception {
		world("FUNDED", false);
		command(this.lan, Role.CUSTOMER, "", 5).andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("MILESTONE_HELD"));
		UUID stranger = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null, null);
		this.mvc.perform(get("/api/v1/boards/" + this.job + "/closure").with(as(stranger, Role.CUSTOMER)))
			.andExpect(status().isNotFound());
	}

	/**
	 * An Active Assessment Job whose Milestone 1 is {@code firstState} at 1,500,000 under Crew Terms version
	 * 1 (the Mentor 70, Vy 30), its seeded Story approved and the Assessment Report written; with
	 * {@code planned}, a Planned Milestone 2.
	 */
	private void world(String firstState, boolean planned) throws Exception {
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null, null);
		this.hung = TeamTestSupport.mentor(this.jdbc, MentorApprovalStatus.APPROVED, "Đỗ Mạnh Hùng",
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
		this.first = UUID.randomUUID();
		this.second = UUID.randomUUID();
		this.criterion = UUID.randomUUID();
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, budget, deadline, rules, selection_days, opened_at, lapses_at, mentor_account_id,
				    selected_application_id, selected_at, version, created_at, updated_at)
				VALUES (:id, :product, :owner, 'ACTIVE', 'Đánh giá sổ đặt bàn', 'Lỗi', 'Đúng', 'Khách', ARRAY['ASSESSMENT'],
				    'UNDER_2M', DATE '2026-12-01', '{"AUTOMATIC_CLOSURE_DAYS":7,"CLOSURE_CONFIRMATION_DAYS":5}'::jsonb,
				    14, :now, :now, :mentor, :application, :now, 5, :now, :now)
				""").param("id", this.job).param("product", product).param("owner", this.lan).param("mentor", this.hung)
			.param("application", UUID.randomUUID()).param("now", now).update();
		String extra = planned ? ",{\"id\":\"%s\",\"title\":\"Kế hoạch\",\"priceVnd\":500000,\"revisionRounds\":1,\"criteria\":[]}"
			.formatted(this.second) : "";
		String content = """
				{"scope":"Đánh giá","priceVnd":1500000,"codeJob":false,"assessment":true,"skills":[],"externalAccess":[],
				 "milestones":[{"id":"%s","title":"Báo cáo","priceVnd":1500000,"dueOn":"2026-11-20","revisionRounds":2,
				   "criteria":[{"id":"%s","statement":"Báo cáo nêu đủ chín mục","checkSteps":"Mở báo cáo, đếm mục"}]}%s]}
				""".formatted(this.first, this.criterion, extra);
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
				INSERT INTO job.crew_row (id, job_id, student_account_id, status, signed_at, activated_at, created_at)
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
		boolean accepted = "ACCEPTED".equals(firstState);
		this.jdbc.sql("""
				INSERT INTO job.milestone (id, job_id, position, title, price_vnd, revision_rounds, created_at, state, funded_at,
				    crew_terms_version_no, accepted_at)
				VALUES (:id, :job, 0, 'Báo cáo', 1500000, 2, now(), :state, now(), 1, :accepted)
				""").param("id", this.first).param("job", this.job).param("state", firstState)
			.param("accepted", accepted ? now : null).update();
		if (planned) {
			this.jdbc.sql("""
					INSERT INTO job.milestone (id, job_id, position, title, price_vnd, revision_rounds, created_at)
					VALUES (:id, :job, 1, 'Kế hoạch', 500000, 1, now())
					""").param("id", this.second).param("job", this.job).update();
		}
		if (accepted) {
			return;
		}
		this.jdbc.sql("""
				INSERT INTO payment.ledger_account (milestone_id, job_id, product_id, created_at) VALUES (:id, :job, :product, now())
				""").param("id", this.first).param("job", this.job).param("product", product).update();
		this.jdbc.sql("""
				INSERT INTO payment.ledger_entry (id, operation_key, entry_type, bucket, job_id, product_id, milestone_id, amount_vnd,
				    vnp_transaction_no, created_at)
				VALUES (:id, :key, 'FUND', 'MILESTONE', :job, :product, :milestone, 1500000, :no, now())
				""").param("id", UUID.randomUUID()).param("key", "FUND:" + UUID.randomUUID()).param("job", this.job)
			.param("product", product).param("milestone", this.first)
			.param("no", String.valueOf(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE))).update();
		this.jdbc.sql("""
				INSERT INTO work.board (job_id, product_id, code, next_number, created_at) VALUES (:job, :product, 'BCD', 2, :now)
				""").param("job", this.job).param("product", product).param("now", now).update();
		UUID story = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO work.work_item (id, job_id, number, type, title, description, criterion_id, milestone_id, status,
				    created_at, updated_at)
				VALUES (:id, :job, 1, 'STORY', 'Báo cáo nêu đủ chín mục', '', :criterion, :milestone, 'DONE', now(), now())
				""").param("id", story).param("job", this.job).param("criterion", this.criterion).param("milestone", this.first)
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
		String json = "{\"milestoneId\":\"" + this.first + "\",\"version\":5,\"criteria\":[{\"criterionId\":\"" + this.criterion
				+ "\",\"met\":true,\"note\":\"Đã rà đủ chín mục.\"}]}";
		return JsonPath.read(this.mvc.perform(post("/api/v1/boards/" + this.job + "/handovers").with(as(this.hung, Role.MENTOR))
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(json)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
	}

	private void accept(String handover) throws Exception {
		this.mvc.perform(post("/api/v1/handovers/" + handover + "/accept").with(as(this.lan, Role.CUSTOMER)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content("{\"version\":6}"))
			.andExpect(status().isOk());
	}

	private ResultActions command(UUID account, Role role, String action, long version) throws Exception {
		String path = "/api/v1/boards/" + this.job + "/closure" + (action.isEmpty() ? "" : "/" + action);
		return this.mvc.perform(post(path).with(as(account, role)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"version\":" + version + "}"));
	}

	private long count(String sql) {
		return this.jdbc.sql(sql).query(Long.class).single();
	}

	private String text(String sql) {
		return this.jdbc.sql(sql).query(String.class).single();
	}

	private long notifications(String type) {
		return this.jdbc.sql("SELECT count(*) FROM notification.notification WHERE type = :type").param("type", type)
			.query(Long.class).single();
	}

	private RequestPostProcessor as(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

}
