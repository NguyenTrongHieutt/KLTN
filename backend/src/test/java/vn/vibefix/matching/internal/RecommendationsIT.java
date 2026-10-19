package vn.vibefix.matching.internal;

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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import vn.vibefix.identity.MentorApprovalStatus;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.MutableClock;
import vn.vibefix.team.internal.TeamTestSupport;

/** FR-14, AD-17, SG-40: deterministic Recommendations for a Job Post and for a Mentor. */
class RecommendationsIT extends IntegrationTest {

	private static final String REACT = TeamTestSupport.REACT;

	private static final String SUPABASE = TeamTestSupport.SUPABASE;

	private static final String WEB_SECURITY = TeamTestSupport.WEB_SECURITY;

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	private UUID lan;

	private UUID product;

	@BeforeEach
	void aCustomerWithAProduct() {
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null, null);
		this.product = product(this.lan);
	}

	@Test
	void mentorsWhoCouldApplyComeBySharedSkillsWithTheirSignals() throws Exception {
		UUID job = job("OPEN", List.of("BUG_FIX"), List.of(REACT, SUPABASE), null);
		UUID both = mentor(MentorApprovalStatus.APPROVED, "Hai Kỹ Năng", List.of(SUPABASE, REACT), true);
		UUID one = mentor(MentorApprovalStatus.APPROVED, "Một Kỹ Năng", List.of(SUPABASE), true);
		UUID noTeam = mentor(MentorApprovalStatus.APPROVED, "Không Nhóm", List.of(REACT, SUPABASE), false);
		UUID busy = mentor(MentorApprovalStatus.APPROVED, "Đủ Việc", List.of(REACT, SUPABASE), true);
		for (int i = 0; i < 3; i++) {
			job("SELECTING", List.of("BUG_FIX"), List.of(), busy);
		}
		mentor(MentorApprovalStatus.PENDING, "Chờ Duyệt", List.of(REACT, SUPABASE), true);
		mentor(MentorApprovalStatus.APPROVED, "Không Chung", List.of(WEB_SECURITY), true);

		String body = this.mvc.perform(get("/api/v1/jobs/" + job + "/recommendations").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].displayName").value("Hai Kỹ Năng"))
			.andExpect(jsonPath("$[0].signals.sharedSkills[0]").value("React"))
			.andExpect(jsonPath("$[0].signals.sharedSkills[1]").value("Supabase"))
			.andExpect(jsonPath("$[0].signals.newcomer").value(true))
			.andExpect(jsonPath("$[0].teamSize").value(1))
			.andExpect(jsonPath("$[0].relation").value("NONE"))
			.andExpect(jsonPath("$[1].displayName").value("Một Kỹ Năng"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		List<String> ids = JsonPath.read(body, "$[*].accountId");
		assertThat(ids).as("no free Student, at the Job limit, pending, or no shared Skill: left out")
			.containsExactly(both.toString(), one.toString())
			.doesNotContain(noTeam.toString(), busy.toString());

		this.mvc.perform(get("/api/v1/jobs/" + job + "/recommendations?skill=" + REACT).with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].accountId").value(both.toString()));
		UUID other = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null, null);
		this.mvc.perform(get("/api/v1/jobs/" + job + "/recommendations").with(as(other, Role.CUSTOMER)))
			.andExpect(status().isNotFound());
		UUID draft = job("DRAFT", List.of("BUG_FIX"), List.of(REACT), null);
		this.mvc.perform(get("/api/v1/jobs/" + draft + "/recommendations").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void workHistoryCountsOneJobPerCustomerAndEndsBeingNew() throws Exception {
		UUID job = job("OPEN", List.of("SECURITY_TESTING"), List.of(REACT), null);
		UUID veteran = mentor(MentorApprovalStatus.APPROVED, "Từng Làm", List.of(WEB_SECURITY), true);
		job("CLOSED", List.of("SECURITY_TESTING"), List.of(), veteran);
		job("CLOSED", List.of("SECURITY_TESTING"), List.of(), veteran);
		UUID newcomer = mentor(MentorApprovalStatus.APPROVED, "Người Mới", List.of(REACT), true);

		this.mvc.perform(get("/api/v1/jobs/" + job + "/recommendations").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[0].accountId").value(newcomer.toString()))
			.andExpect(jsonPath("$[0].signals.newcomer").value(true))
			.andExpect(jsonPath("$[1].accountId").value(veteran.toString()))
			.andExpect(jsonPath("$[1].signals.sharedSkills.length()").value(0))
			.andExpect(jsonPath("$[1].signals.jobTypeMatches[0].jobType").value("SECURITY_TESTING"))
			.andExpect(jsonPath("$[1].signals.jobTypeMatches[0].count").value(1))
			.andExpect(jsonPath("$[1].signals.newcomer").value(false));
	}

	@Test
	void jobPostsForAMentorBySharedSkillsThenNewestLeavingOutAppliedOnes() throws Exception {
		UUID mentor = mentor(MentorApprovalStatus.APPROVED, "Đỗ Mạnh Hùng", List.of(REACT, SUPABASE), true);
		UUID older = job("OPEN", List.of("BUG_FIX"), List.of(SUPABASE), null);
		this.clock.advance(Duration.ofHours(1));
		UUID newer = job("OPEN", List.of("NEW_FEATURE"), List.of(REACT), null);
		this.clock.advance(Duration.ofHours(1));
		UUID best = job("OPEN", List.of("BUG_FIX"), List.of(SUPABASE, REACT), null);
		UUID applied = job("OPEN", List.of("BUG_FIX"), List.of(REACT, SUPABASE), null);
		application(applied, mentor);
		job("OPEN", List.of("BUG_FIX"), List.of(WEB_SECURITY), null);
		UUID hidden = job("OPEN", List.of("BUG_FIX"), List.of(REACT), null);
		this.jdbc.sql("UPDATE job.job SET hidden_at = now(), hidden_reason = 'Kiểm duyệt' WHERE id = :id")
			.param("id", hidden).update();
		invitation(older, mentor);

		String body = this.mvc.perform(get("/api/v1/mentor/recommendations").with(as(mentor, Role.MENTOR)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].signals.sharedSkills.length()").value(2))
			.andExpect(jsonPath("$[0].productName").value("Shop Gốm Bát Tràng"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		List<String> ids = JsonPath.read(body, "$[*].id");
		assertThat(ids).containsExactly(best.toString(), newer.toString(), older.toString());
		List<Boolean> invited = JsonPath.read(body, "$[*].invited");
		assertThat(invited).containsExactly(false, false, true);

		this.mvc.perform(get("/api/v1/mentor/recommendations?skill=" + SUPABASE).with(as(mentor, Role.MENTOR)))
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[0].id").value(best.toString()))
			.andExpect(jsonPath("$[1].id").value(older.toString()));
		UUID pending = mentor(MentorApprovalStatus.PENDING, "Chờ Duyệt", List.of(REACT), true);
		this.mvc.perform(get("/api/v1/mentor/recommendations").with(as(pending, Role.MENTOR)))
			.andExpect(status().isForbidden());
	}

	private UUID mentor(MentorApprovalStatus status, String name, List<String> skills, boolean withFreeStudent) {
		UUID mentor = TeamTestSupport.mentor(this.jdbc, status, name, skills, "Kỹ năng");
		if (withFreeStudent) {
			UUID team = UUID.randomUUID();
			this.jdbc.sql("INSERT INTO team.team (id, mentor_account_id, status, created_at) VALUES (:id, :mentor, 'OPEN', now())")
				.param("id", team)
				.param("mentor", mentor)
				.update();
			UUID student = TeamTestSupport.student(this.jdbc, "Sinh viên của " + name, true, List.of(REACT), "React",
					LocalDate.parse("2026-09-30"));
			this.jdbc.sql("""
					INSERT INTO team.membership (id, team_id, student_account_id, joined_at) VALUES (:id, :team, :student, now())
					""").param("id", UUID.randomUUID()).param("team", team).param("student", student).update();
		}
		return mentor;
	}

	/** A Job of the Customer in {@code state}; past Open it has {@code mentor}, selected now. */
	private UUID job(String state, List<String> types, List<String> skills, UUID mentor) {
		UUID id = UUID.randomUUID();
		Instant now = this.clock.instant();
		boolean draft = "DRAFT".equals(state);
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, skill_ids, skill_names, budget, deadline, rules, selection_days, opened_at, lapses_at,
				    mentor_account_id, selected_application_id, selected_at, version, created_at, updated_at)
				VALUES (:id, :product, :owner, :state, 'Sửa lỗi mất đơn hàng', 'Lỗi', 'Đúng', 'Khách',
				    CAST(:types AS TEXT[]), CAST(:skills AS UUID[]), CAST(:names AS TEXT[]), 'UNDER_2M', DATE '2026-12-01',
				    CAST(:rules AS JSONB), :days, :opened, :lapses, :mentor, :application, :selected, 1, :now, :now)
				""")
			.param("id", id)
			.param("product", this.product)
			.param("owner", this.lan)
			.param("state", state)
			.param("types", "{" + String.join(",", types) + "}")
			.param("skills", "{" + String.join(",", skills) + "}")
			.param("names", "{" + String.join(",", skills.stream().map(skill -> "\"k\"").toList()) + "}")
			.param("rules", draft ? null : "{}")
			.param("days", draft ? null : 14)
			.param("opened", draft ? null : Timestamp.from(now))
			.param("lapses", draft ? null : Timestamp.from(now.plus(Duration.ofDays(14))))
			.param("mentor", mentor)
			.param("application", mentor == null ? null : UUID.randomUUID())
			.param("selected", mentor == null ? null : Timestamp.from(now))
			.param("now", Timestamp.from(now))
			.update();
		return id;
	}

	private void application(UUID job, UUID mentor) {
		this.jdbc.sql("""
				INSERT INTO matching.application (id, job_id, product_id, customer_account_id, mentor_account_id, status,
				    price_vnd, timeline_days, approach, public_code_notice_version, submitted_at)
				VALUES (:id, :job, :product, :lan, :mentor, 'SUBMITTED', 800000, 7, 'Cách làm', '1.0', now())
				""")
			.param("id", UUID.randomUUID())
			.param("job", job)
			.param("product", this.product)
			.param("lan", this.lan)
			.param("mentor", mentor)
			.update();
	}

	private void invitation(UUID job, UUID mentor) {
		this.jdbc.sql("""
				INSERT INTO matching.invitation (id, job_id, product_id, customer_account_id, mentor_account_id, status,
				    invited_at)
				VALUES (:id, :job, :product, :lan, :mentor, 'OPEN', now())
				""")
			.param("id", UUID.randomUUID())
			.param("job", job)
			.param("product", this.product)
			.param("lan", this.lan)
			.param("mentor", mentor)
			.update();
	}

	private UUID product(UUID owner) {
		UUID id = UUID.randomUUID();
		Timestamp now = Timestamp.from(MutableClock.DEFAULT_INSTANT);
		this.jdbc.sql("""
				INSERT INTO product.product (id, customer_account_id, name, description, ai_tools, repository_github_id,
				    repository_full_name, repository_owner_type, repository_default_branch, public_notice_version,
				    copy_permission_version, detection_notice_version, acknowledged_at, created_at)
				VALUES (:id, :owner, 'Shop Gốm Bát Tràng', 'Bán gốm.', ARRAY['LOVABLE'], :github,
				    'lan/shop-gom', 'User', 'main', '1.0', '1.0', '1.0', :now, :now)
				""")
			.param("id", id)
			.param("owner", owner)
			.param("github", ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE))
			.param("now", now)
			.update();
		return id;
	}

	private RequestPostProcessor as(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

}
