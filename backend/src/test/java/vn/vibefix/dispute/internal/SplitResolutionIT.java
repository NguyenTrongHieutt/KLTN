package vn.vibefix.dispute.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
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

import vn.vibefix.authz.JobContent;
import vn.vibefix.identity.internal.AdministratorTestSupport;
import vn.vibefix.identity.internal.AdministratorTestSupport.Administrator;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.job.MilestoneHandovers;
import vn.vibefix.payment.Ledger;
import vn.vibefix.payment.Releases;
import vn.vibefix.shared.ErasureOutcome;
import vn.vibefix.shared.PersonalData;
import vn.vibefix.support.HeldCrewJob;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.timers.internal.DeadlinePoller;

/**
 * Story 22.3 (FR-84, FR-63, AD-7, AD-19, AD-20, AD-24, AD-29, AD-40, A-08, A-09, A-17, A-18, SM-8): a Split Resolution withdrawn
 * and one decided, with the ledger showing nobody paid while it was open; who raises, states, withdraws and reads; the
 * Administrator's take, grant, evidence and decision under the step-up; the undecided flag; and that it stays independent of
 * the Job, working after the Job ends, on a bare row and under a freeze, and that nothing of it reaches the Customer.
 */
class SplitResolutionIT extends IntegrationTest {

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private TransactionTemplate transactions;

	@Autowired
	private MilestoneHandovers handovers;

	@Autowired
	private Releases releases;

	@Autowired
	private Ledger ledger;

	@Autowired
	private DeadlinePoller poller;

	@Autowired
	private PersonalData personalData;

	@Autowired
	private MockMvc mvc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private ApplicationContext context;

	private HeldCrewJob world;

	private Administrator ha;

	/** A second Administrator, without a step-up secret: only the first is enrolled through the bootstrap. */
	private UUID quan;

	private UUID splitId;

	@BeforeEach
	void aHeldMilestoneOfACrewOfThreeAndTwoAdministrators() {
		this.world = HeldCrewJob.create(this.jdbc, this.clock.instant());
		this.ha = AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		this.quan = IdentityTestSupport.insertAccount(this.jdbc, Role.ADMINISTRATOR, null);
		this.jdbc.sql("""
				INSERT INTO identity.administrator_credential (account_id, email, display_name, password_hash,
				    totp_key_version, totp_nonce, totp_ciphertext, created_at)
				VALUES (:id, 'quan.le@vibefix.test', 'Lê Quân', '$argon2id$x', 1, '\\x00'::bytea, '\\x00'::bytea, now())
				""").param("id", this.quan).update();
	}

	// ---- Raise ------------------------------------------------------------------------------------

	@Test
	void aRaiseHoldsTheWholeSplitAndTellsEveryoneButTheCustomer() throws Exception {
		accepted();

		raise(this.world.vy(), 1, "Phần của tôi quá ít").andExpect(status().isCreated())
			.andExpect(jsonPath("$.viewerMayRaise").value(false))
			.andExpect(jsonPath("$.resolutions[0].status").value("OPEN"))
			.andExpect(jsonPath("$.resolutions[0].viewerIsRaiser").value(true));

		assertThat(splitStatus()).isEqualTo("IN_RESOLUTION");
		assertThat(openTodos("job.confirm_split")).isEmpty();
		assertThat(notified("dispute.split_resolution_requested")).containsExactlyInAnyOrder(this.world.hung(), this.world.minh(),
				this.ha.accountId(), this.quan);
		assertThat(ledgerTypes()).as("the whole pool stays pooled").containsExactlyInAnyOrder("FUND", "RELEASE_POOLED");
		assertThat(this.ledger.releasedTotal(this.world.milestone())).as("what a Customer sees does not change")
			.isEqualTo(HeldCrewJob.PRICE);
		assertThat(number("SELECT version FROM job.job WHERE id = '" + this.world.job() + "'")).isEqualTo(version);
		assertThat(number("SELECT count(*) FROM audit.audit_entry WHERE action = 'dispute.split_resolution_raised'")).isOne();

		this.clock.advance(Duration.ofDays(4));
		this.poller.runDue();
		assertThat(deadlineStatuses("job.split_confirmation")).as("the confirmation Deadline ends by its own handler").containsExactly("OBSOLETE");
		assertThat(splitStatus()).isEqualTo("IN_RESOLUTION");
		assertThat(ledgerTypes()).containsExactlyInAnyOrder("FUND", "RELEASE_POOLED");
	}

	@Test
	void aRaiseIsRefusedInItsOrder() throws Exception {
		accept();
		raise(this.world.vy(), 1, "Sớm quá").andExpect(status().isNotFound());
		saved();

		raise(this.world.lan(), 1, "Tôi là khách hàng").andExpect(status().isNotFound());
		raise(stranger(), 1, "Tôi là người lạ").andExpect(status().isNotFound());
		raise(this.world.hung(), 1, "Tôi là người hướng dẫn").andExpect(status().isForbidden())
			.andExpect(jsonPath("$.reason").value("PARTY_ONLY"));
		raise(this.world.vy(), 7, "Bản cũ").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE"));
		raise(this.world.vy(), 1, "  ").andExpect(status().isBadRequest());
		assertThat(number("SELECT count(*) FROM dispute.split_resolution")).isZero();

		raise(this.world.vy(), 1, "Phần của tôi quá ít").andExpect(status().isCreated());
		raise(this.world.minh(), 1, "Phần của tôi cũng ít").andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("SPLIT_IN_RESOLUTION"));
		withdraw(this.world.vy()).andExpect(status().isOk());
		raise(this.world.vy(), 1, "Một lần nữa").andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("SPLIT_RESOLUTION_USED"));
		raise(this.world.minh(), 1, "Tôi vẫn nhờ được").andExpect(status().isCreated());
	}

	@Test
	void aRaisePastTheDueTimeIsRefusedAndTheOldVersionFinalizes() throws Exception {
		accepted();
		this.clock.advance(Duration.ofDays(3));

		raise(this.world.vy(), 1, "Trễ rồi").andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("SPLIT_FINAL"))
			.andExpect(jsonPath("$.pastDue").value(true));
		assertThat(splitStatus()).isEqualTo("PROPOSED");

		this.poller.runDue();
		assertThat(splitStatus()).isEqualTo("FINAL");
		raise(this.world.vy(), 1, "Đã chốt").andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("SPLIT_FINAL"))
			.andExpect(jsonPath("$.pastDue").doesNotExist());
		assertThat(number("SELECT count(*) FROM dispute.split_resolution")).isZero();
	}

	@Test
	void whileOpenNobodyEditsOrConfirms() throws Exception {
		accepted();
		raise(this.world.vy(), 1, "Phần của tôi quá ít").andExpect(status().isCreated());

		saveVersion(1, 25, 45, 30).andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("SPLIT_IN_RESOLUTION"));
		confirm(this.world.minh(), 1).andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("SPLIT_IN_RESOLUTION"));
		cards(this.world.hung()).andExpect(jsonPath("$[0].status").value("IN_RESOLUTION"));
	}

	// ---- Statements and the read ------------------------------------------------------------------

	@Test
	void everyPartyButTheRaiserAddsOneStatementWhichEveryPartyReads() throws Exception {
		accepted();
		raise(this.world.vy(), 1, "Phần của tôi quá ít").andExpect(status().isCreated());
		String id = onlyCase();

		read(this.world.hung()).andExpect(jsonPath("$.viewerMayRaise").value(false))
			.andExpect(jsonPath("$.resolutions[0].viewerMayState").value(true))
			.andExpect(jsonPath("$.resolutions[0].viewerIsRaiser").value(false))
			.andExpect(jsonPath("$.resolutions[0].reason").value("Phần của tôi quá ít"))
			.andExpect(jsonPath("$.resolutions[0].raiserName").value("Trần Thảo Vy"));
		statement(this.world.hung(), id, "Tôi làm phần lớn việc").andExpect(status().isCreated());
		statement(this.world.hung(), id, "Một lần nữa").andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("SPLIT_STATEMENT_USED"));
		statement(this.world.vy(), id, "Lý do của tôi đã có").andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("SPLIT_STATEMENT_USED"));
		statement(stranger(), id, "Tôi là người lạ").andExpect(status().isNotFound());
		statement(this.world.lan(), id, "Tôi là khách hàng").andExpect(status().isNotFound());
		assertThat(number("SELECT count(*) FROM audit.audit_entry WHERE action = 'dispute.split_statement_added'")).isOne();
		assertThat(number("""
				SELECT count(*) FROM notification.notification WHERE type LIKE 'dispute.split_resolution_%'
				  AND account_id = :account
				""".replace(":account", "'" + this.world.vy() + "'"))).as("no notice for a statement").isZero();

		read(this.world.minh()).andExpect(jsonPath("$.resolutions[0].statements", hasSize(1)))
			.andExpect(jsonPath("$.resolutions[0].statements[0].text").value("Tôi làm phần lớn việc"))
			.andExpect(jsonPath("$.resolutions[0].statements[0].mentor").value(true))
			.andExpect(jsonPath("$.resolutions[0].viewerMayState").value(true));
		read(this.world.hung()).andExpect(jsonPath("$.resolutions[0].viewerMayState").value(false));
		read(this.world.lan()).andExpect(status().isNotFound());
		read(stranger()).andExpect(status().isNotFound());

		withdraw(this.world.vy()).andExpect(status().isOk());
		statement(this.world.minh(), id, "Đã kết thúc").andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("SPLIT_RESOLUTION_ENDED"));
	}

	// ---- Withdrawal (SM-8) ------------------------------------------------------------------------

	@Test
	void aWithdrawalStartsAFullRoundKeepsTheConfirmationsAndNobodyWasPaid() throws Exception {
		accepted();
		confirm(this.world.minh(), 1).andExpect(status().isOk());
		this.clock.advance(Duration.ofDays(1));
		raise(this.world.vy(), 1, "Phần của tôi quá ít").andExpect(status().isCreated());
		this.clock.advance(Duration.ofDays(1));
		String id = onlyCase();
		take(this.ha.accountId(), id).andExpect(status().isOk());
		assertThat(ledgerTypes()).containsExactlyInAnyOrder("FUND", "RELEASE_POOLED");

		Instant now = this.clock.instant();
		withdraw(this.world.vy()).andExpect(status().isOk())
			.andExpect(jsonPath("$.splitStatus").value("PROPOSED"))
			.andExpect(jsonPath("$.confirmationDueAt").exists());

		assertThat(splitStatus()).isEqualTo("PROPOSED");
		assertThat(number("SELECT confirmation_round FROM job.milestone_split")).isEqualTo(2);
		assertThat(timestamp("SELECT confirmation_due_at FROM job.milestone_split")).as("a full period from now")
			.isEqualTo(now.plus(Duration.ofDays(3)));
		assertThat(number("SELECT count(*) FROM job.milestone_split_confirmation")).as("the confirmation stays").isOne();
		assertThat(number("SELECT count(*) FROM job.milestone_split_version")).as("no new version").isOne();
		assertThat(openTodos("job.confirm_split")).as("reopened for the one who has not confirmed").containsExactly(this.world.vy());
		assertThat(notified("dispute.split_resolution_withdrawn")).containsExactlyInAnyOrder(this.world.hung(), this.world.vy(),
				this.world.minh(), this.ha.accountId(), this.quan);
		assertThat(text("""
				SELECT params ->> 'next' FROM notification.notification WHERE type = 'dispute.split_resolution_withdrawn'
				ORDER BY id LIMIT 1
				""")).startsWith("Hạn xác nhận mới: ");
		assertThat(number("SELECT count(*) FROM audit.audit_entry WHERE action = 'dispute.split_resolution_withdrawn'")).isOne();
		assertThat(ledgerTypes()).as("nobody was paid").containsExactlyInAnyOrder("FUND", "RELEASE_POOLED");

		evidence(this.ha.accountId(), id, "versions").andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/boards/" + this.world.job() + "/crew-chat/messages").with(admin(this.ha.accountId())))
			.andExpect(status().isNotFound());
		take(this.quan, id).andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("SPLIT_RESOLUTION_ENDED"));

		confirm(this.world.vy(), 1).andExpect(jsonPath("$.status").value("FINAL"));
		awaitListeners();
		assertThat(number("SELECT count(*) FROM payment.ledger_entry WHERE entry_type = 'RELEASE'")).isEqualTo(3);
	}

	@Test
	void aWithdrawalThatFindsEveryoneConfirmedIsFinalAtOnce() throws Exception {
		accepted();
		raise(this.world.vy(), 1, "Phần của tôi quá ít").andExpect(status().isCreated());
		for (UUID member : List.of(this.world.vy(), this.world.minh())) {
			this.jdbc.sql("""
					INSERT INTO job.milestone_split_confirmation (split_id, version_no, party_id, confirmed_at)
					VALUES (:split, 1, :party, :now)
					""").param("split", this.splitId).param("party", party(member)).param("now", Timestamp.from(this.clock.instant())).update();
		}

		withdraw(this.world.vy()).andExpect(status().isOk())
			.andExpect(jsonPath("$.splitStatus").value("FINAL"))
			.andExpect(jsonPath("$.confirmationDueAt").doesNotExist());

		assertThat(splitStatus()).isEqualTo("FINAL");
		assertThat(text("SELECT finality FROM job.milestone_split")).isEqualTo("CONFIRMED");
		assertThat(number("SELECT final_version_no FROM job.milestone_split")).isOne();
		assertThat(notified("job.split_final")).containsExactlyInAnyOrder(this.world.hung(), this.world.vy(), this.world.minh());
		assertThat(text("""
				SELECT params ->> 'next' FROM notification.notification WHERE type = 'dispute.split_resolution_withdrawn'
				ORDER BY id LIMIT 1
				""")).isEqualTo("Mọi người đã xác nhận nên phân chia đã chốt.");
		awaitListeners();
		assertThat(number("SELECT count(*) FROM payment.ledger_entry WHERE entry_type = 'RELEASE'")).isEqualTo(3);
		assertThat(number("SELECT count(*) FROM payment.ledger_entry WHERE entry_type = 'POOL_RELEASED'")).isOne();
	}

	@Test
	void onlyTheRaiserWithdraws() throws Exception {
		accepted();
		raise(this.world.vy(), 1, "Phần của tôi quá ít").andExpect(status().isCreated());
		String id = onlyCase();
		take(this.ha.accountId(), id).andExpect(status().isOk());

		withdrawAs(as(this.world.minh()), id).andExpect(status().isForbidden());
		withdrawAs(as(this.world.hung()), id).andExpect(status().isForbidden());
		withdrawAs(as(this.world.lan()), id).andExpect(status().isNotFound());
		withdrawAs(admin(this.ha.accountId()), id).andExpect(status().isForbidden());
		withdrawAs(admin(this.quan), id).andExpect(status().isNotFound());
		assertThat(splitStatus()).isEqualTo("IN_RESOLUTION");
	}

	// ---- The queue and the take -------------------------------------------------------------------

	@Test
	void theQueueShowsAnAdministratorThatACaseExistsAndOnlyItsAssigneeItsWords() throws Exception {
		accepted();
		raise(this.world.vy(), 1, "Phần của tôi quá ít").andExpect(status().isCreated());
		String id = onlyCase();

		this.mvc.perform(get("/api/v1/admin/overview").with(admin(this.ha.accountId())))
			.andExpect(jsonPath("$.splitResolutions.count").value(1));
		this.mvc.perform(get("/api/v1/admin/split-resolutions").with(admin(this.ha.accountId())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].id").value(id))
			.andExpect(jsonPath("$[0].milestoneNumber").value(1))
			.andExpect(jsonPath("$[0].assigned").value(false))
			.andExpect(jsonPath("$[0].undecidedLong").value(false))
			.andExpect(content().string(not(containsString("quá ít"))));
		detail(this.ha.accountId(), id).andExpect(status().isOk())
			.andExpect(jsonPath("$.pooledVnd").value(HeldCrewJob.PRICE))
			.andExpect(jsonPath("$.reason").doesNotExist())
			.andExpect(jsonPath("$.raiserName").doesNotExist())
			.andExpect(jsonPath("$.parties", hasSize(0)))
			.andExpect(jsonPath("$.evidenceReadable").value(false));
		evidence(this.ha.accountId(), id, "versions").andExpect(status().isNotFound());

		take(this.ha.accountId(), id).andExpect(status().isOk())
			.andExpect(jsonPath("$.assignedToMe").value(true))
			.andExpect(content().string(not(containsString("quá ít"))));
		detail(this.ha.accountId(), id).andExpect(jsonPath("$.assignedToMe").value(true))
			.andExpect(jsonPath("$.reason").value("Phần của tôi quá ít"))
			.andExpect(jsonPath("$.raiserName").value("Trần Thảo Vy"))
			.andExpect(jsonPath("$.parties", hasSize(3)))
			.andExpect(jsonPath("$.cap").value(30))
			.andExpect(jsonPath("$.evidenceReadable").value(true));
		assertThat(number("SELECT count(*) FROM audit.audit_entry WHERE action = 'dispute.split_resolution_taken'")).isOne();

		take(this.quan, id).andExpect(status().isOk()).andExpect(jsonPath("$.assignedToMe").value(true));
		detail(this.ha.accountId(), id).andExpect(jsonPath("$.reason").doesNotExist()).andExpect(jsonPath("$.assignedToMe").value(false));
		evidence(this.ha.accountId(), id, "versions").andExpect(status().isNotFound());
		evidence(this.quan, id, "versions").andExpect(status().isOk());
		this.mvc.perform(get("/api/v1/admin/split-resolutions/" + id).with(as(this.world.hung()))).andExpect(status().isNotFound());
	}

	@Test
	void anAdministratorLinkedToAnAccountInTheCaseCannotTakeItAndAnotherCan() throws Exception {
		accepted();
		raise(this.world.vy(), 1, "Phần của tôi quá ít").andExpect(status().isCreated());
		String id = onlyCase();
		for (UUID holder : List.of(this.ha.accountId(), this.world.minh())) {
			signal(holder, "ab".repeat(32));
		}

		take(this.ha.accountId(), id).andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.code").value("GUARD_FAILED"))
			.andExpect(jsonPath("$.reason").value("CONFLICT_OF_INTEREST"))
			.andExpect(content().string(not(containsString(this.world.minh().toString()))))
			.andExpect(content().string(not(containsString("ab".repeat(32)))));

		assertThat(number("SELECT count(*) FROM audit.audit_entry WHERE action = 'dispute.take_conflict_refused'")).as("kept though refused").isOne();
		assertThat(number("SELECT count(*) FROM dispute.split_resolution WHERE assigned_admin_id IS NOT NULL")).isZero();
		assertThat(number("SELECT count(*) FROM chat.admin_read_grant")).isZero();
		take(this.quan, id).andExpect(status().isOk()).andExpect(jsonPath("$.assignedToMe").value(true));
	}

	@Test
	void anAdministratorLinkedToTheCustomerCannotTakeIt() throws Exception {
		accepted();
		raise(this.world.vy(), 1, "Phần của tôi quá ít").andExpect(status().isCreated());
		for (UUID holder : List.of(this.ha.accountId(), this.world.lan())) {
			signal(holder, "cd".repeat(32));
		}

		take(this.ha.accountId(), onlyCase()).andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("CONFLICT_OF_INTEREST"));
	}

	// ---- The grant and the evidence ---------------------------------------------------------------

	@Test
	void theGrantReadsTheCrewChatAndThePartiesWorkAndNeverTheJobChat() throws Exception {
		UUID crew = JobContent.chat(this.jdbc, this.world.job(), this.world.product(), "CREW");
		UUID jobChat = JobContent.chat(this.jdbc, this.world.job(), this.world.product(), "JOB");
		Instant funded = this.clock.instant().minus(Duration.ofDays(1));
		JobContent.message(this.jdbc, crew, this.world.vy(), "Tin trước giai đoạn", funded.minus(Duration.ofHours(1)));
		JobContent.message(this.jdbc, crew, this.world.vy(), "Tin trong giai đoạn", funded.plus(Duration.ofHours(1)));
		JobContent.message(this.jdbc, jobChat, this.world.hung(), "Tin với khách hàng", funded.plus(Duration.ofHours(2)));
		JobContent.board(this.jdbc, this.world.job(), this.world.product(), "VNM", 3);
		UUID mine = JobContent.workItem(this.jdbc, this.world.job(), 1, "Việc của Vy", this.world.milestone(), null, this.clock.instant());
		this.jdbc.sql("UPDATE work.work_item SET assignee_account_id = :vy WHERE id = :id").param("vy", this.world.vy()).param("id", mine).update();
		UUID other = stranger();
		UUID theirs = JobContent.workItem(this.jdbc, this.world.job(), 2, "Việc của người khác", this.world.milestone(), null, this.clock.instant());
		this.jdbc.sql("UPDATE work.work_item SET assignee_account_id = :other WHERE id = :id").param("other", other).param("id", theirs).update();
		UUID elsewhere = JobContent.workItem(this.jdbc, this.world.job(), 3, "Việc giai đoạn khác", null, null, this.clock.instant());
		this.jdbc.sql("UPDATE work.work_item SET assignee_account_id = :vy WHERE id = :id").param("vy", this.world.vy()).param("id", elsewhere).update();
		UUID handover = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO delivery.handover (id, job_id, product_id, milestone_id, milestone_number, milestone_title, round,
				    mentor_account_id, status, job_version, transition_id, check_by, created_at)
				VALUES (:id, :job, :product, :milestone, 1, 'Giai đoạn 1', 1, :mentor, 'SUBMITTED', 3, :transition, :at, :at)
				""").param("id", handover).param("job", this.world.job()).param("product", this.world.product())
			.param("milestone", this.world.milestone()).param("mentor", this.world.hung()).param("transition", UUID.randomUUID())
			.param("at", Timestamp.from(this.clock.instant())).update();
		long githubVy = this.jdbc.sql("SELECT github_user_id FROM identity.account WHERE id = :id").param("id", this.world.vy()).query(Long.class).single();
		commit(handover, 0, "a", githubVy);
		commit(handover, 1, "b", 999_000_111L);
		commit(handover, 2, "c", null);
		accepted();
		signal(this.world.vy(), "ef".repeat(32));
		signal(this.world.lan(), "ef".repeat(32));
		raise(this.world.minh(), 1, "Phần của tôi quá ít").andExpect(status().isCreated());
		String id = onlyCase();
		UUID admin = this.ha.accountId();

		this.mvc.perform(get("/api/v1/boards/" + this.world.job() + "/crew-chat/messages").with(admin(admin))).andExpect(status().isNotFound());
		take(admin, id).andExpect(status().isOk());

		this.mvc.perform(get("/api/v1/boards/" + this.world.job() + "/crew-chat/messages").with(admin(admin))).andExpect(status().isOk());
		this.mvc.perform(get("/api/v1/jobs/" + this.world.job() + "/chat/messages").with(admin(admin))).andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/work-items/" + mine).with(admin(admin))).andExpect(status().isOk());
		this.mvc.perform(get("/api/v1/work-items/" + theirs).with(admin(admin))).andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/admin/jobs/" + this.world.job() + "/crew-terms").with(admin(admin))).andExpect(status().isNotFound());
		assertThat(number("SELECT count(*) FROM audit.audit_entry WHERE action = 'chat.admin_read'")).isOne();

		evidence(admin, id, "versions").andExpect(status().isOk())
			.andExpect(jsonPath("$.versions", hasSize(1)))
			.andExpect(jsonPath("$.versions[0].kind").value("DRAFT"))
			.andExpect(jsonPath("$.versions[0].authorName").value("Đỗ Mạnh Hùng"))
			.andExpect(jsonPath("$.versions[0].capPercent").value(30))
			.andExpect(jsonPath("$.versions[0].lines", hasSize(3)))
			.andExpect(jsonPath("$.parties", hasSize(3)))
			.andExpect(jsonPath("$.parties[1].activeFrom").exists())
			.andExpect(jsonPath("$.parties[1].activeTo").exists());
		evidence(admin, id, "work-items").andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(1)))
			.andExpect(jsonPath("$[0].title").value("Việc của Vy"))
			.andExpect(jsonPath("$[0].assigneeName").value("Trần Thảo Vy"));
		evidence(admin, id, "commits").andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(3)))
			.andExpect(jsonPath("$[0].partyName").value("Trần Thảo Vy"))
			.andExpect(jsonPath("$[0].authorGithubId").value(githubVy))
			.andExpect(jsonPath("$[1].partyId").doesNotExist())
			.andExpect(jsonPath("$[2].authorGithubId").doesNotExist());
		evidence(admin, id, "crew-chat").andExpect(status().isOk())
			.andExpect(jsonPath("$.messages", hasSize(1)))
			.andExpect(jsonPath("$.messages[0].body").value("Tin trong giai đoạn"))
			.andExpect(content().string(not(containsString("Tin với khách hàng"))));
		evidence(admin, id, "person-links").andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(1)))
			.andExpect(jsonPath("$[0].kind").value("EMAIL"))
			.andExpect(content().string(not(containsString("ef".repeat(32)))));

		for (String tab : List.of("versions", "work_items", "commits", "crew_chat", "person_links")) {
			assertThat(number("SELECT count(*) FROM audit.audit_entry WHERE action = 'dispute.split_evidence_viewed." + tab + "'"))
				.as(tab).isOne();
		}
		assertThat(number("SELECT count(*) FROM audit.audit_entry WHERE action = 'job.admin_read'")).isOne();
		assertThat(number("SELECT count(*) FROM audit.audit_entry WHERE action = 'work.admin_read'")).isEqualTo(2);
		assertThat(number("SELECT count(*) FROM audit.audit_entry WHERE action = 'chat.admin_read'")).isEqualTo(2);
	}

	// ---- The decision (SM-8) ----------------------------------------------------------------------

	@Test
	void theAssigneesDecisionIsFinalPaysThePoolAndKeepsItsRecord() throws Exception {
		accepted();
		raise(this.world.vy(), 1, "Phần của tôi quá ít").andExpect(status().isCreated());
		String id = onlyCase();
		take(this.ha.accountId(), id).andExpect(status().isOk());
		assertThat(ledgerTypes()).containsExactlyInAnyOrder("FUND", "RELEASE_POOLED");

		this.mvc.perform(post("/api/v1/admin/split-resolutions/" + id + "/preview").with(admin(this.ha.accountId())).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(shares(25, 50, 25)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.pooledVnd").value(HeldCrewJob.PRICE))
			.andExpect(jsonPath("$.lines[0].amountVnd").value(250_000))
			.andExpect(jsonPath("$.lines[1].amountVnd").value(500_000));

		decide(this.ha, id, 25, 50, 25, "Vy làm phần lớn việc").andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("DECIDED"))
			.andExpect(jsonPath("$.decision.reasoning").value("Vy làm phần lớn việc"))
			.andExpect(jsonPath("$.decision.versionNo").value(2))
			.andExpect(jsonPath("$.decision.pooledVnd").value(HeldCrewJob.PRICE))
			.andExpect(jsonPath("$.decision.evidenceSnapshot.versions", hasSize(1)));

		assertThat(splitStatus()).isEqualTo("FINAL");
		assertThat(text("SELECT finality FROM job.milestone_split")).isEqualTo("DECISION");
		assertThat(text("SELECT kind FROM job.milestone_split_version WHERE version_no = 2")).isEqualTo("DECISION");
		assertThat(this.jdbc.sql("SELECT author_account_id FROM job.milestone_split_version WHERE version_no = 2").query(UUID.class).single())
			.isEqualTo(this.ha.accountId());
		assertThat(number("SELECT cap_percent FROM job.milestone_split_version WHERE version_no = 2")).isEqualTo(30);
		assertThat(notified("job.split_final")).containsExactlyInAnyOrder(this.world.hung(), this.world.vy(), this.world.minh());
		assertThat(notified("dispute.split_resolution_decided")).containsExactlyInAnyOrder(this.world.hung(), this.world.vy(), this.world.minh());
		assertThat(number("SELECT count(*) FROM audit.audit_entry WHERE action = 'dispute.split_resolution_decided'")).isOne();
		assertThat(text("SELECT decision_amounts ->> 'pooledVnd' FROM dispute.split_resolution")).isEqualTo(String.valueOf(HeldCrewJob.PRICE));
		assertThat(text("SELECT evidence_snapshot::text FROM dispute.split_resolution")).doesNotContain(this.world.vy().toString());

		awaitListeners();
		assertThat(this.jdbc.sql("""
				SELECT amount_vnd FROM payment.ledger_entry WHERE entry_type = 'RELEASE' AND party_account_id = :party
				""").param("party", this.world.vy()).query(Long.class).single()).isEqualTo(500_000);
		assertThat(number("SELECT count(*) FROM payment.ledger_entry WHERE entry_type = 'POOL_RELEASED'")).isOne();
		assertThat(this.ledger.releasedTotal(this.world.milestone())).isEqualTo(HeldCrewJob.PRICE);

		read(this.world.minh()).andExpect(jsonPath("$.resolutions[0].status").value("DECIDED"))
			.andExpect(jsonPath("$.resolutions[0].reasoning").value("Vy làm phần lớn việc"));
		cards(this.world.vy()).andExpect(jsonPath("$[0].finality").value("DECISION"))
			.andExpect(jsonPath("$[0].paid").value(true));
		evidence(this.ha.accountId(), id, "versions").andExpect(status().isOk())
			.andExpect(jsonPath("$.versions", hasSize(2)))
			.andExpect(jsonPath("$.versions[1].kind").value("DECISION"))
			.andExpect(jsonPath("$.versions[1].authorName").value("Hà Phương"));
		this.clock.advance(Duration.ofDays(30));
		evidence(this.ha.accountId(), id, "versions").andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/boards/" + this.world.job() + "/crew-chat/messages").with(admin(this.ha.accountId())))
			.andExpect(status().isNotFound());
	}

	@Test
	void anInvalidDecisionChangesNothing() throws Exception {
		accepted();
		raise(this.world.vy(), 1, "Phần của tôi quá ít").andExpect(status().isCreated());
		String id = onlyCase();
		take(this.ha.accountId(), id).andExpect(status().isOk());

		for (int[] percents : new int[][] { { 31, 40, 29 }, { 30, 40, 29 }, { 30, 70, 0 } }) {
			decide(this.ha, id, percents[0], percents[1], percents[2], "Lý do").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION"))
				.andExpect(jsonPath("$.reason").value("SPLIT_INVALID"));
		}

		assertThat(splitStatus()).isEqualTo("IN_RESOLUTION");
		assertThat(number("SELECT count(*) FROM job.milestone_split_version")).isOne();
		assertThat(text("SELECT status FROM dispute.split_resolution")).isEqualTo("OPEN");
	}

	@Test
	void aDecisionIsHeldToTheCapStoredOnTheLatestVersionNotTheLiveOne() throws Exception {
		accepted();
		raise(this.world.vy(), 1, "Phần của tôi quá ít").andExpect(status().isCreated());
		String id = onlyCase();
		take(this.ha.accountId(), id).andExpect(status().isOk());
		this.jdbc.sql("UPDATE settings.rule_value SET value = 20 WHERE rule_key = 'MENTOR_SHARE_CAP_PERCENT'").update();

		decide(this.ha, id, 31, 40, 29, "Trên mức đã lưu").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reason").value("SPLIT_INVALID"));
		decide(this.ha, id, 30, 40, 30, "Trong mức đã lưu").andExpect(status().isOk());

		assertThat(number("SELECT cap_percent FROM job.milestone_split_version WHERE version_no = 2")).isEqualTo(30);
	}

	@Test
	void underX5ADecisionHasNoCapAndMayGiveEverythingToTheMentor() throws Exception {
		Instant left = this.clock.instant().minus(Duration.ofHours(1));
		for (UUID member : List.of(this.world.vy(), this.world.minh())) {
			this.jdbc.sql("""
					UPDATE job.crew_row SET status = 'RELEASED', released_reason = 'LEFT', released_at = :end, window_end = :end
					WHERE job_id = :job AND member_account_id = :member
					""").param("end", Timestamp.from(left)).param("job", this.world.job()).param("member", member).update();
		}
		accept();
		saveVersion(0, 60, 20, 20).andExpect(status().isOk());
		assertThat(number("SELECT count(*) FROM job.milestone_split_version WHERE cap_percent IS NULL")).isOne();
		raise(this.world.vy(), 1, "Phần của tôi quá ít").andExpect(status().isCreated());
		String id = onlyCase();
		take(this.ha.accountId(), id).andExpect(status().isOk());
		detail(this.ha.accountId(), id).andExpect(jsonPath("$.capApplies").value(false)).andExpect(jsonPath("$.cap").doesNotExist());

		decide(this.ha, id, 100, 0, 0, "Họ đã rời trước khi làm").andExpect(status().isOk());

		assertThat(splitStatus()).isEqualTo("FINAL");
		assertThat(number("SELECT count(*) FROM job.milestone_split_version WHERE version_no = 2 AND cap_percent IS NULL")).isOne();
	}

	@Test
	void aWrongStepUpChangesNothingAndOnlyTheAssigneeDecides() throws Exception {
		accepted();
		raise(this.world.vy(), 1, "Phần của tôi quá ít").andExpect(status().isCreated());
		String id = onlyCase();

		decide(this.ha, id, 30, 40, 30, "Chưa nhận").andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("NOT_ASSIGNED"));
		this.mvc.perform(post("/api/v1/admin/split-resolutions/" + id + "/preview").with(admin(this.ha.accountId())).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(shares(30, 40, 30))).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("NOT_ASSIGNED"));
		take(this.ha.accountId(), id).andExpect(status().isOk());

		String wrong = "000000".equals(code()) ? "000001" : "000000";
		this.mvc.perform(post("/api/v1/admin/split-resolutions/" + id + "/decision").with(admin(this.ha.accountId())).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(body(30, 40, 30, "Sai mã", wrong))).andExpect(status().is4xxClientError());

		assertThat(splitStatus()).isEqualTo("IN_RESOLUTION");
		assertThat(text("SELECT status FROM dispute.split_resolution")).isEqualTo("OPEN");
		assertThat(number("SELECT count(*) FROM job.milestone_split_version")).isOne();
		assertThat(number("""
				SELECT count(*) FROM audit.audit_entry WHERE action = 'dispute.split_resolution_decided'
				""")).isZero();

		decide(this.ha, id, 30, 40, 30, "Đúng mã").andExpect(status().isOk());
		take(this.ha.accountId(), id).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("ALREADY_DECIDED"));
		decide(this.ha, id, 30, 40, 30, "Lần nữa").andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("ALREADY_DECIDED"));
		withdraw(this.world.vy()).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.reason").value("SPLIT_RESOLUTION_ENDED"));
	}

	// ---- Undecided --------------------------------------------------------------------------------

	@Test
	void aCaseUndecidedPastItsCopiedDaysIsFilteredAndAlertedAndStaysOpen() throws Exception {
		this.jdbc.sql("UPDATE job.job SET rules = rules || '{\"SPLIT_UNDECIDED_FLAG_DAYS\": 2}'::jsonb WHERE id = :id")
			.param("id", this.world.job())
			.update();
		accepted();
		raise(this.world.vy(), 1, "Phần của tôi quá ít").andExpect(status().isCreated());
		String id = onlyCase();
		this.jdbc.sql("UPDATE settings.rule_value SET value = 30 WHERE rule_key = 'SPLIT_UNDECIDED_FLAG_DAYS'").update();

		undecided().andExpect(jsonPath("$", hasSize(0)));
		this.clock.advance(Duration.ofDays(2));
		this.poller.runDue();

		undecided().andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].id").value(id)).andExpect(jsonPath("$[0].undecidedLong").value(true));
		assertThat(notified("dispute.split_resolution_undecided")).containsExactlyInAnyOrder(this.ha.accountId(), this.quan);
		assertThat(text("SELECT params ->> 'days' FROM notification.notification WHERE type = 'dispute.split_resolution_undecided' LIMIT 1"))
			.isEqualTo("2");
		assertThat(deadlineStatuses("dispute.split_resolution_undecided")).containsExactly("FIRED");
		assertThat(text("SELECT status FROM dispute.split_resolution")).isEqualTo("OPEN");
		assertThat(splitStatus()).isEqualTo("IN_RESOLUTION");
		assertThat(number("SELECT count(*) FROM audit.audit_entry WHERE action = 'dispute.split_resolution_undecided'")).isOne();
		detail(this.ha.accountId(), id).andExpect(jsonPath("$.daysUndecided").value(2));
	}

	@Test
	void aCaseDecidedInTimeLeavesItsDeadlineObsoleteAndNoAlert() throws Exception {
		accepted();
		raise(this.world.vy(), 1, "Phần của tôi quá ít").andExpect(status().isCreated());
		String id = onlyCase();
		take(this.ha.accountId(), id).andExpect(status().isOk());
		decide(this.ha, id, 30, 40, 30, "Đã quyết").andExpect(status().isOk());

		this.clock.advance(Duration.ofDays(8));
		this.poller.runDue();

		assertThat(deadlineStatuses("dispute.split_resolution_undecided")).containsExactly("OBSOLETE");
		assertThat(notified("dispute.split_resolution_undecided")).isEmpty();
		undecided().andExpect(jsonPath("$", hasSize(0)));
	}

	// ---- Independence -----------------------------------------------------------------------------

	@Test
	void aFreezeOrADisputePausesNothingOfIt() throws Exception {
		accepted();
		Timestamp frozen = Timestamp.from(this.clock.instant());
		this.jdbc.sql("UPDATE job.job SET frozen_at = :at WHERE id = :id").param("at", frozen).param("id", this.world.job()).update();
		this.jdbc.sql("INSERT INTO timers.frozen_job (job_id, frozen_at) VALUES (:job, :at)")
			.param("job", this.world.job())
			.param("at", frozen)
			.update();

		raise(this.world.vy(), 1, "Phần của tôi quá ít").andExpect(status().isCreated());
		String id = onlyCase();
		statement(this.world.hung(), id, "Ý kiến của tôi").andExpect(status().isCreated());
		take(this.ha.accountId(), id).andExpect(status().isOk());
		this.clock.advance(Duration.ofDays(7));
		this.poller.runDue();
		assertThat(deadlineStatuses("dispute.split_resolution_undecided")).containsExactly("FIRED");
		decide(this.ha, id, 30, 40, 30, "Vẫn quyết được").andExpect(status().isOk());
		assertThat(splitStatus()).isEqualTo("FINAL");
		assertThat(number("SELECT count(*) FROM job.job WHERE frozen_at IS NOT NULL")).as("and it unfreezes nothing").isOne();
	}

	@Test
	void aLockOnAnyoneEndsNothing() throws Exception {
		accepted();
		raise(this.world.vy(), 1, "Phần của tôi quá ít").andExpect(status().isCreated());
		String id = onlyCase();
		take(this.ha.accountId(), id).andExpect(status().isOk());
		for (UUID person : List.of(this.world.vy(), this.world.hung())) {
			this.jdbc.sql("UPDATE identity.account SET locked_at = :at, lock_reason = 'Vi phạm' WHERE id = :id")
				.param("at", Timestamp.from(this.clock.instant()))
				.param("id", person)
				.update();
		}

		assertThat(text("SELECT status FROM dispute.split_resolution")).isEqualTo("OPEN");
		this.mvc.perform(post("/api/v1/split-resolutions/" + id + "/withdraw").with(as(this.world.vy())).with(csrf()))
			.andExpect(status().is4xxClientError());
		assertThat(text("SELECT status FROM dispute.split_resolution")).as("the gate refuses a locked account").isEqualTo("OPEN");
		decide(this.ha, id, 30, 40, 30, "Vẫn quyết được").andExpect(status().isOk());
	}

	@Test
	void afterTheJobEndsEverythingStillWorks() throws Exception {
		accepted();
		closeJob();

		raise(this.world.vy(), 1, "Phần của tôi quá ít").andExpect(status().isCreated());
		String id = onlyCase();
		statement(this.world.hung(), id, "Ý kiến của tôi").andExpect(status().isCreated());
		take(this.ha.accountId(), id).andExpect(status().isOk());
		evidence(this.ha.accountId(), id, "versions").andExpect(status().isOk());
		decide(this.ha, id, 30, 40, 30, "Đã kết thúc vẫn quyết").andExpect(status().isOk());
		assertThat(splitStatus()).isEqualTo("FINAL");
	}

	@Test
	void onABareRowEverythingStillWorksAndTheEvidenceWaitsForTheDecision() throws Exception {
		UUID crew = JobContent.chat(this.jdbc, this.world.job(), this.world.product(), "CREW");
		UUID jobChat = JobContent.chat(this.jdbc, this.world.job(), this.world.product(), "JOB");
		JobContent.message(this.jdbc, crew, this.world.vy(), "Tin trong giai đoạn", this.clock.instant().minus(Duration.ofHours(1)));
		JobContent.message(this.jdbc, jobChat, this.world.hung(), "Tin với khách hàng", this.clock.instant().minus(Duration.ofHours(1)));
		JobContent.board(this.jdbc, this.world.job(), this.world.product(), "VNM", 1);
		UUID item = JobContent.workItem(this.jdbc, this.world.job(), 1, "Việc của Vy", this.world.milestone(), null, this.clock.instant());
		this.jdbc.sql("UPDATE work.work_item SET assignee_account_id = :vy WHERE id = :id").param("vy", this.world.vy()).param("id", item).update();
		accepted();
		closeJob();
		raise(this.world.vy(), 1, "Phần của tôi quá ít").andExpect(status().isCreated());
		String id = onlyCase();
		take(this.ha.accountId(), id).andExpect(status().isOk());

		this.transactions.executeWithoutResult(status -> this.personalData.deleteProduct(this.world.product()));

		assertThat(number("SELECT count(*) FROM job.job WHERE product_deleted_at IS NOT NULL")).isOne();
		assertThat(number("SELECT count(*) FROM job.crew_row")).isZero();
		assertThat(number("SELECT count(*) FROM job.milestone")).isZero();
		assertThat(number("SELECT count(*) FROM job.admin_read_grant")).as("the grant stays with the open split").isOne();
		assertThat(number("SELECT count(*) FROM dispute.split_resolution")).isOne();

		read(this.world.hung()).andExpect(status().isOk()).andExpect(jsonPath("$.resolutions", hasSize(1)));
		statement(this.world.hung(), id, "Ý kiến của tôi").andExpect(status().isCreated());
		evidence(this.ha.accountId(), id, "versions").andExpect(status().isOk())
			.andExpect(jsonPath("$.parties[1].activeFrom").exists());
		evidence(this.ha.accountId(), id, "work-items").andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
		evidence(this.ha.accountId(), id, "crew-chat").andExpect(status().isOk()).andExpect(jsonPath("$.messages", hasSize(1)));
		this.mvc.perform(get("/api/v1/boards/" + this.world.job() + "/crew-chat/messages").with(admin(this.ha.accountId())))
			.andExpect(status().isOk());
		this.mvc.perform(get("/api/v1/work-items/" + item).with(admin(this.ha.accountId()))).andExpect(status().isOk());
		this.mvc.perform(get("/api/v1/jobs/" + this.world.job() + "/chat/messages").with(admin(this.ha.accountId())))
			.andExpect(status().isNotFound());

		decide(this.ha, id, 30, 40, 30, "Sản phẩm đã xóa vẫn quyết").andExpect(status().isOk());
		awaitListeners();

		assertThat(number("SELECT count(*) FROM payment.ledger_entry WHERE entry_type = 'RELEASE'")).isEqualTo(3);
		assertThat(number("SELECT count(*) FROM job.job")).as("the bare row has done its work").isZero();
		assertThat(number("SELECT count(*) FROM job.admin_read_grant")).isZero();
		assertThat(number("SELECT count(*) FROM dispute.split_resolution")).as("the record stays").isOne();
		assertThat(number("SELECT count(*) FROM dispute.split_resolution_statement")).isOne();
	}

	@Test
	void aWithdrawalOnABareRowWorksToo() throws Exception {
		accepted();
		closeJob();
		raise(this.world.vy(), 1, "Phần của tôi quá ít").andExpect(status().isCreated());
		this.transactions.executeWithoutResult(status -> this.personalData.deleteProduct(this.world.product()));

		withdraw(this.world.vy()).andExpect(status().isOk()).andExpect(jsonPath("$.splitStatus").value("PROPOSED"));

		assertThat(splitStatus()).isEqualTo("PROPOSED");
		raise(this.world.minh(), 1, "Tôi nhờ tiếp").andExpect(status().isCreated());
	}

	// ---- Retention --------------------------------------------------------------------------------

	@Test
	void anErasureClearsThePeopleAndKeepsTheTextAndTheRecord() throws Exception {
		accepted();
		raise(this.world.vy(), 1, "Phần của tôi quá ít").andExpect(status().isCreated());
		String id = onlyCase();
		statement(this.world.minh(), id, "Ý kiến của tôi").andExpect(status().isCreated());
		take(this.ha.accountId(), id).andExpect(status().isOk());
		decide(this.ha, id, 30, 40, 30, "Đã quyết").andExpect(status().isOk());
		awaitListeners();
		closeJob();
		this.jdbc.sql("""
				UPDATE job.crew_row SET status = 'RELEASED', released_reason = 'JOB_ENDED', released_at = :now, window_end = :now
				WHERE job_id = :job
				""").param("now", Timestamp.from(this.clock.instant())).param("job", this.world.job()).update();

		assertThat(this.personalData.export(this.world.vy()).stream()
			.filter(section -> section.module().equals("dispute"))
			.findFirst()).hasValueSatisfying(section -> assertThat(section.data().toString()).contains("Phần của tôi quá ít"));
		assertThat(this.personalData.export(this.world.minh()).stream()
			.filter(section -> section.module().equals("dispute"))
			.findFirst()).hasValueSatisfying(section -> assertThat(section.data().toString()).contains("Ý kiến của tôi"));

		for (UUID person : List.of(this.world.vy(), this.world.minh())) {
			ErasureOutcome outcome = this.transactions.execute(status -> this.personalData.erase(person));
			assertThat(outcome).isInstanceOf(ErasureOutcome.Erased.class);
		}

		assertThat(number("SELECT count(*) FROM dispute.split_resolution WHERE raiser_account_id IS NULL")).isOne();
		assertThat(text("SELECT reason FROM dispute.split_resolution")).isEqualTo("Phần của tôi quá ít");
		assertThat(text("SELECT decision_reasoning FROM dispute.split_resolution")).isEqualTo("Đã quyết");
		assertThat(number("SELECT count(*) FROM dispute.split_resolution_statement WHERE account_id IS NULL")).isOne();
		assertThat(text("SELECT text FROM dispute.split_resolution_statement")).isEqualTo("Ý kiến của tôi");
	}

	@Test
	void aProductDeletionAndTheRetentionRunDeleteNoResolution() throws Exception {
		accepted();
		raise(this.world.vy(), 1, "Phần của tôi quá ít").andExpect(status().isCreated());
		String id = onlyCase();
		statement(this.world.hung(), id, "Ý kiến của tôi").andExpect(status().isCreated());
		take(this.ha.accountId(), id).andExpect(status().isOk());
		decide(this.ha, id, 30, 40, 30, "Đã quyết").andExpect(status().isOk());
		awaitListeners();
		closeJob();

		this.transactions.executeWithoutResult(status -> this.personalData.retireJobData(this.world.product()));
		this.transactions.executeWithoutResult(status -> this.personalData.deleteProduct(this.world.product()));

		assertThat(number("SELECT count(*) FROM dispute.split_resolution")).isOne();
		assertThat(number("SELECT count(*) FROM dispute.split_resolution_statement")).isOne();
		read(this.world.vy()).andExpect(status().isOk()).andExpect(jsonPath("$.resolutions[0].reasoning").value("Đã quyết"));
	}

	// ---- The Customer -----------------------------------------------------------------------------

	@Test
	void theCustomerIsToldNothingAndFindsNothing() throws Exception {
		accepted();
		raise(this.world.vy(), 1, "Phần của tôi quá ít").andExpect(status().isCreated());
		String id = onlyCase();
		take(this.ha.accountId(), id).andExpect(status().isOk());
		decide(this.ha, id, 30, 40, 30, "Đã quyết").andExpect(status().isOk());
		awaitListeners();

		read(this.world.lan()).andExpect(status().isNotFound());
		statement(this.world.lan(), id, "Tôi là khách hàng").andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/admin/split-resolutions").with(as(this.world.lan()))).andExpect(status().isNotFound());
		assertThat(number("""
				SELECT count(*) FROM notification.notification WHERE account_id = '%s' AND type LIKE 'dispute.split_resolution_%%'
				""".formatted(this.world.lan()))).isZero();
	}

	// ---- Helpers ----------------------------------------------------------------------------------

	private long version;

	private void accept() {
		this.transactions.executeWithoutResult(status -> {
			UUID handover = UUID.randomUUID();
			this.jdbc.sql("UPDATE job.milestone SET handover_id = :handover, state = 'SUBMITTED', submitted_at = :now WHERE id = :id")
				.param("handover", handover)
				.param("now", Timestamp.from(this.clock.instant()))
				.param("id", this.world.milestone())
				.update();
			UUID transition = this.handovers.autoAccept(this.world.job(), this.world.milestone(), handover).orElseThrow().transitionId();
			this.releases.release(this.world.job(), this.world.milestone(), transition);
		});
		this.splitId = this.jdbc.sql("SELECT id FROM job.milestone_split WHERE milestone_id = :id")
			.param("id", this.world.milestone())
			.query(UUID.class)
			.single();
		this.version = number("SELECT version FROM job.job WHERE id = '" + this.world.job() + "'");
	}

	/** The Milestone accepted and its split out for confirmation at 30/40/30. */
	private void accepted() throws Exception {
		accept();
		saved();
	}

	private void saved() throws Exception {
		saveVersion(0, 30, 40, 30).andExpect(status().isOk());
	}

	private void closeJob() {
		this.jdbc.sql("UPDATE job.job SET state = 'CLOSED', closed_at = :now WHERE id = :id")
			.param("now", Timestamp.from(this.clock.instant()))
			.param("id", this.world.job())
			.update();
	}

	private ResultActions saveVersion(int shown, int mentor, int vy, int minh) throws Exception {
		String lines = """
				{"shownVersionNo":%d,"shares":[{"partyId":"%s","percent":%d},{"partyId":"%s","percent":%d},{"partyId":"%s","percent":%d}]}
				""".formatted(shown, party(this.world.hung()), mentor, party(this.world.vy()), vy, party(this.world.minh()), minh);
		return this.mvc.perform(post("/api/v1/splits/" + this.splitId + "/versions").with(as(this.world.hung())).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(lines));
	}

	private ResultActions confirm(UUID account, int version) throws Exception {
		return this.mvc.perform(post("/api/v1/splits/" + this.splitId + "/confirmations").with(as(account)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"splitVersionNo\":" + version + "}"));
	}

	private ResultActions raise(UUID account, int version, String reason) throws Exception {
		return this.mvc.perform(post("/api/v1/splits/" + this.splitId + "/resolutions").with(as(account)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"splitVersionNo\":" + version + ",\"reason\":\"" + reason + "\"}"));
	}

	private ResultActions read(UUID account) throws Exception {
		return this.mvc.perform(get("/api/v1/splits/" + this.splitId + "/resolutions").with(as(account)));
	}

	private ResultActions cards(UUID account) throws Exception {
		return this.mvc.perform(get("/api/v1/jobs/" + this.world.job() + "/splits").with(as(account)));
	}

	private ResultActions statement(UUID account, String id, String text) throws Exception {
		return this.mvc.perform(post("/api/v1/split-resolutions/" + id + "/statements").with(as(account)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"text\":\"" + text + "\"}"));
	}

	/** Withdraws the latest case. */
	private ResultActions withdraw(UUID account) throws Exception {
		return withdrawAs(as(account), latestCase());
	}

	private ResultActions withdrawAs(RequestPostProcessor caller, String id) throws Exception {
		return this.mvc.perform(post("/api/v1/split-resolutions/" + id + "/withdraw").with(caller).with(csrf()));
	}

	private ResultActions take(UUID admin, String id) throws Exception {
		return this.mvc.perform(post("/api/v1/admin/split-resolutions/" + id + "/take").with(admin(admin)).with(csrf()));
	}

	private ResultActions detail(UUID admin, String id) throws Exception {
		return this.mvc.perform(get("/api/v1/admin/split-resolutions/" + id).with(admin(admin)));
	}

	private ResultActions undecided() throws Exception {
		return this.mvc.perform(get("/api/v1/admin/split-resolutions?undecided=true").with(admin(this.ha.accountId())));
	}

	private ResultActions evidence(UUID admin, String id, String tab) throws Exception {
		return this.mvc.perform(get("/api/v1/admin/split-resolutions/" + id + "/evidence/" + tab).with(admin(admin)));
	}

	private ResultActions decide(Administrator admin, String id, int mentor, int vy, int minh, String reasoning) throws Exception {
		return this.mvc.perform(post("/api/v1/admin/split-resolutions/" + id + "/decision").with(admin(admin.accountId())).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(body(mentor, vy, minh, reasoning, freshCode())));
	}

	/** A code of a time step never used, as every decision needs (SG-10). */
	private String freshCode() {
		this.clock.advance(Duration.ofSeconds(31));
		return code();
	}

	private String code() {
		return AdministratorTestSupport.code(this.ha, this.clock.instant());
	}

	private String shares(int mentor, int vy, int minh) {
		return """
				{"shares":[{"partyId":"%s","percent":%d},{"partyId":"%s","percent":%d},{"partyId":"%s","percent":%d}]}
				""".formatted(party(this.world.hung()), mentor, party(this.world.vy()), vy, party(this.world.minh()), minh);
	}

	private String body(int mentor, int vy, int minh, String reasoning, String code) {
		return """
				{"shares":[{"partyId":"%s","percent":%d},{"partyId":"%s","percent":%d},{"partyId":"%s","percent":%d}],
				 "reasoning":"%s","stepUpCode":"%s"}
				""".formatted(party(this.world.hung()), mentor, party(this.world.vy()), vy, party(this.world.minh()), minh, reasoning, code);
	}

	private void commit(UUID handover, int position, String letter, Long githubId) {
		this.jdbc.sql("""
				INSERT INTO delivery.handover_commit (handover_id, position, sha, message, author_github_id)
				VALUES (:handover, :position, :sha, 'Một commit', :author)
				""")
			.param("handover", handover)
			.param("position", position)
			.param("sha", letter.repeat(40))
			.param("author", githubId, java.sql.Types.BIGINT)
			.update();
	}

	private void signal(UUID account, String hmac) {
		this.jdbc.sql("""
				INSERT INTO identity.person_signal (id, account_id, kind, source, hmac, key_version, recorded_at)
				VALUES (:id, :account, 'EMAIL', 'GOOGLE_EMAIL', :hmac, 1, now())
				""").param("id", UUID.randomUUID()).param("account", account).param("hmac", hmac).update();
	}

	private UUID party(UUID account) {
		return this.jdbc.sql("SELECT id FROM job.milestone_split_party WHERE split_id = :split AND party_account_id = :account")
			.param("split", this.splitId)
			.param("account", account)
			.query(UUID.class)
			.single();
	}

	private String onlyCase() {
		return text("SELECT id::text FROM dispute.split_resolution");
	}

	private String latestCase() {
		return text("SELECT id::text FROM dispute.split_resolution ORDER BY raised_at DESC, status = 'OPEN' DESC LIMIT 1");
	}

	private String splitStatus() {
		return text("SELECT status FROM job.milestone_split");
	}

	private List<String> ledgerTypes() {
		return this.jdbc.sql("SELECT entry_type FROM payment.ledger_entry").query(String.class).list();
	}

	private List<UUID> notified(String type) {
		return this.jdbc.sql("SELECT account_id FROM notification.notification WHERE type = :type")
			.param("type", type)
			.query(UUID.class)
			.list();
	}

	private List<UUID> openTodos(String key) {
		return this.jdbc.sql("SELECT account_id FROM notification.todo WHERE todo_key = :key AND closed_at IS NULL")
			.param("key", key)
			.query(UUID.class)
			.list();
	}

	private List<String> deadlineStatuses(String kind) {
		return this.jdbc.sql("SELECT status FROM timers.deadline WHERE kind = :kind ORDER BY created_at, id")
			.param("kind", kind)
			.query(String.class)
			.list();
	}

	private UUID stranger() {
		return IdentityTestSupport.insertGoogleAccount(this.jdbc, "google-resolution-" + UUID.randomUUID(), "3.1");
	}

	private RequestPostProcessor as(UUID account) {
		return IdentityTestSupport.signedIn(this.sessions, account, null, this.clock.instant());
	}

	private RequestPostProcessor admin(UUID administrator) {
		return IdentityTestSupport.signedIn(this.sessions, administrator, Role.ADMINISTRATOR, this.clock.instant());
	}

	private void awaitListeners() {
		await().atMost(Duration.ofSeconds(15)).until(() -> number("""
				SELECT count(*) FROM platform.event_publication WHERE completion_date IS NULL
				""") == 0);
	}

	private long number(String sql) {
		return this.jdbc.sql(sql).query(Long.class).single();
	}

	private String text(String sql) {
		return this.jdbc.sql(sql).query(String.class).single();
	}

	private Instant timestamp(String sql) {
		return this.jdbc.sql(sql).query(Timestamp.class).single().toInstant();
	}

}
