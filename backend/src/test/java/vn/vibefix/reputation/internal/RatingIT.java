package vn.vibefix.reputation.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
import vn.vibefix.job.JobTransitioned;
import vn.vibefix.reputation.PrivateFeedback;
import vn.vibefix.reputation.RatingSummary;
import vn.vibefix.reputation.Reputation;
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.team.internal.TeamTestSupport;
import vn.vibefix.timers.internal.DeadlinePoller;

/**
 * FR-49, FR-78, AD-33, NFR-1: J9 opens the Rating Window for the Job's copied days; the Customer and
 * the Mentor rate each other blind until both rated, the Mentor's Rating of a Crew member stays hidden
 * until the end, private feedback reaches nobody but Administrators, the window's end publishes the
 * rest, and only published Ratings count in the visible summary.
 */
class RatingIT extends IntegrationTest {

	private static final String CANARY = "phản hồi kín canary 6-7";

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

	@Autowired
	private PrivateFeedback privateFeedback;

	@Autowired
	private ReputationPersonalData personalData;

	@Test
	void theWindowKeepsRatingsBlindAndPrivateFeedbackPrivate() throws Exception {
		UUID lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		UUID hung = TeamTestSupport.mentor(this.jdbc, "Đỗ Mạnh Hùng",
				List.of(TeamTestSupport.REACT), "React");
		UUID vy = student("Trần Thảo Vy");
		UUID never = student("Ngô Chưa Ký");
		UUID job = job(lan, hung);
		crewRow(job, vy, true);
		crewRow(job, never, false);

		rate(lan, Role.CUSTOMER, job, hung, 5, "Tuyệt vời").andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("RATING_WINDOW_NOT_OPEN"));
		read(vy, Role.STUDENT, job).andExpect(status().isOk())
			.andExpect(jsonPath("$.open").value(false))
			.andExpect(jsonPath("$.feedbackDue").value(false));

		this.listeners.onJobTransitioned(new JobTransitioned(UUID.randomUUID(), UUID.randomUUID(), job, "J9", "SYSTEM",
				"CLOSING", "CLOSED", 7, 1, Map.of("ratingWindowDays", "10")));
		await().atMost(Duration.ofSeconds(10)).until(() -> openTodos() == 3);
		assertThat(this.jdbc.sql("""
				SELECT extract(day FROM ends_at - opened_at)::int FROM reputation.rating_window WHERE job_id = :job
				""").param("job", job).query(Integer.class).single()).isEqualTo(10);
		assertThat(this.jdbc.sql("""
				SELECT type || ':' || count(*) FROM notification.notification WHERE type LIKE 'reputation.%' GROUP BY type
				ORDER BY type
				""").query(String.class).list())
			.containsExactly("reputation.private_feedback_invited:1", "reputation.rating_window_opened:2");
		assertThat(this.jdbc.sql("SELECT count(*) FROM notification.todo WHERE account_id = :never")
			.param("never", never).query(Long.class).single()).isZero();
		read(never, Role.STUDENT, job).andExpect(status().isNotFound());

		rate(lan, Role.CUSTOMER, job, hung, 5, "Tuyệt vời").andExpect(status().isCreated())
			.andExpect(jsonPath("$.toRate.length()").value(0))
			.andExpect(jsonPath("$.ratings.length()").value(1))
			.andExpect(jsonPath("$.ratings[0].mine").value(true))
			.andExpect(jsonPath("$.hiddenUntil").isNotEmpty());
		rate(lan, Role.CUSTOMER, job, hung, 4, "Lần nữa").andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("ALREADY_SUBMITTED"));
		rate(lan, Role.CUSTOMER, job, vy, 4, "Không được").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reason").value("RATEE_NOT_ALLOWED"));
		rate(vy, Role.STUDENT, job, hung, 4, "Không được").andExpect(status().isForbidden())
			.andExpect(jsonPath("$.reason").value("NOT_A_RATER"));

		read(hung, Role.MENTOR, job).andExpect(status().isOk())
			.andExpect(jsonPath("$.ratings.length()").value(0))
			.andExpect(jsonPath("$.toRate.length()").value(2))
			.andExpect(jsonPath("$.toRate[0].role").value("CUSTOMER"))
			.andExpect(jsonPath("$.toRate[1].name").value("Trần Thảo Vy"))
			.andExpect(jsonPath("$.hiddenUntil").isNotEmpty());
		rate(hung, Role.MENTOR, job, vy, 4, "Chăm chỉ").andExpect(status().isCreated());
		read(vy, Role.STUDENT, job).andExpect(status().isOk())
			.andExpect(jsonPath("$.ratings.length()").value(0))
			.andExpect(jsonPath("$.feedbackDue").value(true))
			.andExpect(jsonPath("$.hiddenUntil").isNotEmpty());

		feedback(vy, Role.STUDENT, job, 2, CANARY).andExpect(status().isNoContent());
		feedback(vy, Role.STUDENT, job, 1, "Lần nữa").andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("ALREADY_SUBMITTED"));
		feedback(hung, Role.MENTOR, job, 5, "Tự khen").andExpect(status().isNotFound());
		read(vy, Role.STUDENT, job).andExpect(jsonPath("$.feedbackGiven").value(true))
			.andExpect(jsonPath("$.feedbackDue").value(false));
		String mentorView = read(hung, Role.MENTOR, job).andReturn().getResponse().getContentAsString();
		assertThat(mentorView).doesNotContain(CANARY).doesNotContain("\"feedbackGiven\":true");

		rate(hung, Role.MENTOR, job, lan, 5, "Rõ ràng").andExpect(status().isCreated())
			.andExpect(jsonPath("$.hiddenUntil").doesNotExist());
		read(lan, Role.CUSTOMER, job).andExpect(jsonPath("$.ratings.length()").value(2))
			.andExpect(jsonPath("$.ratings[?(@.mine == false)].comment").value("Rõ ràng"))
			.andExpect(jsonPath("$.hiddenUntil").doesNotExist());
		assertThat(this.reputation.visibleSummary(hung)).isEqualTo(new RatingSummary(new BigDecimal("5.0"), 1));
		assertThat(this.reputation.visibleSummary(vy)).isEqualTo(RatingSummary.NONE);
		assertThat(openTodos()).isZero();
		assertThat(this.jdbc.sql("SELECT count(*) FROM notification.notification WHERE type = 'reputation.ratings_published'")
			.query(Long.class).single()).isEqualTo(2);

		this.clock.advance(Duration.ofDays(11));
		this.poller.runDue();
		read(vy, Role.STUDENT, job).andExpect(jsonPath("$.ratings.length()").value(1))
			.andExpect(jsonPath("$.ratings[0].stars").value(4))
			.andExpect(jsonPath("$.open").value(false))
			.andExpect(jsonPath("$.hiddenUntil").doesNotExist());
		assertThat(this.reputation.visibleSummary(vy)).isEqualTo(new RatingSummary(new BigDecimal("4.0"), 1));
		rate(hung, Role.MENTOR, job, lan, 3, "Muộn").andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("RATING_WINDOW_CLOSED"));
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM notification.notification WHERE type = 'reputation.ratings_published' AND account_id = :vy
				""").param("vy", vy).query(Long.class).single()).isEqualTo(1);

		PrivateFeedback.Summary summary = this.privateFeedback.summary(hung).orElseThrow();
		assertThat(summary.count()).isEqualTo(1);
		assertThat(summary.average()).isEqualByComparingTo("2.0");
		assertThat(summary.comments()).containsExactly(CANARY);
		assertThat(this.privateFeedback.statistics()).isEmpty();
		assertThat(this.personalData.export(hung).orElseThrow().toString()).doesNotContain(CANARY);
		assertThat(this.personalData.export(vy).orElseThrow().toString()).contains(CANARY);
	}

	private ResultActions rate(UUID account, Role role, UUID job, UUID ratee, int stars, String comment) throws Exception {
		return this.mvc.perform(post("/api/v1/boards/" + job + "/ratings").with(as(account, role)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"rateeId\":\"" + ratee + "\",\"stars\":" + stars + ",\"comment\":\"" + comment + "\"}"));
	}

	private ResultActions feedback(UUID account, Role role, UUID job, int stars, String comment) throws Exception {
		return this.mvc.perform(post("/api/v1/boards/" + job + "/private-feedback").with(as(account, role)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"stars\":" + stars + ",\"comment\":\"" + comment + "\"}"));
	}

	private ResultActions read(UUID account, Role role, UUID job) throws Exception {
		return this.mvc.perform(get("/api/v1/boards/" + job + "/ratings").with(as(account, role)));
	}

	private long openTodos() {
		return this.jdbc.sql("""
				SELECT count(*) FROM notification.todo WHERE todo_key LIKE 'reputation.%' AND closed_at IS NULL
				""").query(Long.class).single();
	}

	private UUID student(String name) {
		return TeamTestSupport.student(this.jdbc, name, true, List.of(TeamTestSupport.REACT), "React", LocalDate.parse("2026-09-30"));
	}

	private UUID job(UUID owner, UUID mentor) {
		UUID product = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO product.product (id, customer_account_id, name, description, ai_tools, repository_github_id,
				    repository_full_name, repository_owner_type, repository_default_branch, public_notice_version,
				    copy_permission_version, detection_notice_version, acknowledged_at, created_at)
				VALUES (:id, :owner, 'Sổ đặt bàn', 'Đặt bàn.', ARRAY['LOVABLE'], :github, 'lan/so-dat-ban', 'User', 'main',
				    '1.0', '1.0', '1.0', now(), now())
				""").param("id", product).param("owner", owner)
			.param("github", ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE)).update();
		UUID job = UUID.randomUUID();
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, budget, deadline, rules, selection_days, opened_at, lapses_at, mentor_account_id,
				    selected_application_id, selected_at, version, created_at, updated_at)
				VALUES (:id, :product, :owner, 'ACTIVE', 'Đánh giá sổ đặt bàn', 'Lỗi', 'Đúng', 'Khách', ARRAY['ASSESSMENT'],
				    'UNDER_2M', DATE '2026-12-01', '{"RATING_WINDOW_DAYS": 10}'::jsonb, 14, :now, :now, :mentor, :application,
				    :now, 5, :now, :now)
				""").param("id", job).param("product", product).param("owner", owner).param("mentor", mentor)
			.param("application", UUID.randomUUID()).param("now", now).update();
		this.jdbc.sql("UPDATE job.job SET state = 'CLOSED', closing_at = :now, closed_at = :now WHERE id = :id")
			.param("id", job).param("now", now).update();
		return job;
	}

	private void crewRow(UUID job, UUID student, boolean wasActive) {
		Timestamp signed = Timestamp.from(this.clock.instant().minus(Duration.ofDays(5)));
		this.jdbc.sql("""
				INSERT INTO job.crew_row (id, job_id, member_account_id, status, signed_at, activated_at, window_end, released_at,
				    released_reason, created_at)
				VALUES (:id, :job, :student, 'RELEASED', :signed, :activated, :end, :released, :reason, now())
				""")
			.param("id", UUID.randomUUID())
			.param("job", job)
			.param("student", student)
			.param("signed", wasActive ? signed : null)
			.param("activated", wasActive ? signed : null)
			.param("end", wasActive ? Timestamp.from(this.clock.instant()) : null)
			.param("released", Timestamp.from(this.clock.instant()))
			.param("reason", wasActive ? "JOB_ENDED" : "DECLINED")
			.update();
	}

	private RequestPostProcessor as(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

}
