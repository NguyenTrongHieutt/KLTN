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
import vn.vibefix.payment.Ledger;
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.team.internal.TeamTestSupport;
import vn.vibefix.timers.internal.DeadlinePoller;

/**
 * FR-40, FR-43, SM-8 "cancellation with and without Accepted work": a cancellation the other side
 * confirms cancels the Planned Milestones (M6), refunds the Held one in full (M7), and ends the Job: J10
 * without Accepted work, J8 then J9 to Cancelled with it; an unanswered proposal tells its proposer.
 */
class CancellationIT extends IntegrationTest {

	private static final long PRICE = 800000;

	/** The data-deletion duties J10 asks for: the Mentor and each Crew member ever active. */
	private static final long DUTIES = 2;

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private DeadlinePoller poller;

	@Autowired
	private Ledger ledger;

	private UUID lan;

	private UUID hung;

	private UUID vy;

	private UUID first;

	private UUID second;

	@Test
	void withoutAcceptedWorkTheConfirmationRefundsTheHeldMilestoneAndCancelsTheJob() throws Exception {
		UUID job = world(false, "BUG_FIX");
		propose(this.lan, Role.CUSTOMER, job, "JOB").andExpect(status().isOk())
			.andExpect(jsonPath("$.proposal.proposer").value("CUSTOMER"))
			.andExpect(jsonPath("$.proposal.mine").value(true))
			.andExpect(jsonPath("$.held").value(true))
			.andExpect(jsonPath("$.canPropose").value(false));
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type = 'job.cancellation_proposed' AND account_id = '"
				+ this.hung + "'")).isEqualTo(1);
		assertThat(openTodos(this.hung)).isEqualTo(1);
		propose(this.lan, Role.CUSTOMER, job, "JOB").andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("CANCELLATION_OPEN"));
		command(this.lan, Role.CUSTOMER, job, "confirm", version(job)).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.reason").value("CANCELLATION_PROPOSER"));
		command(this.hung, Role.MENTOR, job, "confirm", version(job) - 1).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("STALE"));
		this.mvc.perform(get("/api/v1/boards/" + job + "/cancellation").with(as(this.vy, Role.STUDENT)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.audience").value("CREW"));
		command(this.vy, Role.STUDENT, job, "confirm", version(job)).andExpect(status().isForbidden());

		command(this.hung, Role.MENTOR, job, "confirm", version(job)).andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("CANCELLED"))
			.andExpect(jsonPath("$.proposal.status").value("CONFIRMED"));
		assertThat(text("SELECT state || ':' || (cancelled_at IS NOT NULL) || ':' || (closing_at IS NULL) FROM job.job WHERE id = '"
				+ job + "'")).isEqualTo("CANCELLED:true:true");
		assertThat(this.jdbc.sql("SELECT state FROM job.milestone WHERE job_id = :job ORDER BY position").param("job", job)
			.query(String.class).list()).containsExactly("CANCELLED", "CANCELLED");
		await().atMost(Duration.ofSeconds(10)).until(() -> this.ledger.releasable(this.first) == 0);
		assertThat(text("SELECT reason || ':' || amount_vnd FROM payment.refund WHERE milestone_id = '" + this.first + "'"))
			.isEqualTo("CANCELLED:" + PRICE);
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type = 'job.job_cancelled'")).isEqualTo(3);
		assertThat(text("SELECT status || ':' || released_reason FROM job.crew_row WHERE job_id = '" + job + "'"))
			.isEqualTo("RELEASED:JOB_ENDED");
		assertThat(openTodos(this.hung)).isZero();
		assertThat(this.jdbc.sql("""
				SELECT action FROM audit.audit_entry WHERE job_id = :job AND action LIKE 'job.cancellation%' ORDER BY occurred_at
				""").param("job", job).query(String.class).list())
			.containsExactly("job.cancellation_proposed", "job.cancellation_confirmed");
		// J10's data-deletion duties are asked for asynchronously; let them land before the next test.
		await().atMost(Duration.ofSeconds(10)).until(() -> count(
				"SELECT count(*) FROM notification.notification WHERE type = 'repo.data_deletion_confirmation_pending' AND job_id = '"
						+ job + "'") >= DUTIES);
	}

	@Test
	void withAcceptedWorkTheAssessmentClosesThenEndsCancelled() throws Exception {
		UUID job = world(true, "ASSESSMENT");
		propose(this.hung, Role.MENTOR, job, "REMAINING").andExpect(status().isOk());
		command(this.lan, Role.CUSTOMER, job, "confirm", version(job)).andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("CANCELLED"));
		assertThat(text("SELECT state || ':' || (closing_at IS NOT NULL) || ':' || (cancelled_at IS NOT NULL) || ':' || "
				+ "(closed_at IS NULL) FROM job.job WHERE id = '" + job + "'")).isEqualTo("CANCELLED:true:true:true");
		assertThat(this.jdbc.sql("SELECT state FROM job.milestone WHERE job_id = :job ORDER BY position").param("job", job)
			.query(String.class).list()).containsExactly("ACCEPTED", "CANCELLED");
		await().atMost(Duration.ofSeconds(10)).until(() -> this.ledger.releasable(this.second) == 0);
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type = 'job.job_closing'")).isEqualTo(3);
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type = 'job.job_cancelled'")).isEqualTo(3);
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type = 'job.job_closed'")).isZero();
		await().atMost(Duration.ofSeconds(10))
			.until(() -> count("SELECT count(*) FROM reputation.rating_window WHERE job_id = '" + job + "'") == 1);
	}

	@Test
	void anUnansweredProposalTellsItsProposerAndMayBeWithdrawn() throws Exception {
		UUID job = world(false, "BUG_FIX");
		propose(this.lan, Role.CUSTOMER, job, "JOB").andExpect(status().isOk());
		this.clock.advance(Duration.ofDays(6));
		this.poller.runDue();
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type = 'job.cancellation_unanswered' AND account_id = '"
				+ this.lan + "'")).isEqualTo(1);
		this.mvc.perform(get("/api/v1/boards/" + job + "/cancellation").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.proposal.status").value("OPEN"))
			.andExpect(jsonPath("$.proposal.unanswered").value(true));
		this.mvc.perform(post("/api/v1/boards/" + job + "/cancellation/withdraw").with(as(this.hung, Role.MENTOR)).with(csrf()))
			.andExpect(status().isForbidden());
		this.mvc.perform(post("/api/v1/boards/" + job + "/cancellation/withdraw").with(as(this.lan, Role.CUSTOMER)).with(csrf()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.proposal.status").value("WITHDRAWN"))
			.andExpect(jsonPath("$.canPropose").value(true));
		assertThat(openTodos(this.hung)).isZero();
	}

	/** An Active Job: Milestone 1 Accepted or Funded with its FUND, Milestone 2 Funded or Planned. */
	private UUID world(boolean accepted, String type) {
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
				    selected_application_id, selected_at, agreement_version_no, version, created_at, updated_at)
				VALUES (:id, :product, :owner, 'ACTIVE', 'Sổ đặt bàn', 'Lỗi', 'Đúng', 'Khách', ARRAY[CAST(:type AS TEXT)],
				    'UNDER_2M', DATE '2026-12-01', '{"CANCELLATION_CONFIRMATION_DAYS": 5}'::jsonb, 14, :now, :now, :mentor,
				    :application, :now, 1, 8, :now, :now)
				""").param("id", job).param("product", product).param("owner", this.lan).param("mentor", this.hung)
			.param("type", type).param("application", UUID.randomUUID()).param("now", now).update();
		this.first = UUID.randomUUID();
		this.second = UUID.randomUUID();
		milestone(job, this.first, 0, accepted ? "ACCEPTED" : "FUNDED", now);
		milestone(job, this.second, 1, accepted ? "FUNDED" : "PLANNED", now);
		fund(job, product, accepted ? this.second : this.first, now);
		Timestamp signed = Timestamp.from(this.clock.instant().minus(Duration.ofDays(5)));
		this.jdbc.sql("""
				INSERT INTO job.crew_row (id, job_id, student_account_id, status, signed_at, activated_at, created_at)
				VALUES (:id, :job, :student, 'ACTIVE', :signed, :signed, now())
				""").param("id", UUID.randomUUID()).param("job", job).param("student", this.vy).param("signed", signed).update();
		return job;
	}

	private void milestone(UUID job, UUID id, int position, String state, Timestamp now) {
		boolean funded = !"PLANNED".equals(state);
		this.jdbc.sql("""
				INSERT INTO job.milestone (id, job_id, position, title, price_vnd, due_on, revision_rounds, created_at, state,
				    funded_at, crew_terms_version_no, accepted_at)
				VALUES (:id, :job, :position, :title, :price, DATE '2026-11-20', 2, :now, :state, :funded, :terms, :accepted)
				""")
			.param("id", id)
			.param("job", job)
			.param("position", position)
			.param("title", "Giai đoạn " + (position + 1))
			.param("price", PRICE)
			.param("now", now)
			.param("state", state)
			.param("funded", funded ? now : null)
			.param("terms", funded ? 1 : null)
			.param("accepted", "ACCEPTED".equals(state) ? now : null)
			.update();
	}

	/** The Customer's paid order and its FUND on the Milestone's account. */
	private void fund(UUID job, UUID product, UUID milestone, Timestamp now) {
		UUID order = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO payment.payment_order (id, job_id, product_id, milestone_id, customer_account_id, purpose, amount_vnd,
				    agreement_version_no, txn_ref, status, created_at, expires_at, finished_at)
				VALUES (:id, :job, :product, :milestone, :customer, 'FUNDING', :amount, 1, :ref, 'PAID', :now, :now, :now)
				""")
			.param("id", order)
			.param("job", job)
			.param("product", product)
			.param("milestone", milestone)
			.param("customer", this.lan)
			.param("amount", PRICE)
			.param("ref", "C" + Long.toString(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE), 36))
			.param("now", now)
			.update();
		this.jdbc.sql("""
				INSERT INTO payment.ledger_account (milestone_id, job_id, product_id, created_at) VALUES (:id, :job, :product, now())
				""").param("id", milestone).param("job", job).param("product", product).update();
		this.jdbc.sql("""
				INSERT INTO payment.ledger_entry (id, operation_key, entry_type, bucket, job_id, product_id, milestone_id,
				    payment_order_id, amount_vnd, vnp_transaction_no, created_at)
				VALUES (:id, :key, 'FUND', 'MILESTONE', :job, :product, :milestone, :order, :amount, :no, now())
				""")
			.param("id", UUID.randomUUID())
			.param("key", "FUND:" + UUID.randomUUID())
			.param("job", job)
			.param("product", product)
			.param("milestone", milestone)
			.param("order", order)
			.param("amount", PRICE)
			.param("no", String.valueOf(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE)))
			.update();
	}

	private ResultActions propose(UUID account, Role role, UUID job, String scope) throws Exception {
		return this.mvc.perform(post("/api/v1/boards/" + job + "/cancellation").with(as(account, role)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"scope\":\"" + scope + "\",\"reason\":\"Không còn cần nữa\",\"version\":" + version(job) + "}"));
	}

	private ResultActions command(UUID account, Role role, UUID job, String action, long version) throws Exception {
		return this.mvc.perform(post("/api/v1/boards/" + job + "/cancellation/" + action).with(as(account, role)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"version\":" + version + "}"));
	}

	private long version(UUID job) {
		return this.jdbc.sql("SELECT version FROM job.job WHERE id = :id").param("id", job).query(Long.class).single();
	}

	private long openTodos(UUID account) {
		return this.jdbc.sql("""
				SELECT count(*) FROM notification.todo WHERE todo_key = 'job.answer_cancellation' AND account_id = :account
				  AND closed_at IS NULL
				""").param("account", account).query(Long.class).single();
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
