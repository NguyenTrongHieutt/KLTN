package vn.vibefix.matching.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.MutableClock;
import vn.vibefix.team.internal.TeamTestSupport;

/** FR-14, SG-41, SG-44: the Customer's Mentor search and invitations to apply. */
class InvitationsIT extends IntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	private UUID lan;

	private UUID product;

	private UUID job;

	private UUID long_;

	private UUID ha;

	private UUID bao;

	private UUID unlinked;

	private UUID locked;

	@BeforeEach
	void aCustomerWithAnOpenJobPostAndMentorsInEveryState() {
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		this.jdbc.sql("""
				INSERT INTO identity.profile (account_id, kind, display_name, contact_email, updated_at)
				VALUES (:id, 'INDIVIDUAL', 'Lan Nguyễn', 'lan@example.com', now())
				""").param("id", this.lan).update();
		this.product = product(this.lan);
		this.job = openJob();
		this.long_ = mentor("Trần Đức Long", List.of(TeamTestSupport.SUPABASE), "Supabase");
		this.ha = mentor("Phạm Thu Hà", List.of(TeamTestSupport.REACT, TeamTestSupport.SUPABASE), "React Supabase");
		this.bao = mentor("Lê Quốc Bảo", List.of(TeamTestSupport.REACT), "React");
		// Story 21.3: a user without GitHub linked is never listed, and cannot be invited to bid.
		this.unlinked = mentor("Chưa Liên Kết", List.of(TeamTestSupport.SUPABASE), "Supabase");
		IdentityTestSupport.unlinkGitHub(this.jdbc, this.unlinked);
		this.locked = mentor("Bị Khóa", List.of(TeamTestSupport.SUPABASE), "Supabase");
		IdentityTestSupport.lock(this.jdbc, this.locked, "Vi phạm");
	}

	@Test
	void theSearchFindsUnlockedMentorsWithAProfileAndNoContactDetails() throws Exception {
		// The listed Mentor's own contact details (FR-3, AD-20): never in the directory or on PUB-4.
		this.jdbc.sql("""
				UPDATE identity.profile SET contact_email = 'long.lienhe@example.test', phone = '+84 977 123 456'
				WHERE account_id = :id
				""").param("id", this.long_).update();
		String body = search("supabase", "").andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(2))
			.andExpect(jsonPath("$.size").value(20))
			.andExpect(jsonPath("$.items[0].linkedinUrl").doesNotExist())
			.andExpect(jsonPath("$.items[0].experience").doesNotExist())
			.andExpect(jsonPath("$.items[0].teamSize").doesNotExist())
			.andExpect(jsonPath("$.items[0].verified[0].kind").value("GITHUB"))
			.andExpect(jsonPath("$.items[0].verified[0].verifiedAt").isNotEmpty())
			.andExpect(jsonPath("$.items[0].verified[2].kind").value("LINKEDIN"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		List<String> found = JsonPath.read(body, "$.items[*].accountId");
		// Story 21.3 (FR-14, AD-36): the GitHub-verified, unlocked users only; a locked one and an unlinked one are absent.
		assertThat(found).as("a locked Mentor is absent (SG-44), and so is one without GitHub linked")
			.containsExactlyInAnyOrder(this.long_.toString(), this.ha.toString());
		List<String> relations = JsonPath.read(body, "$.items[*].relation");
		assertThat(relations).containsOnly("NONE");
		assertThat(JsonPath.<List<Boolean>>read(body, "$.items[*].available")).containsOnly(true);
		String login = this.jdbc.sql("SELECT github_login FROM identity.account WHERE id = :id").param("id", this.long_)
			.query(String.class)
			.single();
		String publicProfile = this.mvc.perform(get("/api/v1/users/" + login)).andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		for (String response : List.of(body, publicProfile)) {
			assertThat(response).doesNotContain("long.lienhe@example.test").doesNotContain("977 123 456");
		}

		search("supabase", "&skill=" + TeamTestSupport.REACT).andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].displayName").value("Phạm Thu Hà"))
			.andExpect(jsonPath("$.items[0].skills[0]").value("React"));
		search("", "").andExpect(jsonPath("$.total").value(0)).andExpect(jsonPath("$.items.length()").value(0));
		UUID other = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/mentors?q=supabase").with(as(other, Role.CUSTOMER)))
			.andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/mentors?q=supabase").with(as(this.long_, Role.MENTOR)))
			.andExpect(status().isNotFound());
	}

	/**
	 * Story 20.3: the directory lists every person's account with a profile, a user with no role too, who may be
	 * invited; never the Job's own Customer, who cannot be its Mentor (AD-43), though her profile matches.
	 */
	@Test
	void theSearchListsAUserWithNoRoleAndNeverTheJobsCustomer() throws Exception {
		this.jdbc.sql("""
				UPDATE identity.profile SET skill_ids = CAST(:skills AS UUID[]), skill_names = 'Supabase' WHERE account_id = :id
				""").param("skills", "{" + TeamTestSupport.SUPABASE + "}").param("id", this.lan).update();
		UUID minh = TeamTestSupport.googleMentor(this.jdbc, "Ngô Văn Minh", List.of(TeamTestSupport.SUPABASE), "Supabase");

		String body = search("supabase", "").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		List<String> found = JsonPath.read(body, "$.items[*].accountId");
		assertThat(found).contains(minh.toString()).doesNotContain(this.lan.toString());
		assertThat(JsonPath.<List<Boolean>>read(body, "$.items[?(@.accountId == '" + minh + "')].available"))
			.containsExactly(true);
		invite(minh).andExpect(status().isCreated());
	}

	@Test
	void theSearchOrdersByRelevanceThenAccountIdNeverByName() throws Exception {
		UUID first = mentor("Zô Bảo Mật", List.of(TeamTestSupport.SUPABASE), "Bảo mật");
		UUID second = mentor("An Bảo Mật", List.of(TeamTestSupport.SUPABASE), "Bảo mật");

		String body = search("bảo mật", "").andExpect(jsonPath("$.total").value(2)).andReturn().getResponse()
			.getContentAsString();
		List<String> found = JsonPath.read(body, "$.items[*].accountId");
		List<String> byId = this.jdbc.sql("SELECT CAST(id AS TEXT) FROM unnest(ARRAY[:a, :b]::UUID[]) AS id ORDER BY id")
			.param("a", first)
			.param("b", second)
			.query(String.class)
			.list();
		assertThat(found).as("equal ranks fall back to the account ID, as PostgreSQL orders it").isEqualTo(byId);
	}

	/**
	 * Story 21.3 (FR-14, AD-36, X9): the search covers the student name declared at verification only while its owner
	 * shows it; the school and the major are no search terms.
	 */
	@Test
	void theDeclaredStudentNameIsFoundOnlyWhileShown() throws Exception {
		UUID shown = TeamTestSupport.student(this.jdbc, "Hoàng Diệu Linh", true, List.of(TeamTestSupport.REACT), "React",
				java.time.LocalDate.parse("2026-09-30"));
		UUID hidden = TeamTestSupport.student(this.jdbc, "Hoàng Diệu Thư", false, List.of(TeamTestSupport.REACT), "React",
				java.time.LocalDate.parse("2026-09-30"));

		String body = search("Hoàng Diệu", "").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(JsonPath.<List<String>>read(body, "$.items[*].accountId")).containsExactly(shown.toString())
			.doesNotContain(hidden.toString());
		assertThat(body).doesNotContain("Hoàng Diệu Thư");
		search("Bách khoa", "").andExpect(jsonPath("$.total").value(0));
		search(TeamTestSupport.MAJOR, "").andExpect(jsonPath("$.total").value(0));
	}

	/**
	 * Story 21.3 (AD-34): 60 searches per account in a minute, one window shared with "Tìm việc"; the 61st is refused
	 * with {@code Retry-After}, and a blank keyword is no search.
	 */
	@Test
	void sixtySearchesAMinuteSharedWithTheJobPostSearch() throws Exception {
		for (int i = 0; i < 30; i++) {
			this.mvc.perform(get("/api/v1/mentor/job-posts?q=supabase").with(as(this.lan, Role.CUSTOMER)))
				.andExpect(status().isOk());
		}
		for (int i = 0; i < 30; i++) {
			search("supabase", "").andExpect(status().isOk());
		}
		search("", "").andExpect(status().isOk());
		search("supabase", "").andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("RATE_LIMITED"))
			.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Retry-After", "60"));
		this.mvc.perform(get("/api/v1/mentor/job-posts?q=supabase").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(status().isTooManyRequests());
		this.clock.advance(java.time.Duration.ofSeconds(61));
		search("supabase", "").andExpect(status().isOk());
	}

	@Test
	void anInvitationTellsTheMentorAndCountsAgainstTheDay() throws Exception {
		invitations().andExpect(status().isOk())
			.andExpect(jsonPath("$.left").value(10))
			.andExpect(jsonPath("$.limit").doesNotExist())
			.andExpect(jsonPath("$.invitations.length()").value(0));

		invite(this.long_).andExpect(status().isCreated()).andExpect(jsonPath("$.left").value(9));

		// Author decision 2026-10-21: no invitation text names the Customer before selection.
		assertThat(this.jdbc.sql("""
				SELECT params::text FROM notification.notification
				WHERE type = 'matching.invited_to_apply' AND account_id = :long
				""").param("long", this.long_).query(String.class).single()).doesNotContain("Lan Nguyễn")
			.contains("Đánh giá bảo mật cửa hàng online");
		assertThat(this.jdbc.sql("""
				SELECT params ->> 'job' FROM notification.todo
				WHERE todo_key = 'matching.answer_invitation_to_apply' AND account_id = :long AND closed_at IS NULL
				""").param("long", this.long_).query(String.class).single()).isEqualTo("Đánh giá bảo mật cửa hàng online");
		assertThat(this.jdbc.sql("SELECT count(*) FROM audit.audit_entry WHERE action = 'matching.invitation_sent'")
			.query(Long.class).single()).isOne();
		invitations().andExpect(jsonPath("$.left").value(9))
			.andExpect(jsonPath("$.invitations[0].displayName").value("Trần Đức Long"))
			.andExpect(jsonPath("$.invitations[0].status").value("OPEN"));
		search("supabase", "").andExpect(jsonPath("$.items[?(@.displayName == 'Trần Đức Long')].relation").value("INVITED"));
		this.mvc.perform(get("/api/v1/mentor/job-posts/" + this.job + "/invitation").with(as(this.long_, Role.MENTOR)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.customer").doesNotExist())
			.andExpect(jsonPath("$.status").value("OPEN"));
		this.mvc.perform(get("/api/v1/mentor/job-posts/" + this.job + "/invitation").with(as(this.ha, Role.MENTOR)))
			.andExpect(status().isNotFound());
		UUID other = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		this.mvc.perform(post("/api/v1/jobs/" + this.job + "/invitations").with(as(other, Role.CUSTOMER)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content("{\"mentorId\":\"" + this.ha + "\"}"))
			.andExpect(status().isNotFound());
	}

	@Test
	void eachRefusalHasItsReasonAndNeverSaysWhyAMentorIsUnavailable() throws Exception {
		invite(this.long_).andExpect(status().isCreated());
		invite(this.long_).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("ALREADY_INVITED"));
		for (UUID unavailable : List.of(this.unlinked, this.locked, UUID.randomUUID())) {
			invite(unavailable).andExpect(status().is(422)).andExpect(jsonPath("$.reason").value("INVITEE_NOT_AVAILABLE"));
		}
		// AD-43: inviting the Customer themselves to bid is refused first, by their role in the Job.
		invite(this.lan).andExpect(status().is(422))
			.andExpect(jsonPath("$.code").value("GUARD_FAILED"))
			.andExpect(jsonPath("$.reason").value("ROLE_TAKEN"));
		application(this.ha);
		invite(this.ha).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("INVITEE_ALREADY_APPLIED"));
		search("supabase", "").andExpect(jsonPath("$.items[?(@.displayName == 'Phạm Thu Hà')].relation").value("APPLIED"));

		this.jdbc.sql("UPDATE job.job SET hidden_at = now(), hidden_reason = 'Kiểm duyệt' WHERE id = :id")
			.param("id", this.job).update();
		invite(this.bao).andExpect(status().is(422)).andExpect(jsonPath("$.reason").value("JOB_POST_HIDDEN"));
		this.mvc.perform(get("/api/v1/mentor/job-posts/" + this.job + "/invitation").with(as(this.long_, Role.MENTOR)))
			.andExpect(jsonPath("$.status").value("LAPSED"));
	}

	@Test
	void tenAJobPostAndThirtyACustomerEachVietnamDay() throws Exception {
		for (int i = 0; i < 10; i++) {
			sent(this.job);
		}
		invite(this.long_).andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.reason").value("INVITE_LIMIT_JOB_POST"))
			.andExpect(header().exists("Retry-After"));
		invitations().andExpect(jsonPath("$.left").value(0)).andExpect(jsonPath("$.limit").value("INVITE_LIMIT_JOB_POST"));

		UUID second = openJob();
		for (int i = 0; i < 20; i++) {
			sent(UUID.randomUUID());
		}
		this.mvc.perform(post("/api/v1/jobs/" + second + "/invitations").with(as(this.lan, Role.CUSTOMER)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content("{\"mentorId\":\"" + this.long_ + "\"}"))
			.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.reason").value("INVITE_LIMIT_DAILY"));

		this.clock.advance(Duration.ofDays(1));
		invite(this.long_).andExpect(status().isCreated()).andExpect(jsonPath("$.left").value(9));
	}

	@Test
	void j2LapsesTheOpenInvitationsAndClosesTheirTodos() throws Exception {
		invite(this.long_).andExpect(status().isCreated());

		this.mvc.perform(post("/api/v1/jobs/" + this.job + "/withdraw").with(as(this.lan, Role.CUSTOMER)).with(csrf()))
			.andExpect(status().isOk());

		await().atMost(Duration.ofSeconds(10)).until(() -> "LAPSED".equals(this.jdbc.sql("""
				SELECT status FROM matching.invitation WHERE job_id = :job AND mentor_account_id = :long
				""").param("job", this.job).param("long", this.long_).query(String.class).single()));
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM notification.todo WHERE todo_key = 'matching.answer_invitation_to_apply' AND closed_at IS NULL
				""").query(Long.class).single()).isZero();
		this.mvc.perform(get("/api/v1/mentor/job-posts/" + this.job + "/invitation").with(as(this.long_, Role.MENTOR)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("LAPSED"))
			.andExpect(jsonPath("$.jobTitle").value("Đánh giá bảo mật cửa hàng online"));
		this.mvc.perform(get("/api/v1/mentor/job-posts/" + this.job).with(as(this.long_, Role.MENTOR)))
			.andExpect(status().isNotFound());
	}

	/**
	 * Story 21.3 (AD-33): the search cards' Ratings are the person's as Mentor only, the Customers'; the same person's
	 * Rating as Crew member from a Mentor never counts in it.
	 */
	@Test
	void theSearchCardsRatingCountsOnlyTheRatingsAsMentor() throws Exception {
		UUID window = UUID.randomUUID();
		Timestamp at = Timestamp.from(MutableClock.DEFAULT_INSTANT.minus(Duration.ofDays(20)));
		this.jdbc.sql("""
				INSERT INTO reputation.rating_window (job_id, product_id, customer_account_id, mentor_account_id, job_title,
				    opened_at, ends_at, pair_published_at, crew_published_at)
				VALUES (:job, :product, :lan, :ha, 'Việc đã xong', :opened, :at, :at, :at)
				""").param("job", window).param("product", this.product).param("lan", this.lan).param("ha", this.ha)
			.param("opened", Timestamp.from(MutableClock.DEFAULT_INSTANT.minus(Duration.ofDays(34))))
			.param("at", at)
			.update();
		rating(window, "CUSTOMER_TO_MENTOR", this.lan, this.long_, 5, at);
		rating(window, "MENTOR_TO_STUDENT", this.ha, this.long_, 2, at);

		this.mvc.perform(get("/api/v1/reputation/mentor-summaries").param("id", this.long_.toString())
			.with(as(this.lan, Role.CUSTOMER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].accountId").value(this.long_.toString()))
			.andExpect(jsonPath("$[0].average").value(5.0))
			.andExpect(jsonPath("$[0].count").value(1));
	}

	private void rating(UUID job, String kind, UUID rater, UUID ratee, int stars, Timestamp at) {
		this.jdbc.sql("""
				INSERT INTO reputation.rating (id, job_id, kind, rater_account_id, ratee_account_id, stars, comment, created_at)
				VALUES (:id, :job, :kind, :rater, :ratee, :stars, 'Nhận xét', :at)
				""").param("id", UUID.randomUUID()).param("job", job).param("kind", kind).param("rater", rater)
			.param("ratee", ratee).param("stars", stars).param("at", at).update();
	}

	private ResultActions search(String q, String filters) throws Exception {
		return this.mvc.perform(get("/api/v1/jobs/" + this.job + "/mentors" + (filters.isEmpty() ? "" : "?" + filters.substring(1)))
			.param("q", q)
			.with(as(this.lan, Role.CUSTOMER)));
	}

	private ResultActions invitations() throws Exception {
		return this.mvc.perform(get("/api/v1/jobs/" + this.job + "/invitations").with(as(this.lan, Role.CUSTOMER)));
	}

	private ResultActions invite(UUID mentor) throws Exception {
		return this.mvc.perform(post("/api/v1/jobs/" + this.job + "/invitations").with(as(this.lan, Role.CUSTOMER))
			.with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"mentorId\":\"" + mentor + "\"}"));
	}

	/** An invitation the Customer sent today, to someone the counters need not know. */
	private void sent(UUID jobId) {
		this.jdbc.sql("""
				INSERT INTO matching.invitation (id, job_id, product_id, customer_account_id, mentor_account_id, status,
				    invited_at)
				VALUES (:id, :job, :product, :lan, :mentor, 'OPEN', :now)
				""")
			.param("id", UUID.randomUUID())
			.param("job", jobId)
			.param("product", this.product)
			.param("lan", this.lan)
			.param("mentor", UUID.randomUUID())
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
	}

	private void application(UUID mentor) {
		this.jdbc.sql("""
				INSERT INTO matching.application (id, job_id, product_id, customer_account_id, mentor_account_id, status,
				    price_vnd, timeline_days, approach, public_code_notice_version, submitted_at)
				VALUES (:id, :job, :product, :lan, :mentor, 'SUBMITTED', 800000, 7, 'Cách làm', '1.0', :now)
				""")
			.param("id", UUID.randomUUID())
			.param("job", this.job)
			.param("product", this.product)
			.param("lan", this.lan)
			.param("mentor", mentor)
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
	}

	private UUID mentor(String name, List<String> skills, String skillNames) {
		return TeamTestSupport.mentor(this.jdbc, name, skills, skillNames);
	}

	private UUID openJob() {
		UUID id = UUID.randomUUID();
		Instant now = this.clock.instant();
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, budget, deadline, rules, selection_days, opened_at, lapses_at, version, created_at, updated_at)
				VALUES (:id, :product, :owner, 'OPEN', 'Đánh giá bảo mật cửa hàng online', 'Lỗi', 'Đúng', 'Khách',
				    ARRAY['SECURITY_TESTING'], 'FROM_2M_TO_5M', DATE '2026-12-01', '{}'::jsonb, 14, :now, :lapses, 1, :now, :now)
				""")
			.param("id", id)
			.param("product", this.product)
			.param("owner", this.lan)
			.param("now", Timestamp.from(now))
			.param("lapses", Timestamp.from(now.plus(Duration.ofDays(14))))
			.update();
		return id;
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
