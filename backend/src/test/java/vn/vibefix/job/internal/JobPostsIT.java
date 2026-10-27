package vn.vibefix.job.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import javax.imageio.ImageIO;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.Kind;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.MutableClock;
import vn.vibefix.team.internal.TeamTestSupport;

/**
 * FR-12, FR-13, AD-33, AD-36, AD-38: any user with GitHub linked browses and reads Open Job Posts that are not
 * hidden, never listing their own (Story 20.3).
 */
class JobPostsIT extends IntegrationTest {

	private static final String DATA_CATEGORY = "0192b000-0000-7000-8000-000000000003";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	private UUID lan;

	private UUID product;

	private UUID hung;

	@BeforeEach
	void aCustomerWithAProductAndAMentor() {
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		this.product = product(this.lan);
		this.hung = IdentityTestSupport.insertAccount(this.jdbc, Role.MENTOR, null);
	}

	@Test
	void aMentorBrowsesOpenVisibleJobPostsNewestFirstTwentyAPage() throws Exception {
		for (int i = 0; i < 21; i++) {
			openJob("Tin " + i, "BUG_FIX", TeamTestSupport.REACT, "React", "UNDER_2M", "2026-03-01",
					MutableClock.DEFAULT_INSTANT.minus(Duration.ofHours(21 - i)));
		}
		UUID hidden = openJob("Tin bị ẩn", "BUG_FIX", TeamTestSupport.REACT, "React", "UNDER_2M", "2026-03-01",
				MutableClock.DEFAULT_INSTANT);
		this.jdbc.sql("UPDATE job.job SET hidden_at = now(), hidden_reason = 'Vi phạm' WHERE id = :id")
			.param("id", hidden)
			.update();
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, job_types, created_at, updated_at)
				VALUES (:id, :product, :owner, 'DRAFT', 'Bản nháp', ARRAY['BUG_FIX'], now(), now())
				""").param("id", UUID.randomUUID()).param("product", this.product).param("owner", this.lan).update();

		list("").andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(21))
			.andExpect(jsonPath("$.size").value(20))
			.andExpect(jsonPath("$.items.length()").value(20))
			.andExpect(jsonPath("$.items[0].title").value("Tin 20"))
			.andExpect(jsonPath("$.items[0].productName").value("Sổ đặt bàn Quán Mây"))
			.andExpect(jsonPath("$.items[0].skills[0]").value("React"))
			.andExpect(jsonPath("$.items[0].customerId").doesNotExist());
		list("?page=1").andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].title").value("Tin 0"));

		// No Mentor needs an approval (Story 19.7); one locked after the session was read is refused.
		UUID locked = IdentityTestSupport.insertAccount(this.jdbc, Role.MENTOR, "Vi phạm");
		this.mvc.perform(get("/api/v1/mentor/job-posts").with(request -> {
			request.setAttribute(Caller.REQUEST_ATTRIBUTE, Caller.account(locked, Kind.USER));
			return request;
		})).andExpect(status().isForbidden()).andExpect(jsonPath("$.reason").value("ACCOUNT_LOCKED"));
		// No role gates "Tìm việc" (Story 20.3); its list never shows the reader's own Jobs (AD-36).
		this.mvc.perform(get("/api/v1/mentor/job-posts").with(as(this.lan, Role.CUSTOMER))).andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(0));
	}

	@Test
	void aUserWithNoRoleDraftsOnTheirOwnProductAndBrowsesEveryJobPostButTheirOwn() throws Exception {
		UUID minh = IdentityTestSupport.insertGoogleAccount(this.jdbc, "google-minh", "3.1");
		IdentityTestSupport.linkGitHub(this.jdbc, minh);
		UUID own = product(minh);
		UUID lans = openJob("Tin của Lan", "BUG_FIX", TeamTestSupport.REACT, "React", "UNDER_2M", "2026-03-01",
				MutableClock.DEFAULT_INSTANT);

		String created = this.mvc.perform(post("/api/v1/jobs").with(as(minh, null)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(draftBody(own)))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		UUID job = UUID.fromString(com.jayway.jsonpath.JsonPath.read(created, "$.id"));
		this.mvc.perform(post("/api/v1/jobs").with(as(minh, null)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(draftBody(this.product))).andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/jobs").with(as(minh, null))).andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].id").value(job.toString()));
		this.mvc.perform(post("/api/v1/jobs/" + job + "/publish").with(as(minh, null)).with(csrf()))
			.andExpect(status().isOk());

		this.mvc.perform(get("/api/v1/mentor/job-posts").with(as(minh, null))).andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].id").value(lans.toString()));
		this.mvc.perform(get("/api/v1/mentor/job-posts").with(as(this.hung, Role.MENTOR)))
			.andExpect(jsonPath("$.total").value(2));
		// The detail of one's own Job still reads; a bid on it is refused (Story 20.2).
		this.mvc.perform(get("/api/v1/mentor/job-posts/" + job).with(as(minh, null))).andExpect(status().isOk());
		this.mvc.perform(post("/api/v1/mentor/job-posts/" + job + "/applications").with(as(minh, null)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"priceVnd\":800000,\"timelineDays\":7,\"approach\":\"Cách làm\",\"publicCodeAcknowledged\":true}"))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("ROLE_TAKEN"));

		// Without GitHub linked, "Tìm việc" shows the gate's reason; drafting needs only an unlocked user.
		IdentityTestSupport.unlinkGitHub(this.jdbc, minh);
		this.mvc.perform(get("/api/v1/mentor/job-posts").with(as(minh, null))).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.reason").value("GITHUB_NOT_LINKED"));
		this.mvc.perform(get("/api/v1/jobs").with(as(minh, null))).andExpect(status().isOk());
	}

	@Test
	void theFiltersApplyTogether() throws Exception {
		openJob("A", "BUG_FIX", TeamTestSupport.REACT, "React", "UNDER_2M", "2026-01-15", MutableClock.DEFAULT_INSTANT);
		openJob("B", "NEW_FEATURE", TeamTestSupport.SUPABASE, "Supabase", "FROM_2M_TO_5M", "2026-03-01",
				MutableClock.DEFAULT_INSTANT.minusSeconds(60));
		openJob("C", "ASSESSMENT", TeamTestSupport.WEB_SECURITY, "Bảo mật web (OWASP Top 10)", "OVER_10M", "2026-01-10",
				MutableClock.DEFAULT_INSTANT.minusSeconds(120));

		assertTitles("?type=BUG_FIX", "A");
		assertTitles("?type=BUG_FIX&type=ASSESSMENT", "A", "C");
		assertTitles("?skill=" + TeamTestSupport.REACT, "A");
		assertTitles("?category=" + DATA_CATEGORY, "B");
		assertTitles("?skill=" + TeamTestSupport.REACT + "&category=" + DATA_CATEGORY, "A", "B");
		assertTitles("?budget=FROM_2M_TO_5M", "B");
		assertTitles("?within=14", "A", "C");
		assertTitles("?within=14&type=ASSESSMENT", "C");
		assertTitles("?skill=" + UUID.randomUUID());
		list("?within=0").andExpect(status().isBadRequest());
	}

	@Test
	void aJobPostShowsTheProductAnswersAndSkillsButNeverItsCustomer() throws Exception {
		UUID job = draft();
		String added = this.mvc.perform(multipart("/api/v1/jobs/" + job + "/screenshots")
			.file(new MockMultipartFile("file", "trang-dat-ban.png", "image/png", png()))
			.with(as(this.lan, Role.CUSTOMER))
			.with(csrf())).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		UUID file = UUID.fromString(com.jayway.jsonpath.JsonPath.read(added, "$.fileId"));
		this.mvc.perform(get("/api/v1/mentor/job-posts/" + job).with(as(this.hung, Role.MENTOR))).andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/files/" + file).with(as(this.hung, Role.MENTOR))).andExpect(status().isNotFound());
		this.mvc.perform(post("/api/v1/jobs/" + job + "/publish").with(as(this.lan, Role.CUSTOMER)).with(csrf()))
			.andExpect(status().isOk());

		String body = this.mvc.perform(get("/api/v1/mentor/job-posts/" + job).with(as(this.hung, Role.MENTOR)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.title").value("Thêm thanh toán online"))
			.andExpect(jsonPath("$.productName").value("Sổ đặt bàn Quán Mây"))
			.andExpect(jsonPath("$.productDescription").value("Đặt bàn cho quán."))
			.andExpect(jsonPath("$.productTools[0]").value("LOVABLE"))
			.andExpect(jsonPath("$.productOpenFindings").value(0))
			.andExpect(jsonPath("$.expected").value("Khách trả tiền cọc khi đặt bàn."))
			.andExpect(jsonPath("$.skills[0].name").value("React"))
			.andExpect(jsonPath("$.skills[0].hint").isNotEmpty())
			.andExpect(jsonPath("$.screenshots[0].url").value("/api/v1/files/" + file))
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(body).doesNotContain(this.lan.toString()).doesNotContain("user-");
		this.mvc.perform(get("/api/v1/files/" + file).with(as(this.hung, Role.MENTOR))).andExpect(status().isOk());
		this.jdbc.sql("""
				INSERT INTO identity.profile (account_id, kind, display_name, contact_email, updated_at)
				VALUES (:id, 'INDIVIDUAL', 'Nguyễn Thị Lan', 'lan@example.com', now())
				""").param("id", this.lan).update();
		String rating = this.mvc.perform(get("/api/v1/mentor/job-posts/" + job + "/customer-rating").with(as(this.hung, Role.MENTOR)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.count").value(0))
			.andExpect(jsonPath("$.average").value(org.hamcrest.Matchers.nullValue()))
			.andExpect(jsonPath("$.customerKind").value("INDIVIDUAL"))
			.andExpect(jsonPath("$.customerJoinedAt").isNotEmpty())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(rating).as("FR-12: never the Customer's name or contact").doesNotContain("Nguyễn Thị Lan")
			.doesNotContain("lan@example.com").doesNotContain(this.lan.toString());

		this.jdbc.sql("UPDATE job.job SET hidden_at = now(), hidden_reason = 'Vi phạm' WHERE id = :id")
			.param("id", job)
			.update();
		this.mvc.perform(get("/api/v1/mentor/job-posts/" + job).with(as(this.hung, Role.MENTOR))).andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/mentor/job-posts/" + job + "/customer-rating").with(as(this.hung, Role.MENTOR)))
			.andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/files/" + file).with(as(this.hung, Role.MENTOR))).andExpect(status().isNotFound());
	}

	@Test
	void capacityNamesWhyAMentorCannotApply() throws Exception {
		capacity().andExpect(status().isOk())
			.andExpect(jsonPath("$.teamSize").value(0))
			.andExpect(jsonPath("$.freeStudents").value(0))
			.andExpect(jsonPath("$.activeJobs").value(0))
			.andExpect(jsonPath("$.jobLimit").value(3))
			.andExpect(jsonPath("$.reasons[0]").value("NO_FREE_STUDENT"));

		UUID team = UUID.randomUUID();
		this.jdbc.sql("INSERT INTO team.team (id, mentor_account_id, status, created_at) VALUES (:id, :mentor, 'OPEN', now())")
			.param("id", team)
			.param("mentor", this.hung)
			.update();
		join(team, TeamTestSupport.student(this.jdbc, "Chưa xác minh", true, List.of(TeamTestSupport.REACT), "React", null));
		capacity().andExpect(jsonPath("$.teamSize").value(1))
			.andExpect(jsonPath("$.freeStudents").value(0))
			.andExpect(jsonPath("$.reasons[0]").value("NO_FREE_STUDENT"));

		join(team, TeamTestSupport.student(this.jdbc, "Đã xác minh", true, List.of(TeamTestSupport.REACT), "React",
				LocalDate.parse("2026-09-30")));
		capacity().andExpect(jsonPath("$.teamSize").value(2))
			.andExpect(jsonPath("$.freeStudents").value(1))
			.andExpect(jsonPath("$.reasons.length()").value(0));
	}

	private ResultActions list(String query) throws Exception {
		return this.mvc.perform(get("/api/v1/mentor/job-posts" + query).with(as(this.hung, Role.MENTOR)));
	}

	private ResultActions capacity() throws Exception {
		return this.mvc.perform(get("/api/v1/mentor/capacity").with(as(this.hung, Role.MENTOR)));
	}

	private void assertTitles(String query, String... titles) throws Exception {
		String body = list(query).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		List<String> found = com.jayway.jsonpath.JsonPath.read(body, "$.items[*].title");
		assertThat(found).as(query).containsExactly(titles);
	}

	private void join(UUID team, UUID student) {
		this.jdbc.sql("""
				INSERT INTO team.membership (id, team_id, student_account_id, joined_at) VALUES (:id, :team, :student, now())
				""").param("id", UUID.randomUUID()).param("team", team).param("student", student).update();
	}

	private UUID openJob(String title, String type, String skillId, String skillName, String budget, String deadline,
			Instant openedAt) {
		UUID id = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, skill_ids, skill_names, budget, deadline, rules, selection_days, opened_at, lapses_at,
				    version, created_at, updated_at)
				VALUES (:id, :product, :owner, 'OPEN', :title, 'Lỗi', 'Đúng', 'Khách', CAST(:types AS TEXT[]),
				    CAST(:skills AS UUID[]), CAST(:names AS TEXT[]), :budget, CAST(:deadline AS DATE), '{}'::jsonb, 14,
				    :opened, :lapses, 1, :opened, :opened)
				""")
			.param("id", id)
			.param("product", this.product)
			.param("owner", this.lan)
			.param("title", title)
			.param("types", "{" + type + "}")
			.param("skills", "{" + skillId + "}")
			.param("names", new String[] { skillName })
			.param("budget", budget)
			.param("deadline", deadline)
			.param("opened", Timestamp.from(openedAt))
			.param("lapses", Timestamp.from(openedAt.plus(Duration.ofDays(14))))
			.update();
		return id;
	}

	private UUID draft() throws Exception {
		String created = this.mvc.perform(post("/api/v1/jobs").with(as(this.lan, Role.CUSTOMER)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"productId":"%s","title":"Thêm thanh toán online","problem":"Khách chưa trả tiền trước được.",
					 "expected":"Khách trả tiền cọc khi đặt bàn.","appUsers":"Khách đặt bàn trên điện thoại.",
					 "jobTypes":["NEW_FEATURE"],"skillIds":["%s"],"budget":"FROM_2M_TO_5M","deadline":"2026-11-15"}
					""".formatted(this.product, TeamTestSupport.REACT)))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return UUID.fromString(com.jayway.jsonpath.JsonPath.read(created, "$.id"));
	}

	private static String draftBody(UUID product) {
		return """
				{"productId":"%s","title":"Thêm thanh toán online","problem":"Khách chưa trả tiền trước được.",
				 "expected":"Khách trả tiền cọc khi đặt bàn.","appUsers":"Khách đặt bàn trên điện thoại.",
				 "jobTypes":["NEW_FEATURE"],"skillIds":["%s"],"budget":"FROM_2M_TO_5M","deadline":"2026-11-15"}
				""".formatted(product, TeamTestSupport.REACT);
	}

	private UUID product(UUID owner) {
		UUID id = UUID.randomUUID();
		Timestamp now = Timestamp.from(MutableClock.DEFAULT_INSTANT);
		this.jdbc.sql("""
				INSERT INTO product.product (id, customer_account_id, name, description, ai_tools, repository_github_id,
				    repository_full_name, repository_owner_type, repository_default_branch, public_notice_version,
				    copy_permission_version, detection_notice_version, acknowledged_at, created_at)
				VALUES (:id, :owner, 'Sổ đặt bàn Quán Mây', 'Đặt bàn cho quán.', ARRAY['LOVABLE'], :github,
				    'lan/so-dat-ban', 'User', 'main', '1.0', '1.0', '1.0', :now, :now)
				""")
			.param("id", id)
			.param("owner", owner)
			.param("github", ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE))
			.param("now", now)
			.update();
		return id;
	}

	private static byte[] png() throws Exception {
		java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(4, 3, java.awt.image.BufferedImage.TYPE_INT_RGB);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "png", out);
		return out.toByteArray();
	}

	private RequestPostProcessor as(UUID account, @Nullable Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

}
