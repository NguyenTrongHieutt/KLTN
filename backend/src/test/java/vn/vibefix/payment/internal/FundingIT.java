package vn.vibefix.payment.internal;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;

import vn.vibefix.identity.AccountLocked;
import vn.vibefix.identity.MentorApprovalStatus;
import vn.vibefix.identity.internal.CustomerTokens;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.job.Crews;
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.MutableClock;
import vn.vibefix.team.internal.TeamTestSupport;

/** FR-41, AD-4, AD-14, AD-15 (FUND), AD-41: funding a Milestone through the VNPay sandbox's IPN. */
class FundingIT extends IntegrationTest {

	private static final String TMN = "TESTTMN1";

	private static final String SECRET = "funding-it-" + UUID.randomUUID();

	private static final Path SECRET_FILE = secretFile();

	private static final HttpServer GITHUB = start();

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
	private Crews crews;

	@Autowired
	private PaymentOrders orders;

	private UUID lan;

	private UUID hung;

	private UUID team;

	private UUID vy;

	private UUID job;

	@DynamicPropertySource
	static void merchant(DynamicPropertyRegistry registry) {
		registry.add("vibefix.github.api-url", () -> "http://127.0.0.1:" + GITHUB.getAddress().getPort());
		registry.add("vibefix.payment.vnpay.tmn-code", () -> TMN);
		registry.add("vibefix.payment.vnpay.hash-secret-file", SECRET_FILE::toString);
		registry.add("vibefix.payment.vnpay.return-url", () -> "http://localhost:5173/thanh-toan");
	}

	@Test
	void aPaidMilestoneOneStartsTheCodeJobWithTheCrewTermsPinned() throws Exception {
		confirmedAgreement("BUG_FIX");
		UUID admin = administrator();
		assertThat(openTodos("payment.fund_milestone", this.lan)).isOne();
		assertThat(count("SELECT count(*) FROM payment.ledger_account WHERE job_id = '" + this.job + "'")).isOne();
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/funding").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.configured").value(true))
			.andExpect(jsonPath("$.milestones[0].fundable").value(true))
			.andExpect(jsonPath("$.milestones[0].state").value("PLANNED"));

		order(jobVersion() - 1).andExpect(status().isConflict());
		String created = order(jobVersion()).andExpect(status().isCreated())
			.andExpect(jsonPath("$.payUrl").value(org.hamcrest.Matchers.startsWith("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?")))
			.andReturn().getResponse().getContentAsString();
		UUID orderId = UUID.fromString(JsonPath.read(created, "$.id"));
		String payUrl = JsonPath.read(created, "$.payUrl");
		assertThat(payUrl).contains("vnp_Amount=80000000").contains("vnp_TmnCode=" + TMN)
			.contains("vnp_ReturnUrl=" + java.net.URLEncoder.encode("http://localhost:5173/thanh-toan/" + orderId, StandardCharsets.UTF_8))
			.doesNotContain(SECRET);
		assertThat(count("SELECT count(*) FROM timers.deadline WHERE kind = 'payment.order_expiry' AND subject_id = '" + orderId + "'"))
			.isOne();
		String ref = txnRef(orderId);

		ipn(paid(ref, "14000001", 800000), "bad").andExpect(jsonPath("$.RspCode").value("97"));
		Map<String, String> foreign = paid(ref, "14000001", 800000);
		foreign.put("vnp_TmnCode", "OTHER");
		ipn(foreign, null).andExpect(jsonPath("$.RspCode").value("97"));
		ipn(paid("UNKNOWNREF", "14000002", 800000), null).andExpect(jsonPath("$.RspCode").value("01"));
		assertThat(notifications("payment.unknown_payment", admin)).isOne();
		assertThat(orderStatus(orderId)).isEqualTo("OPEN");

		ipn(paid(ref, "14000001", 800000), null).andExpect(jsonPath("$.RspCode").value("00"));

		assertThat(orderStatus(orderId)).isEqualTo("PAID");
		assertThat(this.jdbc.sql("SELECT state FROM job.job WHERE id = :id").param("id", this.job).query(String.class).single())
			.isEqualTo("STARTING");
		assertThat(this.jdbc.sql("SELECT state || ' ' || crew_terms_version_no FROM job.milestone WHERE id = :id")
			.param("id", MILESTONE).query(String.class).single()).isEqualTo("FUNDED 1");
		assertThat(this.jdbc.sql("SELECT status FROM job.crew_terms_version WHERE job_id = :job").param("job", this.job)
			.query(String.class).single()).isEqualTo("IN_FORCE");
		assertThat(this.crews.releaseTermsOf(MILESTONE)).hasValueSatisfying(terms -> {
			assertThat(terms.versionNo()).isOne();
			assertThat(terms.shares()).containsEntry(this.hung, 20).containsEntry(this.vy, 80);
		});
		assertThat(count("SELECT count(*) FROM payment.ledger_entry WHERE entry_type = 'FUND' AND bucket = 'MILESTONE'"
				+ " AND amount_vnd = 800000 AND milestone_id = '" + MILESTONE + "'")).isOne();
		assertThat(notifications("payment.payment_received", this.lan)).isOne();
		assertThat(notifications("job.milestone_funded", this.hung) + notifications("job.milestone_funded", this.vy)).isEqualTo(2);
		for (UUID account : List.of(this.lan, this.hung, this.vy)) {
			assertThat(notifications("job.job_starting", account)).isOne();
		}
		assertThat(openTodos("payment.fund_milestone", this.lan)).isZero();
		assertThat(count("SELECT count(*) FROM audit.audit_entry WHERE action IN ('job.transition_j6', 'job.transition_m1')"
				+ " AND job_id = '" + this.job + "'")).isEqualTo(2);

		ipn(paid(ref, "14000001", 800000), null).andExpect(jsonPath("$.RspCode").value("02"));
		assertThat(count("SELECT count(*) FROM payment.ledger_entry WHERE entry_type = 'FUND'")).as("a replay credits nothing").isOne();
		assertThat(this.orders.expire(orderId)).as("a paid order does not expire").isFalse();
	}

	@Test
	void anAssessmentJobBecomesActiveInTheSameCascade() throws Exception {
		confirmedAgreement("ASSESSMENT");
		UUID orderId = UUID.fromString(JsonPath.read(order(jobVersion()).andExpect(status().isCreated()).andReturn().getResponse()
			.getContentAsString(), "$.id"));
		UUID milestone = this.jdbc.sql("SELECT milestone_id FROM payment.payment_order WHERE id = :id").param("id", orderId)
			.query(UUID.class).single();

		ipn(paid(txnRef(orderId), "14000003", 800000), null).andExpect(jsonPath("$.RspCode").value("00"));

		assertThat(this.jdbc.sql("SELECT state FROM job.job WHERE id = :id").param("id", this.job).query(String.class).single())
			.isEqualTo("ACTIVE");
		assertThat(this.jdbc.sql("SELECT state FROM job.milestone WHERE id = :id").param("id", milestone).query(String.class).single())
			.isEqualTo("FUNDED");
		assertThat(count("SELECT count(*) FROM audit.audit_entry WHERE action = 'job.transition_j7' AND job_id = '" + this.job + "'"))
			.isOne();
		assertThat(notifications("job.job_starting", this.lan)).as("no repositories to prepare").isZero();
	}

	@Test
	void moneyThatCannotApplyIsBookedUnappliedAndFailuresAndExpiriesAreTold() throws Exception {
		confirmedAgreement("BUG_FIX");
		UUID first = created();
		ipn(paid(txnRef(first), "14000010", 500000), null).andExpect(jsonPath("$.RspCode").value("00"));
		assertThat(orderStatus(first)).as("a wrong amount").isEqualTo("UNAPPLIED");
		assertThat(count("SELECT count(*) FROM payment.ledger_entry WHERE bucket = 'UNAPPLIED' AND amount_vnd = 500000")).isOne();
		assertThat(this.jdbc.sql("SELECT state FROM job.milestone WHERE id = :id").param("id", MILESTONE).query(String.class).single())
			.isEqualTo("PLANNED");

		UUID second = created();
		Map<String, String> cancelled = paid(txnRef(second), "14000011", 800000);
		cancelled.put("vnp_ResponseCode", "24");
		cancelled.put("vnp_TransactionStatus", "02");
		ipn(cancelled, null).andExpect(jsonPath("$.RspCode").value("00"));
		assertThat(orderStatus(second)).isEqualTo("FAILED");
		assertThat(notifications("payment.payment_failed", this.lan)).isOne();

		UUID voided = created();
		UUID latest = created();
		assertThat(orderStatus(voided)).as("a new order voids the open one").isEqualTo("VOIDED");
		ipn(paid(txnRef(voided), "14000012", 800000), null).andExpect(jsonPath("$.RspCode").value("00"));
		assertThat(orderStatus(voided)).isEqualTo("UNAPPLIED");

		assertThat(this.orders.expire(latest)).isTrue();
		assertThat(orderStatus(latest)).isEqualTo("EXPIRED");
		assertThat(notifications("payment.payment_failed", this.lan)).isEqualTo(2);
		ipn(paid(txnRef(latest), "14000013", 800000), null).andExpect(jsonPath("$.RspCode").value("00"));
		assertThat(orderStatus(latest)).as("paid after it expired").isEqualTo("UNAPPLIED");
		assertThat(this.jdbc.sql("SELECT state FROM job.job WHERE id = :id").param("id", this.job).query(String.class).single())
			.isEqualTo("AGREEMENT");
	}

	@Test
	void aLockedCrewMemberHoldsMilestoneOneBack() throws Exception {
		confirmedAgreement("BUG_FIX");
		UUID orderId = created();
		AccountLocked locked = new AccountLocked(UUID.randomUUID(), this.vy, Role.STUDENT);
		new TransactionTemplate(this.transactions).executeWithoutResult(status -> this.events.publishEvent(locked));
		await().atMost(Duration.ofSeconds(10)).until(() -> count(
				"SELECT count(*) FROM job.job WHERE crew_reconfirm_owed_at IS NOT NULL AND id = '" + this.job + "'") == 1);

		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/funding").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.milestones[0].fundable").value(false))
			.andExpect(jsonPath("$.milestones[0].blockedReason").value("CREW_NOT_READY"));
		order(jobVersion()).andExpect(status().is(422)).andExpect(jsonPath("$.reason").value("CREW_NOT_READY"));

		ipn(paid(txnRef(orderId), "14000020", 800000), null).andExpect(jsonPath("$.RspCode").value("00"));
		assertThat(orderStatus(orderId)).as("J6's Crew guard refused it").isEqualTo("UNAPPLIED");
		assertThat(this.jdbc.sql("SELECT state FROM job.job WHERE id = :id").param("id", this.job).query(String.class).single())
			.isEqualTo("AGREEMENT");
	}

	// ---- The way to a confirmed Agreement ----------------------------------------------------

	private void confirmedAgreement(String jobType) throws Exception {
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null, null);
		CustomerTokens.keep(this.context, this.lan, "gho_customer_token_never_printed");
		UUID product = product(this.lan);
		this.job = UUID.randomUUID();
		Instant now = this.clock.instant();
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, budget, deadline, rules, selection_days, opened_at, lapses_at, version, created_at, updated_at)
				VALUES (:id, :product, :owner, 'OPEN', 'Sửa lỗi đặt bàn', 'Lỗi', 'Đúng', 'Khách', ARRAY[:type],
				    'UNDER_2M', DATE '2026-12-01', '{"REVISION_ROUNDS":2}'::jsonb, 14, :now, :lapses, 1, :now, :now)
				""")
			.param("id", this.job).param("product", product).param("owner", this.lan).param("type", jobType)
			.param("now", Timestamp.from(now)).param("lapses", Timestamp.from(now.plus(Duration.ofDays(14)))).update();
		this.hung = TeamTestSupport.mentor(this.jdbc, MentorApprovalStatus.APPROVED, "Đỗ Mạnh Hùng", List.of(TeamTestSupport.REACT),
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
		boolean assessment = "ASSESSMENT".equals(jobType);
		UUID milestone = assessment ? UUID.fromString(JsonPath.read(this.mvc.perform(get("/api/v1/mentor/jobs/" + this.job + "/agreement")
			.with(as(this.hung, Role.MENTOR))).andReturn().getResponse().getContentAsString(), "$.content.milestones[0].id")) : MILESTONE;
		this.mvc.perform(put("/api/v1/mentor/jobs/" + this.job + "/agreement").with(as(this.hung, Role.MENTOR)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"revision":0,"scope":"Sửa lỗi hai khách đặt trùng bàn.","priceVnd":800000,"skillIds":[],
					 "milestones":[{"id":"%s","title":"Sửa lỗi trùng giờ","priceVnd":800000,"dueOn":"2026-11-20","revisionRounds":2,
					   "criteria":[{"id":"%s","statement":"Hai khách không đặt trùng bàn","checkSteps":"1. Mở. 2. Đặt."}]}],
					 "externalAccess":[]}
					""".formatted(milestone, UUID.randomUUID()))).andExpect(status().isOk());
		this.mvc.perform(post("/api/v1/mentor/jobs/" + this.job + "/agreement/share").with(as(this.hung, Role.MENTOR)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content("{\"revision\":1}")).andExpect(status().isOk());
		this.mvc.perform(post("/api/v1/mentor/jobs/" + this.job + "/crew").with(as(this.hung, Role.MENTOR)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content("{\"studentId\":\"" + this.vy + "\",\"crewVersion\":0}"))
			.andExpect(status().isOk());
		this.mvc.perform(put("/api/v1/mentor/jobs/" + this.job + "/crew/terms").with(as(this.hung, Role.MENTOR)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"crewVersion":1,"shares":[{"accountId":"%s","percent":20},{"accountId":"%s","percent":80}]}
					""".formatted(this.hung, this.vy))).andExpect(status().isOk());
		this.mvc.perform(post("/api/v1/crew/jobs/" + this.job + "/confirm").with(as(this.vy, Role.STUDENT)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"crewTermsVersionNo\":1,\"ndaVersion\":\"1.0\",\"publicCodeAcknowledged\":true,\"commitEmailNoticeAcknowledged\":true}"))
			.andExpect(status().isNoContent());
		this.mvc.perform(post("/api/v1/jobs/" + this.job + "/agreement/approve").with(as(this.lan, Role.CUSTOMER)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"version\":%d,\"versionNo\":1,\"freezeCommitment\":%s,\"liveAccessIds\":[]}".formatted(jobVersion(), !assessment)))
			.andExpect(status().isNoContent());
		this.mvc.perform(post("/api/v1/mentor/jobs/" + this.job + "/agreement/confirm").with(as(this.hung, Role.MENTOR)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content("{\"version\":%d,\"crewVersion\":%d}".formatted(jobVersion(), crewVersion())))
			.andExpect(status().isOk());
		await().atMost(Duration.ofSeconds(10)).until(() -> openTodos("payment.fund_milestone", this.lan) == 1);
	}

	// ---- Helpers ----------------------------------------------------------------------------

	private ResultActions order(long version) throws Exception {
		UUID milestone = this.jdbc.sql("SELECT id FROM job.milestone WHERE job_id = :job AND removed_at IS NULL ORDER BY position LIMIT 1")
			.param("job", this.job).query(UUID.class).single();
		return this.mvc.perform(post("/api/v1/jobs/" + this.job + "/milestones/" + milestone + "/payment-orders")
			.with(as(this.lan, Role.CUSTOMER)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + version + "}"));
	}

	private UUID created() throws Exception {
		return UUID.fromString(JsonPath.read(order(jobVersion()).andExpect(status().isCreated()).andReturn().getResponse()
			.getContentAsString(), "$.id"));
	}

	/** What VNPay sends for a paid order. */
	private static Map<String, String> paid(String txnRef, String transactionNo, long amountVnd) {
		Map<String, String> fields = new LinkedHashMap<>();
		fields.put("vnp_Amount", Long.toString(amountVnd * 100));
		fields.put("vnp_BankCode", "NCB");
		fields.put("vnp_OrderInfo", "Thanh toan Giai doan 1");
		fields.put("vnp_PayDate", "20261020100500");
		fields.put("vnp_ResponseCode", "00");
		fields.put("vnp_TmnCode", TMN);
		fields.put("vnp_TransactionNo", transactionNo);
		fields.put("vnp_TransactionStatus", "00");
		fields.put("vnp_TxnRef", txnRef);
		return fields;
	}

	/** The IPN as VNPay calls it, signed with the merchant's secret unless a hash is given. */
	private ResultActions ipn(Map<String, String> fields, String hash) throws Exception {
		MockHttpServletRequestBuilder request = get("/webhooks/vnpay/ipn");
		fields.forEach(request::param);
		request.param("vnp_SecureHash", hash != null ? hash : VnpaySigner.hmacSha512(SECRET, VnpaySigner.queryHashData(fields)));
		return this.mvc.perform(request).andExpect(status().isOk());
	}

	private String txnRef(UUID orderId) {
		return this.jdbc.sql("SELECT txn_ref FROM payment.payment_order WHERE id = :id").param("id", orderId).query(String.class).single();
	}

	private String orderStatus(UUID orderId) {
		return this.jdbc.sql("SELECT status FROM payment.payment_order WHERE id = :id").param("id", orderId).query(String.class).single();
	}

	private UUID member(String name) {
		UUID student = TeamTestSupport.student(this.jdbc, name, true, List.of(TeamTestSupport.REACT), "React",
				LocalDate.parse("2026-09-30"));
		this.jdbc.sql("INSERT INTO team.membership (id, team_id, student_account_id, joined_at) VALUES (:id, :team, :student, now())")
			.param("id", UUID.randomUUID()).param("team", this.team).param("student", student).update();
		return student;
	}

	/** An Administrator with credentials, as {@code Administrators.activeIds} counts them. */
	private UUID administrator() {
		UUID admin = IdentityTestSupport.insertAccount(this.jdbc, Role.ADMINISTRATOR, null, null);
		this.jdbc.sql("""
				INSERT INTO identity.administrator_credential (account_id, email, display_name, password_hash,
				    totp_key_version, totp_nonce, totp_ciphertext, created_at)
				VALUES (:id, :email, 'Quản trị', '$argon2id$x', 1, '\\x00'::bytea, '\\x00'::bytea, now())
				""").param("id", admin).param("email", "qt-" + admin.toString().substring(0, 8) + "@vibefix.vn").update();
		return admin;
	}

	private long crewVersion() {
		return this.jdbc.sql("SELECT crew_version FROM job.job WHERE id = :id").param("id", this.job).query(Long.class).single();
	}

	private long jobVersion() {
		return this.jdbc.sql("SELECT version FROM job.job WHERE id = :id").param("id", this.job).query(Long.class).single();
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

	private static Path secretFile() {
		try {
			Path file = Files.createTempFile("vnpay-hash-secret-", ".txt");
			Files.writeString(file, SECRET);
			file.toFile().deleteOnExit();
			return file;
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/** GitHub answering that the Source Repository has no contributor yet. */
	private static HttpServer start() {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/", exchange -> {
				boolean contributors = exchange.getRequestURI().getPath().endsWith("/contributors");
				byte[] body = (contributors ? "[]" : "{}").getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().add("Content-Type", "application/json");
				exchange.sendResponseHeaders(contributors ? 200 : 404, body.length);
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
