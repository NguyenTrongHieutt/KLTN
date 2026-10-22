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

import vn.vibefix.identity.MentorApprovalStatus;
import vn.vibefix.identity.internal.AdministratorTestSupport;
import vn.vibefix.identity.internal.AdministratorTestSupport.Administrator;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.payment.Ledger;
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.team.internal.TeamTestSupport;

/**
 * FR-54, AD-31, SM-8 "Dispute settled with a split": the assignee decides after the step-up, and the
 * execution settles the Held Milestone with exactly the decided release and refund, applies the next
 * state, and only then unfreezes; a failing step keeps the Job frozen and tells the Administrators.
 */
class DisputeDecisionIT extends IntegrationTest {

	private static final long PRICE = 800000;

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private ApplicationContext context;

	@Autowired
	private Ledger ledger;

	private UUID lan;

	private UUID hung;

	private UUID vy;

	private UUID held;

	private UUID planned;

	@Test
	void theAssigneesSplitIsExecutedExactlyAndTheJobUnfreezes() throws Exception {
		Administrator ha = AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		UUID job = world(true, true);
		String dispute = open(job);
		this.mvc.perform(get("/api/v1/admin/disputes").with(admin(ha))).andExpect(status().isOk())
			.andExpect(jsonPath("$[0].id").value(dispute))
			.andExpect(jsonPath("$[0].assigned").value(false));
		this.mvc.perform(get("/api/v1/admin/disputes/" + dispute).with(admin(ha))).andExpect(status().isOk())
			.andExpect(jsonPath("$.held.releasableVnd").value(PRICE))
			.andExpect(jsonPath("$.held.number").value(1))
			.andExpect(jsonPath("$.plannedRemains").value(true));
		assertThat(this.jdbc.sql("SELECT count(*) FROM dispute.dispute WHERE status = 'OPEN'").query(Long.class).single())
			.isEqualTo(1);

		decide(ha, dispute, 500000L, 300000L, "CANCEL").andExpect(status().isForbidden())
			.andExpect(jsonPath("$.reason").value("NOT_ASSIGNED"));
		this.mvc.perform(post("/api/v1/admin/disputes/" + dispute + "/take").with(admin(ha)).with(csrf()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.assignedToMe").value(true));
		decide(ha, dispute, 500000L, 200000L, "CANCEL").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reason").value("AMOUNTS_MISMATCH"));

		decide(ha, dispute, 500000L, 300000L, "CANCEL").andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("DECIDED"))
			.andExpect(jsonPath("$.execution.status").value("DONE"))
			.andExpect(jsonPath("$.execution.step").value(4))
			.andExpect(jsonPath("$.decision.releaseVnd").value(500000))
			.andExpect(jsonPath("$.decision.refundVnd").value(300000));
		assertThat(text("SELECT state || ':' || settled_release_vnd || ':' || settled_refund_vnd FROM job.milestone WHERE id = '"
				+ this.held + "'")).isEqualTo("SETTLED:500000:300000");
		assertThat(this.ledger.releasable(this.held)).isZero();
		assertThat(this.jdbc.sql("""
				SELECT coalesce(sum(amount_vnd), 0) FROM payment.ledger_entry WHERE milestone_id = :id AND entry_type = 'RELEASE'
				""").param("id", this.held).query(Long.class).single()).isEqualTo(500000);
		assertThat(text("SELECT reason || ':' || amount_vnd FROM payment.refund WHERE milestone_id = '" + this.held + "'"))
			.isEqualTo("DISPUTE_DECISION:300000");
		assertThat(text("SELECT state FROM job.milestone WHERE id = '" + this.planned + "'")).isEqualTo("CANCELLED");
		assertThat(text("SELECT state || ':' || (frozen_at IS NULL) FROM job.job WHERE id = '" + job + "'"))
			.isEqualTo("CANCELLED:true");
		assertThat(count("SELECT count(*) FROM timers.frozen_job WHERE job_id = '" + job + "'")).isZero();
		// The inventory's audience: the Customer, the Mentor, and every Crew member who was ever active.
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type = 'dispute.dispute_decided'")).isEqualTo(3);
		assertThat(count("SELECT count(*) FROM audit.audit_entry WHERE action = 'dispute.decision_recorded'")).isEqualTo(1);
		this.mvc.perform(get("/api/v1/boards/" + job + "/dispute").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.decided.nextState").value("CANCEL"))
			.andExpect(jsonPath("$.decided.releaseVnd").value(500000))
			.andExpect(jsonPath("$.open").doesNotExist());
		this.mvc.perform(get("/api/v1/admin/overview").with(admin(ha))).andExpect(jsonPath("$.disputes.count").value(0));
	}

	@Test
	void withoutAHeldMilestoneTheDecisionOnlyMovesTheJobOn() throws Exception {
		Administrator ha = AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		UUID job = world(false, false);
		String dispute = open(job);
		this.mvc.perform(post("/api/v1/admin/disputes/" + dispute + "/take").with(admin(ha)).with(csrf()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.held").doesNotExist());
		decide(ha, dispute, null, null, "CONTINUE").andExpect(status().isOk())
			.andExpect(jsonPath("$.execution.status").value("DONE"));
		assertThat(text("SELECT state || ':' || (frozen_at IS NULL) FROM job.job WHERE id = '" + job + "'"))
			.isEqualTo("ACTIVE:true");
		assertThat(text("SELECT state FROM job.milestone WHERE id = '" + this.planned + "'")).isEqualTo("PLANNED");
	}

	@Test
	void aFailingStepKeepsTheJobFrozenAndTellsTheAdministrators() throws Exception {
		Administrator ha = AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		UUID job = world(true, false);
		String dispute = open(job);
		this.mvc.perform(post("/api/v1/admin/disputes/" + dispute + "/take").with(admin(ha)).with(csrf())).andExpect(status().isOk());
		decide(ha, dispute, 500000L, 300000L, "END").andExpect(status().isOk())
			.andExpect(jsonPath("$.execution.status").value("EXECUTION_FAILED"))
			.andExpect(jsonPath("$.execution.step").value(1))
			.andExpect(jsonPath("$.status").value("OPEN"));
		assertThat(text("SELECT state || ':' || (frozen_at IS NOT NULL) FROM job.job WHERE id = '" + job + "'"))
			.isEqualTo("ACTIVE:true");
		assertThat(text("SELECT state FROM job.milestone WHERE id = '" + this.held + "'")).isEqualTo("FUNDED");
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type = 'dispute.execution_failed' AND account_id = '"
				+ ha.accountId() + "'")).isEqualTo(1);
		this.mvc.perform(post("/api/v1/admin/disputes/" + dispute + "/retry").with(admin(ha)).with(csrf()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.execution.status").value("EXECUTION_FAILED"));
	}

	private String open(UUID job) throws Exception {
		String body = this.mvc.perform(post("/api/v1/boards/" + job + "/disputes").with(as(this.lan, Role.CUSTOMER)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"reason\":\"Giai đoạn chưa đạt\"}")).andExpect(status().isCreated()).andReturn().getResponse()
			.getContentAsString();
		return com.jayway.jsonpath.JsonPath.read(body, "$.open.id");
	}

	private ResultActions decide(Administrator ha, String dispute, Long release, Long refund, String next) throws Exception {
		stated(dispute);
		String amounts = release == null ? "" : "\"releaseVnd\":" + release + ",\"refundVnd\":" + refund + ",";
		return this.mvc.perform(post("/api/v1/admin/disputes/" + dispute + "/decision").with(admin(ha)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{" + amounts + "\"nextState\":\"" + next + "\",\"reasoning\":\"Hai bên cùng có lỗi\",\"stepUpCode\":\""
					+ AdministratorTestSupport.code(ha, this.clock.instant()) + "\"}"));
	}

	/**
	 * An Active Assessment Job: Milestone 1 Funded (or Accepted) with its FUND, paid through an order unless
	 * {@code ordered} is false, and Milestone 2 Planned.
	 */
	private UUID world(boolean funded, boolean ordered) {
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
				VALUES (:id, :owner, 'Sổ đặt bàn', 'Đặt bàn.', ARRAY['LOVABLE'], :github, 'lan/so-dat-ban', 'User', 'main',
				    '1.0', '1.0', '1.0', now(), now())
				""").param("id", product).param("owner", this.lan)
			.param("github", ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE)).update();
		UUID job = UUID.randomUUID();
		this.held = UUID.randomUUID();
		this.planned = UUID.randomUUID();
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, budget, deadline, rules, selection_days, opened_at, lapses_at, mentor_account_id,
				    selected_application_id, selected_at, version, created_at, updated_at)
				VALUES (:id, :product, :owner, 'ACTIVE', 'Đánh giá sổ đặt bàn', 'Lỗi', 'Đúng', 'Khách', ARRAY['ASSESSMENT'],
				    'UNDER_2M', DATE '2026-12-01', '{}'::jsonb, 14, :now, :now, :mentor, :application, :now, 5, :now, :now)
				""").param("id", job).param("product", product).param("owner", this.lan).param("mentor", this.hung)
			.param("application", UUID.randomUUID()).param("now", now).update();
		String content = """
				{"scope":"Đánh giá","priceVnd":1300000,"codeJob":false,"assessment":true,"skills":[],"externalAccess":[],
				 "milestones":[{"id":"%s","title":"Báo cáo","priceVnd":%d,"dueOn":"2026-11-20","revisionRounds":2,"criteria":[]},
				  {"id":"%s","title":"Kế hoạch sửa","priceVnd":500000,"dueOn":"2026-12-01","revisionRounds":1,"criteria":[]}]}
				""".formatted(this.held, PRICE, this.planned);
		this.jdbc.sql("""
				INSERT INTO job.agreement_version (job_id, version_no, status, content, author_account_id, created_at)
				VALUES (:job, 1, 'SHARED', CAST(:content AS JSONB), :mentor, :now)
				""").param("job", job).param("content", content).param("mentor", this.hung).param("now", now).update();
		this.jdbc.sql("""
				INSERT INTO job.confirmed_agreement (job_id, version_no, draft_version_no, content, crew_usernames,
				    freeze_committed, customer_account_id, approved_at, mentor_account_id, confirmed_at)
				VALUES (:job, 1, 1, CAST(:content AS JSONB), '{}', false, :owner, :now, :mentor, :now)
				""").param("job", job).param("content", content).param("owner", this.lan).param("mentor", this.hung)
			.param("now", now).update();
		this.jdbc.sql("UPDATE job.job SET agreement_version_no = 1 WHERE id = :id").param("id", job).update();
		UUID row = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO job.crew_row (id, job_id, student_account_id, status, signed_at, activated_at, created_at)
				VALUES (:id, :job, :student, 'ACTIVE', :signed, :signed, now())
				""").param("id", row).param("job", job).param("student", this.vy)
			.param("signed", Timestamp.from(this.clock.instant().minus(Duration.ofMinutes(5)))).update();
		this.jdbc.sql("""
				INSERT INTO job.crew_terms_version (job_id, version_no, status, cap_percent, author_account_id, created_at)
				VALUES (:job, 1, 'IN_FORCE', 100, :mentor, now())
				""").param("job", job).param("mentor", this.hung).update();
		this.jdbc.sql("""
				INSERT INTO job.crew_terms_share (job_id, version_no, party_account_id, crew_row_id, percent)
				VALUES (:job, 1, :mentor, NULL, 70), (:job, 1, :vy, :row, 30)
				""").param("job", job).param("mentor", this.hung).param("vy", this.vy).param("row", row).update();
		this.jdbc.sql("""
				INSERT INTO job.milestone (id, job_id, position, title, price_vnd, revision_rounds, created_at, state, funded_at,
				    crew_terms_version_no, accepted_at)
				VALUES (:id, :job, 0, 'Báo cáo', :price, 2, now(), :state, now(), 1, :accepted)
				""").param("id", this.held).param("job", job).param("price", PRICE)
			.param("state", funded ? "FUNDED" : "ACCEPTED").param("accepted", funded ? null : now).update();
		this.jdbc.sql("""
				INSERT INTO job.milestone (id, job_id, position, title, price_vnd, revision_rounds, created_at)
				VALUES (:id, :job, 1, 'Kế hoạch sửa', 500000, 1, now())
				""").param("id", this.planned).param("job", job).update();
		for (UUID account : List.of(this.held, this.planned)) {
			this.jdbc.sql("""
					INSERT INTO payment.ledger_account (milestone_id, job_id, product_id, created_at) VALUES (:id, :job, :product, now())
					""").param("id", account).param("job", job).param("product", product).update();
		}
		if (funded) {
			UUID order = ordered ? UUID.randomUUID() : null;
			if (ordered) {
				this.jdbc.sql("""
						INSERT INTO payment.payment_order (id, job_id, product_id, milestone_id, customer_account_id, purpose,
						    amount_vnd, agreement_version_no, txn_ref, status, created_at, expires_at, finished_at)
						VALUES (:id, :job, :product, :milestone, :customer, 'FUNDING', :amount, 1, :ref, 'PAID', :now, :now, :now)
						""")
					.param("id", order)
					.param("job", job)
					.param("product", product)
					.param("milestone", this.held)
					.param("customer", this.lan)
					.param("amount", PRICE)
					.param("ref", "D" + Long.toString(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE), 36))
					.param("now", now)
					.update();
			}
			this.jdbc.sql("""
					INSERT INTO payment.ledger_entry (id, operation_key, entry_type, bucket, job_id, product_id, milestone_id,
					    payment_order_id, amount_vnd, vnp_transaction_no, created_at)
					VALUES (:id, :key, 'FUND', 'MILESTONE', :job, :product, :milestone, :order, :amount, :no, now())
					""")
				.param("id", UUID.randomUUID())
				.param("key", "FUND:" + UUID.randomUUID())
				.param("job", job)
				.param("product", product)
				.param("milestone", this.held)
				.param("order", order)
				.param("amount", PRICE)
				.param("no", String.valueOf(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE)))
				.update();
		}
		return job;
	}

	private long count(String sql) {
		return this.jdbc.sql(sql).query(Long.class).single();
	}

	private String text(String sql) {
		return this.jdbc.sql(sql).query(String.class).single();
	}

	/** Story 15.1: the other side's statement arrived, so the decision need not wait for its period. */
	private void stated(String dispute) {
		this.jdbc.sql("""
				INSERT INTO dispute.statement (dispute_id, side, text, submitted_at) VALUES (CAST(:id AS UUID), 'CUSTOMER', 'Trình bày', now())
				ON CONFLICT DO NOTHING
				""").param("id", dispute).update();
	}

	private RequestPostProcessor admin(Administrator administrator) {
		return IdentityTestSupport.signedIn(this.sessions, administrator.accountId(), Role.ADMINISTRATOR, this.clock.instant());
	}

	private RequestPostProcessor as(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

}
