package vn.vibefix.product.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import vn.vibefix.identity.internal.CustomerTokens;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.support.IntegrationTest;

/** FR-7, AD-13, NFR-5: CUS-3's live repository checks and the registration, against a stub GitHub. */
class ProductRegistrationIT extends IntegrationTest {

	private static final String TOKEN = "gho_customer_token_never_printed";

	private static final HttpServer GITHUB = start();

	/** What the stub answers for {@code /repos/{owner}/{name}}, and for its two files. */
	private static final Map<String, String> REPOSITORIES = new ConcurrentHashMap<>();

	private static final Map<String, String> FILES = new ConcurrentHashMap<>();

	private static final Map<String, String> AUTHORIZATIONS = new ConcurrentHashMap<>();

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private ApplicationContext context;

	private UUID lan;

	private long lanGithubId;

	@DynamicPropertySource
	static void stubGitHub(DynamicPropertyRegistry registry) {
		registry.add("vibefix.github.api-url", () -> "http://127.0.0.1:" + GITHUB.getAddress().getPort());
	}

	@BeforeEach
	void aCustomerWithAKeptToken() {
		REPOSITORIES.clear();
		FILES.clear();
		AUTHORIZATIONS.clear();
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		this.lanGithubId = CustomerTokens.githubUserId(this.jdbc, this.lan);
		CustomerTokens.keep(this.context, this.lan, TOKEN);
	}

	@AfterEach
	void forget() {
		REPOSITORIES.clear();
	}

	@Test
	void aUserOwnedRepositoryOfTheCustomerPassesAndAForkShowsItsParent() throws Exception {
		repository("lan-nguyen/shop-gom", 4001, this.lanGithubId, "User", false, 38_000, null,
				"\"fork\":true,\"parent\":{\"full_name\":\"lovable-templates/shop-starter\"}");

		check("https://github.com/lan-nguyen/shop-gom.git").andExpect(status().isOk())
			.andExpect(jsonPath("$.githubId").value(4001))
			.andExpect(jsonPath("$.fullName").value("lan-nguyen/shop-gom"))
			.andExpect(jsonPath("$.forkParent").value("lovable-templates/shop-starter"))
			.andExpect(jsonPath("$.sizeKb").value(38_000));
		assertThat(AUTHORIZATIONS.get("/repos/lan-nguyen/shop-gom")).as("read with the Customer's own token")
			.isEqualTo("Bearer " + TOKEN);
	}

	@Test
	void anOrganizationRepositoryPassesOnItsAdminPermissionAsS1Decided() throws Exception {
		repository("cong-ty/app", 4002, 99, "Organization", false, 1_000, true, "");
		repository("cong-ty/other", 4003, 99, "Organization", false, 1_000, false, "");

		check("github.com/cong-ty/app").andExpect(status().isOk());
		check("https://github.com/cong-ty/other").andExpect(status().is(422))
			.andExpect(jsonPath("$.reason").value("NO_ADMIN_PERMISSION"))
			.andExpect(jsonPath("$.repository").value("cong-ty/other"));
	}

	@Test
	void eachCheckRefusesInOrderWithItsReason() throws Exception {
		check("https://gitlab.com/lan/shop").andExpect(status().is(422)).andExpect(jsonPath("$.reason").value("NOT_GITHUB"));
		check("https://github.com/lan").andExpect(jsonPath("$.reason").value("NOT_GITHUB"));
		check("https://github.com/lan-nguyen/missing").andExpect(jsonPath("$.reason").value("REPO_NOT_FOUND"));
		repository("lan-nguyen/secret", 4004, this.lanGithubId, "User", true, 10, true, "");
		check("https://github.com/lan-nguyen/secret").andExpect(jsonPath("$.reason").value("REPO_PRIVATE"));
		repository("someone/else", 4005, 12345, "User", false, 10, false, "");
		check("https://github.com/someone/else").andExpect(jsonPath("$.reason").value("NO_ADMIN_PERMISSION"));
		repository("lan-nguyen/lfs", 4006, this.lanGithubId, "User", false, 10, null, "");
		FILES.put("/repos/lan-nguyen/lfs/contents/.gitattributes", "*.psd filter=lfs diff=lfs merge=lfs -text\n");
		check("https://github.com/lan-nguyen/lfs").andExpect(jsonPath("$.reason").value("REPO_UNSUPPORTED_CONTENT"));
		repository("lan-nguyen/sub", 4007, this.lanGithubId, "User", false, 10, null, "");
		FILES.put("/repos/lan-nguyen/sub/contents/.gitmodules", "[submodule \"lib\"]\n");
		check("https://github.com/lan-nguyen/sub").andExpect(jsonPath("$.reason").value("REPO_UNSUPPORTED_CONTENT"));
		repository("lan-nguyen/big", 4008, this.lanGithubId, "User", false, 300_000, null, "");
		check("https://github.com/lan-nguyen/big").andExpect(jsonPath("$.reason").value("REPO_TOO_LARGE"));
	}

	@Test
	void registrationRecordsTheProductItsAcknowledgmentsAndAnAudit() throws Exception {
		repository("lan-nguyen/shop-gom", 4001, this.lanGithubId, "User", false, 38_000, null, "");

		String body = register("https://github.com/lan-nguyen/shop-gom", "1.0").andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		UUID id = UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.id"));

		assertThat(this.jdbc.sql("""
				SELECT repository_github_id = 4001 AND public_notice_version = '1.0' AND copy_permission_version = '1.0'
				       AND detection_notice_version = '1.0' AND ai_tools = ARRAY['LOVABLE']
				FROM product.product WHERE id = :id
				""").param("id", id).query(Boolean.class).single()).isTrue();
		assertThat(this.jdbc.sql("SELECT count(*) FROM audit.audit_entry WHERE action = 'product.registered'")
			.query(Long.class).single()).isOne();
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM scanning.scan_run r JOIN operations.external_operation o
				    ON o.operation_key = 'scanning.scan:' || r.id
				WHERE r.product_id = :id AND r.status = 'PENDING' AND r.purpose = 'INTAKE'
				    AND r.repository_full_name = 'lan-nguyen/shop-gom' AND o.lane = 'SCAN'
				""").param("id", id).query(Long.class).single()).as("the intake scan, requested with the Product").isOne();
		this.mvc.perform(get("/api/v1/products").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$[0].name").value("Shop Gốm Bát Tràng"))
			.andExpect(jsonPath("$[0].scanState").value("SCANNING"))
			.andExpect(jsonPath("$[0].scan.status").value("PENDING"))
			.andExpect(jsonPath("$[0].repositoryUrl").value("https://github.com/lan-nguyen/shop-gom"));
		this.mvc.perform(get("/api/v1/products/" + id).with(as(this.lan, Role.CUSTOMER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.tools[0]").value("LOVABLE"));

		UUID other = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		this.mvc.perform(get("/api/v1/products/" + id).with(as(other, Role.CUSTOMER))).andExpect(status().isNotFound());
	}

	@Test
	void aRepositoryLinkedAlreadyIsRefusedEvenUnderANewName() throws Exception {
		repository("lan-nguyen/shop-gom", 4001, this.lanGithubId, "User", false, 38_000, null, "");
		register("https://github.com/lan-nguyen/shop-gom", "1.0").andExpect(status().isCreated());
		REPOSITORIES.remove("/repos/lan-nguyen/shop-gom");
		repository("lan-nguyen/gom-shop", 4001, this.lanGithubId, "User", false, 38_000, null, "");

		check("https://github.com/lan-nguyen/gom-shop").andExpect(status().is(422))
			.andExpect(jsonPath("$.reason").value("REPO_ALREADY_LINKED"));
		register("https://github.com/lan-nguyen/gom-shop", "1.0").andExpect(jsonPath("$.reason").value("REPO_ALREADY_LINKED"));
	}

	@Test
	void everyAcknowledgmentOfTheCurrentVersionIsNeeded() throws Exception {
		repository("lan-nguyen/shop-gom", 4001, this.lanGithubId, "User", false, 38_000, null, "");

		register("https://github.com/lan-nguyen/shop-gom", null).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reason").value("NOTICE_REQUIRED"));
		register("https://github.com/lan-nguyen/shop-gom", "0.9").andExpect(jsonPath("$.reason").value("NOTICE_REQUIRED"));
		assertThat(this.jdbc.sql("SELECT count(*) FROM product.product").query(Long.class).single()).isZero();
		assertThat(this.jdbc.sql("SELECT count(*) FROM scanning.scan_run").query(Long.class).single()).isZero();
	}

	@Test
	void aCustomerRegistersAtMostFiveProductsADay() throws Exception {
		for (int i = 1; i <= 6; i++) {
			repository("lan-nguyen/app-" + i, 5000 + i, this.lanGithubId, "User", false, 10, null, "");
		}
		for (int i = 1; i <= 5; i++) {
			register("https://github.com/lan-nguyen/app-" + i, "1.0").andExpect(status().isCreated());
		}
		register("https://github.com/lan-nguyen/app-6", "1.0").andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.reason").value("PRODUCT_LIMIT"));

		this.clock.advance(Duration.ofDays(1));
		register("https://github.com/lan-nguyen/app-6", "1.0").andExpect(status().isCreated());
	}

	@Test
	void aUserWithNoRoleAndGitHubLinkedRegistersAndOneWithoutGitHubIsRefused() throws Exception {
		// Story 20.3: no account role gates the Products; the GitHub link does (FR-80).
		UUID minh = IdentityTestSupport.insertGoogleAccount(this.jdbc, "google-minh", "3.1");
		IdentityTestSupport.linkGitHub(this.jdbc, minh);
		CustomerTokens.keep(this.context, minh, TOKEN);
		repository("minh-tran/quan-ca-phe", 4101, CustomerTokens.githubUserId(this.jdbc, minh), "User", false, 1_000, null,
				"");
		check(as(minh, null), "https://github.com/minh-tran/quan-ca-phe").andExpect(status().isOk());
		register(as(minh, null), "https://github.com/minh-tran/quan-ca-phe", "1.0").andExpect(status().isCreated());
		this.mvc.perform(get("/api/v1/products").with(as(minh, null))).andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].repositoryUrl").value("https://github.com/minh-tran/quan-ca-phe"));
		this.mvc.perform(get("/api/v1/products").with(as(this.lan, Role.CUSTOMER))).andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(0));

		UUID unlinked = IdentityTestSupport.insertGoogleAccount(this.jdbc, "google-chua-lien-ket", "3.1");
		check(as(unlinked, null), "https://github.com/minh-tran/quan-ca-phe").andExpect(status().isForbidden())
			.andExpect(jsonPath("$.reason").value("GITHUB_NOT_LINKED"));
		register(as(unlinked, null), "https://github.com/minh-tran/quan-ca-phe", "1.0").andExpect(status().isForbidden())
			.andExpect(jsonPath("$.reason").value("GITHUB_NOT_LINKED"));
		this.mvc.perform(get("/api/v1/products").with(as(unlinked, null))).andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(0));
	}

	private ResultActions check(String url) throws Exception {
		return check(as(this.lan, Role.CUSTOMER), url);
	}

	private ResultActions check(RequestPostProcessor caller, String url) throws Exception {
		return this.mvc.perform(post("/api/v1/products/repository-check").with(caller).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"url\":\"" + url + "\"}"));
	}

	private ResultActions register(String url, String version) throws Exception {
		return register(as(this.lan, Role.CUSTOMER), url, version);
	}

	private ResultActions register(RequestPostProcessor caller, String url, String version) throws Exception {
		String notice = version == null ? "null" : "\"" + version + "\"";
		return this.mvc.perform(post("/api/v1/products").with(caller).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"repositoryUrl":"%s","tools":["LOVABLE"],"name":"Shop Gốm Bát Tràng",
					 "description":"Cửa hàng online bán gốm.","demoUrl":"https://shop-gom.lovable.app",
					 "publicNoticeVersion":%s,"copyPermissionVersion":%s,"detectionNoticeVersion":%s}
					""".formatted(url, notice, notice, notice)));
	}

	private static void repository(String fullName, long id, long ownerId, String ownerType, boolean isPrivate, long sizeKb,
			Boolean admin, String extra) {
		REPOSITORIES.put("/repos/" + fullName, """
				{"id":%d,"full_name":"%s","html_url":"https://github.com/%s","private":%s,"visibility":"%s",
				 "size":%d,"default_branch":"main","owner":{"id":%d,"type":"%s"}%s%s}
				""".formatted(id, fullName, fullName, isPrivate, isPrivate ? "private" : "public", sizeKb, ownerId, ownerType,
				admin == null ? "" : ",\"permissions\":{\"admin\":" + admin + "}", extra.isEmpty() ? "" : "," + extra));
	}

	private RequestPostProcessor as(UUID account, @Nullable Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

	private static HttpServer start() {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/repos/", ProductRegistrationIT::answer);
			server.start();
			return server;
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static void answer(HttpExchange exchange) throws IOException {
		String path = exchange.getRequestURI().getPath();
		AUTHORIZATIONS.put(path, String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
		String body;
		int status;
		if (path.contains("/contents/")) {
			String text = FILES.get(path);
			status = text == null ? 404 : 200;
			body = text == null ? "{\"message\":\"Not Found\"}"
					: "{\"content\":\"" + Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)) + "\"}";
		}
		else {
			String repository = REPOSITORIES.get(path);
			status = repository == null ? 404 : 200;
			body = repository == null ? "{\"message\":\"Not Found\"}" : repository;
		}
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

}
