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

import vn.vibefix.identity.internal.AdministratorTestSupport;
import vn.vibefix.identity.internal.AdministratorTestSupport.Administrator;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.shared.Role;
import vn.vibefix.support.HeldCrewJob;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.timers.internal.DeadlinePoller;

/**
 * Story 15.1 (FR-52, AD-7): the other side of a Dispute gets the Job's copied statement period with its
 * to-do, sends one statement, and Administrators are told; a decision waits until a statement arrived or
 * the period passed, and ADM-4 flags Disputes undecided 7 days after they opened.
 */
class DisputeStatementIT extends IntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private ApplicationContext context;

	@Autowired
	private DeadlinePoller poller;

	private HeldCrewJob world;

	private Administrator ha;

	@BeforeEach
	void anActiveJobAndAnAdministrator() {
		this.world = HeldCrewJob.create(this.jdbc, this.clock.instant());
		this.ha = AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
	}

	@Test
	void theOtherSideStatesOnceAndTheDecisionWaitsForIt() throws Exception {
		UUID dispute = open();
		Instant due = this.jdbc.sql("SELECT deadline_at FROM notification.todo WHERE todo_key = 'dispute.submit_statement' AND account_id = :mentor")
			.param("mentor", this.world.hung()).query(Timestamp.class).single().toInstant();
		assertThat(due).isEqualTo(this.clock.instant().plus(Duration.ofDays(5)));
		assertThat(count("SELECT count(*) FROM notification.todo WHERE todo_key = 'dispute.submit_statement' AND account_id = '"
				+ this.world.lan() + "'")).as("the opener states nothing").isZero();

		state(this.world.lan(), Role.CUSTOMER).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.reason").value("NOT_THE_OTHER_SIDE"));
		this.mvc.perform(post("/api/v1/admin/disputes/" + dispute + "/take").with(admin()).with(csrf())).andExpect(status().isOk());
		decide(dispute).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.reason").value("STATEMENT_PERIOD_OPEN"));
		this.mvc.perform(get("/api/v1/admin/disputes/" + dispute).with(admin()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.decidable").value(false));

		state(this.world.hung(), Role.MENTOR).andExpect(status().isOk())
			.andExpect(jsonPath("$.open.statements[0].side").value("MENTOR"))
			.andExpect(jsonPath("$.open.statements[0].mine").value(true))
			.andExpect(jsonPath("$.open.canState").value(false));
		state(this.world.hung(), Role.MENTOR).andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("STATEMENT_ALREADY_SENT"));
		assertThat(count("SELECT count(*) FROM notification.todo WHERE todo_key = 'dispute.submit_statement' AND closed_at IS NULL"))
			.isZero();
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type = 'dispute.statement_submitted' AND account_id = '"
				+ this.ha.accountId() + "'")).isOne();
		this.mvc.perform(get("/api/v1/boards/" + this.world.job() + "/dispute").with(as(this.world.lan(), Role.CUSTOMER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.open.statements[0].text").value("Khách đổi yêu cầu giữa chừng."));
		this.mvc.perform(get("/api/v1/admin/disputes/" + dispute).with(admin()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.decidable").value(true))
			.andExpect(jsonPath("$.statements[0].side").value("MENTOR"));

		this.mvc.perform(get("/api/v1/admin/disputes").with(admin()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].hasStatement").value(true))
			.andExpect(jsonPath("$[0].undecidedLong").value(false));
		this.clock.advance(Duration.ofDays(8));
		this.mvc.perform(get("/api/v1/admin/disputes").with(admin()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].undecidedLong").value(true));
	}

	@Test
	void afterThePeriodTheDecisionNoLongerWaits() throws Exception {
		UUID dispute = open();
		this.clock.advance(Duration.ofDays(5).plusMinutes(1));
		this.poller.runDue();

		assertThat(count("SELECT count(*) FROM notification.todo WHERE todo_key = 'dispute.submit_statement' AND closed_at IS NULL"))
			.isZero();
		assertThat(text("SELECT status FROM timers.deadline WHERE kind = 'dispute.statement_period' AND subject_id = '" + dispute + "'"))
			.isEqualTo("FIRED");
		this.mvc.perform(get("/api/v1/admin/disputes/" + dispute).with(admin()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.decidable").value(true))
			.andExpect(jsonPath("$.statements").isEmpty());
	}

	private UUID open() throws Exception {
		this.mvc.perform(post("/api/v1/boards/" + this.world.job() + "/disputes").with(as(this.world.lan(), Role.CUSTOMER)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"reason\":\"Giai đoạn 1 chưa đạt tiêu chí\"}"))
			.andExpect(status().isCreated());
		return this.jdbc.sql("SELECT id FROM dispute.dispute WHERE job_id = :job").param("job", this.world.job()).query(UUID.class)
			.single();
	}

	private ResultActions state(UUID account, Role role) throws Exception {
		return this.mvc.perform(post("/api/v1/boards/" + this.world.job() + "/dispute/statement").with(as(account, role)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"text\":\"Khách đổi yêu cầu giữa chừng.\"}"));
	}

	private ResultActions decide(UUID dispute) throws Exception {
		return this.mvc.perform(post("/api/v1/admin/disputes/" + dispute + "/decision").with(admin()).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"releaseVnd\":500000,\"refundVnd\":500000,\"nextState\":\"END\",\"reasoning\":\"Hai bên cùng có lỗi\","
					+ "\"stepUpCode\":\"" + AdministratorTestSupport.code(this.ha, this.clock.instant()) + "\"}"));
	}

	private long count(String sql) {
		return this.jdbc.sql(sql).query(Long.class).single();
	}

	private String text(String sql) {
		return this.jdbc.sql(sql).query(String.class).single();
	}

	private RequestPostProcessor admin() {
		return IdentityTestSupport.signedIn(this.sessions, this.ha.accountId(), Role.ADMINISTRATOR, this.clock.instant());
	}

	private RequestPostProcessor as(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

}
