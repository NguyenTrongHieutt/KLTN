package vn.vibefix.scanning.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterAll;
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

import vn.vibefix.identity.internal.CustomerTokens;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.operations.Lane;
import vn.vibefix.operations.internal.OperationWorker;
import vn.vibefix.scanning.internal.PlantedSecrets.Planted;
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;

/**
 * FR-65, FR-7: a personal-data Finding cleared by a clean repository, end to end: registration
 * against a stub GitHub, the intake scan, a candidate that still holds personal data, and a clean
 * one, each scanned in full from a local {@code file:} clone.
 */
class CleanRepositoryIT extends IntegrationTest {

	private static final String TOKEN = "gho_customer_token_never_printed";

	private static final Path ROOT = temporaryRoot();

	private static final HttpServer GITHUB = start();

	private static final Map<String, String> REPOSITORIES = new ConcurrentHashMap<>();

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private ApplicationContext context;

	@Autowired
	private OperationWorker worker;

	private UUID lan;

	private long lanGithubId;

	@DynamicPropertySource
	static void stubs(DynamicPropertyRegistry registry) {
		registry.add("vibefix.github.api-url", () -> "http://127.0.0.1:" + GITHUB.getAddress().getPort());
		registry.add("vibefix.github.git-url", () -> TestRepositories.gitUrl(ROOT));
	}

	@BeforeEach
	void aCustomerWithAKeptToken() {
		REPOSITORIES.clear();
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null, null);
		this.lanGithubId = CustomerTokens.githubUserId(this.jdbc, this.lan);
		CustomerTokens.keep(this.context, this.lan, TOKEN);
	}

	@AfterEach
	void release() {
		TestRepositories.releaseFiles();
	}

	@AfterAll
	static void cleanUp() {
		GITHUB.stop(0);
		TestRepositories.releaseFiles();
		try (var paths = Files.walk(ROOT)) {
			paths.sorted(Comparator.reverseOrder()).forEach(path -> {
				path.toFile().setWritable(true);
				path.toFile().delete();
			});
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	@Test
	void onlyACandidateWithoutPersonalDataReplacesTheSourceRepository() throws Exception {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		String source = "lan-nguyen/shop-" + suffix;
		String dirty = "lan-nguyen/dirty-" + suffix;
		String clean = "lan-nguyen/clean-" + suffix;
		Planted stripe = PlantedSecrets.all().get("stripe-secret-key");
		gitRepository(source, Map.of("seed/customers.json", PlantedPersonalData.customersJson(12), "README.md", "# Gốm\n"));
		gitRepository(dirty, Map.of("data/customers.json", PlantedPersonalData.customersJson(11)));
		gitRepository(clean, Map.of("README.md", "# Gốm sạch\n", stripe.path(), stripe.text()));
		gitHub(source, 7001);
		gitHub(dirty, 7002);
		gitHub(clean, 7003);
		UUID product = register(source);
		this.worker.runDue(Lane.SCAN);
		UUID personal = this.jdbc.sql("SELECT id FROM scanning.finding WHERE product_id = :p AND category = 'PERSONAL_DATA'")
			.param("p", product)
			.query(UUID.class)
			.single();

		// Each offer later than the one before: the latest replacement is the newest request.
		this.clock.advance(Duration.ofMinutes(1));
		offer(product, dirty).andExpect(status().isAccepted())
			.andExpect(jsonPath("$.status").value("PENDING"))
			.andExpect(jsonPath("$.repositoryFullName").value(dirty));
		this.worker.runDue(Lane.SCAN);
		await().atMost(Duration.ofSeconds(10)).until(() -> replacementStatus(product).equals("REJECTED PERSONAL_DATA_FOUND"));
		assertThat(sourceOf(product)).isEqualTo(source + " 7001");
		assertThat(findings(product)).as("a refused candidate stores no Finding").isEqualTo(1);
		this.mvc.perform(get("/api/v1/products/" + product + "/findings/" + personal).with(as(this.lan)))
			.andExpect(jsonPath("$.cleanRepository.status").value("REJECTED"))
			.andExpect(jsonPath("$.cleanRepository.reason").value("PERSONAL_DATA_FOUND"));

		this.clock.advance(Duration.ofMinutes(1));
		offer(product, clean).andExpect(status().isAccepted());
		this.worker.runDue(Lane.SCAN);
		await().atMost(Duration.ofSeconds(10)).until(() -> replacementStatus(product).equals("ACCEPTED"));

		assertThat(sourceOf(product)).isEqualTo(clean + " 7003");
		assertThat(this.jdbc.sql("""
				SELECT previous_full_name || ' ' || previous_github_id FROM product.repository_replacement
				WHERE product_id = :p AND status = 'ACCEPTED'
				""").param("p", product).query(String.class).single()).isEqualTo(source + " 7001");
		assertThat(this.jdbc.sql("SELECT status || ' ' || resolution FROM scanning.finding WHERE id = :id")
			.param("id", personal).query(String.class).single()).isEqualTo("RESOLVED CLEAN_REPOSITORY");
		assertThat(this.jdbc.sql("""
				SELECT rule_id FROM scanning.finding WHERE product_id = :p AND status = 'OPEN'
				""").param("p", product).query(String.class).list()).containsExactly("stripe-secret-key");
		assertThat(audits("product.repository_replaced")).isOne();
		assertThat(audits("scanning.finding_cleared")).isOne();
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM notification.todo WHERE subject_id = :f AND closed_at IS NULL
				""").param("f", personal).query(Long.class).single()).isZero();
		this.mvc.perform(get("/api/v1/products/" + product).with(as(this.lan)))
			.andExpect(jsonPath("$.repositoryFullName").value(clean))
			.andExpect(jsonPath("$.scan.status").value("SUCCEEDED"))
			.andExpect(jsonPath("$.openFindings").value(1));

		offer(product, "lan-nguyen/other-" + suffix).andExpect(status().is(422))
			.andExpect(jsonPath("$.reason").value("NO_PERSONAL_DATA_FINDING"));
	}

	@Test
	void theChecksRunAgainAndOnlyTheCustomerMayOffer() throws Exception {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		String source = "lan-nguyen/shop-" + suffix;
		gitRepository(source, Map.of("seed/customers.json", PlantedPersonalData.customersJson(12)));
		gitHub(source, 7101);
		REPOSITORIES.put("/repos/lan-nguyen/secret-" + suffix, metadata("lan-nguyen/secret-" + suffix, 7102, true));
		UUID product = register(source);
		this.worker.runDue(Lane.SCAN);

		offer(product, "lan-nguyen/secret-" + suffix).andExpect(status().is(422))
			.andExpect(jsonPath("$.reason").value("REPO_PRIVATE"));
		offer(product, source).andExpect(status().is(422)).andExpect(jsonPath("$.reason").value("REPO_ALREADY_LINKED"));
		UUID other = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null, null);
		this.mvc.perform(post("/api/v1/products/" + product + "/clean-repository").with(as(other)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"url\":\"https://github.com/" + source + "\"}")).andExpect(status().isNotFound());
		assertThat(this.jdbc.sql("SELECT count(*) FROM product.repository_replacement").query(Long.class).single()).isZero();
	}

	private UUID register(String repository) throws Exception {
		String body = this.mvc.perform(post("/api/v1/products").with(as(this.lan)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"repositoryUrl":"https://github.com/%s","tools":["LOVABLE"],"name":"Shop Gốm Bát Tràng",
					 "description":"Cửa hàng online bán gốm.","publicNoticeVersion":"1.0","copyPermissionVersion":"1.0",
					 "detectionNoticeVersion":"1.0"}
					""".formatted(repository)))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
		return UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.id"));
	}

	private ResultActions offer(UUID product, String repository) throws Exception {
		return this.mvc.perform(post("/api/v1/products/" + product + "/clean-repository").with(as(this.lan)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"url\":\"https://github.com/" + repository + "\"}"));
	}

	private String replacementStatus(UUID product) {
		return this.jdbc.sql("""
				SELECT status || coalesce(' ' || reason, '') FROM product.repository_replacement WHERE product_id = :p
				ORDER BY requested_at DESC, id DESC LIMIT 1
				""").param("p", product).query(String.class).single();
	}

	private String sourceOf(UUID product) {
		return this.jdbc.sql("SELECT repository_full_name || ' ' || repository_github_id FROM product.product WHERE id = :p")
			.param("p", product)
			.query(String.class)
			.single();
	}

	private long findings(UUID product) {
		return this.jdbc.sql("SELECT count(*) FROM scanning.finding WHERE product_id = :p")
			.param("p", product)
			.query(Long.class)
			.single();
	}

	private long audits(String action) {
		return this.jdbc.sql("SELECT count(*) FROM audit.audit_entry WHERE action = :a")
			.param("a", action)
			.query(Long.class)
			.single();
	}

	private void gitHub(String fullName, long id) {
		REPOSITORIES.put("/repos/" + fullName, metadata(fullName, id, false));
	}

	private String metadata(String fullName, long id, boolean isPrivate) {
		return """
				{"id":%d,"full_name":"%s","html_url":"https://github.com/%s","private":%s,"visibility":"%s",
				 "size":100,"default_branch":"main","owner":{"id":%d,"type":"User"}}
				""".formatted(id, fullName, fullName, isPrivate, isPrivate ? "private" : "public", this.lanGithubId);
	}

	private static void gitRepository(String fullName, Map<String, String> files) {
		try (Git git = TestRepositories.create(ROOT, fullName)) {
			TestRepositories.commit(git, Instant.parse("2026-09-26T03:00:00Z"), files, Set.of());
		}
	}

	private RequestPostProcessor as(UUID account) {
		return IdentityTestSupport.signedIn(this.sessions, account, Role.CUSTOMER, this.clock.instant());
	}

	private static Path temporaryRoot() {
		try {
			return Files.createTempDirectory("vibefix-git-");
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static HttpServer start() {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/repos/", CleanRepositoryIT::answer);
			server.start();
			return server;
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static void answer(HttpExchange exchange) throws IOException {
		String path = exchange.getRequestURI().getPath();
		String body = path.contains("/contents/") ? null : REPOSITORIES.get(path);
		int status = body == null ? 404 : 200;
		byte[] bytes = (body == null ? "{\"message\":\"Not Found\"}" : body).getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

}
