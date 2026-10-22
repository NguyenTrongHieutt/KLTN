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

import vn.vibefix.identity.MentorApprovalStatus;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.team.internal.TeamTestSupport;

/**
 * Story 14.1 (FR-36, FR-16, FR-22, AD-29): a Change Request changes the scope, Milestone titles and prices,
 * criteria, and Milestones; the resulting Agreement must pass FR-16, a Held Milestone's price waits for
 * Story 14.2, and the confirmation cancels removed Milestones, changes the live entities without re-keying,
 * writes the next version, and seeds Stories and ledger accounts for what it added.
 */
class FullChangeRequestIT extends IntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	private UUID lan;

	private UUID hung;

	private UUID job;

	private UUID product;

	/** Funded, 800.000 ₫, with criterion {@link #c1}. */
	private UUID first;

	/** Planned, 800.000 ₫, with criterion {@link #c2}. */
	private UUID second;

	private UUID c1;

	private UUID c2;

	@BeforeEach
	void anActiveJobWithAFundedAndAPlannedMilestone() {
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null, null);
		this.hung = TeamTestSupport.mentor(this.jdbc, MentorApprovalStatus.APPROVED, "Đỗ Mạnh Hùng",
				List.of(TeamTestSupport.REACT), "React");
		this.product = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO product.product (id, customer_account_id, name, description, ai_tools, repository_github_id,
				    repository_full_name, repository_owner_type, repository_default_branch, public_notice_version,
				    copy_permission_version, detection_notice_version, acknowledged_at, created_at)
				VALUES (:id, :owner, 'Sổ đặt bàn', 'Đặt bàn.', ARRAY['LOVABLE'], :github, 'lan/so-dat-ban', 'User', 'main',
				    '1.0', '1.0', '1.0', now(), now())
				""").param("id", this.product).param("owner", this.lan)
			.param("github", ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE)).update();
		this.job = UUID.randomUUID();
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, budget, deadline, rules, selection_days, opened_at, lapses_at, mentor_account_id,
				    selected_application_id, selected_at, version, created_at, updated_at, agreement_version_no)
				VALUES (:id, :product, :owner, 'ACTIVE', 'Sửa sổ đặt bàn', 'Lỗi', 'Đúng', 'Khách', ARRAY['ASSESSMENT'],
				    'UNDER_2M', DATE '2026-12-01', '{}'::jsonb, 14, :now, :now, :mentor, :application, :now, 6, :now, :now, 1)
				""").param("id", this.job).param("product", this.product).param("owner", this.lan).param("mentor", this.hung)
			.param("application", UUID.randomUUID()).param("now", now).update();
		this.first = UUID.randomUUID();
		this.second = UUID.randomUUID();
		this.c1 = UUID.randomUUID();
		this.c2 = UUID.randomUUID();
		String content = """
				{"scope":"Sửa lỗi đặt bàn","priceVnd":1600000,"codeJob":false,"assessment":true,"skills":[],"externalAccess":[],
				 "milestones":[
				   {"id":"%s","title":"Sửa lỗi trùng giờ","priceVnd":800000,"dueOn":"2026-11-15","revisionRounds":2,
				    "criteria":[{"id":"%s","statement":"Hai khách không trùng bàn","checkSteps":"1. Đặt. 2. Đặt lại."}]},
				   {"id":"%s","title":"Nhắc lịch","priceVnd":800000,"dueOn":"2026-11-30","revisionRounds":2,
				    "criteria":[{"id":"%s","statement":"Khách nhận lời nhắc","checkSteps":"1. Đặt. 2. Chờ."}]}]}
				""".formatted(this.first, this.c1, this.second, this.c2);
		this.jdbc.sql("""
				INSERT INTO job.agreement_version (job_id, version_no, status, content, author_account_id, created_at)
				VALUES (:job, 1, 'SHARED', CAST(:content AS JSONB), :mentor, :now)
				""").param("job", this.job).param("content", content).param("mentor", this.hung).param("now", now).update();
		this.jdbc.sql("""
				INSERT INTO job.confirmed_agreement (job_id, version_no, draft_version_no, content, crew_usernames,
				    freeze_committed, customer_account_id, approved_at, mentor_account_id, confirmed_at)
				VALUES (:job, 1, 1, CAST(:content AS JSONB), '{}', true, :owner, :now, :mentor, :now)
				""").param("job", this.job).param("content", content).param("owner", this.lan).param("mentor", this.hung)
			.param("now", now).update();
		milestone(this.first, 0, "Sửa lỗi trùng giờ", "2026-11-15", true, now);
		milestone(this.second, 1, "Nhắc lịch", "2026-11-30", false, now);
		criterion(this.c1, this.first, "Hai khách không trùng bàn", now);
		criterion(this.c2, this.second, "Khách nhận lời nhắc", now);
		this.jdbc.sql("INSERT INTO work.board (job_id, product_id, code, next_number, created_at) VALUES (:job, :product, 'SDB', 1, :now)")
			.param("job", this.job).param("product", this.product).param("now", now).update();
		for (UUID milestone : List.of(this.first, this.second)) {
			this.jdbc.sql("""
					INSERT INTO payment.ledger_account (milestone_id, job_id, product_id, created_at) VALUES (:id, :job, :product, now())
					""").param("id", milestone).param("job", this.job).param("product", this.product).update();
		}
		UUID order = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO payment.payment_order (id, job_id, product_id, milestone_id, customer_account_id, purpose,
				    amount_vnd, agreement_version_no, txn_ref, status, created_at, expires_at, finished_at)
				VALUES (:id, :job, :product, :milestone, :customer, 'FUNDING', 800000, 1, :ref, 'PAID', :now, :now, :now)
				""").param("id", order).param("job", this.job).param("product", this.product).param("milestone", this.first)
			.param("customer", this.lan)
			.param("ref", "L" + Long.toString(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE), 36)).param("now", now)
			.update();
		this.jdbc.sql("""
				INSERT INTO payment.ledger_entry (id, operation_key, entry_type, bucket, job_id, product_id, milestone_id,
				    payment_order_id, amount_vnd, vnp_transaction_no, created_at)
				VALUES (:id, :key, 'FUND', 'MILESTONE', :job, :product, :milestone, :order, 800000, :no, now())
				""").param("id", UUID.randomUUID()).param("key", "FUND:" + UUID.randomUUID()).param("job", this.job)
			.param("product", this.product).param("milestone", this.first).param("order", order)
			.param("no", String.valueOf(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE))).update();
	}

	@Test
	void aFullRequestTakesEffectAsTheNextVersionAndSeedsWhatItAdded() throws Exception {
		propose(this.lan, Role.CUSTOMER, """
				"scope":"Sửa lỗi đặt bàn và thêm báo cáo",
				"milestones":[{"milestoneId":"%s","title":"Nhắc lịch qua email","priceVnd":900000,"remove":false}],
				"addedCriteria":[{"milestoneId":"%s","statement":"Lời nhắc có giờ đặt","checkSteps":"1. Đặt. 2. Đọc email."}],
				"editedCriteria":[{"criterionId":"%s","statement":"Hai khách không bao giờ trùng bàn","checkSteps":"1. Đặt hai lần."}],
				"addedMilestones":[{"title":"Báo cáo tuần","priceVnd":300000,"dueOn":"2026-12-15","revisionRounds":1,
				  "criteria":[{"statement":"Có báo cáo số bàn","checkSteps":"1. Mở báo cáo."}]}]
				""".formatted(this.second, this.second, this.c1)).andExpect(status().isCreated())
			.andExpect(jsonPath("$.open.changes.scope").value("Sửa lỗi đặt bàn và thêm báo cáo"))
			.andExpect(jsonPath("$.open.changes.milestones[0].oldPriceVnd").value(800000))
			.andExpect(jsonPath("$.open.changes.milestones[0].priceVnd").value(900000))
			.andExpect(jsonPath("$.open.changes.addedMilestones[0].title").value("Báo cáo tuần"))
			.andExpect(jsonPath("$.open.changes.criteria.length()").value(2));
		String request = text("SELECT id::text FROM job.change_request WHERE job_id = '" + this.job + "'");
		command(this.lan, Role.CUSTOMER, request, "approve", "{\"version\":" + version() + "}")
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("IMPACT_MISSING"));
		command(this.hung, Role.MENTOR, request, "impact", "{\"impact\":\"Thêm 300.000 ₫ và 5 ngày\"}").andExpect(status().isOk());
		command(this.lan, Role.CUSTOMER, request, "approve", "{\"version\":" + version() + "}").andExpect(status().isOk());
		command(this.hung, Role.MENTOR, request, "confirm", "{\"version\":" + version() + "}").andExpect(status().isOk());

		assertThat(text("SELECT content ->> 'scope' FROM job.confirmed_agreement WHERE job_id = '" + this.job + "' AND version_no = 2"))
			.isEqualTo("Sửa lỗi đặt bàn và thêm báo cáo");
		assertThat(text("SELECT content ->> 'priceVnd' FROM job.confirmed_agreement WHERE job_id = '" + this.job
				+ "' AND version_no = 2")).isEqualTo("2000000");
		assertThat(count("SELECT jsonb_array_length(content -> 'milestones') FROM job.confirmed_agreement WHERE job_id = '"
				+ this.job + "' AND version_no = 2")).isEqualTo(3);
		assertThat(text("SELECT title FROM job.milestone WHERE id = '" + this.second + "'")).isEqualTo("Nhắc lịch qua email");
		assertThat(count("SELECT price_vnd FROM job.milestone WHERE id = '" + this.second + "'")).isEqualTo(900_000);
		assertThat(text("SELECT statement FROM job.acceptance_criterion WHERE id = '" + this.c1 + "'"))
			.isEqualTo("Hai khách không bao giờ trùng bàn");
		UUID added = this.jdbc.sql("SELECT id FROM job.milestone WHERE job_id = :job AND title = 'Báo cáo tuần' AND state = 'PLANNED'")
			.param("job", this.job).query(UUID.class).single();
		assertThat(count("SELECT count(*) FROM job.acceptance_criterion WHERE job_id = '" + this.job + "' AND removed_at IS NULL"))
			.isEqualTo(4);
		await().atMost(Duration.ofSeconds(15))
			.until(() -> count("SELECT count(*) FROM payment.ledger_account WHERE milestone_id = '" + added + "'") == 1);
		await().atMost(Duration.ofSeconds(15)).until(() -> count("""
				SELECT count(*) FROM work.work_item w JOIN job.acceptance_criterion c ON c.id = w.criterion_id
				WHERE w.job_id = '%s' AND c.statement IN ('Lời nhắc có giờ đặt', 'Có báo cáo số bàn')
				""".formatted(this.job)) == 2);
		this.mvc.perform(get("/api/v1/boards/" + this.job + "/agreement-versions").with(as(this.hung, Role.MENTOR)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.versions[0].scope").value("Sửa lỗi đặt bàn và thêm báo cáo"))
			.andExpect(jsonPath("$.versions[0].milestones[1].priceVnd").value(900000))
			.andExpect(jsonPath("$.versions[0].milestones[1].criteria.length()").value(2));
	}

	@Test
	void removingAMilestoneCancelsItAndARefusedChangeSaysWhy() throws Exception {
		propose(this.lan, Role.CUSTOMER, "\"milestones\":[{\"milestoneId\":\"" + this.first + "\",\"priceVnd\":500,\"remove\":false}]")
			.andExpect(status().isBadRequest());
		propose(this.lan, Role.CUSTOMER, "\"removedCriteria\":[\"" + this.c2 + "\"]")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reason").value("AGREEMENT_INCOMPLETE"))
			.andExpect(jsonPath("$.problems[0]").value("MILESTONE_WITHOUT_CRITERION"));

		propose(this.hung, Role.MENTOR, "\"milestones\":[{\"milestoneId\":\"" + this.first + "\",\"remove\":true}]")
			.andExpect(status().isCreated());
		String request = text("SELECT id::text FROM job.change_request WHERE job_id = '" + this.job + "' AND status = 'OPEN'");
		command(this.lan, Role.CUSTOMER, request, "approve", "{\"version\":" + version() + "}").andExpect(status().isOk());
		command(this.hung, Role.MENTOR, request, "confirm", "{\"version\":" + version() + "}").andExpect(status().isOk());

		assertThat(text("SELECT state FROM job.milestone WHERE id = '" + this.first + "'")).isEqualTo("CANCELLED");
		assertThat(count("SELECT count(*) FROM job.milestone WHERE id = '" + this.first + "' AND removed_at IS NOT NULL")).isOne();
		assertThat(count("SELECT jsonb_array_length(content -> 'milestones') FROM job.confirmed_agreement WHERE job_id = '"
				+ this.job + "' AND version_no = 2")).isOne();
		await().atMost(Duration.ofSeconds(15)).until(() -> count(
				"SELECT count(*) FROM payment.refund WHERE milestone_id = '" + this.first + "'") == 1);

		this.jdbc.sql("UPDATE job.milestone SET state = 'ACCEPTED', accepted_at = now(), funded_at = now(), crew_terms_version_no = 1 WHERE id = :id")
			.param("id", this.second).update();
		propose(this.lan, Role.CUSTOMER, "\"milestones\":[{\"milestoneId\":\"" + this.second + "\",\"title\":\"Đổi tên\",\"remove\":false}]")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reason").value("MILESTONE_FINAL"));
	}

	private ResultActions propose(UUID account, Role role, String changes) throws Exception {
		String impact = role == Role.MENTOR ? "\"impact\":\"Bớt 800.000 ₫ và giai đoạn 1\"," : "";
		return this.mvc.perform(post("/api/v1/boards/" + this.job + "/change-requests").with(as(account, role)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"description\":\"Cần đổi phạm vi\"," + impact + "\"items\":[],\"version\":" + version() + "," + changes + "}"));
	}

	private ResultActions command(UUID account, Role role, String request, String action, String body) throws Exception {
		return this.mvc.perform(post("/api/v1/change-requests/" + request + "/" + action).with(as(account, role)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	private void milestone(UUID id, int position, String title, String due, boolean funded, Timestamp now) {
		this.jdbc.sql("""
				INSERT INTO job.milestone (id, job_id, position, title, price_vnd, due_on, revision_rounds, created_at, state,
				    funded_at, crew_terms_version_no)
				VALUES (:id, :job, :position, :title, 800000, CAST(:due AS DATE), 2, :now, :state, :funded, :terms)
				""")
			.param("id", id)
			.param("job", this.job)
			.param("position", position)
			.param("title", title)
			.param("due", due)
			.param("now", now)
			.param("state", funded ? "FUNDED" : "PLANNED")
			.param("funded", funded ? now : null)
			.param("terms", funded ? 1 : null)
			.update();
	}

	private void criterion(UUID id, UUID milestone, String statement, Timestamp now) {
		this.jdbc.sql("""
				INSERT INTO job.acceptance_criterion (id, job_id, milestone_id, position, statement, check_steps, created_at)
				VALUES (:id, :job, :milestone, 0, :statement, '1. Làm. 2. Xem.', :now)
				""").param("id", id).param("job", this.job).param("milestone", milestone).param("statement", statement)
			.param("now", now).update();
	}

	private long version() {
		return this.jdbc.sql("SELECT version FROM job.job WHERE id = :id").param("id", this.job).query(Long.class).single();
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
