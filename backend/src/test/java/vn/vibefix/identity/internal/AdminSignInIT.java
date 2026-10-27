package vn.vibefix.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

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

import vn.vibefix.support.Browser;
import vn.vibefix.support.IntegrationTest;

/** FR-2, AD-19, AD-34, SG-10: PUB-6's sign-in, its lockout, and the Administrator session limits. */
class AdminSignInIT extends IntegrationTest {

	private static final String EMAIL = "ha.phuong@vibefix.vn";

	private static final String PASSWORD = "dung-mat-khau-dai-12";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private AdministratorBootstrap bootstrap;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private AdminSignIns signIns;

	@Autowired
	private vn.vibefix.notification.RecipientAddresses addresses;

	private UUID administrator;

	/**
	 * Its own application context, so the browser meets the real cookie CSRF repository: Spring
	 * Security's {@code csrf()} test helper, which other suites use, swaps the shared filter's
	 * repository for a session-based one for the rest of a context's life.
	 */
	@DynamicPropertySource
	static void ownContext(DynamicPropertyRegistry registry) {
		registry.add("vibefix.test.context", () -> "admin-sign-in");
	}

	private byte[] secret;

	private Browser browser;

	@BeforeEach
	void anAdministrator() throws Exception {
		AdministratorBootstrap.Created created = this.bootstrap.createFirst(EMAIL, "Hà Phương", PASSWORD.toCharArray());
		this.administrator = created.accountId();
		this.secret = Totp.fromBase32(created.secret());
		this.browser = new Browser(this.mvc);
		this.browser.perform(get("/api/v1/session"));
	}

	@Test
	void theRightEmailPasswordAndCodeStartAnAdministratorSession() throws Exception {
		Instant now = this.clock.instant();

		assertThat(signIn(EMAIL, PASSWORD, code()).getResponse().getStatus()).isEqualTo(204);

		String session = body(this.browser.perform(get("/api/v1/session")));
		assertThat((String) JsonPath.read(session, "$.status")).isEqualTo("SIGNED_IN");
		assertThat((String) JsonPath.read(session, "$.account.role")).isEqualTo("ADMINISTRATOR");
		assertThat((String) JsonPath.read(session, "$.account.kind")).isEqualTo("ADMINISTRATOR");
		assertThat((String) JsonPath.read(session, "$.administrator.email")).isEqualTo(EMAIL);
		assertThat((String) JsonPath.read(session, "$.administrator.displayName")).isEqualTo("Hà Phương");
		assertThat((String) JsonPath.read(session, "$.administrator.idleExpiresAt"))
			.isEqualTo(now.plus(Duration.ofMinutes(30)).toString());
		assertThat((String) JsonPath.read(session, "$.administrator.absoluteExpiresAt"))
			.isEqualTo(now.plus(Duration.ofHours(8)).toString());
		assertThat(session).doesNotContain("totp").doesNotContain("password");
		assertThat(this.browser.perform(get("/api/v1/admin/overview")).getResponse().getStatus()).isEqualTo(200);
		assertThat(audits("identity.admin_signed_in")).isOne();
	}

	@Test
	void eachCodeIsAcceptedOnce() throws Exception {
		String code = code();
		signIn(EMAIL, PASSWORD, code);
		this.browser.command(post("/api/v1/session/sign-out"));
		this.browser.perform(get("/api/v1/session"));

		MvcResult replay = signIn(EMAIL, PASSWORD, code);
		assertThat(replay.getResponse().getStatus()).isEqualTo(422);
		assertThat(body(replay)).contains("ADMIN_SIGN_IN_FAILED");

		this.clock.advance(Duration.ofSeconds(30));
		assertThat(signIn(EMAIL, PASSWORD, code()).getResponse().getStatus()).isEqualTo(204);
	}

	@Test
	void aWrongPasswordAWrongCodeAndAnUnknownEmailAnswerTheSame() throws Exception {
		String code = code();
		String wrongCode = code.equals("000000") ? "111111" : "000000";

		MvcResult wrongPassword = signIn(EMAIL, "khong-dung-mat-khau", code);
		MvcResult wrongSecond = signIn(EMAIL, PASSWORD, wrongCode);
		MvcResult unknown = signIn("ai.do@vibefix.vn", PASSWORD, code);

		for (MvcResult result : new MvcResult[] { wrongPassword, wrongSecond, unknown }) {
			assertThat(result.getResponse().getStatus()).isEqualTo(422);
			assertThat(body(result)).isEqualTo(body(wrongPassword));
		}
		assertThat(body(wrongPassword)).contains("GUARD_FAILED").contains("ADMIN_SIGN_IN_FAILED");
		assertThat(audits("identity.admin_sign_in_failed")).as("the two about a real Administrator").isEqualTo(2);
		assertThat((String) JsonPath.read(body(this.browser.perform(get("/api/v1/session"))), "$.status"))
			.isEqualTo("ANONYMOUS");
	}

	@Test
	void theFifthFailureIn15MinutesLocksTheEmailFor15MinutesAndAlertsTheOtherAdministrators() throws Exception {
		UUID other = IdentityTestSupport.insertAccount(this.jdbc, Role.ADMINISTRATOR, null);
		for (int i = 0; i < 4; i++) {
			assertThat(signIn(EMAIL, "khong-dung-" + i, code()).getResponse().getStatus()).isEqualTo(422);
		}

		MvcResult fifth = signIn(EMAIL, "khong-dung-5", code());
		assertThat(fifth.getResponse().getStatus()).isEqualTo(429);
		assertThat(body(fifth)).contains("RATE_LIMITED").contains("ADMIN_SIGN_IN_LOCKED");
		assertThat(fifth.getResponse().getHeader("Retry-After")).isEqualTo("900");
		assertThat(this.jdbc.sql("""
				SELECT params ->> 'email' FROM notification.notification
				WHERE account_id = :other AND type = 'identity.admin_sign_in_locked'
				""").param("other", other).query(String.class).single()).isEqualTo(EMAIL);
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM notification.notification WHERE account_id = :locked
				""").param("locked", this.administrator).query(Long.class).single()).as("not the locked one").isZero();

		this.clock.advance(Duration.ofMinutes(5));
		MvcResult locked = signIn(EMAIL, PASSWORD, code());
		assertThat(locked.getResponse().getStatus()).as("even the right credentials").isEqualTo(429);
		assertThat(locked.getResponse().getHeader("Retry-After")).isEqualTo("600");

		this.clock.advance(Duration.ofMinutes(10));
		assertThat(signIn(EMAIL, PASSWORD, code()).getResponse().getStatus()).isEqualTo(204);
	}

	@Test
	void failuresOlderThan15MinutesDoNotCount() throws Exception {
		for (int i = 0; i < 4; i++) {
			signIn(EMAIL, "khong-dung-" + i, code());
		}
		this.clock.advance(Duration.ofMinutes(15).plusSeconds(1));

		assertThat(signIn(EMAIL, "khong-dung-5", code()).getResponse().getStatus()).isEqualTo(422);
		assertThat(signIn(EMAIL, PASSWORD, code()).getResponse().getStatus()).isEqualTo(204);
	}

	@Test
	void anUnknownEmailLocksTooWithoutAnAlert() throws Exception {
		IdentityTestSupport.insertAccount(this.jdbc, Role.ADMINISTRATOR, null);
		for (int i = 0; i < 5; i++) {
			signIn("ai.do@vibefix.vn", "khong-dung-" + i, code());
		}

		assertThat(signIn("ai.do@vibefix.vn", PASSWORD, code()).getResponse().getStatus()).isEqualTo(429);
		assertThat(this.jdbc.sql("SELECT count(*) FROM notification.notification").query(Long.class).single()).isZero();
		assertThat(signIn(EMAIL, PASSWORD, code()).getResponse().getStatus()).as("another email").isEqualTo(204);
	}

	@Test
	void pollingNeverExtendsTheSessionAndItEndsAfter30IdleMinutes() throws Exception {
		signIn(EMAIL, PASSWORD, code());

		this.clock.advance(Duration.ofMinutes(29));
		assertThat(this.browser.perform(get("/api/v1/admin/overview")).getResponse().getStatus()).isEqualTo(200);
		this.clock.advance(Duration.ofMinutes(1));

		assertThat(this.browser.perform(get("/api/v1/admin/overview")).getResponse().getStatus()).isEqualTo(401);
		assertThat((String) JsonPath.read(body(this.browser.perform(get("/api/v1/session"))), "$.status"))
			.isEqualTo("ANONYMOUS");
	}

	@Test
	void activityExtendsTheIdleLimitButNeverTheAbsoluteOne() throws Exception {
		Instant signedIn = this.clock.instant();
		signIn(EMAIL, PASSWORD, code());

		this.clock.advance(Duration.ofMinutes(20));
		MvcResult activity = this.browser.command(post("/api/v1/session/activity"));
		assertThat((String) JsonPath.read(body(activity), "$.administrator.idleExpiresAt"))
			.isEqualTo(this.clock.instant().plus(Duration.ofMinutes(30)).toString());
		this.clock.advance(Duration.ofMinutes(20));
		assertThat(this.browser.perform(get("/api/v1/admin/overview")).getResponse().getStatus()).isEqualTo(200);

		while (this.clock.instant().isBefore(signedIn.plus(Duration.ofHours(8)).minus(Duration.ofMinutes(20)))) {
			this.browser.command(post("/api/v1/session/activity"));
			this.clock.advance(Duration.ofMinutes(20));
		}
		this.clock.setInstant(signedIn.plus(Duration.ofHours(8)));
		assertThat(this.browser.command(post("/api/v1/session/activity")).getResponse().getStatus()).isEqualTo(401);
	}

	@Test
	void anAdministratorsNotificationsGoToTheSignInEmail() {
		assertThat(this.addresses.emailAddress(this.administrator)).contains(EMAIL);
	}

	@Test
	void thePurgeDeletesFailuresThatNoLongerCountAndEndedLocks() throws Exception {
		for (int i = 0; i < 5; i++) {
			signIn("ai.do@vibefix.vn", "khong-dung-" + i, code());
		}
		signIn(EMAIL, "khong-dung", code());
		this.clock.advance(Duration.ofMinutes(15));

		assertThat(this.signIns.purge()).as("the lock and the one failure").isEqualTo(2);
		assertThat(this.jdbc.sql("SELECT count(*) FROM identity.admin_sign_in_lock").query(Long.class).single()).isZero();
		assertThat(this.jdbc.sql("SELECT count(*) FROM identity.admin_sign_in_failure").query(Long.class).single()).isZero();
	}

	@Test
	void aLockedAccountCannotSignIn() throws Exception {
		IdentityTestSupport.lock(this.jdbc, this.administrator, "test: locked");

		assertThat(signIn(EMAIL, PASSWORD, code()).getResponse().getStatus()).isEqualTo(422);
	}

	@Test
	void aSignedInAccountCannotSignInAsAnAdministrator() throws Exception {
		signIn(EMAIL, PASSWORD, code());
		this.clock.advance(Duration.ofSeconds(30));

		assertThat(signIn(EMAIL, PASSWORD, code()).getResponse().getStatus()).isEqualTo(409);
	}

	private MvcResult signIn(String email, String password, String code) throws Exception {
		return this.browser.command(post("/api/v1/admin/sign-in").contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\",\"code\":\"" + code + "\"}"));
	}

	private String code() {
		return Totp.codeAt(this.secret, Totp.step(this.clock.instant()));
	}

	private long audits(String action) {
		return this.jdbc.sql("SELECT count(*) FROM audit.audit_entry WHERE action = :action")
			.param("action", action)
			.query(Long.class)
			.single();
	}

	private static String body(MvcResult result) throws Exception {
		return result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
	}

}
