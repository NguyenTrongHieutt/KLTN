package vn.vibefix.job.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.team.internal.TeamTestSupport;
import vn.vibefix.timers.internal.DeadlinePoller;

/**
 * FR-39: a person's closure proposal that is declined, or not answered within the copied days, tells its
 * proposer and lets either side open a Dispute from it; the System's own proposal confirms by itself.
 */
class RefusedClosureIT extends IntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private DeadlinePoller poller;

	private UUID lan;

	private UUID hung;

	@Test
	void anUnansweredProposalTellsItsProposerAndOpensTheWayToADispute() throws Exception {
		UUID job = world();
		command(this.lan, Role.CUSTOMER, job, "").andExpect(status().isCreated());
		read(this.hung, Role.MENTOR, job).andExpect(jsonPath("$.disputable").value(false));
		this.clock.advance(Duration.ofDays(6));
		this.poller.runDue();
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM notification.notification WHERE type = 'job.closure_unanswered' AND account_id = :lan
				""").param("lan", this.lan).query(Long.class).single()).isEqualTo(1);
		read(this.hung, Role.MENTOR, job).andExpect(jsonPath("$.disputable").value(true))
			.andExpect(jsonPath("$.proposal.unanswered").value(true))
			.andExpect(jsonPath("$.proposal.status").value("OPEN"));
		read(this.lan, Role.CUSTOMER, job).andExpect(jsonPath("$.disputable").value(true));
	}

	@Test
	void aDeclinedProposalLetsEitherSideDispute() throws Exception {
		UUID job = world();
		command(this.lan, Role.CUSTOMER, job, "").andExpect(status().isCreated());
		command(this.hung, Role.MENTOR, job, "/decline").andExpect(status().isOk())
			.andExpect(jsonPath("$.disputable").value(true));
		read(this.lan, Role.CUSTOMER, job).andExpect(jsonPath("$.disputable").value(true));
		this.clock.advance(Duration.ofDays(6));
		this.poller.runDue();
		assertThat(this.jdbc.sql("SELECT count(*) FROM notification.notification WHERE type = 'job.closure_unanswered'")
			.query(Long.class).single()).isZero();
	}

	private ResultActions command(UUID account, Role role, UUID job, String path) throws Exception {
		long version = this.jdbc.sql("SELECT version FROM job.job WHERE id = :id").param("id", job).query(Long.class).single();
		return this.mvc.perform(post("/api/v1/boards/" + job + "/closure" + path).with(as(account, role)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"version\":" + version + "}"));
	}

	private ResultActions read(UUID account, Role role, UUID job) throws Exception {
		return this.mvc.perform(get("/api/v1/boards/" + job + "/closure").with(as(account, role)));
	}

	/** An Active Job with no Milestone left to fund or hand over. */
	private UUID world() {
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		this.hung = TeamTestSupport.mentor(this.jdbc, "Đỗ Mạnh Hùng",
				List.of(TeamTestSupport.REACT), "React");
		UUID product = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO product.product (id, customer_account_id, name, description, ai_tools, repository_github_id,
				    repository_full_name, repository_owner_type, repository_default_branch, public_notice_version,
				    copy_permission_version, detection_notice_version, acknowledged_at, created_at)
				VALUES (:id, :owner, 'Sổ đặt bàn', 'Đặt bàn.', ARRAY['LOVABLE'], :github, 'lan/so-dat-ban', 'User', 'main',
				    '1.0', '1.0', '1.0', now(), now())
				""").param("id", product).param("owner", this.lan)
			.param("github", ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE)).update();
		UUID job = UUID.randomUUID();
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, budget, deadline, rules, selection_days, opened_at, lapses_at, mentor_account_id,
				    selected_application_id, selected_at, version, created_at, updated_at)
				VALUES (:id, :product, :owner, 'ACTIVE', 'Sổ đặt bàn', 'Lỗi', 'Đúng', 'Khách', ARRAY['BUG_FIX'],
				    'UNDER_2M', DATE '2026-12-01', '{"CLOSURE_CONFIRMATION_DAYS": 5}'::jsonb, 14, :now, :now, :mentor,
				    :application, :now, 6, :now, :now)
				""").param("id", job).param("product", product).param("owner", this.lan).param("mentor", this.hung)
			.param("application", UUID.randomUUID()).param("now", now).update();
		return job;
	}

	private RequestPostProcessor as(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

}
