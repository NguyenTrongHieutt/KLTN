package vn.vibefix.dispute.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import vn.vibefix.dispute.DisputeQueue;
import vn.vibefix.identity.internal.AdministratorTestSupport;
import vn.vibefix.identity.internal.AdministratorTestSupport.Administrator;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.reputation.CrewRatings;
import vn.vibefix.support.HeldCrewJob;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.timers.internal.DeadlinePoller;

/**
 * Story 12.2 (FR-79, AD-40): the Mentor answers a Team Issue once, keeping the Rating or withdrawing it, which changes
 * nothing yet; the raiser accepts it or asks an Administrator; the escalation period lapses; or the issue becomes moot.
 * No ending moves money.
 */
class TeamIssueEndingIT extends IntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private TransactionTemplate transactions;

	@Autowired
	private DeadlinePoller poller;

	@Autowired
	private DisputeQueue queue;

	@Autowired
	private CrewRatings ratings;

	@Autowired
	private ApplicationContext context;

	private HeldCrewJob world;

	private UUID vyRating;

	@BeforeEach
	void aHeldMilestoneOfACrewOfThreeAndVysRating() {
		this.world = HeldCrewJob.create(this.jdbc, this.clock.instant());
		this.vyRating = this.world.rating(this.jdbc, this.world.vy(), this.clock.instant().minus(Duration.ofDays(1)));
	}

	@Test
	void anAcceptedKeepEndsTheIssueWithTheRatingKeptAndAnAnswerIsGivenOnce() throws Exception {
		UUID issue = raise(this.world.vy(), this.vyRating);
		answer(issue, "TRANSFER").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION"));
		answer(issue, "KEEP").andExpect(status().isOk())
			.andExpect(jsonPath("$.phase").value("ESCALATION"))
			.andExpect(jsonPath("$.answerKind").value("KEEP"))
			.andExpect(jsonPath("$.answerPoints").doesNotExist());
		answer(issue, "WITHDRAW_RATING").andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("ALREADY_ANSWERED"));
		assertThat(notices("dispute.team_issue_answered", this.world.vy())).isOne();
		assertThat(openTodos("dispute.decide_team_issue_answer", this.world.vy())).isOne();
		assertThat(openTodos("dispute.answer_team_issue", this.world.hung())).isZero();

		choose(this.world.vy(), issue, "accept").andExpect(status().isOk())
			.andExpect(jsonPath("$.phase").value("ENDED"))
			.andExpect(jsonPath("$.ending").value("ACCEPTED"))
			.andExpect(jsonPath("$.outcomeRatingRemoved").value(false));
		assertThat(text("SELECT coalesce(hidden_reason, 'shown') FROM reputation.rating WHERE id = '" + this.vyRating + "'"))
			.isEqualTo("shown");
		assertThat(notices("dispute.team_issue_decided", this.world.vy())).isOne();
		assertThat(notices("dispute.team_issue_decided", this.world.hung())).isOne();
		assertThat(openTodos("dispute.decide_team_issue_answer", this.world.vy())).isZero();
		assertThat(count("SELECT count(*) FROM payment.ledger_entry WHERE entry_type <> 'FUND'")).as("no ending moves money")
			.isZero();
	}

	@Test
	void aSilentMentorLetsTheIssueLapse() throws Exception {
		UUID issue = raise(this.world.vy(), this.vyRating);
		this.clock.advance(Duration.ofDays(5).plusMinutes(1));
		answer(issue, "KEEP").andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("ANSWER_PERIOD_ENDED"));
		this.poller.runDue();
		assertThat(text("SELECT phase FROM dispute.team_issue WHERE id = '" + issue + "'")).isEqualTo("ESCALATION");
		assertThat(openTodos("dispute.decide_team_issue_answer", this.world.vy())).isOne();
		choose(this.world.vy(), issue, "accept").andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("NO_ANSWER"));

		this.clock.advance(Duration.ofDays(5).plusMinutes(1));
		this.poller.runDue();
		assertThat(text("SELECT ending FROM dispute.team_issue WHERE id = '" + issue + "'")).isEqualTo("LAPSED");
		assertThat(text("SELECT coalesce(hidden_reason, 'shown') FROM reputation.rating WHERE id = '" + this.vyRating + "'"))
			.as("the Rating stays without an answer").isEqualTo("shown");
		assertThat(openTodos("dispute.decide_team_issue_answer", this.world.vy())).isZero();
	}

	@Test
	void anEscalatedIssueWaitsForAnAdministratorWithNoDeadline() throws Exception {
		Administrator ha = AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		UUID issue = raise(this.world.vy(), this.vyRating);
		answer(issue, "KEEP").andExpect(status().isOk());
		choose(this.world.vy(), issue, "escalate").andExpect(status().isOk()).andExpect(jsonPath("$.phase").value("ESCALATED"));
		assertThat(notices("dispute.team_issue_escalated", this.world.hung())).isOne();
		assertThat(notices("dispute.team_issue_escalated", ha.accountId())).isOne();
		assertThat(notices("dispute.team_issue_escalated", this.world.lan())).isZero();
		assertThat(this.queue.teamIssuesEscalated()).extracting(DisputeQueue.Waiting::disputeId).containsExactly(issue);
		choose(this.world.vy(), issue, "accept").andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("ESCALATION_PERIOD_NOT_OPEN"));

		this.clock.advance(Duration.ofDays(6));
		this.poller.runDue();
		assertThat(text("SELECT phase FROM dispute.team_issue WHERE id = '" + issue + "'")).isEqualTo("ESCALATED");
		assertThat(text("SELECT status FROM timers.deadline WHERE kind = 'dispute.team_issue_escalation' AND subject_id = '"
				+ issue + "'")).isEqualTo("OBSOLETE");
	}

	@Test
	void aWithdrawnRatingIsRemovedAndARemovalByAnotherPathEndsAnOpenRatingIssueAsMoot() throws Exception {
		Instant published = this.clock.instant().minus(Duration.ofDays(1));
		UUID minhRating = this.world.rating(this.jdbc, this.world.minh(), published);
		UUID vyIssue = raise(this.world.vy(), this.vyRating);
		answer(vyIssue, "WITHDRAW_RATING").andExpect(status().isOk());
		choose(this.world.vy(), vyIssue, "accept").andExpect(status().isOk())
			.andExpect(jsonPath("$.outcomeRatingRemoved").value(true));
		assertThat(text("SELECT hidden_reason FROM reputation.rating WHERE id = '" + this.vyRating + "'"))
			.isEqualTo(TeamIssueEndings.REMOVED_REASON);

		UUID minhIssue = raise(this.world.minh(), minhRating);
		this.transactions.executeWithoutResult(status -> this.ratings.removeRating(minhRating, "Vi phạm quy tắc"));
		await().atMost(Duration.ofSeconds(15))
			.until(() -> "MOOT".equals(text("SELECT coalesce(ending, '') FROM dispute.team_issue WHERE id = '" + minhIssue + "'")));
	}

	private UUID raise(UUID student, UUID rating) throws Exception {
		String body = this.mvc.perform(post("/api/v1/team-issues").with(as(student, Role.STUDENT)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"jobId\":\"" + this.world.job() + "\",\"ratingId\":\"" + rating + "\",\"reason\":\"Chưa công bằng.\"}"))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return UUID.fromString(body.replaceAll("^\\{\"id\":\"([^\"]+)\".*", "$1"));
	}

	private ResultActions answer(UUID issue, String kind) throws Exception {
		return this.mvc.perform(post("/api/v1/boards/" + this.world.job() + "/team-issues/" + issue + "/answer")
			.with(as(this.world.hung(), Role.MENTOR))
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"kind\":\"" + kind + "\"}"));
	}

	private ResultActions choose(UUID student, UUID issue, String choice) throws Exception {
		return this.mvc.perform(post("/api/v1/team-issues/" + issue + "/" + choice).with(as(student, Role.STUDENT)).with(csrf()));
	}

	private long notices(String type, UUID account) {
		return this.jdbc.sql("SELECT count(*) FROM notification.notification WHERE type = :type AND account_id = :account")
			.param("type", type).param("account", account).query(Long.class).single();
	}

	private long openTodos(String key, UUID account) {
		return this.jdbc.sql("SELECT count(*) FROM notification.todo WHERE todo_key = :key AND account_id = :account AND closed_at IS NULL")
			.param("key", key).param("account", account).query(Long.class).single();
	}

	private long count(String sql) {
		return this.jdbc.sql(sql).query(Long.class).single();
	}

	private String text(String sql) {
		return this.jdbc.sql(sql).query(String.class).single();
	}

	private RequestPostProcessor as(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

}
