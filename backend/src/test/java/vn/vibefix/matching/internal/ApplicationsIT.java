package vn.vibefix.matching.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.sun.net.httpserver.HttpServer;

import vn.vibefix.identity.AccountLocked;
import vn.vibefix.identity.MentorApprovalStatus;
import vn.vibefix.identity.internal.CustomerTokens;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.MutableClock;
import vn.vibefix.team.internal.TeamTestSupport;

/** FR-13, AD-19, AD-33, AD-34: Applications to Open Job Posts, their refusals, and what ends them. */
class ApplicationsIT extends IntegrationTest {

	private static final String TOKEN = "gho_customer_token_never_printed";

	private static final HttpServer GITHUB = start();

	/** What the stub answers for {@code /repos/{owner}/{name}/contributors}: a status and a body. */
	private static final Map<String, String> CONTRIBUTORS = new ConcurrentHashMap<>();

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

	private UUID job;

	@DynamicPropertySource
	static void stubGitHub(DynamicPropertyRegistry registry) {
		registry.add("vibefix.github.api-url", () -> "http://127.0.0.1:" + GITHUB.getAddress().getPort());
	}

	@BeforeEach
	void aCustomerWithAnOpenJobPost() {
		CONTRIBUTORS.clear();
		CONTRIBUTORS.put("/repos/lan/so-dat-ban/contributors", "200 [{\"id\":987654321,\"login\":\"lan\"},{\"login\":\"anon\"}]");
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null, null);
		CustomerTokens.keep(this.context, this.lan, TOKEN);
		this.job = openJob(product(this.lan), "Sửa lỗi mất đơn hàng");
	}

	@AfterEach
	void forget() {
		CONTRIBUTORS.clear();
	}

	@Test
	void aMentorWithAFreeStudentAppliesAndTheCustomerIsToldOnce() throws Exception {
		UUID hung = mentorWithFreeStudent("Đỗ Mạnh Hùng");
		UUID ha = mentorWithFreeStudent("Phạm Thu Hà");
		this.mvc.perform(post("/api/v1/jobs/" + this.job + "/invitations").with(as(this.lan, Role.CUSTOMER)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content("{\"mentorId\":\"" + hung + "\"}")).andExpect(status().isCreated());

		apply(hung, this.job, true).andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("SUBMITTED"))
			.andExpect(jsonPath("$.priceVnd").value(800000))
			.andExpect(jsonPath("$.jobTitle").value("Sửa lỗi mất đơn hàng"));
		assertThat(todoText()).isEqualTo("1");
		this.clock.advance(Duration.ofMinutes(1));
		apply(ha, this.job, true).andExpect(status().isCreated());

		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM notification.notification WHERE type = 'matching.application_received' AND account_id = :lan
				""").param("lan", this.lan).query(Long.class).single()).as("only the first one notifies").isOne();
		assertThat(todoText()).isEqualTo("2");
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/applications").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[1].mentor.displayName").value("Đỗ Mạnh Hùng"))
			.andExpect(jsonPath("$[1].mentor.teamSize").value(1))
			.andExpect(jsonPath("$[1].approach").value("Tái hiện lỗi rồi sửa và thêm kiểm thử."))
			.andExpect(jsonPath("$[1].invited").value(true))
			.andExpect(jsonPath("$[0].invited").value(false));
		assertThat(this.jdbc.sql("SELECT status FROM matching.invitation WHERE job_id = :job AND mentor_account_id = :hung")
			.param("job", this.job).param("hung", hung).query(String.class).single())
			.as("the invited Mentor's Application answers the invitation").isEqualTo("APPLIED");
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM notification.todo WHERE todo_key = 'matching.answer_invitation_to_apply' AND closed_at IS NULL
				""").query(Long.class).single()).isZero();
		UUID other = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null, null);
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/applications").with(as(other, Role.CUSTOMER)))
			.andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/mentor/applications").with(as(hung, Role.MENTOR)))
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].jobId").value(this.job.toString()));
		assertThat(this.jdbc.sql("SELECT count(*) FROM audit.audit_entry WHERE action = 'matching.application_submitted'")
			.query(Long.class).single()).isEqualTo(2);
	}

	@Test
	void eachRuleOfFr13RefusesWithItsReason() throws Exception {
		UUID hung = mentorWithFreeStudent("Đỗ Mạnh Hùng");
		apply(hung, this.job, false).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reason").value("PUBLIC_CODE_NOT_ACKNOWLEDGED"));
		apply(hung, this.job, true).andExpect(status().isCreated());
		apply(hung, this.job, true).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("ALREADY_APPLIED"));

		UUID lonely = TeamTestSupport.mentor(this.jdbc, MentorApprovalStatus.APPROVED, "Chưa có nhóm",
				List.of(TeamTestSupport.REACT), "React");
		apply(lonely, this.job, true).andExpect(status().is(422)).andExpect(jsonPath("$.reason").value("NO_FREE_STUDENT"));

		UUID hidden = openJob(product(this.lan), "Tin bị ẩn");
		this.jdbc.sql("UPDATE job.job SET hidden_at = now(), hidden_reason = 'Vi phạm' WHERE id = :id").param("id", hidden).update();
		apply(mentorWithFreeStudent("A"), hidden, true).andExpect(jsonPath("$.reason").value("JOB_POST_HIDDEN"));
		UUID draft = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, job_types, created_at, updated_at)
				VALUES (:id, :product, :owner, 'DRAFT', 'Nháp', ARRAY['BUG_FIX'], now(), now())
				""").param("id", draft).param("product", product(this.lan)).param("owner", this.lan).update();
		apply(mentorWithFreeStudent("B"), draft, true).andExpect(jsonPath("$.reason").value("JOB_POST_NOT_OPEN"));

		UUID contributor = mentorWithFreeStudent("Từng đóng góp");
		this.jdbc.sql("UPDATE identity.account SET github_user_id = 987654321 WHERE id = :id").param("id", contributor).update();
		apply(contributor, this.job, true).andExpect(status().is(422))
			.andExpect(jsonPath("$.reason").value("APPLICANT_IS_CONTRIBUTOR"));
		CONTRIBUTORS.put("/repos/lan/so-dat-ban/contributors", "502 {}");
		apply(mentorWithFreeStudent("C"), this.job, true).andExpect(status().is(422))
			.andExpect(jsonPath("$.reason").value("CONTRIBUTORS_UNKNOWN"));
		assertThat(this.jdbc.sql("SELECT count(*) FROM matching.application").query(Long.class).single())
			.as("a refusal writes nothing").isOne();
	}

	@Test
	void aSharedPersonSignalRefusesWithoutSayingWhyAndAlertsEveryAdministrator() throws Exception {
		UUID admin = administrator();
		UUID hung = mentorWithFreeStudent("Đỗ Mạnh Hùng");
		String hmac = "ab".repeat(32);
		for (UUID account : List.of(hung, this.lan)) {
			this.jdbc.sql("""
					INSERT INTO identity.person_signal (id, account_id, kind, source, hmac, key_version, recorded_at)
					VALUES (:id, :account, 'EMAIL', 'GITHUB_EMAIL', :hmac, 1, now())
					""").param("id", UUID.randomUUID()).param("account", account).param("hmac", hmac).update();
		}

		String body = apply(hung, this.job, true).andExpect(status().is(422))
			.andExpect(jsonPath("$.reason").value("CONFLICT_OF_INTEREST"))
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(body).doesNotContainIgnoringCase("email").doesNotContainIgnoringCase("linkedin");
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM notification.notification
				WHERE type = 'matching.conflict_of_interest_refused' AND account_id = :admin
				""").param("admin", admin).query(Long.class).single()).as("committed despite the refusal").isOne();
		assertThat(this.jdbc.sql("SELECT count(*) FROM matching.application").query(Long.class).single()).isZero();
	}

	@Test
	void twentyApplicationsADayAndNoMore() throws Exception {
		UUID hung = mentorWithFreeStudent("Đỗ Mạnh Hùng");
		for (int i = 0; i < Applications.DAILY_LIMIT; i++) {
			UUID other = openJob(product(this.lan), "Tin " + i);
			this.jdbc.sql("""
					INSERT INTO matching.application (id, job_id, product_id, customer_account_id, mentor_account_id, status,
					    price_vnd, timeline_days, approach, public_code_notice_version, submitted_at)
					SELECT :id, j.id, j.product_id, j.customer_account_id, :mentor, 'SUBMITTED', 1000, 1, 'x', '1.0', :now
					FROM job.job j WHERE j.id = :job
					""").param("id", UUID.randomUUID()).param("mentor", hung).param("job", other)
				.param("now", Timestamp.from(this.clock.instant())).update();
		}

		apply(hung, this.job, true).andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("RATE_LIMITED"));
	}

	@Test
	void theMentorWithdrawsAndJ2ClosesTheRest() throws Exception {
		UUID hung = mentorWithFreeStudent("Đỗ Mạnh Hùng");
		UUID ha = mentorWithFreeStudent("Phạm Thu Hà");
		UUID mine = applicationId(apply(hung, this.job, true));
		apply(ha, this.job, true);

		this.mvc.perform(post("/api/v1/mentor/applications/" + mine + "/withdraw").with(as(ha, Role.MENTOR)).with(csrf()))
			.andExpect(status().isNotFound());
		this.mvc.perform(post("/api/v1/mentor/applications/" + mine + "/withdraw").with(as(hung, Role.MENTOR)).with(csrf()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("WITHDRAWN"));
		this.mvc.perform(post("/api/v1/mentor/applications/" + mine + "/withdraw").with(as(hung, Role.MENTOR)).with(csrf()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("NOT_WITHDRAWABLE"));
		assertThat(todoText()).isEqualTo("1");
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/applications").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.length()").value(1));

		this.mvc.perform(post("/api/v1/jobs/" + this.job + "/withdraw").with(as(this.lan, Role.CUSTOMER)).with(csrf()))
			.andExpect(status().isOk());

		await().atMost(Duration.ofSeconds(10)).until(() -> this.jdbc.sql("""
				SELECT count(*) FROM matching.application WHERE job_id = :job AND status = 'CLOSED' AND end_reason = 'JOB_WITHDRAWN'
				""").param("job", this.job).query(Long.class).single() == 1);
		assertThat(todoText()).as("the to-do closes with the Job Post").isNull();
	}

	@Test
	void aLockedMentorsApplicationsAreWithdrawnOnce() throws Exception {
		UUID hung = mentorWithFreeStudent("Đỗ Mạnh Hùng");
		apply(hung, this.job, true).andExpect(status().isCreated());
		AccountLocked locked = new AccountLocked(UUID.randomUUID(), hung, Role.MENTOR);

		for (int i = 0; i < 2; i++) {
			new TransactionTemplate(this.transactions).executeWithoutResult(status -> this.events.publishEvent(locked));
		}

		await().atMost(Duration.ofSeconds(10)).until(() -> this.jdbc.sql("""
				SELECT count(*) FROM matching.application WHERE status = 'WITHDRAWN' AND end_reason = 'MENTOR_UNAVAILABLE'
				""").query(Long.class).single() == 1);
		await().atMost(Duration.ofSeconds(10)).until(() -> this.jdbc.sql("""
				SELECT count(*) FROM notification.notification WHERE type = 'matching.application_auto_withdrawn'
				""").query(Long.class).single() == 1);
		assertThat(this.jdbc.sql("""
				SELECT params ->> 'mentor' FROM notification.notification WHERE type = 'matching.application_auto_withdrawn'
				""").query(String.class).single()).isEqualTo("Đỗ Mạnh Hùng");
		assertThat(todoText()).isNull();
	}

	private ResultActions apply(UUID mentor, UUID jobId, boolean acknowledged) throws Exception {
		return this.mvc.perform(post("/api/v1/mentor/job-posts/" + jobId + "/applications").with(as(mentor, Role.MENTOR))
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"priceVnd":800000,"timelineDays":7,"approach":"Tái hiện lỗi rồi sửa và thêm kiểm thử.",
					 "publicCodeAcknowledged":%s}
					""".formatted(acknowledged)));
	}

	private static UUID applicationId(ResultActions created) throws Exception {
		return UUID.fromString(com.jayway.jsonpath.JsonPath.read(created.andReturn().getResponse().getContentAsString(), "$.id"));
	}

	/** The open to-do's count, or {@code null} without an open one. */
	private String todoText() {
		return this.jdbc.sql("""
				SELECT params ->> 'n' FROM notification.todo
				WHERE todo_key = 'matching.choose_assignee' AND subject_id = :job AND closed_at IS NULL
				""").param("job", this.job).query(String.class).optional().orElse(null);
	}

	private UUID mentorWithFreeStudent(String name) {
		UUID mentor = TeamTestSupport.mentor(this.jdbc, MentorApprovalStatus.APPROVED, name, List.of(TeamTestSupport.REACT),
				"React");
		UUID team = UUID.randomUUID();
		this.jdbc.sql("INSERT INTO team.team (id, mentor_account_id, status, created_at) VALUES (:id, :mentor, 'OPEN', now())")
			.param("id", team)
			.param("mentor", mentor)
			.update();
		UUID student = TeamTestSupport.student(this.jdbc, "Sinh viên của " + name, true, List.of(TeamTestSupport.REACT),
				"React", LocalDate.parse("2026-09-30"));
		this.jdbc.sql("""
				INSERT INTO team.membership (id, team_id, student_account_id, joined_at) VALUES (:id, :team, :student, now())
				""").param("id", UUID.randomUUID()).param("team", team).param("student", student).update();
		return mentor;
	}

	private UUID administrator() {
		UUID admin = IdentityTestSupport.insertAccount(this.jdbc, Role.ADMINISTRATOR, null, null);
		this.jdbc.sql("""
				INSERT INTO identity.administrator_credential (account_id, email, display_name, password_hash,
				    totp_key_version, totp_nonce, totp_ciphertext, created_at)
				VALUES (:id, :email, 'Quản trị', '$argon2id$x', 1, '\\x00'::bytea, '\\x00'::bytea, now())
				""").param("id", admin).param("email", "qt-" + admin.toString().substring(0, 8) + "@vibefix.vn").update();
		return admin;
	}

	private UUID openJob(UUID productId, String title) {
		UUID id = UUID.randomUUID();
		Instant now = this.clock.instant();
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, budget, deadline, rules, selection_days, opened_at, lapses_at, version, created_at, updated_at)
				VALUES (:id, :product, :owner, 'OPEN', :title, 'Lỗi', 'Đúng', 'Khách', ARRAY['BUG_FIX'], 'UNDER_2M',
				    DATE '2026-12-01', '{}'::jsonb, 14, :now, :lapses, 1, :now, :now)
				""")
			.param("id", id)
			.param("product", productId)
			.param("owner", this.lan)
			.param("title", title)
			.param("now", Timestamp.from(now))
			.param("lapses", Timestamp.from(now.plus(Duration.ofDays(14))))
			.update();
		return id;
	}

	private UUID product(UUID owner) {
		UUID id = UUID.randomUUID();
		Timestamp now = Timestamp.from(MutableClock.DEFAULT_INSTANT);
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

	private RequestPostProcessor as(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

	private static HttpServer start() {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/", exchange -> {
				String answer = CONTRIBUTORS.getOrDefault(exchange.getRequestURI().getPath(), "404 {}");
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
			throw new java.io.UncheckedIOException(ex);
		}
	}

}
