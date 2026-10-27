package vn.vibefix.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.UriComponentsBuilder;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.vibefix.support.Browser;
import vn.vibefix.support.IntegrationTest;

/**
 * Story 19.1 (FR-1, FR-2, FR-48, NFR-9, AD-19, AD-22, AD-34): the whole Google sign-in against a stub
 * Google. One account per Google subject, with no role; the first sign-in waits on PUB-3 for N-10 and
 * holds no account until then; an unverified email, a locked account, and the GitHub start sign
 * nobody in; no Google token is kept; and an account with no role is refused by every role-gated
 * endpoint, never with a 500.
 */
class GoogleSignInIT extends IntegrationTest {

	private static final StubGoogle google = StubGoogle.instance();

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private IdentityProperties properties;

	@Autowired
	private ApplicationContext context;

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	private RequestMappingHandlerMapping handlerMapping;

	private Browser browser;

	/** Its own context too, so no other suite's {@code csrf()} helper has changed the CSRF filter (Browser). */
	@DynamicPropertySource
	static void stubGoogle(DynamicPropertyRegistry registry) {
		registry.add("google-oauth-client-id", () -> StubGoogle.CLIENT_ID);
		registry.add("google-oauth-client-secret", () -> "stub-google-secret");
		registry.add("vibefix.google.oauth.issuer-uri", google::baseUrl);
		registry.add("vibefix.google.oauth.authorization-uri", () -> google.baseUrl() + "/o/oauth2/v2/auth");
		registry.add("vibefix.google.oauth.token-uri", () -> google.baseUrl() + "/token");
		registry.add("vibefix.google.oauth.jwk-set-uri", () -> google.baseUrl() + "/certs");
		registry.add("vibefix.google.oauth.user-info-uri", () -> google.baseUrl() + "/userinfo");
	}

	@BeforeEach
	void newBrowser() {
		this.browser = new Browser(this.mvc);
		google.signsInAs("google-lan", "lan.nguyen@gmail.test", "Nguyễn Thị Lan");
	}

	@Test
	void theAuthorizationRequestAsksForExactlyOpenidEmailAndProfileWithANonce() throws Exception {
		MvcResult start = this.browser.perform(get("/oauth2/authorization/google"));

		assertThat(start.getResponse().getStatus()).isEqualTo(302);
		assertThat(start.getResponse().getRedirectedUrl()).startsWith(google.baseUrl() + "/o/oauth2/v2/auth");
		Map<String, List<String>> query = query(start);
		assertThat(query.get("scope")).containsExactly("openid%20email%20profile");
		assertThat(query.get("client_id")).containsExactly(StubGoogle.CLIENT_ID);
		assertThat(query.get("response_type")).containsExactly("code");
		assertThat(decode(query.get("redirect_uri").getFirst())).endsWith("/login/oauth2/code/google");
		assertThat(query).containsKey("state").containsKey("nonce");
		assertThat(query.get("prompt")).as("Google asks which account, after a sign-out too")
			.containsExactly("select_account");
	}

	@Test
	void aFirstSignInCreatesOneUserAccountWithNoRoleAndWaitsOnTheWelcomeHoldingNoAccount() throws Exception {
		assertThat(signIn()).isEqualTo("/bat-dau");

		Map<String, Object> account = this.jdbc.sql("SELECT * FROM identity.account").query().singleRow();
		assertThat(account).containsEntry("kind", "USER")
			.containsEntry("google_sub", "google-lan")
			.containsEntry("google_email", "lan.nguyen@gmail.test")
			.containsEntry("google_name", "Nguyễn Thị Lan");
		assertThat(account.get("role")).isNull();
		assertThat(account.get("github_user_id")).isNull();
		assertThat(this.jdbc.sql("SELECT source FROM identity.person_signal").query(String.class).list())
			.containsExactly("GOOGLE_EMAIL");
		assertThat(audits()).containsExactly("identity.account_created");

		JsonNode session = session();
		assertThat(session.path("status").asString()).isEqualTo("SIGNING_UP");
		assertThat(session.path("displayName").asString()).isEqualTo("Nguyễn Thị Lan");
		assertThat(session.path("privacyNoticeVersion").asString()).isEqualTo(version());
		assertThat(session.path("account").isNull()).isTrue();
		assertThat(this.browser.perform(get("/api/v1/todos")).getResponse().getStatus())
			.as("every other request is refused until N-10 is accepted")
			.isEqualTo(401);
		assertThat(sessionsHoldNoGoogleToken()).isTrue();
	}

	@Test
	void acceptingN10RecordsItOpensTheLinkGitHubToDoAndSignsInOnANewSessionWithNoRole() throws Exception {
		signIn();
		String before = this.browser.cookie(SecurityConfiguration.SESSION_COOKIE);

		MvcResult welcome = welcome(version());

		assertThat(welcome.getResponse().getStatus()).isEqualTo(201);
		JsonNode body = JSON.readTree(welcome.getResponse().getContentAsString());
		assertThat(body.path("status").asString()).isEqualTo("SIGNED_IN");
		assertThat(body.path("account").path("role").isNull()).as("a Google account has no role").isTrue();
		assertThat(body.path("displayName").asString()).isEqualTo("Nguyễn Thị Lan");
		assertThat(this.browser.cookie(SecurityConfiguration.SESSION_COOKIE)).as("the session ID rotates")
			.isNotEqualTo(before);
		assertThat(this.jdbc.sql("SELECT notice || ' ' || notice_version FROM identity.acknowledgment")
			.query(String.class)
			.list()).containsExactly("PRIVACY " + version());
		assertThat(audits()).containsExactly("identity.account_created", "identity.notice_acknowledged",
				"identity.signed_in");
		assertThat(this.jdbc.sql("""
				SELECT todo_key || ' ' || tier || ' ' || link FROM notification.todo
				WHERE account_id = subject_id AND closed_at IS NULL
				""").query(String.class).list()).containsExactly("identity.link_github 1 /ho-so/xac-minh");
		assertThat(session().path("status").asString()).isEqualTo("SIGNED_IN");
		assertThat(this.browser.perform(get("/api/v1/todos")).getResponse().getStatus()).isEqualTo(200);
		assertThat(sessionsHoldNoGoogleToken()).isTrue();
	}

	@Test
	void anOutdatedNoticeVersionIsStaleAndRecordsNothing() throws Exception {
		signIn();

		MvcResult welcome = welcome("1.0");

		assertThat(welcome.getResponse().getStatus()).isEqualTo(409);
		assertThat(JSON.readTree(welcome.getResponse().getContentAsString()).path("code").asString()).isEqualTo("STALE");
		assertThat(this.jdbc.sql("SELECT count(*) FROM identity.acknowledgment").query(Long.class).single()).isZero();
		assertThat(session().path("status").asString()).isEqualTo("SIGNING_UP");
	}

	@Test
	void theWelcomeNeedsAPendingSignInAndHappensOnce() throws Exception {
		session();
		MvcResult none = welcome(version());
		assertThat(none.getResponse().getStatus()).isEqualTo(404);
		assertThat(JSON.readTree(none.getResponse().getContentAsString()).path("code").asString()).isEqualTo("NOT_FOUND");

		signIn();
		welcome(version());
		MvcResult again = welcome(version());

		assertThat(again.getResponse().getStatus()).isEqualTo(409);
		assertThat(JSON.readTree(again.getResponse().getContentAsString()).path("code").asString()).isEqualTo("CONFLICT");
		assertThat(this.jdbc.sql("SELECT count(*) FROM identity.acknowledgment").query(Long.class).single()).isOne();
	}

	@Test
	void theSameSubjectSignsBackIntoTheSameAccountAndLandsOnTheToDoList() throws Exception {
		signIn();
		welcome(version());
		this.browser = new Browser(this.mvc);

		assertThat(signIn()).isEqualTo("/viec-can-lam");

		assertThat(countAccounts()).isOne();
		assertThat(session().path("status").asString()).isEqualTo("SIGNED_IN");
		assertThat(audits()).containsExactly("identity.account_created", "identity.notice_acknowledged",
				"identity.signed_in", "identity.signed_in");
	}

	@Test
	void aSignInReturnsToTheRouteTheSpaAskedForButNeverLeavesTheSite() throws Exception {
		signIn();
		welcome(version());

		this.browser = new Browser(this.mvc);
		assertThat(signIn("/viec-can-lam?trang=2")).isEqualTo("/viec-can-lam?trang=2");
		this.browser = new Browser(this.mvc);
		assertThat(signIn("//evil.example/x")).isEqualTo("/viec-can-lam");
		this.browser = new Browser(this.mvc);
		assertThat(signIn("https://evil.example")).isEqualTo("/viec-can-lam");
	}

	@Test
	void aChangedGmailAddressSignsIntoTheSameAccountAndBecomesWhereNotificationsGo() throws Exception {
		signIn();
		welcome(version());
		this.browser = new Browser(this.mvc);
		google.signsInAs("google-lan", "lan.tran@gmail.test", "Trần Thị Lan");

		assertThat(signIn()).isEqualTo("/viec-can-lam");

		assertThat(countAccounts()).isOne();
		assertThat(this.jdbc.sql("SELECT google_email || ' ' || google_name FROM identity.account").query(String.class)
			.single()).isEqualTo("lan.tran@gmail.test Trần Thị Lan");
		assertThat(this.jdbc.sql("SELECT count(*) FROM identity.person_signal WHERE source = 'GOOGLE_EMAIL'")
			.query(Long.class)
			.single()).as("the new address records its own signal").isEqualTo(2);
		UUID id = this.jdbc.sql("SELECT id FROM identity.account").query(UUID.class).single();
		assertThat(this.context.getBean(Accounts.class).notificationAddress(id)).contains("lan.tran@gmail.test");
	}

	@Test
	void aReturningAccountThatHasNotAcceptedTheCurrentNoticeGoesBackToTheWelcome() throws Exception {
		IdentityTestSupport.insertGoogleAccount(this.jdbc, "google-lan", "2.0");

		assertThat(signIn()).isEqualTo("/bat-dau");

		assertThat(session().path("status").asString()).isEqualTo("SIGNING_UP");
		assertThat(welcome(version()).getResponse().getStatus()).isEqualTo(201);
		assertThat(countAccounts()).isOne();
		assertThat(this.jdbc.sql("SELECT notice_version FROM identity.acknowledgment ORDER BY notice_version")
			.query(String.class)
			.list()).containsExactly("2.0", version());
	}

	@Test
	void anEmailGoogleHasNotVerifiedIsRefusedAndCreatesNothing() throws Exception {
		for (Boolean verified : new Boolean[] { Boolean.FALSE, null }) {
			this.browser = new Browser(this.mvc);
			google.emailVerified(verified);

			assertThat(signIn()).as("email_verified %s", verified).isEqualTo("/dang-nhap?loi=email-chua-xac-minh");

			assertThat(countAccounts()).isZero();
			assertThat(session().path("status").asString()).isEqualTo("ANONYMOUS");
		}
		assertThat(this.jdbc.sql("SELECT count(*) FROM identity.person_signal").query(Long.class).single()).isZero();
	}

	@ParameterizedTest
	@EnumSource(value = StubGoogle.Flaw.class, names = "NONE", mode = EnumSource.Mode.EXCLUDE)
	void anIdTokenSpringCannotValidateSignsNobodyIn(StubGoogle.Flaw flaw) throws Exception {
		google.flaw(flaw);

		assertThat(signIn()).isEqualTo("/dang-nhap?loi=that-bai");

		assertThat(countAccounts()).isZero();
		JsonNode session = session();
		assertThat(session.path("status").asString()).isEqualTo("ANONYMOUS");
		assertThat(session.path("account").isNull()).isTrue();
	}

	@Test
	void aSignInWithNoGoogleNameIsGreetedByItsEmail() throws Exception {
		google.signsInAs("google-an", "an.le@gmail.test", null);

		signIn();

		assertThat(session().path("displayName").asString()).isEqualTo("an.le@gmail.test");
		assertThat(JSON.readTree(welcome(version()).getResponse().getContentAsString()).path("displayName").asString())
			.isEqualTo("an.le@gmail.test");
	}

	@Test
	void anAccountLockedOnTheWelcomeCannotAcceptAndIsSignedOut() throws Exception {
		signIn();
		this.jdbc.sql("UPDATE identity.account SET locked_at = now(), lock_reason = 'x'").update();

		MvcResult welcome = welcome(version());

		assertThat(welcome.getResponse().getStatus()).isEqualTo(404);
		assertThat(this.jdbc.sql("SELECT count(*) FROM identity.acknowledgment").query(Long.class).single()).isZero();
		assertThat(this.jdbc.sql("SELECT count(*) FROM notification.todo WHERE todo_key = 'identity.link_github'")
			.query(Long.class)
			.single()).isZero();
		assertThat(session().path("status").asString()).isEqualTo("ANONYMOUS");
	}

	@Test
	void aLockedAccountIsRefusedWithItsReason() throws Exception {
		signIn();
		welcome(version());
		this.jdbc.sql("UPDATE identity.account SET locked_at = now(), lock_reason = 'Vi phạm quy định'").update();
		this.browser = new Browser(this.mvc);

		assertThat(signIn()).isEqualTo("/dang-nhap");

		JsonNode session = session();
		assertThat(session.path("status").asString()).isEqualTo("LOCKED");
		assertThat(session.path("lockReason").asString()).isEqualTo("Vi phạm quy định");
		assertThat(session.path("supportEmail").asString()).isEqualTo("hotro@vibefix.vn");
	}

	@Test
	void lockingASignedInAccountEndsItsSessionAtTheNextRequest() throws Exception {
		signIn();
		welcome(version());

		this.jdbc.sql("UPDATE identity.account SET locked_at = now(), lock_reason = 'x'").update();

		assertThat(session().path("status").asString()).isEqualTo("ANONYMOUS");
		assertThat(this.jdbc.sql("SELECT count(*) FROM platform.spring_session WHERE principal_name IS NOT NULL")
			.query(Long.class)
			.single()).isZero();
	}

	@Test
	void anAdministratorsEmailSignsIntoANewUserAccountNeverTheAdministrators() throws Exception {
		AdministratorTestSupport.Administrator administrator = AdministratorTestSupport.create(this.context,
				"ha.phuong@vibefix.vn", "Hà Phương");
		google.signsInAs("google-ha", "ha.phuong@vibefix.vn", "Hà Phương");

		signIn();
		JsonNode signedIn = JSON.readTree(welcome(version()).getResponse().getContentAsString());

		assertThat(signedIn.path("account").path("id").asString()).isNotEqualTo(administrator.accountId().toString());
		assertThat(signedIn.path("administrator").isNull()).isTrue();
		assertThat(this.jdbc.sql("SELECT kind FROM identity.account WHERE google_sub = 'google-ha'").query(String.class)
			.single()).isEqualTo("USER");
		assertThat(this.browser.perform(get("/api/v1/admin/users")).getResponse().getStatus()).isEqualTo(404);
	}

	@Test
	void theGitHubStartAndCallbackSignNobodyIn() throws Exception {
		MvcResult start = this.browser.perform(get("/oauth2/authorization/github"));
		assertThat(start.getResponse().getRedirectedUrl()).isEqualTo("/dang-nhap?loi=that-bai");

		MvcResult callback = this.browser.perform(get("/login/oauth2/code/github").param("code", "c-1").param("state", "s"));
		assertThat(callback.getResponse().getRedirectedUrl()).isEqualTo("/dang-nhap?loi=that-bai");

		assertThat(countAccounts()).isZero();
		assertThat(session().path("status").asString()).isEqualTo("ANONYMOUS");
	}

	@Test
	void aCallbackWithAForgedStateSignsNobodyIn() throws Exception {
		this.browser.perform(get("/oauth2/authorization/google"));

		MvcResult callback = this.browser.perform(get("/login/oauth2/code/google").param("code", "c-1")
			.param("state", "forged"));

		assertThat(callback.getResponse().getRedirectedUrl()).isEqualTo("/dang-nhap?loi=that-bai");
		assertThat(countAccounts()).isZero();
	}

	@Test
	void aCommandWithoutTheCsrfHeaderIsForbidden() throws Exception {
		signIn();

		MvcResult welcome = this.browser.commandWithoutCsrf(post("/api/v1/session/sign-up")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"privacyNoticeVersion\":\"" + version() + "\"}"));

		assertThat(welcome.getResponse().getStatus()).isEqualTo(403);
		assertThat(welcome.getResponse().getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		assertThat(JSON.readTree(welcome.getResponse().getContentAsString()).path("code").asString())
			.isEqualTo("FORBIDDEN");
		assertThat(this.jdbc.sql("SELECT count(*) FROM identity.acknowledgment").query(Long.class).single()).isZero();
	}

	@Test
	void signOutEndsTheSession() throws Exception {
		signIn();
		welcome(version());

		assertThat(this.browser.command(post("/api/v1/session/sign-out")).getResponse().getStatus()).isEqualTo(204);

		assertThat(session().path("status").asString()).isEqualTo("ANONYMOUS");
	}

	@Test
	void theSessionCookieIsHttpOnlySecureAndSameSiteLax() throws Exception {
		MvcResult start = this.browser.perform(get("/oauth2/authorization/google"));

		String cookie = start.getResponse()
			.getHeaders("Set-Cookie")
			.stream()
			.filter(header -> header.startsWith("VIBEFIX_SESSION="))
			.findFirst()
			.orElseThrow();
		assertThat(cookie).contains("HttpOnly").contains("Secure").containsIgnoringCase("SameSite=Lax");
	}

	@Test
	void tooManySignInAttemptsFromOneAddressAreRateLimited() throws Exception {
		for (int attempt = 1; attempt <= 20; attempt++) {
			assertThat(this.browser.perform(get("/oauth2/authorization/google")).getResponse().getStatus()).isEqualTo(302);
		}

		MvcResult refused = this.browser.perform(get("/oauth2/authorization/google"));
		MvcResult callback = this.browser.perform(get("/login/oauth2/code/google").param("code", "c").param("state", "s"));

		for (MvcResult result : List.of(refused, callback)) {
			assertThat(result.getResponse().getStatus()).isEqualTo(429);
			assertThat(result.getResponse().getHeader("Retry-After")).isNotBlank();
			assertThat(JSON.readTree(result.getResponse().getContentAsString()).path("code").asString())
				.isEqualTo("RATE_LIMITED");
		}
	}

	@Test
	void anAccountWithNoRoleIsRefusedByTheRoleGatedEndpointsAndNeverGetsA500() throws Exception {
		signIn();
		welcome(version());

		// Role-gated endpoints with valid bodies: refused (deny by default, AD-20) until Epic 20.
		assertThat(status(HttpMethod.GET, "/api/v1/products", null)).isIn(403, 404);
		assertThat(status(HttpMethod.POST, "/api/v1/products", body("product-registration.json"))).isIn(403, 404);
		assertThat(status(HttpMethod.GET, "/api/v1/jobs", null)).isIn(403, 404);
		assertThat(status(HttpMethod.POST, "/api/v1/skill-suggestions", body("skill-suggestion.json"))).isEqualTo(403);
		assertThat(status(HttpMethod.GET, "/api/v1/admin/users", null)).isEqualTo(404);
		// What any signed-in account may do still works, commands included (so the refusals above are not CSRF's).
		assertThat(status(HttpMethod.PUT, "/api/v1/account/theme", body("account-theme.json"))).isEqualTo(204);
		// The one profile has no role (Story 19.3): an account with none saves and reads it.
		assertThat(status(HttpMethod.PUT, "/api/v1/profile", body("profile.json"))).isEqualTo(204);
		assertThat(status(HttpMethod.GET, "/api/v1/profile", null)).isEqualTo(200);
		// So is the student email row (Story 19.4).
		assertThat(status(HttpMethod.GET, "/api/v1/verification", null)).isEqualTo(200);
		assertThat(status(HttpMethod.GET, "/api/v1/todos", null)).isEqualTo(200);

		// Every endpoint under /api, with placeholder IDs and an empty body: anything but a 500.
		List<String> failures = new ArrayList<>();
		for (String endpoint : endpoints()) {
			String[] methodAndPath = endpoint.split(" ", 2);
			String path = methodAndPath[1].replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
			HttpMethod method = HttpMethod.valueOf(methodAndPath[0]);
			int status = status(method, path, method == HttpMethod.GET || method == HttpMethod.DELETE ? null : "{}");
			if (status >= 500) {
				failures.add(endpoint + " answered " + status);
			}
		}
		assertThat(failures).isEmpty();
		assertThat(session().path("status").asString()).as("still signed in after the sweep").isEqualTo("SIGNED_IN");
	}

	/** Every {@code METHOD /api/...} handler, but the sign-out, which would end the sweep's session. */
	private Set<String> endpoints() {
		Set<String> endpoints = new TreeSet<>();
		for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : this.handlerMapping.getHandlerMethods().entrySet()) {
			for (String pattern : entry.getKey().getPatternValues()) {
				if (!pattern.startsWith("/api/") || pattern.endsWith("/sign-out")) {
					continue;
				}
				for (RequestMethod method : entry.getKey().getMethodsCondition().getMethods()) {
					endpoints.add(method.name() + " " + pattern);
				}
			}
		}
		assertThat(endpoints).hasSizeGreaterThan(100);
		return endpoints;
	}

	private int status(HttpMethod method, String path, @Nullable String json) throws Exception {
		MockHttpServletRequestBuilder request = request(method, path);
		if (json != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(json);
		}
		return (method == HttpMethod.GET ? this.browser.perform(request) : this.browser.command(request)).getResponse()
			.getStatus();
	}

	private static String body(String name) throws IOException {
		try (InputStream in = GoogleSignInIT.class.getResourceAsStream("/authz/bodies/" + name)) {
			assertThat(in).as(name).isNotNull();
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/** Runs the OIDC redirect and callback; returns where the application sends the browser. */
	private String signIn() throws Exception {
		return signIn(null);
	}

	private String signIn(@Nullable String returnTo) throws Exception {
		MockHttpServletRequestBuilder start = get("/oauth2/authorization/google");
		if (returnTo != null) {
			start.param("quay-lai", returnTo);
		}
		MvcResult authorize = this.browser.perform(start);
		Map<String, List<String>> query = query(authorize);
		// The redirect URL carries the state and the nonce URL-encoded (Base64 padding is %3D).
		google.nonce(decode(query.get("nonce").getFirst()));
		MvcResult callback = this.browser.perform(get("/login/oauth2/code/google").param("code", "c-" + UUID.randomUUID())
			.param("state", decode(query.get("state").getFirst())));
		assertThat(callback.getResponse().getStatus()).as("callback status").isEqualTo(302);
		return callback.getResponse().getRedirectedUrl();
	}

	private MvcResult welcome(String version) throws Exception {
		return this.browser.command(post("/api/v1/session/sign-up").contentType(MediaType.APPLICATION_JSON)
			.content("{\"privacyNoticeVersion\":\"" + version + "\"}"));
	}

	private JsonNode session() throws Exception {
		return JSON.readTree(this.browser.perform(get("/api/v1/session")).getResponse().getContentAsString());
	}

	private String version() {
		return this.properties.privacyNoticeVersion();
	}

	private List<String> audits() {
		// By name: the entries of one test share the clock's instant.
		return this.jdbc.sql("SELECT action FROM audit.audit_entry WHERE action LIKE 'identity.%' ORDER BY action")
			.query(String.class)
			.list();
	}

	private long countAccounts() {
		return this.jdbc.sql("SELECT count(*) FROM identity.account WHERE kind = 'USER'").query(Long.class).single();
	}

	/** No session attribute holds a token or an ID token Google issued (AD-22). */
	private boolean sessionsHoldNoGoogleToken() {
		List<String> attributes = this.jdbc.sql("SELECT attribute_bytes FROM platform.spring_session_attributes")
			.query((rs, n) -> new String(rs.getBytes(1), StandardCharsets.ISO_8859_1))
			.list();
		return google.issued().stream().noneMatch(token -> attributes.stream().anyMatch(bytes -> bytes.contains(token)));
	}

	private static Map<String, List<String>> query(MvcResult redirect) {
		return UriComponentsBuilder.fromUri(URI.create(redirect.getResponse().getRedirectedUrl())).build().getQueryParams();
	}

	private static String decode(String value) {
		return URLDecoder.decode(value, StandardCharsets.UTF_8);
	}

}
