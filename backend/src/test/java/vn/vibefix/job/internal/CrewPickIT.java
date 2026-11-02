package vn.vibefix.job.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
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
import vn.vibefix.identity.Notices;
import vn.vibefix.identity.PersonSignalMatched;
import vn.vibefix.identity.internal.CustomerTokens;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.job.CrewPicks;
import vn.vibefix.job.CrewPicks.Contribution;
import vn.vibefix.job.CrewRecruitment.ClosedBy;
import vn.vibefix.job.CrewRecruitmentClosed;
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
		assertThat(count("""
				SELECT count(*) FROM timers.deadline WHERE kind = 'matching.commitment_due' AND subject_id = :id AND NOT pausable
				""", Map.of("id", application))).isOne();
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
		this.jdbc.sql("""
				UPDATE job.job SET rules = '{"MENTOR_SHARE_CAP_PERCENT":30,"SPLIT_DRAFT_DAYS":10,"SPLIT_CONFIRMATION_DAYS":5,
				    "SPLIT_EDIT_LIMIT":2}'::jsonb WHERE id = :id
				""").param("id", this.job).update();
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
			.andExpect(jsonPath("$.panel.splitRules.mentorShareCapPercent").value(30))
			.andExpect(jsonPath("$.panel.splitRules.draftDays").value(10))
			.andExpect(jsonPath("$.panel.splitRules.confirmationDays").value(5))
			.andExpect(jsonPath("$.panel.splitRules.editLimit").value(2));
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
		UUID vy = person("Lê Thảo Vy");
		JobContent.crewRow(this.jdbc, this.job, vy, "ACTIVE", null);
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
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type = 'job.crew_member_joined'"
				+ " AND account_id = :vy", Map.of("vy", vy))).as("the other active member hears").isOne();
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type = 'job.crew_member_joined'"
				+ " AND account_id = :minh", Map.of("minh", this.minh))).as("the signer does not").isZero();
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
			.andExpect(jsonPath("$.crew", hasItem(TeamTestSupport.login(this.jdbc, this.minh))));

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
		assertThat(recruitmentTodoDeadline()).as("recruitment is still on, so nothing is asked").isNull();
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
		assertThat(lapsedLink(this.hung)).as("JOB-9 is the Mentor's").isEqualTo("/cong-viec/" + this.job + "/tuyen-thanh-vien");
		assertThat(lapsedLink(this.minh)).as("the person reads why on MEN-3").isEqualTo("/tim-viec/" + this.job);
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
		this.mvc.perform(get("/api/v1/mentor/jobs/" + this.job + "/crew-applications").with(as(this.hung)))
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].id").value(application.toString()))
			.andExpect(jsonPath("$[0].endReason").value("PICK_WITHDRAWN"));
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
		assertThat(systemAudits("job.crew_pick_released")).isOne();
		assertThat(systemAudits("matching.crew_pick_ended")).isOne();
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
		UUID tuan = person("Phạm Anh Tuấn");
		UUID withdrawn = apply(tuan, this.job);
		this.mvc.perform(post("/api/v1/mentor/crew-applications/" + withdrawn + "/withdraw").with(as(tuan)).with(csrf()))
			.andExpect(status().isOk());
		pick(this.hung, this.job, application).andExpect(status().isOk());
		this.mvc.perform(get("/api/v1/mentor/jobs/" + this.job + "/crew-applications").with(as(this.hung)))
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].id").value(application.toString()));
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
		assertThat(systemAudits("matching.crew_pick_withdrawn")).isOne();
		this.mvc.perform(get("/api/v1/mentor/jobs/" + this.job + "/crew-applications").with(as(this.hung)))
			.andExpect(jsonPath("$.length()").value(0));
	}

	/**
	 * AD-4: every command on a pick queues for the Job row before it locks the application, so nothing holds a
	 * {@code matching} row while it waits for the Job.
	 */
	@Test
	void everyCommandOnAPickTakesTheJobRowBeforeTheApplication() throws Exception {
		recruit(this.job, this.hung, 3).andExpect(status().isOk());
		UUID signed = apply(this.minh, this.job);
		takesTheJobRowFirst(signed, () -> pick(this.hung, this.job, signed), 200);
		takesTheJobRowFirst(signed, () -> sign(this.minh, this.job, "1.0", true), 200);

		UUID withdrawn = apply(person("Lê Thảo Vy"), this.job);
		pick(this.hung, this.job, withdrawn).andExpect(status().isOk());
		takesTheJobRowFirst(withdrawn, () -> withdrawPick(this.hung, this.job, withdrawn), 204);

		UUID tung = person("Phạm Hải Tùng");
		UUID declined = apply(tung, this.job);
		pick(this.hung, this.job, declined).andExpect(status().isOk());
		takesTheJobRowFirst(declined, () -> this.mvc
			.perform(post("/api/v1/crew/jobs/" + this.job + "/commitment/decline").with(as(tung)).with(csrf())), 200);
		awaitListeners();
	}

	/** FR-75: no route removes a member who signed, for anyone, and withdrawing their pick afterwards changes nothing. */
	@Test
	void nobodyRemovesAMemberWhoSigned() throws Exception {
		recruit(this.job, this.hung, 1).andExpect(status().isOk());
		UUID application = apply(this.minh, this.job);
		pick(this.hung, this.job, application).andExpect(status().isOk());
		sign(this.minh, this.job, "1.0", true).andExpect(status().isOk());
		String row = text("SELECT id FROM job.crew_row WHERE job_id = :job AND member_account_id = :minh",
				Map.of("job", this.job, "minh", this.minh));

		for (UUID caller : List.of(this.hung, this.lan, this.minh)) {
			this.mvc.perform(post("/api/v1/mentor/jobs/" + this.job + "/crew/" + row + "/remove").with(as(caller)).with(csrf()))
				.andExpect(status().isNotFound());
			this.mvc.perform(delete("/api/v1/mentor/jobs/" + this.job + "/crew/" + row).with(as(caller)).with(csrf()))
				.andExpect(status().isNotFound());
		}
		withdrawPick(this.hung, this.job, application).andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("NOT_WITHDRAWABLE"));

		assertThat(text("SELECT status FROM job.crew_row WHERE job_id = :job AND member_account_id = :minh",
				Map.of("job", this.job, "minh", this.minh))).isEqualTo("ACTIVE");
		awaitListeners();
	}

	/** AD-19: a person-link signal shared with the Customer refuses the pick and the signature, and the alert survives. */
	@Test
	void aPersonLinkedToTheCustomerIsRefusedAtThePickAndTheSignature() throws Exception {
		UUID admin = administrator();
		recruit(this.job, this.hung, 2).andExpect(status().isOk());
		UUID picking = apply(this.minh, this.job);
		UUID tuan = person("Phạm Anh Tuấn");
		UUID signing = apply(tuan, this.job);
		pick(this.hung, this.job, signing).andExpect(status().isOk());
		for (UUID linked : List.of(this.lan, this.minh, tuan)) {
			signal(linked, "a".repeat(64));
		}
		long crewVersion = crewVersion();

		pick(this.hung, this.job, picking).andExpect(status().is(422))
			.andExpect(jsonPath("$.reason").value("CONFLICT_OF_INTEREST"));

		assertThat(count("SELECT count(*) FROM job.crew_row WHERE member_account_id = :minh", Map.of("minh", this.minh)))
			.as("nothing was written").isZero();
		assertThat(applicationState(picking)).isEqualTo("PENDING null");
		assertThat(crewVersion()).isEqualTo(crewVersion);
		assertThat(conflictAlerts(admin)).as("the alert survives the refusal's rollback").isOne();

		sign(tuan, this.job, "1.0", true).andExpect(status().is(422))
			.andExpect(jsonPath("$.reason").value("CONFLICT_OF_INTEREST"));

		assertThat(text("SELECT status FROM job.crew_row WHERE member_account_id = :tuan", Map.of("tuan", tuan)))
			.as("nothing was written").isEqualTo("ASSIGNED");
		assertThat(applicationState(signing)).isEqualTo("PICKED null");
		assertThat(count("SELECT count(*) FROM matching.nda_signature WHERE account_id = :tuan", Map.of("tuan", tuan))).isZero();
		assertThat(conflictAlerts(admin)).as("the alert survives the refusal's rollback").isEqualTo(2);
		awaitListeners();
	}

	/** AD-19: a pick is a role in the Job, so a person link found later alerts each Administrator once, replays included. */
	@Test
	void aPersonLinkBetweenAPickAndTheCustomerAlertsEveryAdministratorOnce() throws Exception {
		List<UUID> admins = List.of(administrator(), administrator());
		recruit(this.job, this.hung, 1).andExpect(status().isOk());
		pick(this.hung, this.job, apply(this.minh, this.job)).andExpect(status().isOk());
		PersonSignalMatched event = new PersonSignalMatched(UUID.randomUUID(), this.minh, this.lan, "EMAIL");

		new TransactionTemplate(this.transactions).executeWithoutResult(status -> this.events.publishEvent(event));

		for (UUID admin : admins) {
			await().atMost(Duration.ofSeconds(10)).until(() -> count("""
					SELECT count(*) FROM notification.notification WHERE type = 'job.person_link_alert' AND account_id = :admin
					""", Map.of("admin", admin)) == 1);
		}
		// The replay comes after the first delivery ended: two identical events in flight can leave one publication open.
		awaitListeners();
		new TransactionTemplate(this.transactions).executeWithoutResult(status -> this.events.publishEvent(event));
		awaitListeners();
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type = 'job.person_link_alert'", Map.of()))
			.isEqualTo(admins.size());
	}

	/** A-04: J10 ends an unsigned pick as J8 does. */
	@Test
	void j10EndsAnUnsignedPick() throws Exception {
		recruit(this.job, this.hung, 1).andExpect(status().isOk());
		UUID application = apply(this.minh, this.job);
		pick(this.hung, this.job, application).andExpect(status().isOk());

		new TransactionTemplate(this.transactions)
			.executeWithoutResult(status -> this.lifecycle.apply(Transition.J10, this.job, Actor.SYSTEM, Trigger.DECISION));

		assertThat(text("SELECT status || ' ' || released_reason FROM job.crew_row WHERE member_account_id = :minh",
				Map.of("minh", this.minh))).isEqualTo("RELEASED JOB_ENDED");
		await().atMost(Duration.ofSeconds(10)).until(() -> "LAPSED PICK_ENDED".equals(applicationState(application)));
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type = 'matching.crew_pick_ended'"
				+ " AND account_id = :minh", Map.of("minh", this.minh))).isOne();
		assertThat(count("""
				SELECT count(*) FROM notification.todo WHERE todo_key = 'matching.sign_commitment' AND closed_at IS NULL
				""", Map.of())).isZero();
		assertThat(systemAudits("job.crew_pick_released")).isOne();
		assertThat(systemAudits("matching.crew_pick_ended")).isOne();
		awaitListeners();
	}

	@Test
	void aSignatureNeedsN5() throws Exception {
		recruit(this.job, this.hung, 1).andExpect(status().isOk());
		UUID application = apply(this.minh, this.job);
		pick(this.hung, this.job, application).andExpect(status().isOk());

		this.mvc.perform(post("/api/v1/crew/jobs/" + this.job + "/commitment").with(as(this.minh)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"ndaVersion":"1.0","commitmentVersion":"1.0","publicCodeAcknowledged":false,
					 "commitEmailNoticeAcknowledged":true}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reason").value("PUBLIC_CODE_NOT_ACKNOWLEDGED"));

		assertThat(applicationState(application)).isEqualTo("PICKED null");
		awaitListeners();
	}

	/** FR-18: N-23 is asked once per account and notice version, so a second Job's panel needs it no more. */
	@Test
	void n23IsAskedOnceAcrossJobs() throws Exception {
		recruit(this.job, this.hung, 1).andExpect(status().isOk());
		pick(this.hung, this.job, apply(this.minh, this.job)).andExpect(status().isOk());
		this.mvc.perform(get("/api/v1/crew/jobs/" + this.job + "/commitment").with(as(this.minh)))
			.andExpect(jsonPath("$.panel.commitEmailNoticeAcknowledged").value(false));
		sign(this.minh, this.job, "1.0", true).andExpect(status().isOk());
		assertThat(count("SELECT count(*) FROM identity.acknowledgment WHERE account_id = :minh AND notice = :notice",
				Map.of("minh", this.minh, "notice", Notices.COMMIT_EMAIL))).isOne();
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				UPDATE job.crew_row SET status = 'RELEASED', released_at = :now, released_reason = 'LEFT', window_end = :now
				WHERE member_account_id = :minh
				""").param("now", now).param("minh", this.minh).update();
		UUID second = recruitingJob();
		UUID secondMentor = this.jdbc.sql("SELECT mentor_account_id FROM job.job WHERE id = :id").param("id", second)
			.query(UUID.class).single();
		pick(secondMentor, second, apply(this.minh, second)).andExpect(status().isOk());

		this.mvc.perform(get("/api/v1/crew/jobs/" + second + "/commitment").with(as(this.minh)))
			.andExpect(jsonPath("$.panel.codeJob").value(true))
			.andExpect(jsonPath("$.panel.commitEmailNoticeAcknowledged").value(true));
		sign(this.minh, second, "1.0", false).andExpect(status().isOk());
		awaitListeners();
	}

	/** FR-18: an Assessment Job has no code, so its signature asks no N-23 and records no acknowledgment. */
	@Test
	void anAssessmentJobSignsWithoutN23() throws Exception {
		this.jdbc.sql("UPDATE job.job SET job_types = ARRAY['ASSESSMENT']::TEXT[] WHERE id = :id").param("id", this.job).update();
		recruit(this.job, this.hung, 1).andExpect(status().isOk());
		pick(this.hung, this.job, apply(this.minh, this.job)).andExpect(status().isOk());

		this.mvc.perform(get("/api/v1/crew/jobs/" + this.job + "/commitment").with(as(this.minh)))
			.andExpect(jsonPath("$.panel.codeJob").value(false));
		sign(this.minh, this.job, "1.0", false).andExpect(status().isOk());

		assertThat(count("SELECT count(*) FROM identity.acknowledgment WHERE account_id = :minh AND notice = :notice",
				Map.of("minh", this.minh, "notice", Notices.COMMIT_EMAIL))).isZero();
		awaitListeners();
	}

	/**
	 * UX-DR112: the review to-do counts the pending applications at every step. A pick takes one out of the count and an
	 * unsigned pick that ends stays out, its application having ended (R4), so each ending shows as the to-do refreshed;
	 * the tier follows whether a member signed.
	 */
	@Test
	void theReviewToDoFollowsPicksAndSignatures() throws Exception {
		recruit(this.job, this.hung, 5).andExpect(status().isOk());
		UUID withdrawn = apply(this.minh, this.job);
		UUID tuan = person("Phạm Anh Tuấn");
		UUID declined = apply(tuan, this.job);
		UUID lapsed = apply(person("Lê Thảo Vy"), this.job);
		UUID ha = person("Nguyễn Thu Hà");
		UUID signing = apply(ha, this.job);
		apply(person("Bùi Văn Sơn"), this.job);
		assertThat(reviewTodo()).isEqualTo("5 2");

		pick(this.hung, this.job, withdrawn).andExpect(status().isOk());
		assertThat(reviewTodo()).as("a pick is no longer pending").isEqualTo("4 2");
		String before = reviewTodoId();
		withdrawPick(this.hung, this.job, withdrawn).andExpect(status().isNoContent());
		assertThat(reviewTodo()).isEqualTo("4 2");
		assertThat(reviewTodoId()).as("refreshed by the withdrawal").isNotEqualTo(before);

		pick(this.hung, this.job, declined).andExpect(status().isOk());
		assertThat(reviewTodo()).isEqualTo("3 2");
		before = reviewTodoId();
		this.mvc.perform(post("/api/v1/crew/jobs/" + this.job + "/commitment/decline").with(as(tuan)).with(csrf()))
			.andExpect(status().isOk());
		assertThat(reviewTodo()).isEqualTo("3 2");
		assertThat(reviewTodoId()).as("refreshed by the decline").isNotEqualTo(before);

		pick(this.hung, this.job, lapsed).andExpect(status().isOk());
		assertThat(reviewTodo()).isEqualTo("2 2");
		before = reviewTodoId();
		this.clock.advance(Duration.ofDays(3).plusMinutes(1));
		this.poller.runDue();
		assertThat(applicationState(lapsed)).isEqualTo("LAPSED COMMITMENT_LAPSED");
		assertThat(reviewTodo()).isEqualTo("2 2");
		assertThat(reviewTodoId()).as("refreshed by the lapse").isNotEqualTo(before);

		pick(this.hung, this.job, signing).andExpect(status().isOk());
		assertThat(reviewTodo()).isEqualTo("1 2");
		sign(ha, this.job, "1.0", true).andExpect(status().isOk());
		assertThat(reviewTodo()).as("a member signed").isEqualTo("1 3");

		AccountLocked locked = new AccountLocked(UUID.randomUUID(), ha, this.clock.instant());
		IdentityTestSupport.lock(this.jdbc, ha, "Vi phạm quy tắc");
		new TransactionTemplate(this.transactions).executeWithoutResult(status -> this.events.publishEvent(locked));
		await().atMost(Duration.ofSeconds(10)).until(() -> "1 2".equals(reviewTodo()));
		awaitListeners();
	}

	/** R7: the only pick is withdrawn after it filled the places, so the Mentor is asked again until the Agreement deadline. */
	@Test
	void aWithdrawnPickAfterRecruitmentFilledAsksTheMentorToRecruitAgain() throws Exception {
		Instant due = agreementDue();
		UUID application = pickFillingTheOnePlace();

		withdrawPick(this.hung, this.job, application).andExpect(status().isNoContent());

		assertThat(recruitmentTodoDeadline()).isEqualTo(due);
		awaitListeners();
	}

	@Test
	void aDeclinedPickAfterRecruitmentFilledAsksTheMentorToRecruitAgain() throws Exception {
		Instant due = agreementDue();
		pickFillingTheOnePlace();

		this.mvc.perform(post("/api/v1/crew/jobs/" + this.job + "/commitment/decline").with(as(this.minh)).with(csrf()))
			.andExpect(status().isOk());

		assertThat(recruitmentTodoDeadline()).isEqualTo(due);
		awaitListeners();
	}

	@Test
	void aLapsedPickAfterRecruitmentFilledAsksTheMentorToRecruitAgain() throws Exception {
		Instant due = agreementDue();
		pickFillingTheOnePlace();

		this.clock.advance(Duration.ofDays(3).plusMinutes(1));
		this.poller.runDue();

		assertThat(recruitmentTodoDeadline()).isEqualTo(due);
		awaitListeners();
	}

	/** R7: while another pick waits for its signature, the Mentor still has someone coming, so nothing is asked yet. */
	@Test
	void anotherRowLeftAsksTheMentorNothingUntilItEndsToo() throws Exception {
		Instant due = agreementDue();
		recruit(this.job, this.hung, 2).andExpect(status().isOk());
		UUID first = apply(this.minh, this.job);
		UUID second = apply(person("Phạm Anh Tuấn"), this.job);
		pick(this.hung, this.job, first).andExpect(status().isOk());
		pick(this.hung, this.job, second).andExpect(status().isOk());
		assertThat(text("SELECT recruiting::text FROM job.job WHERE id = :id", Map.of("id", this.job))).isEqualTo("false");

		withdrawPick(this.hung, this.job, first).andExpect(status().isNoContent());
		assertThat(recruitmentTodoDeadline()).isNull();

		withdrawPick(this.hung, this.job, second).andExpect(status().isNoContent());
		assertThat(recruitmentTodoDeadline()).isEqualTo(due);
		awaitListeners();
	}

	/** R7: the Crew emptied after the confirmation but before J6, so nothing else asks the Mentor for a member. */
	@Test
	void aCrewEmptiedAfterTheConfirmationAsksTheMentorToRecruitAgain() throws Exception {
		Instant due = agreementDue();
		UUID vy = person("Lê Thảo Vy");
		JobContent.crewRow(this.jdbc, this.job, vy, "ACTIVE", null);
		AccountLocked locked = new AccountLocked(UUID.randomUUID(), vy, this.clock.instant());
		IdentityTestSupport.lock(this.jdbc, vy, "Vi phạm quy tắc");

		new TransactionTemplate(this.transactions).executeWithoutResult(status -> this.events.publishEvent(locked));

		await().atMost(Duration.ofSeconds(10)).until(() -> recruitmentTodoDeadline() != null);
		assertThat(recruitmentTodoDeadline()).isEqualTo(due);
		awaitListeners();
	}

	/** "Ký trước {date}": the deadline holds at the signature, even with the Deadline worker late. */
	@Test
	void aSignatureAtTheDeadlineIsRefusedEvenWithTheWorkerLate() throws Exception {
		recruit(this.job, this.hung, 2).andExpect(status().isOk());
		UUID intime = apply(this.minh, this.job);
		UUID tuan = person("Phạm Anh Tuấn");
		UUID late = apply(tuan, this.job);
		pick(this.hung, this.job, intime).andExpect(status().isOk());
		pick(this.hung, this.job, late).andExpect(status().isOk());

		this.clock.advance(Duration.ofDays(3).minusMinutes(1));
		sign(this.minh, this.job, "1.0", true).andExpect(status().isOk());

		this.clock.advance(Duration.ofMinutes(1));
		sign(tuan, this.job, "1.0", true).andExpect(status().is(422)).andExpect(jsonPath("$.reason").value("PICK_ENDED"));

		assertThat(text("SELECT status FROM job.crew_row WHERE member_account_id = :tuan", Map.of("tuan", tuan)))
			.isEqualTo("ASSIGNED");
		assertThat(applicationState(late)).isEqualTo("PICKED null");
		assertThat(count("SELECT count(*) FROM matching.nda_signature WHERE account_id = :tuan", Map.of("tuan", tuan))).isZero();
		awaitListeners();
	}

	/** FR-60: hiding a Job Post lapses pending applications only, so a pick stands and can still be signed. */
	@Test
	void aPickedPersonSignsOnAHiddenJobPost() throws Exception {
		recruit(this.job, this.hung, 1).andExpect(status().isOk());
		pick(this.hung, this.job, apply(this.minh, this.job)).andExpect(status().isOk());
		this.jdbc.sql("UPDATE job.job SET hidden_at = now(), hidden_reason = 'Vi phạm' WHERE id = :id").param("id", this.job).update();

		sign(this.minh, this.job, "1.0", true).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("JOINED"));
		awaitListeners();
	}

	/**
	 * Between J8's or a lock's commit and its listener the row is released and the application still picked: every command
	 * refuses and leaves it to the listener, which sends the right notice.
	 */
	@Test
	void aPickAlreadyReleasedIsLeftForItsListenerToEnd() throws Exception {
		recruit(this.job, this.hung, 1).andExpect(status().isOk());
		UUID application = apply(this.minh, this.job);
		pick(this.hung, this.job, application).andExpect(status().isOk());
		this.jdbc.sql("""
				UPDATE job.crew_row SET status = 'RELEASED', released_at = :now, released_reason = 'JOB_ENDED'
				WHERE crew_application_id = :application
				""").param("now", Timestamp.from(this.clock.instant())).param("application", application).update();

		withdrawPick(this.hung, this.job, application).andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("NOT_WITHDRAWABLE"));
		this.mvc.perform(post("/api/v1/crew/jobs/" + this.job + "/commitment/decline").with(as(this.minh)).with(csrf()))
			.andExpect(status().is(422))
			.andExpect(jsonPath("$.reason").value("PICK_ENDED"));
		this.clock.advance(Duration.ofDays(3).plusMinutes(1));
		this.poller.runDue();

		assertThat(applicationState(application)).isEqualTo("PICKED null");
		assertThat(count("""
				SELECT count(*) FROM notification.notification
				WHERE type IN ('matching.crew_pick_withdrawn', 'matching.commitment_declined', 'matching.commitment_lapsed')
				""", Map.of())).isZero();

		CrewRecruitmentClosed closed = new CrewRecruitmentClosed(UUID.randomUUID(), this.job, ClosedBy.CLOSING, this.clock.instant());
		new TransactionTemplate(this.transactions).executeWithoutResult(status -> this.events.publishEvent(closed));
		await().atMost(Duration.ofSeconds(10)).until(() -> "LAPSED PICK_ENDED".equals(applicationState(application)));
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type = 'matching.crew_pick_ended'"
				+ " AND account_id = :minh", Map.of("minh", this.minh))).isOne();
		awaitListeners();
	}

	/** AD-41: "Công việc" gives a pick not yet signed nothing outside the projection, and the Product once they signed. */
	@Test
	void myJobsGivesAPickNoProductName() throws Exception {
		recruit(this.job, this.hung, 1).andExpect(status().isOk());
		pick(this.hung, this.job, apply(this.minh, this.job)).andExpect(status().isOk());

		this.mvc.perform(get("/api/v1/my-jobs").with(as(this.minh)))
			.andExpect(jsonPath("$[0].crewKind").value("ASSIGNED"))
			.andExpect(jsonPath("$[0].productName").value(""));

		sign(this.minh, this.job, "1.0", true).andExpect(status().isOk());
		this.mvc.perform(get("/api/v1/my-jobs").with(as(this.minh)))
			.andExpect(jsonPath("$[0].crewKind").value("ACTIVE"))
			.andExpect(jsonPath("$[0].productName").value("Sổ đặt bàn Quán Mây"));
		awaitListeners();
	}

	/**
	 * A former selected Mentor who later joins the Crew already holds a signature, which keeps its NDA version and gains
	 * the commitment's; the evidence is otherwise closed to the application's role.
	 */
	@Test
	void aSignatureOverAnEarlierOneChangesOnlyItsCommitmentVersion() throws Exception {
		Timestamp earlier = Timestamp.from(JobContent.SIGNED);
		this.jdbc.sql("""
				INSERT INTO matching.nda_signature (job_id, account_id, nda_version, signed_at)
				VALUES (:job, :minh, '0.9', :earlier)
				""").param("job", this.job).param("minh", this.minh).param("earlier", earlier).update();
		recruit(this.job, this.hung, 1).andExpect(status().isOk());
		pick(this.hung, this.job, apply(this.minh, this.job)).andExpect(status().isOk());

		sign(this.minh, this.job, "1.0", true).andExpect(status().isOk());

		assertThat(text("""
				SELECT nda_version || ' ' || commitment_version || ' ' || (signed_at = :earlier) FROM matching.nda_signature
				WHERE job_id = :job AND account_id = :minh
				""", Map.of("job", this.job, "minh", this.minh, "earlier", earlier))).isEqualTo("0.9 1.0 true");
		assertThatThrownBy(() -> this.jdbc.sql("UPDATE matching.nda_signature SET nda_version = '9.9' WHERE account_id = :minh")
			.param("minh", this.minh).update()).as("only the commitment's version may change")
			.isInstanceOf(DataAccessException.class);
		awaitListeners();
	}

	// ---- Helpers ------------------------------------------------------------------------------

	/** A pick of the only place sought, so recruitment turned off by itself. */
	private UUID pickFillingTheOnePlace() throws Exception {
		recruit(this.job, this.hung, 1).andExpect(status().isOk());
		UUID application = apply(this.minh, this.job);
		pick(this.hung, this.job, application).andExpect(status().isOk());
		assertThat(text("SELECT recruiting::text FROM job.job WHERE id = :id", Map.of("id", this.job))).isEqualTo("false");
		return application;
	}

	/** The Job's Agreement deadline, ten days on. */
	private Instant agreementDue() {
		Instant due = this.clock.instant().plus(Duration.ofDays(10));
		this.jdbc.sql("UPDATE job.job SET agreement_due_at = :due WHERE id = :id").param("due", Timestamp.from(due))
			.param("id", this.job).update();
		return due;
	}

	/** The deadline of the Mentor's open "Mở tuyển thành viên nhóm", or {@code null} without one. */
	private @Nullable Instant recruitmentTodoDeadline() {
		return this.jdbc.sql("""
				SELECT deadline_at FROM notification.todo WHERE todo_key = 'matching.open_crew_recruitment' AND closed_at IS NULL
				    AND account_id = :hung
				""").param("hung", this.hung).query(Timestamp.class).optional().map(Timestamp::toInstant).orElse(null);
	}

	/** The open review to-do's count and tier. */
	private String reviewTodo() {
		return text("""
				SELECT (params ->> 'n') || ' ' || tier FROM notification.todo
				WHERE todo_key = 'matching.review_crew_applications' AND closed_at IS NULL
				""", Map.of());
	}

	private String reviewTodoId() {
		return text("""
				SELECT id FROM notification.todo WHERE todo_key = 'matching.review_crew_applications' AND closed_at IS NULL
				""", Map.of());
	}

	private String lapsedLink(UUID account) {
		return text("SELECT link FROM notification.notification WHERE type = 'matching.commitment_lapsed' AND account_id = :account",
				Map.of("account", account));
	}

	private long systemAudits(String action) {
		return count("SELECT count(*) FROM audit.audit_entry WHERE action = :action AND actor_type = 'SYSTEM' AND job_id = :job",
				Map.of("action", action, "job", this.job));
	}

	private long conflictAlerts(UUID admin) {
		return count("""
				SELECT count(*) FROM notification.notification WHERE type = 'job.conflict_of_interest_refused' AND account_id = :admin
				""", Map.of("admin", admin));
	}

	/** An Administrator with credentials, as {@code Administrators.activeIds} counts them. */
	private UUID administrator() {
		UUID admin = IdentityTestSupport.insertAccount(this.jdbc, Role.ADMINISTRATOR, null);
		this.jdbc.sql("""
				INSERT INTO identity.administrator_credential (account_id, email, display_name, password_hash,
				    totp_key_version, totp_nonce, totp_ciphertext, created_at)
				VALUES (:id, :email, 'Quản trị', '$argon2id$x', 1, '\\x00'::bytea, '\\x00'::bytea, now())
				""").param("id", admin).param("email", "qt-" + admin.toString().substring(0, 8) + "@vibefix.vn").update();
		return admin;
	}

	private void signal(UUID account, String hmac) {
		this.jdbc.sql("""
				INSERT INTO identity.person_signal (id, account_id, kind, source, hmac, key_version, recorded_at)
				VALUES (:id, :account, 'EMAIL', 'GITHUB_EMAIL', :hmac, 1, now())
				""").param("id", UUID.randomUUID()).param("account", account).param("hmac", hmac).update();
	}

	/** Holds the Job row while {@code command} runs: it must come to wait for that row with the application still free. */
	private void takesTheJobRowFirst(UUID application, Callable<ResultActions> command, int expected) throws Exception {
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try (Connection holder = this.context.getBean(javax.sql.DataSource.class).getConnection()) {
			holder.setAutoCommit(false);
			int holderPid = holdJobRow(holder);
			Future<Integer> running = pool.submit(() -> command.call().andReturn().getResponse().getStatus());
			try {
				await().atMost(Duration.ofSeconds(15))
					.until(() -> this.jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE :holder = ANY(pg_blocking_pids(pid))")
						.param("holder", holderPid).query(Long.class).single() > 0);
				assertThat(tryLock("matching.crew_application", application))
					.as("the application stayed free while the command waited for the Job row").isEqualTo("locked");
			}
			finally {
				holder.rollback();
			}
			assertThat(running.get(20, TimeUnit.SECONDS)).isEqualTo(expected);
		}
		finally {
			pool.shutdownNow();
		}
	}

	/** Locks the Job row on {@code holder}, whose transaction stays open; answers its backend's process ID. */
	private int holdJobRow(Connection holder) throws SQLException {
		try (PreparedStatement lock = holder.prepareStatement("SELECT id FROM job.job WHERE id = ? FOR UPDATE")) {
			lock.setObject(1, this.job);
			lock.executeQuery();
		}
		try (PreparedStatement pid = holder.prepareStatement("SELECT pg_backend_pid()"); ResultSet answer = pid.executeQuery()) {
			answer.next();
			return answer.getInt(1);
		}
	}

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
		return tryLock("job.job", this.job);
	}

	private String tryLock(String table, UUID id) {
		try (Connection connection = this.context.getBean(javax.sql.DataSource.class).getConnection()) {
			connection.setAutoCommit(false);
			try (PreparedStatement statement = connection.prepareStatement(
					"SELECT id FROM " + table + " WHERE id = ? FOR UPDATE NOWAIT")) {
				statement.setObject(1, id);
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
