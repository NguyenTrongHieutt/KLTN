package vn.vibefix.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.ServletException;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.vibefix.identity.Capabilities;
import vn.vibefix.identity.Capabilities.Held;
import vn.vibefix.identity.internal.SignInSession.AccountAuthentication;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.Kind;
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;

/**
 * Story 20.1 (AD-19, AD-20, AD-42): every request carries {@code Caller(accountId, kind)} with the transitional
 * role read from the account row, the session holds the account's ID and kind and nothing else, the
 * capabilities are read from the database on every call with validity judged by the Clock in Vietnam, the
 * session view answers the kind and the capabilities, and a session gone from the store leaves the next request
 * anonymous.
 */
class CallerKindIT extends IntegrationTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Autowired
	private Accounts accounts;

	@Autowired
	private Capabilities capabilities;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private MockMvc mvc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void theSessionHoldsTheAccountIdAndKindOnly() {
		assertThat(Arrays.stream(AccountAuthentication.class.getDeclaredFields())
			.filter(field -> !Modifier.isStatic(field.getModifiers()))
			.map(Field::getName)).containsExactlyInAnyOrder("accountId", "kind");
		AccountAuthentication authentication = new AccountAuthentication(UUID.randomUUID(), Kind.USER);
		assertThat(authentication.getAuthorities()).as("it grants no authority").isEmpty();
	}

	@Test
	void aGoogleAccountIsAUserWithNoRole() throws Exception {
		UUID lan = IdentityTestSupport.insertGoogleAccount(this.jdbc, "google-lan", "3.1");

		Caller caller = resolve(new AccountAuthentication(lan, Kind.USER), null);

		assertThat(caller).isEqualTo(new Caller(lan, Kind.USER, null));
		assertThat(caller.isUser()).isTrue();
		assertThat(caller.isAdministrator()).isFalse();
	}

	@Test
	void theLegacyRoleIsReadFromTheAccountRowOnEveryRequest() throws Exception {
		UUID hung = IdentityTestSupport.insertAccount(this.jdbc, Role.MENTOR, null);
		AccountAuthentication session = new AccountAuthentication(hung, Kind.USER);

		assertThat(resolve(session, null)).isEqualTo(new Caller(hung, Kind.USER, Role.MENTOR));

		this.jdbc.sql("UPDATE identity.account SET role = 'CUSTOMER' WHERE id = :id").param("id", hung).update();
		assertThat(resolve(session, null)).as("the same session, the role of this request")
			.isEqualTo(new Caller(hung, Kind.USER, Role.CUSTOMER));
	}

	@Test
	void anAdministratorSessionIsTheAdministratorKind() throws Exception {
		UUID admin = IdentityTestSupport.insertAccount(this.jdbc, Role.ADMINISTRATOR, null);
		MockHttpSession session = new MockHttpSession();
		AdminSessions.start(session, this.clock.instant());

		Caller caller = resolve(new AccountAuthentication(admin, Kind.ADMINISTRATOR), session);

		assertThat(caller).isEqualTo(new Caller(admin, Kind.ADMINISTRATOR, Role.ADMINISTRATOR));
		assertThat(caller.isAdministrator()).isTrue();
		assertThat(caller.isUser()).isFalse();
	}

	@Test
	void aSessionOfAnotherKindThanTheAccountOrOfALockedAccountIsAnonymousAndEnds() throws Exception {
		UUID admin = IdentityTestSupport.insertAccount(this.jdbc, Role.ADMINISTRATOR, null);
		MockHttpSession adminAsUser = new MockHttpSession();
		AdminSessions.start(adminAsUser, this.clock.instant());
		assertThat(resolve(new AccountAuthentication(admin, Kind.USER), adminAsUser).isAnonymous()).isTrue();
		assertThat(adminAsUser.isInvalid()).isTrue();

		UUID lan = IdentityTestSupport.insertGoogleAccount(this.jdbc, "google-lan", "3.1");
		MockHttpSession userAsAdmin = new MockHttpSession();
		AdminSessions.start(userAsAdmin, this.clock.instant());
		assertThat(resolve(new AccountAuthentication(lan, Kind.ADMINISTRATOR), userAsAdmin).isAnonymous())
			.isTrue();
		assertThat(userAsAdmin.isInvalid()).isTrue();

		UUID locked = IdentityTestSupport.insertAccount(this.jdbc, Role.MENTOR, "Vi phạm");
		MockHttpSession lockedSession = new MockHttpSession();
		assertThat(resolve(new AccountAuthentication(locked, Kind.USER), lockedSession).isAnonymous()).isTrue();
		assertThat(lockedSession.isInvalid()).isTrue();
	}

	@Test
	void theCapabilitiesAreReadOnEveryCall() {
		UUID vy = IdentityTestSupport.insertAccount(this.jdbc, Role.STUDENT, null);
		IdentityTestSupport.verifyStudent(this.jdbc, vy, LocalDate.of(2026, 9, 30));

		assertThat(this.capabilities.capabilities(vy)).isEqualTo(new Held(true, false, true, false));

		IdentityTestSupport.unlinkGitHub(this.jdbc, vy);
		assertThat(this.capabilities.capabilities(vy).githubVerified()).isFalse();
		this.jdbc.sql("UPDATE identity.account SET github_user_id = 990001, github_login = 'vy-dev' WHERE id = :id")
			.param("id", vy)
			.update();
		assertThat(this.capabilities.capabilities(vy).githubVerified()).as("linked again, read again").isTrue();

		IdentityTestSupport.lock(this.jdbc, vy, "Vi phạm");
		assertThat(this.capabilities.capabilities(vy)).isEqualTo(new Held(true, false, true, true));
		assertThat(this.capabilities.capabilities(UUID.randomUUID())).as("an unknown account holds nothing")
			.isEqualTo(Held.NONE);
	}

	@Test
	void aStudentVerificationLapsesAtTheEndOfItsLastDayInVietnam() {
		UUID vy = IdentityTestSupport.insertAccount(this.jdbc, Role.STUDENT, null);
		IdentityTestSupport.verifyStudent(this.jdbc, vy, LocalDate.of(2026, 9, 30));

		this.clock.setInstant(Instant.parse("2026-09-30T16:59:59Z"));
		assertThat(this.capabilities.capabilities(vy).studentVerified()).as("23:59:59 on its last day").isTrue();

		this.clock.setInstant(Instant.parse("2026-09-30T17:00:00Z"));
		assertThat(this.capabilities.capabilities(vy).studentVerified())
			.as("valid until yesterday in Vietnam, though still that day in UTC")
			.isFalse();

		UUID ended = IdentityTestSupport.insertAccount(this.jdbc, Role.STUDENT, null);
		IdentityTestSupport.verifyStudent(this.jdbc, ended, LocalDate.of(2027, 9, 30));
		this.jdbc.sql("UPDATE identity.verification SET ended_at = now(), ended_reason = 'UNLINKED', facts = NULL"
				+ " WHERE account_id = :id").param("id", ended).update();
		assertThat(this.capabilities.capabilities(ended).studentVerified()).as("an ended row counts for nothing")
			.isFalse();
	}

	@Test
	void theSessionViewAnswersTheKindAndTheCapabilitiesAndTheDeprecatedRole() throws Exception {
		UUID hung = IdentityTestSupport.insertAccount(this.jdbc, Role.MENTOR, null);
		JsonNode mentor = sessionAccount(
				IdentityTestSupport.signedIn(this.sessions, hung, Role.MENTOR, this.clock.instant()));
		assertThat(mentor.path("kind").asString()).isEqualTo("USER");
		assertThat(mentor.path("role").asString()).isEqualTo("MENTOR");
		assertCapabilities(mentor, true, false, false, false);

		UUID lan = IdentityTestSupport.insertGoogleAccount(this.jdbc, "google-lan", "3.1");
		IdentityTestSupport.verifyStudent(this.jdbc, lan, LocalDate.of(2026, 9, 30));
		JsonNode google = sessionAccount(IdentityTestSupport.signedIn(this.sessions, lan, null, this.clock.instant()));
		assertThat(google.path("kind").asString()).isEqualTo("USER");
		assertThat(google.path("role").isNull() || google.path("role").isMissingNode()).as("a Google account has none")
			.isTrue();
		assertCapabilities(google, false, false, true, false);

		UUID admin = IdentityTestSupport.insertAccount(this.jdbc, Role.ADMINISTRATOR, null);
		JsonNode administrator = sessionAccount(
				IdentityTestSupport.signedIn(this.sessions, admin, Role.ADMINISTRATOR, this.clock.instant()));
		assertThat(administrator.path("kind").asString()).isEqualTo("ADMINISTRATOR");
		assertCapabilities(administrator, false, false, false, false);
	}

	@Test
	void aSessionGoneFromTheStoreLeavesTheNextRequestAnonymous() throws Exception {
		UUID admin = IdentityTestSupport.insertAccount(this.jdbc, Role.ADMINISTRATOR, null);
		RequestPostProcessor stored = IdentityTestSupport.signedIn(this.sessions, admin, Role.ADMINISTRATOR,
				this.clock.instant());
		assertThat(session(stored).path("status").asString()).isEqualTo("SIGNED_IN");

		for (String statement : migration().replaceAll("(?m)^--.*$", "").split(";")) {
			if (!statement.isBlank()) {
				this.jdbc.sql(statement).update();
			}
		}

		assertThat(this.jdbc.sql("SELECT count(*) FROM platform.spring_session").query(Long.class).single()).isZero();
		assertThat(session(stored).path("status").asString()).isEqualTo("ANONYMOUS");
	}

	/** Runs the caller filter on one request whose session holds {@code authentication}; returns its caller. */
	private Caller resolve(Authentication authentication, @Nullable MockHttpSession session)
			throws ServletException, IOException {
		SecurityContextHolder.getContext().setAuthentication(authentication);
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/session");
		if (session != null) {
			request.setSession(session);
		}
		AtomicReference<Caller> seen = new AtomicReference<>(Caller.anonymous());
		new CallerFilter(this.accounts, this.clock).doFilter(request, new MockHttpServletResponse(),
				(req, res) -> {
					if (req.getAttribute(Caller.REQUEST_ATTRIBUTE) instanceof Caller caller) {
						seen.set(caller);
					}
				});
		return seen.get();
	}

	private JsonNode session(RequestPostProcessor signedIn) throws Exception {
		return JSON.readTree(this.mvc.perform(get("/api/v1/session").with(signedIn))
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8));
	}

	private JsonNode sessionAccount(RequestPostProcessor signedIn) throws Exception {
		JsonNode session = session(signedIn);
		assertThat(session.path("status").asString()).isEqualTo("SIGNED_IN");
		return session.path("account");
	}

	private static void assertCapabilities(JsonNode account, boolean github, boolean linkedin, boolean student,
			boolean locked) {
		JsonNode capabilities = account.path("capabilities");
		assertThat(capabilities.path("githubVerified").asBoolean()).as("githubVerified").isEqualTo(github);
		assertThat(capabilities.path("linkedinVerified").asBoolean()).as("linkedinVerified").isEqualTo(linkedin);
		assertThat(capabilities.path("studentVerified").asBoolean()).as("studentVerified").isEqualTo(student);
		assertThat(capabilities.path("locked").asBoolean()).as("locked").isEqualTo(locked);
		assertThat(capabilities.size()).isEqualTo(4);
	}

	private static String migration() throws IOException {
		try (InputStream in = CallerKindIT.class
			.getResourceAsStream("/db/migration/platform/V202610271000__platform_clear_sessions.sql")) {
			assertThat(in).isNotNull();
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

}
