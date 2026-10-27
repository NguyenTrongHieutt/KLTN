package vn.vibefix.work.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.identity.AccountLocked;
import vn.vibefix.identity.internal.AdministratorTestSupport;
import vn.vibefix.identity.internal.AdministratorTestSupport.Administrator;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.TodoRequest;
import vn.vibefix.notification.Todos;
import vn.vibefix.payment.Releases;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.team.internal.TeamTestSupport;

/**
 * Stories 11.4 and 20.4 (FR-59, FR-82, FR-23, FR-42, AD-19, AD-41), SM-8 "a locked member on a Crew of three": a lock
 * counts as leaving. Locking one of three active members releases their row as {@code LEFT}, keeps the Job running
 * with no Dispute, tells the Mentor and the other members, the Customer by GitHub username only, and the
 * Administrators, and unassigns the member's open Work Items with no reassignment to-do; the Milestone's release
 * still pays the member by its pin. When the last member is locked, the Customer hears that no member is left, once,
 * and still no Dispute opens. A reassignment to-do opened before Story 20.4 closes at the unassignment.
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
	private Todos todos;

	@Autowired
	private TransactionTemplate transactions;

	@Autowired
	private ApplicationEventPublisher events;

	@Test
	void aLockedMemberOfACrewOfThreeLeavesTheJobRunningAndIsStillPaidByThePin() throws Exception {
		Administrator ha = AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		UUID lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		UUID hung = TeamTestSupport.mentor(this.jdbc, "Đỗ Mạnh Hùng", List.of(TeamTestSupport.REACT),
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
			UUID row = this.jdbc.sql("SELECT id FROM job.crew_row WHERE member_account_id = :id").param("id", crew.get(index))
				.query(UUID.class).single();
			share(job, crew.get(index), row, percents[index]);
		}
		this.boards.seed(job, true);
		this.jdbc.sql("UPDATE work.work_item SET assignee_account_id = :minh WHERE job_id = :job").param("minh", minh)
			.param("job", job).update();

		lock(ha, minh).andExpect(status().isOk());
		awaitListeners();
		assertThat(text("SELECT status || ' ' || released_reason FROM job.crew_row WHERE member_account_id = '" + minh + "'"))
			.isEqualTo("RELEASED LEFT");
		assertThat(count("SELECT count(*) FROM dispute.dispute WHERE job_id = '" + job + "'")).as("the Job keeps running").isZero();
		assertThat(text("SELECT state FROM job.job WHERE id = '" + job + "'")).isEqualTo("ACTIVE");
		for (UUID told : List.of(hung, vy, tung)) {
			assertThat(notices("job.crew_member_left", told)).as("the Mentor and the other active members").isOne();
		}
		assertThat(notices("job.crew_login_removed", lan)).isOne();
		assertThat(notices("job.crew_empty", lan)).as("two members are left").isZero();
		assertThat(notices("job.party_locked", ha.accountId())).isOne();
		assertThat(notices("job.party_locked", hung)).isZero();
		assertThat(notices("job.party_locked", lan)).isZero();
		// FR-82: the member's open Work Items are unassigned for the Mentor, each change in their history by vibefix.
		assertThat(count("SELECT count(*) FROM work.work_item WHERE job_id = '" + job + "' AND assignee_account_id IS NOT NULL"))
			.isZero();
		assertThat(count("""
				SELECT count(*) FROM work.work_item_history WHERE job_id = '%s' AND action = 'UPDATED' AND actor_account_id IS NULL
				  AND changes -> 'assigneeId' ->> 'from' = '%s'
				""".formatted(job, minh))).isEqualTo(2);
		assertThat(count("SELECT count(*) FROM notification.todo WHERE todo_key = 'work.reassign_work_items'"))
			.as("no reassignment to-do opens any more").isZero();

		List<UUID> items = this.jdbc.sql("SELECT id FROM work.work_item WHERE job_id = :job ORDER BY number").param("job", job)
			.query(UUID.class).list();
		reassign(hung, items.get(0), vy).andExpect(status().isOk());
		reassign(hung, items.get(1), tung).andExpect(status().isOk());

		// FR-42, AD-41: the release splits by the Milestone's pin, so the member who left is still paid.
		this.transactions.executeWithoutResult(status -> this.releases.release(job, HELD, UUID.randomUUID()));
		assertThat(this.jdbc.sql("""
				SELECT amount_vnd FROM payment.ledger_entry WHERE milestone_id = :id AND entry_type = 'RELEASE' AND party_account_id = :minh
				""").param("id", HELD).param("minh", minh).query(Long.class).single()).isEqualTo(160000);

		// The last members are locked: the Customer hears once that no member is left, and no Dispute opens.
		lock(ha, vy).andExpect(status().isOk());
		awaitListeners();
		assertThat(notices("job.crew_empty", lan)).isZero();
		lock(ha, tung).andExpect(status().isOk());
		awaitListeners();
		assertThat(notices("job.crew_empty", lan)).isOne();
		assertThat(text("SELECT params ->> 'mentor' FROM notification.notification WHERE type = 'job.crew_empty'"))
			.isEqualTo("Đỗ Mạnh Hùng");
		assertThat(count("SELECT count(*) FROM dispute.dispute WHERE job_id = '" + job + "'")).isZero();
		assertThat(count("SELECT count(*) FROM job.crew_row WHERE job_id = '" + job + "' AND status <> 'RELEASED'")).isZero();
		assertThat(count("SELECT count(*) FROM work.work_item WHERE job_id = '" + job + "' AND assignee_account_id IS NOT NULL"))
			.isZero();
	}

	/** Every lock runs the Work Item consequence; a reassignment to-do opened before Story 20.4 closes at it. */
	@Test
	void aLockedMembersWorkItemsAreUnassignedAndAnOlderReassignmentToDoCloses() throws Exception {
		Administrator ha = AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		UUID lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		UUID hung = TeamTestSupport.mentor(this.jdbc, "Đỗ Mạnh Hùng", List.of(TeamTestSupport.REACT), "React");
		UUID product = WorkTestSupport.product(this.jdbc, lan, "Sổ đặt bàn Quán Mây");
		UUID job = WorkTestSupport.activeJob(this.jdbc, product, lan, hung, List.of(HELD), List.of(CRITERIA), this.clock.instant());
		UUID minh = IdentityTestSupport.insertGoogleAccount(this.jdbc, "google-minh", "3.1");
		IdentityTestSupport.linkGitHub(this.jdbc, minh);
		UUID tung = WorkTestSupport.student(this.jdbc, "Lê Minh Tùng");
		for (UUID member : List.of(minh, tung)) {
			WorkTestSupport.crewRow(this.jdbc, job, member, "ACTIVE", this.clock.instant().minus(Duration.ofMinutes(5)));
		}
		this.boards.seed(job, true);
		this.jdbc.sql("UPDATE work.work_item SET assignee_account_id = :minh WHERE job_id = :job").param("minh", minh)
			.param("job", job).update();
		// An earlier Job of Minh's, now Closed: its Board no longer moves, so the lock leaves its Work Items alone.
		UUID closed = WorkTestSupport.activeJob(this.jdbc, WorkTestSupport.product(this.jdbc, lan, "Trang đặt món"), lan, hung,
				List.of(UUID.randomUUID()), List.of(List.of(UUID.randomUUID(), UUID.randomUUID())), this.clock.instant());
		this.boards.seed(closed, true);
		this.jdbc.sql("UPDATE work.work_item SET assignee_account_id = :minh WHERE job_id = :job").param("minh", minh)
			.param("job", closed).update();
		this.jdbc.sql("UPDATE job.job SET state = 'CLOSED', closed_at = now() WHERE id = :id").param("id", closed).update();
		UUID subject = UUID.nameUUIDFromBytes((job + ":reassign:" + minh).getBytes(StandardCharsets.UTF_8));
		this.transactions.executeWithoutResult(status -> this.todos.open(new TodoRequest(Reassignments.TODO, hung,
				Audience.MENTOR, subject, Map.of("job", "Sổ đặt bàn", "name", "Minh", "n", "2"), "/cong-viec/" + job + "/bang",
				null, job)));

		lock(ha, minh).andExpect(status().isOk());
		awaitListeners();

		assertThat(text("SELECT status FROM job.crew_row WHERE member_account_id = '" + minh + "'")).isEqualTo("RELEASED");
		assertThat(count("SELECT count(*) FROM work.work_item WHERE job_id = '" + job + "' AND assignee_account_id IS NOT NULL"))
			.isZero();
		assertThat(count("SELECT count(*) FROM work.work_item WHERE job_id = '" + closed + "' AND assignee_account_id = '" + minh
				+ "'")).as("a Closed Job's Board stays as it was").isEqualTo(2);
		assertThat(count("SELECT count(*) FROM work.work_item_history WHERE job_id = '" + closed + "' AND action = 'UPDATED'"))
			.isZero();
		assertThat(count("SELECT count(*) FROM notification.todo WHERE todo_key = 'work.reassign_work_items' AND closed_at IS NULL"))
			.isZero();
	}

	/** An event stored before Story 20.4 carries no lock time: the window ends when the listener runs. */
	@Test
	void aLockEventWithoutItsTimeEndsTheWindowAtTheListenersClock() throws Exception {
		UUID lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		UUID hung = TeamTestSupport.mentor(this.jdbc, "Đỗ Mạnh Hùng", List.of(TeamTestSupport.REACT), "React");
		UUID product = WorkTestSupport.product(this.jdbc, lan, "Sổ đặt bàn Quán Mây");
		UUID job = WorkTestSupport.activeJob(this.jdbc, product, lan, hung, List.of(HELD), List.of(CRITERIA), this.clock.instant());
		UUID minh = WorkTestSupport.student(this.jdbc, "Nguyễn Văn Minh");
		WorkTestSupport.crewRow(this.jdbc, job, minh, "ACTIVE", this.clock.instant().minus(Duration.ofDays(1)));
		this.clock.advance(Duration.ofMinutes(5));

		this.transactions.executeWithoutResult(
				status -> this.events.publishEvent(new AccountLocked(UUID.randomUUID(), minh, null)));
		awaitListeners();

		assertThat(text("SELECT status || ' ' || released_reason FROM job.crew_row WHERE member_account_id = '" + minh + "'"))
			.isEqualTo("RELEASED LEFT");
		assertThat(this.jdbc.sql("SELECT window_end FROM job.crew_row WHERE member_account_id = :minh").param("minh", minh)
			.query(Timestamp.class).single().toInstant()).isEqualTo(this.clock.instant());
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
