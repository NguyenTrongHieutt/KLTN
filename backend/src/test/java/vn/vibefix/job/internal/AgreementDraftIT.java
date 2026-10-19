package vn.vibefix.job.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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

import com.jayway.jsonpath.JsonPath;

import vn.vibefix.identity.MentorApprovalStatus;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.MutableClock;
import vn.vibefix.team.internal.TeamTestSupport;

/** FR-16, FR-10, FR-40, AD-29: the Agreement draft from J4 to the first shared version. */
class AgreementDraftIT extends IntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	private UUID lan;

	private UUID hung;

	@Test
	void j4StartsTheDraftFromTheQuoteAndTheJobPost() throws Exception {
		Instant signed = this.clock.instant();
		UUID job = agreement("BUG_FIX");

		this.mvc.perform(get("/api/v1/mentor/jobs/" + job + "/agreement").with(as(this.hung, Role.MENTOR)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.revision").value(0))
			.andExpect(jsonPath("$.content.priceVnd").value(800000))
			.andExpect(jsonPath("$.content.skills[0].name").value("React"))
			.andExpect(jsonPath("$.content.codeJob").value(true))
			.andExpect(jsonPath("$.content.assessment").value(false))
			.andExpect(jsonPath("$.quote.priceVnd").value(800000))
			.andExpect(jsonPath("$.quote.timelineDays").value(7))
			.andExpect(jsonPath("$.problems[0].code").value("SCOPE_MISSING"))
			.andExpect(jsonPath("$.versions.length()").value(0));
		assertThat(this.jdbc.sql("SELECT agreement_due_at FROM job.job WHERE id = :id").param("id", job)
			.query(Timestamp.class).single().toInstant()).isEqualTo(signed.plus(Duration.ofDays(14)));
		assertThat(this.jdbc.sql("""
				SELECT deadline_at FROM notification.todo WHERE todo_key = 'job.draft_agreement' AND account_id = :hung
				  AND closed_at IS NULL
				""").param("hung", this.hung).query(Timestamp.class).single().toInstant())
			.isEqualTo(signed.plus(Duration.ofDays(14)));
		this.mvc.perform(get("/api/v1/jobs/" + job + "/agreement").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/jobs/" + job).with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.agreementShared").value(false));
	}

	@Test
	void savingKeepsEachIdAndSharingWritesTheFirstVersion() throws Exception {
		UUID job = agreement("BUG_FIX");
		UUID milestone = UUID.randomUUID();
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();
		UUID access = UUID.randomUUID();

		save(job, 0, milestone, 800000, List.of(criterion(first, "Khách chọn giờ đã qua thì không đặt được", "1. Mở. 2. Chọn."),
				criterion(second, "Hai khách đặt cùng bàn thì người sau được báo", "")), access)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.revision").value(1))
			.andExpect(jsonPath("$.content.milestones[0].id").value(milestone.toString()))
			.andExpect(jsonPath("$.content.milestones[0].criteria[1].id").value(second.toString()))
			.andExpect(jsonPath("$.content.externalAccess[0].environment").value("NON_PRODUCTION"))
			.andExpect(jsonPath("$.problems[0].code").value("CRITERION_WITHOUT_STEPS"))
			.andExpect(jsonPath("$.problems[0].criterionId").value(second.toString()));
		save(job, 0, milestone, 800000, List.of(), access).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("STALE"));
		share(job, 1).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reason").value("AGREEMENT_INCOMPLETE"))
			.andExpect(jsonPath("$.problems[0].code").value("CRITERION_WITHOUT_STEPS"));

		save(job, 1, milestone, 500000, List.of(criterion(first, "Khách chọn giờ đã qua thì không đặt được", "1. Mở. 2. Chọn."),
				criterion(second, "Hai khách đặt cùng bàn thì người sau được báo", "1. Đặt hai lần.")), access)
			.andExpect(jsonPath("$.problems[0].code").value("PRICE_SUM_MISMATCH"));
		save(job, 2, milestone, 800000, List.of(criterion(first, "Khách chọn giờ đã qua thì không đặt được", "1. Mở. 2. Chọn."),
				criterion(second, "Hai khách đặt cùng bàn thì người sau được báo", "1. Đặt hai lần.")), access)
			.andExpect(jsonPath("$.problems.length()").value(0));
		share(job, 3).andExpect(status().isOk()).andExpect(jsonPath("$.versions[0].versionNo").value(1));

		this.mvc.perform(get("/api/v1/jobs/" + job + "/agreement").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.versionNo").value(1))
			.andExpect(jsonPath("$.content.milestones[0].id").value(milestone.toString()))
			.andExpect(jsonPath("$.content.milestones[0].criteria.length()").value(2))
			.andExpect(jsonPath("$.content.skills[0].name").value("React"))
			.andExpect(jsonPath("$.quote.priceVnd").value(800000));
		this.mvc.perform(get("/api/v1/jobs/" + job).with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.agreementShared").value(true));
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type = 'job.agreement_draft_ready' AND account_id = '"
				+ this.lan + "'")).isOne();
		assertThat(count("SELECT count(*) FROM notification.todo WHERE todo_key = 'job.draft_agreement' AND closed_at IS NULL"))
			.isZero();
		assertThat(count("SELECT count(*) FROM notification.todo WHERE todo_key = 'job.review_agreement' AND closed_at IS NULL"
				+ " AND account_id = '" + this.lan + "'")).isOne();

		save(job, 3, milestone, 800000, List.of(criterion(first, "Khách chọn giờ đã qua thì không đặt được", "1. Mở. 2. Chọn.")),
				access).andExpect(status().isOk());
		assertThat(count("SELECT count(*) FROM job.acceptance_criterion WHERE id = '" + second + "' AND removed_at IS NOT NULL"))
			.as("a removed criterion is marked, never deleted").isOne();
		this.mvc.perform(get("/api/v1/jobs/" + job + "/agreement?version=1").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.content.milestones[0].criteria.length()").value(2));
	}

	@Test
	void anotherJobsEntityAndOtherCallersAreRefused() throws Exception {
		UUID job = agreement("BUG_FIX");
		UUID foreign = UUID.randomUUID();
		UUID otherJob = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, version, created_at, updated_at)
				SELECT :id, product_id, customer_account_id, 'DRAFT', 'Khác', 0, now(), now() FROM job.job WHERE id = :job
				""").param("id", otherJob).param("job", job).update();
		this.jdbc.sql("""
				INSERT INTO job.milestone (id, job_id, position, revision_rounds, created_at) VALUES (:id, :job, 0, 2, now())
				""").param("id", foreign).param("job", otherJob).update();

		save(job, 0, foreign, 800000, List.of(), UUID.randomUUID()).andExpect(status().isBadRequest());
		UUID other = TeamTestSupport.mentor(this.jdbc, MentorApprovalStatus.APPROVED, "Phạm Thu Hà",
				List.of(TeamTestSupport.REACT), "React");
		this.mvc.perform(get("/api/v1/mentor/jobs/" + job + "/agreement").with(as(other, Role.MENTOR)))
			.andExpect(status().isNotFound());
		UUID student = IdentityTestSupport.insertAccount(this.jdbc, Role.STUDENT, null, null);
		this.mvc.perform(get("/api/v1/jobs/" + job + "/agreement").with(as(student, Role.STUDENT)))
			.andExpect(status().isNotFound());
	}

	@Test
	void anAssessmentJobDraftsOnlyThePriceAndTheDueDate() throws Exception {
		UUID job = agreement("ASSESSMENT");

		String body = this.mvc.perform(get("/api/v1/mentor/jobs/" + job + "/agreement").with(as(this.hung, Role.MENTOR)))
			.andExpect(jsonPath("$.content.assessment").value(true))
			.andExpect(jsonPath("$.content.milestones.length()").value(1))
			.andExpect(jsonPath("$.content.milestones[0].title").value("Báo cáo đánh giá"))
			.andExpect(jsonPath("$.content.milestones[0].criteria.length()").value(6))
			.andReturn()
			.getResponse()
			.getContentAsString();
		UUID milestone = UUID.fromString(JsonPath.read(body, "$.content.milestones[0].id"));

		save(job, 0, milestone, 800000, List.of(criterion(UUID.randomUUID(), "Đổi tiêu chí", "1. Thử.")), UUID.randomUUID())
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content.milestones[0].priceVnd").value(800000))
			.andExpect(jsonPath("$.content.milestones[0].dueOn").value("2026-11-30"))
			.andExpect(jsonPath("$.content.milestones[0].criteria.length()").value(6))
			.andExpect(jsonPath("$.content.externalAccess.length()").value(0))
			.andExpect(jsonPath("$.problems.length()").value(0));
		share(job, 1).andExpect(status().isOk());
	}

	private ResultActions save(UUID job, int revision, UUID milestone, long price, List<String> criteria, UUID access)
			throws Exception {
		String content = """
				{"revision":%d,"scope":"Sửa lỗi đặt trùng bàn.","priceVnd":800000,"skillIds":["%s"],
				 "milestones":[{"id":"%s","title":"Sửa lỗi trùng giờ","priceVnd":%d,"dueOn":"2026-11-30","revisionRounds":2,
				   "criteria":[%s]}],
				 "externalAccess":[{"id":"%s","system":"Supabase","purpose":"Đọc bảng đặt bàn","environment":"NON_PRODUCTION",
				   "accessLevel":"READ"}]}
				""".formatted(revision, TeamTestSupport.REACT, milestone, price, String.join(",", criteria), access);
		return this.mvc.perform(put("/api/v1/mentor/jobs/" + job + "/agreement").with(as(this.hung, Role.MENTOR)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content(content));
	}

	private ResultActions share(UUID job, int revision) throws Exception {
		return this.mvc.perform(post("/api/v1/mentor/jobs/" + job + "/agreement/share").with(as(this.hung, Role.MENTOR))
			.with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"revision\":" + revision + "}"));
	}

	private static String criterion(UUID id, String statement, String steps) {
		return "{\"id\":\"%s\",\"statement\":\"%s\",\"checkSteps\":\"%s\"}".formatted(id, statement, steps);
	}

	private long count(String sql) {
		return this.jdbc.sql(sql).query(Long.class).single();
	}

	/** A Job of {@code type} taken through J3 and J4 by the API. */
	private UUID agreement(String type) throws Exception {
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null, null);
		UUID product = product(this.lan);
		UUID job = UUID.randomUUID();
		Instant now = this.clock.instant();
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, skill_ids, skill_names, budget, deadline, rules, selection_days, opened_at, lapses_at, version,
				    created_at, updated_at)
				VALUES (:id, :product, :owner, 'OPEN', 'Sửa lỗi đặt bàn', 'Lỗi', 'Đúng', 'Khách', CAST(:types AS TEXT[]),
				    CAST(:skills AS UUID[]), ARRAY['React'], 'UNDER_2M', DATE '2026-12-01', '{"REVISION_ROUNDS":2}'::jsonb, 14,
				    :now, :lapses, 1, :now, :now)
				""")
			.param("id", job)
			.param("product", product)
			.param("owner", this.lan)
			.param("types", "{" + type + "}")
			.param("skills", "{" + TeamTestSupport.REACT + "}")
			.param("now", Timestamp.from(now))
			.param("lapses", Timestamp.from(now.plus(Duration.ofDays(14))))
			.update();
		this.hung = TeamTestSupport.mentor(this.jdbc, MentorApprovalStatus.APPROVED, "Đỗ Mạnh Hùng",
				List.of(TeamTestSupport.REACT), "React");
		UUID team = UUID.randomUUID();
		this.jdbc.sql("INSERT INTO team.team (id, mentor_account_id, status, created_at) VALUES (:id, :mentor, 'OPEN', now())")
			.param("id", team).param("mentor", this.hung).update();
		UUID student = TeamTestSupport.student(this.jdbc, "Sinh viên", true, List.of(TeamTestSupport.REACT), "React",
				LocalDate.parse("2026-09-30"));
		this.jdbc.sql("INSERT INTO team.membership (id, team_id, student_account_id, joined_at) VALUES (:id, :team, :student, now())")
			.param("id", UUID.randomUUID()).param("team", team).param("student", student).update();
		UUID application = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO matching.application (id, job_id, product_id, customer_account_id, mentor_account_id, status,
				    price_vnd, timeline_days, approach, public_code_notice_version, submitted_at)
				VALUES (:id, :job, :product, :lan, :hung, 'SUBMITTED', 800000, 7, 'Cách làm', '1.0', :now)
				""")
			.param("id", application).param("job", job).param("product", product).param("lan", this.lan)
			.param("hung", this.hung).param("now", Timestamp.from(now)).update();
		this.mvc.perform(post("/api/v1/jobs/" + job + "/applications/" + application + "/select")
			.with(as(this.lan, Role.CUSTOMER)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}")).andExpect(status().isOk());
		this.mvc.perform(post("/api/v1/mentor/jobs/" + job + "/nda").with(as(this.hung, Role.MENTOR)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"ndaVersion\":\"1.0\",\"commitEmailNoticeAcknowledged\":true}")).andExpect(status().isNoContent());
		await().atMost(Duration.ofSeconds(5)).until(() -> count("SELECT count(*) FROM job.agreement_draft WHERE job_id = '" + job + "'") == 1);
		return job;
	}

	private UUID product(UUID owner) {
		UUID id = UUID.randomUUID();
		Timestamp now = Timestamp.from(MutableClock.DEFAULT_INSTANT);
		this.jdbc.sql("""
				INSERT INTO product.product (id, customer_account_id, name, description, ai_tools, repository_github_id,
				    repository_full_name, repository_owner_type, repository_default_branch, public_notice_version,
				    copy_permission_version, detection_notice_version, acknowledged_at, created_at)
				VALUES (:id, :owner, 'Sổ đặt bàn Quán Mây', 'Đặt bàn.', ARRAY['LOVABLE'], :github,
				    'lan/so-dat-ban', 'User', 'main', '1.0', '1.0', '1.0', :now, :now)
				""")
			.param("id", id).param("owner", owner)
			.param("github", ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE)).param("now", now).update();
		return id;
	}

	private RequestPostProcessor as(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

}
