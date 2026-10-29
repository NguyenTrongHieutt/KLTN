package vn.vibefix.job.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.authz.JobContent;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.job.internal.JobLifecycle.Actor;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.TodoRequest;
import vn.vibefix.notification.Todos;
import vn.vibefix.shared.PersonalData;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.team.internal.TeamTestSupport;

/**
 * Story 21.4 (FR-81, AD-3, AD-41, R2, R7, UX-DR102), with {@code features.largeCrews} on: the Mentor of an Agreement
 * Job turns recruitment on from JOB-9 under the cap of 5 unreleased rows, edits it and turns it off, each write under
 * the Job row lock bumping {@code crew_version} and never the Customer's {@code version}; the Customer and everyone else
 * find nothing; J8 and J10 turn it off; and each closing lapses the Job's pending Crew Applications through
 * {@code CrewRecruitmentClosed}.
 */
@TestPropertySource(properties = "vibefix.features.large-crews=true")
class CrewRecruitmentIT extends IntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private JobLifecycle lifecycle;

	@Autowired
	private Todos todos;

	@Autowired
	private PersonalData personalData;

	@Autowired
	private PlatformTransactionManager transactions;

	@Autowired
	private JobPersonalData jobPersonalData;

	private UUID lan;

	private UUID hung;

	private UUID product;

	private UUID job;

	@BeforeEach
	void anAgreementJobWithItsMentor() {
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		this.hung = TeamTestSupport.mentor(this.jdbc, "Đỗ Mạnh Hùng", List.of(TeamTestSupport.REACT), "React");
		this.product = JobContent.product(this.jdbc, this.lan, "Sổ đặt bàn Quán Mây");
		this.job = JobContent.job(this.jdbc, this.product, this.lan, this.hung, "Sửa lỗi đặt bàn", JobContent.SIGNED,
				"AGREEMENT");
		JobContent.selectedBid(this.jdbc, this.job, this.product, this.lan, this.hung, JobContent.SIGNED);
	}

	@Test
	void theMentorOpensRecruitmentWithTwoSlotsAndTheJobIsListedLookingForItsCrew() throws Exception {
		UUID minh = TeamTestSupport.mentor(this.jdbc, "Trần Quang Minh", List.of(TeamTestSupport.REACT), "React");
		long version = jobVersion();
		new TransactionTemplate(this.transactions).executeWithoutResult(status -> this.todos.open(new TodoRequest(
				"matching.open_crew_recruitment", this.hung, Audience.MENTOR, this.job, Map.of("job", "Sửa lỗi đặt bàn"),
				"/cong-viec/" + this.job + "/tuyen-thanh-vien", null, this.job)));
		recruitment(this.hung).andExpect(status().isOk())
			.andExpect(jsonPath("$.recruiting").value(false))
			.andExpect(jsonPath("$.available").value(true))
			.andExpect(jsonPath("$.maxSlots").value(5))
			.andExpect(jsonPath("$.cap").value(5))
			.andExpect(jsonPath("$.signedMember").value(false))
			.andExpect(jsonPath("$.crewVersion").value(0));

		set(this.hung, 0, true, 2, List.of(TeamTestSupport.REACT, TeamTestSupport.SUPABASE), "Cần một bạn kiểm thử phân quyền")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.recruiting").value(true))
			.andExpect(jsonPath("$.openSlots").value(2))
			.andExpect(jsonPath("$.skills.length()").value(2))
			.andExpect(jsonPath("$.skills[0].id").value(TeamTestSupport.REACT))
			.andExpect(jsonPath("$.note").value("Cần một bạn kiểm thử phân quyền"))
			.andExpect(jsonPath("$.signedMember").value(false))
			.andExpect(jsonPath("$.crewVersion").value(1));

		assertThat(jobVersion()).as("no Customer DTO carries the Crew version").isEqualTo(version);
		this.mvc.perform(get("/api/v1/mentor/job-posts").with(as(minh)))
			.andExpect(jsonPath("$.items[?(@.id == '" + this.job + "')].phase").value("FINDING_CREW"))
			.andExpect(jsonPath("$.items[?(@.id == '" + this.job + "')].recruitment.openSlots").value(2));
		await().atMost(Duration.ofSeconds(10)).until(() -> openTodos("matching.open_crew_recruitment") == 0);
		assertThat(audits("job.recruitment_opened")).isOne();

		set(this.hung, 1, true, 3, List.of(TeamTestSupport.REACT), null).andExpect(status().isOk())
			.andExpect(jsonPath("$.openSlots").value(3))
			.andExpect(jsonPath("$.note").doesNotExist())
			.andExpect(jsonPath("$.crewVersion").value(2));
		set(this.hung, 1, true, 3, List.of(), null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE"));
		assertThat(audits("job.recruitment_edited")).isOne();
	}

	@Test
	void theSlotsStayUnderTheCapOfFiveUnreleasedRows() throws Exception {
		for (int i = 0; i < 4; i++) {
			JobContent.crewRow(this.jdbc, this.job, JobContent.member(this.jdbc, "Thành viên " + i), i == 0 ? "ACTIVE" : "ASSIGNED",
					null);
		}
		recruitment(this.hung).andExpect(jsonPath("$.maxSlots").value(1))
			.andExpect(jsonPath("$.signedMember").value(true))
			.andExpect(jsonPath("$.crew.length()").value(4));

		set(this.hung, 0, true, 2, List.of(), null).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION"))
			.andExpect(jsonPath("$.reason").value("SLOTS_INVALID"))
			.andExpect(jsonPath("$.max").value(1));
		set(this.hung, 0, true, 0, List.of(), null).andExpect(status().isBadRequest());
		set(this.hung, 0, true, 1, List.of(), null).andExpect(status().isOk()).andExpect(jsonPath("$.openSlots").value(1));
	}

	@Test
	void theCustomerAndEveryoneButTheMentorFindNothing() throws Exception {
		UUID minh = TeamTestSupport.mentor(this.jdbc, "Trần Quang Minh", List.of(TeamTestSupport.REACT), "React");
		for (UUID other : List.of(this.lan, minh)) {
			recruitment(other).andExpect(status().isNotFound());
			set(other, 0, true, 1, List.of(), null).andExpect(status().isNotFound());
		}
		assertThat(crewVersion()).isZero();
	}

	@Test
	void recruitmentRunsOnlyFromJ4UntilJ8() throws Exception {
		this.jdbc.sql("UPDATE job.job SET state = 'CLOSING' WHERE id = :id").param("id", this.job).update();
		recruitment(this.hung).andExpect(status().isOk()).andExpect(jsonPath("$.available").value(false));
		set(this.hung, 0, true, 1, List.of(), null).andExpect(status().is(422))
			.andExpect(jsonPath("$.reason").value("RECRUITMENT_UNAVAILABLE"));
		this.jdbc.sql("UPDATE job.job SET state = 'STARTING' WHERE id = :id").param("id", this.job).update();
		set(this.hung, 0, true, 1, List.of(), null).andExpect(status().isOk());
	}

	@Test
	void turningRecruitmentOffLapsesTheOpenApplicationsAndDelistsTheJob() throws Exception {
		UUID minh = TeamTestSupport.mentor(this.jdbc, "Trần Quang Minh", List.of(TeamTestSupport.REACT), "React");
		set(this.hung, 0, true, 2, List.of(), null).andExpect(status().isOk());
		UUID application = pendingApplication(minh);

		set(this.hung, 1, false, null, List.of(), null).andExpect(status().isOk())
			.andExpect(jsonPath("$.recruiting").value(false))
			.andExpect(jsonPath("$.crewVersion").value(2));

		await().atMost(Duration.ofSeconds(10)).until(() -> "LAPSED RECRUITMENT_CLOSED".equals(applicationState(application)));
		assertThat(lapseCause(minh)).isEqualTo("RECRUITMENT_CLOSED");
		this.mvc.perform(get("/api/v1/mentor/job-posts").with(as(minh)))
			.andExpect(jsonPath("$.items[?(@.id == '" + this.job + "')]").isEmpty());
		set(this.hung, 2, false, null, List.of(), null).andExpect(status().isOk()).andExpect(jsonPath("$.crewVersion").value(2));
		assertThat(audits("job.recruitment_closed")).as("off while off changes nothing").isOne();
	}

	@Test
	void j8TurnsRecruitmentOffAndLapsesTheOpenApplications() throws Exception {
		UUID minh = TeamTestSupport.mentor(this.jdbc, "Trần Quang Minh", List.of(TeamTestSupport.REACT), "React");
		this.jdbc.sql("UPDATE job.job SET state = 'ACTIVE' WHERE id = :id").param("id", this.job).update();
		set(this.hung, 0, true, 1, List.of(), null).andExpect(status().isOk());
		UUID application = pendingApplication(minh);
		openRecruitmentTodo();

		new TransactionTemplate(this.transactions)
			.executeWithoutResult(status -> this.lifecycle.apply(Transition.J8, this.job, Actor.SYSTEM, Trigger.AUTO));

		assertThat(this.jdbc.sql("SELECT recruiting FROM job.job WHERE id = :id").param("id", this.job).query(Boolean.class)
			.single()).isFalse();
		await().atMost(Duration.ofSeconds(10)).until(() -> "LAPSED JOB_CLOSING".equals(applicationState(application)));
		assertThat(lapseCause(minh)).isEqualTo("JOB_CLOSING");
		await().atMost(Duration.ofSeconds(10)).until(() -> openTodos("matching.open_crew_recruitment") == 0);
	}

	@Test
	void j10ClosesTheOpenRecruitmentToDoOfAJobThatNeverRecruited() throws Exception {
		openRecruitmentTodo();

		new TransactionTemplate(this.transactions)
			.executeWithoutResult(status -> this.lifecycle.apply(Transition.J10, this.job, Actor.SYSTEM, Trigger.DECISION));

		await().atMost(Duration.ofSeconds(10)).until(() -> openTodos("matching.open_crew_recruitment") == 0);
	}

	@Test
	void theSkillsSoughtAndTheNoteAreChecked() throws Exception {
		List<String> eleven = this.jdbc.sql("SELECT id::text FROM catalog.skill WHERE active ORDER BY id LIMIT 11")
			.query(String.class)
			.list();
		set(this.hung, 0, true, 1, eleven, null).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reason").value("SKILL_LIMIT"));
		set(this.hung, 0, true, 1, List.of(), "x".repeat(JobRecruitment.MAX_NOTE + 1)).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION"));
		set(this.hung, 0, true, 1, List.of(TeamTestSupport.REACT, TeamTestSupport.SUPABASE), null).andExpect(status().isOk());
		this.jdbc.sql("UPDATE catalog.skill SET active = false WHERE id IN (CAST(:supabase AS UUID), CAST(:security AS UUID))")
			.param("supabase", TeamTestSupport.SUPABASE)
			.param("security", TeamTestSupport.WEB_SECURITY)
			.update();

		// A kept Skill the catalog retired since still saves; a newly added retired one does not.
		set(this.hung, 1, true, 2, List.of(TeamTestSupport.REACT, TeamTestSupport.SUPABASE), null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.skills[1].id").value(TeamTestSupport.SUPABASE))
			.andExpect(jsonPath("$.skills[1].active").value(false));
		set(this.hung, 2, true, 2, List.of(TeamTestSupport.REACT, TeamTestSupport.WEB_SECURITY), null)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reason").value("SKILL_INACTIVE"));
	}

	@Test
	void aSaveThatChangesNothingWritesNothing() throws Exception {
		set(this.hung, 0, true, 2, List.of(TeamTestSupport.REACT), "Cần một bạn kiểm thử").andExpect(status().isOk())
			.andExpect(jsonPath("$.crewVersion").value(1));

		set(this.hung, 1, true, 2, List.of(TeamTestSupport.REACT), "Cần một bạn kiểm thử").andExpect(status().isOk())
			.andExpect(jsonPath("$.crewVersion").value(1));

		assertThat(crewVersion()).isOne();
		assertThat(audits("job.recruitment_opened") + audits("job.recruitment_edited")).isOne();
	}

	@Test
	void theCrewListShowsWhoLeftAndHidesAPickThatNeverJoined() throws Exception {
		UUID ha = JobContent.member(this.jdbc, "Phạm Thu Hà");
		JobContent.crewRow(this.jdbc, this.job, ha, "LEFT", JobContent.SIGNED.plus(Duration.ofDays(2)));
		UUID tai = JobContent.member(this.jdbc, "Lê Văn Tài");
		UUID declined = JobContent.crewRow(this.jdbc, this.job, tai, "ASSIGNED", null);
		this.jdbc.sql("UPDATE job.crew_row SET status = 'RELEASED', released_at = now(), released_reason = 'DECLINED' WHERE id = :id")
			.param("id", declined)
			.update();

		recruitment(this.hung).andExpect(status().isOk())
			.andExpect(jsonPath("$.crew.length()").value(1))
			.andExpect(jsonPath("$.crew[0].status").value("RELEASED"))
			.andExpect(jsonPath("$.crew[0].joinedAt").value(JobContent.SIGNED.toString()))
			.andExpect(jsonPath("$.crew[0].leftAt").value(JobContent.SIGNED.plus(Duration.ofDays(2)).toString()));
	}

	@Test
	void anErasedMentorsNoteAndSkillsLeaveTheJobAndItsRecruitmentCloses() throws Exception {
		UUID minh = TeamTestSupport.mentor(this.jdbc, "Trần Quang Minh", List.of(TeamTestSupport.REACT), "React");
		set(this.hung, 0, true, 1, List.of(TeamTestSupport.REACT), "Cần một bạn kiểm thử phân quyền").andExpect(status().isOk());
		UUID application = pendingApplication(minh);

		new TransactionTemplate(this.transactions).executeWithoutResult(status -> this.jobPersonalData.eraseAccount(this.hung));

		assertThat(this.jdbc.sql("""
				SELECT recruiting || ' ' || coalesce(recruit_note, '-') || ' ' || cardinality(recruit_skill_ids)
				FROM job.job WHERE id = :id
				""").param("id", this.job).query(String.class).single()).isEqualTo("false - 0");
		await().atMost(Duration.ofSeconds(10)).until(() -> "LAPSED RECRUITMENT_CLOSED".equals(applicationState(application)));
	}

	@Test
	void j10TurnsRecruitmentOffAndLapsesTheOpenApplications() throws Exception {
		UUID minh = TeamTestSupport.mentor(this.jdbc, "Trần Quang Minh", List.of(TeamTestSupport.REACT), "React");
		set(this.hung, 0, true, 1, List.of(), null).andExpect(status().isOk());
		UUID application = pendingApplication(minh);

		new TransactionTemplate(this.transactions)
			.executeWithoutResult(status -> this.lifecycle.apply(Transition.J10, this.job, Actor.SYSTEM, Trigger.DECISION));

		await().atMost(Duration.ofSeconds(10)).until(() -> "LAPSED JOB_CANCELLED".equals(applicationState(application)));
		assertThat(lapseCause(minh)).isEqualTo("JOB_CANCELLED");
	}

	@Test
	void theMentorsExportCarriesTheNoteToApplicants() throws Exception {
		set(this.hung, 0, true, 1, List.of(TeamTestSupport.REACT), "Cần một bạn kiểm thử phân quyền").andExpect(status().isOk());

		String export = new TransactionTemplate(this.transactions).execute(status -> this.personalData.export(this.hung))
			.toString();

		assertThat(export).contains("crewRecruitments").contains("Cần một bạn kiểm thử phân quyền");
		awaitListeners();
	}

	private UUID pendingApplication(UUID applicant) {
		UUID id = UUID.randomUUID();
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO matching.crew_application (id, job_id, product_id, customer_account_id, mentor_account_id,
				    applicant_account_id, status, note, public_code_notice_version, submitted_at, lapses_at)
				VALUES (:id, :job, :product, :lan, :hung, :applicant, 'PENDING', 'Mình muốn tham gia', '1.0', :now,
				    CAST(:now AS TIMESTAMPTZ) + INTERVAL '14 days')
				""")
			.param("id", id)
			.param("job", this.job)
			.param("product", this.product)
			.param("lan", this.lan)
			.param("hung", this.hung)
			.param("applicant", applicant)
			.param("now", now)
			.update();
		return id;
	}

	private String applicationState(UUID application) {
		return this.jdbc.sql("SELECT status || ' ' || coalesce(end_reason, '') FROM matching.crew_application WHERE id = :id")
			.param("id", application)
			.query(String.class)
			.single();
	}

	private String lapseCause(UUID applicant) {
		return this.jdbc.sql("""
				SELECT params ->> 'cause' FROM notification.notification
				WHERE type = 'matching.crew_application_lapsed' AND account_id = :applicant
				""").param("applicant", applicant).query(String.class).single();
	}

	private ResultActions recruitment(UUID caller) throws Exception {
		return this.mvc.perform(get("/api/v1/mentor/jobs/" + this.job + "/recruitment").with(as(caller)));
	}

	private ResultActions set(UUID caller, long crewVersion, boolean on, Integer slots, List<String> skills, String note)
			throws Exception {
		String skillIds = skills.isEmpty() ? "[]"
				: "[" + String.join(",", skills.stream().map(skill -> "\"" + skill + "\"").toList()) + "]";
		return this.mvc.perform(put("/api/v1/mentor/jobs/" + this.job + "/recruitment").with(as(caller))
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"on\":%s,\"openSlots\":%s,\"skillIds\":%s,\"note\":%s,\"crewVersion\":%d}".formatted(on, slots,
					skillIds, note == null ? "null" : "\"" + note + "\"", crewVersion)));
	}

	private void openRecruitmentTodo() {
		new TransactionTemplate(this.transactions).executeWithoutResult(status -> this.todos.open(new TodoRequest(
				"matching.open_crew_recruitment", this.hung, Audience.MENTOR, this.job, Map.of("job", "Sửa lỗi đặt bàn"),
				"/cong-viec/" + this.job + "/tuyen-thanh-vien", null, this.job)));
		assertThat(openTodos("matching.open_crew_recruitment")).isOne();
	}

	private long openTodos(String key) {
		return this.jdbc.sql("SELECT count(*) FROM notification.todo WHERE todo_key = :key AND subject_id = :job"
				+ " AND closed_at IS NULL").param("key", key).param("job", this.job).query(Long.class).single();
	}

	private long audits(String action) {
		return this.jdbc.sql("SELECT count(*) FROM audit.audit_entry WHERE action = :action").param("action", action)
			.query(Long.class)
			.single();
	}

	private long jobVersion() {
		return this.jdbc.sql("SELECT version FROM job.job WHERE id = :id").param("id", this.job).query(Long.class).single();
	}

	private long crewVersion() {
		return this.jdbc.sql("SELECT crew_version FROM job.job WHERE id = :id").param("id", this.job).query(Long.class).single();
	}

	private void awaitListeners() {
		await().atMost(Duration.ofSeconds(10)).until(() -> this.jdbc.sql(
				"SELECT count(*) FROM platform.event_publication WHERE completion_date IS NULL").query(Long.class).single() == 0);
	}

	private RequestPostProcessor as(UUID account) {
		return IdentityTestSupport.signedIn(this.sessions, account, Role.MENTOR, this.clock.instant());
	}

}
