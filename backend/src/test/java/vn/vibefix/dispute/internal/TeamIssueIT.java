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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.support.HeldCrewJob;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.timers.internal.DeadlinePoller;

/**
 * Story 12.1 (FR-79, AD-40, AD-4, AD-7): a Crew member raises a Team Issue about the Rating the Mentor gave them. Only
 * the Mentor learns of it, nothing moves, and the Job never freezes; the Customer sees nothing.
 */
class TeamIssueIT extends IntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private DeadlinePoller poller;

	@Autowired
	private TeamIssueCheck check;

	private HeldCrewJob world;

	@BeforeEach
	void aHeldMilestoneOfACrewOfThree() {
		this.world = HeldCrewJob.create(this.jdbc, this.clock.instant());
	}

	@Test
	void aRatingIssueGoesToTheMentorAloneAndChangesNothingElse() throws Exception {
		UUID rating = this.world.rating(this.jdbc, this.world.vy(), this.clock.instant().minus(Duration.ofDays(2)));
		this.mvc.perform(get("/api/v1/team-issues").with(as(this.world.vy(), Role.STUDENT)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].jobId").value(this.world.job().toString()))
			.andExpect(jsonPath("$[0].mentorName").value("Đỗ Mạnh Hùng"))
			.andExpect(jsonPath("$[0].ratings[0].ratingId").value(rating.toString()))
			.andExpect(jsonPath("$[0].ratings[0].stars").value(2))
			.andExpect(jsonPath("$[0].ratings[0].refusal").doesNotExist())
			.andExpect(jsonPath("$[0].shares").doesNotExist());

		String body = raise(this.world.vy(), rating, "Điểm này chưa đúng với phần việc mình làm.")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.phase").value("ANSWER"))
			.andExpect(jsonPath("$.ratingId").value(rating.toString()))
			.andExpect(jsonPath("$.mine").value(true))
			.andExpect(jsonPath("$.subject").doesNotExist())
			.andReturn()
			.getResponse()
			.getContentAsString();
		UUID issue = UUID.fromString(body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));

		assertThat(text("SELECT subject FROM dispute.team_issue WHERE id = '" + issue + "'")).isEqualTo("RATING");
		Instant due = this.jdbc.sql("SELECT due_at FROM timers.deadline WHERE kind = 'dispute.team_issue_answer' AND subject_id = :id")
			.param("id", issue).query(Timestamp.class).single().toInstant();
		assertThat(due).isEqualTo(this.clock.instant().plus(Duration.ofDays(5)));
		assertThat(text("SELECT generation FROM timers.deadline WHERE subject_id = '" + issue + "'")).isEqualTo(issue + ":ANSWER");
		assertThat(notices("dispute.team_issue_raised", this.world.hung())).isOne();
		assertThat(count("SELECT count(*) FROM notification.notification WHERE account_id = '" + this.world.lan() + "'"))
			.as("the Customer is told nothing").isZero();
		assertThat(count("SELECT count(*) FROM notification.todo WHERE todo_key = 'dispute.answer_team_issue' AND account_id = '"
				+ this.world.hung() + "' AND closed_at IS NULL")).isOne();
		assertThat(count("SELECT count(*) FROM job.job WHERE id = '" + this.world.job() + "' AND frozen_at IS NULL"))
			.as("the Job never freezes").isOne();
		assertThat(count("SELECT count(*) FROM payment.ledger_entry WHERE entry_type <> 'FUND'")).as("nothing moves").isZero();
		assertThat(count("SELECT count(*) FROM audit.audit_entry WHERE action = 'dispute.team_issue_raised'")).isOne();

		this.mvc.perform(get("/api/v1/boards/" + this.world.job() + "/team-issues").with(as(this.world.lan(), Role.CUSTOMER)))
			.andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/boards/" + this.world.job() + "/team-issues").with(as(this.world.hung(), Role.MENTOR)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.mentor").value(true))
			.andExpect(jsonPath("$.issues[0].raiserName").value("Trần Thảo Vy"))
			.andExpect(jsonPath("$.issues[0].ratingId").value(rating.toString()))
			.andExpect(jsonPath("$.issues[0].split").doesNotExist());
		this.mvc.perform(get("/api/v1/boards/" + this.world.job() + "/team-issues").with(as(this.world.minh(), Role.STUDENT)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.issues").isEmpty());
	}

	@Test
	void eachRefusalNamesItsReason() throws Exception {
		UUID vyRating = this.world.rating(this.jdbc, this.world.vy(), this.clock.instant().minus(Duration.ofDays(2)));
		UUID minhRating = this.world.rating(this.jdbc, this.world.minh(), this.clock.instant().minus(Duration.ofDays(2)));
		raise(this.world.lan(), vyRating, "Khách hàng thử.").andExpect(status().isNotFound());
		UUID stranger = HeldCrewJob.create(this.jdbc, this.clock.instant()).vy();
		raise(stranger, vyRating, "Không ở nhóm này.").andExpect(status().isNotFound());
		raise(this.world.vy(), UUID.randomUUID(), "Không có điểm này.").andExpect(status().isNotFound());
		raise(this.world.vy(), minhRating, "Không phải điểm của mình.").andExpect(status().isNotFound());
		this.mvc.perform(post("/api/v1/team-issues").with(as(this.world.vy(), Role.STUDENT)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"jobId\":\"" + this.world.job() + "\",\"reason\":\"Không nêu điểm nào.\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION"));

		raise(this.world.vy(), vyRating, "Điểm chưa đúng.").andExpect(status().isCreated());
		this.mvc.perform(get("/api/v1/team-issues").with(as(this.world.vy(), Role.STUDENT)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].ratings[0].refusal").value("TEAM_ISSUE_ALREADY_RAISED"));

		this.jdbc.sql("UPDATE dispute.team_issue SET phase = 'ENDED', ended_at = now(), ending = 'LAPSED' WHERE raiser_account_id = :vy")
			.param("vy", this.world.vy()).update();
		raise(this.world.vy(), vyRating, "Lần nữa.")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("TEAM_ISSUE_ALREADY_RAISED"));

		// One open issue per raiser per Job: an issue of Minh's still open on this Job holds back another.
		UUID open = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO dispute.team_issue (id, job_id, product_id, rating_id, raiser_account_id, mentor_account_id, reason,
				    phase, answer_days, escalation_days, raised_at, answer_due_at)
				VALUES (:id, :job, :product, :rating, :minh, :hung, 'Điểm chưa đúng', 'ANSWER', 5, 5, now(), now() + INTERVAL '5 days')
				""").param("id", open).param("job", this.world.job()).param("product", this.world.product())
			.param("rating", UUID.randomUUID()).param("minh", this.world.minh()).param("hung", this.world.hung()).update();
		this.mvc.perform(get("/api/v1/team-issues").with(as(this.world.minh(), Role.STUDENT)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].ratings[0].refusal").value("TEAM_ISSUE_OPEN"));
		raise(this.world.minh(), minhRating, "Điểm chưa đúng.")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("TEAM_ISSUE_OPEN"));
		this.jdbc.sql("UPDATE dispute.team_issue SET phase = 'ENDED', ended_at = now(), ending = 'MOOT' WHERE id = :id")
			.param("id", open).update();

		this.clock.advance(Duration.ofDays(13));
		raise(this.world.minh(), minhRating, "Điểm chưa đúng.")
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("TEAM_ISSUE_WINDOW_CLOSED"));
	}

	@Test
	void theAnswerPeriodEndingStartsTheEscalationPeriodAndAnIssueOpenTooLongIsListed() throws Exception {
		UUID rating = this.world.rating(this.jdbc, this.world.vy(), this.clock.instant().minus(Duration.ofDays(2)));
		raise(this.world.vy(), rating, "Điểm chưa đúng.").andExpect(status().isCreated());
		UUID issue = this.jdbc.sql("SELECT id FROM dispute.team_issue WHERE raiser_account_id = :vy").param("vy", this.world.vy())
			.query(UUID.class).single();

		this.clock.advance(Duration.ofDays(5).plusMinutes(1));
		this.poller.runDue();
		assertThat(text("SELECT phase FROM dispute.team_issue WHERE id = '" + issue + "'")).isEqualTo("ESCALATION");
		assertThat(this.jdbc.sql("SELECT escalation_due_at FROM dispute.team_issue WHERE id = :id").param("id", issue)
			.query(Timestamp.class).single().toInstant()).isEqualTo(this.clock.instant().plus(Duration.ofDays(5)));
		assertThat(count("SELECT count(*) FROM notification.todo WHERE todo_key = 'dispute.answer_team_issue' AND closed_at IS NULL"))
			.isZero();
		assertThat(text("SELECT status FROM timers.deadline WHERE kind = 'dispute.team_issue_answer' AND subject_id = '" + issue + "'"))
			.isEqualTo("FIRED");

		assertThat(this.check.check()).doesNotContain(issue);
		this.clock.advance(Duration.ofDays(12));
		assertThat(this.check.check()).containsExactly(issue);
		assertThat(count("SELECT count(*) FROM operations.external_operation WHERE type = 'dispute.team_issue_overdue'")).isOne();
	}

	/**
	 * Story 22.4: an issue whose row went with the share issues leaves its Deadlines due; each ends {@code OBSOLETE} and
	 * closes the to-do it would have closed, which nothing else closes.
	 */
	@Test
	void aDeadlineOfAnIssueThatIsGoneEndsObsoleteAndClosesItsTodo() {
		UUID answered = UUID.randomUUID();
		UUID escalating = UUID.randomUUID();
		todo(TeamIssues.ANSWER_TODO, this.world.hung(), "MENTOR", answered);
		todo(TeamIssues.DECIDE_TODO, this.world.vy(), "CREW", escalating);
		deadline(TeamIssueAnswerDeadline.KEY, answered, TeamIssues.generation(answered, TeamIssues.Phase.ANSWER));
		deadline(TeamIssueEscalationDeadline.KEY, escalating, TeamIssues.generation(escalating, TeamIssues.Phase.ESCALATION));

		this.clock.advance(Duration.ofMinutes(1));
		this.poller.runDue();

		assertThat(text("SELECT string_agg(status, ',' ORDER BY kind) FROM timers.deadline WHERE subject_id IN ('" + answered
				+ "', '" + escalating + "')")).isEqualTo("OBSOLETE,OBSOLETE");
		assertThat(count("SELECT count(*) FROM notification.todo WHERE subject_id IN ('" + answered + "', '" + escalating
				+ "') AND closed_at IS NULL")).isZero();
		assertThat(count("SELECT count(*) FROM notification.todo WHERE subject_id IN ('" + answered + "', '" + escalating
				+ "')")).isEqualTo(2);
	}

	private void todo(String key, UUID account, String role, UUID subject) {
		this.jdbc.sql("""
				INSERT INTO notification.todo (id, todo_key, account_id, subject_id, tier, params, link, job_id, opened_at, role)
				VALUES (:id, :key, :account, :subject, 1, '{}', '/cong-viec', :job, now(), :role)
				""").param("id", UUID.randomUUID()).param("key", key).param("account", account).param("subject", subject)
			.param("job", this.world.job()).param("role", role).update();
	}

	private void deadline(String kind, UUID subject, String generation) {
		this.jdbc.sql("""
				INSERT INTO timers.deadline (id, kind, owner, subject_id, generation, job_id, pausable, status, due_at, created_at)
				VALUES (:id, :kind, 'dispute', :subject, :generation, :job, FALSE, 'PENDING', :due, now())
				""").param("id", UUID.randomUUID()).param("kind", kind).param("subject", subject).param("generation", generation)
			.param("job", this.world.job()).param("due", Timestamp.from(this.clock.instant())).update();
	}

	private ResultActions raise(UUID account, UUID rating, String reason) throws Exception {
		Role role = this.jdbc.sql("SELECT kind FROM identity.account WHERE id = :id").param("id", account).query(String.class)
			.optional().filter("ADMINISTRATOR"::equals).map(kind -> Role.ADMINISTRATOR).orElse(Role.STUDENT);
		return this.mvc.perform(post("/api/v1/team-issues").with(as(account, role)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"jobId\":\"" + this.world.job() + "\",\"ratingId\":\"" + rating + "\",\"reason\":\"" + reason + "\"}"));
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

	private org.springframework.test.web.servlet.request.RequestPostProcessor as(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

}
