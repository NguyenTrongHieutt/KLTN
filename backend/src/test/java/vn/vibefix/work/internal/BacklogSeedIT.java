package vn.vibefix.work.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import vn.vibefix.identity.MentorApprovalStatus;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.job.AgreementConfirmed;
import vn.vibefix.job.JobTransitioned;
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.team.internal.TeamTestSupport;

/**
 * FR-22, AD-29, AD-33: J7 seeds one Story per Acceptance Criterion of the Agreement in force, keyed
 * from the Product's Job code; a replay seeds nothing, a later version seeds only its new criteria,
 * the seeded fields never change, and only the Mentor and the active Crew read the Board.
 */
class BacklogSeedIT extends IntegrationTest {

	private static final List<UUID> MILESTONES = List.of(UUID.randomUUID(), UUID.randomUUID());

	private static final List<List<UUID>> CRITERIA = List.of(
			List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()), List.of(UUID.randomUUID(), UUID.randomUUID()));

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private WorkListeners listeners;

	private UUID lan;

	private UUID hung;

	private UUID product;

	private UUID job;

	@BeforeEach
	void anActiveJobWithAConfirmedAgreement() {
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null, null);
		this.hung = TeamTestSupport.mentor(this.jdbc, MentorApprovalStatus.APPROVED, "Đỗ Mạnh Hùng",
				List.of(TeamTestSupport.REACT), "React");
		this.product = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO product.product (id, customer_account_id, name, description, ai_tools, repository_github_id,
				    repository_full_name, repository_owner_type, repository_default_branch, public_notice_version,
				    copy_permission_version, detection_notice_version, acknowledged_at, created_at)
				VALUES (:id, :owner, 'Sổ đặt bàn Quán Mây', 'Đặt bàn cho quán.', ARRAY['LOVABLE'], :github,
				    'lan/so-dat-ban', 'User', 'main', '1.0', '1.0', '1.0', now(), now())
				""")
			.param("id", this.product)
			.param("owner", this.lan)
			.param("github", ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE))
			.update();
		this.job = job("ASSESSMENT");
		confirm(this.job, 1, MILESTONES, CRITERIA);
	}

	@Test
	void j7SeedsOneStoryPerCriterionAndAReplaySeedsNothing() throws Exception {
		UUID event = UUID.randomUUID();
		this.listeners.onJobTransitioned(j7(event, this.job));
		await().atMost(Duration.ofSeconds(10)).until(() -> items(this.job) == 5);

		List<Map<String, Object>> rows = this.jdbc.sql("""
				SELECT b.code || '-' || w.number AS key, w.type, w.title, w.description, w.criterion_id, w.milestone_id, w.status,
				       w.assignee_account_id
				FROM work.work_item w JOIN work.board b ON b.job_id = w.job_id WHERE w.job_id = :job ORDER BY w.number
				""").param("job", this.job).query().listOfRows();
		assertThat(rows).extracting(row -> row.get("key")).containsExactly("SDB-1", "SDB-2", "SDB-3", "SDB-4", "SDB-5");
		assertThat(rows).extracting(row -> row.get("criterion_id"))
			.containsExactly(CRITERIA.get(0).get(0), CRITERIA.get(0).get(1), CRITERIA.get(0).get(2), CRITERIA.get(1).get(0),
					CRITERIA.get(1).get(1));
		assertThat(rows).extracting(row -> row.get("milestone_id"))
			.containsExactly(MILESTONES.get(0), MILESTONES.get(0), MILESTONES.get(0), MILESTONES.get(1), MILESTONES.get(1));
		assertThat(rows).allSatisfy(row -> {
			assertThat(row.get("type")).isEqualTo("STORY");
			assertThat(row.get("status")).isEqualTo("TODO");
			assertThat(row.get("assignee_account_id")).isNull();
		});
		assertThat(rows.getFirst().get("title")).isEqualTo("Tiêu chí 1.1");
		assertThat(rows.getFirst().get("description")).isEqualTo("Cách kiểm tra 1.1");

		this.listeners.onJobTransitioned(j7(event, this.job));
		this.listeners.onJobTransitioned(j7(UUID.randomUUID(), this.job));
		await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(5)).until(() -> items(this.job) == 5);
	}

	@Test
	void aLaterAgreementVersionSeedsOnlyItsNewCriterion() {
		this.listeners.onJobTransitioned(j7(UUID.randomUUID(), this.job));
		await().atMost(Duration.ofSeconds(10)).until(() -> items(this.job) == 5);
		UUID added = UUID.randomUUID();
		confirm(this.job, 2, MILESTONES,
				List.of(List.of(CRITERIA.get(0).get(0), CRITERIA.get(0).get(1), CRITERIA.get(0).get(2), added), CRITERIA.get(1)));

		this.listeners.onAgreementConfirmed(confirmed(this.job, 2));
		await().atMost(Duration.ofSeconds(10)).until(() -> items(this.job) == 6);
		assertThat(this.jdbc.sql("SELECT number FROM work.work_item WHERE criterion_id = :c").param("c", added)
			.query(Integer.class).single()).isEqualTo(6);

		UUID other = job("ASSESSMENT");
		confirm(other, 2, MILESTONES, CRITERIA);
		this.listeners.onAgreementConfirmed(confirmed(other, 2));
		await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(5))
			.until(() -> this.jdbc.sql("SELECT count(*) FROM work.board WHERE job_id = :job").param("job", other)
				.query(Long.class).single() == 0);
	}

	@Test
	void aSeededStoryKeepsItsCriterionTypeAndMilestoneAndASecondJobGetsTheNextCode() {
		this.listeners.onJobTransitioned(j7(UUID.randomUUID(), this.job));
		await().atMost(Duration.ofSeconds(10)).until(() -> items(this.job) == 5);

		for (String change : List.of("criterion_id = gen_random_uuid()", "type = 'TASK'", "milestone_id = gen_random_uuid()",
				"number = 99")) {
			assertThatThrownBy(() -> this.jdbc.sql("UPDATE work.work_item SET " + change + " WHERE job_id = :job AND number = 1")
				.param("job", this.job).update()).as(change).isInstanceOf(RuntimeException.class);
		}
		this.jdbc.sql("UPDATE work.work_item SET title = 'Tiêu chí đã đổi tên' WHERE job_id = :job AND number = 1")
			.param("job", this.job)
			.update();

		UUID second = job("ASSESSMENT");
		confirm(second, 1, List.of(UUID.randomUUID()), List.of(List.of(UUID.randomUUID())));
		this.listeners.onJobTransitioned(j7(UUID.randomUUID(), second));
		await().atMost(Duration.ofSeconds(10)).until(() -> items(second) == 1);
		assertThat(this.jdbc.sql("SELECT code FROM work.board WHERE job_id = :job").param("job", second)
			.query(String.class).single()).isEqualTo("SDB2");
	}

	@Test
	void onlyTheMentorAndTheActiveCrewReadTheBoard() throws Exception {
		this.listeners.onJobTransitioned(j7(UUID.randomUUID(), this.job));
		await().atMost(Duration.ofSeconds(10)).until(() -> items(this.job) == 5);
		UUID vy = crew("ACTIVE");
		UUID tung = crew("ASSIGNED");

		for (RequestPostProcessor reader : List.of(as(this.hung, Role.MENTOR), as(vy, Role.STUDENT))) {
			this.mvc.perform(get("/api/v1/boards/" + this.job + "/work-items").with(reader))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.code").value("SDB"))
				.andExpect(jsonPath("$.milestones.length()").value(2))
				.andExpect(jsonPath("$.items.length()").value(5))
				.andExpect(jsonPath("$.items[3].key").value("SDB-4"))
				.andExpect(jsonPath("$.items[3].seeded").value(true))
				.andExpect(jsonPath("$.items[3].type").value("STORY"))
				.andExpect(jsonPath("$.items[3].status").value("TODO"))
				.andExpect(jsonPath("$.items[3].criterion.milestoneNumber").value(2))
				.andExpect(jsonPath("$.items[3].criterion.number").value(1));
		}
		UUID otherMentor = TeamTestSupport.mentor(this.jdbc, MentorApprovalStatus.APPROVED, "Phạm Thu Hà",
				List.of(TeamTestSupport.REACT), "React");
		UUID admin = IdentityTestSupport.insertAccount(this.jdbc, Role.ADMINISTRATOR, null, null);
		for (RequestPostProcessor outsider : List.of(as(this.lan, Role.CUSTOMER), as(tung, Role.STUDENT),
				as(otherMentor, Role.MENTOR), as(admin, Role.ADMINISTRATOR))) {
			this.mvc.perform(get("/api/v1/boards/" + this.job + "/work-items").with(outsider)).andExpect(status().isNotFound());
		}
		UUID notActive = job("BUG_FIX");
		this.mvc.perform(get("/api/v1/boards/" + notActive + "/work-items").with(as(this.hung, Role.MENTOR)))
			.andExpect(status().isNotFound());
	}

	private UUID job(String type) {
		UUID id = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, budget, deadline, rules, selection_days, opened_at, lapses_at, mentor_account_id,
				    selected_application_id, selected_at, version, created_at, updated_at)
				VALUES (:id, :product, :owner, 'ACTIVE', 'Sửa lỗi đặt bàn', 'Lỗi', 'Đúng', 'Khách', ARRAY[:type], 'UNDER_2M',
				    DATE '2026-12-01', '{}'::jsonb, 14, :now, :lapses, :mentor, :application, :now, 5, :now, :now)
				""")
			.param("id", id)
			.param("product", this.product)
			.param("owner", this.lan)
			.param("type", type)
			.param("now", Timestamp.from(this.clock.instant()))
			.param("lapses", Timestamp.from(this.clock.instant().plus(Duration.ofDays(14))))
			.param("mentor", this.hung)
			.param("application", UUID.randomUUID())
			.update();
		return id;
	}

	/** Shares and confirms Agreement version {@code no} with these Milestones and criteria. */
	private void confirm(UUID jobId, int no, List<UUID> milestones, List<List<UUID>> criteria) {
		StringBuilder content = new StringBuilder("{\"scope\":\"Đánh giá\",\"priceVnd\":3000000,\"codeJob\":false,")
			.append("\"assessment\":true,\"skills\":[],\"externalAccess\":[],\"milestones\":[");
		for (int m = 0; m < milestones.size(); m++) {
			content.append(m == 0 ? "" : ",")
				.append("{\"id\":\"%s\",\"title\":\"Giai đoạn %d\",\"priceVnd\":1500000,\"dueOn\":\"2026-11-20\",".formatted(milestones.get(m), m + 1))
				.append("\"revisionRounds\":2,\"criteria\":[");
			for (int c = 0; c < criteria.get(m).size(); c++) {
				content.append(c == 0 ? "" : ",")
					.append("{\"id\":\"%s\",\"statement\":\"Tiêu chí %d.%d\",\"checkSteps\":\"Cách kiểm tra %d.%d\"}"
						.formatted(criteria.get(m).get(c), m + 1, c + 1, m + 1, c + 1));
			}
			content.append("]}");
		}
		content.append("]}");
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO job.agreement_version (job_id, version_no, status, content, author_account_id, created_at)
				VALUES (:job, :no, 'SHARED', CAST(:content AS JSONB), :mentor, :now)
				""").param("job", jobId).param("no", no).param("content", content.toString()).param("mentor", this.hung)
			.param("now", now).update();
		this.jdbc.sql("""
				INSERT INTO job.confirmed_agreement (job_id, version_no, draft_version_no, content, crew_usernames,
				    freeze_committed, customer_account_id, approved_at, mentor_account_id, confirmed_at)
				VALUES (:job, :no, :no, CAST(:content AS JSONB), '{}', false, :lan, :now, :mentor, :now)
				""").param("job", jobId).param("no", no).param("content", content.toString()).param("lan", this.lan)
			.param("mentor", this.hung).param("now", now).update();
	}

	private UUID crew(String status) {
		UUID student = TeamTestSupport.student(this.jdbc, "Sinh viên " + status, true, List.of(TeamTestSupport.REACT), "React",
				LocalDate.parse("2026-09-30"));
		boolean active = "ACTIVE".equals(status);
		this.jdbc.sql("""
				INSERT INTO job.crew_row (id, job_id, student_account_id, status, signed_at, activated_at, created_at)
				VALUES (:id, :job, :student, :status, :at, :at, now())
				""")
			.param("id", UUID.randomUUID())
			.param("job", this.job)
			.param("student", student)
			.param("status", status)
			.param("at", active ? Timestamp.from(this.clock.instant()) : null)
			.update();
		return student;
	}

	private long items(UUID jobId) {
		return this.jdbc.sql("SELECT count(*) FROM work.work_item WHERE job_id = :job").param("job", jobId)
			.query(Long.class).single();
	}

	private JobTransitioned j7(UUID eventId, UUID jobId) {
		return new JobTransitioned(eventId, UUID.randomUUID(), jobId, "J7", "AUTO", "STARTING", "ACTIVE", 6, 1, Map.of());
	}

	private AgreementConfirmed confirmed(UUID jobId, int versionNo) {
		return new AgreementConfirmed(UUID.randomUUID(), jobId, this.product, this.lan, "Sửa lỗi đặt bàn", versionNo, List.of());
	}

	private RequestPostProcessor as(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

}
