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
import vn.vibefix.identity.internal.Role;
import vn.vibefix.support.IntegrationTest;

/**
 * ADM-7 (FR-64, AD-6, SG-10): the failed operation with its Job, retried once behind the step-up from its
 * first attempt with a read-back first, audited and counted on ADM-1. vibefix scans no code (Story 23.1), so
 * the queue holds no false positive.
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
	void noFindingIsQueuedOrReopenedAnyMore() throws Exception {
		Administrator ha = AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		// vibefix scans no code (Story 23.1): ADM-7 has no false positives and ADM-1 counts none.
		this.mvc.perform(get("/api/v1/admin/failures").with(admin(ha))).andExpect(status().isOk())
			.andExpect(jsonPath("$.falsePositives").doesNotExist());
		this.mvc.perform(get("/api/v1/admin/overview").with(admin(ha))).andExpect(status().isOk())
			.andExpect(jsonPath("$.falsePositives").doesNotExist());
		this.mvc.perform(post("/api/v1/admin/failures/findings/" + UUID.randomUUID() + "/reopen").with(admin(ha)).with(csrf()))
			.andExpect(status().isNotFound());
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

	private ResultActions retry(Administrator ha, UUID operation, String code) throws Exception {
		return this.mvc.perform(post("/api/v1/admin/failures/operations/" + operation + "/retry").with(admin(ha)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"stepUpCode\":\"" + code + "\"}"));
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
