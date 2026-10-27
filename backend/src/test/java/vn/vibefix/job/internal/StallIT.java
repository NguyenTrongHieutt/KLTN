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
import vn.vibefix.payment.Ledger;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.team.internal.TeamTestSupport;
import vn.vibefix.timers.internal.DeadlinePoller;

/**
 * FR-40, AD-7: 14 days in Agreement or 7 in Starting tell both sides, and from then on either side cancels
 * alone through J10 with a full refund of held money; a Job that moved on leaves the Deadline obsolete.
 */
class StallIT extends IntegrationTest {

	private static final long PRICE = 800000;

	/** The data-deletion duties J10 asks for: the Mentor and each Crew member ever active. */
	private static final long DUTIES = 1;

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private StallListeners listeners;

	@Autowired
	private DeadlinePoller poller;

	@Autowired
	private Ledger ledger;

	private UUID lan;

	private UUID hung;

	private UUID milestone;

	@Test
	void fourteenDaysInAgreementLetTheCustomerCancelAlone() throws Exception {
		UUID job = world("AGREEMENT", false);
		this.listeners.onJobTransitioned(event(job, "J4", "SELECTING", "AGREEMENT"));
		await().atMost(Duration.ofSeconds(10)).until(() -> count("SELECT count(*) FROM timers.deadline WHERE subject_id = '"
				+ job + "'") >= 1);
		alone(this.lan, Role.CUSTOMER, job).andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("DEADLINE_NOT_PASSED"));
		this.mvc.perform(get("/api/v1/boards/" + job + "/cancellation").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.canCancelAlone").value(false))
			.andExpect(jsonPath("$.canPropose").value(true));

		this.clock.advance(Duration.ofDays(15));
		this.poller.runDue();
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type = 'job.agreement_deadline_passed'"))
			.isEqualTo(2);
		this.mvc.perform(get("/api/v1/boards/" + job + "/cancellation").with(as(this.hung, Role.MENTOR)))
			.andExpect(jsonPath("$.canCancelAlone").value(true));
		alone(this.lan, Role.CUSTOMER, job).andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("CANCELLED"))
			.andExpect(jsonPath("$.canCancelAlone").value(false));
		assertThat(text("SELECT state FROM job.milestone WHERE id = '" + this.milestone + "'")).isEqualTo("CANCELLED");
		assertThat(text("SELECT status || ':' || alone FROM job.cancellation_proposal WHERE job_id = '" + job + "'"))
			.isEqualTo("CONFIRMED:true");
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type = 'job.job_cancelled'")).isEqualTo(2);
		// J10's data-deletion duties are asked for asynchronously; let them land before the next test.
		await().atMost(Duration.ofSeconds(10)).until(() -> count(
				"SELECT count(*) FROM notification.notification WHERE type = 'repo.data_deletion_confirmation_pending' AND job_id = '"
						+ job + "'") >= DUTIES);
	}

	@Test
	void aJobKeepsTheStallRuleItWasPublishedWith() throws Exception {
		// FR-61, author decision 2026-10-21: the stall is a rule value copied onto the Job when it is published.
		UUID job = world("AGREEMENT", false);
		this.jdbc.sql("UPDATE job.job SET rules = '{\"AGREEMENT_STALL_DAYS\":10}'::jsonb WHERE id = :id").param("id", job).update();
		this.listeners.onJobTransitioned(event(job, "J4", "SELECTING", "AGREEMENT"));
		await().atMost(Duration.ofSeconds(10)).until(() -> count("SELECT count(*) FROM timers.deadline WHERE subject_id = '"
				+ job + "'") >= 1);
		this.clock.advance(Duration.ofDays(9));
		this.poller.runDue();
		this.mvc.perform(get("/api/v1/boards/" + job + "/cancellation").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.canCancelAlone").value(false));
		this.clock.advance(Duration.ofDays(2));
		this.poller.runDue();
		this.mvc.perform(get("/api/v1/boards/" + job + "/cancellation").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.canCancelAlone").value(true));
	}

	@Test
	void sevenDaysInStartingLetTheMentorCancelAloneWithTheRefund() throws Exception {
		UUID job = world("STARTING", true);
		this.listeners.onJobTransitioned(event(job, "J6", "AGREEMENT", "STARTING"));
		await().atMost(Duration.ofSeconds(10)).until(() -> count("SELECT count(*) FROM timers.deadline WHERE subject_id = '"
				+ job + "'") >= 1);
		this.clock.advance(Duration.ofDays(8));
		this.poller.runDue();
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type = 'job.starting_deadline_passed'"))
			.isEqualTo(2);
		alone(this.hung, Role.MENTOR, job).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("CANCELLED"));
		await().atMost(Duration.ofSeconds(10)).until(() -> this.ledger.releasable(this.milestone) == 0);
		assertThat(text("SELECT reason FROM payment.refund WHERE milestone_id = '" + this.milestone + "'")).isEqualTo("CANCELLED");
		// J10's data-deletion duties are asked for asynchronously; let them land before the next test.
		await().atMost(Duration.ofSeconds(10)).until(() -> count(
				"SELECT count(*) FROM notification.notification WHERE type = 'repo.data_deletion_confirmation_pending' AND job_id = '"
						+ job + "'") >= DUTIES);
	}

	@Test
	void aJobThatMovedOnLeavesTheDeadlineObsolete() throws Exception {
		UUID job = world("STARTING", false);
		this.listeners.onJobTransitioned(event(job, "J6", "AGREEMENT", "STARTING"));
		await().atMost(Duration.ofSeconds(10)).until(() -> count("SELECT count(*) FROM timers.deadline WHERE subject_id = '"
				+ job + "'") >= 1);
		this.jdbc.sql("UPDATE job.job SET state = 'ACTIVE' WHERE id = :id").param("id", job).update();
		this.clock.advance(Duration.ofDays(8));
		this.poller.runDue();
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type = 'job.starting_deadline_passed'"))
			.isZero();
		assertThat(count("SELECT count(*) FROM job.job WHERE id = '" + job + "' AND stall_deadline_passed_at IS NULL"))
			.isEqualTo(1);
	}

	private JobTransitioned event(UUID job, String transition, String from, String to) {
		return new JobTransitioned(UUID.randomUUID(), UUID.randomUUID(), job, transition, "USER", from, to, 4, null, Map.of());
	}

	/** A Job in {@code state} with one Milestone, Funded with its FUND or Planned. */
	private UUID world(String state, boolean funded) {
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		this.hung = TeamTestSupport.mentor(this.jdbc, "Đỗ Mạnh Hùng",
				List.of(TeamTestSupport.REACT), "React");
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
				    'UNDER_2M', DATE '2026-12-01', '{}'::jsonb, 14, :now, :now, :mentor, :application, :now, 4, :now, :now)
				""").param("id", job).param("product", product).param("owner", this.lan).param("mentor", this.hung)
			.param("state", state).param("application", UUID.randomUUID()).param("now", now).update();
		this.milestone = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO job.milestone (id, job_id, position, title, price_vnd, due_on, revision_rounds, created_at, state,
				    funded_at, crew_terms_version_no)
				VALUES (:id, :job, 0, 'Sửa lỗi trùng giờ', :price, DATE '2026-11-20', 2, :now, :state, :funded, :terms)
				""")
			.param("id", this.milestone)
			.param("job", job)
			.param("price", PRICE)
			.param("now", now)
			.param("state", funded ? "FUNDED" : "PLANNED")
			.param("funded", funded ? now : null)
			.param("terms", funded ? 1 : null)
			.update();
		if (funded) {
			UUID order = UUID.randomUUID();
			this.jdbc.sql("""
					INSERT INTO payment.payment_order (id, job_id, product_id, milestone_id, customer_account_id, purpose,
					    amount_vnd, agreement_version_no, txn_ref, status, created_at, expires_at, finished_at)
					VALUES (:id, :job, :product, :milestone, :customer, 'FUNDING', :amount, 1, :ref, 'PAID', :now, :now, :now)
					""")
				.param("id", order)
				.param("job", job)
				.param("product", product)
				.param("milestone", this.milestone)
				.param("customer", this.lan)
				.param("amount", PRICE)
				.param("ref", "S" + Long.toString(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE), 36))
				.param("now", now)
				.update();
			this.jdbc.sql("""
					INSERT INTO payment.ledger_account (milestone_id, job_id, product_id, created_at)
					VALUES (:id, :job, :product, now())
					""").param("id", this.milestone).param("job", job).param("product", product).update();
			this.jdbc.sql("""
					INSERT INTO payment.ledger_entry (id, operation_key, entry_type, bucket, job_id, product_id, milestone_id,
					    payment_order_id, amount_vnd, vnp_transaction_no, created_at)
					VALUES (:id, :key, 'FUND', 'MILESTONE', :job, :product, :milestone, :order, :amount, :no, now())
					""")
				.param("id", UUID.randomUUID())
				.param("key", "FUND:" + UUID.randomUUID())
				.param("job", job)
				.param("product", product)
				.param("milestone", this.milestone)
				.param("order", order)
				.param("amount", PRICE)
				.param("no", String.valueOf(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE)))
				.update();
		}
		return job;
	}

	private ResultActions alone(UUID account, Role role, UUID job) throws Exception {
		long version = this.jdbc.sql("SELECT version FROM job.job WHERE id = :id").param("id", job).query(Long.class).single();
		return this.mvc.perform(post("/api/v1/boards/" + job + "/cancellation/alone").with(as(account, role)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"reason\":\"Chờ quá lâu\",\"version\":" + version + "}"));
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
