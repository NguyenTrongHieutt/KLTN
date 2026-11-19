package vn.vibefix.reputation.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.jspecify.annotations.Nullable;
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
import vn.vibefix.job.JobTransitioned;
import vn.vibefix.repo.DataDeletionConfirmed;
import vn.vibefix.reputation.PortfolioEntry;
import vn.vibefix.reputation.Reputation;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.PostgresTestDatabase;
import vn.vibefix.support.TestPeople;
import vn.vibefix.timers.internal.DeadlinePoller;

/**
 * FR-50, FR-51, FR-66, FR-14: J9 creates a Portfolio Entry for the Mentor and each ever-active Crew
 * member of a Job with Accepted work, with facts from records; the Mentor drafts the summary and the
 * Customer approves, edits, or declines it in the Rating Window, or it lapses; an entry goes public only
 * after its person's data-deletion confirmation.
 */
class PortfolioIT extends IntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private ReputationListeners listeners;

	@Autowired
	private DeadlinePoller poller;

	@Autowired
	private Reputation reputation;

	private UUID lan;

	private UUID hung;

	private UUID vy;

	@Test
	void entriesFreezeFactsTheCustomerApprovesTheSummaryAndConfirmationPublishes() throws Exception {
		people();
		UUID job = job(true);
		assertThat(this.reputation.newcomer(this.hung)).isTrue();
		closes(job);
		await().atMost(Duration.ofSeconds(10))
			.until(() -> count("SELECT count(*) FROM reputation.portfolio_entry WHERE job_id = '" + job + "'") == 2);
		assertThat(openTodo("reputation.draft_portfolio_summary", this.hung, job)).isTrue();
		assertThat(this.reputation.newcomer(this.hung)).isFalse();

		read(this.hung, Role.MENTOR, job).andExpect(status().isOk())
			.andExpect(jsonPath("$.exists").value(true))
			.andExpect(jsonPath("$.summaryStatus").value("DRAFT_DUE"))
			.andExpect(jsonPath("$.open").value(true))
			.andExpect(jsonPath("$.productName").value("Sổ đặt bàn"))
			.andExpect(jsonPath("$.mine.role").value("MENTOR"))
			.andExpect(jsonPath("$.mine.milestones").value(1))
			.andExpect(jsonPath("$.mine.milestonesHandedOver").value(1))
			.andExpect(jsonPath("$.mine.commitsAuthored").doesNotExist())
			.andExpect(jsonPath("$.mine.skills[0]").isNotEmpty())
			.andExpect(jsonPath("$.mine.publishedAt").doesNotExist());
		read(this.vy, Role.STUDENT, job).andExpect(jsonPath("$.mine.role").value("CREW"))
			.andExpect(jsonPath("$.mine.commitsAuthored").value(2))
			.andExpect(jsonPath("$.mine.milestonesHandedOver").doesNotExist())
			.andExpect(jsonPath("$.productName").doesNotExist());

		draft(this.lan, Role.CUSTOMER, job, "Không được").andExpect(status().isForbidden())
			.andExpect(jsonPath("$.reason").value("MENTOR_ONLY"));
		approve(this.vy, Role.STUDENT, job, "Không được", true).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.reason").value("CUSTOMER_ONLY"));
		approve(this.lan, Role.CUSTOMER, job, "Chưa có bản nháp", true).andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("SUMMARY_NOT_AWAITING"));
		draft(this.hung, Role.MENTOR, job, "Nhóm đánh giá toàn bộ luồng đặt bàn.").andExpect(status().isOk())
			.andExpect(jsonPath("$.summaryStatus").value("AWAITING_APPROVAL"));
		draft(this.hung, Role.MENTOR, job, "Lần nữa").andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("SUMMARY_NOT_AWAITING"));
		assertThat(openTodo("reputation.draft_portfolio_summary", this.hung, job)).isFalse();
		assertThat(openTodo("reputation.approve_portfolio_summary", this.lan, job)).isTrue();
		assertThat(count("""
				SELECT count(*) FROM notification.notification WHERE type = 'reputation.portfolio_summary_awaiting_approval'
				""")).isEqualTo(1);
		read(this.vy, Role.STUDENT, job).andExpect(jsonPath("$.summaryText").doesNotExist());
		approve(this.lan, Role.CUSTOMER, job, "Nhóm đánh giá kỹ toàn bộ luồng đặt bàn.", true).andExpect(status().isOk())
			.andExpect(jsonPath("$.summaryStatus").value("APPROVED"))
			.andExpect(jsonPath("$.showProductName").value(true));
		assertThat(openTodo("reputation.approve_portfolio_summary", this.lan, job)).isFalse();
		read(this.vy, Role.STUDENT, job).andExpect(jsonPath("$.summaryText").value("Nhóm đánh giá kỹ toàn bộ luồng đặt bàn."));

		assertThat(this.reputation.portfolio(this.vy)).isEmpty();
		this.mvc.perform(post("/api/v1/boards/" + job + "/ratings").with(as(this.hung, Role.MENTOR)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"rateeId\":\"" + this.vy + "\",\"stars\":4,\"comment\":\"Chăm chỉ\"}")).andExpect(status().isCreated());
		for (String item : List.of("NON_CODE_DATA", "PREVIEWS")) {
			this.mvc.perform(post("/api/v1/boards/" + job + "/data-deletion/" + item).with(as(this.vy, Role.STUDENT)).with(csrf()))
				.andExpect(status().isOk());
		}
		await().atMost(Duration.ofSeconds(10)).until(() -> !this.reputation.portfolio(this.vy).isEmpty());
		PortfolioEntry entry = this.reputation.portfolio(this.vy).getFirst();
		assertThat(entry.title()).isEqualTo("Đánh giá sổ đặt bàn");
		assertThat(entry.role()).isEqualTo("CREW");
		assertThat(entry.jobTypes()).containsExactly("ASSESSMENT");
		assertThat(entry.skills()).hasSize(1);
		assertThat(entry.commitsAuthored()).isEqualTo(2);
		assertThat(entry.summary()).isEqualTo("Nhóm đánh giá kỹ toàn bộ luồng đặt bàn.");
		assertThat(entry.productName()).isEqualTo("Sổ đặt bàn");
		assertThat(entry.rating()).isNull();
		assertThat(this.reputation.portfolio(this.hung)).isEmpty();
		assertThat(count("""
				SELECT count(*) FROM notification.notification
				WHERE type = 'reputation.portfolio_entry_published' AND account_id = '%s'
				""".formatted(this.vy))).isEqualTo(1);
		read(this.vy, Role.STUDENT, job).andExpect(jsonPath("$.mine.publishedAt").isNotEmpty());

		this.clock.advance(Duration.ofDays(15));
		this.poller.runDue();
		assertThat(this.reputation.portfolio(this.vy).getFirst().rating()).isEqualTo(4);
		assertThat(text("SELECT status FROM reputation.portfolio_summary WHERE job_id = '" + job + "'")).isEqualTo("APPROVED");
	}

	/**
	 * FR-50, Story 24.2: a fact vibefix did not count is frozen as none, never as zero. An Assessment Job's handover
	 * records no commit at all -- its deliverable is the Assessment Report -- so its Crew entry carries no count, and
	 * the screen leaves the fact out instead of publishing "0 commit" of a member who did the work.
	 */
	@Test
	void anAcceptedMilestoneWithNoCommitRecordedFreezesNoCountAtAll() throws Exception {
		people();
		UUID job = job(true);
		this.jdbc.sql("DELETE FROM delivery.handover_commit").update();

		closes(job);
		await().atMost(Duration.ofSeconds(10))
			.until(() -> count("SELECT count(*) FROM reputation.portfolio_entry WHERE job_id = '" + job + "'") == 2);

		assertThat(frozenFacts(job, this.vy)).as("none, not zero").isEqualTo("CREW:-:-");
		assertThat(frozenFacts(job, this.hung)).as("the Mentor's own fact is counted as before").isEqualTo("MENTOR:-:1");
		read(this.vy, Role.STUDENT, job).andExpect(status().isOk())
			.andExpect(jsonPath("$.mine.role").value("CREW"))
			.andExpect(jsonPath("$.mine.commitsAuthored").doesNotExist());
	}

	/**
	 * FR-50, Story 24.2: GitHub's compare lists 300 commits at most, so a long Milestone's record is partial. A
	 * count read from a partial record would be wrong for everyone on it, so nobody's entry carries one.
	 */
	@Test
	void aTruncatedCommitListFreezesNoCountAtAll() throws Exception {
		people();
		UUID job = job(true);
		// delivery writes the flag when the copy run reports it; the runtime role may not write the column at all.
		asMigrator("UPDATE delivery.handover SET commits_truncated = TRUE WHERE job_id = '" + job + "'");

		closes(job);
		await().atMost(Duration.ofSeconds(10))
			.until(() -> count("SELECT count(*) FROM reputation.portfolio_entry WHERE job_id = '" + job + "'") == 2);

		assertThat(frozenFacts(job, this.vy)).as("the two commits of Vy are in a partial record").isEqualTo("CREW:-:-");
		read(this.vy, Role.STUDENT, job).andExpect(jsonPath("$.mine.commitsAuthored").doesNotExist());
	}

	@Test
	void aJobWithoutAcceptedWorkHasNoEntry() throws Exception {
		people();
		UUID job = job(false);
		closes(job);
		await().atMost(Duration.ofSeconds(10))
			.until(() -> count("SELECT count(*) FROM reputation.rating_window WHERE job_id = '" + job + "'") == 1);
		assertThat(count("SELECT count(*) FROM reputation.portfolio_entry WHERE job_id = '" + job + "'")).isZero();
		read(this.hung, Role.MENTOR, job).andExpect(jsonPath("$.exists").value(false));
		draft(this.hung, Role.MENTOR, job, "Không có").andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("NO_PORTFOLIO"));
		assertThat(this.reputation.newcomer(this.hung)).isTrue();
	}

	@Test
	void aDeclinedSummaryStaysAwayAndAnUndecidedOneLapses() throws Exception {
		people();
		UUID declined = job(true);
		closes(declined);
		UUID silent = job(true);
		closes(silent);
		await().atMost(Duration.ofSeconds(10))
			.until(() -> count("SELECT count(*) FROM reputation.portfolio_summary WHERE job_id IN ('" + declined + "', '"
					+ silent + "')") == 2);
		draft(this.hung, Role.MENTOR, declined, "Một bản tóm tắt.").andExpect(status().isOk());
		this.mvc.perform(post("/api/v1/boards/" + declined + "/portfolio/summary/decline").with(as(this.lan, Role.CUSTOMER))
			.with(csrf())).andExpect(status().isOk())
			.andExpect(jsonPath("$.summaryStatus").value("DECLINED"))
			.andExpect(jsonPath("$.summaryText").doesNotExist());
		draft(this.hung, Role.MENTOR, silent, "Bản chưa ai duyệt.").andExpect(status().isOk());

		this.clock.advance(Duration.ofDays(15));
		this.poller.runDue();
		assertThat(text("SELECT status FROM reputation.portfolio_summary WHERE job_id = '" + declined + "'")).isEqualTo("DECLINED");
		assertThat(text("SELECT status || ':' || (text IS NULL) FROM reputation.portfolio_summary WHERE job_id = '" + silent + "'"))
			.isEqualTo("LAPSED:true");
		assertThat(count("""
				SELECT count(*) FROM notification.todo WHERE todo_key LIKE 'reputation.%_portfolio_summary' AND closed_at IS NULL
				""")).isZero();
		approve(this.lan, Role.CUSTOMER, silent, "Muộn", false).andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("RATING_WINDOW_CLOSED"));
	}

	/**
	 * Story 21.3 (FR-50, AD-41): a Cancelled Job with one Accepted Milestone credits the Mentor and every member whose
	 * active window overlaps its work, a member who left after it included, never one who left before it was funded or
	 * joined after it was accepted; each entry is published after that person's FR-66 confirmation.
	 */
	@Test
	void aCancelledJobCreditsTheMentorAndTheMembersActiveDuringAcceptedWork() throws Exception {
		people();
		UUID job = job(true);
		Timestamp funded = Timestamp.from(this.clock.instant().minus(Duration.ofDays(4)));
		Timestamp accepted = Timestamp.from(this.clock.instant().minus(Duration.ofDays(2)));
		this.jdbc.sql("UPDATE job.job SET state = 'CANCELLED', closed_at = NULL, cancelled_at = :at WHERE id = :id")
			.param("at", Timestamp.from(this.clock.instant()))
			.param("id", job)
			.update();
		this.jdbc.sql("UPDATE job.milestone SET funded_at = :funded, accepted_at = :accepted WHERE job_id = :job")
			.param("funded", funded)
			.param("accepted", accepted)
			.param("job", job)
			.update();
		// Vy left a day after the acceptance; a leaver is credited.
		this.jdbc.sql("""
				UPDATE job.crew_row SET released_reason = 'LEFT', window_end = :left, released_at = :left
				WHERE job_id = :job AND member_account_id = :vy
				""").param("left", Timestamp.from(this.clock.instant().minus(Duration.ofDays(1))))
			.param("job", job)
			.param("vy", this.vy)
			.update();
		UUID early = TestPeople.student(this.jdbc, "Rời sớm", true, List.of(TestPeople.REACT), "React",
				LocalDate.parse("2026-09-30"));
		crewRow(job, early, Duration.ofDays(9), Duration.ofDays(5));
		UUID late = TestPeople.student(this.jdbc, "Vào muộn", true, List.of(TestPeople.REACT), "React",
				LocalDate.parse("2026-09-30"));
		crewRow(job, late, Duration.ofDays(1), Duration.ZERO);

		this.listeners.onJobTransitioned(new JobTransitioned(UUID.randomUUID(), UUID.randomUUID(), job, "J9", "SYSTEM",
				"CLOSING", "CANCELLED", 7, 1, Map.of("ratingWindowDays", "14")));
		await().atMost(Duration.ofSeconds(10))
			.until(() -> count("SELECT count(*) FROM reputation.portfolio_entry WHERE job_id = '" + job + "'") == 2);
		assertThat(this.jdbc.sql("SELECT account_id || ':' || role FROM reputation.portfolio_entry WHERE job_id = :job")
			.param("job", job)
			.query(String.class)
			.list()).containsExactlyInAnyOrder(this.hung + ":MENTOR", this.vy + ":CREW");
		assertThat(this.reputation.portfolio(this.vy)).as("published only after the FR-66 confirmation").isEmpty();

		this.listeners.onDataDeletionConfirmed(new DataDeletionConfirmed(UUID.randomUUID(), job, this.vy));
		await().atMost(Duration.ofSeconds(10)).until(() -> !this.reputation.portfolio(this.vy).isEmpty());
		assertThat(this.reputation.portfolio(this.vy)).singleElement()
			.satisfies(entry -> assertThat(entry.role()).isEqualTo("CREW"));
		assertThat(this.reputation.portfolio(this.hung)).isEmpty();
	}

	/** One statement as the migration role, for a column the application may never write. */
	private static void asMigrator(String sql) throws SQLException {
		try (Connection connection = database.migratorConnection(PostgresTestDatabase.DATABASE);
				Statement statement = connection.createStatement()) {
			statement.executeUpdate(sql);
		}
	}

	/** The facts the person's entry froze on the Job, as {@code role:commits:milestonesHandedOver}, {@code -} for none. */
	private String frozenFacts(UUID job, UUID account) {
		return this.jdbc.sql("""
				SELECT role || ':' || coalesce(commits_authored::text, '-') || ':'
				       || coalesce(milestones_handed_over::text, '-')
				FROM reputation.portfolio_entry WHERE job_id = :job AND account_id = :account
				""").param("job", job).param("account", account).query(String.class).single();
	}

	/** A Crew row activated {@code activated} ago and left {@code left} ago (or still active for {@code ZERO}). */
	private void crewRow(UUID job, UUID member, Duration activated, Duration left) {
		Timestamp from = Timestamp.from(this.clock.instant().minus(activated));
		Timestamp to = left.isZero() ? null : Timestamp.from(this.clock.instant().minus(left));
		this.jdbc.sql("""
				INSERT INTO job.crew_row (id, job_id, member_account_id, status, signed_at, activated_at, window_end, released_at,
				    released_reason, created_at)
				VALUES (:id, :job, :member, :status, :from, :from, :to, :to, :reason, :from)
				""")
			.param("id", UUID.randomUUID())
			.param("job", job)
			.param("member", member)
			.param("status", to == null ? "ACTIVE" : "RELEASED")
			.param("from", from)
			.param("to", to)
			.param("reason", to == null ? null : "LEFT")
			.update();
	}

	private void people() {
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		this.hung = TestPeople.mentor(this.jdbc, "Đỗ Mạnh Hùng",
				List.of(TestPeople.REACT), "React");
		this.vy = TestPeople.student(this.jdbc, "Trần Thảo Vy", true, List.of(TestPeople.REACT), "React",
				LocalDate.parse("2026-09-30"));
	}

	private void closes(UUID job) {
		this.listeners.onJobTransitioned(new JobTransitioned(UUID.randomUUID(), UUID.randomUUID(), job, "J9", "SYSTEM",
				"CLOSING", "CLOSED", 7, 1, Map.of("ratingWindowDays", "14")));
	}

	/** A Closed Assessment Job with one Milestone, accepted or not, and the facts its records hold. */
	private UUID job(boolean accepted) {
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
				VALUES (:id, :product, :owner, 'ACTIVE', 'Đánh giá sổ đặt bàn', 'Lỗi', 'Đúng', 'Khách', ARRAY['ASSESSMENT'],
				    'UNDER_2M', DATE '2026-12-01', '{}'::jsonb, 14, :now, :now, :mentor, :application, :now, 5, :now, :now)
				""").param("id", job).param("product", product).param("owner", this.lan).param("mentor", this.hung)
			.param("application", UUID.randomUUID()).param("now", now).update();
		this.jdbc.sql("UPDATE job.job SET state = 'CLOSED', closing_at = :now, closed_at = :now WHERE id = :id")
			.param("id", job).param("now", now).update();
		String content = "{\"scope\":\"Đánh giá\",\"skills\":[{\"id\":\"" + TestPeople.REACT
				+ "\",\"name\":\"React\"}],\"milestones\":[]}";
		this.jdbc.sql("""
				INSERT INTO job.agreement_version (job_id, version_no, status, content, author_account_id, created_at)
				VALUES (:job, 1, 'SHARED', CAST(:content AS JSONB), :mentor, :now)
				""").param("job", job).param("content", content).param("mentor", this.hung).param("now", now).update();
		this.jdbc.sql("""
				INSERT INTO job.confirmed_agreement (job_id, version_no, draft_version_no, content, crew_usernames,
				    freeze_committed, customer_account_id, approved_at, mentor_account_id, confirmed_at)
				VALUES (:job, 1, 1, CAST(:content AS JSONB), '{}', false, :owner, :now, :mentor, :now)
				""")
			.param("job", job)
			.param("content", content)
			.param("owner", this.lan)
			.param("mentor", this.hung)
			.param("now", now)
			.update();
		UUID milestone = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO job.milestone (id, job_id, position, title, price_vnd, due_on, revision_rounds, created_at, state,
				    funded_at, accepted_at)
				VALUES (:id, :job, 0, 'Báo cáo đánh giá', 800000, DATE '2026-11-20', 2, :now, :state, :now, :accepted)
				""")
			.param("id", milestone)
			.param("job", job)
			.param("now", now)
			.param("state", accepted ? "ACCEPTED" : "CANCELLED")
			.param("accepted", accepted ? now : null)
			.update();
		Timestamp signed = Timestamp.from(this.clock.instant().minus(Duration.ofDays(5)));
		this.jdbc.sql("""
				INSERT INTO job.crew_row (id, job_id, member_account_id, status, signed_at, activated_at, window_end, released_at,
				    released_reason, created_at)
				VALUES (:id, :job, :student, 'RELEASED', :signed, :signed, :now, :now, 'JOB_ENDED', now())
				""").param("id", UUID.randomUUID()).param("job", job).param("student", this.vy).param("signed", signed)
			.param("now", now).update();
		// The Milestone's accepted Handover, whose commits are a Crew member's frozen facts (FR-50, Story 24.2).
		UUID handover = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO delivery.handover (id, job_id, product_id, milestone_id, milestone_number, milestone_title, round,
				    mentor_account_id, status, job_version, transition_id, check_by, created_at, decided_at, decided_by)
				VALUES (:id, :job, :product, :milestone, 1, 'Báo cáo đánh giá', 1, :mentor, 'ACCEPTED', 5, :transition, :now,
				    :now, :now, :owner)
				""")
			.param("id", handover)
			.param("job", job)
			.param("product", product)
			.param("milestone", milestone)
			.param("mentor", this.hung)
			.param("owner", this.lan)
			.param("transition", UUID.randomUUID())
			.param("now", now)
			.update();
		Long github = this.jdbc.sql("SELECT github_user_id FROM identity.account WHERE id = :id")
			.param("id", this.vy)
			.query(Long.class)
			.single();
		commit(handover, 0, "a", github);
		commit(handover, 1, "b", github);
		commit(handover, 2, "c", null);
		return job;
	}

	/** One commit of the handover: {@code author} is the GitHub numeric ID, or none when GitHub resolved nobody. */
	private void commit(UUID handover, int position, String sha, @Nullable Long author) {
		this.jdbc.sql("""
				INSERT INTO delivery.handover_commit (handover_id, position, sha, message, author_github_id)
				VALUES (:id, :position, :sha, 'Sửa luồng đặt bàn', :author)
				""").param("id", handover).param("position", position).param("sha", sha.repeat(40)).param("author", author)
			.update();
	}

	private ResultActions read(UUID account, Role role, UUID job) throws Exception {
		return this.mvc.perform(get("/api/v1/boards/" + job + "/portfolio").with(as(account, role)));
	}

	private ResultActions draft(UUID account, Role role, UUID job, String text) throws Exception {
		return this.mvc.perform(post("/api/v1/boards/" + job + "/portfolio/summary").with(as(account, role)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"text\":\"" + text + "\"}"));
	}

	private ResultActions approve(UUID account, Role role, UUID job, String text, boolean show) throws Exception {
		return this.mvc.perform(post("/api/v1/boards/" + job + "/portfolio/summary/approve").with(as(account, role))
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"text\":\"" + text + "\",\"showProductName\":" + show + "}"));
	}

	private boolean openTodo(String key, UUID account, UUID job) {
		return this.jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM notification.todo WHERE todo_key = :key AND account_id = :account AND subject_id = :job
				    AND closed_at IS NULL)
				""").param("key", key).param("account", account).param("job", job).query(Boolean.class).single();
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
