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
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.payment.Ledger;
import vn.vibefix.payment.Releases;
import vn.vibefix.shared.Role;
import vn.vibefix.support.HeldCrewJob;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.timers.internal.DeadlinePoller;

/**
 * Story 12.1 (FR-79, AD-40, AD-4, AD-7): a Crew member raises a Team Issue about their share of the Held
 * Milestone or the Rating the Mentor gave them. A share issue holds the Mentor's part and the raiser's at
 * release, which leaves the released total as it is; the Customer sees nothing, and the Job never freezes.
 */
class TeamIssueIT extends IntegrationTest {

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
	private TeamIssueCheck check;

	private HeldCrewJob world;

	@BeforeEach
	void aHeldMilestoneOfACrewOfThree() {
		this.world = HeldCrewJob.create(this.jdbc, this.clock.instant());
	}

	@Test
	void aShareIssueHoldsTheTwoPartsAtReleaseAndOnlyTheMentorLearnsOfIt() throws Exception {
		this.mvc.perform(get("/api/v1/team-issues").with(as(this.world.vy(), Role.STUDENT)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].jobId").value(this.world.job().toString()))
			.andExpect(jsonPath("$[0].mentorName").value("Đỗ Mạnh Hùng"))
			.andExpect(jsonPath("$[0].shares[0].milestoneId").value(this.world.milestone().toString()))
			.andExpect(jsonPath("$[0].shares[0].number").value(1))
			.andExpect(jsonPath("$[0].shares[0].refusal").doesNotExist());

		String body = raise(this.world.vy(), "SHARE", this.world.milestone(), "Mình làm gần hết phần đặt bàn khi Minh ốm.")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.phase").value("ANSWER"))
			.andExpect(jsonPath("$.milestoneNumber").value(1))
			.andExpect(jsonPath("$.mine").value(true))
			.andReturn()
			.getResponse()
			.getContentAsString();
		UUID issue = UUID.fromString(body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));

		assertThat(count("SELECT count(*) FROM payment.share_hold WHERE team_issue_id = '" + issue + "' AND ended_at IS NULL"))
			.isOne();
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
		assertThat(count("SELECT count(*) FROM audit.audit_entry WHERE action = 'dispute.team_issue_raised'")).isOne();

		this.mvc.perform(get("/api/v1/boards/" + this.world.job() + "/team-issues").with(as(this.world.lan(), Role.CUSTOMER)))
			.andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/boards/" + this.world.job() + "/team-issues").with(as(this.world.hung(), Role.MENTOR)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.mentor").value(true))
			.andExpect(jsonPath("$.issues[0].raiserName").value("Trần Thảo Vy"))
			.andExpect(jsonPath("$.issues[0].subject").value("SHARE"));
		this.mvc.perform(get("/api/v1/boards/" + this.world.job() + "/team-issues").with(as(this.world.minh(), Role.STUDENT)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.issues").isEmpty());

		// AD-40: the release writes RELEASE for Minh and RELEASE_HELD for Hùng and Vy; paid out is whole.
		this.transactions.executeWithoutResult(
				status -> this.releases.release(this.world.job(), this.world.milestone(), UUID.randomUUID()));
		assertThat(entry("RELEASE", this.world.minh())).isEqualTo(400_000);
		assertThat(entry("RELEASE_HELD", this.world.vy())).isEqualTo(400_000);
		assertThat(entry("RELEASE_HELD", this.world.hung())).isEqualTo(200_000);
		assertThat(text("SELECT hold_id FROM payment.ledger_entry WHERE entry_type = 'RELEASE_HELD' AND party_account_id = '"
				+ this.world.vy() + "'")).isEqualTo(issue.toString());
		assertThat(this.ledger.releasedTotal(this.world.milestone())).isEqualTo(HeldCrewJob.PRICE);
		assertThat(this.ledger.releasable(this.world.milestone())).isZero();
		assertThat(notices("payment.share_held", this.world.vy())).isOne();
		assertThat(notices("payment.share_held", this.world.hung())).isOne();
		assertThat(notices("payment.funds_released", this.world.minh())).isOne();
		assertThat(notices("payment.funds_released", this.world.vy())).isZero();

		this.mvc.perform(get("/api/v1/balance").with(as(this.world.vy(), Role.STUDENT)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.balanceVnd").value(0))
			.andExpect(jsonPath("$.heldParts[0].amountVnd").value(400_000))
			.andExpect(jsonPath("$.heldParts[0].number").value(1));
		this.mvc.perform(get("/api/v1/balance").with(as(this.world.minh(), Role.STUDENT)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.balanceVnd").value(400_000))
			.andExpect(jsonPath("$.heldParts").isEmpty());

		// After the release, the share is no longer raisable.
		raise(this.world.minh(), "SHARE", this.world.milestone(), "Phần của mình cũng chưa đúng.")
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("MILESTONE_NOT_HELD"));
		assertThat(count("SELECT count(*) FROM dispute.team_issue WHERE raiser_account_id = '" + this.world.minh() + "'"))
			.as("a refused hold creates no issue").isZero();
	}

	@Test
	void eachRefusalNamesItsReason() throws Exception {
		raise(this.world.lan(), "SHARE", this.world.milestone(), "Khách hàng thử.").andExpect(status().isNotFound());
		UUID stranger = HeldCrewJob.create(this.jdbc, this.clock.instant()).vy();
		raise(stranger, "SHARE", this.world.milestone(), "Không ở nhóm này.").andExpect(status().isNotFound());

		raise(this.world.vy(), "SHARE", UUID.randomUUID(), "Không phải giai đoạn đang giữ tiền.")
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("MILESTONE_NOT_HELD"));

		UUID vyRating = this.world.rating(this.jdbc, this.world.vy(), this.clock.instant().minus(Duration.ofDays(2)));
		UUID minhRating = this.world.rating(this.jdbc, this.world.minh(), this.clock.instant().minus(Duration.ofDays(2)));
		raise(this.world.vy(), "SHARE", this.world.milestone(), "Phần chia chưa công bằng.").andExpect(status().isCreated());
		raise(this.world.vy(), "RATING", vyRating, "Điểm chưa đúng.")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("TEAM_ISSUE_OPEN"));
		this.mvc.perform(get("/api/v1/team-issues").with(as(this.world.vy(), Role.STUDENT)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].shares[0].refusal").value("TEAM_ISSUE_ALREADY_RAISED"))
			.andExpect(jsonPath("$[0].ratings[0].refusal").value("TEAM_ISSUE_OPEN"));

		this.jdbc.sql("UPDATE dispute.team_issue SET phase = 'ENDED', ended_at = now() WHERE raiser_account_id = :vy")
			.param("vy", this.world.vy()).update();
		raise(this.world.vy(), "SHARE", this.world.milestone(), "Lần nữa.")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("TEAM_ISSUE_ALREADY_RAISED"));
		raise(this.world.vy(), "RATING", vyRating, "Điểm chưa đúng.").andExpect(status().isCreated());
		this.jdbc.sql("UPDATE dispute.team_issue SET phase = 'ENDED', ended_at = now() WHERE raiser_account_id = :vy")
			.param("vy", this.world.vy()).update();
		raise(this.world.vy(), "RATING", vyRating, "Lần nữa.")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("TEAM_ISSUE_ALREADY_RAISED"));
		raise(this.world.vy(), "RATING", minhRating, "Không phải điểm của mình.").andExpect(status().isNotFound());

		this.clock.advance(Duration.ofDays(13));
		raise(this.world.minh(), "RATING", minhRating, "Điểm chưa đúng.")
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("TEAM_ISSUE_WINDOW_CLOSED"));
	}

	@Test
	void theAnswerPeriodEndingStartsTheEscalationPeriodAndAnIssueOpenTooLongIsListed() throws Exception {
		raise(this.world.vy(), "SHARE", this.world.milestone(), "Phần chia chưa công bằng.").andExpect(status().isCreated());
		UUID issue = this.jdbc.sql("SELECT id FROM dispute.team_issue WHERE raiser_account_id = :vy").param("vy", this.world.vy())
			.query(UUID.class).single();

		this.clock.advance(Duration.ofDays(5).plusMinutes(1));
		this.poller.runDue();
		assertThat(text("SELECT phase FROM dispute.team_issue WHERE id = '" + issue + "'")).isEqualTo("ESCALATION");
		assertThat(this.jdbc.sql("SELECT escalation_due_at FROM dispute.team_issue WHERE id = :id").param("id", issue)
			.query(Timestamp.class).single().toInstant()).isEqualTo(this.clock.instant().plus(Duration.ofDays(5)));
		assertThat(count("SELECT count(*) FROM notification.todo WHERE todo_key = 'dispute.answer_team_issue' AND closed_at IS NULL"))
			.isZero();
		assertThat(text("SELECT status FROM timers.deadline WHERE subject_id = '" + issue + "'")).isEqualTo("FIRED");

		assertThat(this.check.check()).doesNotContain(issue);
		this.clock.advance(Duration.ofDays(12));
		assertThat(this.check.check()).containsExactly(issue);
		assertThat(count("SELECT count(*) FROM operations.external_operation WHERE type = 'dispute.team_issue_overdue'")).isOne();
	}

	private ResultActions raise(UUID account, String subject, UUID target, String reason) throws Exception {
		String field = "SHARE".equals(subject) ? "milestoneId" : "ratingId";
		Role role = this.jdbc.sql("SELECT role FROM identity.account WHERE id = :id").param("id", account).query(String.class)
			.optional().map(Role::valueOf).orElse(Role.STUDENT);
		return this.mvc.perform(post("/api/v1/team-issues").with(as(account, role)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"jobId\":\"" + this.world.job() + "\",\"subject\":\"" + subject + "\",\"" + field + "\":\"" + target
					+ "\",\"reason\":\"" + reason + "\"}"));
	}

	private long entry(String type, UUID party) {
		return this.jdbc.sql("SELECT amount_vnd FROM payment.ledger_entry WHERE entry_type = :type AND party_account_id = :party")
			.param("type", type).param("party", party).query(Long.class).single();
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
