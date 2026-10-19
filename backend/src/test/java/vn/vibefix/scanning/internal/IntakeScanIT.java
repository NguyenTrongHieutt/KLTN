package vn.vibefix.scanning.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.FileMode;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.operations.Lane;
import vn.vibefix.operations.internal.OperationWorker;
import vn.vibefix.scanning.ScanCompleted;
import vn.vibefix.scanning.ScanPurpose;
import vn.vibefix.scanning.ScanTarget;
import vn.vibefix.scanning.Scans;
import vn.vibefix.scanning.ShaRange;
import vn.vibefix.scanning.internal.PlantedSecrets.Planted;
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.MutableClock;

/**
 * FR-8, AD-18, AD-6, AD-32: an intake scan from its request to CUS-4. The operation runs the scan job
 * in-process (the dev stack's stand-in for the sandbox) on repositories cloned from local
 * {@code file:} URLs, records masked Findings, and tells the Customer.
 */
@RecordApplicationEvents
class IntakeScanIT extends IntegrationTest {

	private static final Path ROOT = temporaryRoot();

	private static final Map<String, Planted> PLANTED = PlantedSecrets.all();

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private OperationWorker worker;

	@Autowired
	private Scans scans;

	@Autowired
	private PlatformTransactionManager transactions;

	@Autowired
	private ScanningPersonalData personalData;

	@Autowired
	private ApplicationEvents events;

	private UUID lan;

	@DynamicPropertySource
	static void localGit(DynamicPropertyRegistry registry) {
		registry.add("vibefix.github.git-url", () -> TestRepositories.gitUrl(ROOT));
	}

	@BeforeEach
	void aCustomer() {
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null, null);
		this.jdbc.sql("UPDATE identity.account SET github_email = 'lan@users.github.test' WHERE id = :id")
			.param("id", this.lan)
			.update();
	}

	@AfterEach
	void release() {
		TestRepositories.releaseFiles();
	}

	@AfterAll
	static void deleteRepositories() {
		TestRepositories.releaseFiles();
		try (var paths = Files.walk(ROOT)) {
			paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
				path.toFile().setWritable(true);
				path.toFile().delete();
			});
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	@Test
	void aScanStoresMaskedFindingsTellsTheCustomerAndShowsThemOnCus4() throws Exception {
		Planted stripe = PLANTED.get("stripe-secret-key");
		Planted supabase = PLANTED.get("supabase-service-role-key");
		Planted github = PLANTED.get("github-pat");
		String anon = PlantedSecrets.jwt("anon", 60);
		repository("lan/shop-gom", Map.of(stripe.path(), stripe.text(), supabase.path(),
				supabase.text() + "VITE_SUPABASE_ANON_KEY=" + anon + "\n", github.path(), github.text(),
				"public/video.mp4.txt", "v".repeat(1024 * 1024 + 10)));
		UUID product = product("lan/shop-gom");

		UUID run = requestScan(product, "lan/shop-gom");
		assertThat(this.worker.runDue(Lane.SCAN)).isOne();

		assertThat(this.jdbc.sql("""
				SELECT status || ' ' || found_count || ' ' || new_findings || ' ' || unscanned_count || ' ' || rules_version
				FROM scanning.scan_run WHERE id = :id
				""").param("id", run).query(String.class).single()).isEqualTo("SUCCEEDED 3 3 1 2");
		assertThat(this.jdbc.sql("""
				SELECT rule_id || ' ' || masked_preview || ' ' || file_path FROM scanning.finding WHERE product_id = :p
				ORDER BY rule_id
				""").param("p", product).query(String.class).list()).containsExactly(
				"github-pat " + SecretMatcher.mask(github.value()) + " " + github.path(),
				"stripe-secret-key " + SecretMatcher.mask(stripe.value()) + " " + stripe.path(),
				"supabase-service-role-key " + SecretMatcher.mask(supabase.value()) + " " + supabase.path());
		assertThat(this.jdbc.sql("SELECT count(*) FROM scanning.finding WHERE fingerprint ~ '^[0-9a-f]{64}$'")
			.query(Long.class).single()).isEqualTo(3);
		assertNoRawValueStored(stripe.value(), supabase.value(), github.value(), anon);

		assertThat(this.jdbc.sql("""
				SELECT params->>'type' FROM notification.notification
				WHERE account_id = :lan AND type = 'scanning.finding_opened' ORDER BY params->>'type'
				""").param("lan", this.lan).query(String.class).list())
			.containsExactly("Khóa bí mật Stripe", "Khóa dịch vụ Supabase", "Mã truy cập GitHub");
		assertThat(notifications("scanning.intake_scan_clean")).isZero();
		assertThat(this.events.stream(ScanCompleted.class))
			.singleElement()
			.satisfies(completed -> assertThat(completed.clean()).isFalse());
		this.worker.runDue(Lane.EMAIL);
		assertThat(this.mail.sent()).extracting(sent -> sent.subject())
			.containsExactlyInAnyOrder("Phát hiện Khóa bí mật Stripe trong Shop Gốm Bát Tràng",
					"Phát hiện Khóa dịch vụ Supabase trong Shop Gốm Bát Tràng",
					"Phát hiện Mã truy cập GitHub trong Shop Gốm Bát Tràng");

		this.mvc.perform(get("/api/v1/products/" + product).with(as(this.lan, Role.CUSTOMER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.scanState").value("FINDINGS"))
			.andExpect(jsonPath("$.openFindings").value(3))
			.andExpect(jsonPath("$.scan.status").value("SUCCEEDED"))
			.andExpect(jsonPath("$.scan.unscannedCount").value(1))
			.andExpect(jsonPath("$.scan.unscanned[0].path").value("public/video.mp4.txt"));
		this.mvc.perform(get("/api/v1/products/" + product + "/findings").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(3))
			.andExpect(jsonPath("$[0].maskedPreview").exists())
			.andExpect(jsonPath("$[0].status").value("OPEN"))
			.andExpect(jsonPath("$[0].fingerprint").doesNotExist());
		this.mvc.perform(get("/api/v1/products").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$[0].scanState").value("FINDINGS"))
			.andExpect(jsonPath("$[0].openFindings").value(3));
		UUID other = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null, null);
		this.mvc.perform(get("/api/v1/products/" + product + "/findings").with(as(other, Role.CUSTOMER)))
			.andExpect(status().isNotFound());
	}

	@Test
	void personalDataIsFoundInTheSameRunAndShownMaskedPerFile() throws Exception {
		repository("lan/customers", Map.of("seed/customers.json", PlantedPersonalData.customersJson(12),
				"exports/khach-hang.xlsx", "PK not really a spreadsheet\n", "README.md", "Gọi 0912345678\n"));
		UUID product = product("lan/customers");

		UUID run = requestScan(product, "lan/customers");
		this.worker.runDue(Lane.SCAN);

		assertThat(runStatus(run)).isEqualTo("SUCCEEDED");
		assertThat(this.jdbc.sql("""
				SELECT category || ' ' || rule_type || ' ' || match_count || ' ' || masked_preview
				FROM scanning.finding WHERE product_id = :p
				""").param("p", product).query(String.class).single())
			.isEqualTo("PERSONAL_DATA Email và số điện thoại khách hàng 12 kh••••@gmail.com · 09•• ••• •00");
		assertNoRawValueStored(PlantedPersonalData.phone(0), "khach.hang0@gmail.com", "khach.hang11@gmail.com");
		assertThat(this.jdbc.sql("""
				SELECT params->>'type' FROM notification.notification WHERE type = 'scanning.finding_opened'
				""").query(String.class).single()).isEqualTo("Email và số điện thoại khách hàng");
		this.mvc.perform(get("/api/v1/products/" + product).with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.scanState").value("FINDINGS"))
			.andExpect(jsonPath("$.scan.unscanned[0].path").value("exports/khach-hang.xlsx"))
			.andExpect(jsonPath("$.scan.unscanned[0].reason").value("NOT_EXTRACTED"));
		this.mvc.perform(get("/api/v1/products/" + product + "/findings").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$[0].category").value("PERSONAL_DATA"))
			.andExpect(jsonPath("$[0].matchCount").value(12))
			.andExpect(jsonPath("$[0].path").value("seed/customers.json"));
	}

	@Test
	void theCustomerConfirmsARotationOnCus5AndOnlyThatFindingCloses() throws Exception {
		Planted stripe = PLANTED.get("stripe-secret-key");
		repository("lan/rotate", Map.of(stripe.path(), stripe.text(), "seed/customers.json",
				PlantedPersonalData.customersJson(12)));
		UUID product = product("lan/rotate");
		requestScan(product, "lan/rotate");
		this.worker.runDue(Lane.SCAN);
		UUID credential = findingOf(product, "CREDENTIAL");
		UUID personal = findingOf(product, "PERSONAL_DATA");

		assertThat(this.jdbc.sql("""
				SELECT link FROM notification.todo
				WHERE account_id = :lan AND todo_key = 'scanning.resolve_intake_finding' AND closed_at IS NULL ORDER BY link
				""").param("lan", this.lan).query(String.class).list()).containsExactlyInAnyOrder(
				"/san-pham/" + product + "/van-de/" + credential, "/san-pham/" + product + "/van-de/" + personal);
		String path = "/api/v1/products/" + product + "/findings/" + credential;
		this.mvc.perform(get(path).with(as(this.lan, Role.CUSTOMER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.canResolve").value(true))
			.andExpect(jsonPath("$.productName").value("Shop Gốm Bát Tràng"))
			.andExpect(jsonPath("$.tools[0]").value("LOVABLE"))
			.andExpect(jsonPath("$.finding.ruleId").value("stripe-secret-key"))
			.andExpect(jsonPath("$.finding.status").value("OPEN"));
		UUID admin = IdentityTestSupport.insertAccount(this.jdbc, Role.ADMINISTRATOR, null, null);
		this.mvc.perform(get(path).with(as(admin, Role.ADMINISTRATOR))).andExpect(jsonPath("$.canResolve").value(false));
		UUID other = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null, null);
		this.mvc.perform(get(path).with(as(other, Role.CUSTOMER))).andExpect(status().isNotFound());
		this.mvc.perform(post(path + "/rotation").with(as(other, Role.CUSTOMER)).with(csrf()))
			.andExpect(status().isNotFound());
		this.mvc.perform(post("/api/v1/products/" + product + "/findings/" + personal + "/rotation")
			.with(as(this.lan, Role.CUSTOMER)).with(csrf()))
			.andExpect(status().is(422))
			.andExpect(jsonPath("$.reason").value("NOT_A_CREDENTIAL"));

		this.mvc.perform(post(path + "/rotation").with(as(this.lan, Role.CUSTOMER)).with(csrf()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("RESOLVED"))
			.andExpect(jsonPath("$.resolution").value("ROTATED"));
		this.mvc.perform(post(path + "/rotation").with(as(this.lan, Role.CUSTOMER)).with(csrf()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("FINDING_CLOSED"));

		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM audit.audit_entry e JOIN audit.audit_entry_object o ON o.entry_id = e.id
				WHERE e.action = 'scanning.finding_rotated' AND o.object_id = :f
				""").param("f", credential).query(Long.class).single()).isOne();
		assertThat(this.jdbc.sql("""
				SELECT subject_id FROM notification.todo
				WHERE todo_key = 'scanning.resolve_intake_finding' AND closed_at IS NULL
				""").query(UUID.class).list()).containsExactly(personal);
		this.mvc.perform(get("/api/v1/products/" + product).with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.openFindings").value(1));
	}

	@Test
	void setResolversReplacesWhoMayResolveAFinding() {
		Planted stripe = PLANTED.get("stripe-secret-key");
		repository("lan/resolvers", Map.of(stripe.path(), stripe.text()));
		UUID product = product("lan/resolvers");
		requestScan(product, "lan/resolvers");
		this.worker.runDue(Lane.SCAN);
		UUID finding = findingOf(product, "CREDENTIAL");
		UUID mentor = IdentityTestSupport.insertAccount(this.jdbc, Role.MENTOR, null, null);
		assertThat(this.scans.mayResolve(finding, this.lan)).isTrue();

		new TransactionTemplate(this.transactions).executeWithoutResult(
				status -> this.scans.setResolvers(finding, Set.of(mentor)));

		assertThat(this.scans.mayResolve(finding, this.lan)).isFalse();
		assertThat(this.scans.mayResolve(finding, mentor)).isTrue();
	}

	@Test
	void aFalsePositiveKeepsItsReasonAndARescanNeverReopensIt() throws Exception {
		Planted stripe = PLANTED.get("stripe-secret-key");
		repository("lan/sample-key", Map.of(stripe.path(), stripe.text()));
		UUID product = product("lan/sample-key");
		requestScan(product, "lan/sample-key");
		this.worker.runDue(Lane.SCAN);
		UUID finding = findingOf(product, "CREDENTIAL");
		String path = "/api/v1/products/" + product + "/findings/" + finding + "/false-positive";

		this.mvc.perform(post(path).with(as(this.lan, Role.CUSTOMER)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"reason\":\"  \"}")).andExpect(status().isBadRequest());
		UUID other = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null, null);
		this.mvc.perform(post(path).with(as(other, Role.CUSTOMER)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"reason\":\"Khóa mẫu\"}")).andExpect(status().isNotFound());
		this.mvc.perform(post(path).with(as(this.lan, Role.CUSTOMER)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"reason\":\"Khóa mẫu trong tài liệu, dự án đã xóa từ tháng 8.\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("FALSE_POSITIVE"))
			.andExpect(jsonPath("$.resolutionReason").value("Khóa mẫu trong tài liệu, dự án đã xóa từ tháng 8."));
		this.mvc.perform(post(path).with(as(this.lan, Role.CUSTOMER)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"reason\":\"Lần nữa\"}")).andExpect(status().isConflict());
		assertThat(this.jdbc.sql("SELECT count(*) FROM audit.audit_entry WHERE action = 'scanning.finding_marked_false_positive'")
			.query(Long.class).single()).isOne();
		assertThat(this.jdbc.sql("SELECT count(*) FROM notification.todo WHERE subject_id = :f AND closed_at IS NULL")
			.param("f", finding).query(Long.class).single()).isZero();

		this.clock.advance(Duration.ofMinutes(1));
		this.mvc.perform(post("/api/v1/products/" + product + "/scans").with(as(this.lan, Role.CUSTOMER)).with(csrf()))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.purpose").value("RESCAN"))
			.andExpect(jsonPath("$.status").value("PENDING"));
		this.mvc.perform(post("/api/v1/products/" + product + "/scans").with(as(this.lan, Role.CUSTOMER)).with(csrf()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("SCAN_IN_PROGRESS"));
		this.worker.runDue(Lane.SCAN);

		assertThat(this.jdbc.sql("SELECT count(*) FROM scanning.finding WHERE product_id = :p").param("p", product)
			.query(Long.class).single()).as("the fingerprint keeps the false positive closed").isOne();
		this.mvc.perform(get("/api/v1/products/" + product + "/findings").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].status").value("FALSE_POSITIVE"));
		this.mvc.perform(get("/api/v1/products/" + product + "/scans").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[0].purpose").value("RESCAN"))
			.andExpect(jsonPath("$[0].status").value("SUCCEEDED"))
			.andExpect(jsonPath("$[0].found").value(1))
			.andExpect(jsonPath("$[1].purpose").value("INTAKE"));
		this.mvc.perform(get("/api/v1/products/" + product).with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.openFindings").value(0))
			.andExpect(jsonPath("$.scanState").value("CLEAN"));
	}

	@Test
	void aProductIsRescannedAtMostFiveTimesAVietnamDay() throws Exception {
		repository("lan/often", Map.of("README.md", "# Thường xuyên\n"));
		UUID product = product("lan/often");
		requestScan(product, "lan/often");
		this.worker.runDue(Lane.SCAN);
		for (int i = 0; i < 5; i++) {
			this.clock.advance(Duration.ofMinutes(1));
			this.mvc.perform(post("/api/v1/products/" + product + "/scans").with(as(this.lan, Role.CUSTOMER)).with(csrf()))
				.andExpect(status().isAccepted());
			this.worker.runDue(Lane.SCAN);
		}

		this.mvc.perform(post("/api/v1/products/" + product + "/scans").with(as(this.lan, Role.CUSTOMER)).with(csrf()))
			.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.reason").value("SCAN_LIMIT"))
			.andExpect(header().exists("Retry-After"));
		this.clock.advance(Duration.ofDays(1));
		this.mvc.perform(post("/api/v1/products/" + product + "/scans").with(as(this.lan, Role.CUSTOMER)).with(csrf()))
			.andExpect(status().isAccepted());
	}

	@Test
	void aRepeatedCredentialOpensNoSecondFindingAndACleanRepositorySaysSo() {
		Planted stripe = PLANTED.get("stripe-secret-key");
		repository("lan/repeat", Map.of(stripe.path(), stripe.text(), "copy/" + stripe.path(), "// again\n" + stripe.text()));
		repository("lan/clean", Map.of("README.md", "# Sạch\n", "src/app.ts", "export const ok = true\n"));
		UUID repeat = product("lan/repeat");
		UUID clean = product("lan/clean");

		requestScan(repeat, "lan/repeat");
		requestScan(clean, "lan/clean");
		this.worker.runDue(Lane.SCAN);
		UUID again = requestScan(repeat, "lan/repeat");
		this.worker.runDue(Lane.SCAN);

		assertThat(this.jdbc.sql("SELECT count(*) FROM scanning.finding WHERE product_id = :p").param("p", repeat)
			.query(Long.class).single()).isOne();
		assertThat(this.jdbc.sql("SELECT found_count || ' ' || new_findings FROM scanning.scan_run WHERE id = :id")
			.param("id", again).query(String.class).single()).isEqualTo("1 0");
		assertThat(notifications("scanning.finding_opened")).isOne();
		assertThat(notifications("scanning.intake_scan_clean")).isOne();
		assertThat(this.jdbc.sql("""
				SELECT params->>'product' FROM notification.notification WHERE type = 'scanning.intake_scan_clean'
				""").query(String.class).single()).isEqualTo("Shop Gốm Bát Tràng");
		assertThat(this.events.stream(ScanCompleted.class).filter(ScanCompleted::clean).map(ScanCompleted::productId))
			.containsExactly(clean);
	}

	@Test
	void aScanThatKeepsFailingIsTriedThreeTimesThenFails() throws Exception {
		UUID product = product("lan/missing");
		UUID run = requestScan(product, "lan/missing");

		this.worker.runDue(Lane.SCAN);
		assertThat(runStatus(run)).isEqualTo("RETRYING SCAN_JOB_FAILED");
		this.mvc.perform(get("/api/v1/products/" + product).with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.scanState").value("NOT_FINISHED"))
			.andExpect(jsonPath("$.scan.status").value("RETRYING"));
		this.clock.advance(Duration.ofMinutes(5));
		assertThat(this.worker.runDue(Lane.SCAN)).isOne();
		this.clock.advance(Duration.ofMinutes(10));
		assertThat(this.worker.runDue(Lane.SCAN)).isOne();

		await().atMost(Duration.ofSeconds(10)).until(() -> runStatus(run).equals("FAILED SCAN_JOB_FAILED"));
		assertThat(this.jdbc.sql("SELECT attempts FROM scanning.scan_run WHERE id = :id").param("id", run)
			.query(Integer.class).single()).isEqualTo(3);
		assertThat(this.events.stream(ScanCompleted.class)).isEmpty();
	}

	@Test
	void aRepositoryWithAnUnsafeTreeFailsAtOnce() {
		try (Git git = TestRepositories.create(ROOT, "lan/escape")) {
			TestRepositories.rawCommit(git.getRepository(), Map.of("..", Map.entry(FileMode.REGULAR_FILE, "x\n")));
		}
		UUID run = requestScan(product("lan/escape"), "lan/escape");

		this.worker.runDue(Lane.SCAN);

		assertThat(runStatus(run)).isEqualTo("FAILED UNSAFE_REPOSITORY");
	}

	@Test
	void theCustomersScansAreExportedAndErasedWithTheirFindings() {
		Planted stripe = PLANTED.get("stripe-secret-key");
		repository("lan/erase", Map.of(stripe.path(), stripe.text()));
		requestScan(product("lan/erase"), "lan/erase");
		this.worker.runDue(Lane.SCAN);

		assertThat(this.personalData.export(this.lan)).hasValueSatisfying(section -> assertThat(section.toString())
			.contains("Khóa bí mật Stripe")
			.contains(SecretMatcher.mask(stripe.value()))
			.doesNotContain(stripe.value()));
		this.personalData.eraseAccount(this.lan);

		assertThat(this.jdbc.sql("SELECT (SELECT count(*) FROM scanning.scan_run) + (SELECT count(*) FROM scanning.finding)")
			.query(Long.class).single()).isZero();
	}

	private void assertNoRawValueStored(String... values) {
		List<String> rows = this.jdbc.sql("""
				SELECT row_to_json(r)::text FROM scanning.scan_run r
				UNION ALL SELECT row_to_json(f)::text FROM scanning.finding f
				UNION ALL SELECT row_to_json(n)::text FROM notification.notification n
				UNION ALL SELECT row_to_json(o)::text FROM operations.external_operation o
				UNION ALL SELECT row_to_json(e)::text FROM platform.event_publication e
				""").query(String.class).list();
		assertThat(rows).isNotEmpty();
		for (String value : values) {
			assertThat(rows).allSatisfy(row -> assertThat(row).doesNotContain(value));
		}
	}

	private UUID findingOf(UUID product, String category) {
		return this.jdbc.sql("SELECT id FROM scanning.finding WHERE product_id = :p AND category = :c")
			.param("p", product)
			.param("c", category)
			.query(UUID.class)
			.single();
	}

	private long notifications(String type) {
		return this.jdbc.sql("SELECT count(*) FROM notification.notification WHERE account_id = :lan AND type = :type")
			.param("lan", this.lan)
			.param("type", type)
			.query(Long.class)
			.single();
	}

	private String runStatus(UUID run) {
		return this.jdbc.sql("SELECT status || ' ' || coalesce(error_code, '') FROM scanning.scan_run WHERE id = :id")
			.param("id", run)
			.query(String.class)
			.single()
			.strip();
	}

	private UUID requestScan(UUID product, String repository) {
		return new TransactionTemplate(this.transactions).execute(status -> this.scans.requestScan(
				new ScanTarget(product, this.lan, "Shop Gốm Bát Tràng", repository, 4001), ShaRange.wholeHistory(),
				ScanPurpose.INTAKE));
	}

	private UUID product(String repository) {
		UUID id = UUID.randomUUID();
		Timestamp now = Timestamp.from(MutableClock.DEFAULT_INSTANT);
		this.jdbc.sql("""
				INSERT INTO product.product (id, customer_account_id, name, description, ai_tools, repository_github_id,
				    repository_full_name, repository_owner_type, repository_default_branch, public_notice_version,
				    copy_permission_version, detection_notice_version, acknowledged_at, created_at)
				VALUES (:id, :owner, 'Shop Gốm Bát Tràng', 'Cửa hàng online bán gốm.', ARRAY['LOVABLE'], :github,
				    :repository, 'User', 'main', '1.0', '1.0', '1.0', :now, :now)
				""")
			.param("id", id)
			.param("owner", this.lan)
			.param("github", ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE))
			.param("repository", repository)
			.param("now", now)
			.update();
		return id;
	}

	private static void repository(String fullName, Map<String, String> files) {
		try (Git git = TestRepositories.create(ROOT, fullName)) {
			TestRepositories.commit(git, Instant.parse("2026-09-26T03:00:00Z"), files, Set.of());
		}
	}

	private RequestPostProcessor as(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

	private static Path temporaryRoot() {
		try {
			return Files.createTempDirectory("vibefix-git-");
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
