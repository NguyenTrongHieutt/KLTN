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
import vn.vibefix.shared.Role;
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
		// Story 19.7: a Mentor without GitHub linked is listed, but cannot be invited to bid.
		this.unlinked = mentor("Chưa Liên Kết", List.of(TeamTestSupport.SUPABASE), "Supabase");
		IdentityTestSupport.unlinkGitHub(this.jdbc, this.unlinked);
		this.locked = mentor("Bị Khóa", List.of(TeamTestSupport.SUPABASE), "Supabase");
		IdentityTestSupport.lock(this.jdbc, this.locked, "Vi phạm");
	}

	@Test
	void theSearchFindsUnlockedMentorsWithAProfileAndNoContactDetails() throws Exception {
		// The listed Mentor's own contact details (FR-3, AD-20): never in the directory or on PUB-5.
		this.jdbc.sql("""
				UPDATE identity.profile SET contact_email = 'long.lienhe@example.test', phone = '+84 977 123 456'
				WHERE account_id = :id
				""").param("id", this.long_).update();
		String body = search("supabase", "").andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(3))
			.andExpect(jsonPath("$.size").value(20))
			.andExpect(jsonPath("$.items[0].linkedinUrl").doesNotExist())
			.andExpect(jsonPath("$.items[0].experience").doesNotExist())
			.andReturn()
			.getResponse()
			.getContentAsString();
		List<String> found = JsonPath.read(body, "$.items[*].accountId");
		assertThat(found).as("a locked Mentor is absent (SG-44); one without GitHub linked is listed")
			.containsExactlyInAnyOrder(this.long_.toString(), this.ha.toString(), this.unlinked.toString());
		List<String> relations = JsonPath.read(body, "$.items[*].relation");
		assertThat(relations).containsOnly("NONE");
		String login = this.jdbc.sql("SELECT github_login FROM identity.account WHERE id = :id").param("id", this.long_)
			.query(String.class)
			.single();
		String publicProfile = this.mvc.perform(get("/api/v1/mentors/" + login)).andExpect(status().isOk())
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
		for (UUID unavailable : List.of(this.unlinked, this.locked, UUID.randomUUID(), this.lan)) {
			invite(unavailable).andExpect(status().is(422)).andExpect(jsonPath("$.reason").value("INVITEE_NOT_AVAILABLE"));
		}
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
