package vn.vibefix.job.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
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
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.TodoRequest;
import vn.vibefix.notification.Todos;
import vn.vibefix.payment.Ledger;
import vn.vibefix.support.HeldCrewJob;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.TestPeople;

/**
 * Story 25.4 (FR-82, FR-85, AD-47 exceptions 1, 12 and 13, R49): a Self-mentored Job and its Crew. The Customer, who
 * is the Mentor, hears of a member who leaves once and by name, and reads the reason; a to-do opened to them records
 * the one role; and a Held Milestone with no member left is cancelled on the Customer's word alone only when nobody
 * worked on it since it was funded.
 */
class SelfMentoredCrewIT extends IntegrationTest {

	private static final String REASON = "Mình bận thực tập";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private TransactionTemplate transactions;

	@Autowired
	private Todos todos;

	@Autowired
	private Ledger ledger;

	private HeldCrewJob world;

	private UUID job;

	private UUID lan;

	private UUID vy;

	private UUID minh;

	/** {@link HeldCrewJob} with the Customer as its Mentor: Vy and Minh active since before the Held Milestone's funding. */
	@BeforeEach
	void aSelfMentoredJobWithAHeldMilestoneAndTwoMembers() {
		this.world = HeldCrewJob.create(this.jdbc, this.clock.instant());
		this.job = this.world.job();
		this.lan = this.world.lan();
		this.vy = this.world.vy();
		this.minh = this.world.minh();
		this.jdbc.sql("""
				UPDATE job.job SET self_mentored = TRUE, self_mentored_notice_version = '1.0',
				    mentor_account_id = customer_account_id, selected_application_id = NULL WHERE id = :id
				""").param("id", this.job).update();
		this.jdbc.sql("""
				INSERT INTO identity.profile (account_id, kind, display_name, contact_email, updated_at)
				VALUES (:id, 'INDIVIDUAL', 'Nguyễn Thị Lan', 'lan@example.com', now())
				""").param("id", this.lan).update();
	}

	@Test
	void theCustomerHearsOfALeaverOnceAndByNameAndReadsTheReason() throws Exception {
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/crew/me").with(as(this.vy))).andExpect(status().isOk())
			.andExpect(jsonPath("$.selfMentored").value(true));

		leave(this.vy, REASON).andExpect(status().isNoContent());
		awaitListeners();

		assertThat(notices("job.crew_member_left", this.lan)).as("the Mentor's type is not the Customer's").isZero();
		assertThat(notices("job.crew_member_left", this.minh)).as("the other member").isOne();
		assertThat(notices("job.crew_login_removed", this.lan)).isOne();
		assertThat(param("job.crew_login_removed", this.lan, "who"))
			.isEqualTo("Trần Thảo Vy (@" + TestPeople.login(this.jdbc, this.vy) + ")");
		this.mvc.perform(get("/api/v1/mentor/jobs/" + this.job + "/recruitment").with(as(this.lan))).andExpect(status().isOk())
			.andExpect(jsonPath("$.crew[?(@.status == 'RELEASED')].leftNote").value(REASON));
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/role").with(as(this.lan))).andExpect(status().isOk())
			.andExpect(jsonPath("$.role").value("CUSTOMER_MENTOR"))
			.andExpect(jsonPath("$.noCrew").value(false));

		leave(this.minh, null).andExpect(status().isNoContent());
		awaitListeners();

		assertThat(count("SELECT count(*) FROM notification.notification WHERE type = 'job.crew_member_left'"))
			.as("nobody is left to tell by the Mentor's type").isOne();
		assertThat(notices("job.crew_login_removed", this.lan)).isEqualTo(2);
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type = 'job.crew_empty'"))
			.as("nobody carries on, so nothing says so").isZero();
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/role").with(as(this.lan))).andExpect(status().isOk())
			.andExpect(jsonPath("$.noCrew").value(true));
		// R51: each leaver's own duty starts; the Customer is never asked as the Mentor.
		assertThat(openTodos("repo.confirm_data_deletion", this.vy)).isOne();
		assertThat(openTodos("repo.confirm_data_deletion", this.lan)).isZero();
		assertThat(text("SELECT DISTINCT role FROM notification.todo WHERE todo_key = 'repo.confirm_data_deletion'"))
			.isEqualTo("CREW");
	}

	/** AD-47 exception 12 (c): the to-do's role is the combined one, whichever of the two classes it was opened to. */
	@Test
	void aToDoOpenedToTheCustomerRecordsTheCombinedRole() {
		UUID asCustomer = UUID.randomUUID();
		UUID asMentor = UUID.randomUUID();
		UUID asMember = UUID.randomUUID();
		this.transactions.executeWithoutResult(tx -> {
			this.todos.open(new TodoRequest(ExternalAccessGrants.TODO, this.lan, Audience.CUSTOMER, asCustomer,
					Map.of("system", "Supabase"), "/cong-viec/" + this.job + "/thoa-thuan", null, this.job));
			this.todos.open(new TodoRequest("job.draft_agreement", this.lan, Audience.MENTOR, asMentor, Map.of("job", "Sửa lỗi đặt bàn"),
					"/cong-viec/" + this.job + "/thoa-thuan", null, this.job));
			this.todos.open(new TodoRequest("repo.confirm_data_deletion", this.vy, Audience.CREW_EVER_ACTIVE, asMember,
					Map.of("job", "Sửa lỗi đặt bàn"), "/cong-viec/" + this.job, null, this.job));
		});

		assertThat(role(asCustomer)).isEqualTo("CUSTOMER_MENTOR");
		assertThat(role(asMentor)).isEqualTo("CUSTOMER_MENTOR");
		assertThat(role(asMember)).isEqualTo("CREW");
	}

	/** A normal Job's to-dos keep the role of the class they were opened to. */
	@Test
	void aNormalJobsToDoKeepsTheRoleOfItsClass() {
		HeldCrewJob normal = HeldCrewJob.create(this.jdbc, this.clock.instant());
		UUID asCustomer = UUID.randomUUID();
		UUID asMentor = UUID.randomUUID();
		this.transactions.executeWithoutResult(tx -> {
			this.todos.open(new TodoRequest(ExternalAccessGrants.TODO, normal.lan(), Audience.CUSTOMER, asCustomer,
					Map.of("system", "Supabase"), "/cong-viec/" + normal.job() + "/thoa-thuan", null, normal.job()));
			this.todos.open(new TodoRequest("job.draft_agreement", normal.hung(), Audience.MENTOR, asMentor,
					Map.of("job", "Sửa lỗi đặt bàn"), "/cong-viec/" + normal.job() + "/thoa-thuan", null, normal.job()));
		});

		assertThat(role(asCustomer)).isEqualTo("CUSTOMER");
		assertThat(role(asMentor)).isEqualTo("MENTOR");
	}

	/**
	 * R49: Vy and Minh worked since the Milestone was funded, so with both gone the Customer does not take the money back
	 * alone; the page offers recruiting again or a Dispute, and recruiting again works.
	 */
	@Test
	void aHeldMilestoneAMemberWorkedOnIsNotCancelledAlone() throws Exception {
		cancellation().andExpect(jsonPath("$.noMember").doesNotExist()).andExpect(jsonPath("$.canPropose").value(true));
		leave(this.vy, null).andExpect(status().isNoContent());
		leave(this.minh, null).andExpect(status().isNoContent());
		awaitListeners();

		cancellation().andExpect(jsonPath("$.noMember").value("CREW_WAS_ACTIVE"))
			.andExpect(jsonPath("$.canPropose").value(false))
			.andExpect(jsonPath("$.canCancelAlone").value(false))
			.andExpect(jsonPath("$.held").value(true));
		cancel().andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.reason").value("CREW_WAS_ACTIVE"));

		assertThat(count("SELECT count(*) FROM job.cancellation_proposal WHERE job_id = '" + this.job + "'")).isZero();
		assertThat(text("SELECT state FROM job.job WHERE id = '" + this.job + "'")).isEqualTo("ACTIVE");
		assertThat(text("SELECT state FROM job.milestone WHERE id = '" + this.world.milestone() + "'")).isEqualTo("FUNDED");
		assertThat(this.ledger.releasable(this.world.milestone())).isEqualTo(HeldCrewJob.PRICE);
		long crewVersion = count("SELECT crew_version FROM job.job WHERE id = '" + this.job + "'");
		this.mvc.perform(put("/api/v1/mentor/jobs/" + this.job + "/recruitment").with(as(this.lan)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"on":true,"openSlots":1,"skillIds":["%s"],"note":"Cần một bạn làm tiếp giai đoạn 1","crewVersion":%d}
					""".formatted(TestPeople.REACT, crewVersion)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.recruiting").value(true));
	}

	/**
	 * R49, M7's alone-cancel: both members left before the Milestone was funded, so nobody worked on it, and the
	 * Customer's cancellation command applies at once with the full refund.
	 */
	@Test
	void aHeldMilestoneNobodyWorkedOnIsCancelledAloneWithTheFullRefund() throws Exception {
		leaveBeforeTheFunding();

		cancellation().andExpect(jsonPath("$.noMember").value("ALONE")).andExpect(jsonPath("$.canPropose").value(true));
		cancel().andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("CANCELLED"))
			.andExpect(jsonPath("$.proposal.status").value("CONFIRMED"))
			.andExpect(jsonPath("$.noMember").doesNotExist());

		assertThat(text("SELECT status || ':' || alone || ':' || (confirmed_by = proposer_account_id) FROM job.cancellation_proposal"
				+ " WHERE job_id = '" + this.job + "'")).isEqualTo("CONFIRMED:true:true");
		assertThat(text("SELECT state FROM job.milestone WHERE id = '" + this.world.milestone() + "'")).isEqualTo("CANCELLED");
		assertThat(count("SELECT count(*) FROM audit.audit_entry WHERE action = 'job.cancellation_alone'")).isOne();
		assertThat(count("SELECT count(*) FROM notification.todo WHERE todo_key = 'job.answer_cancellation'")).isZero();
		await().atMost(Duration.ofSeconds(10)).until(() -> this.ledger.releasable(this.world.milestone()) == 0);
		assertThat(text("SELECT reason || ':' || amount_vnd FROM payment.refund WHERE milestone_id = '" + this.world.milestone()
				+ "'")).isEqualTo("CANCELLED:" + HeldCrewJob.PRICE);
		awaitListeners();
	}

	/** A window that ends exactly when the Milestone is funded counts as work on it: the bound is included. */
	@Test
	void aMemberWhoLeftTheMomentTheMilestoneWasFundedCounts() throws Exception {
		leave(this.vy, null).andExpect(status().isNoContent());
		leave(this.minh, null).andExpect(status().isNoContent());
		awaitListeners();
		this.jdbc.sql("UPDATE job.milestone SET funded_at = :at WHERE id = :id").param("at", Timestamp.from(this.clock.instant()))
			.param("id", this.world.milestone()).update();

		cancellation().andExpect(jsonPath("$.noMember").value("CREW_WAS_ACTIVE"));
	}

	/** A member who joined after the funding and left again worked on the Milestone, however briefly. */
	@Test
	void aMemberWhoJoinedAfterTheFundingAndLeftCounts() throws Exception {
		leaveBeforeTheFunding();
		cancellation().andExpect(jsonPath("$.noMember").value("ALONE"));
		Timestamp joined = Timestamp.from(this.clock.instant().plus(Duration.ofMinutes(1)));
		Timestamp left = Timestamp.from(this.clock.instant().plus(Duration.ofMinutes(2)));
		this.jdbc.sql("""
				INSERT INTO job.crew_row (id, job_id, member_account_id, status, signed_at, activated_at, window_end, released_at,
				    released_reason, created_at)
				VALUES (:id, :job, :member, 'RELEASED', :joined, :joined, :left, :left, 'LEFT', :joined)
				""").param("id", UUID.randomUUID()).param("job", this.job)
			.param("member", TestPeople.student(this.jdbc, "Lê Văn Hải", true, java.util.List.of(TestPeople.REACT), "React",
					java.time.LocalDate.parse("2026-09-30")))
			.param("joined", joined).param("left", left).update();

		cancellation().andExpect(jsonPath("$.noMember").value("CREW_WAS_ACTIVE"));
		cancel().andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.reason").value("CREW_WAS_ACTIVE"));
	}

	/** A pick that never signed did no work: it does not stand between the Customer and the refund. */
	@Test
	void aPickThatNeverSignedDoesNotCount() throws Exception {
		leaveBeforeTheFunding();
		this.jdbc.sql("""
				INSERT INTO job.crew_row (id, job_id, member_account_id, status, created_at)
				VALUES (:id, :job, :member, 'ASSIGNED', now())
				""").param("id", UUID.randomUUID()).param("job", this.job)
			.param("member", TestPeople.student(this.jdbc, "Lê Văn Hải", true, java.util.List.of(TestPeople.REACT), "React",
					java.time.LocalDate.parse("2026-09-30")))
			.update();

		cancellation().andExpect(jsonPath("$.noMember").value("ALONE"));
	}

	/** A cascade is refused while a Dispute freezes the Job, so the page does not offer it then. */
	@Test
	void aFrozenJobIsNotOfferedTheCancellationAlone() throws Exception {
		leaveBeforeTheFunding();
		this.jdbc.sql("UPDATE job.job SET frozen_at = :at WHERE id = :id").param("at", Timestamp.from(this.clock.instant()))
			.param("id", this.job).update();

		cancellation().andExpect(jsonPath("$.noMember").value("ALONE"))
			.andExpect(jsonPath("$.canPropose").value(false))
			.andExpect(jsonPath("$.frozen").value(true));
		cancel().andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("JOB_FROZEN"));
	}

	/** With a member still active the command proposes, as before; the members' agreement is Story 25.7's. */
	@Test
	void whileAMemberIsActiveTheCommandStillProposes() throws Exception {
		leave(this.vy, null).andExpect(status().isNoContent());
		awaitListeners();

		cancellation().andExpect(jsonPath("$.noMember").doesNotExist());
		cancel().andExpect(status().isOk()).andExpect(jsonPath("$.proposal.status").value("OPEN"));
		assertThat(text("SELECT state FROM job.job WHERE id = '" + this.job + "'")).isEqualTo("ACTIVE");
		awaitListeners();
	}

	/** A normal Job whose Crew emptied is cancelled by agreement with its Mentor, never by this rule. */
	@Test
	void aNormalJobWithNoMemberKeepsItsProposal() throws Exception {
		HeldCrewJob normal = HeldCrewJob.create(this.jdbc, this.clock.instant());
		for (UUID member : new UUID[] { normal.vy(), normal.minh() }) {
			this.mvc.perform(post("/api/v1/jobs/" + normal.job() + "/crew/leave").with(as(member)).with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNoContent());
		}
		awaitListeners();

		this.mvc.perform(get("/api/v1/boards/" + normal.job() + "/cancellation").with(as(normal.lan()))).andExpect(status().isOk())
			.andExpect(jsonPath("$.noMember").doesNotExist())
			.andExpect(jsonPath("$.canPropose").value(true));
		assertThat(notices("job.crew_empty", normal.lan())).as("the Mentor carries on").isOne();
		assertThat(notices("job.crew_member_left", normal.hung())).isEqualTo(2);
		assertThat(param("job.crew_login_removed", normal.lan(), "who")).startsWith("@").doesNotContain("Trần").doesNotContain("Nguyễn");
	}

	/** Vy and Minh leave, and the Held Milestone is funded five minutes later: nobody worked on it. */
	private void leaveBeforeTheFunding() throws Exception {
		leave(this.vy, null).andExpect(status().isNoContent());
		leave(this.minh, null).andExpect(status().isNoContent());
		awaitListeners();
		this.clock.advance(Duration.ofMinutes(5));
		this.jdbc.sql("UPDATE job.milestone SET funded_at = :at WHERE id = :id").param("at", Timestamp.from(this.clock.instant()))
			.param("id", this.world.milestone()).update();
	}

	private ResultActions cancellation() throws Exception {
		return this.mvc.perform(get("/api/v1/boards/" + this.job + "/cancellation").with(as(this.lan))).andExpect(status().isOk());
	}

	private ResultActions cancel() throws Exception {
		long version = count("SELECT version FROM job.job WHERE id = '" + this.job + "'");
		return this.mvc.perform(post("/api/v1/boards/" + this.job + "/cancellation").with(as(this.lan)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"scope\":\"JOB\",\"reason\":\"Không còn ai làm giai đoạn này\",\"version\":" + version + "}"));
	}

	private ResultActions leave(UUID member, @Nullable String reason) throws Exception {
		return this.mvc.perform(post("/api/v1/jobs/" + this.job + "/crew/leave").with(as(member)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(reason == null ? "{}" : "{\"reason\":\"" + reason + "\"}"));
	}

	private @Nullable String role(UUID subject) {
		return this.jdbc.sql("SELECT role FROM notification.todo WHERE subject_id = :subject").param("subject", subject)
			.query(String.class).single();
	}

	private String param(String type, UUID account, String name) {
		return this.jdbc.sql("""
				SELECT params ->> :name FROM notification.notification WHERE type = :type AND account_id = :account
				ORDER BY created_at LIMIT 1
				""").param("name", name).param("type", type).param("account", account).query(String.class).single();
	}

	private long notices(String type, UUID account) {
		return this.jdbc.sql("SELECT count(*) FROM notification.notification WHERE type = :type AND account_id = :account")
			.param("type", type).param("account", account).query(Long.class).single();
	}

	private long openTodos(String key, UUID account) {
		return this.jdbc.sql("""
				SELECT count(*) FROM notification.todo WHERE todo_key = :key AND account_id = :account AND closed_at IS NULL
				""").param("key", key).param("account", account).query(Long.class).single();
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

	private RequestPostProcessor as(UUID account) {
		return IdentityTestSupport.signedIn(this.sessions, account, Role.CUSTOMER, this.clock.instant());
	}

}
