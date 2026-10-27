package vn.vibefix.dispute.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
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
import vn.vibefix.identity.StudentVerification;
import vn.vibefix.identity.internal.AdministratorTestSupport;
import vn.vibefix.identity.internal.AdministratorTestSupport.Administrator;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.payment.Releases;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.team.internal.TeamTestSupport;

/**
 * FR-59, FR-82, AD-19, AD-41, SM-8 "account lock mid-Milestone" (Stories 10.2 and 20.4): a lock counts as leaving.
 * Locking the only Crew member of an Active Job with a Held Milestone releases their row as {@code LEFT} with the
 * window ending at the lock's time, tells the Mentor, the Customer by GitHub username only, and the Administrators,
 * tells the Customer that no member is left, and opens no Dispute; a re-delivery changes nothing, the Milestone's
 * release still pays the member by its pin, and an unlock brings nothing back and removed no Verification. A
 * pick not yet signed is released as {@code DECLINED} and only the Mentor hears. A locked Mentor or Customer leaves
 * each running Job to one Dispute by the System, and one lock acts on every relation, Job by Job.
 */
@RecordApplicationEvents
class LockConsequencesIT extends IntegrationTest {

	private static final long PRICE = 800000;

	private static final List<String> LOCK_NOTICES = List.of("job.crew_member_left", "job.crew_login_removed",
			"job.crew_pick_released", "job.crew_empty", "job.party_locked");

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

	@Autowired
	private Releases releases;

	@Autowired
	private StudentVerification verification;

	private UUID lan;

	private UUID hung;

	private UUID vy;

	private UUID held;

	private final List<UUID> crew = new ArrayList<>();

	@Test
	void theOnlyCrewMembersLockCountsAsLeavingAndOpensNoDispute() throws Exception {
		Administrator ha = AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		UUID job = world();
		lock(ha, this.vy).andExpect(status().isOk());
		awaitListeners();
		Instant lockedAt = lockedAt(this.vy);

		assertThat(text("SELECT status || ' ' || released_reason FROM job.crew_row WHERE job_id = '" + job + "'"))
			.isEqualTo("RELEASED LEFT");
		assertThat(this.jdbc.sql("SELECT window_end FROM job.crew_row WHERE job_id = :job").param("job", job)
			.query(Timestamp.class).single().toInstant()).as("the window ends at the lock").isEqualTo(lockedAt);
		assertThat(count("SELECT count(*) FROM dispute.dispute WHERE job_id = '" + job + "'")).as("no Dispute for no Crew")
			.isZero();
		assertThat(text("SELECT state || ' ' || (frozen_at IS NOT NULL)::text FROM job.job WHERE id = '" + job + "'"))
			.isEqualTo("ACTIVE false");
		assertThat(notices("job.crew_member_left", this.hung)).isOne();
		assertThat(param("job.crew_member_left", this.hung, "name")).isEqualTo("Trần Thảo Vy");
		assertThat(notices("job.crew_login_removed", this.lan)).isOne();
		assertThat(param("job.crew_login_removed", this.lan, "login")).isEqualTo(TeamTestSupport.login(this.jdbc, this.vy));
		assertThat(notices("job.crew_empty", this.lan)).isOne();
		assertThat(param("job.crew_empty", this.lan, "mentor")).isEqualTo("Đỗ Mạnh Hùng");
		assertThat(notices("job.party_locked", ha.accountId())).isOne();
		assertThat(notices("job.party_locked", this.hung)).isZero();
		assertThat(notices("job.party_locked", this.lan)).isZero();
		// UX-DR104, UX-DR108: the Customer reads the username only, never a name or the reason.
		assertThat(this.jdbc.sql("SELECT string_agg(params::text, ' ') FROM notification.notification WHERE account_id = :lan")
			.param("lan", this.lan).query(String.class).single()).doesNotContain("Trần Thảo Vy").doesNotContain("Vi phạm");

		AccountLocked event = this.events.stream(AccountLocked.class).findFirst().orElseThrow();
		assertThat(event.lockedAt()).isEqualTo(lockedAt);
		long told = lockNotices();
		this.transactions.executeWithoutResult(status -> this.publisher.publishEvent(event));
		awaitListeners();
		assertThat(lockNotices()).as("a replay changes nothing").isEqualTo(told);
		assertThat(count("SELECT count(*) FROM dispute.dispute WHERE job_id = '" + job + "'")).isZero();

		// FR-42, AD-41: the release splits by the Milestone's pin, so the member who left is still paid.
		this.transactions.executeWithoutResult(status -> this.releases.release(job, this.held, UUID.randomUUID()));
		assertThat(this.jdbc.sql("""
				SELECT amount_vnd FROM payment.ledger_entry WHERE milestone_id = :id AND entry_type = 'RELEASE' AND party_account_id = :vy
				""").param("id", this.held).param("vy", this.vy).query(Long.class).single()).isEqualTo(240000);

		this.clock.advance(Duration.ofMinutes(5));
		this.mvc.perform(post("/api/v1/admin/users/" + this.vy + "/unlock").with(admin(ha)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"reason\":\"Đã làm rõ\"}")).andExpect(status().isOk());
		awaitListeners();
		assertThat(text("SELECT status FROM job.crew_row WHERE job_id = '" + job + "'")).as("an unlock brings no row back")
			.isEqualTo("RELEASED");
		assertThat(this.verification.isVerified(this.vy)).as("a lock removes no Verification").isTrue();
	}

	/** Q7, FR-82: a Crew emptied in Starting tells the Customer too, and opens no Dispute. */
	@Test
	void theOnlyMembersLockInStartingTellsTheCustomerTheCrewIsEmpty() throws Exception {
		Administrator ha = AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		UUID job = world();
		this.jdbc.sql("UPDATE job.job SET state = 'STARTING' WHERE id = :id").param("id", job).update();

		lock(ha, this.vy).andExpect(status().isOk());
		awaitListeners();

		assertThat(text("SELECT status || ' ' || released_reason FROM job.crew_row WHERE job_id = '" + job + "'"))
			.isEqualTo("RELEASED LEFT");
		assertThat(notices("job.crew_empty", this.lan)).isOne();
		assertThat(param("job.crew_empty", this.lan, "mentor")).isEqualTo("Đỗ Mạnh Hùng");
		assertThat(count("SELECT count(*) FROM dispute.dispute WHERE job_id = '" + job + "'")).isZero();
		assertThat(text("SELECT state FROM job.job WHERE id = '" + job + "'")).isEqualTo("STARTING");
	}

	/** Story 20.4: a pick not yet signed is released as DECLINED; the Customer never learns of picks. */
	@Test
	void aPickNotYetSignedIsReleasedAsDeclinedAndOnlyTheMentorHears() throws Exception {
		Administrator ha = AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		UUID job = world();
		UUID ngan = TeamTestSupport.student(this.jdbc, "Hoàng Kim Ngân", true, List.of(TeamTestSupport.REACT), "React",
				LocalDate.parse("2026-09-30"));
		this.jdbc.sql("""
				INSERT INTO job.crew_row (id, job_id, member_account_id, status, created_at)
				VALUES (:id, :job, :ngan, 'ASSIGNED', now())
				""").param("id", UUID.randomUUID()).param("job", job).param("ngan", ngan).update();

		lock(ha, ngan).andExpect(status().isOk());
		awaitListeners();

		assertThat(text("SELECT status || ' ' || released_reason || ' ' || (window_end IS NULL)::text FROM job.crew_row"
				+ " WHERE member_account_id = '" + ngan + "'")).isEqualTo("RELEASED DECLINED true");
		assertThat(notices("job.crew_pick_released", this.hung)).isOne();
		assertThat(param("job.crew_pick_released", this.hung, "name")).isEqualTo("Hoàng Kim Ngân");
		assertThat(count("SELECT count(*) FROM notification.notification WHERE account_id = '" + this.lan + "'"))
			.as("the Customer never learns of picks").isZero();
		assertThat(notices("job.party_locked", ha.accountId())).isZero();
		assertThat(notices("job.crew_member_left", this.hung)).isZero();
		assertThat(notices("job.crew_empty", this.lan)).as("Vy still works on it").isZero();
		assertThat(text("SELECT status FROM job.crew_row WHERE member_account_id = '" + this.vy + "'")).isEqualTo("ACTIVE");
	}

	/** AD-19, AD-20: the Customer of one Job and an active member of another, in one fan-out, replay-safe. */
	@Test
	void oneLockActsOnTheJobItIsTheCustomerOfAndTheJobItWorksOn() throws Exception {
		Administrator ha = AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		UUID ordered = world();
		UUID crewed = world();
		UUID minh = this.vy;
		// Minh, the only active member of the second Job, is the Customer of the first.
		this.jdbc.sql("UPDATE job.job SET customer_account_id = :minh WHERE id = :id").param("minh", minh).param("id", ordered)
			.update();

		lock(ha, minh).andExpect(status().isOk());
		awaitSystemDispute(ordered);
		awaitListeners();

		assertThat(count("SELECT count(*) FROM dispute.dispute WHERE job_id = '" + crewed + "'")).isZero();
		assertThat(text("SELECT status || ' ' || released_reason FROM job.crew_row WHERE member_account_id = '" + minh + "'"))
			.isEqualTo("RELEASED LEFT");
		assertThat(this.jdbc.sql("SELECT window_end FROM job.crew_row WHERE member_account_id = :minh").param("minh", minh)
			.query(Timestamp.class).single().toInstant()).isEqualTo(lockedAt(minh));
		assertThat(notices("job.crew_member_left", this.hung)).as("the second Job's Mentor").isOne();
		assertThat(notices("job.crew_login_removed", this.lan)).as("the second Job's Customer").isOne();
		assertThat(notices("job.party_locked", this.hung)).as("the first Job's Mentor hears of its Customer").isOne();
		assertThat(notices("job.party_locked", ha.accountId())).isEqualTo(2);
		assertThat(text("SELECT status FROM job.crew_row WHERE job_id = '" + ordered + "'")).isEqualTo("ACTIVE");

		long told = lockNotices();
		AccountLocked event = this.events.stream(AccountLocked.class).findFirst().orElseThrow();
		this.transactions.executeWithoutResult(status -> this.publisher.publishEvent(event));
		awaitListeners();
		assertThat(lockNotices()).as("a replay changes nothing").isEqualTo(told);
		assertThat(count("SELECT count(*) FROM dispute.dispute WHERE job_id = '" + ordered + "'")).isOne();
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

	/** Story 20.3: no role switch; one lock acts on every relation, here the Mentor of one Job and Customer of another. */
	@Test
	void oneLockActsOnTheJobTheAccountMentorsAndTheJobItIsTheCustomerOf() throws Exception {
		Administrator ha = AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		UUID mentored = world();
		UUID ordered = world();
		UUID thao = TeamTestSupport.mentor(this.jdbc, "Nguyễn Phương Thảo", List.of(TeamTestSupport.REACT), "React");
		// Hung, the Mentor of the first Job, is the Customer of the second, which Thảo mentors.
		this.jdbc.sql("UPDATE job.job SET customer_account_id = :hung, mentor_account_id = :thao WHERE id = :id")
			.param("hung", this.hung).param("thao", thao).param("id", ordered).update();

		lock(ha, this.hung).andExpect(status().isOk());
		awaitSystemDispute(mentored);
		awaitSystemDispute(ordered);
		awaitListeners();

		assertThat(notices("job.party_locked", this.lan)).as("the first Job's Customer hears of its Mentor").isEqualTo(1);
		assertThat(notices("job.party_locked", thao)).as("the second Job's Mentor hears of its Customer").isEqualTo(1);
		assertThat(notices("job.party_locked", ha.accountId())).isEqualTo(2);
		assertThat(notices("job.party_locked", this.hung)).isZero();
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

	private long lockNotices() {
		return this.jdbc.sql("SELECT count(*) FROM notification.notification WHERE type IN (:types)")
			.param("types", LOCK_NOTICES)
			.query(Long.class)
			.single();
	}

	private String param(String type, UUID account, String name) {
		return this.jdbc.sql("""
				SELECT params ->> :name FROM notification.notification WHERE type = :type AND account_id = :account
				""").param("name", name).param("type", type).param("account", account).query(String.class).single();
	}

	private Instant lockedAt(UUID account) {
		return this.jdbc.sql("SELECT locked_at FROM identity.account WHERE id = :id").param("id", account)
			.query(Timestamp.class).single().toInstant();
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
			this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
			this.jdbc.sql("""
					INSERT INTO identity.profile (account_id, kind, display_name, contact_email, updated_at)
					VALUES (:id, 'INDIVIDUAL', 'Nguyễn Thị Lan', 'lan@example.com', now())
					""").param("id", this.lan).update();
			this.hung = TeamTestSupport.mentor(this.jdbc, "Đỗ Mạnh Hùng",
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
				INSERT INTO job.crew_row (id, job_id, member_account_id, status, signed_at, activated_at, created_at)
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
