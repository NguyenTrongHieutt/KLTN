package vn.vibefix.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.UriComponentsBuilder;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.vibefix.identity.CustomerRepositories;
import vn.vibefix.shared.ApiException;
import vn.vibefix.support.Browser;
import vn.vibefix.support.IntegrationTest;

/**
 * Story 19.2 (FR-80, R14, AD-10, AD-19, AD-22, AD-24, AD-42): linking GitHub to a signed-in Google account
 * against a stub GitHub, through the start, GitHub's page (skipped), and the callback. N-24 first; one
 * GitHub ID per account and nothing merged; a re-link only of the same ID; the token kept only for a first
 * Product or a live Product's owner; unlinking only without blockers; and the commands that create work
 * refused without a link. Logins by ID are {@code repo}'s ({@code JobSetupIT}).
 */
class GitHubLinkIT extends IntegrationTest {

	private static final StubGoogle google = StubGoogle.instance();

	private static final StubGitHub github = StubGitHub.instance();

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String CLIENT_ID = "stub-github-client";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private TokenCipher cipher;

	@Autowired
	private TokenRenewals renewals;

	@Autowired
	private CustomerRepositories repositories;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	private Browser browser;

	/** Its own context, so no other suite's {@code csrf()} helper has changed the CSRF filter (Browser). */
	@DynamicPropertySource
	static void stubs(DynamicPropertyRegistry registry) {
		registry.add("google-oauth-client-id", () -> StubGoogle.CLIENT_ID);
		registry.add("google-oauth-client-secret", () -> "stub-google-secret");
		registry.add("vibefix.google.oauth.issuer-uri", google::baseUrl);
		registry.add("vibefix.google.oauth.authorization-uri", () -> google.baseUrl() + "/o/oauth2/v2/auth");
		registry.add("vibefix.google.oauth.token-uri", () -> google.baseUrl() + "/token");
		registry.add("vibefix.google.oauth.jwk-set-uri", () -> google.baseUrl() + "/certs");
		registry.add("vibefix.google.oauth.user-info-uri", () -> google.baseUrl() + "/userinfo");
		registry.add("github-oauth-client-id", () -> CLIENT_ID);
		registry.add("github-oauth-client-secret", () -> "stub-github-secret");
		registry.add("vibefix.github.oauth.authorization-uri", () -> github.baseUrl() + "/login/oauth/authorize");
		registry.add("vibefix.github.oauth.token-uri", () -> github.baseUrl() + "/login/oauth/access_token");
		registry.add("vibefix.github.oauth.user-info-uri", () -> github.baseUrl() + "/user");
		registry.add("vibefix.github.oauth.user-emails-uri", () -> github.baseUrl() + "/user/emails");
	}

	@BeforeEach
	void aSignedInGoogleAccount() throws Exception {
		this.browser = new Browser(this.mvc);
		github.tokenRequests().clear();
		github.refusesCodes(false);
		github.signsInAs(4242, "lan-dev");
	}

	@AfterEach
	void reset() {
		github.refusesCodes(false);
	}

	@Test
	void theStartRecordsN24AndAsksGitHubForExactlyReadUserAndUserEmailWithPkce() throws Exception {
		UUID lan = signIn(this.browser, "google-lan", "lan.nguyen@gmail.test", "Nguyễn Thị Lan");

		MvcResult start = start(this.browser, "1.0", "PROFILE", null);

		assertThat(start.getResponse().getStatus()).isEqualTo(200);
		String url = JSON.readTree(start.getResponse().getContentAsString()).path("authorizationUrl").asString();
		assertThat(url).startsWith(github.baseUrl() + "/login/oauth/authorize?");
		Map<String, String> query = query(url);
		assertThat(query).containsEntry("response_type", "code")
			.containsEntry("client_id", CLIENT_ID)
			.containsEntry("scope", "read:user user:email")
			.containsEntry("code_challenge_method", "S256")
			.containsEntry("allow_signup", "false");
		assertThat(query.get("redirect_uri")).isEqualTo("http://localhost/link/oauth2/code/github");
		assertThat(query.get("state")).as("32 random bytes, base64url").matches("[A-Za-z0-9_-]{43}");
		assertThat(query.get("code_challenge")).matches("[A-Za-z0-9_-]{43}");
		assertThat(this.jdbc.sql("""
				SELECT notice || ' ' || notice_version FROM identity.acknowledgment WHERE account_id = :id ORDER BY notice
				""").param("id", lan).query(String.class).list()).containsExactly("LINK_GITHUB 1.0", "PRIVACY 3.1");
		assertThat(github.tokenRequests()).as("nothing is exchanged before GitHub returns").isEmpty();
	}

	@Test
	void linkingRecordsTheGitHubAccountItsVerifiedEmailsAndClosesTheToDo() throws Exception {
		UUID lan = signIn(this.browser, "google-lan", "lan.nguyen@gmail.test", "Nguyễn Thị Lan");
		assertThat(openTodos(lan, "identity.link_github")).isOne();
		assertThat(sessionAccount().path("capabilities").path("githubVerified").asBoolean()).isFalse();

		Map<String, String> authorization = authorization(this.browser, "PROFILE", null);
		String target = callback(this.browser, authorization.get("state"));

		assertThat(target).isEqualTo("/ho-so/xac-minh?ket-qua=da-lien-ket");
		Map<String, Object> account = this.jdbc.sql("""
				SELECT github_user_id, github_login, github_avatar_url, github_email
				FROM identity.account WHERE id = :id
				""").param("id", lan).query().singleRow();
		assertThat(account).containsEntry("github_user_id", 4242L)
			.containsEntry("github_login", "lan-dev")
			.containsEntry("github_avatar_url", "https://avatars.example/u/4242")
			.containsEntry("github_email", "lan-dev@example.test");
		assertThat(linkedAt(lan)).isEqualTo(this.clock.instant());
		assertThat(signals(lan)).as("each verified email, never the unverified one").isEqualTo(2);
		assertThat(openTodos(lan, "identity.link_github")).isZero();
		assertThat(githubAudits()).contains("identity.github_linked");
		Map<String, String> exchange = form(github.tokenRequests().getLast());
		assertThat(exchange).containsEntry("client_id", CLIENT_ID).containsEntry("redirect_uri",
				"http://localhost/link/oauth2/code/github");
		assertThat(GitHubLinkController.challenge(exchange.get("code_verifier"))).as("the PKCE verifier arrives")
			.isEqualTo(authorization.get("code_challenge"));
		assertThat(keptToken(lan)).as("ACC-2 keeps no token for an account without a live Product").isNull();

		JsonNode row = read(this.browser);
		assertThat(row.path("linked").asBoolean()).isTrue();
		assertThat(row.path("login").asString()).isEqualTo("lan-dev");
		assertThat(Instant.parse(row.path("linkedAt").asString())).isEqualTo(this.clock.instant());
		assertThat(row.path("relinkNeeded").asBoolean()).isFalse();
		assertThat(row.path("repositoryTokenKept").asBoolean()).isFalse();
		assertThat(row.path("blockers").size()).isZero();
		assertThat(row.path("noticeVersion").asString()).isEqualTo("1.0");
		JsonNode session = JSON.readTree(this.browser.perform(get("/api/v1/session")).getResponse().getContentAsString());
		assertThat(session.path("status").asString()).as("linking signs nobody in or out").isEqualTo("SIGNED_IN");
		assertThat(session.path("account").path("id").asString()).isEqualTo(lan.toString());
		assertThat(session.path("githubLogin").asString()).isEqualTo("lan-dev");
		assertThat(session.path("account").path("capabilities").path("githubVerified").asBoolean())
			.as("read on the next request, with no new sign-in (Story 20.1)")
			.isTrue();
		assertThat(sessionsHold(github.lastToken())).as("the token never reaches the session").isFalse();
	}

	@Test
	void aLinkStartedForAFirstProductKeepsTheTokenSealedWithTheAccountIdAndReturnsWhereItStarted() throws Exception {
		UUID lan = signIn(this.browser, "google-lan", "lan.nguyen@gmail.test", "Nguyễn Thị Lan");

		String target = callback(this.browser, authorization(this.browser, "PRODUCT", "/san-pham/moi").get("state"));

		assertThat(target).isEqualTo("/san-pham/moi");
		TokenCipher.Sealed sealed = keptToken(lan);
		assertThat(sealed).isNotNull();
		assertThat(this.cipher.open(sealed, lan.toString())).isEqualTo(github.lastToken());
		assertThatThrownBy(() -> this.cipher.open(sealed, UUID.randomUUID().toString()))
			.as("sealed with the account ID as associated data")
			.isInstanceOf(IllegalStateException.class);
		assertThat(read(this.browser).path("repositoryTokenKept").asBoolean()).isTrue();
	}

	@Test
	void aProfileLinkKeepsTheTokenOnlyForALiveProductsOwner() throws Exception {
		UUID lan = signIn(this.browser, "google-lan", "lan.nguyen@gmail.test", "Nguyễn Thị Lan");
		link(this.browser, "PROFILE");
		assertThat(keptToken(lan)).isNull();

		liveProduct(lan, "Sổ đặt bàn Quán Mây");
		link(this.browser, "PROFILE");

		assertThat(keptToken(lan)).as("the owner of a live Product keeps it").isNotNull();
		assertThat(this.cipher.open(keptToken(lan), lan.toString())).isEqualTo(github.lastToken());
	}

	@Test
	void anOldOrMissingN24VersionOrAReturnOutsideTheSpaStartsNothing() throws Exception {
		UUID lan = signIn(this.browser, "google-lan", "lan.nguyen@gmail.test", "Nguyễn Thị Lan");

		assertProblem(start(this.browser, "0.9", "PROFILE", null), 409, "STALE", null);
		assertProblem(this.browser.command(post("/api/v1/identity/github/link").contentType(MediaType.APPLICATION_JSON)
			.content("{\"purpose\":\"PROFILE\"}")), 400, "VALIDATION", null);
		assertProblem(start(this.browser, "1.0", "PROFILE", "https://evil.example/"), 400, "VALIDATION", null);
		assertProblem(start(this.browser, "1.0", "PROFILE", "//evil.example/"), 400, "VALIDATION", null);

		assertThat(callback(this.browser, "any-state")).as("no link is pending").isEqualTo("/ho-so/xac-minh?loi=that-bai");
		assertThat(this.jdbc.sql("SELECT count(*) FROM identity.acknowledgment WHERE account_id = :id AND notice = 'LINK_GITHUB'")
			.param("id", lan).query(Long.class).single()).isZero();
		assertThat(githubId(lan)).isNull();
	}

	@Test
	void aWrongExpiredOrReusedStateARefusedCodeOrAnotherSessionWritesNothing() throws Exception {
		UUID lan = signIn(this.browser, "google-lan", "lan.nguyen@gmail.test", "Nguyễn Thị Lan");

		String state = authorization(this.browser, "PROFILE", null).get("state");
		assertThat(callback(this.browser, state + "x")).isEqualTo("/ho-so/xac-minh?loi=that-bai");
		assertThat(callback(this.browser, state)).as("a state is used once, even after a wrong one")
			.isEqualTo("/ho-so/xac-minh?loi=that-bai");

		state = authorization(this.browser, "PROFILE", null).get("state");
		this.clock.advance(Duration.ofMinutes(10));
		assertThat(callback(this.browser, state)).as("10 minutes old").isEqualTo("/ho-so/xac-minh?loi=that-bai");

		state = authorization(this.browser, "PROFILE", null).get("state");
		MvcResult denied = this.browser.perform(get("/link/oauth2/code/github").param("error", "access_denied")
			.param("state", state));
		assertThat(denied.getResponse().getRedirectedUrl()).isEqualTo("/ho-so/xac-minh?loi=that-bai");

		github.refusesCodes(true);
		state = authorization(this.browser, "PROFILE", null).get("state");
		assertThat(callback(this.browser, state)).isEqualTo("/ho-so/xac-minh?loi=that-bai");
		github.refusesCodes(false);

		state = authorization(this.browser, "PROFILE", null).get("state");
		Browser stranger = new Browser(this.mvc);
		assertThat(callback(stranger, state)).as("no session").isEqualTo("/ho-so/xac-minh?loi=that-bai");
		Browser other = new Browser(this.mvc);
		signIn(other, "google-ha", "ha.phuong@gmail.test", "Hà Phương");
		assertThat(callback(other, state)).as("another account's session").isEqualTo("/ho-so/xac-minh?loi=that-bai");
		assertThat(githubId(lan)).isNull();
		assertThat(signals(lan)).isZero();
		assertThat(githubAudits()).doesNotContain("identity.github_linked");

		assertThat(callback(this.browser, state)).as("the right session still finishes")
			.isEqualTo("/ho-so/xac-minh?ket-qua=da-lien-ket");
		assertThat(githubId(lan)).isEqualTo(4242L);
	}

	@Test
	void aGitHubAccountLinkedToAnotherAccountIsRefusedAndNothingIsMerged() throws Exception {
		UUID lan = signIn(this.browser, "google-lan", "lan.nguyen@gmail.test", "Nguyễn Thị Lan");
		link(this.browser, "PROFILE");
		Browser second = new Browser(this.mvc);
		UUID other = signIn(second, "google-lan-2", "lan.work@gmail.test", "Lan Nguyễn");

		String target = callback(second, authorization(second, "PRODUCT", "/san-pham/moi").get("state"));

		assertThat(target).isEqualTo("/ho-so/xac-minh?loi=da-lien-ket-tai-khoan-khac");
		assertThat(githubId(other)).isNull();
		assertThat(githubId(lan)).isEqualTo(4242L);
		assertThat(signals(other)).as("no signal links the two accounts by itself").isZero();
		assertThat(keptToken(other)).isNull();
		assertThat(openTodos(other, "identity.link_github")).isOne();
		assertThat(read(second).path("linked").asBoolean()).isFalse();
	}

	@Test
	void reLinkingTheSameGitHubAccountRefreshesItAndAnotherOneIsRefused() throws Exception {
		UUID lan = signIn(this.browser, "google-lan", "lan.nguyen@gmail.test", "Nguyễn Thị Lan");
		link(this.browser, "PROFILE");
		this.renewals.ask(lan);
		assertThat(read(this.browser).path("relinkNeeded").asBoolean()).as("ACC-2's Cần liên kết lại").isTrue();
		this.clock.advance(Duration.ofDays(30));
		github.signsInAs(4242, "lan-moi");

		assertThat(link(this.browser, "PROFILE")).isEqualTo("/ho-so/xac-minh?ket-qua=da-lien-ket");

		assertThat(this.jdbc.sql("SELECT github_login FROM identity.account WHERE id = :id").param("id", lan)
			.query(String.class).single()).isEqualTo("lan-moi");
		assertThat(linkedAt(lan)).isEqualTo(this.clock.instant());
		assertThat(openTodos(lan, "identity.relink_github")).isZero();
		assertThat(read(this.browser).path("relinkNeeded").asBoolean()).isFalse();
		assertThat(githubAudits()).contains("identity.github_relinked");

		github.signsInAs(5151, "someone-else");
		assertThat(link(this.browser, "PROFILE")).isEqualTo("/ho-so/xac-minh?loi=sai-tai-khoan");
		assertThat(githubId(lan)).isEqualTo(4242L);
		assertThat(this.jdbc.sql("SELECT github_login FROM identity.account WHERE id = :id").param("id", lan)
			.query(String.class).single()).isEqualTo("lan-moi");
	}

	@Test
	void unlinkingIsRefusedWhileALiveProductARoleInARunningJobOrAnOpenBidKeepsTheLink() throws Exception {
		UUID lan = signIn(this.browser, "google-lan", "lan.nguyen@gmail.test", "Nguyễn Thị Lan");
		link(this.browser, "PROFILE");

		UUID product = liveProduct(lan, "Sổ đặt bàn Quán Mây");
		JsonNode blockers = read(this.browser).path("blockers");
		assertThat(blockers.size()).isOne();
		assertThat(blockers.get(0).path("reason").asString()).isEqualTo("LIVE_PRODUCT");
		assertThat(blockers.get(0).path("name").asString()).isEqualTo("Sổ đặt bàn Quán Mây");
		JsonNode refused = assertProblem(this.browser.command(delete("/api/v1/identity/github")), 422, "GUARD_FAILED",
				"GITHUB_LINK_IN_USE");
		assertThat(refused.path("blockers").get(0).path("module").asString()).isEqualTo("product");
		assertThat(refused.path("blockers").get(0).path("subjectId").asString()).isEqualTo(product.toString());

		this.jdbc.sql("UPDATE product.product SET deleted_at = :now WHERE id = :id").param("now", Timestamp.from(this.clock.instant()))
			.param("id", product).update();
		UUID job = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, job_types, created_at, updated_at)
				VALUES (:id, :product, :owner, 'DRAFT', 'Sửa lỗi đặt bàn', ARRAY['ASSESSMENT'], :now, :now)
				""").param("id", job).param("product", product).param("owner", lan)
			.param("now", Timestamp.from(this.clock.instant())).update();
		assertProblem(this.browser.command(delete("/api/v1/identity/github")), 422, "GUARD_FAILED", "GITHUB_LINK_IN_USE");
		assertThat(read(this.browser).path("blockers").get(0).path("reason").asString()).isEqualTo("JOB_ROLE");

		this.jdbc.sql("DELETE FROM job.job WHERE id = :id").param("id", job).update();
		this.jdbc.sql("""
				INSERT INTO matching.application (id, job_id, product_id, customer_account_id, mentor_account_id, status,
				    price_vnd, timeline_days, approach, public_code_notice_version, submitted_at)
				VALUES (:id, :job, :product, :customer, :mentor, 'SUBMITTED', 800000, 7, 'Cách làm', '1.0', :now)
				""").param("id", UUID.randomUUID()).param("job", UUID.randomUUID()).param("product", UUID.randomUUID())
			.param("customer", UUID.randomUUID()).param("mentor", lan).param("now", Timestamp.from(this.clock.instant()))
			.update();
		assertProblem(this.browser.command(delete("/api/v1/identity/github")), 422, "GUARD_FAILED", "GITHUB_LINK_IN_USE");
		assertThat(read(this.browser).path("blockers").get(0).path("reason").asString()).isEqualTo("OPEN_BID");

		assertThat(githubId(lan)).isEqualTo(4242L);
	}

	@Test
	void unlinkingRemovesTheLinkAtOnceDeletesTheTokenRevokesItsGrantAndKeepsTheSignals() throws Exception {
		UUID lan = signIn(this.browser, "google-lan", "lan.nguyen@gmail.test", "Nguyễn Thị Lan");
		link(this.browser, "PRODUCT");
		assertThat(keptToken(lan)).isNotNull();

		MvcResult unlinked = this.browser.command(delete("/api/v1/identity/github"));

		assertThat(unlinked.getResponse().getStatus()).isEqualTo(204);
		Map<String, Object> account = this.jdbc.sql("""
				SELECT github_user_id, github_login, github_avatar_url, github_email, github_linked_at
				FROM identity.account WHERE id = :id
				""").param("id", lan).query().singleRow();
		assertThat(account.values()).containsOnlyNulls();
		assertThat(keptToken(lan)).isNull();
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM operations.external_operation
				WHERE type = 'identity.revoke_oauth_grant' AND account_id = :id AND personal_data IS NOT NULL
				""").param("id", lan).query(Long.class).single()).as("the grant is revoked with the kept token").isOne();
		assertThat(openTodos(lan, "identity.link_github")).isOne();
		assertThat(githubAudits()).contains("identity.github_unlinked");
		assertThat(signals(lan)).as("the signals stay (AD-24)").isEqualTo(2);
		assertThat(read(this.browser).path("linked").asBoolean()).isFalse();
		assertProblem(this.browser.command(delete("/api/v1/identity/github")), 404, "NOT_FOUND", null);

		assertThat(link(this.browser, "PROFILE")).as("the same GitHub account links again")
			.isEqualTo("/ho-so/xac-minh?ket-qua=da-lien-ket");
	}

	@Test
	void aMissingTokenAtAReCheckAsksTheOwnerToReLink() throws Exception {
		UUID lan = signIn(this.browser, "google-lan", "lan.nguyen@gmail.test", "Nguyễn Thị Lan");
		link(this.browser, "PROFILE");
		liveProduct(lan, "Sổ đặt bàn Quán Mây");

		assertThatThrownBy(() -> this.repositories.read(lan, "lan-dev", "quan-may")).isInstanceOf(ApiException.class);

		assertThat(openTodos(lan, "identity.relink_github")).isOne();
		assertThat(this.jdbc.sql("""
				SELECT link FROM notification.notification WHERE type = 'identity.relink_github' AND account_id = :id
				""").param("id", lan).query(String.class).single()).isEqualTo("/ho-so/xac-minh");
		assertThat(read(this.browser).path("relinkNeeded").asBoolean()).isTrue();

		link(this.browser, "PROFILE");
		assertThat(openTodos(lan, "identity.relink_github")).isZero();
		assertThat(keptToken(lan)).isNotNull();
	}

	@Test
	void theCommandsThatCreateWorkAreRefusedWithoutALinkReadOnEveryRequest() throws Exception {
		UUID customer = unlinked(Role.CUSTOMER);
		UUID mentor = unlinked(Role.MENTOR);
		UUID student = unlinked(Role.STUDENT);
		// Story 20.2: a Job-scoped command reads the caller's role in the Job first, so each caller holds one here.
		UUID draft = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, job_types, created_at, updated_at)
				VALUES (:id, :product, :customer, 'DRAFT', 'Sửa lỗi đặt bàn', ARRAY['BUG_FIX'], :now, :now)
				""").param("id", draft).param("product", UUID.randomUUID()).param("customer", customer)
			.param("now", Timestamp.from(this.clock.instant())).update();
		UUID selecting = job("SELECTING", mentor);
		UUID crewed = job("AGREEMENT", UUID.randomUUID());
		this.jdbc.sql("""
				INSERT INTO job.crew_row (id, job_id, member_account_id, status, created_at)
				VALUES (:id, :job, :student, 'ASSIGNED', :now)
				""").param("id", UUID.randomUUID()).param("job", crewed).param("student", student)
			.param("now", Timestamp.from(this.clock.instant())).update();
		this.browser.perform(get("/api/v1/session"));

		assertProblem(as(customer, Role.CUSTOMER, post("/api/v1/products/repository-check"), "product-repository-check.json"), 403,
				"FORBIDDEN", "GITHUB_NOT_LINKED");
		assertProblem(as(customer, Role.CUSTOMER, post("/api/v1/products"), "product-registration.json"), 403, "FORBIDDEN",
				"GITHUB_NOT_LINKED");
		assertProblem(as(customer, Role.CUSTOMER, post("/api/v1/jobs/" + draft + "/publish"), null), 403, "FORBIDDEN",
				"GITHUB_NOT_LINKED");
		assertProblem(as(mentor, Role.MENTOR, post("/api/v1/mentor/job-posts/" + UUID.randomUUID() + "/applications"),
				"application.json"), 403, "FORBIDDEN", "GITHUB_NOT_LINKED");
		assertProblem(as(mentor, Role.MENTOR, post("/api/v1/mentor/jobs/" + selecting + "/nda"), "nda.json"), 403,
				"FORBIDDEN", "GITHUB_NOT_LINKED");
		// Reading "Tìm việc" (X1) needs the link too (Story 20.1): the Job Posts, one Job Post, the capacity, the
		// Recommendations, and MEN-3's Customer Rating and invitation.
		UUID post = UUID.randomUUID();
		String jobPost = "/api/v1/mentor/job-posts/" + post;
		for (String read : List.of("/api/v1/mentor/job-posts", jobPost, "/api/v1/mentor/capacity",
				"/api/v1/mentor/recommendations", jobPost + "/customer-rating", jobPost + "/invitation")) {
			assertProblem(as(mentor, Role.MENTOR, get(read), null), 403, "FORBIDDEN", "GITHUB_NOT_LINKED");
		}
		assertProblem(as(student, Role.STUDENT, post("/api/v1/crew/jobs/" + crewed + "/confirm"), "crew-confirm.json"), 403,
				"FORBIDDEN", "GITHUB_NOT_LINKED");

		this.jdbc.sql("UPDATE identity.account SET github_user_id = 777001, github_login = 'lan-dev' WHERE id = :id")
			.param("id", customer).update();
		MvcResult linked = as(customer, Role.CUSTOMER, post("/api/v1/jobs/" + draft + "/publish"), null);
		assertThat(linked.getResponse().getStatus() + " " + linked.getResponse().getContentAsString())
			.as("past the gate, J1 answers for itself").doesNotContain("GITHUB_NOT_LINKED")
			.doesNotStartWith("403");
		IdentityTestSupport.lock(this.jdbc, customer, "Vi phạm điều khoản");
		assertThat(as(customer, Role.CUSTOMER, post("/api/v1/jobs/" + UUID.randomUUID() + "/publish"), null).getResponse()
			.getStatus()).as("a locked account is signed out").isEqualTo(401);
	}

	@Test
	void aProductLinkThenAProfileLinkWithNoLiveProductDiscardsTheTokenAndRevokesEachOneAlone() throws Exception {
		UUID lan = signIn(this.browser, "google-lan", "lan.nguyen@gmail.test", "Nguyễn Thị Lan");
		link(this.browser, "PRODUCT");
		String first = github.lastToken();
		link(this.browser, "PRODUCT");
		assertThat(revokedTokens(lan)).as("the token a re-link replaced").containsExactly(first);

		link(this.browser, "PROFILE");

		assertThat(keptToken(lan)).as("no live Product needs one").isNull();
		assertThat(revokedTokens(lan)).as("the dropped token and the discarded one, never the grant")
			.containsExactlyInAnyOrder(first, previousToken(), github.lastToken());
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM operations.external_operation
				WHERE type = 'identity.revoke_oauth_grant' AND account_id = :id AND payload ->> 'revoke' IS NULL
				""").param("id", lan).query(Long.class).single()).isZero();
	}

	@Test
	void aMentorOnARunningJobAndAnUnreleasedCrewRowKeepTheLinkAndAReleasedRowOrAClosedJobDoNot() throws Exception {
		UUID lan = signIn(this.browser, "google-lan", "lan.nguyen@gmail.test", "Nguyễn Thị Lan");
		link(this.browser, "PROFILE");
		UUID mentored = job("STARTING", lan);
		assertThat(blockerReasons()).containsExactly("JOB_ROLE");

		this.jdbc.sql("UPDATE job.job SET state = 'CLOSED', closed_at = :now WHERE id = :id")
			.param("now", Timestamp.from(this.clock.instant())).param("id", mentored).update();
		assertThat(blockerReasons()).as("a Closed Job").isEmpty();

		UUID crewed = job("ACTIVE", UUID.randomUUID());
		UUID row = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO job.crew_row (id, job_id, member_account_id, status, created_at)
				VALUES (:id, :job, :student, 'ASSIGNED', :now)
				""").param("id", row).param("job", crewed).param("student", lan)
			.param("now", Timestamp.from(this.clock.instant())).update();
		assertThat(blockerReasons()).as("an unreleased Crew row").containsExactly("JOB_ROLE");

		this.jdbc.sql("""
				UPDATE job.crew_row SET status = 'RELEASED', released_at = :now, released_reason = 'DECLINED' WHERE id = :id
				""").param("now", Timestamp.from(this.clock.instant())).param("id", row).update();
		assertThat(blockerReasons()).as("a released Crew row").isEmpty();
		assertThat(this.browser.command(delete("/api/v1/identity/github")).getResponse().getStatus()).isEqualTo(204);
	}

	@Test
	void aReLinkWhileGitHubDoesNotListTheEmailsSucceedsAndKeepsTheAddressOnFile() throws Exception {
		UUID lan = signIn(this.browser, "google-lan", "lan.nguyen@gmail.test", "Nguyễn Thị Lan");
		link(this.browser, "PROFILE");
		github.signsInAs(4242, "lan-moi");
		github.emailsAnswer(500);

		assertThat(link(this.browser, "PROFILE")).isEqualTo("/ho-so/xac-minh?ket-qua=da-lien-ket");

		assertThat(this.jdbc.sql("SELECT github_login || ' ' || github_email FROM identity.account WHERE id = :id")
			.param("id", lan).query(String.class).single()).isEqualTo("lan-moi lan-dev@example.test");
		assertThat(signals(lan)).as("no new signal without the emails").isEqualTo(2);
	}

	@Test
	void signingInAsAnotherAccountInTheSameSessionForgetsAPendingLink() throws Exception {
		UUID lan = signIn(this.browser, "google-lan", "lan.nguyen@gmail.test", "Nguyễn Thị Lan");
		String state = authorization(this.browser, "PROFILE", null).get("state");
		UUID ha = signIn(this.browser, "google-ha", "ha.phuong@gmail.test", "Hà Phương");

		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM platform.spring_session_attributes WHERE attribute_name = :name
				""").param("name", SignInSession.PENDING_LINK).query(Long.class).single())
			.as("the sign-in clears the earlier account's link").isZero();
		assertThat(callback(this.browser, state)).isEqualTo("/ho-so/xac-minh?loi=that-bai");
		assertThat(githubId(lan)).isNull();
		assertThat(githubId(ha)).isNull();
	}

	@Test
	void anyUserLinksForAProductWhateverRoleTheRowStores() throws Exception {
		this.browser.perform(get("/api/v1/session"));
		for (Role role : List.of(Role.MENTOR, Role.STUDENT, Role.CUSTOMER)) {
			UUID account = unlinked(role);
			this.jdbc.sql("""
					INSERT INTO identity.acknowledgment (id, account_id, notice, notice_version, acknowledged_at)
					VALUES (:ack, :id, 'PRIVACY', '3.1', :now)
					""").param("ack", UUID.randomUUID()).param("id", account)
				.param("now", Timestamp.from(this.clock.instant())).update();
			MvcResult started = as(account, role, post("/api/v1/identity/github/link"), null, "{\"noticeVersion\":\"1.0\","
					+ "\"purpose\":\"PRODUCT\"}");
			// An account holds no role (Story 20.3): anyone may register a Product, so anyone links for one.
			assertThat(started.getResponse().getStatus()).as(role.name()).isEqualTo(200);
		}
		UUID google = signIn(this.browser, "google-lan", "lan.nguyen@gmail.test", "Nguyễn Thị Lan");
		assertThat(start(this.browser, "1.0", "PRODUCT", null).getResponse().getStatus()).as("no role").isEqualTo(200);
		assertThat(githubId(google)).isNull();
	}

	/** A Job of the Customer's, with a Mentor; {@code mentor} may be the account under test. */
	private UUID job(String state, UUID mentor) {
		UUID id = UUID.randomUUID();
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, job_types, rules, selection_days,
				    mentor_account_id, selected_application_id, selected_at, created_at, updated_at)
				VALUES (:id, :product, :customer, :state, 'Sửa lỗi đặt bàn', ARRAY['BUG_FIX'], '{}'::jsonb, 14, :mentor,
				    :application, :now, :now, :now)
				""").param("id", id).param("product", UUID.randomUUID()).param("customer", UUID.randomUUID())
			.param("state", state).param("mentor", mentor).param("application", UUID.randomUUID()).param("now", now).update();
		return id;
	}

	private List<String> blockerReasons() throws Exception {
		List<String> reasons = new java.util.ArrayList<>();
		read(this.browser).path("blockers").forEach(blocker -> reasons.add(blocker.path("reason").asString()));
		return reasons;
	}

	/** The tokens queued for revocation alone, opened from the operations' sealed personal data. */
	private List<String> revokedTokens(UUID account) {
		return this.jdbc.sql("""
				SELECT personal_data ->> 'keyVersion' AS version, personal_data ->> 'nonce' AS nonce,
				       personal_data ->> 'ciphertext' AS ciphertext
				FROM operations.external_operation
				WHERE type = 'identity.revoke_oauth_grant' AND account_id = :id AND payload ->> 'revoke' = 'TOKEN'
				""").param("id", account).query((rs, n) -> this.cipher.open(new TokenCipher.Sealed(
						Integer.parseInt(rs.getString("version")), java.util.Base64.getDecoder().decode(rs.getString("nonce")),
						java.util.Base64.getDecoder().decode(rs.getString("ciphertext"))), account.toString()))
			.list();
	}

	/** The token issued just before the last one. */
	private static String previousToken() {
		String last = github.lastToken();
		return "gho_stub_" + (Integer.parseInt(last.substring("gho_stub_".length())) - 1);
	}

	/** An account with a role and no GitHub link: the shim's, keyed by a Google subject instead. */
	private UUID unlinked(Role role) {
		UUID id = IdentityTestSupport.insertAccount(this.jdbc, role, null);
		this.jdbc.sql("""
				UPDATE identity.account SET google_sub = :sub, github_user_id = NULL, github_login = NULL, github_linked_at = NULL
				WHERE id = :id
				""").param("sub", "google-" + id).param("id", id).update();
		return id;
	}

	private MvcResult as(UUID account, Role role, MockHttpServletRequestBuilder request, @Nullable String body)
			throws Exception {
		return as(account, role, request, body, null);
	}

	/** As the account, with the body file {@code body} or the JSON {@code json}. */
	private MvcResult as(UUID account, Role role, MockHttpServletRequestBuilder request, @Nullable String body,
			@Nullable String json) throws Exception {
		request.with(IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant()));
		if (body != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(body(body));
		}
		else if (json != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(json);
		}
		return this.browser.command(request);
	}

	private static String body(String name) throws IOException {
		try (InputStream in = GitHubLinkIT.class.getResourceAsStream("/authz/bodies/" + name)) {
			assertThat(in).as(name).isNotNull();
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private UUID liveProduct(UUID owner, String name) {
		UUID id = UUID.randomUUID();
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO product.product (id, customer_account_id, name, description, ai_tools, repository_github_id,
				    repository_full_name, repository_owner_type, repository_default_branch, public_notice_version,
				    copy_permission_version, detection_notice_version, acknowledged_at, created_at)
				VALUES (:id, :owner, :name, 'Đặt bàn.', ARRAY['LOVABLE'], :repository, 'lan-dev/quan-may', 'User', 'main',
				    '1.0', '1.0', '1.0', :now, :now)
				""").param("id", id).param("owner", owner).param("name", name)
			.param("repository", ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE)).param("now", now).update();
		return id;
	}

	/** The Google sign-in and the welcome, so the browser holds a signed-in session; returns the account. */
	private UUID signIn(Browser browser, String subject, String email, String name) throws Exception {
		google.signsInAs(subject, email, name);
		MvcResult authorize = browser.perform(get("/oauth2/authorization/google"));
		Map<String, String> query = query(authorize.getResponse().getRedirectedUrl());
		google.nonce(query.get("nonce"));
		MvcResult callback = browser.perform(get("/login/oauth2/code/google").param("code", "c-" + UUID.randomUUID())
			.param("state", query.get("state")));
		assertThat(callback.getResponse().getRedirectedUrl()).isEqualTo("/bat-dau");
		MvcResult welcome = browser.command(post("/api/v1/session/sign-up").contentType(MediaType.APPLICATION_JSON)
			.content("{\"privacyNoticeVersion\":\"3.1\"}"));
		assertThat(welcome.getResponse().getStatus()).isEqualTo(201);
		return this.jdbc.sql("SELECT id FROM identity.account WHERE google_sub = :sub").param("sub", subject)
			.query(UUID.class).single();
	}

	private MvcResult start(Browser browser, String version, String purpose, @Nullable String returnTo) throws Exception {
		Map<String, String> body = new HashMap<>(Map.of("noticeVersion", version, "purpose", purpose));
		if (returnTo != null) {
			body.put("returnTo", returnTo);
		}
		return browser.command(post("/api/v1/identity/github/link").contentType(MediaType.APPLICATION_JSON)
			.content(JSON.writeValueAsString(body)));
	}

	/** The start, then GitHub's authorization page's query, decoded. */
	private Map<String, String> authorization(Browser browser, String purpose, @Nullable String returnTo) throws Exception {
		MvcResult start = start(browser, "1.0", purpose, returnTo);
		assertThat(start.getResponse().getStatus()).as("the start").isEqualTo(200);
		return query(JSON.readTree(start.getResponse().getContentAsString()).path("authorizationUrl").asString());
	}

	/** GitHub returns the browser with a new code; returns where the application sends it. */
	private String callback(Browser browser, String state) throws Exception {
		MvcResult callback = browser.perform(get("/link/oauth2/code/github").param("code", "c-" + UUID.randomUUID())
			.param("state", state));
		assertThat(callback.getResponse().getStatus()).as("callback status").isEqualTo(302);
		return callback.getResponse().getRedirectedUrl();
	}

	private String link(Browser browser, String purpose) throws Exception {
		return callback(browser, authorization(browser, purpose, null).get("state"));
	}

	private JsonNode read(Browser browser) throws Exception {
		MvcResult read = browser.perform(get("/api/v1/identity/github"));
		assertThat(read.getResponse().getStatus()).isEqualTo(200);
		return JSON.readTree(read.getResponse().getContentAsString());
	}

	/** The signed-in account of this browser's session, as the SPA reads it. */
	private JsonNode sessionAccount() throws Exception {
		return JSON.readTree(this.browser.perform(get("/api/v1/session")).getResponse().getContentAsString())
			.path("account");
	}

	private static JsonNode assertProblem(MvcResult result, int status, String code, @Nullable String reason)
			throws Exception {
		assertThat(result.getResponse().getStatus()).as("status").isEqualTo(status);
		JsonNode problem = JSON.readTree(result.getResponse().getContentAsString());
		assertThat(problem.path("code").asString()).isEqualTo(code);
		if (reason != null) {
			assertThat(problem.path("reason").asString()).isEqualTo(reason);
		}
		return problem;
	}

	private @Nullable Long githubId(UUID account) {
		return this.jdbc.sql("SELECT github_user_id FROM identity.account WHERE id = :id").param("id", account)
			.query(Long.class)
			.list()
			.getFirst();
	}

	private TokenCipher.@Nullable Sealed keptToken(UUID account) {
		return this.jdbc.sql("SELECT key_version, nonce, ciphertext FROM identity.customer_token WHERE account_id = :id")
			.param("id", account)
			.query((rs, n) -> new TokenCipher.Sealed(rs.getInt("key_version"), rs.getBytes("nonce"), rs.getBytes("ciphertext")))
			.optional()
			.orElse(null);
	}

	private @Nullable Instant linkedAt(UUID account) {
		Timestamp at = this.jdbc.sql("SELECT github_linked_at FROM identity.account WHERE id = :id").param("id", account)
			.query(Timestamp.class)
			.list()
			.getFirst();
		return at == null ? null : at.toInstant();
	}

	private long signals(UUID account) {
		return this.jdbc.sql("SELECT count(*) FROM identity.person_signal WHERE account_id = :id AND source = 'GITHUB_EMAIL'")
			.param("id", account).query(Long.class).single();
	}

	private long openTodos(UUID account, String key) {
		return this.jdbc.sql("""
				SELECT count(*) FROM notification.todo WHERE todo_key = :key AND account_id = :id AND closed_at IS NULL
				""").param("key", key).param("id", account).query(Long.class).single();
	}

	/** The GitHub link's audit entries, by name: the entries of one test share the clock's instant. */
	private List<String> githubAudits() {
		return this.jdbc.sql("SELECT action FROM audit.audit_entry WHERE action LIKE 'identity.github_%' ORDER BY action")
			.query(String.class)
			.list();
	}

	/** Whether any stored session attribute holds {@code value} (AD-22). */
	private boolean sessionsHold(String value) {
		return this.jdbc.sql("SELECT attribute_bytes FROM platform.spring_session_attributes")
			.query((rs, n) -> new String(rs.getBytes(1), StandardCharsets.ISO_8859_1))
			.list()
			.stream()
			.anyMatch(bytes -> bytes.contains(value));
	}

	private static Map<String, String> query(String url) {
		Map<String, String> query = new HashMap<>();
		UriComponentsBuilder.fromUri(URI.create(url)).build().getQueryParams()
			.forEach((name, values) -> query.put(name, URLDecoder.decode(values.getFirst(), StandardCharsets.UTF_8)));
		return query;
	}

	private static Map<String, String> form(String body) {
		Map<String, String> form = new HashMap<>();
		for (String pair : body.split("&")) {
			String[] nameValue = pair.split("=", 2);
			form.put(URLDecoder.decode(nameValue[0], StandardCharsets.UTF_8),
					nameValue.length > 1 ? URLDecoder.decode(nameValue[1], StandardCharsets.UTF_8) : "");
		}
		return form;
	}

}
