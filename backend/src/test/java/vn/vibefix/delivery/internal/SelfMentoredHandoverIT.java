package vn.vibefix.delivery.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
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
import vn.vibefix.job.CrewMemberAdded;
import vn.vibefix.job.MilestoneHandovers;
import vn.vibefix.shared.ApiException;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.TestPeople;
import vn.vibefix.timers.internal.DeadlinePoller;

/**
 * Story 25.5 (FR-32, FR-86, AD-4 M3, AD-28, AD-47 exceptions 1, 2 and 12, R50, R65, R66): in a Self-mentored Job an
 * active Crew member hands the Milestone over and the Customer, who is the Mentor, cannot; the Customer may return the
 * handover with a reason, which voids it without a Revision Round, at most the Job's copied limit of times; the members
 * are the ones told and asked to hand over again; and a normal Job has none of it.
 */
class SelfMentoredHandoverIT extends IntegrationTest {

	private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

	private static final long PRICE = 1_000_001;

	private static final String REASON = "Báo cáo còn thiếu mục 7, nhóm bấm bàn giao sớm";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private TransactionTemplate transactions;

	@Autowired
	private MilestoneHandovers handovers;

	@Autowired
	private DeadlinePoller poller;

	@Autowired
	private ApplicationEventPublisher events;

	@Autowired
	private DeliveryPersonalData personalData;

	private UUID lan;

	private UUID hung;

	private UUID vy;

	private UUID minh;

	private UUID job;

	private UUID product;

	private UUID milestone;

	private UUID criterion;

	@Test
	void aMemberHandsOverAndTheCustomerWhoMentorsCannot() throws Exception {
		world(true, 2, null);

		handOver(this.lan, 5).andExpect(status().isForbidden()).andExpect(jsonPath("$.reason").value("TEAM_ONLY"));
		this.mvc.perform(get("/api/v1/boards/" + this.job + "/handovers").with(as(this.lan))).andExpect(status().isOk())
			.andExpect(jsonPath("$.audience").value("CUSTOMER"))
			.andExpect(jsonPath("$.canHandOver").value(false))
			.andExpect(jsonPath("$.selfMentored").value(true))
			.andExpect(jsonPath("$.waiting").doesNotExist());
		this.mvc.perform(get("/api/v1/boards/" + this.job + "/handovers").with(as(this.vy))).andExpect(status().isOk())
			.andExpect(jsonPath("$.audience").value("CREW"))
			.andExpect(jsonPath("$.canHandOver").value(true))
			.andExpect(jsonPath("$.waiting.milestoneId").value(this.milestone.toString()));
		this.mvc.perform(get("/api/v1/boards/" + this.job + "/handovers/preflight").with(as(this.vy))).andExpect(status().isOk())
			.andExpect(jsonPath("$.assessment").value(true));

		String handover = handedOver(this.vy, 5);

		assertThat(text("SELECT state FROM job.milestone WHERE id = '" + this.milestone + "'")).isEqualTo("SUBMITTED");
		assertThat(this.jdbc.sql("SELECT mentor_account_id FROM delivery.handover WHERE id = :id")
			.param("id", UUID.fromString(handover)).query(UUID.class).single()).as("the member who handed over").isEqualTo(this.vy);
		assertThat(param("job.milestone_submitted", this.lan, "who")).isEqualTo("Thành viên Trần Thảo Vy");
		assertThat(notices("job.milestone_submitted", this.vy)).isZero();
		assertThat(openTodos("delivery.review_handover", this.lan)).isOne();
		assertThat(text("SELECT role FROM notification.todo WHERE todo_key = 'delivery.review_handover'"))
			.isEqualTo("CUSTOMER_MENTOR");
		// One current handover at a time: the Milestone is no longer waiting for another member's.
		handOver(this.minh, 6).andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("MILESTONE_NOT_HELD"));
		for (UUID reader : List.of(this.lan, this.minh, this.vy)) {
			this.mvc.perform(get("/api/v1/handovers/" + handover).with(as(reader))).andExpect(status().isOk())
				.andExpect(jsonPath("$.mentorName").value("Trần Thảo Vy"))
				.andExpect(jsonPath("$.byMember").value(true))
				.andExpect(jsonPath("$.returned").doesNotExist());
		}
		this.mvc.perform(get("/api/v1/handovers/" + handover).with(as(this.lan)))
			.andExpect(jsonPath("$.audience").value("CUSTOMER"))
			.andExpect(jsonPath("$.decision.returnLimit").value(2))
			.andExpect(jsonPath("$.decision.returnsLeft").value(2));
		this.mvc.perform(get("/api/v1/handovers/" + handover).with(as(this.vy)))
			.andExpect(jsonPath("$.decision").doesNotExist());
	}

	/** M2 itself refuses the Customer, who is the Job's Mentor: until Story 25.5 it let the Mentor's account through. */
	@Test
	void m2RefusesTheCustomerAndAnyoneWhoIsNotAnActiveMember() {
		world(true, 2, null);
		for (UUID actor : List.of(this.lan, this.hung)) {
			assertThatThrownBy(() -> this.transactions.executeWithoutResult(
					status -> this.handovers.submit(this.job, this.milestone, actor, 5, UUID.randomUUID())))
				.isInstanceOfSatisfying(ApiException.class,
						refused -> assertThat(refused.reason()).contains("MEMBER_ONLY"));
		}
		assertThat(text("SELECT state FROM job.milestone WHERE id = '" + this.milestone + "'")).isEqualTo("FUNDED");
	}

	@Test
	void theCustomerReturnsAHandoverWithAReasonAndNoRevisionRoundIsUsed() throws Exception {
		world(true, 2, null);
		LocalDate today = LocalDate.ofInstant(this.clock.instant(), VIETNAM);
		String handover = handedOver(this.vy, 5);

		returnIt(this.vy, handover, 6, REASON).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.reason").value("CUSTOMER_ONLY"));
		returnIt(this.lan, handover, 6, "   ").andExpect(status().isBadRequest());
		returnIt(this.lan, handover, 5, REASON).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE"));
		assertThat(text("SELECT status FROM delivery.handover WHERE id = '" + handover + "'")).isEqualTo("SUBMITTED");

		returnIt(this.lan, handover, 6, "  " + REASON + "  ").andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("VOIDED"))
			.andExpect(jsonPath("$.returned.reason").value(REASON))
			.andExpect(jsonPath("$.returned.at").exists())
			.andExpect(jsonPath("$.decision").doesNotExist());
		awaitListeners();

		assertThat(text("""
				SELECT state || ':' || revision_rounds_used || ':' || handover_returns_used || ':' || due_on FROM job.milestone
				WHERE id = '%s'
				""".formatted(this.milestone))).as("back where it was handed over from, a return counted, the rework due date")
			.isEqualTo("FUNDED:0:1:" + today.plusDays(5));
		assertThat(this.jdbc.sql("SELECT returned_by_account_id FROM delivery.handover WHERE id = :id")
			.param("id", UUID.fromString(handover)).query(UUID.class).single()).isEqualTo(this.lan);
		// The members are told why; the Customer returned it and is told nothing; no Administrator is alerted.
		assertThat(param("job.handover_returned", this.vy, "reason")).isEqualTo(REASON);
		assertThat(notices("job.handover_returned", this.minh)).isOne();
		assertThat(notices("job.handover_returned", this.lan)).isZero();
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type IN ('job.handover_voided', "
				+ "'repo.acceptance_head_moved')")).isZero();
		assertThat(notices("job.milestone_rework_due", this.lan)).as("the Customer once, though also the Mentor").isOne();
		assertThat(notices("job.milestone_rework_due", this.vy)).isOne();
		assertThat(count("SELECT count(*) FROM audit.audit_entry WHERE action = 'job.milestone_due_moved.return'")).isOne();
		assertThat(count("SELECT count(*) FROM audit.audit_entry WHERE action = 'delivery.handover_returned'")).isOne();
		// The to-dos move from the Customer back to the members, each of whom may hand over again.
		assertThat(openTodos("delivery.review_handover", this.lan)).isZero();
		assertThat(openTodos("delivery.hand_over", this.vy)).isOne();
		assertThat(openTodos("delivery.hand_over", this.minh)).isOne();
		assertThat(openTodos("delivery.hand_over", this.lan)).as("the Customer hands nothing over").isZero();
		// A second decision on the returned handover finds it void, and every reader sees why.
		returnIt(this.lan, handover, 7, REASON).andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("HANDOVER_RETURNED"));
		decide(this.lan, handover, "accept", 7, true).andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("HANDOVER_RETURNED"));
		this.mvc.perform(get("/api/v1/handovers/" + handover).with(as(this.minh))).andExpect(status().isOk())
			.andExpect(jsonPath("$.returned.reason").value(REASON));
		assertThat(this.personalData.export(this.lan).orElseThrow().data().toString()).contains(REASON);
		// NFR-10: the reason is what the handover said, so retirement clears it with the notes; "returned" stays.
		this.transactions.executeWithoutResult(status -> this.personalData.retireJobData(this.product));
		assertThat(text("SELECT returned || ':' || (return_reason IS NULL) FROM delivery.handover WHERE id = '" + handover + "'"))
			.isEqualTo("true:true");
		this.mvc.perform(get("/api/v1/handovers/" + handover).with(as(this.minh))).andExpect(status().isOk())
			.andExpect(jsonPath("$.returned.at").exists())
			.andExpect(jsonPath("$.returned.reason").doesNotExist());
		assertThat(this.personalData.export(this.lan).orElseThrow().data().toString()).doesNotContain(REASON);
	}

	/** The other member hands over next, which closes the to-do of both; the count of returns left follows. */
	@Test
	void afterAReturnAnotherMemberHandsOverAndAutoAcceptanceNeverTakesTheReturnedOne() throws Exception {
		world(true, 2, null);
		String handover = handedOver(this.vy, 5);
		returnIt(this.lan, handover, 6, REASON).andExpect(status().isOk());
		awaitListeners();

		// FR-35, AD-4: Auto-acceptance is cancelled with the handover, so its Deadline finds nothing to accept.
		this.clock.advance(Duration.ofDays(7).plusMinutes(1));
		this.poller.runDue();
		assertThat(text("SELECT status FROM delivery.handover WHERE id = '" + handover + "'")).isEqualTo("VOIDED");
		assertThat(text("SELECT state FROM job.milestone WHERE id = '" + this.milestone + "'")).isEqualTo("FUNDED");
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type = 'job.milestone_accepted'")).isZero();

		String second = handedOver(this.minh, 7);
		assertThat(openTodos("delivery.hand_over", this.vy)).isZero();
		assertThat(openTodos("delivery.hand_over", this.minh)).isZero();
		this.mvc.perform(get("/api/v1/handovers/" + second).with(as(this.lan)))
			.andExpect(jsonPath("$.round").value(2))
			.andExpect(jsonPath("$.mentorName").value("Lê Quang Minh"))
			.andExpect(jsonPath("$.decision.returnsLeft").value(1));
	}

	/** R65, FR-34: with the returns used up the command is refused, and a revision request still follows its own rule. */
	@Test
	void theLimitOfReturnsIsTheJobsCopyAndARevisionStillFollowsItsRounds() throws Exception {
		world(true, 1, 1);
		// AD-7, R50: the Job runs by the limit copied when it was published, whatever the setting is today.
		this.jdbc.sql("UPDATE settings.rule_value SET value = 5 WHERE rule_key = 'HANDOVER_RETURN_LIMIT'").update();
		String first = handedOver(this.vy, 5);
		this.mvc.perform(get("/api/v1/handovers/" + first).with(as(this.lan)))
			.andExpect(jsonPath("$.decision.returnLimit").value(1))
			.andExpect(jsonPath("$.decision.returnsLeft").value(1));
		returnIt(this.lan, first, 6, REASON).andExpect(status().isOk());

		String second = handedOver(this.vy, 7);
		this.mvc.perform(get("/api/v1/handovers/" + second).with(as(this.lan)))
			.andExpect(jsonPath("$.decision.returnLimit").value(1))
			.andExpect(jsonPath("$.decision.returnsLeft").value(0))
			.andExpect(jsonPath("$.decision.revisionAllowed").value(true));
		returnIt(this.lan, second, 8, REASON).andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("HANDOVER_RETURNS_USED"));
		assertThat(text("SELECT status FROM delivery.handover WHERE id = '" + second + "'")).isEqualTo("SUBMITTED");

		// AD-47 exception 12 (b): the members are told of the revision; the Customer, who asked, is not.
		decide(this.lan, second, "revision", 8, false).andExpect(status().isOk());
		assertThat(notices("job.revision_requested", this.vy)).isOne();
		assertThat(notices("job.revision_requested", this.minh)).isOne();
		assertThat(notices("job.revision_requested", this.lan)).isZero();
		awaitListeners();
		assertThat(openTodos("delivery.hand_over", this.vy)).isOne();
		assertThat(openTodos("delivery.hand_over", this.lan)).isZero();

		String third = handedOver(this.minh, 9);
		decide(this.lan, third, "accept", 10, true).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED"));
		assertThat(notices("job.milestone_accepted", this.lan)).as("the Customer once").isOne();
		assertThat(notices("job.milestone_accepted", this.vy)).isOne();
		assertThat(notices("job.milestone_accepted", this.minh)).isOne();
	}

	/** M3 leads back to the state the handover left: Revision Requested once a Revision Round was used. */
	@Test
	void aReturnAfterARevisionLeadsBackToRevisionRequested() throws Exception {
		world(true, 2, null);
		String first = handedOver(this.vy, 5);
		decide(this.lan, first, "revision", 6, false).andExpect(status().isOk());
		String second = handedOver(this.vy, 7);

		returnIt(this.lan, second, 8, REASON).andExpect(status().isOk());

		assertThat(text("SELECT state || ':' || revision_rounds_used || ':' || handover_returns_used FROM job.milestone WHERE id = '"
				+ this.milestone + "'")).isEqualTo("REVISION_REQUESTED:1:1");
	}

	/** R50: a limit of 0 offers no return; AD-28: a busy Job refuses one and stores nothing; a frozen Job refuses too. */
	@Test
	void aLimitOfZeroABusyJobAndAFrozenJobRefuseTheReturn() throws Exception {
		world(true, 2, 0);
		String handover = handedOver(this.vy, 5);
		this.mvc.perform(get("/api/v1/handovers/" + handover).with(as(this.lan)))
			.andExpect(jsonPath("$.decision.revisionAllowed").value(true))
			.andExpect(jsonPath("$.decision.returnLimit").doesNotExist())
			.andExpect(jsonPath("$.decision.returnsLeft").doesNotExist());
		returnIt(this.lan, handover, 6, REASON).andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("HANDOVER_RETURNS_USED"));

		this.jdbc.sql("UPDATE job.job SET rules = rules || '{\"HANDOVER_RETURN_LIMIT\":2}'::jsonb WHERE id = :id")
			.param("id", this.job).update();
		this.jdbc.sql("""
				UPDATE job.job SET busy_operation_key = 'test:busy', busy_purpose = 'ACCEPTANCE', busy_subject = 'test',
				    busy_since = now() WHERE id = :id
				""").param("id", this.job).update();
		returnIt(this.lan, handover, 6, REASON).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("JOB_BUSY"));
		this.jdbc.sql("""
				UPDATE job.job SET busy_operation_key = NULL, busy_purpose = NULL, busy_subject = NULL, busy_since = NULL,
				    frozen_at = now() WHERE id = :id
				""").param("id", this.job).update();
		returnIt(this.lan, handover, 6, REASON).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("JOB_FROZEN"));

		assertThat(text("SELECT status FROM delivery.handover WHERE id = '" + handover + "'")).isEqualTo("SUBMITTED");
		assertThat(text("SELECT state || ':' || handover_returns_used FROM job.milestone WHERE id = '" + this.milestone + "'"))
			.isEqualTo("SUBMITTED:0");
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type = 'job.handover_returned'")).isZero();
	}

	/** FR-86: a normal Job has no return, and its Crew member still cannot hand over. */
	@Test
	void aNormalJobHasNoReturnAndItsMemberDoesNotHandOver() throws Exception {
		world(false, 2, null);

		handOver(this.vy, 5).andExpect(status().isForbidden()).andExpect(jsonPath("$.reason").value("MENTOR_ONLY"));
		this.mvc.perform(get("/api/v1/boards/" + this.job + "/handovers").with(as(this.vy))).andExpect(status().isOk())
			.andExpect(jsonPath("$.canHandOver").value(false))
			.andExpect(jsonPath("$.selfMentored").value(false));
		String handover = handedOver(this.hung, 5);
		assertThat(param("job.milestone_submitted", this.lan, "who")).isEqualTo("Người hướng dẫn Đỗ Mạnh Hùng");
		this.mvc.perform(get("/api/v1/handovers/" + handover).with(as(this.lan))).andExpect(status().isOk())
			.andExpect(jsonPath("$.mentorName").value("Đỗ Mạnh Hùng"))
			.andExpect(jsonPath("$.byMember").value(false))
			.andExpect(jsonPath("$.decision.revisionAllowed").value(true))
			.andExpect(jsonPath("$.decision.returnLimit").doesNotExist());

		for (UUID caller : List.of(this.lan, this.hung, this.vy)) {
			returnIt(caller, handover, 6, REASON).andExpect(status().isNotFound());
		}
		assertThat(text("SELECT status FROM delivery.handover WHERE id = '" + handover + "'")).isEqualTo("SUBMITTED");
		decide(this.lan, handover, "revision", 6, false).andExpect(status().isOk());
		assertThat(notices("job.revision_requested", this.hung)).as("the Mentor, as before").isOne();
		assertThat(notices("job.revision_requested", this.vy)).isZero();
	}

	/**
	 * AD-47 exception 1 (Story 25.4's deferred check): a Submitted handover stays decidable after its member, and every
	 * member, left; a decision with nobody left to tell still applies, and Auto-acceptance still runs.
	 */
	@Test
	void aSubmittedHandoverStaysDecidableAfterEveryMemberLeft() throws Exception {
		world(true, 2, null);
		String first = handedOver(this.vy, 5);
		leave(this.vy);
		leave(this.minh);
		awaitListeners();
		long version = count("SELECT version FROM job.job WHERE id = '" + this.job + "'");

		returnIt(this.lan, first, version, REASON).andExpect(status().isOk());
		awaitListeners();
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type = 'job.handover_returned'"))
			.as("nobody is left to tell").isZero();
		assertThat(count("SELECT count(*) FROM notification.todo WHERE todo_key = 'delivery.hand_over' AND closed_at IS NULL"))
			.as("nobody is left to hand over").isZero();
		handOver(this.vy, version + 1).andExpect(status().isNotFound());
	}

	@Test
	void autoAcceptanceStillRunsAfterTheMemberWhoHandedOverLeft() throws Exception {
		world(true, 2, null);
		String handover = handedOver(this.vy, 5);
		leave(this.vy);
		awaitListeners();

		this.clock.advance(Duration.ofDays(7).plusMinutes(1));
		this.poller.runDue();

		assertThat(text("SELECT status || ':' || auto FROM delivery.handover WHERE id = '" + handover + "'"))
			.isEqualTo("ACCEPTED:true");
		assertThat(notices("job.milestone_accepted", this.lan)).isOne();
		assertThat(notices("job.milestone_accepted", this.minh)).as("the member still active").isOne();
		assertThat(notices("job.milestone_accepted", this.vy)).as("the one who left").isZero();
	}

	/** The handover to-do follows the Crew: a member who joins while a Milestone waits gets it, and a leaver loses it. */
	@Test
	void theHandoverToDoFollowsTheCrew() throws Exception {
		world(true, 2, null);
		String handover = handedOver(this.vy, 5);
		returnIt(this.lan, handover, 6, REASON).andExpect(status().isOk());
		awaitListeners();
		assertThat(openTodos("delivery.hand_over", this.minh)).isOne();

		leave(this.minh);
		awaitListeners();
		assertThat(openTodos("delivery.hand_over", this.minh)).as("the one who left").isZero();
		assertThat(openTodos("delivery.hand_over", this.vy)).isOne();

		UUID an = TestPeople.student(this.jdbc, "Phạm Hoài An", true, List.of(TestPeople.REACT), "React",
				LocalDate.parse("2026-09-30"));
		UUID row = member(an);
		this.transactions.executeWithoutResult(
				status -> this.events.publishEvent(new CrewMemberAdded(UUID.randomUUID(), this.job, row, an, "ACTIVE")));
		awaitListeners();
		assertThat(openTodos("delivery.hand_over", an)).as("the one who joined while the Milestone waits").isOne();
		assertThat(text("SELECT DISTINCT role FROM notification.todo WHERE todo_key = 'delivery.hand_over'")).isEqualTo("CREW");
	}

	/** R66: a handover of a Self-mentored Job voided by the System is told to the Customer and the active members. */
	@Test
	void aVoidByTheSystemIsToldToTheCustomerAndTheMembers() throws Exception {
		world(true, 2, null);
		String handover = handedOver(this.vy, 5);

		Boolean applied = this.transactions
			.execute(status -> this.handovers.voidHandover(this.job, this.milestone, UUID.fromString(handover)));
		awaitListeners();

		assertThat(applied).isTrue();
		assertThat(notices("job.handover_voided", this.lan)).as("the Customer once").isOne();
		assertThat(notices("job.handover_voided", this.vy)).isOne();
		assertThat(notices("job.handover_voided", this.minh)).isOne();
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type = 'job.handover_returned'")).isZero();
		assertThat(text("SELECT status || ':' || (return_reason IS NULL) FROM delivery.handover WHERE id = '" + handover + "'"))
			.isEqualTo("VOIDED:true");
		assertThat(count("SELECT handover_returns_used FROM job.milestone WHERE id = '" + this.milestone + "'"))
			.as("a void by the System is no return").isZero();
		this.mvc.perform(get("/api/v1/handovers/" + handover).with(as(this.lan))).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("VOIDED"))
			.andExpect(jsonPath("$.returned").doesNotExist());
	}

	/**
	 * An Active Assessment Job with Milestone 1 Funded at {@code PRICE} and due today, Milestone 2 Planned, and Vy and
	 * Minh on its Crew since before the funding. Self-mentored, Lan is its Mentor too; else Hùng is.
	 * @param returnLimit the Job's copied {@code HANDOVER_RETURN_LIMIT}; none for a Job published before the rule
	 */
	private void world(boolean selfMentored, int revisionRounds, Integer returnLimit) {
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		this.hung = TestPeople.mentor(this.jdbc, "Đỗ Mạnh Hùng", List.of(TestPeople.REACT), "React");
		this.vy = TestPeople.student(this.jdbc, "Trần Thảo Vy", true, List.of(TestPeople.REACT), "React",
				LocalDate.parse("2026-09-30"));
		this.minh = TestPeople.student(this.jdbc, "Lê Quang Minh", true, List.of(TestPeople.REACT), "React",
				LocalDate.parse("2026-09-30"));
		UUID product = UUID.randomUUID();
		this.product = product;
		this.jdbc.sql("""
				INSERT INTO product.product (id, customer_account_id, name, description, ai_tools, repository_github_id,
				    repository_full_name, repository_owner_type, repository_default_branch, public_notice_version,
				    copy_permission_version, detection_notice_version, acknowledged_at, created_at)
				VALUES (:id, :owner, 'Báo cáo đặt bàn', 'Đặt bàn.', ARRAY['LOVABLE'], :github, 'lan/so-dat-ban', 'User', 'main',
				    '1.0', '1.0', '1.0', now(), now())
				""").param("id", product).param("owner", this.lan)
			.param("github", ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE)).update();
		this.job = UUID.randomUUID();
		this.milestone = UUID.randomUUID();
		UUID next = UUID.randomUUID();
		this.criterion = UUID.randomUUID();
		Timestamp now = Timestamp.from(this.clock.instant());
		LocalDate today = LocalDate.ofInstant(this.clock.instant(), VIETNAM);
		String rules = returnLimit == null ? "{\"AUTO_ACCEPTANCE_DAYS\":7}"
				: "{\"AUTO_ACCEPTANCE_DAYS\":7,\"HANDOVER_RETURN_LIMIT\":" + returnLimit + "}";
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, budget, deadline, rules, selection_days, opened_at, lapses_at, mentor_account_id,
				    selected_application_id, selected_at, version, created_at, updated_at)
				VALUES (:id, :product, :owner, 'ACTIVE', 'Đánh giá sổ đặt bàn', 'Lỗi', 'Đúng', 'Khách', ARRAY['ASSESSMENT'],
				    'UNDER_2M', DATE '2026-12-01', CAST(:rules AS JSONB), 14, :now, :now, :mentor, :application, :now, 5, :now, :now)
				""").param("id", this.job).param("product", product).param("owner", this.lan).param("mentor", this.hung)
			.param("rules", rules).param("application", UUID.randomUUID()).param("now", now).update();
		if (selfMentored) {
			this.jdbc.sql("""
					UPDATE job.job SET self_mentored = TRUE, self_mentored_notice_version = '1.0',
					    mentor_account_id = customer_account_id, selected_application_id = NULL WHERE id = :id
					""").param("id", this.job).update();
		}
		UUID mentor = selfMentored ? this.lan : this.hung;
		String content = """
				{"scope":"Đánh giá","priceVnd":1500001,"codeJob":false,"assessment":true,"skills":[],"externalAccess":[],
				 "milestones":[{"id":"%s","title":"Báo cáo","priceVnd":%d,"dueOn":"%s","revisionRounds":%d,
				   "criteria":[{"id":"%s","statement":"Báo cáo nêu đủ chín mục","checkSteps":"Mở báo cáo, đếm mục"}]},
				  {"id":"%s","title":"Kế hoạch sửa","priceVnd":500000,"dueOn":"%s","revisionRounds":1,"criteria":[]}]}
				""".formatted(this.milestone, PRICE, today, revisionRounds, this.criterion, next, today.plusDays(30));
		this.jdbc.sql("""
				INSERT INTO job.agreement_version (job_id, version_no, status, content, author_account_id, created_at)
				VALUES (:job, 1, 'SHARED', CAST(:content AS JSONB), :mentor, :now)
				""").param("job", this.job).param("content", content).param("mentor", mentor).param("now", now).update();
		this.jdbc.sql("""
				INSERT INTO job.confirmed_agreement (job_id, version_no, draft_version_no, content, crew_usernames,
				    freeze_committed, customer_account_id, approved_at, mentor_account_id, confirmed_at)
				VALUES (:job, 1, 1, CAST(:content AS JSONB), '{}', false, :owner, :now, :mentor, :now)
				""").param("job", this.job).param("content", content).param("owner", this.lan).param("mentor", mentor)
			.param("now", now).update();
		this.jdbc.sql("UPDATE job.job SET agreement_version_no = 1 WHERE id = :id").param("id", this.job).update();
		member(this.vy);
		member(this.minh);
		this.jdbc.sql("""
				INSERT INTO job.milestone (id, job_id, position, title, price_vnd, revision_rounds, created_at, state, funded_at,
				    due_on)
				VALUES (:id, :job, 0, 'Báo cáo', :price, :rounds, now(), 'FUNDED', :funded, :due)
				""").param("id", this.milestone).param("job", this.job).param("price", PRICE).param("rounds", revisionRounds)
			.param("funded", Timestamp.from(this.clock.instant().minus(Duration.ofMinutes(1)))).param("due", today)
			.update();
		this.jdbc.sql("""
				INSERT INTO job.milestone (id, job_id, position, title, price_vnd, revision_rounds, created_at)
				VALUES (:id, :job, 1, 'Kế hoạch sửa', 500000, 1, now())
				""").param("id", next).param("job", this.job).update();
		for (UUID account : List.of(this.milestone, next)) {
			this.jdbc.sql("""
					INSERT INTO payment.ledger_account (milestone_id, job_id, product_id, created_at) VALUES (:id, :job, :product, now())
					""").param("id", account).param("job", this.job).param("product", product).update();
		}
		this.jdbc.sql("""
				INSERT INTO payment.ledger_entry (id, operation_key, entry_type, bucket, job_id, product_id, milestone_id, amount_vnd,
				    vnp_transaction_no, created_at)
				VALUES (:id, :key, 'FUND', 'MILESTONE', :job, :product, :milestone, :amount, :no, now())
				""").param("id", UUID.randomUUID()).param("key", "FUND:" + UUID.randomUUID()).param("job", this.job)
			.param("product", product).param("milestone", this.milestone).param("amount", PRICE)
			.param("no", String.valueOf(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE))).update();
	}

	/** An {@code ACTIVE} Crew row signed five minutes before the clock, so before the Milestone's funding. */
	private UUID member(UUID student) {
		UUID row = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO job.crew_row (id, job_id, member_account_id, status, signed_at, activated_at, created_at)
				VALUES (:id, :job, :student, 'ACTIVE', :signed, :signed, now())
				""").param("id", row).param("job", this.job).param("student", student)
			.param("signed", Timestamp.from(this.clock.instant().minus(Duration.ofMinutes(5)))).update();
		return row;
	}

	private ResultActions handOver(UUID account, long version) throws Exception {
		String json = "{\"milestoneId\":\"" + this.milestone + "\",\"version\":" + version + ",\"criteria\":[{\"criterionId\":\""
				+ this.criterion + "\",\"met\":true,\"note\":\"Đã rà đủ chín mục.\"}]"
				+ ",\"links\":[{\"kind\":\"DOCUMENT\",\"url\":\"https://docs.google.com/document/d/bao-cao\",\"label\":\"Báo cáo đánh giá\"}]}";
		return this.mvc.perform(post("/api/v1/boards/" + this.job + "/handovers").with(as(account)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(json));
	}

	private String handedOver(UUID account, long version) throws Exception {
		return JsonPath.read(handOver(account, version).andExpect(status().isAccepted()).andReturn().getResponse()
			.getContentAsString(), "$.handoverId");
	}

	private ResultActions returnIt(UUID account, String handover, long version, String reason) throws Exception {
		return this.mvc.perform(post("/api/v1/handovers/" + handover + "/return").with(as(account)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"version\":" + version + ",\"reason\":\"" + reason + "\"}"));
	}

	private ResultActions decide(UUID account, String handover, String action, long version, boolean met) throws Exception {
		return this.mvc.perform(post("/api/v1/handovers/" + handover + "/" + action).with(as(account)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"version\":" + version + ",\"checks\":[{\"criterionId\":\"" + this.criterion + "\",\"met\":" + met
					+ (met ? "" : ",\"comment\":\"Thiếu mục 7\"") + "}]}"));
	}

	private void leave(UUID member) throws Exception {
		this.mvc.perform(post("/api/v1/jobs/" + this.job + "/crew/leave").with(as(member)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}")).andExpect(status().isNoContent());
	}

	private String param(String type, UUID account, String name) {
		return this.jdbc.sql("SELECT params::text FROM notification.notification WHERE type = :type AND account_id = :account")
			.param("type", type).param("account", account).query(String.class).list().stream()
			.map(params -> (String) JsonPath.<Map<String, Object>>read(params, "$").get(name)).findFirst().orElse(null);
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
		Role role = account.equals(this.lan) ? Role.CUSTOMER : account.equals(this.hung) ? Role.MENTOR : Role.STUDENT;
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

}
