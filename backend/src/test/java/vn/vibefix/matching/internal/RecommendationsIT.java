package vn.vibefix.matching.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
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

import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.Kind;
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
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		this.product = product(this.lan);
	}

	@Test
	void mentorsWhoCouldApplyComeBySharedSkillsWithTheirSignals() throws Exception {
		UUID job = job("OPEN", List.of("BUG_FIX"), List.of(REACT, SUPABASE), null);
		UUID both = mentor("Hai Kỹ Năng", List.of(SUPABASE, REACT));
		UUID one = mentor("Một Kỹ Năng", List.of(SUPABASE));
		// Story 21.1: no free Student is needed any more, so a Mentor with no Team is recommended too.
		UUID noTeam = mentor("Không Nhóm", List.of(REACT, SUPABASE));
		UUID busy = mentor("Đủ Việc", List.of(REACT, SUPABASE));
		for (int i = 0; i < 3; i++) {
			job("SELECTING", List.of("BUG_FIX"), List.of(), busy);
		}
		// Story 19.7: a Mentor without GitHub linked is not available, so never recommended.
		IdentityTestSupport.unlinkGitHub(this.jdbc, mentor("Chưa Liên Kết", List.of(REACT, SUPABASE)));
		mentor("Không Chung", List.of(WEB_SECURITY));

		String body = this.mvc.perform(get("/api/v1/jobs/" + job + "/recommendations").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].signals.sharedSkills[0]").value("React"))
			.andExpect(jsonPath("$[0].signals.sharedSkills[1]").value("Supabase"))
			.andExpect(jsonPath("$[0].signals.newcomer").value(true))
			.andExpect(jsonPath("$[0].relation").value("NONE"))
			.andExpect(jsonPath("$[2].displayName").value("Một Kỹ Năng"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		List<String> ids = JsonPath.read(body, "$[*].accountId");
		assertThat(ids).as("at the Job limit, no GitHub link, or no shared Skill: left out")
			.containsExactlyInAnyOrder(both.toString(), noTeam.toString(), one.toString())
			.doesNotContain(busy.toString());
		assertThat(ids.subList(0, 2)).containsExactlyInAnyOrder(both.toString(), noTeam.toString());

		this.mvc.perform(get("/api/v1/jobs/" + job + "/recommendations?skill=" + SUPABASE).with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.length()").value(3));
		this.mvc.perform(get("/api/v1/jobs/" + job + "/recommendations?skill=" + REACT).with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.length()").value(2));
		UUID other = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
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
		UUID veteran = mentor("Từng Làm", List.of(WEB_SECURITY));
		job("CLOSED", List.of("SECURITY_TESTING"), List.of(), veteran);
		job("CLOSED", List.of("SECURITY_TESTING"), List.of(), veteran);
		UUID newcomer = mentor("Người Mới", List.of(REACT));

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

	/**
	 * Story 20.3: anyone with a profile who could take the Job is recommended, a user with no role too, but never the
	 * Job's own Customer, who cannot be its Mentor (AD-43).
	 */
	@Test
	void aUserWithNoRoleIsRecommendedAndTheJobsCustomerNever() throws Exception {
		UUID job = job("OPEN", List.of("BUG_FIX"), List.of(REACT), null);
		UUID minh = TeamTestSupport.googleMentor(this.jdbc, "Ngô Văn Minh", List.of(REACT), "React");
		this.jdbc.sql("""
				INSERT INTO identity.profile (account_id, display_name, skill_ids, skill_names, updated_at)
				VALUES (:id, 'Lan Nguyễn', CAST(:skills AS UUID[]), 'React', now())
				""").param("id", this.lan).param("skills", "{" + REACT + "}").update();

		String body = this.mvc.perform(get("/api/v1/jobs/" + job + "/recommendations").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		List<String> ids = JsonPath.read(body, "$[*].accountId");
		assertThat(ids).containsExactly(minh.toString()).doesNotContain(this.lan.toString());
	}

	/** Story 20.3: "Tìm việc" never shows the reader's own Jobs (AD-36), whatever Skills they share. */
	@Test
	void aMentorsOwnOpenJobIsNeverRecommendedToThem() throws Exception {
		UUID hung = mentor("Đỗ Mạnh Hùng", List.of(REACT));
		UUID own = job(product(hung), hung, "OPEN", List.of("BUG_FIX"), List.of(REACT), null);
		UUID lans = job("OPEN", List.of("BUG_FIX"), List.of(REACT), null);

		String body = this.mvc.perform(get("/api/v1/mentor/recommendations").with(as(hung, Role.MENTOR)))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		List<String> ids = JsonPath.read(body, "$[*].id");
		assertThat(ids).containsExactly(lans.toString()).doesNotContain(own.toString());
	}

	/** Story 20.1: MEN-2's Recommendations are part of "Tìm việc" (X1), which needs GitHub linked. */
	@Test
	void aMentorWithoutGitHubLinkedIsRefusedTheRecommendations() throws Exception {
		UUID mentor = mentor("Chưa Liên Kết", List.of(WEB_SECURITY));
		IdentityTestSupport.unlinkGitHub(this.jdbc, mentor);
		job("OPEN", List.of("BUG_FIX"), List.of(WEB_SECURITY), null);

		this.mvc.perform(get("/api/v1/mentor/recommendations").with(as(mentor, Role.MENTOR)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("FORBIDDEN"))
			.andExpect(jsonPath("$.reason").value("GITHUB_NOT_LINKED"));
	}

	@Test
	void jobPostsForAMentorBySharedSkillsThenNewestLeavingOutAppliedOnes() throws Exception {
		UUID mentor = mentor("Đỗ Mạnh Hùng", List.of(REACT, SUPABASE));
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
		// No Mentor needs an approval (Story 19.7); one locked after the session was read is refused.
		UUID locked = mentor("Bị Khóa", List.of(REACT));
		IdentityTestSupport.lock(this.jdbc, locked, "Vi phạm");
		this.mvc.perform(get("/api/v1/mentor/recommendations").with(request -> {
			request.setAttribute(Caller.REQUEST_ATTRIBUTE, Caller.account(locked, Kind.USER));
			return request;
		})).andExpect(status().isForbidden()).andExpect(jsonPath("$.reason").value("ACCOUNT_LOCKED"));
	}

	/**
	 * Story 21.1 (AD-17): every listed Job, a recruiting one too, where the user holds no role and has no bid of any
	 * status, with its phase and what it needs; a Job Type of the user's history as a Crew member counts as one as
	 * Mentor does.
	 */
	@Test
	void listedJobsWhereTheUserHoldsNoRoleAndHasNoBidWithTheirPhaseAndNeed() throws Exception {
		UUID vy = mentor("Lê Thảo Vy", List.of(REACT));
		UUID ha = mentor("Phạm Thu Hà", List.of(WEB_SECURITY));
		// Vy's history as a Crew member: a Security testing Job closed with Accepted work.
		UUID done = job("CLOSED", List.of("SECURITY_TESTING"), List.of(), ha);
		this.jdbc.sql("UPDATE job.job SET closing_at = now(), closed_at = now() WHERE id = :id").param("id", done).update();
		milestone(done);
		crewRow(done, vy, "RELEASED", true);
		UUID open = job("OPEN", List.of("BUG_FIX"), List.of(REACT), null);
		this.clock.advance(Duration.ofHours(1));
		UUID byHistory = job("OPEN", List.of("SECURITY_TESTING"), List.of(), null);
		this.clock.advance(Duration.ofHours(1));
		UUID recruiting = recruiting(job("AGREEMENT", List.of("NEW_FEATURE"), List.of(), ha), REACT, 2);
		UUID withdrawnBid = job("OPEN", List.of("BUG_FIX"), List.of(REACT), null);
		application(withdrawnBid, vy, "WITHDRAWN");
		UUID asMentor = recruiting(job("AGREEMENT", List.of("BUG_FIX"), List.of(REACT), vy), REACT, 1);
		UUID asMember = recruiting(job("ACTIVE", List.of("BUG_FIX"), List.of(REACT), ha), REACT, 1);
		crewRow(asMember, vy, "ACTIVE", true);
		recruiting(job("AGREEMENT", List.of("BUG_FIX"), List.of(REACT), ha), REACT, 0);

		String body = this.mvc.perform(get("/api/v1/mentor/recommendations").with(as(vy, Role.MENTOR)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].id").value(recruiting.toString()))
			.andExpect(jsonPath("$[0].phase").value("FINDING_CREW"))
			.andExpect(jsonPath("$[0].need").value("CREW"))
			.andExpect(jsonPath("$[0].openSlots").value(2))
			.andExpect(jsonPath("$[0].signals.sharedSkills[0]").value("React"))
			.andExpect(jsonPath("$[1].id").value(open.toString()))
			.andExpect(jsonPath("$[1].phase").value("FINDING_MENTOR"))
			.andExpect(jsonPath("$[1].need").value("MENTOR"))
			.andExpect(jsonPath("$[1].openSlots").value(0))
			.andExpect(jsonPath("$[2].id").value(byHistory.toString()))
			.andExpect(jsonPath("$[2].signals.sharedSkills.length()").value(0))
			.andExpect(jsonPath("$[2].signals.jobTypeMatches[0].jobType").value("SECURITY_TESTING"))
			.andExpect(jsonPath("$[2].signals.jobTypeMatches[0].count").value(1))
			.andReturn()
			.getResponse()
			.getContentAsString();
		List<String> ids = JsonPath.read(body, "$[*].id");
		assertThat(ids).as("a bid of any status, a role held, or no slot left: left out")
			.containsExactly(recruiting.toString(), open.toString(), byHistory.toString())
			.doesNotContain(withdrawnBid.toString(), asMentor.toString(), asMember.toString());
	}

	/** Turns the Job's Crew recruitment on, seeking {@code skill}, with {@code slots} places open. */
	private UUID recruiting(UUID job, String skill, int slots) {
		this.jdbc.sql("""
				UPDATE job.job SET recruiting = true, recruiting_since = :now, open_slots = :slots,
				    recruit_skill_ids = CAST(:skills AS UUID[]), recruit_skill_names = ARRAY['k']
				WHERE id = :id
				""")
			.param("id", job)
			.param("now", Timestamp.from(this.clock.instant()))
			.param("slots", slots)
			.param("skills", "{" + skill + "}")
			.update();
		return job;
	}

	private void crewRow(UUID job, UUID member, String status, boolean activated) {
		Instant at = this.clock.instant().minus(Duration.ofDays(5));
		boolean released = "RELEASED".equals(status);
		this.jdbc.sql("""
				INSERT INTO job.crew_row (id, job_id, member_account_id, status, signed_at, activated_at, window_end,
				    released_at, released_reason, created_at)
				VALUES (:id, :job, :member, :status, :signed, :activated, :end, :released, :reason, :at)
				""")
			.param("id", UUID.randomUUID())
			.param("job", job)
			.param("member", member)
			.param("status", status)
			.param("signed", activated ? Timestamp.from(at) : null)
			.param("activated", activated ? Timestamp.from(at) : null)
			.param("end", activated && released ? Timestamp.from(at.plus(Duration.ofDays(1))) : null)
			.param("released", released ? Timestamp.from(at.plus(Duration.ofDays(1))) : null)
			.param("reason", released ? "JOB_ENDED" : null)
			.param("at", Timestamp.from(at))
			.update();
	}

	/** An Accepted Milestone on the Job: Accepted work, which history counts (AD-30). */
	private void milestone(UUID job) {
		Instant at = this.clock.instant().minus(Duration.ofDays(5));
		this.jdbc.sql("""
				INSERT INTO job.milestone (id, job_id, position, title, price_vnd, revision_rounds, created_at, state,
				    funded_at, crew_terms_version_no, accepted_at)
				VALUES (:id, :job, 0, 'Giai đoạn', 800000, 2, :at, 'ACCEPTED', :at, 1, :at)
				""").param("id", UUID.randomUUID()).param("job", job).param("at", Timestamp.from(at)).update();
	}

	/** A person with GitHub linked and a profile holding {@code skills}: anyone may mentor a Job (Story 20.3). */
	private UUID mentor(String name, List<String> skills) {
		return TeamTestSupport.mentor(this.jdbc, name, skills, "Kỹ năng");
	}

	/** A Job of the Customer in {@code state}; past Open it has {@code mentor}, selected now. */
	private UUID job(String state, List<String> types, List<String> skills, UUID mentor) {
		return job(this.product, this.lan, state, types, skills, mentor);
	}

	/** A Job of {@code owner} on {@code product} in {@code state}; past Open it has {@code mentor}, selected now. */
	private UUID job(UUID product, UUID owner, String state, List<String> types, List<String> skills, UUID mentor) {
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
			.param("product", product)
			.param("owner", owner)
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
		application(job, mentor, "SUBMITTED");
	}

	private void application(UUID job, UUID mentor, String status) {
		this.jdbc.sql("""
				INSERT INTO matching.application (id, job_id, product_id, customer_account_id, mentor_account_id, status,
				    price_vnd, timeline_days, approach, public_code_notice_version, submitted_at, ended_at, end_reason)
				VALUES (:id, :job, :product, :lan, :mentor, :status, 800000, 7, 'Cách làm', '1.0', now(), :ended, :reason)
				""")
			.param("id", UUID.randomUUID())
			.param("job", job)
			.param("product", this.product)
			.param("lan", this.lan)
			.param("mentor", mentor)
			.param("status", status)
			.param("ended", "SUBMITTED".equals(status) ? null : java.sql.Timestamp.from(this.clock.instant()))
			.param("reason", "SUBMITTED".equals(status) ? null : status)
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
