package vn.vibefix.job.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.catalog.SkillRenamed;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.shared.PersonalData;
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.MutableClock;

/** FR-9, AD-20, AD-38: Draft Jobs on CUS-6 and CUS-7, their rules, screenshots, and Skills. */
class JobIT extends IntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private ApplicationEventPublisher events;

	@Autowired
	private PlatformTransactionManager transactions;

	@Autowired
	private PersonalData personalData;

	private UUID lan;

	private UUID product;

	@BeforeEach
	void aCustomerWithAProduct() {
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null, null);
		this.product = product(this.lan);
	}

	@Test
	void aDraftKeepsItsAnswersTypesSkillsBudgetAndDeadline() throws Exception {
		List<UUID> skills = skills("React", "Supabase");
		String body = draft("Thêm thanh toán online khi đặt bàn", "[\"NEW_FEATURE\",\"BUG_FIX\"]", skills, "FROM_2M_TO_5M",
				"2026-11-15");

		String created = this.mvc.perform(post("/api/v1/jobs").with(as(this.lan)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(body)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		UUID job = UUID.fromString(com.jayway.jsonpath.JsonPath.read(created, "$.id"));

		this.mvc.perform(get("/api/v1/jobs/" + job).with(as(this.lan)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("DRAFT"))
			.andExpect(jsonPath("$.productName").value("Sổ đặt bàn Quán Mây"))
			.andExpect(jsonPath("$.problem").value("Khách đặt bàn xong không nhận được email."))
			.andExpect(jsonPath("$.appUsers").value("Khách đặt bàn trên điện thoại."))
			.andExpect(jsonPath("$.jobTypes[0]").value("BUG_FIX"))
			.andExpect(jsonPath("$.jobTypes[1]").value("NEW_FEATURE"))
			.andExpect(jsonPath("$.skills[0].name").value("React"))
			.andExpect(jsonPath("$.skills[1].name").value("Supabase"))
			.andExpect(jsonPath("$.budget").value("FROM_2M_TO_5M"))
			.andExpect(jsonPath("$.deadline").value("2026-11-15"));
		this.mvc.perform(put("/api/v1/jobs/" + job).with(as(this.lan)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(draft("Thêm thanh toán và nhắc lịch", "[\"ASSESSMENT\"]", List.of(), null, null)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.title").value("Thêm thanh toán và nhắc lịch"))
			.andExpect(jsonPath("$.jobTypes.length()").value(1))
			.andExpect(jsonPath("$.skills.length()").value(0));
		this.mvc.perform(get("/api/v1/jobs").with(as(this.lan)))
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].state").value("DRAFT"))
			.andExpect(jsonPath("$[0].productName").value("Sổ đặt bàn Quán Mây"));
		UUID other = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null, null);
		this.mvc.perform(get("/api/v1/jobs/" + job).with(as(other))).andExpect(status().isNotFound());
		this.mvc.perform(put("/api/v1/jobs/" + job).with(as(other)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(draft("Khác", "[]", List.of(), null, null))).andExpect(status().isNotFound());
		assertThat(this.jdbc.sql("SELECT count(*) FROM audit.audit_entry WHERE action = 'job.drafted'")
			.query(Long.class).single()).isOne();
	}

	@Test
	void theRulesOfFr9AreEnforcedOnTheServer() throws Exception {
		create(draft("", "[]", List.of(), null, null)).andExpect(status().isBadRequest());
		create(draft("Đánh giá và sửa", "[\"ASSESSMENT\",\"BUG_FIX\"]", List.of(), null, null))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reason").value("ASSESSMENT_ALONE"));
		List<UUID> eleven = this.jdbc.sql("SELECT id FROM catalog.skill WHERE active ORDER BY display_order LIMIT 11")
			.query(UUID.class).list();
		create(draft("Quá nhiều kỹ năng", "[]", eleven, null, null)).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reason").value("SKILL_LIMIT"));
		openFinding(this.product);
		create(draft("Sửa lỗi", "[\"BUG_FIX\"]", List.of(), null, null)).andExpect(status().is(422))
			.andExpect(jsonPath("$.reason").value("OPEN_FINDINGS"));
		create(draft("Đánh giá trước", "[\"ASSESSMENT\"]", List.of(), null, null)).andExpect(status().isCreated());
		UUID someoneElses = product(IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null, null));
		this.mvc.perform(post("/api/v1/jobs").with(as(this.lan)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(draft("Của người khác", "[]", List.of(), null, null).replace(this.product.toString(), someoneElses.toString())))
			.andExpect(status().isNotFound());
	}

	@Test
	void screenshotsAreStoredWithinTheJobQuotaAndReadOnlyByItsCustomer() throws Exception {
		UUID job = jobId(create(draft("Ảnh", "[]", List.of(), null, null)).andExpect(status().isCreated()));

		String added = this.mvc.perform(multipart("/api/v1/jobs/" + job + "/screenshots")
			.file(new MockMultipartFile("file", "trang-dat-ban.png", "image/png", png()))
			.with(as(this.lan)).with(csrf()))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.fileName").exists())
			.andReturn().getResponse().getContentAsString();
		UUID file = UUID.fromString(com.jayway.jsonpath.JsonPath.read(added, "$.fileId"));
		this.mvc.perform(get("/api/v1/files/" + file).with(as(this.lan))).andExpect(status().isOk());
		UUID other = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null, null);
		this.mvc.perform(get("/api/v1/files/" + file).with(as(other))).andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/jobs/" + job).with(as(this.lan)))
			.andExpect(jsonPath("$.screenshots.length()").value(1))
			.andExpect(jsonPath("$.screenshots[0].url").value("/api/v1/files/" + file));

		this.jdbc.sql("""
				INSERT INTO job.job_screenshot (job_id, file_id, file_name, size_bytes, added_at)
				VALUES (:job, :file, 'lon.png', :size, now())
				""").param("job", job).param("file", UUID.randomUUID()).param("size", Jobs.FILE_QUOTA_BYTES - 10).update();
		this.mvc.perform(multipart("/api/v1/jobs/" + job + "/screenshots")
			.file(new MockMultipartFile("file", "them.png", "image/png", png()))
			.with(as(this.lan)).with(csrf()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reason").value("JOB_FILE_QUOTA"));

		this.mvc.perform(delete("/api/v1/jobs/" + job + "/screenshots/" + file).with(as(this.lan)).with(csrf()))
			.andExpect(status().isNoContent());
		this.mvc.perform(get("/api/v1/files/" + file).with(as(this.lan))).andExpect(status().isNotFound());
	}

	@Test
	void aRenamedSkillIsRenamedOnTheJobOnce() throws Exception {
		UUID react = skills("React").getFirst();
		UUID job = jobId(create(draft("Đổi tên", "[]", List.of(react), null, null)).andExpect(status().isCreated()));
		SkillRenamed renamed = new SkillRenamed(UUID.randomUUID(), react, "React.js");

		new TransactionTemplate(this.transactions).executeWithoutResult(status -> this.events.publishEvent(renamed));

		await().atMost(Duration.ofSeconds(10)).until(() -> this.jdbc.sql("SELECT skill_names[1] FROM job.job WHERE id = :id")
			.param("id", job).query(String.class).single().equals("React.js"));
	}

	@Test
	void theSuggestedSkillsComeFromTheProductsAiToolsWhenGitHubCannotAnswer() throws Exception {
		this.mvc.perform(get("/api/v1/products/" + this.product + "/skill-suggestions").with(as(this.lan)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.name == 'Lovable')]").exists())
			.andExpect(jsonPath("$[?(@.name == 'Supabase')]").exists())
			.andExpect(jsonPath("$[0].hint").exists());
	}

	@Test
	void deletingTheProductDeletesItsDraftJobs() throws Exception {
		UUID job = jobId(create(draft("Sẽ xóa", "[]", List.of(), null, null)).andExpect(status().isCreated()));

		new TransactionTemplate(this.transactions).executeWithoutResult(status -> this.personalData.deleteProduct(this.product));

		assertThat(this.jdbc.sql("SELECT count(*) FROM job.job WHERE id = :id").param("id", job).query(Long.class).single())
			.isZero();
	}

	private ResultActions create(String body) throws Exception {
		return this.mvc.perform(post("/api/v1/jobs").with(as(this.lan)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	private static UUID jobId(ResultActions created) throws Exception {
		return UUID.fromString(com.jayway.jsonpath.JsonPath.read(created.andReturn().getResponse().getContentAsString(), "$.id"));
	}

	private String draft(String title, String types, List<UUID> skills, String budget, String deadline) {
		return """
				{"productId":"%s","title":"%s","problem":"Khách đặt bàn xong không nhận được email.",
				 "expected":"Khách nhận email xác nhận ngay.","appUsers":"Khách đặt bàn trên điện thoại.",
				 "jobTypes":%s,"skillIds":[%s],"budget":%s,"deadline":%s}
				""".formatted(this.product, title, types,
				String.join(",", skills.stream().map(id -> "\"" + id + "\"").toList()),
				budget == null ? "null" : "\"" + budget + "\"", deadline == null ? "null" : "\"" + deadline + "\"");
	}

	private List<UUID> skills(String... names) {
		return java.util.Arrays.stream(names)
			.map(name -> this.jdbc.sql("SELECT id FROM catalog.skill WHERE name = :name").param("name", name)
				.query(UUID.class).single())
			.toList();
	}

	private UUID product(UUID owner) {
		UUID id = UUID.randomUUID();
		Timestamp now = Timestamp.from(MutableClock.DEFAULT_INSTANT);
		this.jdbc.sql("""
				INSERT INTO product.product (id, customer_account_id, name, description, ai_tools, repository_github_id,
				    repository_full_name, repository_owner_type, repository_default_branch, public_notice_version,
				    copy_permission_version, detection_notice_version, acknowledged_at, created_at)
				VALUES (:id, :owner, 'Sổ đặt bàn Quán Mây', 'Đặt bàn cho quán.', ARRAY['LOVABLE'], :github,
				    'lan/so-dat-ban', 'User', 'main', '1.0', '1.0', '1.0', :now, :now)
				""")
			.param("id", id)
			.param("owner", owner)
			.param("github", ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE))
			.param("now", now)
			.update();
		return id;
	}

	private void openFinding(UUID productId) {
		UUID run = UUID.randomUUID();
		Timestamp now = Timestamp.from(MutableClock.DEFAULT_INSTANT);
		this.jdbc.sql("""
				INSERT INTO scanning.scan_run (id, product_id, customer_account_id, product_name, repository_full_name,
				    repository_github_id, purpose, status, rules_version, found_count, new_findings, requested_at, finished_at)
				VALUES (:id, :product, :owner, 'Sổ', 'lan/so-dat-ban', 1, 'INTAKE', 'SUCCEEDED', 2, 1, 1, :now, :now)
				""").param("id", run).param("product", productId).param("owner", this.lan).param("now", now).update();
		this.jdbc.sql("""
				INSERT INTO scanning.finding (id, product_id, scan_run_id, category, rule_id, rule_type, file_path,
				    commit_sha, committed_at, masked_preview, fingerprint, status, created_at)
				VALUES (:id, :product, :run, 'CREDENTIAL', 'stripe-secret-key', 'Khóa bí mật Stripe', 'pay.js',
				    repeat('a', 40), :now, 'sk_l••••••••Q7mZ', repeat('c', 64), 'OPEN', :now)
				""").param("id", UUID.randomUUID()).param("product", productId).param("run", run).param("now", now).update();
	}

	private static byte[] png() throws Exception {
		java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(4, 3, java.awt.image.BufferedImage.TYPE_INT_RGB);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "png", out);
		return out.toByteArray();
	}

	private RequestPostProcessor as(UUID account) {
		return IdentityTestSupport.signedIn(this.sessions, account, Role.CUSTOMER, this.clock.instant());
	}

}
