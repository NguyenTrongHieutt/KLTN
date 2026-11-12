package vn.vibefix.product.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.jayway.jsonpath.JsonPath;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.github.internal.GitHubStub;
import vn.vibefix.github.internal.GitHubStub.MergeInstallation;
import vn.vibefix.github.internal.GitHubStub.Seen;
import vn.vibefix.github.internal.TestKeys;
import vn.vibefix.identity.internal.CustomerTokens;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.product.ProductSources;
import vn.vibefix.shared.PersonalDataSection;
import vn.vibefix.support.IntegrationTest;

/**
 * FR-7, AD-10, AD-13, AD-45, NFR-5, Story 23.5: CUS-3's Source App install link and its Setup URL return, the live
 * checks through the Source App in their order, the published workflow and its check, the registration, and the
 * binding that ends with the Product, against {@link GitHubStub}. No call reads a repository with the Customer's
 * linking token: none is kept here.
 */
class ProductRegistrationIT extends IntegrationTest {

	private static final Path DIRECTORY;

	private static final GitHubStub GITHUB;

	private static final String WORKFLOW = ".github/workflows/vibefix.yml";

	/** {@code GET /repos/{owner}/{name}}'s answer for each repository a test describes. */
	private static final Map<String, String> REPOSITORIES = new ConcurrentHashMap<>();

	private static final Pattern REPOSITORY = Pattern.compile("/repos/([^/]+)/([^/]+)(?:/languages)?");

	static {
		try {
			DIRECTORY = Files.createTempDirectory("product-registration-");
			GITHUB = new GitHubStub(Files.createDirectories(DIRECTORY.resolve("git")));
			Files.writeString(DIRECTORY.resolve("app-key"), TestKeys.pkcs1());
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private ProductSources sources;

	@Autowired
	private ProductPersonalData personalData;

	@Autowired
	private PlatformTransactionManager transactionManager;

	private UUID lan;

	private long lanGithubId;

	private String lanLogin;

	@DynamicPropertySource
	static void stubGitHub(DynamicPropertyRegistry registry) {
		registry.add("vibefix.github.api-url", () -> GITHUB.apiUrl().toString());
		registry.add("vibefix.github.git-url", () -> "https://github.com");
		registry.add("vibefix.github.source-app.id", () -> "2222");
		registry.add("vibefix.github.source-app.key-file", () -> DIRECTORY.resolve("app-key").toString());
	}

	@AfterAll
	static void stop() {
		GITHUB.close();
	}

	@BeforeEach
	void aCustomerWithGitHubLinked() {
		REPOSITORIES.clear();
		GITHUB.mergeInstallations.clear();
		GITHUB.repositoryInstallations.clear();
		GITHUB.sourcePermissions.clear();
		GITHUB.sourceFiles.clear();
		GITHUB.unavailable.clear();
		GITHUB.seen.clear();
		GITHUB.extra = (exchange, request, query) -> {
			Matcher repository = REPOSITORY.matcher(request.path());
			String body = request.method().equals("GET") && repository.matches() ? REPOSITORIES.get(request.path()) : null;
			if (body == null) {
				return false;
			}
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, bytes.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
			return true;
		};
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		this.lanGithubId = CustomerTokens.githubUserId(this.jdbc, this.lan);
		this.lanLogin = GITHUB.loginOf(this.lanGithubId);
	}

	@Test
	void aPrivateRepositoryWithTheSourceAppAndTheWorkflowRegistersAndTheBindingFollowsTheProduct() throws Exception {
		long installation = ready("lan-nguyen/shop-gom", 4001, this.lanGithubId, "User", true, 38_000,
				"\"fork\":true,\"parent\":{\"full_name\":\"lovable-templates/shop-starter\"}");

		check("https://github.com/lan-nguyen/shop-gom.git").andExpect(status().isOk())
			.andExpect(jsonPath("$.githubId").value(4001))
			.andExpect(jsonPath("$.fullName").value("lan-nguyen/shop-gom"))
			.andExpect(jsonPath("$.forkParent").value("lovable-templates/shop-starter"))
			.andExpect(jsonPath("$.sizeKb").value(38_000));
		assertThat(binding("lan-nguyen/shop-gom")).as("the check binds the draft").isEqualTo("BOUND:" + installation + ":-");
		workflowCheck("https://github.com/lan-nguyen/shop-gom").andExpect(status().isOk())
			.andExpect(jsonPath("$.defaultBranch").value("main"));

		String body = register("https://github.com/lan-nguyen/shop-gom", "2.0").andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		UUID id = UUID.fromString(JsonPath.read(body, "$.id"));

		assertThat(this.jdbc.sql("""
				SELECT repository_github_id = 4001 AND public_notice_version = '2.0' AND copy_permission_version = '2.0'
				       AND detection_notice_version = '2.0' AND ai_tools = ARRAY['LOVABLE']
				FROM product.product WHERE id = :id
				""").param("id", id).query(Boolean.class).single()).isTrue();
		assertThat(binding("lan-nguyen/shop-gom")).as("the binding is the Product's").isEqualTo("BOUND:" + installation + ":" + id);
		assertThat(this.jdbc.sql("SELECT count(*) FROM audit.audit_entry WHERE action = 'product.registered'")
			.query(Long.class).single()).isOne();
		assertThat(this.jdbc.sql("SELECT count(*) FROM operations.external_operation").query(Long.class).single())
			.as("no scan is requested with the Product").isZero();
		// Every Source App token but the selection read's is narrowed to the one repository, and each is revoked.
		List<String> tokens = GITHUB.tokensOf(installation);
		assertThat(tokens).isNotEmpty();
		assertThat(GITHUB.revokedTokens).containsAll(tokens);
		assertThat(tokens).allSatisfy(token -> {
			String narrowed = GITHUB.grantOf(token).path("repository_ids").toString();
			assertThat(narrowed.isEmpty() || narrowed.equals("[4001]")).as(narrowed).isTrue();
		});
		assertThat(GITHUB.seen).as("the admin check reads the collaborator permission of the linked account's login")
			.anyMatch(request -> request.path().equals("/repos/lan-nguyen/shop-gom/collaborators/" + this.lanLogin + "/permission"));
		this.mvc.perform(get("/api/v1/products").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$[0].name").value("Shop Gốm Bát Tràng"))
			.andExpect(jsonPath("$[0].scanState").doesNotExist())
			.andExpect(jsonPath("$[0].repositoryUrl").value("https://github.com/lan-nguyen/shop-gom"));
		this.mvc.perform(get("/api/v1/products/" + id).with(as(this.lan, Role.CUSTOMER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.tools[0]").value("LOVABLE"))
			.andExpect(jsonPath("$.sourceApp.accountLogin").value("lan-nguyen"))
			.andExpect(jsonPath("$.sourceApp.settingsUrl").value("https://github.com/settings/installations/" + installation))
			.andExpect(jsonPath("$.sourceApp.otherProducts").value(0));

		UUID other = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		this.mvc.perform(get("/api/v1/products/" + id).with(as(other, Role.CUSTOMER))).andExpect(status().isNotFound());

		this.mvc.perform(delete("/api/v1/products/" + id).with(as(this.lan, Role.CUSTOMER)).with(csrf()))
			.andExpect(status().isNoContent());
		assertThat(this.jdbc.sql("SELECT count(*) FROM product.source_app_binding WHERE product_id = :id").param("id", id)
			.query(Long.class).single()).as("the binding ends with the Product").isZero();
		assertThat(GITHUB.removedInstallations).as("vibefix never uninstalls the Customer's App").isEmpty();
	}

	@Test
	void aSecondProductOnTheSameInstallationIsAcceptedAndAnOrganizationRepositoryPassesOnAdmin() throws Exception {
		long installation = ready("cong-ty/app", 4002, 99, "Organization", false, 1_000, "");
		GITHUB.mergeInstallations.get(installation).accountType = "Organization";
		register("https://github.com/cong-ty/app", "2.0").andExpect(status().isCreated());

		repository("cong-ty/app-2", 4003, 99, "Organization", false, 1_000, "");
		GITHUB.mergeInstallations.get(installation).repository(4003, "cong-ty/app-2", 99);
		GITHUB.repositoryInstallations.put("cong-ty/app-2", installation);
		GITHUB.sourcePermissions.put("cong-ty/app-2", Map.of(this.lanLogin, "admin"));
		GITHUB.sourceFiles.put("cong-ty/app-2:" + WORKFLOW, published());

		check("https://github.com/cong-ty/app-2").andExpect(status().isOk());
		String body = register("https://github.com/cong-ty/app-2", "2.0").andExpect(status().isCreated()).andReturn()
			.getResponse().getContentAsString(StandardCharsets.UTF_8);
		this.mvc.perform(get("/api/v1/products/" + JsonPath.read(body, "$.id")).with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.sourceApp.accountType").value("Organization"))
			.andExpect(jsonPath("$.sourceApp.settingsUrl")
				.value("https://github.com/organizations/cong-ty/settings/installations/" + installation))
			.andExpect(jsonPath("$.sourceApp.otherProducts").value(1));
	}

	@Test
	void theInstallLinkCarriesASingleUseStateOnlyItsCustomerConsumesAndTheReturnBindsNothing() throws Exception {
		repository("lan-nguyen/shop-gom", 4001, this.lanGithubId, "User", false, 10, "");

		check("https://github.com/lan-nguyen/shop-gom").andExpect(status().is(422))
			.andExpect(jsonPath("$.reason").value("SOURCE_APP_MISSING"))
			.andExpect(jsonPath("$.repository").value("lan-nguyen/shop-gom"));
		String state = state(installLink("https://github.com/lan-nguyen/shop-gom"));
		String again = state(installLink("https://github.com/lan-nguyen/shop-gom"));
		assertThat(binding("lan-nguyen/shop-gom")).isEqualTo("PENDING:-:-");

		// The earlier link's state no longer counts; the newer one counts once.
		setup(this.lan, state, "1", "install").andExpect(header().string("Location", "/san-pham/moi"));
		setup(this.lan, again, "1", "install").andExpect(header().string("Location", "/san-pham/moi?ung-dung=da-cai"));
		setup(this.lan, again, "1", "install").andExpect(header().string("Location", "/san-pham/moi"));
		assertThat(binding("lan-nguyen/shop-gom")).as("the return binds nothing").isEqualTo("PENDING:-:-");

		UUID other = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		String request = state(installLink("https://github.com/lan-nguyen/shop-gom"));
		setup(other, request, null, "request").andExpect(header().string("Location", "/san-pham/moi"));
		setup(this.lan, request, null, "request").andExpect(header().string("Location", "/san-pham/moi?ung-dung=cho-duyet"));
		String nothing = state(installLink("https://github.com/lan-nguyen/shop-gom"));
		setup(this.lan, nothing, null, null).andExpect(header().string("Location", "/san-pham/moi?ung-dung=chua-thay"));
		String expired = state(installLink("https://github.com/lan-nguyen/shop-gom"));
		this.clock.advance(Duration.ofMinutes(61));
		setup(this.lan, expired, "1", "install").andExpect(header().string("Location", "/san-pham/moi"));

		// An organization owner approves the install later: the next "Kiểm tra" binds it, with no state.
		long installation = GITHUB.installSource("lan-nguyen", "shop-gom", 4001, this.lanGithubId);
		GITHUB.sourcePermissions.put("lan-nguyen/shop-gom", Map.of(this.lanLogin, "admin"));
		check("https://github.com/lan-nguyen/shop-gom").andExpect(status().isOk());
		assertThat(binding("lan-nguyen/shop-gom")).isEqualTo("BOUND:" + installation + ":-");
	}

	@Test
	void aWrongSelectionASuspensionOrAPermissionNotAcceptedIsRefused() throws Exception {
		long installation = ready("lan-nguyen/shop-gom", 4001, this.lanGithubId, "User", false, 10, "");
		MergeInstallation installed = GITHUB.mergeInstallations.get(installation);

		installed.all = true;
		check("https://github.com/lan-nguyen/shop-gom").andExpect(jsonPath("$.reason").value("SOURCE_APP_WRONG_REPOSITORY"));
		installed.all = false;
		// Another selected repository that is no live Product's.
		installed.repository(5555, "lan-nguyen/other", this.lanGithubId);
		check("https://github.com/lan-nguyen/shop-gom").andExpect(jsonPath("$.reason").value("SOURCE_APP_WRONG_REPOSITORY"));
		// The repository itself not selected.
		installed.deselect(5555).deselect(4001);
		check("https://github.com/lan-nguyen/shop-gom").andExpect(jsonPath("$.reason").value("SOURCE_APP_WRONG_REPOSITORY"));
		installed.repository(4001, "lan-nguyen/shop-gom", this.lanGithubId);
		installed.suspended = true;
		check("https://github.com/lan-nguyen/shop-gom").andExpect(jsonPath("$.reason").value("SOURCE_APP_MISSING"));
		installed.suspended = false;
		installed.permissions.put("actions", "read");
		check("https://github.com/lan-nguyen/shop-gom").andExpect(jsonPath("$.reason").value("SOURCE_APP_MISSING"));
		assertThat(this.jdbc.sql("SELECT count(*) FROM product.source_app_binding WHERE status = 'BOUND'")
			.query(Long.class).single()).as("nothing was bound").isZero();

		installed.permissions.put("actions", "write");
		check("https://github.com/lan-nguyen/shop-gom").andExpect(status().isOk());
	}

	@Test
	void eachCheckRefusesInOrderWithItsReason() throws Exception {
		check("https://gitlab.com/lan/shop").andExpect(status().is(422)).andExpect(jsonPath("$.reason").value("NOT_GITHUB"));
		check("https://github.com/lan").andExpect(jsonPath("$.reason").value("NOT_GITHUB"));
		check("https://github.com/lan-nguyen/missing").andExpect(jsonPath("$.reason").value("SOURCE_APP_MISSING"));
		// The Source App's token reads no such repository.
		long installation = GITHUB.installSource("lan-nguyen", "gone", 4009, this.lanGithubId);
		check("https://github.com/lan-nguyen/gone").andExpect(jsonPath("$.reason").value("REPO_NOT_FOUND"));
		assertThat(GITHUB.revokedTokens).containsAll(GITHUB.tokensOf(installation));
		ready("someone/else", 4005, 12345, "User", false, 10, "");
		GITHUB.sourcePermissions.put("someone/else", Map.of(this.lanLogin, "write"));
		check("https://github.com/someone/else").andExpect(jsonPath("$.reason").value("NO_ADMIN_PERMISSION"))
			.andExpect(jsonPath("$.repository").value("someone/else"));
		ready("lan-nguyen/lfs", 4006, this.lanGithubId, "User", false, 10, "");
		GITHUB.sourceFiles.put("lan-nguyen/lfs:.gitattributes", "*.psd filter=lfs diff=lfs merge=lfs -text\n");
		check("https://github.com/lan-nguyen/lfs").andExpect(jsonPath("$.reason").value("REPO_UNSUPPORTED_CONTENT"));
		ready("lan-nguyen/sub", 4007, this.lanGithubId, "User", false, 10, "");
		GITHUB.sourceFiles.put("lan-nguyen/sub:.gitmodules", "[submodule \"lib\"]\n");
		check("https://github.com/lan-nguyen/sub").andExpect(jsonPath("$.reason").value("REPO_UNSUPPORTED_CONTENT"));
		ready("lan-nguyen/big", 4008, this.lanGithubId, "User", false, 300_000, "");
		check("https://github.com/lan-nguyen/big").andExpect(jsonPath("$.reason").value("REPO_TOO_LARGE"));
	}

	@Test
	void theWorkflowCheckMatchesTheAcceptedSetWithCrlfReadAsLfAndWarnsAboutLineEndingsOnAMismatch() throws Exception {
		ready("lan-nguyen/shop-gom", 4001, this.lanGithubId, "User", false, 10, "");
		String url = "https://github.com/lan-nguyen/shop-gom";
		String published = published();

		GITHUB.sourceFiles.remove("lan-nguyen/shop-gom:" + WORKFLOW);
		workflowCheck(url).andExpect(status().is(422)).andExpect(jsonPath("$.reason").value("WORKFLOW_MISSING"));
		register(url, "2.0").andExpect(jsonPath("$.reason").value("WORKFLOW_MISSING"));
		GITHUB.sourceFiles.put("lan-nguyen/shop-gom:" + WORKFLOW, published + "# changed\n");
		workflowCheck(url).andExpect(jsonPath("$.reason").value("WORKFLOW_MISMATCH"))
			.andExpect(jsonPath("$.lineEndings").doesNotExist());
		GITHUB.sourceFiles.put("lan-nguyen/shop-gom:" + WORKFLOW, (published + "# changed\n").replace("\n", "\r\n"));
		workflowCheck(url).andExpect(jsonPath("$.reason").value("WORKFLOW_MISMATCH"))
			.andExpect(jsonPath("$.lineEndings").value(true));
		// Only the line endings differ: AD-45 reads CRLF as LF.
		GITHUB.sourceFiles.put("lan-nguyen/shop-gom:" + WORKFLOW, published.replace("\n", "\r\n"));
		workflowCheck(url).andExpect(status().isOk());
		assertThat(this.jdbc.sql("SELECT count(*) FROM product.product").query(Long.class).single()).isZero();
	}

	@Test
	void thePublishedWorkflowIsServedAsItsPathContentAndBlob() throws Exception {
		this.mvc.perform(get("/api/v1/products/workflow-file").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.path").value(WORKFLOW))
			.andExpect(jsonPath("$.content").value(published()))
			.andExpect(jsonPath("$.blob").value(blob(published())));
	}

	@Test
	void gitHubDownAnswersUnavailableAndBindsNothing() throws Exception {
		ready("lan-nguyen/shop-gom", 4001, this.lanGithubId, "User", false, 10, "");
		GITHUB.unavailable.add("GET /installation/repositories");

		check("https://github.com/lan-nguyen/shop-gom").andExpect(status().is(422))
			.andExpect(jsonPath("$.reason").value("GITHUB_UNAVAILABLE"));
		assertThat(this.jdbc.sql("SELECT count(*) FROM product.source_app_binding").query(Long.class).single()).isZero();
	}

	@Test
	void aRepositoryLinkedAlreadyIsRefusedEvenUnderANewName() throws Exception {
		long installation = ready("lan-nguyen/shop-gom", 4001, this.lanGithubId, "User", false, 10, "");
		register("https://github.com/lan-nguyen/shop-gom", "2.0").andExpect(status().isCreated());
		REPOSITORIES.remove("/repos/lan-nguyen/shop-gom");
		repository("lan-nguyen/gom-shop", 4001, this.lanGithubId, "User", false, 10, "");
		GITHUB.mergeInstallations.get(installation).deselect(4001).repository(4001, "lan-nguyen/gom-shop", this.lanGithubId);
		GITHUB.repositoryInstallations.put("lan-nguyen/gom-shop", installation);
		GITHUB.sourcePermissions.put("lan-nguyen/gom-shop", Map.of(this.lanLogin, "admin"));

		check("https://github.com/lan-nguyen/gom-shop").andExpect(status().is(422))
			.andExpect(jsonPath("$.reason").value("REPO_ALREADY_LINKED"));
		register("https://github.com/lan-nguyen/gom-shop", "2.0").andExpect(jsonPath("$.reason").value("REPO_ALREADY_LINKED"));
	}

	@Test
	void everyAcknowledgmentOfTheCurrentVersionIsNeeded() throws Exception {
		ready("lan-nguyen/shop-gom", 4001, this.lanGithubId, "User", false, 10, "");

		register("https://github.com/lan-nguyen/shop-gom", null).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reason").value("NOTICE_REQUIRED"));
		// 1.1 spoke of public repositories (Story 23.5): acknowledging it no longer registers.
		register("https://github.com/lan-nguyen/shop-gom", "1.1").andExpect(jsonPath("$.reason").value("NOTICE_REQUIRED"));
		assertThat(this.jdbc.sql("SELECT count(*) FROM product.product").query(Long.class).single()).isZero();
	}

	@Test
	void aCustomerRegistersAtMostFiveProductsADay() throws Exception {
		for (int i = 1; i <= 6; i++) {
			ready("lan-nguyen/app-" + i, 5000 + i, this.lanGithubId, "User", false, 10, "");
		}
		for (int i = 1; i <= 5; i++) {
			register("https://github.com/lan-nguyen/app-" + i, "2.0").andExpect(status().isCreated());
		}
		register("https://github.com/lan-nguyen/app-6", "2.0").andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.reason").value("PRODUCT_LIMIT"));

		this.clock.advance(Duration.ofDays(1));
		register("https://github.com/lan-nguyen/app-6", "2.0").andExpect(status().isCreated());
	}

	@Test
	void aUserWithNoRoleAndGitHubLinkedRegistersAndOneWithoutGitHubIsRefused() throws Exception {
		// Story 20.3: no account role gates the Products; the GitHub link does (FR-80).
		UUID minh = IdentityTestSupport.insertGoogleAccount(this.jdbc, "google-minh", "3.1");
		IdentityTestSupport.linkGitHub(this.jdbc, minh);
		long minhId = CustomerTokens.githubUserId(this.jdbc, minh);
		ready("minh-tran/quan-ca-phe", 4101, minhId, "User", false, 1_000, "");
		GITHUB.sourcePermissions.put("minh-tran/quan-ca-phe", Map.of(GITHUB.loginOf(minhId), "admin"));
		check(as(minh, null), "https://github.com/minh-tran/quan-ca-phe").andExpect(status().isOk());
		register(as(minh, null), "https://github.com/minh-tran/quan-ca-phe", "2.0").andExpect(status().isCreated());
		this.mvc.perform(get("/api/v1/products").with(as(minh, null))).andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1));
		this.mvc.perform(get("/api/v1/products").with(as(this.lan, Role.CUSTOMER))).andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(0));

		UUID unlinked = IdentityTestSupport.insertGoogleAccount(this.jdbc, "google-chua-lien-ket", "3.1");
		check(as(unlinked, null), "https://github.com/minh-tran/quan-ca-phe").andExpect(status().isForbidden())
			.andExpect(jsonPath("$.reason").value("GITHUB_NOT_LINKED"));
		this.mvc.perform(post("/api/v1/products/source-app/install-link").with(as(unlinked, null)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"url\":\"https://github.com/minh-tran/quan-ca-phe\"}"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.reason").value("GITHUB_NOT_LINKED"));
		register(as(unlinked, null), "https://github.com/minh-tran/quan-ca-phe", "2.0").andExpect(status().isForbidden())
			.andExpect(jsonPath("$.reason").value("GITHUB_NOT_LINKED"));
	}

	@Test
	void aCustomerWhoIsNotAdminIsRefusedByBothChecksBeforeTheWorkflowAndKeepsNoBinding() throws Exception {
		ready("someone/else", 4005, 12345, "User", false, 10, "");
		GITHUB.sourcePermissions.put("someone/else", Map.of(this.lanLogin, "write"));
		// No workflow either: the admin right is read first, so its refusal is the answer.
		GITHUB.sourceFiles.remove("someone/else:" + WORKFLOW);

		check("https://github.com/someone/else").andExpect(status().is(422))
			.andExpect(jsonPath("$.reason").value("NO_ADMIN_PERMISSION"));
		workflowCheck("https://github.com/someone/else").andExpect(status().is(422))
			.andExpect(jsonPath("$.reason").value("NO_ADMIN_PERMISSION"));
		// The Source App's token reads no such repository: refused before the workflow too.
		GITHUB.installSource("lan-nguyen", "gone", 4009, this.lanGithubId);
		workflowCheck("https://github.com/lan-nguyen/gone").andExpect(status().is(422))
			.andExpect(jsonPath("$.reason").value("REPO_NOT_FOUND"));
		assertThat(this.jdbc.sql("SELECT count(*) FROM product.source_app_binding WHERE customer_account_id = :id")
			.param("id", this.lan).query(Long.class).single()).as("no binding for a repository they do not administer").isZero();
	}

	@Test
	void aTransferredRepositoryWhoseNewOwnerInstalledTheAppIsReconfirmedOnTheNewInstallation() throws Exception {
		long before = ready("lan-nguyen/shop-gom", 4001, this.lanGithubId, "User", false, 10, "");
		UUID id = registered("https://github.com/lan-nguyen/shop-gom");
		// GitHub moves the repository to cong-ty: the old installation keeps no selection of it, and cong-ty installed
		// the Source App on it.
		GITHUB.mergeInstallations.get(before).deselect(4001);
		GITHUB.repositoryInstallations.remove("lan-nguyen/shop-gom");
		REPOSITORIES.remove("/repos/lan-nguyen/shop-gom");
		long after = ready("cong-ty/shop-gom", 4001, 99, "Organization", false, 10, "");
		GITHUB.mergeInstallations.get(after).accountType = "Organization";

		assertThat(this.sources.reconfirm(id, "cong-ty/shop-gom")).isNull();

		assertThat(binding("cong-ty/shop-gom")).isEqualTo("BOUND:" + after + ":" + id);
		assertThat(this.jdbc.sql("SELECT repository_full_name FROM product.product WHERE id = :id").param("id", id)
			.query(String.class).single()).isEqualTo("cong-ty/shop-gom");
	}

	@Test
	void theSuggestedSkillsReadTheLanguagesWithATokenNarrowedToTheRepositoryAndRevoked() throws Exception {
		ready("lan-nguyen/shop-gom", 4001, this.lanGithubId, "User", false, 10, "");
		UUID id = registered("https://github.com/lan-nguyen/shop-gom");
		REPOSITORIES.put("/repos/lan-nguyen/shop-gom/languages", "{\"Python\":12000}");
		GITHUB.seen.clear();

		this.mvc.perform(get("/api/v1/products/" + id + "/skill-suggestions").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.name == 'Python')]").exists())
			.andExpect(jsonPath("$[?(@.name == 'React')]").exists());

		String token = GITHUB.seen.stream()
			.filter(request -> request.path().equals("/repos/lan-nguyen/shop-gom/languages"))
			.map(Seen::authorization)
			.map(authorization -> authorization.substring(authorization.indexOf(' ') + 1))
			.findFirst()
			.orElseThrow();
		assertThat(GITHUB.grantOf(token).path("repository_ids").toString()).isEqualTo("[4001]");
		assertThat(GITHUB.grantOf(token).path("permissions").toString()).isEqualTo("{\"metadata\":\"read\"}");
		assertThat(GITHUB.revokedTokens).contains(token);
	}

	@Test
	@SuppressWarnings("unchecked")
	void theExportHoldsTheBindingsWithoutTheirStateAndErasureDeletesThem() throws Exception {
		ready("lan-nguyen/shop-gom", 4001, this.lanGithubId, "User", false, 10, "");
		registered("https://github.com/lan-nguyen/shop-gom");
		repository("lan-nguyen/ban-nhap", 4010, this.lanGithubId, "User", false, 10, "");
		installLink("https://github.com/lan-nguyen/ban-nhap");
		String hash = this.jdbc.sql("SELECT state_hash FROM product.source_app_binding WHERE repository_full_name = 'lan-nguyen/ban-nhap'")
			.query(String.class).single();
		assertThat(hash).isNotBlank();

		PersonalDataSection section = this.personalData.export(this.lan).orElseThrow();

		List<Map<String, Object>> bindings = (List<Map<String, Object>>) section.data().get("sourceAppBindings");
		assertThat(bindings).extracting(binding -> binding.get("repository"))
			.containsExactlyInAnyOrder("lan-nguyen/shop-gom", "lan-nguyen/ban-nhap");
		assertThat(bindings).allSatisfy(binding -> assertThat(binding.keySet())
			.noneMatch(key -> key.toLowerCase(java.util.Locale.ROOT).contains("state")));
		assertThat(section.toString()).doesNotContain(hash);

		new TransactionTemplate(this.transactionManager)
			.executeWithoutResult(status -> this.personalData.eraseAccount(this.lan));

		assertThat(this.jdbc.sql("SELECT count(*) FROM product.source_app_binding WHERE customer_account_id = :id")
			.param("id", this.lan).query(Long.class).single()).isZero();
	}

	/**
	 * A repository ready to register: described, the Source App on exactly it, the Customer its admin, and the
	 * published {@code vibefix.yml} on its default branch.
	 * @return the installation
	 */
	private long ready(String fullName, long id, long ownerId, String ownerType, boolean isPrivate, long sizeKb,
			String extra) throws Exception {
		repository(fullName, id, ownerId, ownerType, isPrivate, sizeKb, extra);
		String[] name = fullName.split("/", 2);
		long installation = GITHUB.installSource(name[0], name[1], id, ownerId);
		GITHUB.sourcePermissions.put(fullName, Map.of(this.lanLogin, "admin"));
		GITHUB.sourceFiles.put(fullName + ":" + WORKFLOW, published());
		return installation;
	}

	private static String published() throws Exception {
		return GitHubStub.published(GitHubStub.CUSTOMER_WORKFLOW);
	}

	/** {@code git hash-object} of a text with LF line ends. */
	private static String blob(String content) throws Exception {
		byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
		MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
		sha1.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
		return HexFormat.of().formatHex(sha1.digest(bytes));
	}

	/** The draft's or the Product's binding of a repository as {@code status:installation:product}. */
	private String binding(String fullName) {
		return this.jdbc.sql("""
				SELECT status || ':' || coalesce(installation_id::text, '-') || ':' || coalesce(product_id::text, '-')
				FROM product.source_app_binding WHERE lower(repository_full_name) = lower(:name) AND status <> 'ENDED'
				ORDER BY created_at DESC LIMIT 1
				""").param("name", fullName).query(String.class).single();
	}

	private ResultActions check(String url) throws Exception {
		return check(as(this.lan, Role.CUSTOMER), url);
	}

	private ResultActions check(RequestPostProcessor caller, String url) throws Exception {
		return this.mvc.perform(post("/api/v1/products/repository-check").with(caller).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"url\":\"" + url + "\"}"));
	}

	private ResultActions workflowCheck(String url) throws Exception {
		return this.mvc.perform(post("/api/v1/products/workflow-check").with(as(this.lan, Role.CUSTOMER)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"url\":\"" + url + "\"}"));
	}

	private String installLink(String url) throws Exception {
		return JsonPath.read(this.mvc.perform(post("/api/v1/products/source-app/install-link").with(as(this.lan, Role.CUSTOMER))
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"url\":\"" + url + "\"}"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8), "$.url");
	}

	private static String state(String link) {
		assertThat(link).startsWith("https://github.com/apps/").contains("/installations/new?state=");
		return URLDecoder.decode(link.substring(link.indexOf("state=") + "state=".length()), StandardCharsets.UTF_8);
	}

	private ResultActions setup(UUID account, String state, @Nullable String installationId, @Nullable String action)
			throws Exception {
		MockHttpServletRequestBuilder request = get("/api/v1/github/source-app/setup").with(as(account, Role.CUSTOMER))
			.param("state", state);
		if (installationId != null) {
			request.param("installation_id", installationId);
		}
		if (action != null) {
			request.param("setup_action", action);
		}
		return this.mvc.perform(request).andExpect(status().isSeeOther());
	}

	/** Registers the repository with the current notices and answers the Product's ID. */
	private UUID registered(String url) throws Exception {
		return UUID.fromString(JsonPath.read(register(url, "2.0").andExpect(status().isCreated()).andReturn().getResponse()
			.getContentAsString(StandardCharsets.UTF_8), "$.id"));
	}

	private ResultActions register(String url, @Nullable String version) throws Exception {
		return register(as(this.lan, Role.CUSTOMER), url, version);
	}

	private ResultActions register(RequestPostProcessor caller, String url, @Nullable String version) throws Exception {
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
			String extra) {
		REPOSITORIES.put("/repos/" + fullName, """
				{"id":%d,"full_name":"%s","html_url":"https://github.com/%s","private":%s,"visibility":"%s",
				 "size":%d,"default_branch":"main","owner":{"id":%d,"type":"%s"}%s}
				""".formatted(id, fullName, fullName, isPrivate, isPrivate ? "private" : "public", sizeKb, ownerId, ownerType,
				extra.isEmpty() ? "" : "," + extra));
	}

	private RequestPostProcessor as(UUID account, @Nullable Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

}
