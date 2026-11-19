package vn.vibefix.job.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.TestPeople;

/**
 * FR-28, AD-33, AD-41, UX-DR68: JOB-1's progress, served by {@code job} since Story 24.2a. Each Milestone of
 * the Agreement in force reads its own live state, price and due date, and nothing counts the team's tasks:
 * no {@code done}, {@code total}, {@code inReview}, open item, workload or leaver reaches the page, because
 * the platform no longer tracks them (decision of 2026-11-18). The Mentor also reads the Crew with each
 * member's AD-41 state, a Crew member reads the Crew as empty, a former member reads the Job's header alone,
 * and nobody else reads it at all. FR-10, R6: {@code assessment} tells the Mentor and the active Crew of an
 * Assessment Job that it is one, which is what makes JOB-1 show N-12 and their own link to its private
 * read-only copy (Story 23.11b); the Customer never reads it as one, and a Code Job is never one.
 */
class ProgressIT extends IntegrationTest {

	private static final List<UUID> MILESTONES = List.of(UUID.randomUUID(), UUID.randomUUID());

	private static final List<List<UUID>> CRITERIA = List.of(List.of(UUID.randomUUID(), UUID.randomUUID()),
			List.of(UUID.randomUUID()));

	/** The fields Story 24.2a took out of the answer: every one of them counted the team's own work. */
	private static final List<String> RETIRED = List.of("done", "total", "inReview", "openItems", "mine", "leavers");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	private UUID lan;

	private UUID hung;

	private UUID vy;

	private UUID job;

	@BeforeEach
	void anActiveJobWithOneFundedMilestoneAndOnePlanned() {
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		this.hung = TestPeople.mentor(this.jdbc, "Đỗ Mạnh Hùng", List.of(TestPeople.REACT), "React");
		UUID product = product(this.lan, "Sổ đặt bàn Quán Mây");
		this.job = activeJob(product, this.lan, this.hung, MILESTONES, CRITERIA, "BUG_FIX");
		milestone(MILESTONES.get(0), this.job, 0, "Giai đoạn 1", "FUNDED");
		milestone(MILESTONES.get(1), this.job, 1, "Giai đoạn 2", "PLANNED");
		this.vy = student("Trần Thảo Vy");
		crewRow(this.job, this.vy, "ACTIVE", this.clock.instant().minus(Duration.ofMinutes(5)));
	}

	@Test
	void theCustomerReadsEachMilestonesStateAndNoProgressFigure() throws Exception {
		String body = this.mvc.perform(get("/api/v1/boards/" + this.job + "/progress").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.audience").value("CUSTOMER"))
			.andExpect(jsonPath("$.state").value("ACTIVE"))
			.andExpect(jsonPath("$.productName").value("Sổ đặt bàn Quán Mây"))
			.andExpect(jsonPath("$.mentorName").value("Đỗ Mạnh Hùng"))
			.andExpect(jsonPath("$.milestones.length()").value(2))
			.andExpect(jsonPath("$.milestones[0].state").value("FUNDED"))
			.andExpect(jsonPath("$.milestones[0].priceVnd").value(1500000))
			.andExpect(jsonPath("$.milestones[0].dueOn").value("2026-11-20"))
			.andExpect(jsonPath("$.milestones[0].roundsLeft").value(2))
			.andExpect(jsonPath("$.milestones[1].number").value(2))
			.andExpect(jsonPath("$.milestones[1].state").value("PLANNED"))
			.andExpect(jsonPath("$.crew.length()").value(0))
			.andExpect(jsonPath("$.assessment").value(false))
			.andReturn().getResponse().getContentAsString();
		assertThat(body).doesNotContain("Tiêu chí 1.1");
		assertThat(RETIRED).allSatisfy(field -> assertThat(body).as(field).doesNotContain("\"" + field + "\""));
	}

	/**
	 * The two labels that carried "{done}/{total} tiêu chí đã xong" and the team's "{n} hạng mục chờ duyệt" are
	 * composed in the SPA from the state alone now, so every state must still arrive with everything the words are
	 * built from -- the price, the due date and the Revision Rounds left -- and nothing counted beside it.
	 * {@code SETTLED} is an Administrator's decision on a Dispute ({@code JobSettlements}).
	 */
	@Test
	void everyMilestoneStateReachesTheCustomerWithNothingCountedBesideIt() throws Exception {
		for (String state : List.of("SUBMITTED", "REVISION_REQUESTED", "ACCEPTED", "CANCELLED", "SETTLED")) {
			move(MILESTONES.get(0), state);
			String body = this.mvc.perform(get("/api/v1/boards/" + this.job + "/progress").with(as(this.lan, Role.CUSTOMER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.milestones[0].state").value(state))
				.andExpect(jsonPath("$.milestones[0].priceVnd").value(1500000))
				.andExpect(jsonPath("$.milestones[0].dueOn").value("2026-11-20"))
				// One Round is used while the Milestone is being revised, and none in any other state.
				.andExpect(jsonPath("$.milestones[0].roundsLeft").value("REVISION_REQUESTED".equals(state) ? 1 : 2))
				.andReturn().getResponse().getContentAsString();
			assertThat(RETIRED).allSatisfy(field -> assertThat(body).as(state + " " + field)
				.doesNotContain("\"" + field + "\""));
		}
	}

	/**
	 * Story 24.2a: a Job cancelled before it ever reached Active has no Agreement in force, so its Customer reads the
	 * Job's header with an empty Milestone list instead of the not-found the Board's own lookup used to give.
	 */
	@Test
	void aJobCancelledFromAgreementAnswersItsCustomerWithNoMilestone() throws Exception {
		UUID product = product(this.lan, "Sổ giữ chỗ");
		UUID cancelled = agreementJob(product, this.lan, this.hung);
		this.jdbc.sql("UPDATE job.job SET state = 'CANCELLED' WHERE id = :id").param("id", cancelled).update();

		this.mvc.perform(get("/api/v1/boards/" + cancelled + "/progress").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.audience").value("CUSTOMER"))
			.andExpect(jsonPath("$.state").value("CANCELLED"))
			.andExpect(jsonPath("$.productName").value("Sổ giữ chỗ"))
			.andExpect(jsonPath("$.milestones.length()").value(0))
			.andExpect(jsonPath("$.crew.length()").value(0));
	}

	/** AD-41: the Mentor's Crew list names every member's state, and carries no work of theirs. */
	@Test
	void theMentorReadsTheCrewWithEachMembersStateAndAMemberReadsNoCrew() throws Exception {
		UUID tung = student("Lê Minh Tùng");
		crewRow(this.job, tung, "ASSIGNED", null);
		UUID ngan = student("Hoàng Kim Ngân");
		crewRow(this.job, ngan, "ACTIVE", this.clock.instant().minus(Duration.ofDays(2)));
		release(this.job, ngan);

		String body = this.mvc.perform(get("/api/v1/boards/" + this.job + "/progress").with(as(this.hung, Role.MENTOR)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.audience").value("MENTOR"))
			.andExpect(jsonPath("$.crew.length()").value(3))
			.andExpect(jsonPath("$.crew[?(@.name == 'Trần Thảo Vy')].state").value("ACTIVE"))
			.andExpect(jsonPath("$.crew[?(@.name == 'Lê Minh Tùng')].state").value("ASSIGNED"))
			.andExpect(jsonPath("$.crew[?(@.name == 'Hoàng Kim Ngân')].state").value("RELEASED"))
			.andReturn().getResponse().getContentAsString();
		assertThat(RETIRED).allSatisfy(field -> assertThat(body).as(field).doesNotContain("\"" + field + "\""));

		this.mvc.perform(get("/api/v1/boards/" + this.job + "/progress").with(as(this.vy, Role.STUDENT)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.audience").value("CREW"))
			.andExpect(jsonPath("$.milestones.length()").value(2))
			.andExpect(jsonPath("$.crew.length()").value(0));
	}

	/**
	 * AD-41: a former member, one whose lock counted as leaving included, keeps JOB-1 for their post-Job duties and
	 * reads the Job's header alone -- nothing from after their time.
	 */
	@Test
	void aFormerMemberReadsTheJobsHeaderOnly() throws Exception {
		release(this.job, this.vy);

		this.mvc.perform(get("/api/v1/boards/" + this.job + "/progress").with(as(this.vy, Role.STUDENT)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.audience").value("FORMER"))
			.andExpect(jsonPath("$.state").value("ACTIVE"))
			.andExpect(jsonPath("$.title").value("Sửa lỗi đặt bàn"))
			.andExpect(jsonPath("$.mentorName").value("Đỗ Mạnh Hùng"))
			.andExpect(jsonPath("$.milestones.length()").value(0))
			.andExpect(jsonPath("$.crew.length()").value(0))
			.andExpect(jsonPath("$.assessment").value(false));
	}

	/**
	 * The flag the team's N-12 notice and their read-only-copy link hang on (FR-10, Story 23.11b): true for the
	 * Mentor and an {@code ACTIVE} member of an Assessment Job, false for that Job's own Customer, and false on a
	 * Job of any other type.
	 */
	@Test
	void jobOneNamesAnAssessmentJobForItsTeamAndForNobodyElse() throws Exception {
		UUID bao = student("Phạm Quốc Bảo");
		UUID assessment = assessmentJob(bao);

		for (RequestPostProcessor team : List.of(as(this.hung, Role.MENTOR), as(bao, Role.STUDENT))) {
			this.mvc.perform(get("/api/v1/boards/" + assessment + "/progress").with(team))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.assessment").value(true));
		}
		this.mvc.perform(get("/api/v1/boards/" + assessment + "/progress").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.assessment").value(false));
		this.mvc.perform(get("/api/v1/boards/" + this.job + "/progress").with(as(this.hung, Role.MENTOR)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.assessment").value(false));
	}

	@Test
	void nobodyElseReadsTheProgress() throws Exception {
		UUID otherCustomer = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		UUID tung = student("Lê Minh Tùng");
		crewRow(this.job, tung, "ASSIGNED", null);
		UUID admin = IdentityTestSupport.insertAccount(this.jdbc, Role.ADMINISTRATOR, null);
		for (RequestPostProcessor outsider : List.of(as(otherCustomer, Role.CUSTOMER), as(tung, Role.STUDENT),
				as(admin, Role.ADMINISTRATOR))) {
			this.mvc.perform(get("/api/v1/boards/" + this.job + "/progress").with(outsider)).andExpect(status().isNotFound());
		}
		this.mvc.perform(get("/api/v1/boards/" + UUID.randomUUID() + "/progress").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(status().isNotFound());
	}

	// ---- The world ------------------------------------------------------------------------------

	/**
	 * A second Active Job of the same Customer and Mentor, this one an Assessment, with {@code member} on its Crew.
	 * The member is its own: a Student runs one Job at a time (AD-41).
	 */
	private UUID assessmentJob(UUID member) {
		UUID product = product(this.lan, "Sổ đánh giá");
		UUID milestone = UUID.randomUUID();
		UUID assessment = activeJob(product, this.lan, this.hung, List.of(milestone),
				List.of(List.of(UUID.randomUUID())), "ASSESSMENT");
		milestone(milestone, assessment, 0, "Giai đoạn 1", "PLANNED");
		crewRow(assessment, member, "ACTIVE", this.clock.instant().minus(Duration.ofMinutes(5)));
		return assessment;
	}

	private UUID product(UUID owner, String name) {
		UUID id = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO product.product (id, customer_account_id, name, description, ai_tools, repository_github_id,
				    repository_full_name, repository_owner_type, repository_default_branch, public_notice_version,
				    copy_permission_version, detection_notice_version, acknowledged_at, created_at)
				VALUES (:id, :owner, :name, 'Đặt bàn cho quán.', ARRAY['LOVABLE'], :github, 'lan/so-dat-ban', 'User', 'main',
				    '1.0', '1.0', '1.0', now(), now())
				""")
			.param("id", id)
			.param("owner", owner)
			.param("name", name)
			.param("github", ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE))
			.update();
		return id;
	}

	/**
	 * A Job that reached Agreement and never became Active: its Mentor is chosen, nothing is confirmed, so it has no
	 * Agreement in force and no Milestone of its own.
	 */
	private UUID agreementJob(UUID product, UUID owner, UUID mentor) {
		UUID id = UUID.randomUUID();
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, budget, deadline, rules, selection_days, opened_at, lapses_at, mentor_account_id,
				    selected_application_id, selected_at, version, created_at, updated_at)
				VALUES (:id, :product, :owner, 'AGREEMENT', 'Giữ chỗ theo giờ', 'Lỗi', 'Đúng', 'Khách', ARRAY['BUG_FIX'],
				    'UNDER_2M', DATE '2026-12-01', '{}'::jsonb, 14, :now, :lapses, :mentor, :application, :now, 3, :now, :now)
				""")
			.param("id", id)
			.param("product", product)
			.param("owner", owner)
			.param("now", now)
			.param("lapses", Timestamp.from(this.clock.instant().plus(Duration.ofDays(14))))
			.param("mentor", mentor)
			.param("application", UUID.randomUUID())
			.update();
		return id;
	}

	/** An Active Job of {@code jobType} whose Mentor is {@code mentor}, with Agreement version 1 confirmed. */
	private UUID activeJob(UUID product, UUID owner, UUID mentor, List<UUID> milestones, List<List<UUID>> criteria,
			String jobType) {
		UUID id = UUID.randomUUID();
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, budget, deadline, rules, selection_days, opened_at, lapses_at, mentor_account_id,
				    selected_application_id, selected_at, agreement_version_no, version, created_at, updated_at)
				VALUES (:id, :product, :owner, 'ACTIVE', 'Sửa lỗi đặt bàn', 'Lỗi', 'Đúng', 'Khách', ARRAY[:type],
				    'UNDER_2M', DATE '2026-12-01', '{}'::jsonb, 14, :now, :lapses, :mentor, :application, :now, 1, 5, :now, :now)
				""")
			.param("id", id)
			.param("product", product)
			.param("owner", owner)
			.param("now", now)
			.param("lapses", Timestamp.from(this.clock.instant().plus(Duration.ofDays(14))))
			.param("mentor", mentor)
			.param("application", UUID.randomUUID())
			.param("type", jobType)
			.update();
		String content = content(milestones, criteria);
		this.jdbc.sql("""
				INSERT INTO job.agreement_version (job_id, version_no, status, content, author_account_id, created_at)
				VALUES (:job, 1, 'SHARED', CAST(:content AS JSONB), :mentor, :now)
				""").param("job", id).param("content", content).param("mentor", mentor).param("now", now).update();
		this.jdbc.sql("""
				INSERT INTO job.confirmed_agreement (job_id, version_no, draft_version_no, content, crew_usernames,
				    freeze_committed, customer_account_id, approved_at, mentor_account_id, confirmed_at)
				VALUES (:job, 1, 1, CAST(:content AS JSONB), '{}', true, :owner, :now, :mentor, :now)
				""").param("job", id).param("content", content).param("owner", owner).param("mentor", mentor)
			.param("now", now).update();
		return id;
	}

	private static String content(List<UUID> milestones, List<List<UUID>> criteria) {
		StringBuilder content = new StringBuilder("{\"scope\":\"Sửa lỗi\",\"priceVnd\":3000000,\"codeJob\":true,")
			.append("\"assessment\":false,\"skills\":[],\"externalAccess\":[],\"milestones\":[");
		for (int m = 0; m < milestones.size(); m++) {
			content.append(m == 0 ? "" : ",")
				.append("{\"id\":\"%s\",\"title\":\"Giai đoạn %d\",\"priceVnd\":1500000,\"dueOn\":\"2026-11-20\","
					.formatted(milestones.get(m), m + 1))
				.append("\"revisionRounds\":2,\"criteria\":[");
			for (int c = 0; c < criteria.get(m).size(); c++) {
				content.append(c == 0 ? "" : ",")
					.append("{\"id\":\"%s\",\"statement\":\"Tiêu chí %d.%d\",\"checkSteps\":\"Cách kiểm tra %d.%d\"}"
						.formatted(criteria.get(m).get(c), m + 1, c + 1, m + 1, c + 1));
			}
			content.append("]}");
		}
		return content.append("]}").toString();
	}

	private void milestone(UUID id, UUID job, int position, String title, String state) {
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO job.milestone (id, job_id, position, title, price_vnd, revision_rounds, created_at, state, funded_at)
				VALUES (:id, :job, :position, :title, 1500000, 2, :now, :state, :funded)
				""")
			.param("id", id)
			.param("job", job)
			.param("position", position)
			.param("title", title)
			.param("state", state)
			.param("funded", "PLANNED".equals(state) ? null : now)
			.param("now", now)
			.update();
	}

	/** The Milestone in {@code state}, with the one column each state's own check asks for. */
	private void move(UUID milestone, String state) {
		Timestamp now = Timestamp.from(this.clock.instant());
		boolean submitted = "SUBMITTED".equals(state);
		boolean revision = "REVISION_REQUESTED".equals(state);
		this.jdbc.sql("""
				UPDATE job.milestone
				SET state = :state, funded_at = coalesce(funded_at, :now), submitted_at = :submitted,
				    handover_id = :handover, accepted_at = :accepted, settled_at = :settled, revision_criteria = :criteria,
				    revision_rounds_used = :used
				WHERE id = :id
				""")
			.param("id", milestone)
			.param("state", state)
			.param("now", now)
			.param("submitted", submitted ? now : null)
			.param("handover", submitted ? UUID.randomUUID() : null)
			.param("accepted", "ACCEPTED".equals(state) ? now : null)
			.param("settled", "SETTLED".equals(state) ? now : null)
			.param("criteria", revision ? 1 : null)
			.param("used", revision ? 1 : 0)
			.update();
	}

	private UUID student(String name) {
		return TestPeople.student(this.jdbc, name, true, List.of(TestPeople.REACT), "React",
				LocalDate.parse("2026-09-30"));
	}

	/** A Crew row in {@code status}; every row but ASSIGNED signed and became ACTIVE at {@code signed}. */
	private void crewRow(UUID job, UUID student, String status, @Nullable Instant signed) {
		this.jdbc.sql("""
				INSERT INTO job.crew_row (id, job_id, member_account_id, status, signed_at, activated_at, created_at)
				VALUES (:id, :job, :student, :status, :signed, :signed, now())
				""")
			.param("id", UUID.randomUUID())
			.param("job", job)
			.param("student", student)
			.param("status", status)
			.param("signed", signed == null ? null : Timestamp.from(signed))
			.update();
	}

	/** The member's row released with its window closed, which is what makes them {@code FORMER} (AD-41). */
	private void release(UUID job, UUID student) {
		this.jdbc.sql("""
				UPDATE job.crew_row SET status = 'RELEASED', released_at = :now, released_reason = 'LEFT', window_end = :now
				WHERE job_id = :job AND member_account_id = :student
				""")
			.param("job", job)
			.param("student", student)
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
	}

	private RequestPostProcessor as(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

}
