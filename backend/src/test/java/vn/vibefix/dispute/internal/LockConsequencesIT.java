package vn.vibefix.dispute.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.identity.AccountLocked;
import vn.vibefix.identity.MentorApprovalStatus;
import vn.vibefix.identity.internal.AdministratorTestSupport;
import vn.vibefix.identity.internal.AdministratorTestSupport.Administrator;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.team.internal.TeamTestSupport;

/**
 * FR-59, AD-19, AD-41, SM-8 "account lock mid-Milestone": locking the only Crew member, the Mentor, or
 * the Customer of an Active Job with a Held Milestone tells whom UX-DR83 allows, opens one Dispute by the
 * System that freezes the Job, and changes nothing more on a re-delivery; the decision's release still
 * pays the locked Crew member by the Milestone's pin, and an unlock leaves the row {@code INACTIVE}.
 */
@RecordApplicationEvents
class LockConsequencesIT extends IntegrationTest {

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
	private ApplicationEvents events;

	@Autowired
	private ApplicationEventPublisher publisher;

	@Autowired
	private TransactionTemplate transactions;

	private UUID lan;

	private UUID hung;

	private UUID vy;

	private UUID held;

	private final List<UUID> crew = new ArrayList<>();

	@Test
	void theOnlyCrewMembersLockOpensOneSystemDisputeAndTheReleaseStillPaysThemByThePin() throws Exception {
		Administrator ha = AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		UUID job = world();
		lock(ha, this.vy).andExpect(status().isOk());
		String dispute = awaitSystemDispute(job);
		assertThat(text("SELECT status FROM job.crew_row WHERE job_id = '" + job + "'")).isEqualTo("INACTIVE");
		assertThat(text("SELECT (frozen_at IS NOT NULL)::text FROM job.job WHERE id = '" + job + "'")).isEqualTo("true");
		assertThat(notices("job.party_locked", this.hung)).isEqualTo(1);
		assertThat(notices("job.party_locked", ha.accountId())).isEqualTo(1);
		assertThat(notices("job.party_locked", this.lan)).isZero();
		assertThat(notices("dispute.dispute_opened", this.lan)).isEqualTo(1);
		assertThat(this.jdbc.sql("SELECT params ->> 'name' FROM notification.notification WHERE type = 'job.party_locked' LIMIT 1")
			.query(String.class).single()).isEqualTo("Trần Thảo Vy");

		AccountLocked event = this.events.stream(AccountLocked.class).findFirst().orElseThrow();
		this.transactions.executeWithoutResult(status -> this.publisher.publishEvent(event));
		awaitListeners();
		assertThat(count("SELECT count(*) FROM dispute.dispute WHERE job_id = '" + job + "'")).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type = 'job.party_locked'")).isEqualTo(2);

		this.mvc.perform(post("/api/v1/admin/disputes/" + dispute + "/take").with(admin(ha)).with(csrf()))
			.andExpect(status().isOk());
		// Story 15.1: a statement arrived, so the decision need not wait for its period.
		this.jdbc.sql("""
				INSERT INTO dispute.statement (dispute_id, side, text, submitted_at) VALUES (CAST(:id AS UUID), 'CUSTOMER', 'Trình bày', now())
				""").param("id", dispute.toString()).update();
		this.mvc.perform(post("/api/v1/admin/disputes/" + dispute + "/decision").with(admin(ha)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"releaseVnd\":" + PRICE + ",\"refundVnd\":0,\"nextState\":\"CONTINUE\",\"reasoning\":\"Giai đoạn đạt\","
					+ "\"stepUpCode\":\"" + AdministratorTestSupport.code(ha, this.clock.instant()) + "\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.execution.status").value("DONE"));
		assertThat(this.jdbc.sql("""
				SELECT amount_vnd FROM payment.ledger_entry WHERE milestone_id = :id AND entry_type = 'RELEASE' AND party_account_id = :vy
				""").param("id", this.held).param("vy", this.vy).query(Long.class).single()).isEqualTo(240000);

		this.clock.advance(Duration.ofMinutes(5));
		this.mvc.perform(post("/api/v1/admin/users/" + this.vy + "/unlock").with(admin(ha)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"reason\":\"Đã làm rõ\"}")).andExpect(status().isOk());
		assertThat(text("SELECT status FROM job.crew_row WHERE job_id = '" + job + "'")).isEqualTo("INACTIVE");
		awaitListeners();
	}

	@Test
	void aLockedMentorLeavesEachRunningJobToADisputeAndTellsTheCustomerAndTheCrew() throws Exception {
		Administrator ha = AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		UUID first = world();
		UUID second = world();
		lock(ha, this.hung).andExpect(status().isOk());
		awaitSystemDispute(first);
		awaitSystemDispute(second);
		assertThat(notices("job.party_locked", this.lan)).isEqualTo(2);
		for (UUID member : this.crew) {
			assertThat(notices("job.party_locked", member)).isEqualTo(1);
		}
		assertThat(notices("job.party_locked", ha.accountId())).isEqualTo(2);
		assertThat(notices("job.party_locked", this.hung)).isZero();
		assertThat(this.jdbc.sql("SELECT params ->> 'name' FROM notification.notification WHERE type = 'job.party_locked' LIMIT 1")
			.query(String.class).single()).isEqualTo("Đỗ Mạnh Hùng");
		awaitListeners();
	}

	@Test
	void aLockedCustomerLeavesTheJobToADisputeAndTellsTheMentor() throws Exception {
		Administrator ha = AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		UUID job = world();
		lock(ha, this.lan).andExpect(status().isOk());
		awaitSystemDispute(job);
		assertThat(notices("job.party_locked", this.hung)).isEqualTo(1);
		assertThat(notices("job.party_locked", ha.accountId())).isEqualTo(1);
		assertThat(notices("job.party_locked", this.vy)).isZero();
		assertThat(text("SELECT state FROM job.job WHERE id = '" + job + "'")).isEqualTo("ACTIVE");
		awaitListeners();
	}

	private ResultActions lock(Administrator ha, UUID account) throws Exception {
		return this.mvc.perform(post("/api/v1/admin/users/" + account + "/lock").with(admin(ha)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"reason\":\"Vi phạm quy tắc\"}"));
	}

	private String awaitSystemDispute(UUID job) {
		await().atMost(Duration.ofSeconds(15)).until(() -> count("SELECT count(*) FROM dispute.dispute WHERE job_id = '" + job
				+ "' AND opened_by = 'SYSTEM' AND status = 'OPEN'") == 1);
		return text("SELECT id::text FROM dispute.dispute WHERE job_id = '" + job + "'");
	}

	private void awaitListeners() {
		await().atMost(Duration.ofSeconds(15))
			.until(() -> count("SELECT count(*) FROM platform.event_publication WHERE completion_date IS NULL") == 0);
	}

	private long notices(String type, UUID account) {
		return this.jdbc.sql("SELECT count(*) FROM notification.notification WHERE type = :type AND account_id = :account")
			.param("type", type)
			.param("account", account)
			.query(Long.class)
			.single();
	}

	/** An Active Job with a Held Milestone and its FUND, the Crew Terms pin 70/30, and one active Crew member. */
	private UUID world() {
		if (this.hung == null) {
			this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null, null);
			this.jdbc.sql("""
					INSERT INTO identity.customer_profile (account_id, kind, display_name, contact_email, updated_at)
					VALUES (:id, 'INDIVIDUAL', 'Nguyễn Thị Lan', 'lan@example.com', now())
					""").param("id", this.lan).update();
			this.hung = TeamTestSupport.mentor(this.jdbc, MentorApprovalStatus.APPROVED, "Đỗ Mạnh Hùng",
					List.of(TeamTestSupport.REACT), "React");
		}
		// A Student is on one running Crew at a time, so each Job gets its own.
		this.vy = TeamTestSupport.student(this.jdbc, this.crew.isEmpty() ? "Trần Thảo Vy" : "Lê Văn Minh", true,
				List.of(TeamTestSupport.REACT), "React", LocalDate.parse("2026-09-30"));
		this.crew.add(this.vy);
		UUID product = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO product.product (id, customer_account_id, name, description, ai_tools, repository_github_id,
				    repository_full_name, repository_owner_type, repository_default_branch, public_notice_version,
				    copy_permission_version, detection_notice_version, acknowledged_at, created_at)
				VALUES (:id, :owner, 'Sổ đặt bàn', 'Đặt bàn.', ARRAY['LOVABLE'], :github, :name, 'User', 'main',
				    '1.0', '1.0', '1.0', now(), now())
				""").param("id", product).param("owner", this.lan)
			.param("github", ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE))
			.param("name", "lan/so-dat-ban-" + Long.toHexString(ThreadLocalRandom.current().nextLong()))
			.update();
		UUID job = UUID.randomUUID();
		this.held = UUID.randomUUID();
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, budget, deadline, rules, selection_days, opened_at, lapses_at, mentor_account_id,
				    selected_application_id, selected_at, version, created_at, updated_at)
				VALUES (:id, :product, :owner, 'ACTIVE', 'Đánh giá sổ đặt bàn', 'Lỗi', 'Đúng', 'Khách', ARRAY['ASSESSMENT'],
				    'UNDER_2M', DATE '2026-12-01', '{}'::jsonb, 14, :now, :now, :mentor, :application, :now, 5, :now, :now)
				""").param("id", job).param("product", product).param("owner", this.lan).param("mentor", this.hung)
			.param("application", UUID.randomUUID()).param("now", now).update();
		UUID planned = UUID.randomUUID();
		String content = """
				{"scope":"Đánh giá","priceVnd":1300000,"codeJob":false,"assessment":true,"skills":[],"externalAccess":[],
				 "milestones":[{"id":"%s","title":"Báo cáo","priceVnd":%d,"dueOn":"2026-11-20","revisionRounds":2,"criteria":[]},
				  {"id":"%s","title":"Kế hoạch sửa","priceVnd":500000,"dueOn":"2026-12-01","revisionRounds":1,"criteria":[]}]}
				""".formatted(this.held, PRICE, planned);
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
				    crew_terms_version_no)
				VALUES (:id, :job, 0, 'Báo cáo', :price, 2, now(), 'FUNDED', now(), 1)
				""").param("id", this.held).param("job", job).param("price", PRICE).update();
		this.jdbc.sql("""
				INSERT INTO job.milestone (id, job_id, position, title, price_vnd, revision_rounds, created_at)
				VALUES (:id, :job, 1, 'Kế hoạch sửa', 500000, 1, now())
				""").param("id", planned).param("job", job).update();
		for (UUID account : List.of(this.held, planned)) {
			this.jdbc.sql("""
					INSERT INTO payment.ledger_account (milestone_id, job_id, product_id, created_at) VALUES (:id, :job, :product, now())
					""").param("id", account).param("job", job).param("product", product).update();
		}
		UUID order = UUID.randomUUID();
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
			.param("ref", "L" + Long.toString(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE), 36))
			.param("now", now)
			.update();
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
		return job;
	}

	private long count(String sql) {
		return this.jdbc.sql(sql).query(Long.class).single();
	}

	private String text(String sql) {
		return this.jdbc.sql(sql).query(String.class).single();
	}

	private RequestPostProcessor admin(Administrator administrator) {
		return IdentityTestSupport.signedIn(this.sessions, administrator.accountId(), Role.ADMINISTRATOR, this.clock.instant());
	}

}
