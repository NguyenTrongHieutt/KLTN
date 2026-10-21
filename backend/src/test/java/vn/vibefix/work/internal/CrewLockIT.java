package vn.vibefix.work.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
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
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.identity.MentorApprovalStatus;
import vn.vibefix.identity.internal.AdministratorTestSupport;
import vn.vibefix.identity.internal.AdministratorTestSupport.Administrator;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.payment.Releases;
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.team.internal.TeamTestSupport;

/**
 * Story 11.4 (FR-59, FR-23, FR-42, AD-19, AD-41), SM-8 "a locked member on a Crew of three": locking
 * one of three active members keeps the Job running with no Dispute, tells the Mentor and the
 * Administrators, and gives the Mentor a to-do for the member's open Work Items that closes once they are
 * reassigned; the Milestone's release still pays the locked member by its pin. When the last member
 * who could work is locked, the Job goes to a Dispute and no to-do opens.
 */
class CrewLockIT extends IntegrationTest {

	private static final long PRICE = 800000;

	private static final UUID HELD = UUID.randomUUID();

	private static final List<UUID> CRITERIA = List.of(UUID.randomUUID(), UUID.randomUUID());

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private ApplicationContext context;

	@Autowired
	private Boards boards;

	@Autowired
	private Releases releases;

	@Autowired
	private TransactionTemplate transactions;

	@Test
	void aLockedMemberOfACrewOfThreeLeavesTheJobRunningAndIsStillPaidByThePin() throws Exception {
		Administrator ha = AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		UUID lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null, null);
		UUID hung = TeamTestSupport.mentor(this.jdbc, MentorApprovalStatus.APPROVED, "Đỗ Mạnh Hùng", List.of(TeamTestSupport.REACT),
				"React");
		UUID product = WorkTestSupport.product(this.jdbc, lan, "Sổ đặt bàn Quán Mây");
		UUID job = WorkTestSupport.activeJob(this.jdbc, product, lan, hung, List.of(HELD), List.of(CRITERIA), this.clock.instant());
		UUID vy = WorkTestSupport.student(this.jdbc, "Trần Thảo Vy");
		UUID minh = WorkTestSupport.student(this.jdbc, "Nguyễn Văn Minh");
		UUID tung = WorkTestSupport.student(this.jdbc, "Lê Minh Tùng");
		this.jdbc.sql("UPDATE job.job SET agreement_version_no = 1 WHERE id = :id").param("id", job).update();
		heldMilestone(job, product, lan);
		this.jdbc.sql("""
				INSERT INTO job.crew_terms_version (job_id, version_no, status, cap_percent, author_account_id, created_at)
				VALUES (:job, 1, 'IN_FORCE', 40, :mentor, now())
				""").param("job", job).param("mentor", hung).update();
		share(job, hung, null, 40);
		int[] percents = { 30, 20, 10 };
		List<UUID> crew = List.of(vy, minh, tung);
		for (int index = 0; index < crew.size(); index++) {
			WorkTestSupport.crewRow(this.jdbc, job, crew.get(index), "ACTIVE", this.clock.instant().minus(Duration.ofMinutes(5)));
			UUID row = this.jdbc.sql("SELECT id FROM job.crew_row WHERE student_account_id = :id").param("id", crew.get(index))
				.query(UUID.class).single();
			share(job, crew.get(index), row, percents[index]);
		}
		this.boards.seed(job, true);
		this.jdbc.sql("UPDATE work.work_item SET assignee_account_id = :minh WHERE job_id = :job").param("minh", minh)
			.param("job", job).update();

		lock(ha, minh).andExpect(status().isOk());
		awaitListeners();
		assertThat(text("SELECT status FROM job.crew_row WHERE student_account_id = '" + minh + "'")).isEqualTo("INACTIVE");
		assertThat(count("SELECT count(*) FROM dispute.dispute WHERE job_id = '" + job + "'")).as("the Job keeps running").isZero();
		assertThat(text("SELECT state FROM job.job WHERE id = '" + job + "'")).isEqualTo("ACTIVE");
		assertThat(notices("job.party_locked", hung)).isOne();
		assertThat(notices("job.party_locked", ha.accountId())).isOne();
		assertThat(notices("job.party_locked", lan)).isZero();
		assertThat(text("SELECT params ->> 'n' FROM notification.todo WHERE todo_key = 'work.reassign_work_items' AND account_id = '"
				+ hung + "' AND closed_at IS NULL")).isEqualTo("2");
		assertThat(text("SELECT link FROM notification.todo WHERE todo_key = 'work.reassign_work_items'"))
			.isEqualTo("/cong-viec/" + job + "/bang");

		List<UUID> items = this.jdbc.sql("SELECT id FROM work.work_item WHERE job_id = :job ORDER BY number").param("job", job)
			.query(UUID.class).list();
		reassign(hung, items.get(0), vy).andExpect(status().isOk())
			.andExpect(jsonPath("$.history[1].assignedTo").value("Trần Thảo Vy"));
		assertThat(openTodos(hung)).as("one Work Item still waits").isOne();
		reassign(hung, items.get(1), tung).andExpect(status().isOk());
		assertThat(openTodos(hung)).isZero();

		// FR-42, AD-41: the release splits by the Milestone's pin, so the locked member is still paid.
		this.transactions.executeWithoutResult(status -> this.releases.release(job, HELD, UUID.randomUUID()));
		assertThat(this.jdbc.sql("""
				SELECT amount_vnd FROM payment.ledger_entry WHERE milestone_id = :id AND entry_type = 'RELEASE' AND party_account_id = :minh
				""").param("id", HELD).param("minh", minh).query(Long.class).single()).isEqualTo(160000);

		// The last members who could work are locked: a Dispute decides, and no reassignment to-do opens.
		lock(ha, vy).andExpect(status().isOk());
		awaitListeners();
		assertThat(openTodos(hung)).as("Tùng can still take Vy's work").isOne();
		lock(ha, tung).andExpect(status().isOk());
		await().atMost(Duration.ofSeconds(15)).until(() -> count("SELECT count(*) FROM dispute.dispute WHERE job_id = '" + job
				+ "' AND opened_by = 'SYSTEM'") == 1);
		awaitListeners();
		assertThat(count("SELECT count(*) FROM notification.todo WHERE todo_key = 'work.reassign_work_items'")).isEqualTo(2);
	}

	private void heldMilestone(UUID job, UUID product, UUID customer) {
		this.jdbc.sql("""
				INSERT INTO job.milestone (id, job_id, position, title, price_vnd, revision_rounds, created_at, state, funded_at,
				    crew_terms_version_no)
				VALUES (:id, :job, 0, 'Giai đoạn 1', :price, 2, now(), 'FUNDED', now(), 1)
				""").param("id", HELD).param("job", job).param("price", PRICE).update();
		this.jdbc.sql("""
				INSERT INTO payment.ledger_account (milestone_id, job_id, product_id, created_at) VALUES (:id, :job, :product, now())
				""").param("id", HELD).param("job", job).param("product", product).update();
		UUID order = UUID.randomUUID();
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO payment.payment_order (id, job_id, product_id, milestone_id, customer_account_id, purpose,
				    amount_vnd, agreement_version_no, txn_ref, status, created_at, expires_at, finished_at)
				VALUES (:id, :job, :product, :milestone, :customer, 'FUNDING', :amount, 1, :ref, 'PAID', :now, :now, :now)
				""").param("id", order).param("job", job).param("product", product).param("milestone", HELD)
			.param("customer", customer).param("amount", PRICE)
			.param("ref", "L" + Long.toString(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE), 36)).param("now", now)
			.update();
		this.jdbc.sql("""
				INSERT INTO payment.ledger_entry (id, operation_key, entry_type, bucket, job_id, product_id, milestone_id,
				    payment_order_id, amount_vnd, vnp_transaction_no, created_at)
				VALUES (:id, :key, 'FUND', 'MILESTONE', :job, :product, :milestone, :order, :amount, :no, now())
				""").param("id", UUID.randomUUID()).param("key", "FUND:" + UUID.randomUUID()).param("job", job)
			.param("product", product).param("milestone", HELD).param("order", order).param("amount", PRICE)
			.param("no", String.valueOf(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE))).update();
	}

	private void share(UUID job, UUID party, UUID row, int percent) {
		this.jdbc.sql("""
				INSERT INTO job.crew_terms_share (job_id, version_no, party_account_id, crew_row_id, percent)
				VALUES (:job, 1, :party, :row, :percent)
				""").param("job", job).param("party", party).param("row", row).param("percent", percent).update();
	}

	private ResultActions reassign(UUID mentor, UUID item, UUID assignee) throws Exception {
		String title = text("SELECT title FROM work.work_item WHERE id = '" + item + "'");
		long version = count("SELECT version FROM work.work_item WHERE id = '" + item + "'");
		return this.mvc.perform(put("/api/v1/work-items/" + item).with(as(mentor, Role.MENTOR)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"type\":\"STORY\",\"title\":\"" + title + "\",\"milestoneId\":\"" + HELD + "\",\"assigneeId\":\"" + assignee
					+ "\",\"version\":" + version + "}"));
	}

	private ResultActions lock(Administrator administrator, UUID account) throws Exception {
		return this.mvc.perform(post("/api/v1/admin/users/" + account + "/lock")
			.with(IdentityTestSupport.signedIn(this.sessions, administrator.accountId(), Role.ADMINISTRATOR, this.clock.instant()))
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"reason\":\"Vi phạm quy tắc\"}"));
	}

	private long openTodos(UUID mentor) {
		return count("SELECT count(*) FROM notification.todo WHERE todo_key = 'work.reassign_work_items' AND account_id = '" + mentor
				+ "' AND closed_at IS NULL");
	}

	private long notices(String type, UUID account) {
		return this.jdbc.sql("SELECT count(*) FROM notification.notification WHERE type = :type AND account_id = :account")
			.param("type", type).param("account", account).query(Long.class).single();
	}

	private void awaitListeners() {
		await().atMost(Duration.ofSeconds(15))
			.until(() -> count("SELECT count(*) FROM platform.event_publication WHERE completion_date IS NULL") == 0);
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
