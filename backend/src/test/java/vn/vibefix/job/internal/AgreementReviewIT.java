package vn.vibefix.job.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.sun.net.httpserver.HttpServer;

import vn.vibefix.identity.AccountLocked;
import vn.vibefix.identity.internal.CustomerTokens;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.job.CrewPicks;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.MutableClock;
import vn.vibefix.support.TestPeople;

/**
 * FR-16, FR-17, AD-29, AD-41: the Customer's review, the Mentor's confirmation, which waits for no Crew condition and
 * lists only the members who signed (Story 21.5, R7), and a lock before or after it; then FR-66's External Access
 * guidance with each active person's own account (Story 8.1), which a pick not yet signed never reads (AD-41).
 */
class AgreementReviewIT extends IntegrationTest {

	private static final String TOKEN = "gho_customer_token_never_printed";

	private static final HttpServer GITHUB = start();

	private static final UUID MILESTONE = UUID.fromString("00000000-0000-7000-8000-00000000a001");

	private static final UUID CRITERION = UUID.fromString("00000000-0000-7000-8000-00000000a002");

	private static final UUID ACCESS = UUID.fromString("00000000-0000-7000-8000-00000000a003");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private ApplicationContext context;

	@Autowired
	private ApplicationEventPublisher events;

	@Autowired
	private PlatformTransactionManager transactions;

	@Autowired
	private CrewPicks picks;

	private UUID lan;

	private UUID hung;

	private UUID vy;

	private UUID job;

	@DynamicPropertySource
	static void stubGitHub(DynamicPropertyRegistry registry) {
		registry.add("vibefix.github.api-url", () -> "http://127.0.0.1:" + GITHUB.getAddress().getPort());
	}

	@BeforeEach
	void aSharedDraft() throws Exception {
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		CustomerTokens.keep(this.context, this.lan, TOKEN);
		UUID product = product(this.lan);
		this.job = UUID.randomUUID();
		Instant now = this.clock.instant();
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, budget, deadline, rules, selection_days, opened_at, lapses_at, version, created_at, updated_at)
				VALUES (:id, :product, :owner, 'OPEN', 'Sửa lỗi đặt bàn', 'Lỗi', 'Đúng', 'Khách', ARRAY['BUG_FIX'],
				    'UNDER_2M', DATE '2026-12-01', '{"REVISION_ROUNDS":2}'::jsonb, 14, :now, :lapses, 1, :now, :now)
				""")
			.param("id", this.job).param("product", product).param("owner", this.lan)
			.param("now", Timestamp.from(now)).param("lapses", Timestamp.from(now.plus(Duration.ofDays(14)))).update();
		this.hung = TestPeople.mentor(this.jdbc, "Đỗ Mạnh Hùng", List.of(TestPeople.REACT),
				"React");
		this.vy = member("Lê Thảo Vy");
		UUID application = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO matching.application (id, job_id, product_id, customer_account_id, mentor_account_id, status,
				    price_vnd, timeline_days, approach, public_code_notice_version, submitted_at)
				VALUES (:id, :job, :product, :lan, :hung, 'SUBMITTED', 800000, 7, 'Cách làm', '1.0', :now)
				""")
			.param("id", application).param("job", this.job).param("product", product).param("lan", this.lan)
			.param("hung", this.hung).param("now", Timestamp.from(now)).update();
		this.mvc.perform(post("/api/v1/jobs/" + this.job + "/applications/" + application + "/select")
			.with(as(this.lan, Role.CUSTOMER)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}")).andExpect(status().isOk());
		this.mvc.perform(post("/api/v1/mentor/jobs/" + this.job + "/nda").with(as(this.hung, Role.MENTOR)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"ndaVersion\":\"2.0\",\"commitEmailNoticeAcknowledged\":true}")).andExpect(status().isNoContent());
		save(0, "Sửa lỗi hai khách đặt trùng bàn.").andExpect(status().isOk());
		share(1).andExpect(status().isOk());
	}

	@Test
	void theCustomerReviewsAndTheMentorConfirmsWithNoCrewConditionListingOnlyTheMembersWhoSigned() throws Exception {
		confirm(jobVersion(), 0).andExpect(status().is(422)).andExpect(jsonPath("$.reason").value("AGREEMENT_NOT_APPROVED"));
		String customerView = this.mvc.perform(get("/api/v1/jobs/" + this.job + "/agreement").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.latestVersionNo").value(1))
			.andExpect(jsonPath("$.changeRequested").value(false))
			.andExpect(jsonPath("$.agreementStartedAt").exists())
			.andReturn().getResponse().getContentAsString();
		assertThat(customerView).doesNotContain("crewVersion").doesNotContain("percent");

		comment(1, "MILESTONE", MILESTONE).andExpect(status().isCreated()).andExpect(jsonPath("$.targetId").value(MILESTONE.toString()));
		comment(1, "SCOPE", null).andExpect(status().isCreated());
		comment(1, "CRITERION", UUID.randomUUID()).andExpect(status().isBadRequest());
		comment(2, "SCOPE", null).andExpect(status().isConflict());
		long version = jobVersion();
		changes(version - 1, 1).andExpect(status().isConflict());
		changes(version, 1).andExpect(status().isNoContent());
		assertThat(jobVersion()).isEqualTo(version + 1);
		assertThat(notifications("job.agreement_changes_requested", this.hung)).isOne();
		assertThat(openTodos("job.revise_agreement", this.hung)).isOne();
		assertThat(openTodos("job.review_agreement", this.lan)).isZero();
		approve(jobVersion(), 1, true, List.of()).andExpect(status().is(422)).andExpect(jsonPath("$.reason").value("CHANGES_REQUESTED"));
		this.mvc.perform(get("/api/v1/mentor/jobs/" + this.job + "/agreement").with(as(this.hung, Role.MENTOR)))
			.andExpect(jsonPath("$.review.comments.length()").value(2))
			.andExpect(jsonPath("$.review.changeRequest.note").value("Hãy thêm nhắc lịch"));

		save(1, "Sửa lỗi hai khách đặt trùng bàn, và nhắc lịch.").andExpect(status().isOk());
		share(2).andExpect(status().isOk());
		assertThat(openTodos("job.revise_agreement", this.hung)).isZero();
		assertThat(openTodos("job.review_agreement", this.lan)).isOne();
		approve(jobVersion() - 1, 2, true, List.of()).andExpect(status().isConflict());
		approve(jobVersion(), 2, false, List.of()).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reason").value("FREEZE_COMMITMENT_REQUIRED"));
		approve(jobVersion(), 2, true, List.of(UUID.randomUUID())).andExpect(status().isBadRequest());
		approve(jobVersion(), 2, true, List.of(ACCESS)).andExpect(status().isNoContent());
		approve(jobVersion(), 2, true, List.of()).andExpect(status().is(422)).andExpect(jsonPath("$.reason").value("AGREEMENT_APPROVED"));
		assertThat(notifications("job.agreement_awaiting_confirmation", this.hung)).isOne();
		assertThat(openTodos("job.confirm_agreement_mentor", this.hung)).isOne();

		confirmedCrew(this.vy, 0);
		UUID minh = member("Trần Quang Minh");
		picked(minh);
		long crewVersion = crewVersion();
		save(2, "Sửa lỗi hai khách đặt trùng bàn, và nhắc lịch trước 2 giờ.").andExpect(status().isOk());
		confirm(jobVersion(), crewVersion).andExpect(status().is(422)).andExpect(jsonPath("$.reason").value("DRAFT_CHANGED"));
		save(3, "Sửa lỗi hai khách đặt trùng bàn, và nhắc lịch.").andExpect(status().isOk());
		confirm(jobVersion() - 1, crewVersion).andExpect(status().isConflict());
		confirm(jobVersion(), crewVersion - 1).andExpect(status().isConflict());
		confirm(jobVersion(), crewVersion).andExpect(status().isOk())
			.andExpect(jsonPath("$.confirmed.versionNo").value(1))
			.andExpect(jsonPath("$.confirmed.draftVersionNo").value(2))
			.andExpect(jsonPath("$.confirmed.crew.length()").value(1))
			.andExpect(jsonPath("$.confirmed.crew[0]").value(TestPeople.login(this.jdbc, this.vy)))
			.andExpect(jsonPath("$.confirmed.liveAccessIds[0]").value(ACCESS.toString()));

		assertThat(this.jdbc.sql("SELECT agreement_version_no FROM job.job WHERE id = :id").param("id", this.job)
			.query(Integer.class).single()).as("the approval survived the Crew change (R-1 M5)").isOne();
		for (UUID account : List.of(this.lan, this.hung, this.vy)) {
			assertThat(notifications("job.agreement_confirmed", account)).isOne();
		}
		assertThat(notifications("job.milestone_awaiting_funding", this.lan)).as("R7: a member signed").isOne();
		await().atMost(Duration.ofSeconds(10)).until(() -> openTodos("payment.fund_milestone", this.lan) == 1);
		assertThat(openTodos("job.confirm_agreement_mentor", this.hung)).isZero();
		save(3, "Thêm").andExpect(status().is(422)).andExpect(jsonPath("$.reason").value("AGREEMENT_CONFIRMED"));
		comment(2, "SCOPE", null).andExpect(status().isConflict());
		String view = this.mvc.perform(get("/api/v1/jobs/" + this.job + "/agreement").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.confirmed.versionNo").value(1))
			.andExpect(jsonPath("$.approvedAt").exists())
			.andExpect(jsonPath("$.crew.length()").value(1))
			.andExpect(jsonPath("$.crew[0]").value(TestPeople.login(this.jdbc, this.vy)))
			.andReturn().getResponse().getContentAsString();
		assertThat(view).as("A-05: the Customer never learns of a pick").doesNotContain(TestPeople.login(this.jdbc, minh))
			.doesNotContain("crewConfirmed");
	}

	/** R7, Story 21.5: the confirmation waits for no member, and the confirmed version then lists no username. */
	@Test
	void theMentorConfirmsWithNoMemberSignedAndTheConfirmedVersionListsNoUsername() throws Exception {
		approve(jobVersion(), 1, true, List.of()).andExpect(status().isNoContent());

		confirm(jobVersion(), crewVersion()).andExpect(status().isOk())
			.andExpect(jsonPath("$.confirmed.crew.length()").value(0));

		assertThat(this.jdbc.sql("""
				SELECT params ->> 'crew' FROM notification.notification WHERE type = 'job.agreement_confirmed'
				    AND account_id = :lan
				""").param("lan", this.lan).query(String.class).single()).isEqualTo(AgreementReviews.NO_MEMBER_YET);
		this.mvc.perform(get("/api/v1/mentor/jobs/" + this.job + "/crew").with(as(this.hung, Role.MENTOR)))
			.andExpect(jsonPath("$.signedMember").value(false))
			.andExpect(jsonPath("$.members.length()").value(0));
	}

	/**
	 * R7: Milestone 1 asks the Customer for its funding only with a signed member: not at a confirmation with none, then
	 * at the first signature, once; a later signature adds nothing.
	 */
	@Test
	void milestoneOneAsksForFundingAtTheFirstSignatureAfterAConfirmationWithNoMember() throws Exception {
		approve(jobVersion(), 1, true, List.of()).andExpect(status().isNoContent());
		confirm(jobVersion(), crewVersion()).andExpect(status().isOk());
		await().atMost(Duration.ofSeconds(10)).until(() -> ledgerAccounts() == 1);
		Thread.sleep(300);
		assertThat(notifications("job.milestone_awaiting_funding", this.lan)).as("nobody signed yet").isZero();
		assertThat(openTodos("payment.fund_milestone", this.lan)).as("nobody signed yet").isZero();

		activate(picked(this.vy));

		assertThat(notifications("job.milestone_awaiting_funding", this.lan)).isOne();
		await().atMost(Duration.ofSeconds(10)).until(() -> openTodos("payment.fund_milestone", this.lan) == 1);
		assertThat(this.jdbc.sql("""
				SELECT params ->> 'n' FROM notification.todo WHERE todo_key = 'payment.fund_milestone' AND account_id = :lan
				""").param("lan", this.lan).query(String.class).single()).isEqualTo("1");

		activate(picked(member("Trần Quang Minh")));
		Thread.sleep(300);

		assertThat(notifications("job.milestone_awaiting_funding", this.lan)).as("a later signature adds nothing").isOne();
		assertThat(openTodos("payment.fund_milestone", this.lan)).as("a later signature adds nothing").isOne();
		assertThat(this.jdbc.sql("SELECT count(*) FROM notification.todo WHERE todo_key = 'payment.fund_milestone'")
			.query(Long.class).single()).isOne();
	}

	/** R7: a signature before the confirmation asks for nothing; the confirmation does, with that member. */
	@Test
	void milestoneOneAsksForFundingAtAConfirmationWithAMember() throws Exception {
		activate(picked(this.vy));
		Thread.sleep(300);
		assertThat(notifications("job.milestone_awaiting_funding", this.lan)).isZero();
		assertThat(openTodos("payment.fund_milestone", this.lan)).isZero();
		approve(jobVersion(), 1, true, List.of()).andExpect(status().isNoContent());

		confirm(jobVersion(), crewVersion()).andExpect(status().isOk());

		assertThat(notifications("job.milestone_awaiting_funding", this.lan)).isOne();
		await().atMost(Duration.ofSeconds(10)).until(() -> openTodos("payment.fund_milestone", this.lan) == 1);
	}

	/**
	 * Story 20.4: a lock counts as leaving, so the member's row is released as {@code LEFT}, its window ending at the
	 * lock's own time even though the listener runs five minutes later. Story 21.5: nothing is re-confirmed; the Crew
	 * is simply empty until someone signs, the Mentor's Crew list says so, and the Mentor is asked again to recruit (R7).
	 */
	@Test
	void aLockAfterTheConfirmationReleasesTheMemberAndTheCrewIsEmptyUntilSomeoneSigns() throws Exception {
		confirmedCrew(this.vy, 0);
		approve(jobVersion(), 1, true, List.of()).andExpect(status().isNoContent());
		confirm(jobVersion(), crewVersion()).andExpect(status().isOk());
		closeRecruitmentTodo();
		Instant lockedAt = this.clock.instant();
		AccountLocked locked = new AccountLocked(UUID.randomUUID(), this.vy, lockedAt);
		this.clock.advance(Duration.ofMinutes(5));

		new TransactionTemplate(this.transactions).executeWithoutResult(status -> this.events.publishEvent(locked));
		await().atMost(Duration.ofSeconds(10)).until(() -> notifications("job.crew_member_left", this.hung) == 1);
		new TransactionTemplate(this.transactions).executeWithoutResult(status -> this.events.publishEvent(locked));
		Thread.sleep(300);

		assertThat(notifications("job.crew_member_left", this.hung)).as("a replay changes nothing").isOne();
		assertThat(notifications("job.crew_login_removed", this.lan)).isOne();
		assertThat(this.jdbc.sql("""
				SELECT status || ' ' || released_reason FROM job.crew_row WHERE member_account_id = :vy
				""").param("vy", this.vy).query(String.class).single()).isEqualTo("RELEASED LEFT");
		assertThat(this.jdbc.sql("SELECT window_end FROM job.crew_row WHERE member_account_id = :vy").param("vy", this.vy)
			.query(Timestamp.class).single().toInstant()).as("the lock's time, not the listener's").isEqualTo(lockedAt);
		assertThat(notifications("job.crew_empty", this.lan)).as("the Customer is told only after Starting").isZero();
		this.mvc.perform(get("/api/v1/mentor/jobs/" + this.job + "/crew").with(as(this.hung, Role.MENTOR)))
			.andExpect(jsonPath("$.signedMember").value(false))
			.andExpect(jsonPath("$.members.length()").value(0));
		await().atMost(Duration.ofSeconds(10)).until(() -> openTodos("matching.open_crew_recruitment", this.hung) == 1);
		assertThat(this.jdbc.sql("""
				SELECT t.deadline_at = j.agreement_due_at FROM notification.todo t JOIN job.job j ON j.id = t.job_id
				WHERE t.todo_key = 'matching.open_crew_recruitment' AND t.closed_at IS NULL
				""").query(Boolean.class).single()).as("until the Agreement deadline").isTrue();
	}

	/**
	 * Story 20.4: a lock of the only member before the Agreement is confirmed releases the row; the Customer hears
	 * nothing before J6, and no Dispute opens. Story 21.5 (R7): the Mentor is asked again to open recruitment.
	 */
	@Test
	void aLockBeforeTheConfirmationReleasesTheRowAndAsksTheMentorToRecruitAgain() throws Exception {
		confirmedCrew(this.vy, 0);
		closeRecruitmentTodo();
		AccountLocked locked = new AccountLocked(UUID.randomUUID(), this.vy, this.clock.instant());

		new TransactionTemplate(this.transactions).executeWithoutResult(status -> this.events.publishEvent(locked));
		await().atMost(Duration.ofSeconds(10)).until(() -> notifications("job.crew_member_left", this.hung) == 1);
		await().atMost(Duration.ofSeconds(10)).until(() -> this.jdbc.sql(
				"SELECT count(*) FROM platform.event_publication WHERE completion_date IS NULL").query(Long.class).single() == 0);

		assertThat(this.jdbc.sql("""
				SELECT status || ' ' || released_reason FROM job.crew_row WHERE member_account_id = :vy
				""").param("vy", this.vy).query(String.class).single()).isEqualTo("RELEASED LEFT");
		assertThat(openTodos("matching.open_crew_recruitment", this.hung)).as("R7: recruit again").isOne();
		assertThat(notifications("job.crew_empty", this.lan)).isZero();
		assertThat(this.jdbc.sql("SELECT count(*) FROM dispute.dispute WHERE job_id = :job").param("job", this.job)
			.query(Long.class).single()).isZero();
	}

	@Test
	void afterTheConfirmationTheCustomerSeesEachPersonsOwnAccountNextToTheItemAndTicksItGranted() throws Exception {
		confirmedCrew(this.vy, 0);
		approve(jobVersion(), 1, true, List.of()).andExpect(status().isNoContent());
		external(this.lan, Role.CUSTOMER).andExpect(status().isNotFound());
		confirm(jobVersion(), crewVersion()).andExpect(status().isOk());
		assertThat(openTodos(ExternalAccessGrants.TODO, this.lan)).as("N-8, one per item").isOne();

		identifier(this.hung, Role.MENTOR, "  hungdm.work@gmail.com ").andExpect(status().isOk())
			.andExpect(jsonPath("$.viewer").value("MENTOR"))
			.andExpect(jsonPath("$.items[0].mine").value("hungdm.work@gmail.com"))
			.andExpect(jsonPath("$.items[0].identifiers.length()").value(0))
			.andExpect(jsonPath("$.items[0].granted").doesNotExist());
		identifier(this.vy, Role.STUDENT, "vy.le.dev@gmail.com").andExpect(status().isOk());
		// A fake key, built at run time: the field holds accounts, never credentials.
		String key = "sk" + "_live_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
		identifier(this.vy, Role.STUDENT, key).andExpect(status().is(422))
			.andExpect(jsonPath("$.reason").value("IDENTIFIER_LOOKS_LIKE_SECRET"));
		identifier(this.vy, Role.STUDENT, "vy\\nle").andExpect(status().isBadRequest());
		identifier(this.lan, Role.CUSTOMER, "lan@gmail.com").andExpect(status().isNotFound());
		UUID minh = member("Trần Quang Minh");
		picked(minh);
		// AD-41: a pick not yet signed reads none of it.
		identifier(minh, Role.STUDENT, "minh@gmail.com").andExpect(status().isNotFound());
		external(minh, Role.STUDENT).andExpect(status().isNotFound());

		String customerView = external(this.lan, Role.CUSTOMER).andExpect(status().isOk())
			.andExpect(jsonPath("$.viewer").value("CUSTOMER"))
			.andExpect(jsonPath("$.open").value(true))
			.andExpect(jsonPath("$.items[0].system").value("Supabase"))
			.andExpect(jsonPath("$.items[0].environment").value("NON_PRODUCTION"))
			.andExpect(jsonPath("$.items[0].live").value(false))
			.andExpect(jsonPath("$.items[0].granted").value(false))
			.andExpect(jsonPath("$.items[0].identifiers[0].githubLogin").value(TestPeople.login(this.jdbc, this.hung)))
			.andExpect(jsonPath("$.items[0].identifiers[0].identifier").value("hungdm.work@gmail.com"))
			.andExpect(jsonPath("$.items[0].identifiers[1].githubLogin").value(TestPeople.login(this.jdbc, this.vy)))
			.andExpect(jsonPath("$.items[0].identifiers[1].identifier").value("vy.le.dev@gmail.com"))
			.andReturn().getResponse().getContentAsString();
		assertThat(customerView).doesNotContain("Lê Thảo Vy");
		external(this.vy, Role.STUDENT).andExpect(jsonPath("$.items[0].mine").value("vy.le.dev@gmail.com"))
			.andExpect(jsonPath("$.items[0].identifiers.length()").value(0));
		assertThat(this.mvc.perform(get("/api/v1/jobs/" + this.job + "/agreement").with(as(this.lan, Role.CUSTOMER)))
			.andReturn().getResponse().getContentAsString()).as("no other surface returns it").doesNotContain("hungdm.work");

		granted(this.hung, Role.MENTOR).andExpect(status().isNotFound());
		granted(this.lan, Role.CUSTOMER).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].granted").value(true));
		granted(this.lan, Role.CUSTOMER).andExpect(status().isOk());
		assertThat(openTodos(ExternalAccessGrants.TODO, this.lan)).isZero();

		this.jdbc.sql("UPDATE job.job SET state = 'CANCELLED' WHERE id = :id").param("id", this.job).update();
		external(this.lan, Role.CUSTOMER).andExpect(jsonPath("$.open").value(false))
			.andExpect(jsonPath("$.items[0].identifiers.length()").value(0));
		external(this.hung, Role.MENTOR).andExpect(jsonPath("$.items[0].mine").doesNotExist());
		identifier(this.hung, Role.MENTOR, "hung@gmail.com").andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("EXTERNAL_ACCESS_CLOSED"));
	}

	private ResultActions external(UUID account, Role role) throws Exception {
		return this.mvc.perform(get("/api/v1/jobs/" + this.job + "/external-access").with(as(account, role)));
	}

	private ResultActions identifier(UUID account, Role role, String identifier) throws Exception {
		return this.mvc.perform(put("/api/v1/jobs/" + this.job + "/external-access/" + ACCESS + "/identifier")
			.with(as(account, role)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"identifier\":\"" + identifier + "\"}"));
	}

	private ResultActions granted(UUID account, Role role) throws Exception {
		return this.mvc.perform(post("/api/v1/jobs/" + this.job + "/external-access/" + ACCESS + "/granted")
			.with(as(account, role)).with(csrf()));
	}

	/** A member who signed (Story 21.5): an {@code ACTIVE} row, as {@code job.activateCrew} leaves it. */
	private void confirmedCrew(UUID member, long crewVersion) {
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO job.crew_row (id, job_id, member_account_id, status, signed_at, activated_at, created_at)
				VALUES (:id, :job, :member, 'ACTIVE', :now, :now, :now)
				""").param("id", UUID.randomUUID()).param("job", this.job).param("member", member).param("now", now).update();
		this.jdbc.sql("UPDATE job.job SET crew_version = :next WHERE id = :id").param("next", crewVersion + 1)
			.param("id", this.job).update();
	}

	/** J4's "Mở tuyển thành viên nhóm", closed as a member's signature closes it (Story 21.5). */
	private void closeRecruitmentTodo() {
		this.jdbc.sql("""
				UPDATE notification.todo SET closed_at = :now WHERE todo_key = 'matching.open_crew_recruitment' AND closed_at IS NULL
				""").param("now", Timestamp.from(this.clock.instant())).update();
	}

	/** A pick not yet signed: an {@code ASSIGNED} row, as {@code job.addCrew} leaves it. */
	private UUID picked(UUID member) {
		Timestamp now = Timestamp.from(this.clock.instant());
		UUID row = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO job.crew_row (id, job_id, member_account_id, status, crew_application_id, created_at)
				VALUES (:id, :job, :member, 'ASSIGNED', :application, :now)
				""").param("id", row).param("job", this.job).param("member", member)
			.param("application", UUID.randomUUID()).param("now", now).update();
		return row;
	}

	/** {@code job.activateCrew}, as the signature calls it. */
	private void activate(UUID row) {
		new TransactionTemplate(this.transactions).executeWithoutResult(
				status -> this.picks.activateCrew(this.job, row, CrewPicks.Contribution.NONE, this.clock.instant()));
	}

	private long ledgerAccounts() {
		return this.jdbc.sql("SELECT count(*) FROM payment.ledger_account WHERE job_id = :job").param("job", this.job)
			.query(Long.class).single();
	}

	private ResultActions save(int revision, String scope) throws Exception {
		return this.mvc.perform(put("/api/v1/mentor/jobs/" + this.job + "/agreement").with(as(this.hung, Role.MENTOR)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"revision":%d,"scope":"%s","priceVnd":800000,"skillIds":[],
					 "milestones":[{"id":"%s","title":"Sửa lỗi trùng giờ","priceVnd":800000,"dueOn":"2026-11-20","revisionRounds":2,
					   "criteria":[{"id":"%s","statement":"Hai khách không đặt trùng bàn","checkSteps":"1. Mở. 2. Đặt."}]}],
					 "externalAccess":[{"id":"%s","system":"Supabase","purpose":"Đọc bảng đặt bàn","environment":"NON_PRODUCTION",
					   "accessLevel":"READ"}]}
					""".formatted(revision, scope, MILESTONE, CRITERION, ACCESS)));
	}

	private ResultActions share(int revision) throws Exception {
		return this.mvc.perform(post("/api/v1/mentor/jobs/" + this.job + "/agreement/share").with(as(this.hung, Role.MENTOR))
			.with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"revision\":" + revision + "}"));
	}

	private ResultActions comment(int versionNo, String kind, UUID target) throws Exception {
		return this.mvc.perform(post("/api/v1/jobs/" + this.job + "/agreement/comments").with(as(this.lan, Role.CUSTOMER)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"versionNo\":%d,\"targetKind\":\"%s\",%s\"body\":\"Giá này hơi cao\"}".formatted(versionNo, kind,
					target == null ? "" : "\"targetId\":\"" + target + "\",")));
	}

	private ResultActions changes(long version, int versionNo) throws Exception {
		return this.mvc.perform(post("/api/v1/jobs/" + this.job + "/agreement/changes").with(as(this.lan, Role.CUSTOMER)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"version\":%d,\"versionNo\":%d,\"note\":\"Hãy thêm nhắc lịch\",\"milestoneOrder\":[\"%s\"]}"
				.formatted(version, versionNo, MILESTONE)));
	}

	private ResultActions approve(long version, int versionNo, boolean freeze, List<UUID> live) throws Exception {
		return this.mvc.perform(post("/api/v1/jobs/" + this.job + "/agreement/approve").with(as(this.lan, Role.CUSTOMER)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"version\":%d,\"versionNo\":%d,\"freezeCommitment\":%s,\"liveAccessIds\":[%s]}".formatted(version, versionNo,
					freeze, String.join(",", live.stream().map(id -> "\"" + id + "\"").toList()))));
	}

	private ResultActions confirm(long version, long crewVersion) throws Exception {
		return this.mvc.perform(post("/api/v1/mentor/jobs/" + this.job + "/agreement/confirm").with(as(this.hung, Role.MENTOR))
			.with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"version\":%d,\"crewVersion\":%d}".formatted(version, crewVersion)));
	}

	private UUID member(String name) {
		return TestPeople.student(this.jdbc, name, true, List.of(TestPeople.REACT), "React",
				LocalDate.parse("2026-09-30"));
	}

	private long jobVersion() {
		return this.jdbc.sql("SELECT version FROM job.job WHERE id = :id").param("id", this.job).query(Long.class).single();
	}

	private long crewVersion() {
		return this.jdbc.sql("SELECT crew_version FROM job.job WHERE id = :id").param("id", this.job).query(Long.class).single();
	}

	private long notifications(String type, UUID account) {
		return this.jdbc.sql("SELECT count(*) FROM notification.notification WHERE type = :type AND account_id = :account")
			.param("type", type).param("account", account).query(Long.class).single();
	}

	private long openTodos(String key, UUID account) {
		return this.jdbc.sql("""
				SELECT count(*) FROM notification.todo WHERE todo_key = :key AND account_id = :account AND closed_at IS NULL
				""").param("key", key).param("account", account).query(Long.class).single();
	}

	private UUID product(UUID owner) {
		UUID id = UUID.randomUUID();
		Timestamp now = Timestamp.from(MutableClock.DEFAULT_INSTANT);
		this.jdbc.sql("""
				INSERT INTO product.product (id, customer_account_id, name, description, ai_tools, repository_github_id,
				    repository_full_name, repository_owner_type, repository_default_branch, public_notice_version,
				    copy_permission_version, detection_notice_version, acknowledged_at, created_at)
				VALUES (:id, :owner, 'Sổ đặt bàn Quán Mây', 'Đặt bàn.', ARRAY['LOVABLE'], :github,
				    'lan/so-dat-ban', 'User', 'main', '1.0', '1.0', '1.0', :now, :now)
				""")
			.param("id", id).param("owner", owner)
			.param("github", ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE)).param("now", now).update();
		return id;
	}

	private RequestPostProcessor as(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

	/** GitHub answering that the Source Repository has no contributor yet. */
	private static HttpServer start() {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/", exchange -> {
				byte[] body = (exchange.getRequestURI().getPath().endsWith("/contributors") ? "[]" : "{}")
					.getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().add("Content-Type", "application/json");
				exchange.sendResponseHeaders(exchange.getRequestURI().getPath().endsWith("/contributors") ? 200 : 404, body.length);
				try (OutputStream out = exchange.getResponseBody()) {
					out.write(body);
				}
			});
			server.start();
			return server;
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
