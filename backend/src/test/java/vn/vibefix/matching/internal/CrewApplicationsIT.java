package vn.vibefix.matching.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;

import vn.vibefix.authz.JobContent;
import vn.vibefix.files.StoredFiles;
import vn.vibefix.identity.AccountLocked;
import vn.vibefix.identity.internal.CustomerTokens;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.job.CrewRecruitment.ClosedBy;
import vn.vibefix.job.CrewRecruitmentClosed;
import vn.vibefix.job.JobPostHidden;
import vn.vibefix.job.JobTransitioned;
import vn.vibefix.shared.ErasureOutcome;
import vn.vibefix.shared.PersonalData;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.TestFiles;
import vn.vibefix.team.internal.TeamTestSupport;
import vn.vibefix.timers.internal.DeadlinePoller;

/**
 * Story 21.4 (FR-81, AD-3, AD-4, AD-7, AD-19, AD-20, AD-24, AD-43, R3, R5, R6, X3), with {@code features.largeCrews}
 * off (a Crew of one): on an Agreement Job whose Mentor Hùng recruits one member with the Skills React and Supabase,
 * users with GitHub linked apply from FND-2 with a note, Skills and proof, and every refusal answers its plain reason;
 * the contributor read runs outside any lock, and the send takes the Job row lock first, so no application commits
 * after recruitment closes; the Mentor reads the Applicant cards and declines one; an application lapses after the
 * Job's copied days, a lock withdraws it, and an ended one is deleted with its proof 90 days after the decision.
 */
class CrewApplicationsIT extends IntegrationTest {

	private static final String TOKEN = "gho_customer_token_never_printed";

	private static final String CONTRIBUTORS_PATH = "/repos/lan/so-dat-ban/contributors";

	private static final HttpServer GITHUB = start();

	/** What the stub answers for each path: a status and a body. */
	private static final Map<String, String> ANSWERS = new ConcurrentHashMap<>();

	/** Runs on the stub's thread before it answers a contributors read. */
	private static final AtomicReference<Runnable> BEFORE_CONTRIBUTORS = new AtomicReference<>();

	private static final AtomicInteger CONTRIBUTOR_READS = new AtomicInteger();

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
	private PersonalData personalData;

	@Autowired
	private DeadlinePoller poller;

	@Autowired
	private CrewApplicationPurge purge;

	@Autowired
	private Recommendations recommendations;

	@Autowired
	private MatchingPersonalData matching;

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
	void anAgreementJobWhoseMentorRecruitsOneMember() throws Exception {
		ANSWERS.clear();
		ANSWERS.put(CONTRIBUTORS_PATH, "200 [{\"id\":987654321,\"login\":\"lan\"}]");
		BEFORE_CONTRIBUTORS.set(null);
		CONTRIBUTOR_READS.set(0);
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		CustomerTokens.keep(this.context, this.lan, TOKEN);
		this.hung = TeamTestSupport.mentor(this.jdbc, "Đỗ Mạnh Hùng", List.of(TeamTestSupport.REACT), "React");
		this.product = product(this.lan);
		this.job = JobContent.job(this.jdbc, this.product, this.lan, this.hung, "Sửa lỗi đặt bàn", JobContent.SIGNED,
				"AGREEMENT");
		JobContent.selectedBid(this.jdbc, this.job, this.product, this.lan, this.hung, JobContent.SIGNED);
		recruit(this.job, this.hung, 0, true).andExpect(status().isOk());
		this.minh = applicant("Trần Quang Minh");
	}

	@AfterEach
	void forget() {
		ANSWERS.clear();
		BEFORE_CONTRIBUTORS.set(null);
	}

	@Test
	void aLinkedUserAppliesWithANoteTwoSkillsAndALinkAndTheMentorReadsTheApplicantCard() throws Exception {
		Instant now = this.clock.instant();
		UUID id = id(apply(this.minh, this.job, true, List.of(TeamTestSupport.REACT, TeamTestSupport.SUPABASE),
				proof(link("https://docs.example.vn/du-an-lop", "Dự án môn học của tôi")))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("PENDING"))
			.andExpect(jsonPath("$.skills.length()").value(2))
			.andExpect(jsonPath("$.proof[0].domain").value("docs.example.vn"))
			.andExpect(jsonPath("$.lapsesAt").value(now.plus(Duration.ofDays(14)).toString())));

		assertThat(text("""
				SELECT params ->> 'name' FROM notification.notification
				WHERE type = 'matching.crew_application_received' AND account_id = :hung
				""", Map.of("hung", this.hung))).isEqualTo("Trần Quang Minh");
		assertThat(text("""
				SELECT (params ->> 'n') || ' ' || tier FROM notification.todo
				WHERE todo_key = 'matching.review_crew_applications' AND account_id = :hung AND closed_at IS NULL
				""", Map.of("hung", this.hung))).as("tier 2 while no member has signed").isEqualTo("1 2");
		assertThat(count("SELECT count(*) FROM timers.deadline WHERE kind = 'matching.crew_application_lapse' AND subject_id = :id",
				Map.of("id", id))).isOne();
		assertThat(customerRows()).as("the copied Customer and Mentor").isEqualTo(this.lan + " " + this.hung);

		this.mvc.perform(get("/api/v1/mentor/jobs/" + this.job + "/crew-applications").with(as(this.hung)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].id").value(id.toString()))
			.andExpect(jsonPath("$[0].note").value("Mình đã làm kiểm thử phân quyền cho đồ án."))
			.andExpect(jsonPath("$[0].applicant.displayName").value("Trần Quang Minh"))
			.andExpect(jsonPath("$[0].proof[0].url").value("https://docs.example.vn/du-an-lop"))
			.andExpect(jsonPath("$[0].fitMetrics.sharedSkills[0]").value("React"))
			.andExpect(jsonPath("$[0].fitMetrics.skillsSought").value(2));
		this.mvc.perform(get("/api/v1/mentor/jobs/" + this.job + "/crew-applications").with(as(this.lan)))
			.andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/mentor/crew-applications").with(as(this.lan))).andExpect(jsonPath("$.length()").value(0));
		this.mvc.perform(get("/api/v1/mentor/crew-applications").with(as(this.minh)))
			.andExpect(jsonPath("$[0].jobTitle").value("Sửa lỗi đặt bàn"))
			.andExpect(jsonPath("$[0].status").value("PENDING"));
		apply(this.minh, this.job, true, List.of(), "[]").andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("ALREADY_APPLIED"));
		assertThat(CONTRIBUTOR_READS.get()).as("a refusal before the contributor rule asks GitHub nothing").isOne();
		apply(this.minh, this.job, false, List.of(), "[]").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reason").value("PUBLIC_CODE_NOT_ACKNOWLEDGED"));
	}

	@Test
	void theJobsCustomerMentorAndCrewHoldTheirRole() throws Exception {
		UUID member = applicant("Phạm Thu Hà");
		JobContent.crewRow(this.jdbc, this.job, member, "ACTIVE", null);
		this.jdbc.sql("UPDATE job.job SET open_slots = 1 WHERE id = :id").param("id", this.job).update();

		for (UUID role : List.of(this.lan, this.hung)) {
			apply(role, this.job, true, List.of(), "[]").andExpect(status().is(422))
				.andExpect(jsonPath("$.reason").value("ROLE_TAKEN"));
		}
		apply(member, this.job, true, List.of(), "[]").andExpect(status().is(422))
			.andExpect(jsonPath("$.reason").value("ROLE_TAKEN"));
		assertThat(CONTRIBUTOR_READS.get()).isZero();
		assertThat(count("SELECT count(*) FROM matching.crew_application", Map.of())).isZero();
	}

	@Test
	void aMemberWhoLeftNeverRejoinsAndAMemberOfAnotherRunningCrewIsBusy() throws Exception {
		UUID left = applicant("Lê Văn Tài");
		JobContent.crewRow(this.jdbc, this.job, left, "LEFT", this.clock.instant().minus(Duration.ofDays(1)));
		apply(left, this.job, true, List.of(), "[]").andExpect(status().is(422))
			.andExpect(jsonPath("$.reason").value("CREW_REJOIN_REFUSED"));
		uploadAs(left).andExpect(status().is(422)).andExpect(jsonPath("$.reason").value("CREW_REJOIN_REFUSED"));

		UUID other = JobContent.activeJob(this.jdbc, this.product, this.lan, this.hung, "Việc khác");
		UUID busy = applicant("Phạm Hải Tùng");
		JobContent.crewRow(this.jdbc, other, busy, "ACTIVE", null);
		apply(busy, this.job, true, List.of(), "[]").andExpect(status().is(422))
			.andExpect(jsonPath("$.reason").value("ON_ANOTHER_CREW"));
		uploadAs(busy).andExpect(status().is(422)).andExpect(jsonPath("$.reason").value("ON_ANOTHER_CREW"));
		assertThat(CONTRIBUTOR_READS.get()).isZero();
	}

	@Test
	void aBidderNotSelectedMayApplyAndAnOpenBidHoldsTheRole() throws Exception {
		bidOn(this.job, this.minh, "CLOSED");
		apply(this.minh, this.job, true, List.of(), "[]").andExpect(status().isCreated());

		UUID ha = applicant("Phạm Thu Hà");
		bidOn(this.job, ha, "SUBMITTED");
		apply(ha, this.job, true, List.of(), "[]").andExpect(status().is(422))
			.andExpect(jsonPath("$.reason").value("ROLE_TAKEN"));
		assertThatThrownBy(() -> insertApplication(ha)).as("the trigger is the backstop")
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insertApplication(this.lan)).as("the CHECK is the backstop")
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void aClosedOrFullRecruitmentRefusesWithItsReason() throws Exception {
		JobContent.crewRow(this.jdbc, this.job, applicant("Phạm Thu Hà"), "ASSIGNED", null);
		apply(this.minh, this.job, true, List.of(), "[]").andExpect(status().is(422))
			.andExpect(jsonPath("$.reason").value("CREW_FULL"))
			.andExpect(jsonPath("$.n").value(1));

		recruit(this.job, this.hung, crewVersion(this.job), false).andExpect(status().isOk());
		apply(this.minh, this.job, true, List.of(), "[]").andExpect(status().is(422))
			.andExpect(jsonPath("$.reason").value("RECRUITMENT_CLOSED"));
		this.mvc.perform(multipart("/api/v1/mentor/job-posts/" + this.job + "/crew-application-files")
			.file(new MockMultipartFile("file", "a.pdf", "application/pdf", TestFiles.pdf()))
			.with(as(this.minh))
			.with(csrf())).andExpect(status().is(422)).andExpect(jsonPath("$.reason").value("RECRUITMENT_CLOSED"));
		assertThat(CONTRIBUTOR_READS.get()).isZero();
	}

	@Test
	void tenApplicationsADayAndNoMore() throws Exception {
		Timestamp now = Timestamp.from(this.clock.instant());
		for (int i = 0; i < CrewApplications.DAILY_LIMIT; i++) {
			this.jdbc.sql("""
					INSERT INTO matching.crew_application (id, job_id, product_id, customer_account_id, mentor_account_id,
					    applicant_account_id, status, note, public_code_notice_version, submitted_at, lapses_at)
					VALUES (:id, :job, :product, :lan, :hung, :minh, 'PENDING', 'Đơn khác', '1.0', :now,
					    CAST(:now AS TIMESTAMPTZ) + INTERVAL '14 days')
					""").param("id", UUID.randomUUID()).param("job", UUID.randomUUID()).param("product", this.product)
				.param("lan", this.lan).param("hung", this.hung).param("minh", this.minh).param("now", now).update();
		}

		apply(this.minh, this.job, true, List.of(), "[]").andExpect(status().isTooManyRequests())
			.andExpect(header().exists("Retry-After"));
		assertThat(CONTRIBUTOR_READS.get()).isZero();
	}

	@Test
	void aSignalSharedWithTheMentorRefusesWithoutSayingWhyAndAlertsEveryAdministrator() throws Exception {
		UUID admin = administrator();
		String hmac = "cd".repeat(32);
		for (UUID account : List.of(this.minh, this.hung)) {
			this.jdbc.sql("""
					INSERT INTO identity.person_signal (id, account_id, kind, source, hmac, key_version, recorded_at)
					VALUES (:id, :account, 'EMAIL', 'GITHUB_EMAIL', :hmac, 1, now())
					""").param("id", UUID.randomUUID()).param("account", account).param("hmac", hmac).update();
		}

		String body = apply(this.minh, this.job, true, List.of(), "[]").andExpect(status().is(422))
			.andExpect(jsonPath("$.reason").value("CONFLICT_OF_INTEREST"))
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(body).doesNotContainIgnoringCase("email");
		assertThat(count("""
				SELECT count(*) FROM notification.notification WHERE type = 'job.conflict_of_interest_refused' AND account_id = :admin
				""", Map.of("admin", admin))).as("committed despite the refusal").isOne();
		assertThat(count("SELECT count(*) FROM matching.crew_application", Map.of())).isZero();
	}

	@Test
	void aContributorIsRefusedAndGitHubNotAnsweringFailsClosed() throws Exception {
		long minhGitHub = this.jdbc.sql("SELECT github_user_id FROM identity.account WHERE id = :id").param("id", this.minh)
			.query(Long.class).single();
		ANSWERS.put(CONTRIBUTORS_PATH, "200 [{\"id\":" + minhGitHub + ",\"login\":\"minh\"}]");
		apply(this.minh, this.job, true, List.of(), "[]").andExpect(status().is(422))
			.andExpect(jsonPath("$.reason").value("APPLICANT_IS_CONTRIBUTOR"));

		ANSWERS.put(CONTRIBUTORS_PATH, "500 {}");
		apply(this.minh, this.job, true, List.of(), "[]").andExpect(status().is(422))
			.andExpect(jsonPath("$.reason").value("CONTRIBUTORS_UNKNOWN"));
		assertThat(count("SELECT count(*) FROM matching.crew_application", Map.of())).isZero();
	}

	/**
	 * A-03, A-14: while GitHub answers, no lock of the send is held, so the Mentor may close recruitment meanwhile; the
	 * send then reads the recruitment again under the Job row lock and refuses.
	 */
	@Test
	void gitHubIsReadOutsideAnyLockAndTheRecruitmentIsReadAgainUnderTheJobRowLock() throws Exception {
		AtomicBoolean blocked = new AtomicBoolean();
		BEFORE_CONTRIBUTORS.set(() -> {
			try {
				new TransactionTemplate(this.transactions).executeWithoutResult(status -> {
					this.jdbc.sql("SET LOCAL lock_timeout = '3s'").update();
					this.jdbc.sql("UPDATE job.job SET recruiting = false WHERE id = :id").param("id", this.job).update();
				});
			}
			catch (RuntimeException held) {
				blocked.set(true);
			}
		});

		apply(this.minh, this.job, true, List.of(), "[]").andExpect(status().is(422))
			.andExpect(jsonPath("$.reason").value("RECRUITMENT_CLOSED"));

		assertThat(blocked).as("no lock held while GitHub answers").isFalse();
		assertThat(CONTRIBUTOR_READS.get()).isOne();
		assertThat(count("SELECT count(*) FROM matching.crew_application", Map.of())).isZero();
	}

	/**
	 * AD-4, A-14: the send takes the Job row lock first, so while the Mentor's closing transaction holds it the send
	 * waits, and then finds recruitment closed: no application commits after {@code CrewRecruitmentClosed}.
	 */
	@Test
	void aSendWaitsForTheJobRowLockSoNoneCommitsAfterRecruitmentCloses() throws Exception {
		CountDownLatch locked = new CountDownLatch(1);
		ExecutorService closer = Executors.newSingleThreadExecutor();
		try {
			Future<?> closing = closer.submit(() -> new TransactionTemplate(this.transactions).executeWithoutResult(status -> {
				this.jdbc.sql("SELECT id FROM job.job WHERE id = :id FOR UPDATE").param("id", this.job).query(UUID.class).single();
				this.jdbc.sql("UPDATE job.job SET recruiting = false WHERE id = :id").param("id", this.job).update();
				locked.countDown();
				try {
					Thread.sleep(1500);
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
			}));
			assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

			apply(this.minh, this.job, true, List.of(), "[]").andExpect(status().is(422))
				.andExpect(jsonPath("$.reason").value("RECRUITMENT_CLOSED"));

			closing.get(10, TimeUnit.SECONDS);
			assertThat(CONTRIBUTOR_READS.get()).as("the precheck saw the recruitment still open").isOne();
			assertThat(count("SELECT count(*) FROM matching.crew_application", Map.of())).isZero();
		}
		finally {
			closer.shutdownNow();
		}
	}

	@Test
	void theMentorDeclinesWithoutAReasonAndTheApplicantLearnsOnlyThat() throws Exception {
		UUID id = id(apply(this.minh, this.job, true, List.of(), "[]").andExpect(status().isCreated()));

		decline(this.lan, id).andExpect(status().isNotFound());
		decline(this.minh, id).andExpect(status().isNotFound());
		decline(this.hung, id).andExpect(status().isNoContent());

		this.mvc.perform(get("/api/v1/mentor/crew-applications").with(as(this.minh)))
			.andExpect(jsonPath("$[0].status").value("DECLINED"))
			.andExpect(jsonPath("$[0].endReason").value("DECLINED"));
		assertThat(count("""
				SELECT count(*) FROM notification.notification WHERE type = 'matching.crew_application_declined'
				    AND account_id = :minh
				""", Map.of("minh", this.minh))).isOne();
		assertThat(count("""
				SELECT count(*) FROM notification.todo WHERE todo_key = 'matching.review_crew_applications'
				    AND closed_at IS NULL
				""", Map.of())).isZero();
		decline(this.hung, id).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("NOT_DECLINABLE"));
		this.mvc.perform(get("/api/v1/mentor/jobs/" + this.job + "/crew-applications").with(as(this.hung)))
			.andExpect(jsonPath("$[0].status").value("DECLINED"))
			.andExpect(jsonPath("$[0].proof.length()").value(0))
			.andExpect(jsonPath("$[0].fitMetrics").doesNotExist());
		// A declined applicant may apply again: only a pending application is unique.
		apply(this.minh, this.job, true, List.of(), "[]").andExpect(status().isCreated());
	}

	@Test
	void anApplicationLapsesAfterTheDaysTheJobCopiedAtPublication() throws Exception {
		this.jdbc.sql("UPDATE job.job SET rules = '{\"CREW_APPLICATION_LAPSE_DAYS\": 5}'::jsonb WHERE id = :id")
			.param("id", this.job)
			.update();
		Instant now = this.clock.instant();
		UUID id = id(apply(this.minh, this.job, true, List.of(), "[]").andExpect(status().isCreated())
			.andExpect(jsonPath("$.lapsesAt").value(now.plus(Duration.ofDays(5)).toString())));

		this.clock.advance(Duration.ofDays(5).plusMinutes(1));
		this.poller.runDue();

		assertThat(text("SELECT status || ' ' || end_reason FROM matching.crew_application WHERE id = :id", Map.of("id", id)))
			.isEqualTo("LAPSED EXPIRED");
		assertThat(text("""
				SELECT params ->> 'cause' FROM notification.notification WHERE type = 'matching.crew_application_lapsed'
				    AND account_id = :minh
				""", Map.of("minh", this.minh))).isEqualTo("EXPIRED");
	}

	@Test
	void theApplicantWithdrawsAndALockWithdrawsTheOtherTellingTheMentor() throws Exception {
		UUID id = id(apply(this.minh, this.job, true, List.of(), "[]").andExpect(status().isCreated()));
		UUID ha = applicant("Phạm Thu Hà");
		this.mvc.perform(post("/api/v1/mentor/crew-applications/" + id + "/withdraw").with(as(ha)).with(csrf()))
			.andExpect(status().isNotFound());
		this.mvc.perform(post("/api/v1/mentor/crew-applications/" + id + "/withdraw").with(as(this.minh)).with(csrf()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("WITHDRAWN"));
		this.mvc.perform(post("/api/v1/mentor/crew-applications/" + id + "/withdraw").with(as(this.minh)).with(csrf()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("NOT_WITHDRAWABLE"));

		UUID other = id(apply(ha, this.job, true, List.of(), "[]").andExpect(status().isCreated()));
		IdentityTestSupport.lock(this.jdbc, ha, "Vi phạm");
		AccountLocked locked = new AccountLocked(UUID.randomUUID(), ha, this.clock.instant());
		new TransactionTemplate(this.transactions).executeWithoutResult(status -> this.events.publishEvent(locked));

		await().atMost(Duration.ofSeconds(10)).until(() -> "WITHDRAWN APPLICANT_UNAVAILABLE".equals(
				text("SELECT status || ' ' || end_reason FROM matching.crew_application WHERE id = :id", Map.of("id", other))));
		assertThat(text("""
				SELECT params ->> 'name' FROM notification.notification
				WHERE type = 'matching.crew_application_auto_withdrawn' AND account_id = :hung
				""", Map.of("hung", this.hung))).isEqualTo("Phạm Thu Hà");
		awaitListeners();
	}

	@Test
	void anEndedApplicationAndItsProofAreDeletedNinetyDaysAfterTheDecision() throws Exception {
		String file = fileId(upload(this.minh));
		UUID id = id(apply(this.minh, this.job, true, List.of(), proof(fileItem(file, "Báo cáo kiểm thử")))
			.andExpect(status().isCreated()));
		this.mvc.perform(get("/api/v1/files/" + file).with(as(this.minh))).andExpect(status().isOk());
		this.mvc.perform(get("/api/v1/files/" + file).with(as(this.hung))).andExpect(status().isOk());
		this.mvc.perform(get("/api/v1/files/" + file).with(as(this.lan))).andExpect(status().isNotFound());
		decline(this.hung, id).andExpect(status().isNoContent());
		// The Mentor's card dropped it, and so does the file.
		this.mvc.perform(get("/api/v1/files/" + file).with(as(this.hung))).andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/files/" + file).with(as(this.minh))).andExpect(status().isOk());
		StoredFiles files = this.context.getBean(StoredFiles.class);

		this.clock.advance(Duration.ofDays(89));
		new TransactionTemplate(this.transactions).executeWithoutResult(status -> this.purge.purge());
		assertThat(count("SELECT count(*) FROM matching.crew_application WHERE id = :id", Map.of("id", id))).isOne();

		this.clock.advance(Duration.ofDays(2));
		new TransactionTemplate(this.transactions).executeWithoutResult(status -> this.purge.purge());
		assertThat(count("SELECT count(*) FROM matching.crew_application WHERE id = :id", Map.of("id", id))).isZero();
		assertThat(count("SELECT count(*) FROM matching.crew_application_proof", Map.of())).isZero();
		assertThat(files.find(UUID.fromString(file))).isEmpty();
		assertThat(count("""
				SELECT count(*) FROM audit.audit_entry e JOIN audit.audit_entry_object o ON o.entry_id = e.id
				WHERE e.action = 'matching.crew_application_purged' AND o.object_id = :id
				""", Map.of("id", id))).isOne();
	}

	@Test
	void aWithdrawnApplicationsProofIsTheApplicantsAlone() throws Exception {
		String file = fileId(upload(this.minh));
		UUID id = id(apply(this.minh, this.job, true, List.of(), proof(fileItem(file, "Báo cáo kiểm thử")))
			.andExpect(status().isCreated()));
		this.mvc.perform(post("/api/v1/mentor/crew-applications/" + id + "/withdraw").with(as(this.minh)).with(csrf()))
			.andExpect(status().isOk());

		this.mvc.perform(get("/api/v1/files/" + file).with(as(this.hung))).andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/files/" + file).with(as(this.minh))).andExpect(status().isOk());
	}

	@Test
	void theApplicantExportsTheirApplicationsAndAnErasureTellsTheMentorBeforeDeletingThem() throws Exception {
		String file = fileId(upload(this.minh));
		UUID id = id(apply(this.minh, this.job, true, List.of(TeamTestSupport.REACT), proof(fileItem(file, "Báo cáo")))
			.andExpect(status().isCreated()));

		String export = new TransactionTemplate(this.transactions).execute(status -> this.personalData.export(this.minh))
			.toString();
		assertThat(export).contains("crewApplications").contains("Mình đã làm kiểm thử phân quyền cho đồ án.")
			.contains("id=" + id)
			.contains("jobTitle=Sửa lỗi đặt bàn")
			.contains("crewApplicationId=" + id);

		ErasureOutcome outcome = new TransactionTemplate(this.transactions).execute(status -> this.personalData.erase(this.minh));
		assertThat(outcome).isInstanceOf(ErasureOutcome.Erased.class);
		assertThat(text("""
				SELECT params ->> 'name' FROM notification.notification
				WHERE type = 'matching.crew_application_auto_withdrawn' AND account_id = :hung
				""", Map.of("hung", this.hung))).isEqualTo("Trần Quang Minh");
		assertThat(count("SELECT count(*) FROM matching.crew_application", Map.of())).isZero();
		awaitListeners();
	}

	@Test
	void recommendationsSkipAJobWithAPendingApplicationAndABidOnlyWhileItNeedsAMentor() throws Exception {
		bidOn(this.job, this.minh, "CLOSED");
		assertThat(recommendedJobs(this.minh)).as("a bidder not selected may still join the Crew (R3)").contains(this.job);

		apply(this.minh, this.job, true, List.of(), "[]").andExpect(status().isCreated());

		assertThat(recommendedJobs(this.minh)).doesNotContain(this.job);
	}

	@Test
	void aDeadlineThatFindsTheApplicationDeclinedLapsesNothingAndSendsNothing() throws Exception {
		UUID id = id(apply(this.minh, this.job, true, List.of(), "[]").andExpect(status().isCreated()));
		decline(this.hung, id).andExpect(status().isNoContent());

		this.clock.advance(Duration.ofDays(15));
		this.poller.runDue();

		assertThat(text("SELECT status || ' ' || end_reason FROM matching.crew_application WHERE id = :id", Map.of("id", id)))
			.isEqualTo("DECLINED DECLINED");
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type = 'matching.crew_application_lapsed'",
				Map.of())).isZero();
		Boolean lapsed = new TransactionTemplate(this.transactions)
			.execute(status -> this.context.getBean(CrewApplications.class).lapse(id));
		assertThat(lapsed).isFalse();
	}

	@Test
	void aClosingLapsesOnlyWhatWasSentByThenAndAReplayChangesNothing() throws Exception {
		Instant closed = this.clock.instant();
		this.clock.advance(Duration.ofMinutes(5));
		UUID later = id(apply(this.minh, this.job, true, List.of(), "[]").andExpect(status().isCreated()));
		CrewRecruitmentClosed stale = new CrewRecruitmentClosed(UUID.randomUUID(), this.job, ClosedBy.MENTOR, closed);

		new TransactionTemplate(this.transactions).executeWithoutResult(status -> this.events.publishEvent(stale));
		awaitListeners();
		assertThat(text("SELECT status FROM matching.crew_application WHERE id = :id", Map.of("id", later)))
			.as("sent after that closing").isEqualTo("PENDING");

		CrewRecruitmentClosed now = new CrewRecruitmentClosed(UUID.randomUUID(), this.job, ClosedBy.MENTOR,
				this.clock.instant());
		new TransactionTemplate(this.transactions).executeWithoutResult(status -> this.events.publishEvent(now));
		await().atMost(Duration.ofSeconds(10)).until(() -> "LAPSED".equals(
				text("SELECT status FROM matching.crew_application WHERE id = :id", Map.of("id", later))));
		new TransactionTemplate(this.transactions).executeWithoutResult(status -> this.events.publishEvent(now));
		awaitListeners();
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type = 'matching.crew_application_lapsed'",
				Map.of())).as("one notice").isOne();
	}

	@Test
	void aHiddenJobLapsesItsApplicationsWithThatCauseAndTakesNoMore() throws Exception {
		UUID id = id(apply(this.minh, this.job, true, List.of(), "[]").andExpect(status().isCreated()));
		this.jdbc.sql("UPDATE job.job SET hidden_at = now(), hidden_reason = 'Vi phạm quy định' WHERE id = :id")
			.param("id", this.job)
			.update();
		JobPostHidden hidden = new JobPostHidden(UUID.randomUUID(), this.job);

		new TransactionTemplate(this.transactions).executeWithoutResult(status -> this.events.publishEvent(hidden));

		await().atMost(Duration.ofSeconds(10)).until(() -> "LAPSED JOB_HIDDEN".equals(
				text("SELECT status || ' ' || coalesce(end_reason, '') FROM matching.crew_application WHERE id = :id",
						Map.of("id", id))));
		assertThat(text("""
				SELECT params ->> 'cause' FROM notification.notification
				WHERE type = 'matching.crew_application_lapsed' AND account_id = :minh
				""", Map.of("minh", this.minh))).isEqualTo("JOB_HIDDEN");
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type = 'job.job_post_hidden'", Map.of()))
			.as("no bid's notice").isZero();
		UUID ha = applicant("Phạm Thu Hà");
		apply(ha, this.job, true, List.of(), "[]").andExpect(status().is(422))
			.andExpect(jsonPath("$.reason").value("RECRUITMENT_CLOSED"));
		uploadAs(ha).andExpect(status().is(422)).andExpect(jsonPath("$.reason").value("RECRUITMENT_CLOSED"));
		awaitListeners();
	}

	@Test
	void uploadsStopAtFiveAndAWaitingUploadIsRemoved() throws Exception {
		String first = fileId(upload(this.minh));
		for (int i = 1; i < BidProofs.MAX; i++) {
			upload(this.minh);
		}
		uploadAs(this.minh).andExpect(status().isBadRequest()).andExpect(jsonPath("$.reason").value("PROOF_LIMIT"));
		StoredFiles files = this.context.getBean(StoredFiles.class);

		this.mvc.perform(delete("/api/v1/mentor/job-posts/" + this.job + "/crew-application-files/" + first)
			.with(as(this.hung)).with(csrf())).andExpect(status().isNotFound());
		this.mvc.perform(delete("/api/v1/mentor/job-posts/" + this.job + "/crew-application-files/" + first)
			.with(as(this.minh)).with(csrf())).andExpect(status().isNoContent());

		assertThat(files.find(UUID.fromString(first))).isEmpty();
		this.mvc.perform(get("/api/v1/mentor/job-posts/" + this.job + "/crew-application-files").with(as(this.minh)))
			.andExpect(jsonPath("$.length()").value(BidProofs.MAX - 1));
		upload(this.minh);
	}

	@Test
	void anErasedCustomersOrMentorsJobsLoseTheirApplicationsWithTheirProofAudited() throws Exception {
		for (UUID erased : List.of(this.lan, this.hung)) {
			Applied applied = appliedWithAFile(applicant("Người xin " + erased));

			new TransactionTemplate(this.transactions).executeWithoutResult(status -> this.matching.eraseAccount(erased));

			assertDeletedAndAudited(applied);
		}
	}

	@Test
	void deletingTheProductOrRetiringItsJobDataDeletesTheApplicationsWithTheirProofAudited() throws Exception {
		Applied first = appliedWithAFile(this.minh);
		new TransactionTemplate(this.transactions).executeWithoutResult(status -> this.matching.deleteProduct(this.product));
		assertDeletedAndAudited(first);

		Applied second = appliedWithAFile(applicant("Phạm Thu Hà"));
		new TransactionTemplate(this.transactions).executeWithoutResult(status -> this.matching.retireJobData(this.product));
		assertDeletedAndAudited(second);
	}

	@Test
	void thePurgeDeletesAnUploadNoApplicationTookWithinNinetyDays() throws Exception {
		String file = fileId(upload(this.minh));
		UUID row = this.jdbc.sql("SELECT id FROM matching.crew_application_proof WHERE file_id = CAST(:file AS UUID)")
			.param("file", file)
			.query(UUID.class)
			.single();

		this.clock.advance(Duration.ofDays(91));
		new TransactionTemplate(this.transactions).executeWithoutResult(status -> this.purge.purge());

		assertThat(count("SELECT count(*) FROM matching.crew_application_proof", Map.of())).isZero();
		assertThat(this.context.getBean(StoredFiles.class).find(UUID.fromString(file))).isEmpty();
		assertThat(count("""
				SELECT count(*) FROM audit.audit_entry e JOIN audit.audit_entry_object o ON o.entry_id = e.id
				WHERE e.action = 'matching.crew_application_purged' AND o.object_id = :id
				""", Map.of("id", row))).isOne();
	}

	@Test
	void theReviewToDoClosesWhenTheOnlyApplicationEndsAndDropsToTierThreeOnceAMemberSigned() throws Exception {
		UUID first = id(apply(this.minh, this.job, true, List.of(), "[]").andExpect(status().isCreated()));
		assertThat(reviewTodo()).isEqualTo("1 2");
		this.mvc.perform(post("/api/v1/mentor/crew-applications/" + first + "/withdraw").with(as(this.minh)).with(csrf()))
			.andExpect(status().isOk());
		assertThat(reviewTodo()).as("withdrawn").isNull();

		apply(this.minh, this.job, true, List.of(), "[]").andExpect(status().isCreated());
		assertThat(reviewTodo()).isEqualTo("1 2");
		this.clock.advance(Duration.ofDays(15));
		this.poller.runDue();
		assertThat(reviewTodo()).as("lapsed").isNull();

		UUID ha = applicant("Phạm Thu Hà");
		apply(ha, this.job, true, List.of(), "[]").andExpect(status().isCreated());
		IdentityTestSupport.lock(this.jdbc, ha, "Vi phạm");
		AccountLocked locked = new AccountLocked(UUID.randomUUID(), ha, this.clock.instant());
		new TransactionTemplate(this.transactions).executeWithoutResult(status -> this.events.publishEvent(locked));
		await().atMost(Duration.ofSeconds(10)).until(() -> reviewTodo() == null);

		UUID tai = applicant("Lê Văn Tài");
		UUID tung = applicant("Phạm Hải Tùng");
		UUID taiApplication = id(apply(tai, this.job, true, List.of(), "[]").andExpect(status().isCreated()));
		apply(tung, this.job, true, List.of(), "[]").andExpect(status().isCreated());
		JobContent.crewRow(this.jdbc, this.job, applicant("Đặng Thu Trang"), "ACTIVE", null);
		this.mvc.perform(post("/api/v1/mentor/crew-applications/" + taiApplication + "/withdraw").with(as(tai)).with(csrf()))
			.andExpect(status().isOk());
		assertThat(reviewTodo()).as("a member has signed").isEqualTo("1 3");
		awaitListeners();
	}

	@Test
	void j4OpensNoRecruitmentToDoWhileRecruitmentIsAlreadyOn() throws Exception {
		JobTransitioned j4 = new JobTransitioned(UUID.randomUUID(), UUID.randomUUID(), this.job, "J4", "USER", "SELECTING",
				"AGREEMENT", 5, null, Map.of("mentorId", this.hung.toString(), "agreementDueAt",
						this.clock.instant().plus(Duration.ofDays(14)).toString()));

		new TransactionTemplate(this.transactions).executeWithoutResult(status -> this.events.publishEvent(j4));
		awaitListeners();

		assertThat(count("SELECT count(*) FROM notification.todo WHERE todo_key = 'matching.open_crew_recruitment'",
				Map.of())).isZero();
	}

	@Test
	void aStrangerFindsNoJobOutsideAgreementStartingAndActive() throws Exception {
		UUID draft = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, job_types, created_at, updated_at)
				VALUES (:id, :product, :lan, 'DRAFT', 'Bản nháp', ARRAY['BUG_FIX'], now(), now())
				""").param("id", draft).param("product", this.product).param("lan", this.lan).update();
		UUID closed = JobContent.job(this.jdbc, this.product, this.lan, this.hung, "Việc đã xong", JobContent.SIGNED,
				"CLOSED");

		for (UUID hidden : List.of(draft, closed, UUID.randomUUID())) {
			this.mvc.perform(post("/api/v1/mentor/job-posts/" + hidden + "/crew-applications").with(as(this.minh)).with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"note\":\"Mình muốn tham gia.\",\"publicCodeAcknowledged\":true}"))
				.andExpect(status().isNotFound());
			this.mvc.perform(multipart("/api/v1/mentor/job-posts/" + hidden + "/crew-application-files")
				.file(new MockMultipartFile("file", "a.pdf", "application/pdf", TestFiles.pdf()))
				.with(as(this.minh))
				.with(csrf())).andExpect(status().isNotFound());
		}
		// The Job's own Customer knows it.
		apply(this.lan, draft, true, List.of(), "[]").andExpect(status().is(422))
			.andExpect(jsonPath("$.reason").value("ROLE_TAKEN"));
		assertThat(CONTRIBUTOR_READS.get()).isZero();
	}

	@Test
	void anApplicantCardCountsTheJobPostsSkillsWhileTheMentorSeeksNone() throws Exception {
		this.jdbc.sql("UPDATE job.job SET skill_ids = ARRAY[CAST(:react AS UUID)], skill_names = ARRAY['React'] WHERE id = :id")
			.param("react", TeamTestSupport.REACT)
			.param("id", this.job)
			.update();
		this.mvc.perform(put("/api/v1/mentor/jobs/" + this.job + "/recruitment").with(as(this.hung)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"on\":true,\"openSlots\":1,\"skillIds\":[],\"crewVersion\":1}"))
			.andExpect(status().isOk());
		apply(this.minh, this.job, true, List.of(), "[]").andExpect(status().isCreated());

		this.mvc.perform(get("/api/v1/mentor/jobs/" + this.job + "/crew-applications").with(as(this.hung)))
			.andExpect(jsonPath("$[0].fitMetrics.skillsSought").value(1))
			.andExpect(jsonPath("$[0].fitMetrics.sharedSkills[0]").value("React"));
	}

	/** An application with one file of proof, as the matrix of deletions needs it. */
	private record Applied(UUID application, String file) {
	}

	private Applied appliedWithAFile(UUID applicant) throws Exception {
		String file = fileId(upload(applicant));
		UUID application = id(apply(applicant, this.job, true, List.of(), proof(fileItem(file, "Báo cáo kiểm thử")))
			.andExpect(status().isCreated()));
		return new Applied(application, file);
	}

	private void assertDeletedAndAudited(Applied applied) {
		assertThat(count("SELECT count(*) FROM matching.crew_application WHERE id = :id", Map.of("id", applied.application())))
			.isZero();
		assertThat(count("SELECT count(*) FROM matching.crew_application_proof WHERE crew_application_id = :id",
				Map.of("id", applied.application()))).isZero();
		assertThat(this.context.getBean(StoredFiles.class).find(UUID.fromString(applied.file()))).isEmpty();
		assertThat(count("""
				SELECT count(*) FROM audit.audit_entry e JOIN audit.audit_entry_object o ON o.entry_id = e.id
				WHERE e.action = 'matching.crew_application_purged' AND o.object_id = :id
				""", Map.of("id", applied.application()))).isOne();
	}

	/** The open review to-do's count and tier, or {@code null} without one. */
	private String reviewTodo() {
		return this.jdbc.sql("""
				SELECT (params ->> 'n') || ' ' || tier FROM notification.todo
				WHERE todo_key = 'matching.review_crew_applications' AND subject_id = :job AND closed_at IS NULL
				""").param("job", this.job).query(String.class).optional().orElse(null);
	}

	private ResultActions uploadAs(UUID applicant) throws Exception {
		return this.mvc.perform(multipart("/api/v1/mentor/job-posts/" + this.job + "/crew-application-files")
			.file(new MockMultipartFile("file", "bao-cao.pdf", "application/pdf", TestFiles.pdf()))
			.with(as(applicant))
			.with(csrf()));
	}

	private List<UUID> recommendedJobs(UUID user) {
		return new TransactionTemplate(this.transactions).execute(status -> this.recommendations.forMentor(user, null)
			.stream()
			.map(found -> found.post().jobId())
			.toList());
	}

	private String customerRows() {
		return text("SELECT customer_account_id || ' ' || mentor_account_id FROM matching.crew_application", Map.of());
	}

	private void insertApplication(UUID applicant) {
		this.jdbc.sql("""
				INSERT INTO matching.crew_application (id, job_id, product_id, customer_account_id, mentor_account_id,
				    applicant_account_id, status, note, public_code_notice_version, submitted_at, lapses_at)
				VALUES (:id, :job, :product, :lan, :hung, :applicant, 'PENDING', 'Đơn', '1.0', now(), now() + INTERVAL '1 day')
				""").param("id", UUID.randomUUID()).param("job", this.job).param("product", this.product).param("lan", this.lan)
			.param("hung", this.hung).param("applicant", applicant).update();
	}

	private void bidOn(UUID jobId, UUID bidder, String status) {
		boolean ended = !"SUBMITTED".equals(status);
		this.jdbc.sql("""
				INSERT INTO matching.application (id, job_id, product_id, customer_account_id, mentor_account_id, status,
				    price_vnd, timeline_days, approach, public_code_notice_version, submitted_at, ended_at, end_reason)
				VALUES (:id, :job, :product, :lan, :bidder, :status, 800000, 7, 'Cách làm', '1.0', now(),
				    CASE WHEN :ended THEN now() END, CASE WHEN :ended THEN 'NOT_SELECTED' END)
				""")
			.param("id", UUID.randomUUID())
			.param("job", jobId)
			.param("product", this.product)
			.param("lan", this.lan)
			.param("bidder", bidder)
			.param("status", status)
			.param("ended", ended)
			.update();
	}

	private UUID applicant(String name) {
		return TeamTestSupport.mentor(this.jdbc, name, List.of(TeamTestSupport.REACT), "React");
	}

	private ResultActions recruit(UUID jobId, UUID mentor, long crewVersion, boolean on) throws Exception {
		return this.mvc.perform(put("/api/v1/mentor/jobs/" + jobId + "/recruitment").with(as(mentor))
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"on":%s,"openSlots":1,"skillIds":["%s","%s"],"note":"Cần một bạn kiểm thử phân quyền","crewVersion":%d}
					""".formatted(on, TeamTestSupport.REACT, TeamTestSupport.SUPABASE, crewVersion)));
	}

	private long crewVersion(UUID jobId) {
		return this.jdbc.sql("SELECT crew_version FROM job.job WHERE id = :id").param("id", jobId).query(Long.class).single();
	}

	private ResultActions apply(UUID applicant, UUID jobId, boolean acknowledged, List<String> skills, String proof)
			throws Exception {
		String skillIds = "[" + String.join(",", skills.stream().map(skill -> "\"" + skill + "\"").toList()) + "]";
		return this.mvc.perform(post("/api/v1/mentor/job-posts/" + jobId + "/crew-applications").with(as(applicant))
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"note":"Mình đã làm kiểm thử phân quyền cho đồ án.","skillIds":%s,"publicCodeAcknowledged":%s,
					 "proof":%s}
					""".formatted(skillIds, acknowledged, proof)));
	}

	private ResultActions decline(UUID caller, UUID application) throws Exception {
		return this.mvc.perform(post("/api/v1/mentor/jobs/" + this.job + "/crew-applications/" + application + "/decline")
			.with(as(caller))
			.with(csrf()));
	}

	private ResultActions upload(UUID applicant) throws Exception {
		return this.mvc.perform(multipart("/api/v1/mentor/job-posts/" + this.job + "/crew-application-files")
			.file(new MockMultipartFile("file", "bao-cao.pdf", "application/pdf", TestFiles.pdf()))
			.with(as(applicant))
			.with(csrf())).andExpect(status().isCreated());
	}

	private static String proof(String... items) {
		return "[" + String.join(",", items) + "]";
	}

	private static String fileItem(String fileId, String caption) {
		return "{\"kind\":\"FILE\",\"fileId\":\"" + fileId + "\",\"caption\":\"" + caption + "\"}";
	}

	private static String link(String url, String caption) {
		return "{\"kind\":\"LINK\",\"url\":\"" + url + "\",\"caption\":\"" + caption + "\"}";
	}

	private static String fileId(ResultActions uploaded) throws Exception {
		return JsonPath.read(uploaded.andReturn().getResponse().getContentAsString(), "$.fileId");
	}

	private static UUID id(ResultActions created) throws Exception {
		return UUID.fromString(JsonPath.read(created.andReturn().getResponse().getContentAsString(), "$.id"));
	}

	private long count(String sql, Map<String, ?> params) {
		JdbcClient.StatementSpec spec = this.jdbc.sql(sql);
		for (Map.Entry<String, ?> param : params.entrySet()) {
			spec = spec.param(param.getKey(), param.getValue());
		}
		return spec.query(Long.class).single();
	}

	private String text(String sql, Map<String, ?> params) {
		JdbcClient.StatementSpec spec = this.jdbc.sql(sql);
		for (Map.Entry<String, ?> param : params.entrySet()) {
			spec = spec.param(param.getKey(), param.getValue());
		}
		return spec.query(String.class).single();
	}

	private void awaitListeners() {
		await().atMost(Duration.ofSeconds(10)).until(() -> this.jdbc.sql(
				"SELECT count(*) FROM platform.event_publication WHERE completion_date IS NULL").query(Long.class).single() == 0);
	}

	private UUID administrator() {
		UUID admin = IdentityTestSupport.insertAccount(this.jdbc, Role.ADMINISTRATOR, null);
		this.jdbc.sql("""
				INSERT INTO identity.administrator_credential (account_id, email, display_name, password_hash,
				    totp_key_version, totp_nonce, totp_ciphertext, created_at)
				VALUES (:id, :email, 'Quản trị', '$argon2id$x', 1, '\\x00'::bytea, '\\x00'::bytea, now())
				""").param("id", admin).param("email", "qt-" + admin.toString().substring(0, 8) + "@vibefix.vn").update();
		return admin;
	}

	private UUID product(UUID owner) {
		UUID id = UUID.randomUUID();
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO product.product (id, customer_account_id, name, description, ai_tools, repository_github_id,
				    repository_full_name, repository_owner_type, repository_default_branch, public_notice_version,
				    copy_permission_version, detection_notice_version, acknowledged_at, created_at)
				VALUES (:id, :owner, 'Sổ đặt bàn Quán Mây', 'Đặt bàn cho quán.', ARRAY['LOVABLE'], :github,
				    'lan/so-dat-ban', 'User', 'main', '1.0', '1.0', '1.0', :now, :now)
				""")
			.param("id", id)
			.param("owner", owner)
			.param("github", ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE))
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
				if (exchange.getRequestURI().getPath().endsWith("/contributors")) {
					CONTRIBUTOR_READS.incrementAndGet();
					Runnable before = BEFORE_CONTRIBUTORS.get();
					if (before != null) {
						before.run();
					}
				}
				String answer = ANSWERS.getOrDefault(exchange.getRequestURI().getPath(), "404 {}");
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
