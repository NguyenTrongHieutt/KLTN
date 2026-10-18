package vn.vibefix.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
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
import org.springframework.web.util.UriComponentsBuilder;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.vibefix.support.Browser;
import vn.vibefix.support.IntegrationTest;

/**
 * FR-1, AD-19, AD-22, SG-9, SG-11: the whole GitHub sign-in against a stub GitHub. One account per
 * GitHub numeric ID with one permanent role; the role is chosen once on PUB-3 with the personal-data
 * notice; only a Customer's token is kept, encrypted to its account; a locked account is refused.
 */
class GitHubSignInIT extends IntegrationTest {

	private static final StubGitHub github = StubGitHub.instance();

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private TokenCipher cipher;

	private Browser browser;

	@DynamicPropertySource
	static void stubGitHub(DynamicPropertyRegistry registry) {
		registry.add("github-oauth-client-id", () -> "stub-client");
		registry.add("github-oauth-client-secret", () -> "stub-secret");
		registry.add("vibefix.github.oauth.authorization-uri", () -> github.baseUrl() + "/login/oauth/authorize");
		registry.add("vibefix.github.oauth.token-uri", () -> github.baseUrl() + "/login/oauth/access_token");
		registry.add("vibefix.github.oauth.user-info-uri", () -> github.baseUrl() + "/user");
		registry.add("vibefix.github.oauth.user-emails-uri", () -> github.baseUrl() + "/user/emails");
	}

	@BeforeEach
	void newBrowser() {
		this.browser = new Browser(this.mvc);
	}

	@Test
	void theAuthorizationRequestAsksForExactlyTheIdentityAndEmailScopes() throws Exception {
		MvcResult start = this.browser.perform(get("/oauth2/authorization/github"));

		assertThat(start.getResponse().getStatus()).isEqualTo(302);
		Map<String, List<String>> query = UriComponentsBuilder.fromUri(URI.create(start.getResponse().getRedirectedUrl()))
			.build()
			.getQueryParams();
		assertThat(start.getResponse().getRedirectedUrl()).startsWith(github.baseUrl() + "/login/oauth/authorize");
		assertThat(query.get("scope")).containsExactly("read:user%20user:email");
		assertThat(query.get("client_id")).containsExactly("stub-client");
		assertThat(query).containsKey("state");
	}

	@Test
	void aFirstSignInWaitsForTheRoleAndCreatesNoAccountYet() throws Exception {
		github.signsInAs(4242, "lan");

		assertThat(signIn()).isEqualTo("/bat-dau");

		JsonNode session = session();
		assertThat(session.path("status").asString()).isEqualTo("SIGNING_UP");
		assertThat(session.path("githubLogin").asString()).isEqualTo("lan");
		assertThat(session.path("privacyNoticeVersion").asString()).isEqualTo("2.0");
		assertThat(countAccounts()).isZero();
		assertThat(sessionAttributesHoldNoToken()).isTrue();
	}

	@Test
	void aCustomerChoosesTheRoleOnceAndKeepsTheTokenEncryptedToTheAccount() throws Exception {
		github.signsInAs(4242, "lan");
		signIn();
		String token = github.lastToken();

		MvcResult signUp = signUp("CUSTOMER", "2.0");

		assertThat(signUp.getResponse().getStatus()).isEqualTo(201);
		JsonNode body = JSON.readTree(signUp.getResponse().getContentAsString());
		assertThat(body.path("status").asString()).isEqualTo("SIGNED_IN");
		UUID accountId = UUID.fromString(body.path("account").path("id").asString());
		assertThat(body.path("account").path("role").asString()).isEqualTo("CUSTOMER");
		Map<String, Object> account = this.jdbc.sql("SELECT * FROM identity.account").query().singleRow();
		assertThat(account).containsEntry("github_user_id", 4242L)
			.containsEntry("github_login", "lan")
			.containsEntry("role", "CUSTOMER");
		assertThat(this.jdbc.sql("SELECT notice || ' ' || notice_version FROM identity.acknowledgment")
			.query(String.class)
			.list()).containsExactly("PRIVACY 2.0");
		assertThat(this.jdbc.sql("SELECT action FROM audit.audit_entry ORDER BY occurred_at, action")
			.query(String.class)
			.list()).containsExactly("identity.account_created", "identity.notice_acknowledged");
		TokenCipher.Sealed sealed = this.jdbc.sql("SELECT key_version, nonce, ciphertext FROM identity.customer_token")
			.query((rs, n) -> new TokenCipher.Sealed(rs.getInt(1), rs.getBytes(2), rs.getBytes(3)))
			.single();
		assertThat(this.cipher.open(sealed, accountId.toString())).isEqualTo(token);
		assertThat(new String(sealed.ciphertext())).doesNotContain(token);
		assertThat(session().path("status").asString()).isEqualTo("SIGNED_IN");
		assertThat(signUp.getResponse().getContentAsString()).doesNotContain(token);
	}

	@Test
	void aMentorStartsPendingAndNeitherAMentorNorAStudentTokenIsKept() throws Exception {
		github.signsInAs(5001, "hung");
		signIn();
		assertThat(signUp("MENTOR", "2.0").getResponse().getStatus()).isEqualTo(201);
		this.browser = new Browser(this.mvc);
		github.signsInAs(5002, "minh");
		signIn();
		assertThat(signUp("STUDENT", "2.0").getResponse().getStatus()).isEqualTo(201);

		assertThat(this.jdbc.sql("SELECT github_login || ' ' || role || ' ' || coalesce(mentor_approval_status, '-')"
				+ " FROM identity.account ORDER BY github_login").query(String.class).list())
			.containsExactly("hung MENTOR PENDING", "minh STUDENT -");
		assertThat(this.jdbc.sql("SELECT count(*) FROM identity.customer_token").query(Long.class).single()).isZero();
	}

	@Test
	void notificationsGoToThePrimaryVerifiedEmailReadAtEverySignIn() throws Exception {
		github.signsInAs(4242, "lan");
		signIn();
		signUp("CUSTOMER", "2.0");
		assertThat(githubEmail()).isEqualTo("lan@example.test");

		this.browser = new Browser(this.mvc);
		github.signsInAs(4242, "lan-nguyen");
		signIn();
		assertThat(githubEmail()).as("refreshed at sign-in").isEqualTo("lan-nguyen@example.test");

		this.browser = new Browser(this.mvc);
		github.emailsAnswer(503);
		assertThat(signIn()).as("GitHub not answering does not stop the sign-in").isEqualTo("/viec-can-lam");
		assertThat(githubEmail()).as("the address on file stays").isEqualTo("lan-nguyen@example.test");
	}

	@Test
	void aNewMentorOrStudentStartsWithTheirSignUpToDo() throws Exception {
		github.signsInAs(5001, "hung");
		signIn();
		signUp("MENTOR", "2.0");
		this.browser = new Browser(this.mvc);
		github.signsInAs(5002, "minh");
		signIn();
		signUp("STUDENT", "2.0");
		this.browser = new Browser(this.mvc);
		github.signsInAs(5003, "lan");
		signIn();
		signUp("CUSTOMER", "2.0");

		assertThat(this.jdbc.sql("""
				SELECT account.github_login || ' ' || todo.todo_key || ' ' || todo.tier || ' ' || todo.link
				FROM notification.todo todo JOIN identity.account account ON account.id = todo.account_id
				WHERE todo.subject_id = todo.account_id AND todo.closed_at IS NULL
				ORDER BY account.github_login
				""").query(String.class).list())
			.containsExactly("hung identity.complete_mentor_application 1 /ho-so", "minh identity.verify_student 3 /ho-so");
	}

	@Test
	void theSameGitHubIdSignsIntoTheSameAccountEvenAfterARename() throws Exception {
		github.signsInAs(4242, "lan");
		signIn();
		signUp("CUSTOMER", "2.0");
		this.browser = new Browser(this.mvc);
		github.signsInAs(4242, "lan-nguyen");

		assertThat(signIn()).isEqualTo("/viec-can-lam");

		assertThat(countAccounts()).isOne();
		assertThat(this.jdbc.sql("SELECT github_login FROM identity.account").query(String.class).single())
			.isEqualTo("lan-nguyen");
		assertThat(session().path("status").asString()).isEqualTo("SIGNED_IN");
	}

	@Test
	void aPendingMentorLandsOnTheMentorProfileOnly() throws Exception {
		github.signsInAs(5001, "hung");
		signIn();
		signUp("MENTOR", "2.0");
		this.browser = new Browser(this.mvc);

		assertThat(signIn()).isEqualTo("/ho-so");
	}

	@Test
	void aLockedAccountIsRefusedWithItsReason() throws Exception {
		github.signsInAs(4242, "lan");
		signIn();
		signUp("CUSTOMER", "2.0");
		this.jdbc.sql("UPDATE identity.account SET locked_at = now(), lock_reason = 'Vi phạm quy định'").update();
		this.browser = new Browser(this.mvc);

		assertThat(signIn()).isEqualTo("/dang-nhap");

		JsonNode session = session();
		assertThat(session.path("status").asString()).isEqualTo("LOCKED");
		assertThat(session.path("lockReason").asString()).isEqualTo("Vi phạm quy định");
		assertThat(session.path("supportEmail").asString()).isEqualTo("hotro@vibefix.vn");
	}

	@Test
	void aSignInReturnsToTheRouteTheSpaAskedForButNeverLeavesTheSite() throws Exception {
		github.signsInAs(4242, "lan");
		signIn();
		signUp("CUSTOMER", "2.0");

		this.browser = new Browser(this.mvc);
		assertThat(signIn("/viec-can-lam?trang=2")).isEqualTo("/viec-can-lam?trang=2");
		this.browser = new Browser(this.mvc);
		assertThat(signIn("//evil.example/x")).isEqualTo("/viec-can-lam");
		this.browser = new Browser(this.mvc);
		assertThat(signIn("https://evil.example")).isEqualTo("/viec-can-lam");
	}

	@Test
	void anOutdatedNoticeVersionIsStaleAndCreatesNothing() throws Exception {
		github.signsInAs(4242, "lan");
		signIn();

		MvcResult signUp = signUp("CUSTOMER", "1.0");

		assertThat(signUp.getResponse().getStatus()).isEqualTo(409);
		assertThat(JSON.readTree(signUp.getResponse().getContentAsString()).path("code").asString()).isEqualTo("STALE");
		assertThat(countAccounts()).isZero();
		assertThat(session().path("status").asString()).isEqualTo("SIGNING_UP");
	}

	@Test
	void signUpNeedsAPendingSignInAndHappensOnce() throws Exception {
		session();
		assertThat(signUp("CUSTOMER", "2.0").getResponse().getStatus()).isEqualTo(404);

		github.signsInAs(4242, "lan");
		signIn();
		signUp("CUSTOMER", "2.0");
		MvcResult again = signUp("STUDENT", "2.0");

		assertThat(again.getResponse().getStatus()).isEqualTo(409);
		assertThat(JSON.readTree(again.getResponse().getContentAsString()).path("code").asString()).isEqualTo("CONFLICT");
		assertThat(countAccounts()).isOne();
	}

	@Test
	void anAdministratorRoleCannotBeChosen() throws Exception {
		github.signsInAs(4242, "lan");
		signIn();

		MvcResult signUp = signUp("ADMINISTRATOR", "2.0");

		assertThat(signUp.getResponse().getStatus()).isEqualTo(400);
		assertThat(countAccounts()).isZero();
	}

	@Test
	void aCommandWithoutTheCsrfHeaderIsForbidden() throws Exception {
		github.signsInAs(4242, "lan");
		signIn();

		MvcResult signUp = this.browser.commandWithoutCsrf(post("/api/v1/session/sign-up")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"role\":\"CUSTOMER\",\"privacyNoticeVersion\":\"2.0\"}"));

		assertThat(signUp.getResponse().getStatus()).isEqualTo(403);
		assertThat(signUp.getResponse().getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		assertThat(JSON.readTree(signUp.getResponse().getContentAsString()).path("code").asString())
			.isEqualTo("FORBIDDEN");
		assertThat(countAccounts()).isZero();
	}

	@Test
	void signOutEndsTheSession() throws Exception {
		github.signsInAs(4242, "lan");
		signIn();
		signUp("CUSTOMER", "2.0");

		assertThat(this.browser.command(post("/api/v1/session/sign-out")).getResponse().getStatus()).isEqualTo(204);

		assertThat(session().path("status").asString()).isEqualTo("ANONYMOUS");
	}

	@Test
	void lockingASignedInAccountEndsItsSessionAtTheNextRequest() throws Exception {
		github.signsInAs(4242, "lan");
		signIn();
		signUp("CUSTOMER", "2.0");
		assertThat(session().path("status").asString()).isEqualTo("SIGNED_IN");

		this.jdbc.sql("UPDATE identity.account SET locked_at = now(), lock_reason = 'x'").update();

		assertThat(session().path("status").asString()).isEqualTo("ANONYMOUS");
		assertThat(this.jdbc.sql("SELECT count(*) FROM platform.spring_session WHERE principal_name IS NOT NULL")
			.query(Long.class)
			.single()).isZero();
	}

	@Test
	void theSessionCookieIsHttpOnlySecureAndSameSiteLax() throws Exception {
		MvcResult start = this.browser.perform(get("/oauth2/authorization/github"));

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
			assertThat(this.browser.perform(get("/oauth2/authorization/github")).getResponse().getStatus()).isEqualTo(302);
		}

		MvcResult refused = this.browser.perform(get("/oauth2/authorization/github"));

		assertThat(refused.getResponse().getStatus()).isEqualTo(429);
		assertThat(refused.getResponse().getHeader("Retry-After")).isNotBlank();
		assertThat(JSON.readTree(refused.getResponse().getContentAsString()).path("code").asString())
			.isEqualTo("RATE_LIMITED");
	}

	/** Runs the OAuth redirect and callback; returns where the application sends the browser. */
	private String signIn() throws Exception {
		return signIn(null);
	}

	private String signIn(String returnTo) throws Exception {
		var start = get("/oauth2/authorization/github");
		if (returnTo != null) {
			start.param("quay-lai", returnTo);
		}
		MvcResult authorize = this.browser.perform(start);
		// The redirect URL carries the state URL-encoded (its Base64 padding is %3D).
		String state = URLDecoder.decode(UriComponentsBuilder.fromUri(URI.create(authorize.getResponse().getRedirectedUrl()))
			.build()
			.getQueryParams()
			.getFirst("state"), StandardCharsets.UTF_8);
		MvcResult callback = this.browser.perform(get("/login/oauth2/code/github").param("code", "c-" + UUID.randomUUID())
			.param("state", state));
		assertThat(callback.getResponse().getStatus()).as("callback status").isEqualTo(302);
		return callback.getResponse().getRedirectedUrl();
	}

	private MvcResult signUp(String role, String version) throws Exception {
		return this.browser.command(post("/api/v1/session/sign-up").contentType(MediaType.APPLICATION_JSON)
			.content("{\"role\":\"" + role + "\",\"privacyNoticeVersion\":\"" + version + "\"}"));
	}

	private JsonNode session() throws Exception {
		return JSON.readTree(this.browser.perform(get("/api/v1/session")).getResponse().getContentAsString());
	}

	private String githubEmail() {
		return this.jdbc.sql("SELECT github_email FROM identity.account").query(String.class).single();
	}

	private long countAccounts() {
		return this.jdbc.sql("SELECT count(*) FROM identity.account").query(Long.class).single();
	}

	/** No session attribute holds the token in clear. */
	private boolean sessionAttributesHoldNoToken() {
		String token = github.lastToken();
		return this.jdbc.sql("SELECT attribute_bytes FROM platform.spring_session_attributes")
			.query((rs, n) -> new String(rs.getBytes(1), java.nio.charset.StandardCharsets.ISO_8859_1))
			.list()
			.stream()
			.noneMatch(bytes -> bytes.contains(token));
	}

}
