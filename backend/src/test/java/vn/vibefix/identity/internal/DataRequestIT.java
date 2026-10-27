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

import org.jspecify.annotations.Nullable;
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
			.andExpect(jsonPath("$.blockers[0].name").value("Sửa lỗi đặt bàn"))
			.andExpect(jsonPath("$.tokenKept").value(true));
		this.mvc.perform(post("/api/v1/account/deletion-request").with(as(this.world.lan(), Role.CUSTOMER)).with(csrf()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("ALREADY_REQUESTED"));
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type = 'identity.deletion_requested' AND account_id = '"
				+ this.ha.accountId() + "'")).isOne();
		this.mvc.perform(get("/api/v1/admin/users/" + this.world.lan()).with(admin()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.deletionRequest.blockers[0].reason").value("JOB_RUNNING"))
			.andExpect(jsonPath("$.deletionRequest.blockers[0].name").value("Sửa lỗi đặt bàn"));
		erase().andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("ERASURE_BLOCKED"))
			.andExpect(jsonPath("$.blockers[0].jobId").value(this.world.job().toString()))
			.andExpect(jsonPath("$.blockers[0].name").value("Sửa lỗi đặt bàn"));
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

	@Test
	void aGoogleAccountRequestsDeletionUnderItsGoogleNameAndTheAdministratorErasesIt() throws Exception {
		UUID google = IdentityTestSupport.insertGoogleAccount(this.jdbc, "google-xoa", "3.1");

		this.mvc.perform(post("/api/v1/account/deletion-request").with(as(google, null)).with(csrf()))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.deletionRequestedAt").exists());

		assertThat(this.jdbc.sql("""
				SELECT params ->> 'name' FROM notification.notification
				WHERE type = 'identity.deletion_requested' AND account_id = :admin
				""").param("admin", this.ha.accountId()).query(String.class).single()).isEqualTo("Người dùng google-xoa");
		erase(google).andExpect(status().isOk()).andExpect(jsonPath("$.erased").value(true));
		assertThat(count("SELECT count(*) FROM identity.account WHERE id = '" + google + "' AND erased_at IS NOT NULL"))
			.isOne();
	}

	/**
	 * Story 20.4 (review 2026-10-27 A-19): a former member whose window ended after a Milestone was funded joins its
	 * split, so the request waits for it while it is neither released nor cancelled, with the Job's title.
	 */
	@Test
	void aFormerMembersRequestWaitsForAMilestoneFundedDuringTheirWindow() throws Exception {
		this.jdbc.sql("""
				UPDATE job.crew_row SET status = 'RELEASED', released_at = now(), released_reason = 'LEFT',
				    window_end = now() + INTERVAL '1 hour'
				WHERE member_account_id = :vy
				""").param("vy", this.world.vy()).update();
		// A second Held Milestone of the same Job: the Job is listed once.
		UUID second = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO job.milestone (id, job_id, position, title, price_vnd, revision_rounds, created_at, state, funded_at,
				    crew_terms_version_no)
				VALUES (:id, :job, 1, 'Giai đoạn 2', 500000, 2, now(), 'FUNDED', now(), 1)
				""").param("id", second).param("job", this.world.job()).update();

		this.mvc.perform(post("/api/v1/account/deletion-request").with(as(this.world.vy(), Role.STUDENT)).with(csrf()))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.blockers.length()").value(1))
			.andExpect(jsonPath("$.blockers[0].module").value("job"))
			.andExpect(jsonPath("$.blockers[0].reason").value("MILESTONE_PENDING"))
			.andExpect(jsonPath("$.blockers[0].jobId").value(this.world.job().toString()))
			.andExpect(jsonPath("$.blockers[0].name").value("Sửa lỗi đặt bàn"));
		erase(this.world.vy()).andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.code").value("GUARD_FAILED"))
			.andExpect(jsonPath("$.reason").value("ERASURE_BLOCKED"))
			.andExpect(jsonPath("$.blockers[0].reason").value("MILESTONE_PENDING"))
			.andExpect(jsonPath("$.blockers[0].name").value("Sửa lỗi đặt bàn"));
		this.mvc.perform(get("/api/v1/admin/users/" + this.world.vy()).with(admin()))
			.andExpect(jsonPath("$.deletionRequest.blockers[0].reason").value("MILESTONE_PENDING"));

		// A window that ended before the Milestone was funded joins nothing.
		this.jdbc.sql("UPDATE job.crew_row SET window_end = now() - INTERVAL '1 day' WHERE member_account_id = :vy")
			.param("vy", this.world.vy()).update();
		this.mvc.perform(get("/api/v1/account/data").with(as(this.world.vy(), Role.STUDENT)))
			.andExpect(jsonPath("$.blockers.length()").value(0));
		this.jdbc.sql("UPDATE job.crew_row SET window_end = now() + INTERVAL '1 hour' WHERE member_account_id = :vy")
			.param("vy", this.world.vy()).update();
		// Once the Milestones are released or cancelled, nothing waits.
		this.jdbc.sql("UPDATE job.milestone SET state = 'ACCEPTED', accepted_at = now() WHERE id = :id")
			.param("id", this.world.milestone()).update();
		this.mvc.perform(get("/api/v1/account/data").with(as(this.world.vy(), Role.STUDENT)))
			.andExpect(jsonPath("$.blockers.length()").value(1));
		this.jdbc.sql("UPDATE job.milestone SET state = 'CANCELLED' WHERE id = :id").param("id", second).update();
		this.mvc.perform(get("/api/v1/account/data").with(as(this.world.vy(), Role.STUDENT)))
			.andExpect(jsonPath("$.blockers.length()").value(0));
	}

	/** A Team Issue not ended keeps its raiser's request waiting, with the Job's title; an ended one blocks nothing. */
	@Test
	void aRaisersRequestWaitsForTheirTeamIssueUntilItEnds() throws Exception {
		this.jdbc.sql("UPDATE job.job SET state = 'CLOSED', closed_at = now() WHERE id = :id").param("id", this.world.job()).update();
		UUID issue = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO dispute.team_issue (id, job_id, product_id, subject, milestone_id, milestone_number,
				    raiser_account_id, mentor_account_id, reason, phase, answer_days, escalation_days, raised_at, answer_due_at)
				VALUES (:id, :job, :product, 'SHARE', :milestone, 1, :vy, :hung, 'Phần chia chưa đúng', 'ANSWER', 5, 5, now(),
				    now() + INTERVAL '5 days')
				""").param("id", issue).param("job", this.world.job()).param("product", this.world.product())
			.param("milestone", this.world.milestone()).param("vy", this.world.vy()).param("hung", this.world.hung()).update();

		this.mvc.perform(post("/api/v1/account/deletion-request").with(as(this.world.vy(), Role.STUDENT)).with(csrf()))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.blockers.length()").value(1))
			.andExpect(jsonPath("$.blockers[0].module").value("dispute"))
			.andExpect(jsonPath("$.blockers[0].reason").value("TEAM_ISSUE_OPEN"))
			.andExpect(jsonPath("$.blockers[0].jobId").value(this.world.job().toString()))
			.andExpect(jsonPath("$.blockers[0].name").value("Sửa lỗi đặt bàn"));

		this.jdbc.sql("UPDATE dispute.team_issue SET phase = 'ENDED', ended_at = now(), ending = 'MOOT' WHERE id = :id")
			.param("id", issue).update();
		this.mvc.perform(get("/api/v1/account/data").with(as(this.world.vy(), Role.STUDENT)))
			.andExpect(jsonPath("$.blockers.length()").value(0));
	}

	/** Story 20.4: an open Dispute on a Job whose Customer or Mentor the person is keeps the request waiting. */
	@Test
	void theCustomersRequestWaitsForAnOpenDispute() throws Exception {
		this.jdbc.sql("UPDATE job.job SET state = 'CLOSED', closed_at = now() WHERE id = :id").param("id", this.world.job()).update();
		UUID dispute = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO dispute.dispute (id, job_id, product_id, opened_by, opener_account_id, reason, concern, job_state,
				    status, opened_at)
				VALUES (:id, :job, :product, 'CUSTOMER', :lan, 'Giai đoạn chưa đạt', 'NEXT_STATE', 'ACTIVE', 'OPEN', now())
				""").param("id", dispute).param("job", this.world.job()).param("product", this.world.product())
			.param("lan", this.world.lan()).update();

		this.mvc.perform(post("/api/v1/account/deletion-request").with(as(this.world.lan(), Role.CUSTOMER)).with(csrf()))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.blockers.length()").value(1))
			.andExpect(jsonPath("$.blockers[0].module").value("dispute"))
			.andExpect(jsonPath("$.blockers[0].reason").value("DISPUTE_OPEN"))
			.andExpect(jsonPath("$.blockers[0].jobId").value(this.world.job().toString()))
			.andExpect(jsonPath("$.blockers[0].name").value("Sửa lỗi đặt bàn"));
		erase().andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("ERASURE_BLOCKED"))
			.andExpect(jsonPath("$.blockers[0].reason").value("DISPUTE_OPEN"));
		this.mvc.perform(post("/api/v1/account/deletion-request").with(as(this.world.hung(), Role.MENTOR)).with(csrf()))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.blockers[0].reason").value("DISPUTE_OPEN"));
		this.mvc.perform(post("/api/v1/account/deletion-request").with(as(this.world.minh(), Role.STUDENT)).with(csrf()))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.blockers.length()").value(0));

		this.jdbc.sql("UPDATE dispute.dispute SET status = 'DECIDED', decided_at = now() WHERE id = :id").param("id", dispute)
			.update();
		this.mvc.perform(get("/api/v1/account/data").with(as(this.world.lan(), Role.CUSTOMER)))
			.andExpect(jsonPath("$.blockers.length()").value(0));
	}

	@Test
	void anAdministratorsAccountIsNeverErased() throws Exception {
		erase(this.ha.accountId()).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.reason").value("ADMINISTRATOR_ACCOUNT"));

		assertThat(count("SELECT count(*) FROM identity.account WHERE id = '" + this.ha.accountId()
				+ "' AND erased_at IS NULL")).isOne();
	}

	private ResultActions erase() throws Exception {
		return erase(this.world.lan());
	}

	/** Each confirmation one minute later, since a step-up code is accepted once per time step. */
	private ResultActions erase(UUID account) throws Exception {
		this.clock.advance(Duration.ofMinutes(1));
		return this.mvc.perform(post("/api/v1/admin/users/" + account + "/erase").with(admin()).with(csrf())
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

	private RequestPostProcessor as(UUID account, @Nullable Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

}
