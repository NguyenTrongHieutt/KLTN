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
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.MutableClock;
import vn.vibefix.team.internal.TeamTestSupport;

/**
 * FR-16, FR-17, AD-29, AD-41 (R-1): the Customer's review, the Mentor's confirmation, and a lock after it;
 * then FR-66's External Access guidance with each person's own account (Story 8.1).
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

	private UUID lan;

	private UUID hung;

	private UUID team;

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
		this.hung = TeamTestSupport.mentor(this.jdbc, "Đỗ Mạnh Hùng", List.of(TeamTestSupport.REACT),
				"React");
		this.team = UUID.randomUUID();
		this.jdbc.sql("INSERT INTO team.team (id, mentor_account_id, status, created_at) VALUES (:id, :mentor, 'OPEN', now())")
			.param("id", this.team).param("mentor", this.hung).update();
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
			.content("{\"ndaVersion\":\"1.0\",\"commitEmailNoticeAcknowledged\":true}")).andExpect(status().isNoContent());
		save(0, "Sửa lỗi hai khách đặt trùng bàn.").andExpect(status().isOk());
		share(1).andExpect(status().isOk());
	}

	@Test
	void theCustomerReviewsAndTheMentorConfirmsOnceTheCrewIs() throws Exception {
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

		confirm(jobVersion(), 0).andExpect(status().is(422)).andExpect(jsonPath("$.reason").value("CREW_NOT_CONFIRMED"));
		confirmedCrew(this.vy, 0);
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
			.andExpect(jsonPath("$.confirmed.liveAccessIds[0]").value(ACCESS.toString()));

		assertThat(this.jdbc.sql("SELECT agreement_version_no FROM job.job WHERE id = :id").param("id", this.job)
			.query(Integer.class).single()).as("the approval survived the Crew change (R-1 M5)").isOne();
		for (UUID account : List.of(this.lan, this.hung, this.vy)) {
			assertThat(notifications("job.agreement_confirmed", account)).isOne();
		}
		assertThat(notifications("job.milestone_awaiting_funding", this.lan)).isOne();
		assertThat(openTodos("job.confirm_agreement_mentor", this.hung) + openTodos("job.pick_crew", this.hung)).isZero();
		save(3, "Thêm").andExpect(status().is(422)).andExpect(jsonPath("$.reason").value("AGREEMENT_CONFIRMED"));
		add(member("Trần Quang Minh"), crewVersion()).andExpect(status().is(422)).andExpect(jsonPath("$.reason").value("CREW_LOCKED"));
		comment(2, "SCOPE", null).andExpect(status().isConflict());
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/agreement").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.confirmed.versionNo").value(1))
			.andExpect(jsonPath("$.approvedAt").exists())
			.andExpect(jsonPath("$.crewConfirmed").value(true));
	}

	/**
	 * Story 20.4: a lock counts as leaving, so the member's row is released as {@code LEFT}, its window ending at the
	 * lock's own time even though the listener runs five minutes later; the Crew owes a re-confirmation, and nobody
	 * is left to remove before it.
	 */
	@Test
	void aLockAfterTheConfirmationReleasesTheMemberAndOwesAReconfirmation() throws Exception {
		confirmedCrew(this.vy, 0);
		approve(jobVersion(), 1, true, List.of()).andExpect(status().isNoContent());
		confirm(jobVersion(), crewVersion()).andExpect(status().isOk());
		Instant lockedAt = this.clock.instant();
		AccountLocked locked = new AccountLocked(UUID.randomUUID(), this.vy, lockedAt);
		this.clock.advance(Duration.ofMinutes(5));

		new TransactionTemplate(this.transactions).executeWithoutResult(status -> this.events.publishEvent(locked));
		await().atMost(Duration.ofSeconds(10)).until(() -> notifications("job.crew_reconfirm_needed", this.hung) == 1);
		new TransactionTemplate(this.transactions).executeWithoutResult(status -> this.events.publishEvent(locked));
		Thread.sleep(300);

		assertThat(notifications("job.crew_reconfirm_needed", this.hung)).as("a replay changes nothing").isOne();
		assertThat(notifications("job.crew_member_left", this.hung)).isOne();
		assertThat(notifications("job.crew_login_removed", this.lan)).isOne();
		assertThat(this.jdbc.sql("""
				SELECT status || ' ' || released_reason FROM job.crew_row WHERE member_account_id = :vy
				""").param("vy", this.vy).query(String.class).single()).isEqualTo("RELEASED LEFT");
		assertThat(this.jdbc.sql("SELECT window_end FROM job.crew_row WHERE member_account_id = :vy").param("vy", this.vy)
			.query(Timestamp.class).single().toInstant()).as("the lock's time, not the listener's").isEqualTo(lockedAt);
		assertThat(openTodos("job.pick_crew", this.hung)).as("no longer opened (Story 21.4)").isZero();
		assertThat(notifications("job.crew_empty", this.lan)).as("no Crew empties before J6").isZero();
		this.mvc.perform(get("/api/v1/mentor/jobs/" + this.job + "/crew").with(as(this.hung, Role.MENTOR)))
			.andExpect(jsonPath("$.reconfirmOwed").value(true))
			.andExpect(jsonPath("$.locked").value(false))
			.andExpect(jsonPath("$.rows[0].status").value("RELEASED"))
			.andExpect(jsonPath("$.rows[0].releasedReason").value("LEFT"));
		reconfirm(crewVersion()).andExpect(status().is(422)).andExpect(jsonPath("$.reason").value("CREW_NOT_CONFIRMED"));

		confirmedCrew(member("Trần Quang Minh"), crewVersion());
		reconfirm(crewVersion() - 1).andExpect(status().isConflict());
		reconfirm(crewVersion()).andExpect(status().isOk())
			.andExpect(jsonPath("$.reconfirmOwed").value(false))
			.andExpect(jsonPath("$.locked").value(true));
		add(member("Phạm Hải Tùng"), crewVersion()).andExpect(status().is(422)).andExpect(jsonPath("$.reason").value("CREW_LOCKED"));
		reconfirm(crewVersion()).andExpect(status().isConflict());
	}

	/**
	 * Story 20.4: a lock of the only confirmed member before the Agreement is confirmed releases the row; nothing empties
	 * before J6, and no Dispute opens. "Chọn nhóm thực hiện" no longer opens (Story 21.4).
	 */
	@Test
	void aLockBeforeTheConfirmationReleasesTheRowAndOpensNoPickToDo() throws Exception {
		confirmedCrew(this.vy, 0);
		assertThat(openTodos("job.pick_crew", this.hung)).as("the Crew condition holds").isZero();
		AccountLocked locked = new AccountLocked(UUID.randomUUID(), this.vy, this.clock.instant());

		new TransactionTemplate(this.transactions).executeWithoutResult(status -> this.events.publishEvent(locked));
		await().atMost(Duration.ofSeconds(10)).until(() -> notifications("job.crew_member_left", this.hung) == 1);
		await().atMost(Duration.ofSeconds(10)).until(() -> this.jdbc.sql(
				"SELECT count(*) FROM platform.event_publication WHERE completion_date IS NULL").query(Long.class).single() == 0);

		assertThat(this.jdbc.sql("""
				SELECT status || ' ' || released_reason FROM job.crew_row WHERE member_account_id = :vy
				""").param("vy", this.vy).query(String.class).single()).isEqualTo("RELEASED LEFT");
		assertThat(openTodos("job.pick_crew", this.hung)).as("no longer opened (Story 21.4)").isZero();
		assertThat(notifications("job.crew_reconfirm_needed", this.hung)).as("nothing was confirmed yet").isZero();
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
		identifier(member("Trần Quang Minh"), Role.STUDENT, "minh@gmail.com").andExpect(status().isNotFound());

		String customerView = external(this.lan, Role.CUSTOMER).andExpect(status().isOk())
			.andExpect(jsonPath("$.viewer").value("CUSTOMER"))
			.andExpect(jsonPath("$.open").value(true))
			.andExpect(jsonPath("$.items[0].system").value("Supabase"))
			.andExpect(jsonPath("$.items[0].environment").value("NON_PRODUCTION"))
			.andExpect(jsonPath("$.items[0].live").value(false))
			.andExpect(jsonPath("$.items[0].granted").value(false))
			.andExpect(jsonPath("$.items[0].identifiers[0].githubLogin").value(TeamTestSupport.login(this.jdbc, this.hung)))
			.andExpect(jsonPath("$.items[0].identifiers[0].identifier").value("hungdm.work@gmail.com"))
			.andExpect(jsonPath("$.items[0].identifiers[1].githubLogin").value(TeamTestSupport.login(this.jdbc, this.vy)))
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

	/** Adds the Student, saves 20/80, and lets them sign and confirm. */
	private void confirmedCrew(UUID student, long crewVersion) throws Exception {
		add(student, crewVersion).andExpect(status().isOk());
		this.mvc.perform(put("/api/v1/mentor/jobs/" + this.job + "/crew/terms").with(as(this.hung, Role.MENTOR)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"crewVersion":%d,"shares":[{"accountId":"%s","percent":20},{"accountId":"%s","percent":80}]}
					""".formatted(crewVersion + 1, this.hung, student))).andExpect(status().isOk());
		int termsVersion = this.jdbc.sql("SELECT max(version_no) FROM job.crew_terms_version WHERE job_id = :job")
			.param("job", this.job).query(Integer.class).single();
		this.mvc.perform(post("/api/v1/crew/jobs/" + this.job + "/confirm").with(as(student, Role.STUDENT)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"crewTermsVersionNo\":%d,\"ndaVersion\":\"1.0\",\"publicCodeAcknowledged\":true,\"commitEmailNoticeAcknowledged\":true}"
				.formatted(termsVersion))).andExpect(status().isNoContent());
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

	private ResultActions reconfirm(long crewVersion) throws Exception {
		return this.mvc.perform(post("/api/v1/mentor/jobs/" + this.job + "/crew/reconfirm").with(as(this.hung, Role.MENTOR))
			.with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"crewVersion\":" + crewVersion + "}"));
	}

	private ResultActions add(UUID student, long crewVersion) throws Exception {
		return this.mvc.perform(post("/api/v1/mentor/jobs/" + this.job + "/crew").with(as(this.hung, Role.MENTOR)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"studentId\":\"" + student + "\",\"crewVersion\":" + crewVersion + "}"));
	}

	private UUID member(String name) {
		UUID student = TeamTestSupport.student(this.jdbc, name, true, List.of(TeamTestSupport.REACT), "React",
				LocalDate.parse("2026-09-30"));
		this.jdbc.sql("INSERT INTO team.membership (id, team_id, student_account_id, joined_at) VALUES (:id, :team, :student, now())")
			.param("id", UUID.randomUUID()).param("team", this.team).param("student", student).update();
		return student;
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
