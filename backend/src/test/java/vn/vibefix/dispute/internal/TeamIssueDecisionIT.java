package vn.vibefix.dispute.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

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
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.identity.internal.AdministratorTestSupport;
import vn.vibefix.identity.internal.AdministratorTestSupport.Administrator;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.payment.Ledger;
import vn.vibefix.payment.Releases;
import vn.vibefix.support.HeldCrewJob;
import vn.vibefix.support.IntegrationTest;

/**
 * Story 12.3 (FR-63, FR-79, AD-20, AD-40): an Administrator takes an escalated Team Issue, which pushes a
 * read grant for the Crew Chat, the raiser's Work Item histories, and the Crew Terms versions; another
 * taking it over ends the old grant at once. The assignee decides under the step-up, the decision is
 * executed exactly, and the grant ends 30 days after it.
 */
class TeamIssueDecisionIT extends IntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private TransactionTemplate transactions;

	@Autowired
	private Releases releases;

	@Autowired
	private Ledger ledger;

	@Autowired
	private ApplicationContext context;

	private HeldCrewJob world;

	private Administrator ha;

	/** A second Administrator, without a step-up secret: only the first is enrolled through the bootstrap. */
	private UUID minh;

	@BeforeEach
	void aHeldMilestoneAndTwoAdministrators() {
		this.world = HeldCrewJob.create(this.jdbc, this.clock.instant());
		this.ha = AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		this.minh = IdentityTestSupport.insertAccount(this.jdbc, Role.ADMINISTRATOR, null);
		this.jdbc.sql("""
				INSERT INTO identity.administrator_credential (account_id, email, display_name, password_hash,
				    totp_key_version, totp_nonce, totp_ciphertext, created_at)
				VALUES (:id, 'minh.quan@vibefix.test', 'Minh Quân', '$argon2id$x', 1, '\\x00'::bytea, '\\x00'::bytea, now())
				""").param("id", this.minh).update();
	}

	@Test
	void theAssigneeDecidesAShareFromTheEvidenceAndTheDecisionIsExecutedExactly() throws Exception {
		UUID chat = UUID.randomUUID();
		this.jdbc.sql("INSERT INTO chat.chat (id, job_id, product_id, kind, opened_at) VALUES (:id, :job, :product, 'CREW', now())")
			.param("id", chat).param("job", this.world.job()).param("product", this.world.product()).update();
		UUID issue = raise(this.world.vy(), "SHARE", this.world.milestone());
		this.mvc.perform(get("/api/v1/admin/team-issues/" + issue).with(admin(this.ha.accountId()))).andExpect(status().isNotFound());
		release();
		answer(issue).andExpect(status().isOk());
		choose(this.world.vy(), issue, "escalate").andExpect(status().isOk());

		this.mvc.perform(get("/api/v1/admin/team-issues").with(admin(this.ha.accountId())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].id").value(issue.toString()))
			.andExpect(jsonPath("$[0].phase").value("ESCALATED"))
			.andExpect(jsonPath("$[0].assigned").value(false));
		this.mvc.perform(get("/api/v1/admin/jobs/" + this.world.job() + "/crew-terms").with(admin(this.minh)))
			.andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/crew-chats/" + chat + "/messages").with(admin(this.minh))).andExpect(status().isNotFound());

		take(this.minh, issue).andExpect(status().isOk())
			.andExpect(jsonPath("$.assignedToMe").value(true))
			.andExpect(jsonPath("$.split.capPoints").value(20))
			.andExpect(jsonPath("$.raiserName").value("Trần Thảo Vy"));
		for (String table : new String[] { "chat.admin_read_grant", "work.admin_read_grant", "job.admin_read_grant" }) {
			assertThat(count("SELECT count(*) FROM " + table + " WHERE reason_id = '" + issue + "' AND ends_at IS NULL"))
				.as(table).isOne();
		}
		// Story 20.3: each party's role is their role in this Job (roleOf), never which directory describes them.
		this.mvc.perform(get("/api/v1/admin/jobs/" + this.world.job() + "/crew-terms").with(admin(this.minh)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.versions[0].shares.length()").value(3))
			.andExpect(jsonPath("$.versions[0].shares[?(@.role == 'MENTOR')].name").value("Đỗ Mạnh Hùng"))
			.andExpect(jsonPath("$.versions[0].shares[?(@.name == 'Trần Thảo Vy')].role").value("STUDENT"))
			.andExpect(jsonPath("$.versions[0].shares[?(@.name == 'Nguyễn Văn Minh')].role").value("STUDENT"));
		this.mvc.perform(get("/api/v1/crew-chats/" + chat + "/messages").with(admin(this.minh))).andExpect(status().isOk());
		assertThat(count("SELECT count(*) FROM audit.audit_entry WHERE action = 'job.admin_read'")).isOne();

		take(this.ha.accountId(), issue).andExpect(status().isOk());
		assertThat(count("SELECT count(*) FROM job.admin_read_grant WHERE reason_id = '" + issue + "' AND ends_at IS NULL"))
			.as("Minh's grant ended, Hà's is open").isOne();
		this.mvc.perform(get("/api/v1/admin/jobs/" + this.world.job() + "/crew-terms").with(admin(this.minh)))
			.andExpect(status().isNotFound());
		decide(this.minh, "000000", issue, "\"points\":10").andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("NOT_ASSIGNED"));
		decide(this.ha, issue, "\"points\":25").andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("SHARE_TRANSFER_TOO_LARGE"))
			.andExpect(jsonPath("$.cap").value(20));

		decide(this.ha, issue, "\"points\":10").andExpect(status().isOk())
			.andExpect(jsonPath("$.phase").value("ENDED"))
			.andExpect(jsonPath("$.ending").value("DECIDED"))
			.andExpect(jsonPath("$.decision.points").value(10))
			.andExpect(jsonPath("$.decision.reasoning").value("Vy làm phần lớn việc"));
		assertThat(this.jdbc.sql("SELECT amount_vnd FROM payment.ledger_entry WHERE operation_key = :key")
			.param("key", "RELEASE:" + issue + ":" + this.world.vy()).query(Long.class).single()).isEqualTo(500_000);
		assertThat(this.jdbc.sql("SELECT amount_vnd FROM payment.ledger_entry WHERE operation_key = :key")
			.param("key", "RELEASE:" + issue + ":" + this.world.hung()).query(Long.class).single()).isEqualTo(100_000);
		assertThat(this.ledger.releasedTotal(this.world.milestone())).isEqualTo(HeldCrewJob.PRICE);
		assertThat(notices("dispute.team_issue_decided", this.world.vy())).isOne();
		assertThat(notices("dispute.team_issue_decided", this.world.hung())).isOne();
		decide(this.ha, issue, "\"points\":5").andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("ALREADY_DECIDED"));

		Instant ends = this.jdbc.sql("SELECT ends_at FROM chat.admin_read_grant WHERE reason_id = :issue AND admin_account_id = :ha")
			.param("issue", issue).param("ha", this.ha.accountId()).query(Timestamp.class).single().toInstant();
		assertThat(ends).isEqualTo(this.clock.instant().plus(Duration.ofDays(30)));
		this.mvc.perform(get("/api/v1/crew-chats/" + chat + "/messages").with(admin(this.ha.accountId()))).andExpect(status().isOk());
		this.clock.advance(Duration.ofDays(31));
		this.mvc.perform(get("/api/v1/crew-chats/" + chat + "/messages").with(admin(this.ha.accountId())))
			.andExpect(status().isNotFound());
	}

	@Test
	void aRatingIsRemovedByTheDecisionAndItsEvidenceIsReadOnlyByTheAssignee() throws Exception {
		UUID rating = this.world.rating(this.jdbc, this.world.vy(), this.clock.instant().minus(Duration.ofDays(1)));
		UUID issue = raise(this.world.vy(), "RATING", rating);
		this.mvc.perform(post("/api/v1/boards/" + this.world.job() + "/team-issues/" + issue + "/answer")
			.with(as(this.world.hung(), Role.MENTOR))
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"kind\":\"KEEP\"}")).andExpect(status().isOk());
		choose(this.world.vy(), issue, "escalate").andExpect(status().isOk());

		take(this.ha.accountId(), issue).andExpect(status().isOk()).andExpect(jsonPath("$.rating.stars").value(2));
		this.mvc.perform(get("/api/v1/admin/team-issues/" + issue).with(admin(this.minh)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.rating").doesNotExist());
		assertThat(count("SELECT count(*) FROM audit.audit_entry WHERE action = 'reputation.admin_read'")).isOne();
		decide(this.ha, issue, "\"points\":10").andExpect(status().isBadRequest());

		decide(this.ha, issue, "\"removeRating\":true").andExpect(status().isOk())
			.andExpect(jsonPath("$.decision.ratingRemoved").value(true));
		assertThat(text("SELECT hidden_reason FROM reputation.rating WHERE id = '" + rating + "'"))
			.isEqualTo(TeamIssueEndings.REMOVED_REASON);
		assertThat(text("SELECT ending FROM dispute.team_issue WHERE id = '" + issue + "'")).isEqualTo("DECIDED");
	}

	private UUID raise(UUID student, String subject, UUID target) throws Exception {
		String field = "SHARE".equals(subject) ? "milestoneId" : "ratingId";
		String body = this.mvc.perform(post("/api/v1/team-issues").with(as(student, Role.STUDENT)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"jobId\":\"" + this.world.job() + "\",\"subject\":\"" + subject + "\",\"" + field + "\":\"" + target
					+ "\",\"reason\":\"Chưa công bằng.\"}"))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return UUID.fromString(body.replaceAll("^\\{\"id\":\"([^\"]+)\".*", "$1"));
	}

	private ResultActions answer(UUID issue) throws Exception {
		return this.mvc.perform(post("/api/v1/boards/" + this.world.job() + "/team-issues/" + issue + "/answer")
			.with(as(this.world.hung(), Role.MENTOR))
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"kind\":\"KEEP\"}"));
	}

	private ResultActions choose(UUID student, UUID issue, String choice) throws Exception {
		return this.mvc.perform(post("/api/v1/team-issues/" + issue + "/" + choice).with(as(student, Role.STUDENT)).with(csrf()));
	}

	private ResultActions take(UUID administrator, UUID issue) throws Exception {
		return this.mvc.perform(post("/api/v1/admin/team-issues/" + issue + "/take").with(admin(administrator)).with(csrf()));
	}

	private ResultActions decide(Administrator administrator, UUID issue, String outcome) throws Exception {
		return decide(administrator.accountId(), AdministratorTestSupport.code(administrator, this.clock.instant()), issue,
				outcome);
	}

	private ResultActions decide(UUID administrator, String code, UUID issue, String outcome) throws Exception {
		return this.mvc.perform(post("/api/v1/admin/team-issues/" + issue + "/decision").with(admin(administrator)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{" + outcome + ",\"reasoning\":\"Vy làm phần lớn việc\",\"stepUpCode\":\""
					+ code + "\"}"));
	}

	private void release() {
		this.transactions.executeWithoutResult(
				status -> this.releases.release(this.world.job(), this.world.milestone(), UUID.randomUUID()));
	}

	private long notices(String type, UUID account) {
		return this.jdbc.sql("SELECT count(*) FROM notification.notification WHERE type = :type AND account_id = :account")
			.param("type", type).param("account", account).query(Long.class).single();
	}

	private long count(String sql) {
		return this.jdbc.sql(sql).query(Long.class).single();
	}

	private String text(String sql) {
		return this.jdbc.sql(sql).query(String.class).single();
	}

	private RequestPostProcessor admin(UUID administrator) {
		return IdentityTestSupport.signedIn(this.sessions, administrator, Role.ADMINISTRATOR, this.clock.instant());
	}

	private RequestPostProcessor as(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

}
