package vn.vibefix.matching.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import vn.vibefix.authz.JobContent;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.MutableClock;
import vn.vibefix.team.internal.TeamTestSupport;

/**
 * CUS-8's Bid cards and {@code matching.fitMetrics} (FR-15, AD-17, X12, UX-DR100, Story 21.3): bids are listed oldest
 * first and never reordered, an invitation changes nothing; each card carries the bidder's Verified facts and the Fit
 * metrics, each empty one empty rather than a score, and the Milestones accepted on time as Mentor are x/y by FR-62's
 * formula.
 */
class BidCardsIT extends IntegrationTest {

	private static final Instant MONDAY = Instant.parse("2026-01-05T02:00:00Z");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	private UUID lan;

	private UUID product;

	private UUID job;

	@BeforeEach
	void aCustomerWithAnOpenJobPost() {
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		this.product = JobContent.product(this.jdbc, this.lan, "Sổ đặt bàn Quán Mây");
		this.job = UUID.randomUUID();
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, skill_ids, skill_names, budget, deadline, rules, selection_days, opened_at, lapses_at, version,
				    created_at, updated_at)
				VALUES (:id, :product, :owner, 'OPEN', 'Sửa lỗi mất đơn hàng', 'Lỗi', 'Đúng', 'Khách', ARRAY['BUG_FIX'],
				    CAST(:skills AS UUID[]), ARRAY['React', 'Supabase'], 'UNDER_2M', DATE '2026-12-01', '{}'::jsonb, 14, :now,
				    :lapses, 1, :now, :now)
				""")
			.param("id", this.job)
			.param("product", this.product)
			.param("owner", this.lan)
			.param("skills", "{" + TeamTestSupport.REACT + "," + TeamTestSupport.SUPABASE + "}")
			.param("now", now)
			.param("lapses", Timestamp.from(this.clock.instant().plus(Duration.ofDays(14))))
			.update();
	}

	@Test
	void bidsAreListedOldestFirstAndAnInvitationChangesNothing() throws Exception {
		UUID mon = TeamTestSupport.mentor(this.jdbc, "Bùi Thứ Hai", List.of(TeamTestSupport.REACT), "React");
		UUID tue = TeamTestSupport.mentor(this.jdbc, "Cao Thứ Ba", List.of(TeamTestSupport.REACT), "React");
		UUID wed = TeamTestSupport.mentor(this.jdbc, "An Thứ Tư", List.of(TeamTestSupport.REACT), "React");
		this.mvc.perform(post("/api/v1/jobs/" + this.job + "/invitations").with(as(this.lan, Role.CUSTOMER)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content("{\"mentorId\":\"" + wed + "\"}")).andExpect(status().isCreated());
		// Written out of order, and the invited bidder written first: neither changes the order.
		bid(wed, MONDAY.plus(Duration.ofDays(2)), 900_000);
		bid(mon, MONDAY, 3_000_000);
		bid(tue, MONDAY.plus(Duration.ofDays(1)), 500_000);

		String body = cards().andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(3))
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(JsonPath.<List<String>>read(body, "$[*].mentor.displayName")).containsExactly("Bùi Thứ Hai", "Cao Thứ Ba",
				"An Thứ Tư");
		assertThat(JsonPath.<List<Boolean>>read(body, "$[*].invited")).containsExactly(false, false, true);
		assertThat(body).as("no score, rank or Team size").doesNotContain("score", "rank", "teamSize", mon.toString(),
				tue.toString(), wed.toString());
	}

	@Test
	void aNewUserHasNoHistoryMetricAndIsNew() throws Exception {
		UUID newbie = TeamTestSupport.mentor(this.jdbc, "Người Mới", List.of(TeamTestSupport.REACT), "React");
		bid(newbie, MONDAY, 800_000);

		cards().andExpect(status().isOk())
			.andExpect(jsonPath("$[0].fitMetrics.sharedSkills.length()").value(1))
			.andExpect(jsonPath("$[0].fitMetrics.sharedSkills[0]").value("React"))
			.andExpect(jsonPath("$[0].fitMetrics.skillsSought").value(2))
			.andExpect(jsonPath("$[0].fitMetrics.jobTypeMatches.length()").value(0))
			.andExpect(jsonPath("$[0].fitMetrics.jobsAsMentor").value(0))
			.andExpect(jsonPath("$[0].fitMetrics.jobsAsCrew").value(0))
			.andExpect(jsonPath("$[0].fitMetrics.onTime").value(Matchers.nullValue()))
			.andExpect(jsonPath("$[0].fitMetrics.activeJobsAsMentor").value(0))
			.andExpect(jsonPath("$[0].fitMetrics.verifications.length()").value(1))
			.andExpect(jsonPath("$[0].fitMetrics.verifications[0]").value("GITHUB"))
			.andExpect(jsonPath("$[0].mentor.memberSince").value("2026-01-05T03:00:00Z"));
		this.mvc.perform(get("/api/v1/reputation/histories?username=" + TeamTestSupport.login(this.jdbc, newbie)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].newcomer").value(true))
			.andExpect(jsonPath("$[0].mentor.jobs").value(0))
			.andExpect(jsonPath("$[0].mentor.count").value(0))
			.andExpect(jsonPath("$[0].crew.jobs").value(0));
	}

	@Test
	void theMetricsCountTheHistoryInBothRolesAndTheOnTimeMilestonesAsMentor() throws Exception {
		UUID hung = TeamTestSupport.student(this.jdbc, "Đỗ Mạnh Hùng", true,
				List.of(TeamTestSupport.SUPABASE, TeamTestSupport.REACT), "Supabase, React", LocalDate.parse("2026-09-30"));
		UUID other = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		UUID otherProduct = JobContent.product(this.jdbc, other, "Ứng dụng khác");
		// As Mentor: one Job closed with four accepted dated Milestones, three handed over by the end of the due day in
		// Vietnam (23:30 on it counts, 00:30 the next day does not); an undated one and an unaccepted one do not count.
		UUID mentored = JobContent.job(this.jdbc, otherProduct, other, hung, "Việc đã hướng dẫn", JobContent.SIGNED,
				"CLOSED");
		milestone(mentored, 0, "2025-12-01", "2025-11-30T10:00:00Z", "ACCEPTED");
		milestone(mentored, 1, "2025-12-02", "2025-12-02T16:30:00Z", "ACCEPTED");
		milestone(mentored, 2, "2025-12-03", "2025-12-03T16:59:59Z", "ACCEPTED");
		milestone(mentored, 3, "2025-12-04", "2025-12-04T17:30:00Z", "ACCEPTED");
		milestone(mentored, 4, null, "2025-12-05T10:00:00Z", "ACCEPTED");
		milestone(mentored, 5, "2025-12-06", null, "CANCELLED");
		// As Crew member: one Job of another Mentor closed with Accepted work done while Hùng was active on it.
		UUID tam = TeamTestSupport.mentor(this.jdbc, "Phan Văn Tâm", List.of(TeamTestSupport.REACT), "React");
		UUID crewed = JobContent.job(this.jdbc, otherProduct, other, tam, "Việc làm thành viên nhóm", JobContent.SIGNED,
				"CLOSED");
		milestone(crewed, 0, "2025-12-30", "2025-12-28T10:00:00Z", "ACCEPTED");
		JobContent.crewRow(this.jdbc, crewed, hung, "JOB_ENDED", JobContent.SIGNED.plus(Duration.ofDays(5)));
		// Now: Mentor of one running Job.
		JobContent.activeJob(this.jdbc, otherProduct, other, hung, "Việc đang hướng dẫn");
		bid(hung, MONDAY, 800_000);

		String body = cards().andExpect(status().isOk())
			.andExpect(jsonPath("$[0].fitMetrics.sharedSkills.length()").value(2))
			.andExpect(jsonPath("$[0].fitMetrics.sharedSkills[0]").value("React"))
			.andExpect(jsonPath("$[0].fitMetrics.sharedSkills[1]").value("Supabase"))
			.andExpect(jsonPath("$[0].fitMetrics.jobTypeMatches.length()").value(1))
			.andExpect(jsonPath("$[0].fitMetrics.jobTypeMatches[0].jobType").value("BUG_FIX"))
			.andExpect(jsonPath("$[0].fitMetrics.jobTypeMatches[0].asMentor").value(1))
			.andExpect(jsonPath("$[0].fitMetrics.jobTypeMatches[0].asCrew").value(1))
			.andExpect(jsonPath("$[0].fitMetrics.jobsAsMentor").value(1))
			.andExpect(jsonPath("$[0].fitMetrics.jobsAsCrew").value(1))
			.andExpect(jsonPath("$[0].fitMetrics.onTime.onTime").value(3))
			.andExpect(jsonPath("$[0].fitMetrics.onTime.total").value(4))
			.andExpect(jsonPath("$[0].fitMetrics.activeJobsAsMentor").value(1))
			.andExpect(jsonPath("$[0].fitMetrics.verifications[0]").value("GITHUB"))
			.andExpect(jsonPath("$[0].fitMetrics.verifications[1]").value("STUDENT"))
			.andExpect(jsonPath("$[0].mentor.verified[1].school").value(TeamTestSupport.SCHOOL))
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(body).as("the history names no Job, Customer or Mentor of it")
			.doesNotContain("Việc đã hướng dẫn", "Việc làm thành viên nhóm", "Việc đang hướng dẫn", "Phan Văn Tâm",
					other.toString(), mentored.toString(), crewed.toString());
	}

	/** A bid of {@code bidder}, submitted at {@code at}, written as {@code Applications.submit} writes it. */
	private void bid(UUID bidder, Instant at, long price) {
		this.jdbc.sql("""
				INSERT INTO matching.application (id, job_id, product_id, customer_account_id, mentor_account_id, status,
				    price_vnd, timeline_days, approach, public_code_notice_version, submitted_at)
				VALUES (:id, :job, :product, :customer, :mentor, 'SUBMITTED', :price, 7, 'Tái hiện lỗi rồi sửa.', '1.0', :at)
				""")
			.param("id", UUID.randomUUID())
			.param("job", this.job)
			.param("product", this.product)
			.param("customer", this.lan)
			.param("mentor", bidder)
			.param("price", price)
			.param("at", Timestamp.from(at))
			.update();
	}

	/** A Milestone in {@code state} due on {@code dueOn}, handed over at {@code submitted}, accepted a day after. */
	private void milestone(UUID job, int position, String dueOn, String submitted, String state) {
		Instant handedOver = submitted == null ? null : Instant.parse(submitted);
		this.jdbc.sql("""
				INSERT INTO job.milestone (id, job_id, position, title, price_vnd, due_on, revision_rounds, created_at, state,
				    funded_at, crew_terms_version_no, submitted_at, handover_id, accepted_at)
				VALUES (:id, :job, :position, 'Giai đoạn', 500000, CAST(:due AS DATE), 2, :created, :state, :funded, 1,
				    :submitted, :handover, :accepted)
				""")
			.param("id", UUID.randomUUID())
			.param("job", job)
			.param("position", position)
			.param("due", dueOn)
			.param("created", Timestamp.from(JobContent.SIGNED))
			.param("state", state)
			.param("funded", Timestamp.from(JobContent.SIGNED))
			.param("submitted", handedOver == null ? null : Timestamp.from(handedOver))
			.param("handover", handedOver == null ? null : UUID.randomUUID())
			.param("accepted", "ACCEPTED".equals(state) ? Timestamp.from(handedOver.plus(Duration.ofDays(1))) : null)
			.update();
	}

	private ResultActions cards() throws Exception {
		return this.mvc.perform(get("/api/v1/jobs/" + this.job + "/applications").with(as(this.lan, Role.CUSTOMER)));
	}

	private RequestPostProcessor as(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, MutableClock.DEFAULT_INSTANT);
	}

}
