package vn.vibefix.dispute.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
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
import vn.vibefix.job.JobTransitioned;
import vn.vibefix.payment.Ledger;
import vn.vibefix.payment.Releases;
import vn.vibefix.reputation.CrewRatings;
import vn.vibefix.support.HeldCrewJob;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.timers.internal.DeadlinePoller;

/**
 * Story 12.2 (FR-79, AD-40, AD-15, AD-5): the Mentor answers a Team Issue once, which moves no money; the
 * raiser accepts it or asks an Administrator; the escalation period lapses; or the issue becomes moot.
 * Every share ending executes through {@code payment.endHold}, before or after the release, so the parts
 * are whole VND summing to what was released and paid out never changes.
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
	private Releases releases;

	@Autowired
	private Ledger ledger;

	@Autowired
	private DeadlinePoller poller;

	@Autowired
	private DisputeQueue queue;

	@Autowired
	private CrewRatings ratings;

	@Autowired
	private ApplicationEventPublisher events;

	@Autowired
	private ApplicationContext context;

	private HeldCrewJob world;

	@BeforeEach
	void aHeldMilestoneOfACrewOfThree() {
		this.world = HeldCrewJob.create(this.jdbc, this.clock.instant());
	}

	@Test
	void aTransferAcceptedBeforeTheReleaseIsAppliedByTheRelease() throws Exception {
		UUID issue = raise(this.world.vy(), "SHARE", this.world.milestone());
		this.mvc.perform(get("/api/v1/boards/" + this.world.job() + "/team-issues").with(as(this.world.hung(), Role.MENTOR)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.issues[0].split.basisVnd").value(HeldCrewJob.PRICE))
			.andExpect(jsonPath("$.issues[0].split.mentorPercent").value(20))
			.andExpect(jsonPath("$.issues[0].split.raiserPercent").value(40))
			.andExpect(jsonPath("$.issues[0].split.capPoints").value(20))
			.andExpect(jsonPath("$.issues[0].split.others[0]").value(40));

		answer(issue, "TRANSFER", 25).andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("SHARE_TRANSFER_TOO_LARGE"))
			.andExpect(jsonPath("$.cap").value(20));
		answer(issue, "WITHDRAW_RATING", null).andExpect(status().isBadRequest());
		answer(issue, "TRANSFER", 10).andExpect(status().isOk())
			.andExpect(jsonPath("$.phase").value("ESCALATION"))
			.andExpect(jsonPath("$.answerPreview.raiserPercent").value(50))
			.andExpect(jsonPath("$.answerPreview.raiserVnd").value(500_000))
			.andExpect(jsonPath("$.answerPreview.mentorVnd").value(100_000));
		answer(issue, "KEEP", null).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("ALREADY_ANSWERED"));
		assertThat(notices("dispute.team_issue_answered", this.world.vy())).isOne();
		assertThat(openTodos("dispute.decide_team_issue_answer", this.world.vy())).isOne();
		assertThat(openTodos("dispute.answer_team_issue", this.world.hung())).isZero();
		assertThat(count("SELECT count(*) FROM payment.ledger_entry WHERE entry_type <> 'FUND'")).as("an answer moves no money")
			.isZero();

		choose(this.world.vy(), issue, "accept").andExpect(status().isOk())
			.andExpect(jsonPath("$.phase").value("ENDED"))
			.andExpect(jsonPath("$.ending").value("ACCEPTED"))
			.andExpect(jsonPath("$.outcomePoints").value(10));
		assertThat(count("SELECT transfer_points FROM payment.share_hold WHERE team_issue_id = '" + issue + "'")).isEqualTo(10);
		assertThat(notices("dispute.team_issue_decided", this.world.vy())).isOne();
		assertThat(notices("dispute.team_issue_decided", this.world.hung())).isOne();
		assertThat(openTodos("dispute.decide_team_issue_answer", this.world.vy())).isZero();

		release();
		assertThat(entry("RELEASE", this.world.hung())).isEqualTo(100_000);
		assertThat(entry("RELEASE", this.world.vy())).isEqualTo(500_000);
		assertThat(entry("RELEASE", this.world.minh())).isEqualTo(400_000);
		assertThat(count("SELECT count(*) FROM payment.ledger_entry WHERE entry_type = 'RELEASE_HELD'")).isZero();
	}

	@Test
	void anAcceptanceAfterTheReleaseReversesTheHeldPartsAndPaysTheNewOnes() throws Exception {
		UUID issue = raise(this.world.vy(), "SHARE", this.world.milestone());
		release();
		answer(issue, "TRANSFER", 10).andExpect(status().isOk());
		choose(this.world.vy(), issue, "accept").andExpect(status().isOk());

		assertThat(count("SELECT count(*) FROM payment.ledger_entry WHERE entry_type = 'HELD_RELEASED'")).isEqualTo(2);
		assertThat(this.jdbc.sql("SELECT amount_vnd FROM payment.ledger_entry WHERE operation_key = :key")
			.param("key", "RELEASE:" + issue + ":" + this.world.vy()).query(Long.class).single()).isEqualTo(500_000);
		assertThat(this.jdbc.sql("SELECT amount_vnd FROM payment.ledger_entry WHERE operation_key = :key")
			.param("key", "RELEASE:" + issue + ":" + this.world.hung()).query(Long.class).single()).isEqualTo(100_000);
		assertThat(this.ledger.releasedTotal(this.world.milestone())).as("paid out does not change").isEqualTo(HeldCrewJob.PRICE);
		assertThat(this.ledger.releasable(this.world.milestone())).isZero();
		this.mvc.perform(get("/api/v1/balance").with(as(this.world.vy(), Role.STUDENT)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.balanceVnd").value(500_000))
			.andExpect(jsonPath("$.heldParts").isEmpty());
		assertThat(notices("payment.funds_released", this.world.vy())).isOne();
		assertThat(notices("payment.funds_released", this.world.hung())).isOne();
	}

	@Test
	void twoShareIssuesShareTheCapAndTheMentorStaysHeldUntilTheLastEnds() throws Exception {
		UUID vyIssue = raise(this.world.vy(), "SHARE", this.world.milestone());
		UUID minhIssue = raise(this.world.minh(), "SHARE", this.world.milestone());
		release();
		assertThat(count("SELECT count(*) FROM payment.ledger_entry WHERE entry_type = 'RELEASE_HELD'")).isEqualTo(3);

		answer(vyIssue, "TRANSFER", 15).andExpect(status().isOk());
		answer(minhIssue, "TRANSFER", 6).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.cap").value(5));
		answer(minhIssue, "TRANSFER", 5).andExpect(status().isOk());

		choose(this.world.vy(), vyIssue, "accept").andExpect(status().isOk());
		assertThat(this.jdbc.sql("SELECT amount_vnd FROM payment.ledger_entry WHERE operation_key = :key")
			.param("key", "RELEASE_HELD:" + vyIssue + ":" + this.world.hung()).query(Long.class).single())
			.as("the Mentor is still held for Minh's issue").isEqualTo(50_000);
		assertThat(this.jdbc.sql("SELECT amount_vnd FROM payment.ledger_entry WHERE operation_key = :key")
			.param("key", "RELEASE:" + vyIssue + ":" + this.world.vy()).query(Long.class).single()).isEqualTo(550_000);

		choose(this.world.minh(), minhIssue, "accept").andExpect(status().isOk());
		assertThat(this.jdbc.sql("SELECT amount_vnd FROM payment.ledger_entry WHERE operation_key = :key")
			.param("key", "RELEASE:" + minhIssue + ":" + this.world.minh()).query(Long.class).single()).isEqualTo(450_000);
		assertThat(count("""
				SELECT count(*) FROM payment.ledger_entry e WHERE e.entry_type = 'RELEASE_HELD'
				  AND NOT EXISTS (SELECT 1 FROM payment.ledger_entry r WHERE r.reverses_entry_id = e.id)
				""")).as("nothing is held any more").isZero();
		assertThat(count("SELECT coalesce(sum(amount_vnd), 0) FROM payment.ledger_entry WHERE entry_type = 'RELEASE'"))
			.isEqualTo(HeldCrewJob.PRICE);
		assertThat(this.ledger.releasedTotal(this.world.milestone())).isEqualTo(HeldCrewJob.PRICE);
	}

	@Test
	void aSilentMentorLetsTheIssueLapseAndTheHeldPartsArePaidByThePin() throws Exception {
		UUID issue = raise(this.world.vy(), "SHARE", this.world.milestone());
		release();
		this.clock.advance(Duration.ofDays(5).plusMinutes(1));
		answer(issue, "KEEP", null).andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("ANSWER_PERIOD_ENDED"));
		this.poller.runDue();
		assertThat(text("SELECT phase FROM dispute.team_issue WHERE id = '" + issue + "'")).isEqualTo("ESCALATION");
		assertThat(openTodos("dispute.decide_team_issue_answer", this.world.vy())).isOne();
		choose(this.world.vy(), issue, "accept").andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("NO_ANSWER"));

		this.clock.advance(Duration.ofDays(5).plusMinutes(1));
		this.poller.runDue();
		assertThat(text("SELECT ending FROM dispute.team_issue WHERE id = '" + issue + "'")).isEqualTo("LAPSED");
		assertThat(this.jdbc.sql("SELECT amount_vnd FROM payment.ledger_entry WHERE operation_key = :key")
			.param("key", "RELEASE:" + issue + ":" + this.world.vy()).query(Long.class).single()).isEqualTo(400_000);
		assertThat(this.jdbc.sql("SELECT amount_vnd FROM payment.ledger_entry WHERE operation_key = :key")
			.param("key", "RELEASE:" + issue + ":" + this.world.hung()).query(Long.class).single()).isEqualTo(200_000);
		assertThat(openTodos("dispute.decide_team_issue_answer", this.world.vy())).isZero();
	}

	@Test
	void anEscalatedIssueWaitsForAnAdministratorWithNoDeadline() throws Exception {
		Administrator ha = AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		UUID issue = raise(this.world.vy(), "SHARE", this.world.milestone());
		answer(issue, "KEEP", null).andExpect(status().isOk());
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
		assertThat(count("SELECT count(*) FROM payment.share_hold WHERE team_issue_id = '" + issue + "' AND ended_at IS NULL"))
			.as("the hold waits for the decision").isOne();
	}

	@Test
	void aMilestoneCancelledWithoutAReleaseEndsTheShareIssueAsMoot() throws Exception {
		UUID issue = raise(this.world.vy(), "SHARE", this.world.milestone());
		this.jdbc.sql("UPDATE job.milestone SET state = 'CANCELLED' WHERE id = :id").param("id", this.world.milestone()).update();
		this.transactions.executeWithoutResult(status -> this.events.publishEvent(new JobTransitioned(UUID.randomUUID(),
				UUID.randomUUID(), this.world.job(), "M7", "SYSTEM", "FUNDED", "CANCELLED", 7, 1,
				Map.of("milestoneId", this.world.milestone().toString()))));
		await().atMost(Duration.ofSeconds(15))
			.until(() -> "MOOT".equals(text("SELECT coalesce(ending, '') FROM dispute.team_issue WHERE id = '" + issue + "'")));
		assertThat(count("SELECT transfer_points FROM payment.share_hold WHERE team_issue_id = '" + issue + "'")).isZero();
		assertThat(notices("dispute.team_issue_decided", this.world.vy())).isOne();
	}

	@Test
	void aWithdrawnRatingIsRemovedAndARemovalByAnotherPathEndsAnOpenRatingIssueAsMoot() throws Exception {
		Instant published = this.clock.instant().minus(Duration.ofDays(1));
		UUID vyRating = this.world.rating(this.jdbc, this.world.vy(), published);
		UUID minhRating = this.world.rating(this.jdbc, this.world.minh(), published);
		UUID vyIssue = raise(this.world.vy(), "RATING", vyRating);
		answer(vyIssue, "TRANSFER", 5).andExpect(status().isBadRequest());
		answer(vyIssue, "WITHDRAW_RATING", null).andExpect(status().isOk());
		choose(this.world.vy(), vyIssue, "accept").andExpect(status().isOk())
			.andExpect(jsonPath("$.outcomeRatingRemoved").value(true));
		assertThat(text("SELECT hidden_reason FROM reputation.rating WHERE id = '" + vyRating + "'"))
			.isEqualTo(TeamIssueEndings.REMOVED_REASON);

		UUID minhIssue = raise(this.world.minh(), "RATING", minhRating);
		this.transactions.executeWithoutResult(status -> this.ratings.removeRating(minhRating, "Vi phạm quy tắc"));
		await().atMost(Duration.ofSeconds(15))
			.until(() -> "MOOT".equals(text("SELECT coalesce(ending, '') FROM dispute.team_issue WHERE id = '" + minhIssue + "'")));
	}

	@Test
	void aRefusedExecutionLeavesTheIssueInItsPhaseAndQueuesTheEndingForAdministrators() throws Exception {
		UUID issue = raise(this.world.vy(), "SHARE", this.world.milestone());
		answer(issue, "TRANSFER", 10).andExpect(status().isOk());
		// A transfer recorded behind the cap's back: the ending would move more than the Mentor's pinned share.
		this.jdbc.sql("""
				INSERT INTO payment.share_hold (team_issue_id, milestone_id, job_id, product_id, raiser_account_id, held_at,
				    ended_at, transfer_points)
				VALUES (:id, :milestone, :job, :product, :minh, now(), now(), 15)
				""").param("id", UUID.randomUUID()).param("milestone", this.world.milestone()).param("job", this.world.job())
			.param("product", this.world.product()).param("minh", this.world.minh()).update();

		choose(this.world.vy(), issue, "accept").andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("ENDING_REFUSED"));
		assertThat(text("SELECT phase FROM dispute.team_issue WHERE id = '" + issue + "'")).isEqualTo("ESCALATION");
		assertThat(count("SELECT count(*) FROM payment.share_hold WHERE team_issue_id = '" + issue + "' AND ended_at IS NULL"))
			.isOne();
		assertThat(count("SELECT count(*) FROM operations.external_operation WHERE type = 'dispute.team_issue_ending'")).isOne();
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

	private ResultActions answer(UUID issue, String kind, Integer points) throws Exception {
		return this.mvc.perform(post("/api/v1/boards/" + this.world.job() + "/team-issues/" + issue + "/answer")
			.with(as(this.world.hung(), Role.MENTOR))
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"kind\":\"" + kind + "\"" + (points == null ? "" : ",\"points\":" + points) + "}"));
	}

	private ResultActions choose(UUID student, UUID issue, String choice) throws Exception {
		return this.mvc.perform(post("/api/v1/team-issues/" + issue + "/" + choice).with(as(student, Role.STUDENT)).with(csrf()));
	}

	private void release() {
		this.transactions.executeWithoutResult(
				status -> this.releases.release(this.world.job(), this.world.milestone(), UUID.randomUUID()));
	}

	private long entry(String type, UUID party) {
		return this.jdbc.sql("SELECT amount_vnd FROM payment.ledger_entry WHERE entry_type = :type AND party_account_id = :party")
			.param("type", type).param("party", party).query(Long.class).single();
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
