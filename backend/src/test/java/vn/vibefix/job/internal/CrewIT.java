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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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

import vn.vibefix.identity.PersonSignalMatched;
import vn.vibefix.identity.internal.CustomerTokens;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.job.Crews;
import vn.vibefix.job.Crews.Relationship;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.MutableClock;
import vn.vibefix.team.internal.TeamTestSupport;

/** FR-75, FR-17's Crew condition, AD-30, AD-37, AD-41 (R-1): the Crew and its Crew Terms before J6. */
class CrewIT extends IntegrationTest {

	private static final String TOKEN = "gho_customer_token_never_printed";

	private static final Map<String, String> CONTRIBUTORS = new ConcurrentHashMap<>();

	private static final HttpServer GITHUB = start();

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private ApplicationContext context;

	@Autowired
	private Crews crews;

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
	void aJobInAgreement() throws Exception {
		CONTRIBUTORS.clear();
		CONTRIBUTORS.put("/repos/lan/so-dat-ban/contributors", "200 [{\"id\":987654321,\"login\":\"lan\"}]");
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
		this.vy = member("Lê Thảo Vy", true);
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
	}

	@Test
	void theMentorPicksAStudentWhoSignsAndConfirms() throws Exception {
		UUID huy = member("Bùi Gia Huy", false);
		// Story 21.4: J4 opens "Mở tuyển thành viên nhóm" in place of the retired "Chọn nhóm thực hiện".
		await().atMost(Duration.ofSeconds(10)).until(() -> openTodos("matching.open_crew_recruitment", this.hung) == 1);
		assertThat(openTodos("job.pick_crew", this.hung)).isZero();
		assertThat(this.jdbc.sql("""
				SELECT t.deadline_at = j.agreement_due_at FROM notification.todo t JOIN job.job j ON j.id = t.subject_id
				WHERE t.todo_key = 'matching.open_crew_recruitment' AND t.closed_at IS NULL
				""").query(Boolean.class).single()).as("due at the Agreement deadline").isTrue();
		this.mvc.perform(get("/api/v1/mentor/jobs/" + this.job + "/crew").with(as(this.hung, Role.MENTOR)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.crewVersion").value(0))
			.andExpect(jsonPath("$.capPercent").value(40))
			.andExpect(jsonPath("$.team[?(@.studentId == '" + this.vy + "')].state").value("FREE"))
			.andExpect(jsonPath("$.team[?(@.studentId == '" + huy + "')].state").value("NOT_VERIFIED"));
		long version = jobVersion();

		add(this.vy, 0).andExpect(status().isOk())
			.andExpect(jsonPath("$.rows[0].status").value("ASSIGNED"))
			.andExpect(jsonPath("$.crewVersion").value(1));
		assertThat(jobVersion()).as("the usernames on the Customer's view changed").isEqualTo(version + 1);
		assertThat(count("SELECT count(*) FROM team.crew_reservation")).isOne();
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type = 'job.crew_assigned' AND account_id = '"
				+ this.vy + "'")).isOne();
		assertThat(openTodos("job.confirm_crew_terms", this.vy)).isOne();
		UUID minh = member("Trần Quang Minh", true);
		add(minh, 1).andExpect(status().is(422)).andExpect(jsonPath("$.reason").value("CREW_LIMIT"));
		add(minh, 0).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE"));

		terms(1, 45, 55).andExpect(status().isBadRequest()).andExpect(jsonPath("$.reason").value("SHARES_INVALID"));
		terms(1, 20, 80).andExpect(status().isOk())
			.andExpect(jsonPath("$.latest.versionNo").value(1))
			.andExpect(jsonPath("$.rows[0].percent").value(80))
			.andExpect(jsonPath("$.crewVersion").value(2));
		assertThat(jobVersion()).as("a Crew Terms save changes no username").isEqualTo(version + 1);

		this.mvc.perform(get("/api/v1/crew/assignments").with(as(this.vy, Role.STUDENT)))
			.andExpect(jsonPath("$[0].sharePercent").value(80))
			.andExpect(jsonPath("$[0].versionNo").value(1))
			.andExpect(jsonPath("$[0].signed").value(false))
			.andExpect(jsonPath("$[0].mentorName").value("Đỗ Mạnh Hùng"));
		assertThat(this.crews.crewRelationship(this.job, this.vy).kind()).isEqualTo(Relationship.ASSIGNED);
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/chat").with(as(this.vy, Role.STUDENT))).andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/mentor/jobs/" + this.job + "/agreement").with(as(this.vy, Role.STUDENT)))
			.andExpect(status().isNotFound());

		confirm(this.vy, 2, true).andExpect(status().isConflict());
		confirm(this.vy, 1, false).andExpect(status().isBadRequest()).andExpect(jsonPath("$.reason").value("PUBLIC_CODE_NOT_ACKNOWLEDGED"));
		assertThat(count("SELECT count(*) FROM matching.nda_signature WHERE account_id = '" + this.vy + "'")).isZero();
		confirm(this.vy, 1, true).andExpect(status().isNoContent());

		assertThat(this.crews.crewRelationship(this.job, this.vy).kind()).isEqualTo(Relationship.ACTIVE);
		assertThat(count("SELECT count(*) FROM matching.nda_signature WHERE account_id = '" + this.vy + "'")).isOne();
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type IN ('job.crew_member_confirmed', 'job.crew_confirmed')"
				+ " AND account_id = '" + this.hung + "'")).isEqualTo(2);
		assertThat(openTodos("job.pick_crew", this.hung)).as("FR-17's Crew condition holds").isZero();
		assertThat(openTodos("job.confirm_crew_terms", this.vy)).isZero();
		Timestamp signed = this.jdbc.sql("SELECT signed_at FROM job.crew_row WHERE member_account_id = :vy").param("vy", this.vy)
			.query(Timestamp.class).single();

		terms(3, 30, 70).andExpect(status().isOk()).andExpect(jsonPath("$.crewConfirmed").value(false));
		assertThat(this.crews.crewRelationship(this.job, this.vy).kind()).as("R-1 M1").isEqualTo(Relationship.ASSIGNED);
		assertThat(openTodos("job.pick_crew", this.hung)).as("no longer opened (Story 21.4)").isZero();
		this.clock.advance(Duration.ofHours(1));
		confirm(this.vy, 2, true).andExpect(status().isNoContent());
		assertThat(count("SELECT count(*) FROM matching.nda_signature WHERE account_id = '" + this.vy + "'"))
			.as("one signature per Job and Student (R-1 M4)").isOne();
		assertThat(this.jdbc.sql("SELECT signed_at FROM job.crew_row WHERE member_account_id = :vy").param("vy", this.vy)
			.query(Timestamp.class).single()).isEqualTo(signed);
	}

	@Test
	void eachIneligibleStudentIsRefusedWithItsReason() throws Exception {
		UUID admin = administrator();
		UUID huy = member("Bùi Gia Huy", false);
		add(huy, 0).andExpect(status().is(422)).andExpect(jsonPath("$.reason").value("STUDENT_NOT_VERIFIED"));

		UUID busy = member("Nguyễn Văn Hải", true);
		otherJob();
		this.jdbc.sql("""
				INSERT INTO job.crew_row (id, job_id, member_account_id, status, created_at)
				SELECT :id, id, :student, 'ASSIGNED', now() FROM job.job WHERE id <> :job LIMIT 1
				""").param("id", UUID.randomUUID()).param("student", busy).param("job", this.job).update();
		add(busy, 0).andExpect(status().is(422)).andExpect(jsonPath("$.reason").value("STUDENT_ON_CREW"));

		UUID linked = member("Ngô Bảo Anh", true);
		signal(linked, "a".repeat(64));
		signal(this.lan, "a".repeat(64));
		add(linked, 0).andExpect(status().is(422)).andExpect(jsonPath("$.reason").value("CONFLICT_OF_INTEREST"));
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type = 'job.conflict_of_interest_refused' AND account_id = '"
				+ admin + "'")).as("the alert survives the refusal").isOne();

		UUID contributor = member("Phạm Hải Tùng", true);
		long githubId = this.jdbc.sql("SELECT github_user_id FROM identity.account WHERE id = :id").param("id", contributor)
			.query(Long.class).single();
		CONTRIBUTORS.put("/repos/lan/so-dat-ban/contributors", "200 [{\"id\":" + githubId + ",\"login\":\"tung\"}]");
		add(contributor, 0).andExpect(status().is(422)).andExpect(jsonPath("$.reason").value("APPLICANT_IS_CONTRIBUTOR"));
		CONTRIBUTORS.put("/repos/lan/so-dat-ban/contributors", "502 {}");
		add(contributor, 0).andExpect(status().is(422)).andExpect(jsonPath("$.reason").value("CONTRIBUTORS_UNKNOWN"));
		CONTRIBUTORS.put("/repos/lan/so-dat-ban/contributors", "200 []");

		UUID outsider = TeamTestSupport.student(this.jdbc, "Ngoài nhóm", true, List.of(TeamTestSupport.REACT), "React",
				LocalDate.parse("2026-09-30"));
		add(outsider, 0).andExpect(status().is(422)).andExpect(jsonPath("$.reason").value("NOT_IN_TEAM"));
		assertThat(count("SELECT count(*) FROM job.crew_row WHERE job_id = '" + this.job + "'")).as("nothing was written").isZero();
	}

	@Test
	void theJobsCustomerAndMentorAreNeverPickedForItsCrew() throws Exception {
		// AD-43: refused first, under the Job lock, before the Crew limit or any eligibility rule.
		add(this.vy, 0).andExpect(status().isOk());
		for (UUID taken : List.of(this.lan, this.hung)) {
			add(taken, 1).andExpect(status().is(422))
				.andExpect(jsonPath("$.code").value("GUARD_FAILED"))
				.andExpect(jsonPath("$.reason").value("ROLE_TAKEN"));
		}
		assertThat(count("SELECT count(*) FROM job.crew_row WHERE job_id = '" + this.job + "'")).as("nothing was written")
			.isOne();
		assertThat(count("SELECT crew_version FROM job.job WHERE id = '" + this.job + "'")).isOne();
	}

	@Test
	void aDeclineAndARemovalReleaseTheRowAndTheReservation() throws Exception {
		add(this.vy, 0).andExpect(status().isOk());
		terms(1, 20, 80).andExpect(status().isOk());
		this.mvc.perform(post("/api/v1/crew/jobs/" + this.job + "/decline").with(as(this.vy, Role.STUDENT)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content("{\"crewTermsVersionNo\":1}")).andExpect(status().isNoContent());

		assertThat(this.jdbc.sql("SELECT status || ' ' || released_reason FROM job.crew_row WHERE member_account_id = :vy")
			.param("vy", this.vy).query(String.class).single()).isEqualTo("RELEASED DECLINED");
		assertThat(count("SELECT count(*) FROM team.crew_reservation")).isZero();
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type = 'job.crew_member_declined' AND account_id = '"
				+ this.hung + "'")).isOne();
		assertThat(this.crews.runningCrewOf(this.vy)).isEmpty();

		UUID minh = member("Trần Quang Minh", true);
		String body = add(minh, 3).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		String row = com.jayway.jsonpath.JsonPath.read(body, "$.rows[?(@.studentId == '" + minh + "')].rowId").toString()
			.replaceAll("[\\[\\]\"]", "");
		this.mvc.perform(post("/api/v1/mentor/jobs/" + this.job + "/crew/" + row + "/remove").with(as(this.hung, Role.MENTOR))
			.with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"crewVersion\":3}"))
			.andExpect(status().isConflict());
		this.mvc.perform(post("/api/v1/mentor/jobs/" + this.job + "/crew/" + row + "/remove").with(as(this.hung, Role.MENTOR))
			.with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"crewVersion\":4}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.rows[?(@.studentId == '" + minh + "')].releasedReason").value("REMOVED"));
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type = 'job.crew_member_removed' AND account_id = '"
				+ minh + "'")).isOne();
	}

	@Test
	void aPersonLinkBetweenTwoParticipantsAlertsEveryAdministratorOnce() throws Exception {
		UUID admin = administrator();
		add(this.vy, 0).andExpect(status().isOk());
		PersonSignalMatched event = new PersonSignalMatched(UUID.randomUUID(), this.vy, this.lan, "EMAIL");

		new TransactionTemplate(this.transactions).executeWithoutResult(status -> this.events.publishEvent(event));
		new TransactionTemplate(this.transactions).executeWithoutResult(status -> this.events.publishEvent(event));

		await().atMost(Duration.ofSeconds(10)).until(() -> count("SELECT count(*) FROM notification.notification WHERE type = "
				+ "'job.person_link_alert' AND account_id = '" + admin + "'") == 1);
		Thread.sleep(300);
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type = 'job.person_link_alert'")).isOne();
	}

	private ResultActions add(UUID student, long crewVersion) throws Exception {
		return this.mvc.perform(post("/api/v1/mentor/jobs/" + this.job + "/crew").with(as(this.hung, Role.MENTOR)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"studentId\":\"" + student + "\",\"crewVersion\":" + crewVersion + "}"));
	}

	private ResultActions terms(long crewVersion, int mentor, int student) throws Exception {
		return this.mvc.perform(put("/api/v1/mentor/jobs/" + this.job + "/crew/terms").with(as(this.hung, Role.MENTOR)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"crewVersion":%d,"shares":[{"accountId":"%s","percent":%d},{"accountId":"%s","percent":%d}]}
					""".formatted(crewVersion, this.hung, mentor, this.vy, student)));
	}

	private ResultActions confirm(UUID student, int version, boolean publicCode) throws Exception {
		return this.mvc.perform(post("/api/v1/crew/jobs/" + this.job + "/confirm").with(as(student, Role.STUDENT)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"crewTermsVersionNo\":%d,\"ndaVersion\":\"1.0\",\"publicCodeAcknowledged\":%s,\"commitEmailNoticeAcknowledged\":true}"
				.formatted(version, publicCode)));
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

	private UUID member(String name, boolean verified) {
		UUID student = TeamTestSupport.student(this.jdbc, name, true, List.of(TeamTestSupport.REACT), "React",
				verified ? LocalDate.parse("2026-09-30") : null);
		this.jdbc.sql("INSERT INTO team.membership (id, team_id, student_account_id, joined_at) VALUES (:id, :team, :student, now())")
			.param("id", UUID.randomUUID()).param("team", this.team).param("student", student).update();
		return student;
	}

	private void otherJob() {
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, version, created_at, updated_at)
				SELECT :id, product_id, customer_account_id, 'DRAFT', 'Việc khác', 0, now(), now() FROM job.job WHERE id = :job
				""").param("id", UUID.randomUUID()).param("job", this.job).update();
	}

	private void signal(UUID account, String hmac) {
		this.jdbc.sql("""
				INSERT INTO identity.person_signal (id, account_id, kind, source, hmac, key_version, recorded_at)
				VALUES (:id, :account, 'EMAIL', 'GITHUB_EMAIL', :hmac, 1, now())
				""").param("id", UUID.randomUUID()).param("account", account).param("hmac", hmac).update();
	}

	private long jobVersion() {
		return this.jdbc.sql("SELECT version FROM job.job WHERE id = :id").param("id", this.job).query(Long.class).single();
	}

	private long openTodos(String key, UUID account) {
		return count("SELECT count(*) FROM notification.todo WHERE todo_key = '" + key + "' AND account_id = '" + account
				+ "' AND closed_at IS NULL");
	}

	private long count(String sql) {
		return this.jdbc.sql(sql).query(Long.class).single();
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
			throw new UncheckedIOException(ex);
		}
	}

}
