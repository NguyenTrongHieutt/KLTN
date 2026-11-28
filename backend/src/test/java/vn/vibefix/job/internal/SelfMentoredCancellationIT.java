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

import com.jayway.jsonpath.JsonPath;
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
import vn.vibefix.job.MilestoneSettlements;
import vn.vibefix.payment.Ledger;
import vn.vibefix.support.HeldCrewJob;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.TestPeople;
import vn.vibefix.timers.internal.DeadlinePoller;

/**
 * Story 25.7, part a (FR-37, FR-40, FR-67, AD-47 exception 6, R56, B-04, B-05 a, B-06): closing and cancelling a
 * Self-mentored Job. With no Held Milestone the Customer, who is the Mentor, closes or cancels alone and the members
 * are told. With a Held Milestone a cancellation needs the agreement of every member who was active when it was
 * proposed: each answers once, a refusal ends it, a member who leaves no longer counts and one who joins is not asked.
 * The System's closure proposal after the last acceptance asks only the Customer. A normal Job is as it was.
 */
class SelfMentoredCancellationIT extends IntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private TransactionTemplate transactions;

	@Autowired
	private DeadlinePoller poller;

	@Autowired
	private Ledger ledger;

	@Autowired
	private ClosureProposals closureProposals;

	@Autowired
	private Jobs jobs;

	@Autowired
	private MilestoneSettlements settlements;

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
		selfMentored(this.job);
	}

	@Test
	void theMembersActiveThenAreAskedAndTheirAgreementCancelsWithTheFullRefund() throws Exception {
		String proposal = proposed();
		awaitListeners();

		this.mvc.perform(get("/api/v1/boards/" + this.job + "/cancellation").with(as(this.lan))).andExpect(status().isOk())
			.andExpect(jsonPath("$.selfMentored").value(true))
			.andExpect(jsonPath("$.proposal.status").value("OPEN"))
			.andExpect(jsonPath("$.proposal.mine").value(true))
			.andExpect(jsonPath("$.proposal.asked").value(2))
			.andExpect(jsonPath("$.proposal.agreed").value(0))
			.andExpect(jsonPath("$.proposal.mayAnswer").value(false));
		// The other side is the members, never the proposer's own account.
		for (UUID member : List.of(this.vy, this.minh)) {
			assertThat(notices("job.cancellation_awaiting_members", member)).isOne();
			assertThat(openTodos("job.answer_cancellation", member)).isOne();
		}
		assertThat(notices("job.cancellation_awaiting_members", this.lan)).isZero();
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type = 'job.cancellation_proposed'")).isZero();
		assertThat(openTodos("job.answer_cancellation", this.lan)).isZero();
		assertThat(text("SELECT DISTINCT role FROM notification.todo WHERE todo_key = 'job.answer_cancellation'")).isEqualTo("CREW");

		// The confirm command is not there in the mode, and the Customer is not asked.
		long version = version();
		this.mvc.perform(post("/api/v1/boards/" + this.job + "/cancellation/confirm").with(as(this.lan)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + version + "}")).andExpect(status().isNotFound());
		answer(this.lan, proposal, true).andExpect(status().isForbidden()).andExpect(jsonPath("$.reason").value("NOT_ASKED"));

		this.mvc.perform(get("/api/v1/boards/" + this.job + "/cancellation").with(as(this.vy))).andExpect(status().isOk())
			.andExpect(jsonPath("$.audience").value("CREW"))
			.andExpect(jsonPath("$.proposal.reason").value("Sản phẩm đổi hướng"))
			.andExpect(jsonPath("$.proposal.mayAnswer").value(true))
			.andExpect(jsonPath("$.proposal.myAnswer").doesNotExist());
		answer(this.vy, UUID.randomUUID().toString(), true).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("STALE"));
		answer(this.vy, proposal, true).andExpect(status().isOk())
			.andExpect(jsonPath("$.proposal.status").value("OPEN"))
			.andExpect(jsonPath("$.proposal.agreed").value(1))
			.andExpect(jsonPath("$.proposal.mayAnswer").value(false))
			.andExpect(jsonPath("$.proposal.myAnswer").value("AGREED"));
		answer(this.vy, proposal, false).andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("PROPOSAL_ANSWERED"));
		assertThat(openTodos("job.answer_cancellation", this.vy)).isZero();
		assertThat(text("SELECT state FROM job.job WHERE id = '" + this.job + "'")).isEqualTo("ACTIVE");

		answer(this.minh, proposal, true).andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("CANCELLED"))
			.andExpect(jsonPath("$.proposal.status").value("CONFIRMED"));
		awaitListeners();

		assertThat(text("SELECT status || ':' || alone FROM job.cancellation_proposal WHERE id = '" + proposal + "'"))
			.isEqualTo("CONFIRMED:false");
		assertThat(text("SELECT state FROM job.milestone WHERE id = '" + this.world.milestone() + "'")).isEqualTo("CANCELLED");
		await().atMost(Duration.ofSeconds(10)).until(() -> this.ledger.releasable(this.world.milestone()) == 0);
		assertThat(text("SELECT reason || ':' || amount_vnd FROM payment.refund WHERE milestone_id = '" + this.world.milestone()
				+ "'")).isEqualTo("CANCELLED:" + HeldCrewJob.PRICE);
		assertThat(count("SELECT count(*) FROM notification.todo WHERE todo_key = 'job.answer_cancellation' AND closed_at IS NULL"))
			.isZero();
		assertThat(count("SELECT count(*) FROM audit.audit_entry WHERE action = 'job.cancellation_agreed'")).isEqualTo(2);
		for (UUID told : List.of(this.lan, this.vy, this.minh)) {
			assertThat(notices("job.job_cancelled", told)).as("the Customer once, and each member").isOne();
		}
	}

	@Test
	void aRefusalEndsTheProposalAndTellsTheCustomer() throws Exception {
		String proposal = proposed();
		answer(this.vy, proposal, true).andExpect(status().isOk());

		answer(this.minh, proposal, false).andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("ACTIVE"))
			.andExpect(jsonPath("$.proposal.status").value("REFUSED"))
			.andExpect(jsonPath("$.proposal.myAnswer").value("REFUSED"));
		awaitListeners();

		assertThat(notices("job.cancellation_refused", this.lan)).isOne();
		assertThat(count("SELECT count(*) FROM notification.todo WHERE todo_key = 'job.answer_cancellation' AND closed_at IS NULL"))
			.isZero();
		assertThat(text("SELECT state FROM job.milestone WHERE id = '" + this.world.milestone() + "'")).isEqualTo("FUNDED");
		this.mvc.perform(get("/api/v1/boards/" + this.job + "/cancellation").with(as(this.lan))).andExpect(status().isOk())
			.andExpect(jsonPath("$.canPropose").value(true))
			.andExpect(jsonPath("$.proposal.status").value("REFUSED"));
		// The Customer may ask again: a new proposal asks the members anew.
		String again = proposed();
		assertThat(again).isNotEqualTo(proposal);
		answer(this.vy, proposal, true).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE"));
	}

	/** R56: who counts is who was active when the proposal was made and still is. */
	@Test
	void aMemberWhoLeavesNoLongerCountsAndOneWhoJoinsIsNotAsked() throws Exception {
		String proposal = proposed();
		leave(this.minh);
		awaitListeners();
		UUID an = TestPeople.student(this.jdbc, "Phạm Hoài An", true, List.of(TestPeople.REACT), "React",
				LocalDate.parse("2026-09-30"));
		this.jdbc.sql("""
				INSERT INTO job.crew_row (id, job_id, member_account_id, status, signed_at, activated_at, created_at)
				VALUES (:id, :job, :member, 'ACTIVE', :at, :at, :at)
				""").param("id", UUID.randomUUID()).param("job", this.job).param("member", an)
			.param("at", Timestamp.from(this.clock.instant())).update();

		this.mvc.perform(get("/api/v1/boards/" + this.job + "/cancellation").with(as(this.lan))).andExpect(status().isOk())
			.andExpect(jsonPath("$.proposal.asked").value(1));
		answer(an, proposal, true).andExpect(status().isForbidden()).andExpect(jsonPath("$.reason").value("NOT_ASKED"));
		this.mvc.perform(get("/api/v1/boards/" + this.job + "/cancellation").with(as(an))).andExpect(status().isOk())
			.andExpect(jsonPath("$.proposal.mayAnswer").value(false));
		answer(this.minh, proposal, true).andExpect(status().isNotFound());

		answer(this.vy, proposal, true).andExpect(status().isOk()).andExpect(jsonPath("$.proposal.status").value("CONFIRMED"));
		assertThat(text("SELECT state FROM job.job WHERE id = '" + this.job + "'")).isEqualTo("CANCELLED");
		awaitListeners();
	}

	@Test
	void atTheDateAnAgreementALeaverLeftCompleteAppliesAndSilenceTellsTheCustomer() throws Exception {
		String proposal = proposed();
		this.clock.advance(Duration.ofDays(5).plusMinutes(1));
		this.poller.runDue();
		awaitListeners();
		assertThat(text("SELECT status || ':' || (unanswered_at IS NOT NULL) FROM job.cancellation_proposal WHERE id = '"
				+ proposal + "'")).as("no answer by the date: the proposal stays open and the Customer is told").isEqualTo("OPEN:true");
		assertThat(notices("job.cancellation_unanswered", this.lan)).isOne();
		this.mvc.perform(post("/api/v1/boards/" + this.job + "/cancellation/withdraw").with(as(this.lan)).with(csrf()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.proposal.status").value("WITHDRAWN"));

		// An agreement a leaver leaves complete applies when they go, and their to-do closes with them.
		String second = proposed();
		answer(this.vy, second, true).andExpect(status().isOk()).andExpect(jsonPath("$.proposal.status").value("OPEN"));
		leave(this.minh);
		awaitListeners();

		assertThat(text("SELECT status FROM job.cancellation_proposal WHERE id = '" + second + "'")).isEqualTo("CONFIRMED");
		assertThat(text("SELECT state FROM job.job WHERE id = '" + this.job + "'")).isEqualTo("CANCELLED");
		assertThat(count("SELECT count(*) FROM notification.todo WHERE todo_key = 'job.answer_cancellation' AND closed_at IS NULL"))
			.isZero();
		assertThat(count("SELECT count(*) FROM audit.audit_entry WHERE action = 'job.cancellation_agreed_by_all'")).isOne();
	}

	/**
	 * AD-28: the Job is busy when a leaver completes the agreement, so nothing applies then; at the date the Deadline
	 * comes again while the Job is busy, never telling the Customer "unanswered", and applies once the marker is gone.
	 */
	@Test
	void aBusyJobDelaysACompleteAgreementAndAFrozenOneRefusesTheLastAnswer() throws Exception {
		String proposal = proposed();
		answer(this.vy, proposal, true).andExpect(status().isOk());
		this.jdbc.sql("UPDATE job.job SET frozen_at = now() WHERE id = :id").param("id", this.job).update();
		answer(this.minh, proposal, true).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("JOB_FROZEN"));
		assertThat(count("SELECT count(*) FROM job.proposal_answer WHERE answer IS NOT NULL")).as("the refused answer is not kept")
			.isOne();
		assertThat(openTodos("job.answer_cancellation", this.minh)).isOne();
		this.jdbc.sql("""
				UPDATE job.job SET frozen_at = NULL, busy_operation_key = 'test:busy', busy_purpose = 'ACCEPTANCE',
				    busy_subject = 'test', busy_since = now() WHERE id = :id
				""").param("id", this.job).update();

		leave(this.minh);
		awaitListeners();
		assertThat(text("SELECT state FROM job.job WHERE id = '" + this.job + "'")).isEqualTo("ACTIVE");
		assertThat(openTodos("job.answer_cancellation", this.minh)).as("the leaver is asked nothing more").isZero();
		this.clock.advance(Duration.ofDays(5).plusMinutes(1));
		this.poller.runDue();
		awaitListeners();
		assertThat(text("SELECT status || ':' || (unanswered_at IS NULL) FROM job.cancellation_proposal WHERE id = '" + proposal
				+ "'")).isEqualTo("OPEN:true");
		assertThat(notices("job.cancellation_unanswered", this.lan)).isZero();

		this.jdbc.sql("""
				UPDATE job.job SET busy_operation_key = NULL, busy_purpose = NULL, busy_subject = NULL, busy_since = NULL
				WHERE id = :id
				""").param("id", this.job).update();
		// The Deadline was armed again for the next poll.
		this.clock.advance(Duration.ofMinutes(10));
		this.poller.runDue();
		awaitListeners();
		assertThat(text("SELECT status FROM job.cancellation_proposal WHERE id = '" + proposal + "'")).isEqualTo("CONFIRMED");
		assertThat(text("SELECT state FROM job.job WHERE id = '" + this.job + "'")).isEqualTo("CANCELLED");
	}

	/** R56: a locked member no longer counts, and with nobody left to ask nothing is agreed by default. */
	@Test
	void aLockedMemberNoLongerCountsAndNobodyLeftAgreesNothing() throws Exception {
		String proposal = proposed();
		this.jdbc.sql("UPDATE identity.account SET locked_at = :at, lock_reason = 'Vi phạm quy tắc' WHERE id = :id")
			.param("at", Timestamp.from(this.clock.instant())).param("id", this.minh).update();
		this.mvc.perform(get("/api/v1/boards/" + this.job + "/cancellation").with(as(this.lan))).andExpect(status().isOk())
			.andExpect(jsonPath("$.proposal.asked").value(1));

		leave(this.vy);
		awaitListeners();
		this.mvc.perform(get("/api/v1/boards/" + this.job + "/cancellation").with(as(this.lan))).andExpect(status().isOk())
			.andExpect(jsonPath("$.proposal.status").value("OPEN"))
			.andExpect(jsonPath("$.proposal.asked").value(0))
			.andExpect(jsonPath("$.noMember").value("CREW_WAS_ACTIVE"));
		this.clock.advance(Duration.ofDays(5).plusMinutes(1));
		this.poller.runDue();
		awaitListeners();

		assertThat(text("SELECT status || ':' || (unanswered_at IS NOT NULL) FROM job.cancellation_proposal WHERE id = '"
				+ proposal + "'")).isEqualTo("OPEN:true");
		assertThat(text("SELECT state FROM job.job WHERE id = '" + this.job + "'")).isEqualTo("ACTIVE");
		assertThat(notices("job.cancellation_unanswered", this.lan)).isOne();
	}

	/**
	 * The members were asked about a Held Milestone: once it is settled or accepted their answers are about another
	 * state of the money, so the proposal ends and nothing they said applies.
	 */
	@Test
	void theProposalEndsWhenItsMilestoneStopsBeingHeld() throws Exception {
		String proposal = proposed();
		answer(this.vy, proposal, true).andExpect(status().isOk());

		this.settlements.settle(this.job, this.world.milestone(), 0, HeldCrewJob.PRICE);
		awaitListeners();

		assertThat(text("SELECT status FROM job.cancellation_proposal WHERE id = '" + proposal + "'")).isEqualTo("WITHDRAWN");
		assertThat(count("SELECT count(*) FROM notification.todo WHERE todo_key = 'job.answer_cancellation' AND closed_at IS NULL"))
			.isZero();
		assertThat(count("SELECT count(*) FROM audit.audit_entry WHERE action = 'job.cancellation_moot'")).isOne();
		answer(this.minh, proposal, true).andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("NO_CANCELLATION_OPEN"));
		assertThat(text("SELECT state FROM job.job WHERE id = '" + this.job + "'")).isEqualTo("ACTIVE");
	}

	/** B-06: with no Held Milestone the Customer's word is enough, and the members are told by the Job's own notice. */
	@Test
	void withNoHeldMilestoneTheCustomerCancelsAtOnce() throws Exception {
		this.jdbc.sql("UPDATE job.milestone SET state = 'PLANNED', funded_at = NULL WHERE id = :id")
			.param("id", this.world.milestone()).update();

		this.mvc.perform(get("/api/v1/boards/" + this.job + "/cancellation").with(as(this.lan))).andExpect(status().isOk())
			.andExpect(jsonPath("$.held").value(false))
			.andExpect(jsonPath("$.canPropose").value(true));
		cancel().andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("CANCELLED"))
			.andExpect(jsonPath("$.proposal.status").value("CONFIRMED"))
			.andExpect(jsonPath("$.proposal.asked").doesNotExist());
		awaitListeners();

		assertThat(text("SELECT status || ':' || alone FROM job.cancellation_proposal WHERE job_id = '" + this.job + "'"))
			.isEqualTo("CONFIRMED:true");
		assertThat(count("SELECT count(*) FROM job.proposal_answer")).as("no row awaits an answer").isZero();
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type = 'job.cancellation_awaiting_members'")).isZero();
		assertThat(notices("job.job_cancelled", this.vy)).isOne();
		assertThat(notices("job.job_cancelled", this.minh)).isOne();
	}

	/** B-06: the closure command applies J8 at once, with nobody to ask. */
	@Test
	void withNoHeldMilestoneTheCustomerClosesAtOnce() throws Exception {
		accepted();

		this.mvc.perform(post("/api/v1/boards/" + this.job + "/closure").with(as(this.lan)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + version() + "}")).andExpect(status().isCreated())
			.andExpect(jsonPath("$.proposal.status").value("CONFIRMED"));
		awaitListeners();

		assertThat(text("SELECT state FROM job.job WHERE id = '" + this.job + "'")).isIn("CLOSING", "CLOSED");
		assertThat(text("SELECT status || ':' || (customer_confirmed_at IS NOT NULL) || ':' || (mentor_confirmed_at IS NOT NULL)"
				+ " FROM job.closure_proposal WHERE job_id = '" + this.job + "'")).isEqualTo("CONFIRMED:true:true");
		assertThat(count("SELECT count(*) FROM audit.audit_entry WHERE action = 'job.closure_alone'")).isOne();
		assertThat(count("SELECT count(*) FROM audit.audit_entry WHERE action = 'job.closure_proposed'")).isZero();
		assertThat(count("SELECT count(*) FROM notification.todo WHERE todo_key = 'job.answer_closure'")).isZero();
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type = 'job.closure_proposed'")).isZero();
		assertThat(notices("job.job_closing", this.vy)).isOne();
	}

	/** B-06: the System's proposal after the last acceptance asks only the Customer, whose one answer decides it. */
	@Test
	void theSystemsClosureProposalAsksOnlyTheCustomer() throws Exception {
		accepted();
		automaticProposal();
		awaitListeners();

		assertThat(notices("job.closure_proposed", this.lan)).isOne();
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type = 'job.closure_proposed'")).isOne();
		assertThat(count("SELECT count(*) FROM notification.todo WHERE todo_key = 'job.answer_closure'")).as("opened once").isOne();
		assertThat(text("SELECT role FROM notification.todo WHERE todo_key = 'job.answer_closure'")).isEqualTo("CUSTOMER_MENTOR");

		// A decline withdraws it, and tells nobody: there is no other side.
		this.mvc.perform(post("/api/v1/boards/" + this.job + "/closure/decline").with(as(this.lan)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + version() + "}")).andExpect(status().isOk())
			.andExpect(jsonPath("$.proposal").doesNotExist());
		awaitListeners();
		assertThat(text("SELECT status FROM job.closure_proposal WHERE job_id = '" + this.job + "'")).isEqualTo("DECLINED");
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type = 'job.closure_declined'")).isZero();
		assertThat(text("SELECT state FROM job.job WHERE id = '" + this.job + "'")).isEqualTo("ACTIVE");

		// One confirmation by the one person confirms the next proposal.
		automaticProposal();
		this.mvc.perform(post("/api/v1/boards/" + this.job + "/closure/confirm").with(as(this.lan)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + version() + "}")).andExpect(status().isOk());
		awaitListeners();
		assertThat(text("SELECT state FROM job.job WHERE id = '" + this.job + "'")).isIn("CLOSING", "CLOSED");
		assertThat(count("SELECT count(*) FROM job.closure_proposal WHERE job_id = '" + this.job + "' AND status = 'CONFIRMED'"))
			.isOne();
	}

	/** A normal Job keeps its two sides: the Mentor confirms, and no member answers anything. */
	@Test
	void aNormalJobKeepsItsConfirmationAndHasNoMembersAnswer() throws Exception {
		HeldCrewJob normal = HeldCrewJob.create(this.jdbc, this.clock.instant());
		long version = count("SELECT version FROM job.job WHERE id = '" + normal.job() + "'");
		String proposal = JsonPath.read(this.mvc
			.perform(post("/api/v1/boards/" + normal.job() + "/cancellation").with(as(normal.lan())).with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"scope\":\"JOB\",\"reason\":\"Sản phẩm đổi hướng\",\"version\":" + version + "}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.selfMentored").value(false))
			.andExpect(jsonPath("$.proposal.status").value("OPEN"))
			.andExpect(jsonPath("$.proposal.asked").doesNotExist())
			.andReturn().getResponse().getContentAsString(), "$.proposal.id");
		awaitListeners();

		assertThat(notices("job.cancellation_proposed", normal.hung())).isOne();
		assertThat(count("SELECT count(*) FROM job.proposal_answer")).isZero();
		this.mvc.perform(post("/api/v1/boards/" + normal.job() + "/cancellation/answer").with(as(normal.vy())).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content("{\"proposalId\":\"" + proposal + "\",\"agree\":true}"))
			.andExpect(status().isNotFound());
		this.mvc.perform(post("/api/v1/boards/" + normal.job() + "/cancellation/confirm").with(as(normal.hung())).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + version + "}")).andExpect(status().isOk())
			.andExpect(jsonPath("$.proposal.status").value("CONFIRMED"));
		awaitListeners();
	}

	private void selfMentored(UUID jobId) {
		this.jdbc.sql("""
				UPDATE job.job SET self_mentored = TRUE, self_mentored_notice_version = '1.0',
				    mentor_account_id = customer_account_id, selected_application_id = NULL WHERE id = :id
				""").param("id", jobId).update();
	}

	/** The Held Milestone as accepted work, so nothing is held and the Job can close. */
	private void accepted() {
		this.jdbc.sql("""
				UPDATE job.milestone SET state = 'ACCEPTED', submitted_at = :at, handover_id = :handover, accepted_at = :at
				WHERE id = :id
				""").param("at", Timestamp.from(this.clock.instant())).param("handover", UUID.randomUUID())
			.param("id", this.world.milestone()).update();
	}

	private void automaticProposal() {
		this.transactions.executeWithoutResult(status -> this.closureProposals
			.proposeAutomatically(this.jobs.lock(this.job).orElseThrow(), UUID.randomUUID()));
	}

	/** The Customer's cancellation proposal, open and waiting for the members. */
	private String proposed() throws Exception {
		return JsonPath.read(cancel().andExpect(status().isOk()).andExpect(jsonPath("$.proposal.status").value("OPEN"))
			.andReturn().getResponse().getContentAsString(), "$.proposal.id");
	}

	private ResultActions cancel() throws Exception {
		return this.mvc.perform(post("/api/v1/boards/" + this.job + "/cancellation").with(as(this.lan)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"scope\":\"JOB\",\"reason\":\"Sản phẩm đổi hướng\",\"version\":" + version() + "}"));
	}

	private ResultActions answer(UUID member, String proposal, boolean agree) throws Exception {
		return this.mvc.perform(post("/api/v1/boards/" + this.job + "/cancellation/answer").with(as(member)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"proposalId\":\"" + proposal + "\",\"agree\":" + agree + "}"));
	}

	private void leave(UUID member) throws Exception {
		this.mvc.perform(post("/api/v1/jobs/" + this.job + "/crew/leave").with(as(member)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNoContent());
	}

	private long version() {
		return count("SELECT version FROM job.job WHERE id = '" + this.job + "'");
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
