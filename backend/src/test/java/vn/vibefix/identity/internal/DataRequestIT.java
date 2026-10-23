package vn.vibefix.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.BeforeEach;
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

import vn.vibefix.identity.internal.AdministratorTestSupport.Administrator;
import vn.vibefix.shared.Role;
import vn.vibefix.support.HeldCrewJob;
import vn.vibefix.support.IntegrationTest;

/**
 * Story 16.2 (NFR-11, AD-24, SG-9): a person downloads their own data and requests their account's deletion
 * once; Administrators hear of it; while a Job runs the request waits and lists its blocker; once nothing blocks,
 * the Administrator confirms it behind the step-up, every participant erases the account, its Products go, and
 * the sign-in grant's revocation is queued with the kept token sealed.
 */
class DataRequestIT extends IntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private ApplicationContext context;

	@Autowired
	private Accounts accounts;

	@Autowired
	private TokenCipher cipher;

	private HeldCrewJob world;

	private Administrator ha;

	@BeforeEach
	void aRunningJobAndAnAdministrator() {
		this.world = HeldCrewJob.create(this.jdbc, this.clock.instant());
		this.ha = AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
	}

	@Test
	void aStudentDownloadsOnlyTheirOwnData() throws Exception {
		this.mvc.perform(get("/api/v1/account/data/export").with(as(this.world.vy(), Role.STUDENT)))
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Disposition", containsString("attachment")))
			.andExpect(header().string("Content-Disposition", containsString("vibefix-du-lieu-")))
			.andExpect(jsonPath("$.accountId").value(this.world.vy().toString()))
			.andExpect(jsonPath("$.sections").isArray())
			.andExpect(content().string(not(containsString(this.world.minh().toString()))));
		assertThat(count("SELECT count(*) FROM audit.audit_entry WHERE action = 'identity.data_exported' AND actor_account_id = '"
				+ this.world.vy() + "'")).isOne();
		this.mvc.perform(get("/api/v1/account/data/export").with(admin())).andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/account/data").with(as(this.world.vy(), Role.STUDENT)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.deletionRequestedAt").doesNotExist())
			.andExpect(jsonPath("$.tokenKept").value(false));
	}

	@Test
	void theRequestWaitsForTheRunningJobAndThenTheAdministratorErasesTheAccount() throws Exception {
		String token = "gho_" + HexFormat.of().formatHex(randomBytes());
		this.accounts.storeCustomerToken(this.world.lan(), this.cipher.seal(token, this.world.lan().toString()),
				this.clock.instant());
		erase().andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.reason").value("NO_DELETION_REQUEST"));

		this.mvc.perform(post("/api/v1/account/deletion-request").with(as(this.world.lan(), Role.CUSTOMER)).with(csrf()))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.deletionRequestedAt").exists())
			.andExpect(jsonPath("$.blockers[0].reason").value("JOB_RUNNING"))
			.andExpect(jsonPath("$.blockers[0].jobId").value(this.world.job().toString()))
			.andExpect(jsonPath("$.tokenKept").value(true));
		this.mvc.perform(post("/api/v1/account/deletion-request").with(as(this.world.lan(), Role.CUSTOMER)).with(csrf()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("ALREADY_REQUESTED"));
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type = 'identity.deletion_requested' AND account_id = '"
				+ this.ha.accountId() + "'")).isOne();
		this.mvc.perform(get("/api/v1/admin/users/" + this.world.lan()).with(admin()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.deletionRequest.blockers[0].reason").value("JOB_RUNNING"));
		erase().andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("ERASURE_BLOCKED"))
			.andExpect(jsonPath("$.blockers[0].jobId").value(this.world.job().toString()));
		assertThat(count("SELECT count(*) FROM identity.account WHERE id = '" + this.world.lan() + "' AND erased_at IS NULL"))
			.isOne();

		this.jdbc.sql("UPDATE job.job SET state = 'CLOSED', closed_at = now() WHERE id = :id").param("id", this.world.job()).update();
		erase().andExpect(status().isOk()).andExpect(jsonPath("$.erased").value(true));

		assertThat(count("SELECT count(*) FROM identity.account WHERE id = '" + this.world.lan() + "' AND erased_at IS NOT NULL"))
			.isOne();
		assertThat(count("SELECT count(*) FROM identity.deletion_request WHERE account_id = '" + this.world.lan()
				+ "' AND completed_at IS NOT NULL")).isOne();
		assertThat(count("SELECT count(*) FROM identity.customer_token WHERE account_id = '" + this.world.lan() + "'")).isZero();
		String sealed = this.jdbc.sql("""
				SELECT personal_data::text FROM operations.external_operation WHERE type = 'identity.revoke_oauth_grant'
				""").query(String.class).single();
		assertThat(sealed).contains("ciphertext").doesNotContain(token);
		assertThat(count("SELECT count(*) FROM audit.audit_entry WHERE action = 'identity.account_erased'")).isOne();
		await().atMost(Duration.ofSeconds(15))
			.until(() -> count("SELECT count(*) FROM platform.event_publication WHERE completion_date IS NULL") == 0);
		assertThat(count("SELECT count(*) FROM product.product WHERE id = '" + this.world.product() + "'")).isZero();
		assertThat(count("SELECT count(*) FROM audit.audit_entry WHERE action = 'product.deleted' AND actor_type = 'SYSTEM'"))
			.isOne();
		erase().andExpect(status().isNotFound());
	}

	/** Each confirmation one minute later, since a step-up code is accepted once per time step. */
	private ResultActions erase() throws Exception {
		this.clock.advance(Duration.ofMinutes(1));
		return this.mvc.perform(post("/api/v1/admin/users/" + this.world.lan() + "/erase").with(admin()).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"stepUpCode\":\"" + AdministratorTestSupport.code(this.ha, this.clock.instant()) + "\"}"));
	}

	private static byte[] randomBytes() {
		byte[] bytes = new byte[18];
		ThreadLocalRandom.current().nextBytes(bytes);
		return bytes;
	}

	private long count(String sql) {
		return this.jdbc.sql(sql).query(Long.class).single();
	}

	private RequestPostProcessor admin() {
		return IdentityTestSupport.signedIn(this.sessions, this.ha.accountId(), Role.ADMINISTRATOR, this.clock.instant());
	}

	private RequestPostProcessor as(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

}
