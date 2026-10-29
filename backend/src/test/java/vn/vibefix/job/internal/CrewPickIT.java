package vn.vibefix.job.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;

import vn.vibefix.authz.JobContent;
import vn.vibefix.identity.AccountLocked;
import vn.vibefix.identity.internal.CustomerTokens;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.job.CrewPicks;
import vn.vibefix.job.CrewPicks.Contribution;
import vn.vibefix.job.internal.JobLifecycle.Actor;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.TodoRequest;
import vn.vibefix.notification.Todos;
import vn.vibefix.shared.ApiException;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.team.internal.TeamTestSupport;
import vn.vibefix.timers.internal.DeadlinePoller;

/**
 * Story 21.5 (FR-75, FR-81, AD-3, AD-4, AD-7, AD-41, AD-43, R4, A-03, A-04, A-16, SG-51), with {@code features.largeCrews}
 * on (a Crew of up to five): the Mentor of an Agreement Job picks a Crew Application from JOB-9, {@code job.addCrew}
 * taking the Job row lock first and the contributor read running before it; the picked person reads the assignment
 * projection and the Commitment panel only, signs and joins, or declines; the 3 days pass, the Mentor withdraws the
 * pick, a lock or J8 ends it; and nobody who signed is removed.
 */
@TestPropertySource(properties = "vibefix.features.large-crews=true")
class CrewPickIT extends IntegrationTest {

	private static final String TOKEN = "gho_customer_token_never_printed";

	private static final String CONTRIBUTORS_PATH = "/repos/lan/so-dat-ban/contributors";

	private static final HttpServer GITHUB = start();

	/** What the stub answers for each path: a status and a body; any other contributors read answers none. */
	private static final Map<String, String> ANSWERS = new ConcurrentHashMap<>();

	/** Runs on the stub's thread before it answers a contributors read. */
	private static final AtomicReference<Runnable> BEFORE_CONTRIBUTORS = new AtomicReference<>();

	private static final UUID MILESTONE = UUID.fromString("00000000-0000-7000-8000-00000000b001");

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
	private JobLifecycle lifecycle;

	@Autowired
	private CrewPicks picks;

	@Autowired
	private DeadlinePoller poller;

	@Autowired
	private Todos todos;

	private UUID lan;

	private UUID hung;

	private UUID product;

	private UUID job;

	private UUID minh;

	@DynamicPropertySource
	static void stubGitHub(DynamicPropertyRegistry registry) {
		registry.add("vibefix.github.api-url", () -> "http://127.0.0.1:" + GITHUB.getAddress().getPort());
	}

	@BeforeEach
	void anAgreementJobWhoseMentorRecruits() throws Exception {
		ANSWERS.clear();
		BEFORE_CONTRIBUTORS.set(null);
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		CustomerTokens.keep(this.context, this.lan, TOKEN);
		this.hung = TeamTestSupport.mentor(this.jdbc, "Đỗ Mạnh Hùng", List.of(TeamTestSupport.REACT), "React");
		this.product = product(this.lan, "lan/so-dat-ban");
		this.job = JobContent.job(this.jdbc, this.product, this.lan, this.hung, "Sửa lỗi đặt bàn", JobContent.SIGNED,
				"AGREEMENT");
		JobContent.selectedBid(this.jdbc, this.job, this.product, this.lan, this.hung, JobContent.SIGNED);
		JobContent.confirmedAgreement(this.jdbc, this.job, this.lan, this.hung, false, MILESTONE, "Sửa lỗi trùng giờ", 800000,
				JobContent.SIGNED);
		this.minh = person("Trần Quang Minh");
	}

	@AfterEach
	void forget() {
		ANSWERS.clear();
		BEFORE_CONTRIBUTORS.set(null);
	}

	@Test
	void aPickAddsAnAssignedRowPicksTheApplicationAndTurnsAFullRecruitmentOff() throws Exception {
		recruit(this.job, this.hung, 1).andExpect(status().isOk());
		UUID application = apply(this.minh, this.job);
		UUID tuan = person("Phạm Anh Tuấn");
		UUID other = apply(tuan, this.job);
		long version = jobVersion();
		long crewVersion = crewVersion();
		Instant now = this.clock.instant();

		pick(this.hung, this.job, application).andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(application.toString()))
			.andExpect(jsonPath("$.status").value("PICKED"))
			.andExpect(jsonPath("$.signDueAt").value(now.plus(Duration.ofDays(3)).toString()));

		assertThat(text("""
				SELECT status || ' ' || (crew_application_id = :application) FROM job.crew_row
				WHERE job_id = :job AND member_account_id = :minh
				""", Map.of("job", this.job, "minh", this.minh, "application", application))).isEqualTo("ASSIGNED true");
		assertThat(text("""
				SELECT a.status || ' ' || (a.crew_row_id = r.id) FROM matching.crew_application a JOIN job.crew_row r
				    ON r.crew_application_id = a.id WHERE a.id = :id
				""", Map.of("id", application))).isEqualTo("PICKED true");
		assertThat(crewVersion()).isGreaterThan(crewVersion);
		assertThat(jobVersion()).as("the Customer never learns of a pick (A-05)").isEqualTo(version);
		assertThat(count("SELECT count(*) FROM timers.deadline WHERE kind = 'matching.commitment_due' AND subject_id = :id",
				Map.of("id", application))).isOne();
		assertThat(text("""
				SELECT params ->> 'mentor' FROM notification.notification
				WHERE type = 'matching.crew_application_picked' AND account_id = :minh
				""", Map.of("minh", this.minh))).isEqualTo("Đỗ Mạnh Hùng");
		assertThat(text("""
				SELECT role FROM notification.todo WHERE todo_key = 'matching.sign_commitment' AND account_id = :minh
				    AND closed_at IS NULL
				""", Map.of("minh", this.minh))).isEqualTo("CREW");
		assertThat(text("SELECT recruiting || ' ' || open_slots FROM job.job WHERE id = :id", Map.of("id", this.job)))
			.as("the one place is taken, so recruitment turned off by itself").isEqualTo("false 0");
		await().atMost(Duration.ofSeconds(10))
			.until(() -> "LAPSED RECRUITMENT_CLOSED".equals(applicationState(other)));
		assertThat(count("SELECT count(*) FROM audit.audit_entry WHERE action = 'job.recruitment_filled' AND job_id = :job",
				Map.of("job", this.job))).isOne();

		pick(this.hung, this.job, application).andExpect(status().isConflict());
		this.mvc.perform(get("/api/v1/mentor/jobs/" + this.job + "/crew").with(as(this.hung)))
			.andExpect(jsonPath("$.members.length()").value(0))
			.andExpect(jsonPath("$.waiting").value(1));
		awaitListeners();
	}

	@Test
	void theMentorReadsOnlyTheNeutralReasonForAPersonOnAnotherCrewOrLocked() throws Exception {
		recruit(this.job, this.hung, 2).andExpect(status().isOk());
		UUID application = apply(this.minh, this.job);
		UUID tuan = person("Phạm Anh Tuấn");
		UUID locked = apply(tuan, this.job);
		UUID elsewhere = JobContent.activeJob(this.jdbc, product(person("Khách khác"), "khach/khac"), this.lan,
				person("Người hướng dẫn khác"), "Việc khác");
		JobContent.crewRow(this.jdbc, elsewhere, this.minh, "ACTIVE", null);
		IdentityTestSupport.lock(this.jdbc, tuan, "Vi phạm quy tắc");

		String busy = pick(this.hung, this.job, application).andExpect(status().is(422))
			.andExpect(jsonPath("$.code").value("GUARD_FAILED"))
			.andExpect(jsonPath("$.reason").value("APPLICANT_UNAVAILABLE"))
			.andReturn().getResponse().getContentAsString();
		pick(this.hung, this.job, locked).andExpect(status().is(422))
			.andExpect(jsonPath("$.reason").value("APPLICANT_UNAVAILABLE"));

		assertThat(busy).as("A-16: never the other Job").doesNotContain("Việc khác").doesNotContain("ON_ANOTHER_CREW");
		assertThat(count("SELECT count(*) FROM job.crew_row WHERE job_id = :job", Map.of("job", this.job))).isZero();
		assertThat(applicationState(application)).isEqualTo("PENDING null");
	}

	@Test
	void aCrewOfFiveUnreleasedRowsRefusesThePick() throws Exception {
		recruit(this.job, this.hung, 1).andExpect(status().isOk());
		UUID application = apply(this.minh, this.job);
		for (int i = 0; i < 5; i++) {
			JobContent.crewRow(this.jdbc, this.job, JobContent.member(this.jdbc, "Thành viên " + i), i == 0 ? "ACTIVE" : "ASSIGNED",
					null);
		}

		pick(this.hung, this.job, application).andExpect(status().is(422)).andExpect(jsonPath("$.reason").value("CREW_FULL"))
			.andExpect(jsonPath("$.n").value(5));
	}

	@Test
	void gitHubNotAnsweringRefusesThePickAndWritesNothing() throws Exception {
		recruit(this.job, this.hung, 1).andExpect(status().isOk());
		UUID application = apply(this.minh, this.job);
		ANSWERS.put(CONTRIBUTORS_PATH, "500 {}");
		long crewVersion = crewVersion();

		pick(this.hung, this.job, application).andExpect(status().is(422))
			.andExpect(jsonPath("$.reason").value("CONTRIBUTORS_UNKNOWN"));

		assertThat(count("SELECT count(*) FROM job.crew_row WHERE job_id = :job", Map.of("job", this.job))).isZero();
		assertThat(applicationState(application)).isEqualTo("PENDING null");
		assertThat(crewVersion()).isEqualTo(crewVersion);
		ANSWERS.put(CONTRIBUTORS_PATH, "200 [{\"id\":" + githubId(this.minh) + ",\"login\":\"minh\"}]");
		pick(this.hung, this.job, application).andExpect(status().is(422))
			.andExpect(jsonPath("$.reason").value("APPLICANT_IS_CONTRIBUTOR"));
	}

	/** A-03: GitHub is read before the pick's transaction, so nothing holds the Job row while it answers. */
	@Test
	void theContributorReadRunsBeforeThePicksTransactionAndHoldsNoLock() throws Exception {
		recruit(this.job, this.hung, 1).andExpect(status().isOk());
		UUID application = apply(this.minh, this.job);
		AtomicReference<String> during = new AtomicReference<>();
		BEFORE_CONTRIBUTORS.set(() -> during.set(tryLockJob()));

		pick(this.hung, this.job, application).andExpect(status().isOk());

		assertThat(during.get()).as("the Job row was free while GitHub answered").isEqualTo("locked");
		awaitListeners();
	}

	@Test
	void thePickedPersonReadsTheProjectionAndTheCommitmentPanelAndNothingElseOfTheJob() throws Exception {
		recruit(this.job, this.hung, 1).andExpect(status().isOk());
		UUID application = apply(this.minh, this.job);
		pick(this.hung, this.job, application).andExpect(status().isOk());

		this.mvc.perform(get("/api/v1/crew/jobs/" + this.job + "/commitment").with(as(this.minh)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("PICKED"))
			.andExpect(jsonPath("$.jobTitle").value("Sửa lỗi đặt bàn"))
			.andExpect(jsonPath("$.mentorName").value("Đỗ Mạnh Hùng"))
			.andExpect(jsonPath("$.panel.scope").value("Sửa lỗi"))
			.andExpect(jsonPath("$.panel.milestones[0].title").value("Sửa lỗi trùng giờ"))
			.andExpect(jsonPath("$.panel.milestones[0].priceVnd").value(800000))
			.andExpect(jsonPath("$.panel.codeJob").value(true))
			.andExpect(jsonPath("$.panel.githubLogin").value(TeamTestSupport.login(this.jdbc, this.minh)))
			.andExpect(jsonPath("$.panel.ndaVersion").value("1.0"))
			.andExpect(jsonPath("$.panel.commitmentVersion").value("1.0"))
			.andExpect(jsonPath("$.panel.splitRules.mentorShareCapPercent").value(40))
			.andExpect(jsonPath("$.panel.splitRules.draftDays").value(7))
			.andExpect(jsonPath("$.panel.splitRules.confirmationDays").value(3))
			.andExpect(jsonPath("$.panel.splitRules.editLimit").value(3));
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/role").with(as(this.minh)))
			.andExpect(jsonPath("$.crewKind").value("ASSIGNED"));
		for (String read : List.of("/api/v1/jobs/" + this.job + "/external-access", "/api/v1/jobs/" + this.job + "/busy",
				"/api/v1/jobs/" + this.job + "/agreement", "/api/v1/jobs/" + this.job + "/chat",
				"/api/v1/jobs/" + this.job + "/crew-chat", "/api/v1/mentor/jobs/" + this.job + "/crew",
				"/api/v1/mentor/jobs/" + this.job + "/crew-applications")) {
			this.mvc.perform(get(read).with(as(this.minh))).andExpect(status().isNotFound());
		}
		for (UUID stranger : List.of(this.lan, this.hung)) {
			this.mvc.perform(get("/api/v1/crew/jobs/" + this.job + "/commitment").with(as(stranger)))
				.andExpect(status().isNotFound());
		}
		awaitListeners();
	}

	@Test
	void signingMakesTheMemberActiveAndTellsTheCustomerTheUsernameOnly() throws Exception {
		recruit(this.job, this.hung, 1).andExpect(status().isOk());
		UUID application = apply(this.minh, this.job);
		UUID otherJob = recruitingJob();
		UUID elsewhere = apply(this.minh, otherJob);
		pick(this.hung, this.job, application).andExpect(status().isOk());
		openRecruitmentTodo();
		long version = jobVersion();

		sign(this.minh, this.job, "1.0", false).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reason").value("COMMIT_EMAIL_NOT_ACKNOWLEDGED"));
		sign(this.minh, this.job, "0.9", true).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reason").value("COMMITMENT_VERSION_OUTDATED"));
		sign(this.minh, this.job, "1.0", true).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("JOINED"))
			.andExpect(jsonPath("$.endReason").value("SIGNED"))
			.andExpect(jsonPath("$.panel").doesNotExist());

		assertThat(text("""
				SELECT status || ' ' || (signed_at IS NOT NULL) || ' ' || (activated_at IS NOT NULL) FROM job.crew_row
				WHERE job_id = :job AND member_account_id = :minh
				""", Map.of("job", this.job, "minh", this.minh))).isEqualTo("ACTIVE true true");
		assertThat(jobVersion()).as("the usernames the Customer sees changed").isEqualTo(version + 1);
		assertThat(text("""
				SELECT nda_version || ' ' || commitment_version FROM matching.nda_signature
				WHERE job_id = :job AND account_id = :minh
				""", Map.of("job", this.job, "minh", this.minh))).isEqualTo("1.0 1.0");
		assertThat(text("SELECT params ->> 'name' FROM notification.notification WHERE type = 'job.crew_member_joined'"
				+ " AND account_id = :hung", Map.of("hung", this.hung))).isEqualTo("Trần Quang Minh");
		String customer = text("SELECT params::text FROM notification.notification WHERE type = 'job.crew_login_added'"
				+ " AND account_id = :lan", Map.of("lan", this.lan));
		assertThat(customer).contains(TeamTestSupport.login(this.jdbc, this.minh)).doesNotContain("Trần Quang Minh");
		assertThat(count("""
				SELECT count(*) FROM notification.todo WHERE todo_key = 'matching.sign_commitment' AND closed_at IS NULL
				""", Map.of())).isZero();
		await().atMost(Duration.ofSeconds(10))
			.until(() -> "LAPSED JOINED_ANOTHER_CREW".equals(applicationState(elsewhere)));
		await().atMost(Duration.ofSeconds(10)).until(() -> count("""
				SELECT count(*) FROM notification.todo WHERE todo_key = 'matching.open_crew_recruitment' AND closed_at IS NULL
				""", Map.of()) == 0);
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/role").with(as(this.minh)))
			.andExpect(jsonPath("$.crewKind").value("ACTIVE"));
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/agreement").with(as(this.lan)))
			.andExpect(jsonPath("$.crew[0]").value(TeamTestSupport.login(this.jdbc, this.minh)));

		// FR-75: nobody who signed is removed; "Rút lựa chọn" finds no pick to withdraw.
		withdrawPick(this.hung, this.job, application).andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("NOT_WITHDRAWABLE"));
		assertThat(text("SELECT status FROM job.crew_row WHERE member_account_id = :minh AND job_id = :job",
				Map.of("minh", this.minh, "job", this.job))).isEqualTo("ACTIVE");
		awaitListeners();
	}

	@Test
	void aDeclinedPickTellsTheMentorAndGivesItsPlaceBackWhileRecruiting() throws Exception {
		recruit(this.job, this.hung, 2).andExpect(status().isOk());
		UUID application = apply(this.minh, this.job);
		pick(this.hung, this.job, application).andExpect(status().isOk());
		assertThat(text("SELECT recruiting || ' ' || open_slots FROM job.job WHERE id = :id", Map.of("id", this.job)))
			.isEqualTo("true 1");

		this.mvc.perform(post("/api/v1/crew/jobs/" + this.job + "/commitment/decline").with(as(this.minh)).with(csrf()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("DECLINED"))
			.andExpect(jsonPath("$.endReason").value("COMMITMENT_DECLINED"));

		assertThat(text("SELECT status || ' ' || released_reason FROM job.crew_row WHERE member_account_id = :minh",
				Map.of("minh", this.minh))).isEqualTo("RELEASED DECLINED");
		assertThat(text("SELECT recruiting || ' ' || open_slots FROM job.job WHERE id = :id", Map.of("id", this.job)))
			.isEqualTo("true 2");
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type = 'matching.commitment_declined'"
				+ " AND account_id = :hung", Map.of("hung", this.hung))).isOne();
		assertThat(count("SELECT count(*) FROM notification.notification WHERE account_id = :lan AND type LIKE '%crew%'",
				Map.of("lan", this.lan))).as("the Customer never learns of picks").isZero();
		sign(this.minh, this.job, "1.0", true).andExpect(status().is(422)).andExpect(jsonPath("$.reason").value("PICK_ENDED"));
		awaitListeners();
	}

	@Test
	void theSignaturePeriodLapsesThePickAndTellsTheMentorAndThePerson() throws Exception {
		recruit(this.job, this.hung, 1).andExpect(status().isOk());
		UUID application = apply(this.minh, this.job);
		pick(this.hung, this.job, application).andExpect(status().isOk());

		this.clock.advance(Duration.ofDays(3).plusMinutes(1));
		this.poller.runDue();

		assertThat(applicationState(application)).isEqualTo("LAPSED COMMITMENT_LAPSED");
		assertThat(text("SELECT status || ' ' || released_reason FROM job.crew_row WHERE member_account_id = :minh",
				Map.of("minh", this.minh))).isEqualTo("RELEASED DECLINED");
		for (UUID told : List.of(this.hung, this.minh)) {
			assertThat(count("SELECT count(*) FROM notification.notification WHERE type = 'matching.commitment_lapsed'"
					+ " AND account_id = :account", Map.of("account", told))).isOne();
		}
		this.mvc.perform(get("/api/v1/crew/jobs/" + this.job + "/commitment").with(as(this.minh)))
			.andExpect(jsonPath("$.status").value("LAPSED"))
			.andExpect(jsonPath("$.endReason").value("COMMITMENT_LAPSED"))
			.andExpect(jsonPath("$.panel").doesNotExist());
		awaitListeners();
	}

	@Test
	void theMentorWithdrawsAPickAndThePersonIsFreeAgain() throws Exception {
		recruit(this.job, this.hung, 2).andExpect(status().isOk());
		UUID application = apply(this.minh, this.job);
		pick(this.hung, this.job, application).andExpect(status().isOk());

		withdrawPick(this.hung, this.job, application).andExpect(status().isNoContent());

		assertThat(applicationState(application)).isEqualTo("WITHDRAWN PICK_WITHDRAWN");
		assertThat(text("SELECT status || ' ' || released_reason FROM job.crew_row WHERE member_account_id = :minh",
				Map.of("minh", this.minh))).isEqualTo("RELEASED WITHDRAWN");
		assertThat(text("SELECT open_slots FROM job.job WHERE id = :id", Map.of("id", this.job))).isEqualTo("2");
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type = 'matching.crew_pick_withdrawn'"
				+ " AND account_id = :minh", Map.of("minh", this.minh))).isOne();
		withdrawPick(this.hung, this.job, application).andExpect(status().isConflict());
		withdrawPick(this.lan, this.job, application).andExpect(status().isNotFound());
		assertThat(this.jdbc.sql("SELECT count(*) FROM job.crew_row WHERE member_account_id = :minh AND status <> 'RELEASED'")
			.param("minh", this.minh).query(Long.class).single()).as("free for another Crew").isZero();
		awaitListeners();
	}

	/** A-04: J8 ends an unsigned pick and turns recruitment off; a signature that reaches job after it is refused. */
	@Test
	void j8EndsAnUnsignedPickAndASignatureAfterItIsRefused() throws Exception {
		this.jdbc.sql("UPDATE job.job SET state = 'ACTIVE' WHERE id = :id").param("id", this.job).update();
		UUID vy = person("Lê Thảo Vy");
		JobContent.crewRow(this.jdbc, this.job, vy, "ACTIVE", null);
		recruit(this.job, this.hung, 1).andExpect(status().isOk());
		UUID application = apply(this.minh, this.job);
		pick(this.hung, this.job, application).andExpect(status().isOk());
		UUID row = this.jdbc.sql("SELECT id FROM job.crew_row WHERE member_account_id = :minh").param("minh", this.minh)
			.query(UUID.class).single();

		new TransactionTemplate(this.transactions)
			.executeWithoutResult(status -> this.lifecycle.apply(Transition.J8, this.job, Actor.SYSTEM, Trigger.AUTO));

		assertThat(text("SELECT status || ' ' || released_reason FROM job.crew_row WHERE id = :id", Map.of("id", row)))
			.isEqualTo("RELEASED JOB_ENDED");
		assertThat(text("SELECT status FROM job.crew_row WHERE member_account_id = :vy", Map.of("vy", vy)))
			.as("J8 never releases an ACTIVE row").isEqualTo("ACTIVE");
		await().atMost(Duration.ofSeconds(10)).until(() -> "LAPSED PICK_ENDED".equals(applicationState(application)));
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type = 'matching.crew_pick_ended'"
				+ " AND account_id = :minh", Map.of("minh", this.minh))).isOne();
		assertThatThrownBy(() -> new TransactionTemplate(this.transactions).executeWithoutResult(
				status -> this.picks.activateCrew(this.job, row, Contribution.NONE, this.clock.instant())))
			.isInstanceOfSatisfying(ApiException.class,
					refused -> assertThat(refused.reason()).hasValue("PICK_ENDED"));
		assertThat(text("SELECT status FROM job.crew_row WHERE id = :id", Map.of("id", row))).isEqualTo("RELEASED");
		sign(this.minh, this.job, "1.0", true).andExpect(status().is(422)).andExpect(jsonPath("$.reason").value("PICK_ENDED"));
		awaitListeners();
	}

	/** SG-51: two Mentors pick the same person at once; one running Crew per person, so one pick commits. */
	@Test
	void twoPicksOfOnePersonRaceAndOneCommits() throws Exception {
		recruit(this.job, this.hung, 1).andExpect(status().isOk());
		UUID first = apply(this.minh, this.job);
		UUID otherJob = recruitingJob();
		UUID otherMentor = this.jdbc.sql("SELECT mentor_account_id FROM job.job WHERE id = :id").param("id", otherJob)
			.query(UUID.class).single();
		UUID second = apply(this.minh, otherJob);
		CountDownLatch both = new CountDownLatch(2);
		BEFORE_CONTRIBUTORS.set(() -> {
			both.countDown();
			try {
				both.await(5, TimeUnit.SECONDS);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		});
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<Integer> one = pool.submit(() -> pick(this.hung, this.job, first).andReturn().getResponse().getStatus());
			Future<Integer> two = pool.submit(() -> pick(otherMentor, otherJob, second).andReturn().getResponse().getStatus());
			List<Integer> statuses = new ArrayList<>(List.of(one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS)));
			statuses.sort(Integer::compare);
			assertThat(statuses).containsExactly(200, 422);
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(this.jdbc.sql("SELECT count(*) FROM job.crew_row WHERE member_account_id = :minh AND status <> 'RELEASED'")
			.param("minh", this.minh).query(Long.class).single()).isOne();
		awaitListeners();
	}

	/** Story 20.4 with picks: a lock releases a pick as DECLINED, tells the Mentor only, and gives the place back. */
	@Test
	void aLockOfAPickedPersonTellsOnlyTheMentorAndGivesThePlaceBack() throws Exception {
		recruit(this.job, this.hung, 2).andExpect(status().isOk());
		UUID application = apply(this.minh, this.job);
		pick(this.hung, this.job, application).andExpect(status().isOk());
		AccountLocked locked = new AccountLocked(UUID.randomUUID(), this.minh, this.clock.instant());
		IdentityTestSupport.lock(this.jdbc, this.minh, "Vi phạm quy tắc");

		new TransactionTemplate(this.transactions).executeWithoutResult(status -> this.events.publishEvent(locked));
		await().atMost(Duration.ofSeconds(10))
			.until(() -> "WITHDRAWN APPLICANT_UNAVAILABLE".equals(applicationState(application)));
		awaitListeners();

		assertThat(text("SELECT status || ' ' || released_reason FROM job.crew_row WHERE member_account_id = :minh",
				Map.of("minh", this.minh))).isEqualTo("RELEASED DECLINED");
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type = 'job.crew_pick_released'"
				+ " AND account_id = :hung", Map.of("hung", this.hung))).isOne();
		assertThat(count("SELECT count(*) FROM notification.notification WHERE account_id = :lan AND type LIKE '%crew%'",
				Map.of("lan", this.lan))).isZero();
		assertThat(text("SELECT open_slots FROM job.job WHERE id = :id", Map.of("id", this.job))).isEqualTo("2");
		assertThat(count("""
				SELECT count(*) FROM notification.todo WHERE todo_key = 'matching.sign_commitment' AND closed_at IS NULL
				""", Map.of())).isZero();
	}

	// ---- Helpers ------------------------------------------------------------------------------

	/** Another Agreement Job, of another Customer, whose own Mentor recruits one member. */
	private UUID recruitingJob() throws Exception {
		UUID customer = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		CustomerTokens.keep(this.context, customer, TOKEN);
		UUID mentor = person("Nguyễn Thu Hà");
		UUID job = JobContent.job(this.jdbc, product(customer, "ha/cua-hang"), customer, mentor, "Việc của Hà",
				JobContent.SIGNED, "AGREEMENT");
		recruit(job, mentor, 1).andExpect(status().isOk());
		return job;
	}

	private void openRecruitmentTodo() {
		new TransactionTemplate(this.transactions).executeWithoutResult(status -> this.todos.open(new TodoRequest(
				"matching.open_crew_recruitment", this.hung, Audience.MENTOR, this.job, Map.of("job", "Sửa lỗi đặt bàn"),
				"/cong-viec/" + this.job + "/tuyen-thanh-vien", null, this.job)));
	}

	private String tryLockJob() {
		try (Connection connection = this.context.getBean(javax.sql.DataSource.class).getConnection()) {
			connection.setAutoCommit(false);
			try (PreparedStatement statement = connection.prepareStatement(
					"SELECT id FROM job.job WHERE id = ? FOR UPDATE NOWAIT")) {
				statement.setObject(1, this.job);
				statement.executeQuery();
				return "locked";
			}
			catch (SQLException held) {
				return "held: " + held.getMessage();
			}
			finally {
				connection.rollback();
			}
		}
		catch (SQLException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private UUID person(String name) {
		return TeamTestSupport.mentor(this.jdbc, name, List.of(TeamTestSupport.REACT), "React");
	}

	private long githubId(UUID account) {
		return this.jdbc.sql("SELECT github_user_id FROM identity.account WHERE id = :id").param("id", account)
			.query(Long.class).single();
	}

	private ResultActions recruit(UUID jobId, UUID mentor, int slots) throws Exception {
		long crewVersion = this.jdbc.sql("SELECT crew_version FROM job.job WHERE id = :id").param("id", jobId)
			.query(Long.class).single();
		return this.mvc.perform(put("/api/v1/mentor/jobs/" + jobId + "/recruitment").with(as(mentor)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"on":true,"openSlots":%d,"skillIds":["%s"],"note":"Cần một bạn kiểm thử phân quyền","crewVersion":%d}
					""".formatted(slots, TeamTestSupport.REACT, crewVersion)));
	}

	private UUID apply(UUID applicant, UUID jobId) throws Exception {
		String created = this.mvc.perform(post("/api/v1/mentor/job-posts/" + jobId + "/crew-applications").with(as(applicant))
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"note":"Mình đã làm kiểm thử phân quyền cho đồ án.","skillIds":["%s"],"publicCodeAcknowledged":true}
					""".formatted(TeamTestSupport.REACT)))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		return UUID.fromString(JsonPath.read(created, "$.id"));
	}

	private ResultActions pick(UUID mentor, UUID jobId, UUID application) throws Exception {
		return this.mvc.perform(post("/api/v1/mentor/jobs/" + jobId + "/crew-applications/" + application + "/pick")
			.with(as(mentor)).with(csrf()));
	}

	private ResultActions withdrawPick(UUID mentor, UUID jobId, UUID application) throws Exception {
		return this.mvc.perform(post("/api/v1/mentor/jobs/" + jobId + "/crew-applications/" + application + "/withdraw-pick")
			.with(as(mentor)).with(csrf()));
	}

	private ResultActions sign(UUID person, UUID jobId, String commitmentVersion, boolean commitEmail) throws Exception {
		return this.mvc.perform(post("/api/v1/crew/jobs/" + jobId + "/commitment").with(as(person)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"ndaVersion":"1.0","commitmentVersion":"%s","publicCodeAcknowledged":true,
					 "commitEmailNoticeAcknowledged":%s}
					""".formatted(commitmentVersion, commitEmail)));
	}

	private String applicationState(UUID application) {
		return text("SELECT status || ' ' || coalesce(end_reason, 'null') FROM matching.crew_application WHERE id = :id",
				Map.of("id", application));
	}

	private long jobVersion() {
		return this.jdbc.sql("SELECT version FROM job.job WHERE id = :id").param("id", this.job).query(Long.class).single();
	}

	private long crewVersion() {
		return this.jdbc.sql("SELECT crew_version FROM job.job WHERE id = :id").param("id", this.job).query(Long.class).single();
	}

	private long count(String sql, Map<String, ?> params) {
		var statement = this.jdbc.sql(sql);
		for (Map.Entry<String, ?> param : params.entrySet()) {
			statement = statement.param(param.getKey(), param.getValue());
		}
		return statement.query(Long.class).single();
	}

	private String text(String sql, Map<String, ?> params) {
		var statement = this.jdbc.sql(sql);
		for (Map.Entry<String, ?> param : params.entrySet()) {
			statement = statement.param(param.getKey(), param.getValue());
		}
		return statement.query(String.class).optional().orElse(null);
	}

	private void awaitListeners() {
		await().atMost(Duration.ofSeconds(10)).until(() -> this.jdbc.sql(
				"SELECT count(*) FROM platform.event_publication WHERE completion_date IS NULL").query(Long.class).single() == 0);
	}

	private UUID product(UUID owner, String repository) {
		UUID id = UUID.randomUUID();
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO product.product (id, customer_account_id, name, description, ai_tools, repository_github_id,
				    repository_full_name, repository_owner_type, repository_default_branch, public_notice_version,
				    copy_permission_version, detection_notice_version, acknowledged_at, created_at)
				VALUES (:id, :owner, 'Sổ đặt bàn Quán Mây', 'Đặt bàn cho quán.', ARRAY['LOVABLE'], :github,
				    :repository, 'User', 'main', '1.0', '1.0', '1.0', :now, :now)
				""")
			.param("id", id)
			.param("owner", owner)
			.param("github", java.util.concurrent.ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE))
			.param("repository", repository)
			.param("now", now)
			.update();
		return id;
	}

	private RequestPostProcessor as(UUID account) {
		return IdentityTestSupport.signedIn(this.sessions, account, Role.MENTOR, this.clock.instant());
	}

	private static HttpServer start() {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.setExecutor(Executors.newCachedThreadPool());
			server.createContext("/", exchange -> {
				String path = exchange.getRequestURI().getPath();
				boolean contributors = path.endsWith("/contributors");
				if (contributors) {
					Runnable before = BEFORE_CONTRIBUTORS.get();
					if (before != null) {
						before.run();
					}
				}
				String answer = ANSWERS.getOrDefault(path, contributors ? "200 []" : "404 {}");
				int status = Integer.parseInt(answer.substring(0, 3));
				byte[] body = answer.substring(4).getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().add("Content-Type", "application/json");
				exchange.sendResponseHeaders(status, body.length);
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
