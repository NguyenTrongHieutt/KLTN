package vn.vibefix.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;

import vn.vibefix.notification.internal.RecordingMailSender;
import vn.vibefix.operations.Lane;
import vn.vibefix.operations.internal.OperationWorker;
import vn.vibefix.support.Browser;
import vn.vibefix.support.IntegrationTest;

/** FR-2, SG-10: creating an Administrator with step-up, the enrolment link, alerts, and ADM-11. */
class AdministratorCreationIT extends IntegrationTest {

	private static final String HA = "ha.phuong@vibefix.vn";

	private static final String QUAN = "quan@vibefix.vn";

	private static final String PASSWORD = "dung-mat-khau-dai-12";

	private static final Pattern LINK = Pattern.compile("/quan-tri/kich-hoat/([0-9a-f]{32})");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private AdministratorBootstrap bootstrap;

	@Autowired
	private OperationWorker worker;

	@Autowired
	private JdbcClient jdbc;

	private UUID ha;

	private byte[] haSecret;

	private Browser browser;

	/** Its own context, for the browser's real CSRF cookie (see {@link Browser}). */
	@DynamicPropertySource
	static void ownContext(DynamicPropertyRegistry registry) {
		registry.add("vibefix.test.context", () -> "administrator-creation");
	}

	@BeforeEach
	void haSignedIn() throws Exception {
		AdministratorBootstrap.Created created = this.bootstrap.createFirst(HA, "Hà Phương", PASSWORD.toCharArray());
		this.ha = created.accountId();
		this.haSecret = Totp.fromBase32(created.secret());
		this.browser = new Browser(this.mvc);
		this.browser.perform(get("/api/v1/session"));
		assertThat(command("/api/v1/admin/sign-in", "{\"email\":\"" + HA + "\",\"password\":\"" + PASSWORD
				+ "\",\"code\":\"" + code(this.haSecret) + "\"}").getResponse().getStatus()).isEqualTo(204);
		// The sign-in used this step; a step-up needs the next one.
		this.clock.advance(Duration.ofSeconds(30));
	}

	@Test
	void aStepUpCreatesTheAdministratorEmailsTheLinkAlertsEveryoneAndAudits() throws Exception {
		MvcResult created = create("Vũ Minh Quân", QUAN, code(this.haSecret));

		assertThat(created.getResponse().getStatus()).isEqualTo(201);
		UUID quan = UUID.fromString(JsonPath.read(body(created), "$.id"));
		assertThat(this.jdbc.sql("SELECT role FROM identity.account WHERE id = :id").param("id", quan)
			.query(String.class).single()).isEqualTo("ADMINISTRATOR");
		assertThat(this.jdbc.sql("""
				SELECT params ->> 'email' FROM notification.notification
				WHERE account_id = :ha AND type = 'identity.administrator_created'
				""").param("ha", this.ha).query(String.class).single()).isEqualTo(QUAN);
		assertThat(this.jdbc.sql("""
				SELECT actor_account_id FROM audit.audit_entry WHERE action = 'identity.administrator_created'
				""").query(UUID.class).single()).isEqualTo(this.ha);

		String token = enrolmentToken();
		assertThat(this.jdbc.sql("SELECT payload::text FROM operations.external_operation").query(String.class).list())
			.as("the link with its token never sits in the permanent payload")
			.allSatisfy(payload -> assertThat(payload).doesNotContain(token));
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM operations.external_operation
				WHERE payload ->> 'template' = 'identity.administrator_enrolment' AND personal_data IS NOT NULL
				""").query(Long.class).single()).as("and the address and link are purged once it is sent").isZero();
		assertThat(this.jdbc.sql("SELECT count(*) FROM identity.administrator_enrolment WHERE token_hash = :hash")
			.param("hash", StudentVerifications.sha256(token)).query(Long.class).single()).isOne();
	}

	@Test
	void aWrongStepUpCodeSaysHowManyAttemptsAreLeftAndTheFifthEndsTheSession() throws Exception {
		String wrong = wrong(code(this.haSecret));
		for (int left = 4; left >= 1; left--) {
			MvcResult refused = create("Vũ Minh Quân", QUAN, wrong);
			assertThat(refused.getResponse().getStatus()).isEqualTo(422);
			assertThat(body(refused)).contains("STEP_UP_FAILED");
			assertThat((Integer) JsonPath.read(body(refused), "$.attemptsLeft")).isEqualTo(left);
		}

		MvcResult fifth = create("Vũ Minh Quân", QUAN, wrong);
		assertThat(fifth.getResponse().getStatus()).isEqualTo(429);
		assertThat(body(fifth)).contains("ADMIN_SIGN_IN_LOCKED");
		assertThat((String) JsonPath.read(body(this.browser.perform(get("/api/v1/session"))), "$.status"))
			.isEqualTo("ANONYMOUS");
		assertThat(this.jdbc.sql("SELECT count(*) FROM identity.administrator_enrolment").query(Long.class).single())
			.isZero();
		assertThat(this.jdbc.sql("SELECT count(*) FROM audit.audit_entry WHERE action = 'identity.step_up_failed'")
			.query(Long.class).single()).isEqualTo(5);
	}

	@Test
	void anEmailOfAnAdministratorOrOfAnOpenEnrolmentIsTaken() throws Exception {
		MvcResult own = create("Hà", HA, code(this.haSecret));
		assertThat(own.getResponse().getStatus()).isEqualTo(409);
		assertThat(body(own)).contains("ADMINISTRATOR_EMAIL_IN_USE");

		this.clock.advance(Duration.ofSeconds(30));
		assertThat(create("Vũ Minh Quân", QUAN, code(this.haSecret)).getResponse().getStatus()).isEqualTo(201);
		this.clock.advance(Duration.ofSeconds(30));
		assertThat(body(create("Quân", "Quan@Vibefix.VN", code(this.haSecret)))).contains("ADMINISTRATOR_EMAIL_IN_USE");
	}

	@Test
	void theLinkShowsTheSameSecretUntilItEnrolsThenSignsTheNewAdministratorInOnce() throws Exception {
		create("Vũ Minh Quân", QUAN, code(this.haSecret));
		String token = enrolmentToken();
		Browser quan = new Browser(this.mvc);
		quan.perform(get("/api/v1/session"));

		String first = body(quan.perform(get("/api/v1/admin/enrolment/" + token)));
		assertThat((String) JsonPath.read(first, "$.email")).isEqualTo(QUAN);
		assertThat((String) JsonPath.read(first, "$.displayName")).isEqualTo("Vũ Minh Quân");
		String secret = JsonPath.read(first, "$.secret");
		assertThat((String) JsonPath.read(body(quan.perform(get("/api/v1/admin/enrolment/" + token))), "$.secret"))
			.isEqualTo(secret);

		MvcResult shortPassword = quan.command(post("/api/v1/admin/enrolment/" + token)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"password\":\"ngan\",\"code\":\"" + code(Totp.fromBase32(secret)) + "\"}"));
		assertThat(shortPassword.getResponse().getStatus()).isEqualTo(400);
		MvcResult wrongCode = enrol(quan, token, wrong(code(Totp.fromBase32(secret))));
		assertThat(wrongCode.getResponse().getStatus()).isEqualTo(422);
		assertThat(body(wrongCode)).contains("ENROLMENT_CODE_WRONG");

		assertThat(enrol(quan, token, code(Totp.fromBase32(secret))).getResponse().getStatus()).isEqualTo(204);
		String session = body(quan.perform(get("/api/v1/session")));
		assertThat((String) JsonPath.read(session, "$.account.role")).isEqualTo("ADMINISTRATOR");
		assertThat((String) JsonPath.read(session, "$.account.kind")).isEqualTo("ADMINISTRATOR");
		assertThat((String) JsonPath.read(session, "$.administrator.email")).isEqualTo(QUAN);
		assertThat(this.jdbc.sql("SELECT count(*) FROM audit.audit_entry WHERE action = 'identity.administrator_enrolled'")
			.query(Long.class).single()).isOne();

		Browser other = new Browser(this.mvc);
		other.perform(get("/api/v1/session"));
		assertThat(other.perform(get("/api/v1/admin/enrolment/" + token)).getResponse().getStatus())
			.as("a used link").isEqualTo(404);
	}

	@Test
	void aLinkLapsesAfter24HoursAndCreatingTheEmailAgainRenewsIt() throws Exception {
		MvcResult created = create("Vũ Minh Quân", QUAN, code(this.haSecret));
		String lapsedToken = enrolmentToken();
		this.clock.advance(Duration.ofHours(24).plusSeconds(1));
		Browser quan = new Browser(this.mvc);
		quan.perform(get("/api/v1/session"));
		assertThat(quan.perform(get("/api/v1/admin/enrolment/" + lapsedToken)).getResponse().getStatus()).isEqualTo(404);

		this.browser = new Browser(this.mvc);
		this.browser.perform(get("/api/v1/session"));
		command("/api/v1/admin/sign-in", "{\"email\":\"" + HA + "\",\"password\":\"" + PASSWORD + "\",\"code\":\""
				+ code(this.haSecret) + "\"}");
		this.clock.advance(Duration.ofSeconds(30));
		MvcResult renewed = create("Vũ Minh Quân", QUAN, code(this.haSecret));

		assertThat(renewed.getResponse().getStatus()).isEqualTo(201);
		assertThat((String) JsonPath.read(body(renewed), "$.id")).isEqualTo(JsonPath.read(body(created), "$.id"));
		String token = enrolmentToken();
		assertThat(token).isNotEqualTo(lapsedToken);
		assertThat(quan.perform(get("/api/v1/admin/enrolment/" + token)).getResponse().getStatus()).isEqualTo(200);
	}

	@Test
	void adm11ListsEachAdministratorWithItsTwoFactorStateAndAdm1CountsTheActiveOnes() throws Exception {
		assertThat((Integer) JsonPath.read(body(this.browser.perform(get("/api/v1/admin/overview"))),
				"$.activeAdministrators")).isOne();
		create("Vũ Minh Quân", QUAN, code(this.haSecret));

		String list = body(this.browser.perform(get("/api/v1/admin/administrators")));
		List<String> statuses = JsonPath.read(list, "$[*].status");
		assertThat(statuses).containsExactly("ACTIVE", "ENROLLING");
		assertThat((Boolean) JsonPath.read(list, "$[0].you")).isTrue();
		assertThat((String) JsonPath.read(list, "$[0].signedInAt")).isNotNull();
		assertThat((String) JsonPath.read(list, "$[1].email")).isEqualTo(QUAN);
		assertThat(list).doesNotContain("token").doesNotContain("secret");
		assertThat((Integer) JsonPath.read(body(this.browser.perform(get("/api/v1/admin/overview"))),
				"$.activeAdministrators")).as("not until it enrols").isOne();
	}

	private MvcResult create(String name, String email, String stepUpCode) throws Exception {
		return command("/api/v1/admin/administrators",
				"{\"displayName\":\"" + name + "\",\"email\":\"" + email + "\",\"stepUpCode\":\"" + stepUpCode + "\"}");
	}

	private MvcResult enrol(Browser who, String token, String code) throws Exception {
		return who.command(post("/api/v1/admin/enrolment/" + token).contentType(MediaType.APPLICATION_JSON)
			.content("{\"password\":\"" + PASSWORD + "-quan\",\"code\":\"" + code + "\"}"));
	}

	private MvcResult command(String path, String json) throws Exception {
		return this.browser.command(post(path).contentType(MediaType.APPLICATION_JSON).content(json));
	}

	/** Runs the email lane and returns the token of the last enrolment link sent to {@value #QUAN}. */
	private String enrolmentToken() {
		this.worker.runDue(Lane.EMAIL);
		RecordingMailSender.Sent sent = this.mail.sent()
			.stream()
			.filter(mail -> mail.to().equals(QUAN))
			.reduce((a, b) -> b)
			.orElseThrow();
		Matcher matcher = LINK.matcher(sent.text());
		assertThat(matcher.find()).as("the link in %s", sent.text()).isTrue();
		return matcher.group(1);
	}

	private String code(byte[] secret) {
		return Totp.codeAt(secret, Totp.step(this.clock.instant()));
	}

	private static String wrong(String code) {
		return code.equals("000000") ? "111111" : "000000";
	}

	private static String body(MvcResult result) throws Exception {
		return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
	}

}
