package vn.vibefix.admin.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import vn.vibefix.identity.internal.AdministratorTestSupport;
import vn.vibefix.identity.internal.AdministratorTestSupport.Administrator;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;

/**
 * ADM-7 (FR-64, AD-6, SG-10): the failed operation with its Job, retried once behind the step-up from its
 * first attempt with a read-back first; and the false positive, reopened as an open Finding that hands its
 * resolver the to-do and the notice back. Both are audited and counted on ADM-1.
 */
class FailuresIT extends IntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private ApplicationContext context;

	@Test
	void aFailedOperationIsRetriedOnceBehindTheStepUp() throws Exception {
		Administrator ha = AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		UUID job = UUID.randomUUID();
		UUID operation = failedOperation(job);
		this.mvc.perform(get("/api/v1/admin/failures").with(admin(ha))).andExpect(status().isOk())
			.andExpect(jsonPath("$.operations[0].id").value(operation.toString()))
			.andExpect(jsonPath("$.operations[0].jobId").value(job.toString()))
			.andExpect(jsonPath("$.operations[0].errorCode").value("GITHUB_UNAVAILABLE"));

		retry(ha, operation, "000000").andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("STEP_UP_FAILED"));
		assertThat(text("SELECT status FROM operations.external_operation WHERE id = '" + operation + "'")).isEqualTo("FAILED");
		retry(ha, operation, AdministratorTestSupport.code(ha, this.clock.instant())).andExpect(status().isOk())
			.andExpect(jsonPath("$.operations").isEmpty());
		assertThat(text("""
				SELECT status || ':' || attempt_count || ':' || read_back_first FROM operations.external_operation WHERE id = '"""
				+ operation + "'")).isEqualTo("PENDING:0:true");
		assertThat(count("SELECT count(*) FROM audit.audit_entry WHERE action = 'admin.operation_retried'")).isEqualTo(1);
		this.clock.advance(Duration.ofSeconds(31));
		retry(ha, operation, AdministratorTestSupport.code(ha, this.clock.instant())).andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("NOT_FAILED"));
	}

	@Test
	void aFalsePositiveIsReopenedForItsResolver() throws Exception {
		Administrator ha = AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		UUID lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		this.jdbc.sql("""
				INSERT INTO identity.profile (account_id, kind, display_name, contact_email, updated_at)
				VALUES (:id, 'INDIVIDUAL', 'Nguyễn Thị Lan', 'lan@example.com', now())
				""").param("id", lan).update();
		UUID finding = falsePositive(lan);
		this.mvc.perform(get("/api/v1/admin/failures").with(admin(ha)))
			.andExpect(jsonPath("$.falsePositives[0].findingId").value(finding.toString()))
			.andExpect(jsonPath("$.falsePositives[0].reason").value("Khóa thử nghiệm"))
			.andExpect(jsonPath("$.falsePositives[0].markedByName").value("Nguyễn Thị Lan"));
		this.mvc.perform(get("/api/v1/admin/overview").with(admin(ha))).andExpect(jsonPath("$.falsePositives.count").value(1));

		reopen(ha, finding).andExpect(status().isOk()).andExpect(jsonPath("$.falsePositives").isEmpty());
		assertThat(text("SELECT status || ':' || (resolution IS NULL) FROM scanning.finding WHERE id = '" + finding + "'"))
			.isEqualTo("OPEN:true");
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type = 'scanning.finding_reopened' AND account_id = '"
				+ lan + "'")).isEqualTo(1);
		assertThat(count("""
				SELECT count(*) FROM notification.todo WHERE todo_key = 'scanning.resolve_intake_finding' AND closed_at IS NULL
				  AND account_id = '""" + lan + "'")).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM audit.audit_entry WHERE action = 'scanning.finding_reopened'")).isEqualTo(1);
		reopen(ha, finding).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("NOT_A_FALSE_POSITIVE"));
	}

	private UUID failedOperation(UUID job) {
		UUID id = UUID.randomUUID();
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO operations.external_operation (id, operation_key, type, lane, priority, status, payload,
				    attempt_count, next_attempt_at, error_code, created_at, completed_at)
				VALUES (:id, :key, 'github.test_operation', 'GITHUB_SHORT', 1, 'FAILED', CAST(:payload AS JSONB), 5, :now,
				    'GITHUB_UNAVAILABLE', :now, :now)
				""")
			.param("id", id)
			.param("key", "TEST:" + id)
			.param("payload", "{\"jobId\":\"" + job + "\"}")
			.param("now", now)
			.update();
		return id;
	}

	private UUID falsePositive(UUID customer) {
		UUID run = UUID.randomUUID();
		UUID product = UUID.randomUUID();
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO scanning.scan_run (id, product_id, customer_account_id, product_name, repository_full_name,
				    repository_github_id, purpose, status, requested_at, finished_at)
				VALUES (:id, :product, :customer, 'Sổ đặt bàn', 'lan/so-dat-ban', :github, 'INTAKE', 'SUCCEEDED', :now, :now)
				""").param("id", run).param("product", product).param("customer", customer)
			.param("github", ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE)).param("now", now).update();
		UUID finding = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO scanning.finding (id, product_id, scan_run_id, rule_id, rule_type, file_path, commit_sha, committed_at,
				    masked_preview, fingerprint, status, created_at, resolution, resolution_reason, resolved_by_account_id,
				    resolved_at, category)
				VALUES (:id, :product, :run, 'generic-api-key', 'Khóa API', 'src/config.ts', :sha, :now, 'abcd••••••••1234',
				    :fingerprint, 'FALSE_POSITIVE', :now, 'FALSE_POSITIVE', 'Khóa thử nghiệm', :customer, :now, 'CREDENTIAL')
				""")
			.param("id", finding)
			.param("product", product)
			.param("run", run)
			.param("sha", "a".repeat(40))
			.param("fingerprint", "b".repeat(64))
			.param("customer", customer)
			.param("now", now)
			.update();
		this.jdbc.sql("INSERT INTO scanning.finding_resolver (finding_id, account_id) VALUES (:finding, :customer)")
			.param("finding", finding)
			.param("customer", customer)
			.update();
		return finding;
	}

	private ResultActions retry(Administrator ha, UUID operation, String code) throws Exception {
		return this.mvc.perform(post("/api/v1/admin/failures/operations/" + operation + "/retry").with(admin(ha)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"stepUpCode\":\"" + code + "\"}"));
	}

	private ResultActions reopen(Administrator ha, UUID finding) throws Exception {
		return this.mvc.perform(post("/api/v1/admin/failures/findings/" + finding + "/reopen").with(admin(ha)).with(csrf()));
	}

	private long count(String sql) {
		return this.jdbc.sql(sql).query(Long.class).single();
	}

	private String text(String sql) {
		return this.jdbc.sql(sql).query(String.class).single();
	}

	private RequestPostProcessor admin(Administrator administrator) {
		return IdentityTestSupport.signedIn(this.sessions, administrator.accountId(), Role.ADMINISTRATOR, this.clock.instant());
	}

}
