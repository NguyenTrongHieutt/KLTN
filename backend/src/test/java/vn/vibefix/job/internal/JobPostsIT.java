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
 * FR-12, FR-13, AD-33, AD-36, AD-38, AD-43: any user with GitHub linked browses and reads the Jobs that need people,
 * Open or recruiting their Crew with a free slot, by phase (Story 21.1), never listing a Job where they hold a role
 * (Story 20.3).
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
		// Story 21.3 (AD-33): the Customer's summary as Customer, the Mentors' Ratings of them.
		publishedRating(this.lan, 4);
		String rating = this.mvc.perform(get("/api/v1/mentor/job-posts/" + job + "/customer-rating").with(as(this.hung, Role.MENTOR)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.count").value(1))
			.andExpect(jsonPath("$.average").value(4.0))
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

	/** Story 21.1, UX-DR97: every Job that needs people, by phase, with what it needs and the Customer's kind. */
	@Test
	void everyJobThatNeedsPeopleIsListedWithItsPhaseAndWhatItNeeds() throws Exception {
		this.jdbc.sql("""
				INSERT INTO identity.profile (account_id, kind, display_name, contact_email, updated_at)
				VALUES (:id, 'BUSINESS', 'Nguyễn Thị Lan', 'lan@example.com', now())
				""").param("id", this.lan).update();
		UUID open = openJob("Tin mở", "BUG_FIX", TeamTestSupport.REACT, "React", "UNDER_2M", "2026-03-01",
				MutableClock.DEFAULT_INSTANT.minus(Duration.ofHours(3)));
		UUID ha = TeamTestSupport.mentor(this.jdbc, "Phạm Thu Hà", List.of(TeamTestSupport.REACT), "React");
		UUID recruiting = crewJob("Đang tuyển thêm", "AGREEMENT", ha, true, 2, "Cần người làm trang bán hàng",
				MutableClock.DEFAULT_INSTANT.minus(Duration.ofHours(1)));
		UUID vy = member();
		crewRow(recruiting, vy, "ACTIVE", true);
		UUID longMentor = TeamTestSupport.mentor(this.jdbc, "Trần Đức Long", List.of(TeamTestSupport.REACT), "React");
		UUID noMember = crewJob("Chưa có ai", "AGREEMENT", longMentor, true, 1, null,
				MutableClock.DEFAULT_INSTANT.minus(Duration.ofHours(2)));
		// A pick not signed yet is no counted member (AD-41).
		crewRow(noMember, member(), "ASSIGNED", false);

		String body = list("").andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(3))
			.andExpect(jsonPath("$.items[0].id").value(recruiting.toString()))
			.andExpect(jsonPath("$.items[0].phase").value("RECRUITING"))
			.andExpect(jsonPath("$.items[0].need").value("CREW"))
			.andExpect(jsonPath("$.items[0].mentor.displayName").value("Phạm Thu Hà"))
			.andExpect(jsonPath("$.items[0].mentor.githubLogin").value(TeamTestSupport.login(this.jdbc, ha)))
			.andExpect(jsonPath("$.items[0].recruitment.openSlots").value(2))
			.andExpect(jsonPath("$.items[0].recruitment.skills[0]").value("Supabase"))
			.andExpect(jsonPath("$.items[0].recruitment.note").value("Cần người làm trang bán hàng"))
			.andExpect(jsonPath("$.items[0].customerKind").value("BUSINESS"))
			.andExpect(jsonPath("$.items[0].customerJoinedAt").isNotEmpty())
			.andExpect(jsonPath("$.items[1].id").value(noMember.toString()))
			.andExpect(jsonPath("$.items[1].phase").value("FINDING_CREW"))
			.andExpect(jsonPath("$.items[1].need").value("CREW"))
			.andExpect(jsonPath("$.items[1].recruitment.note").value(org.hamcrest.Matchers.nullValue()))
			.andExpect(jsonPath("$.items[2].id").value(open.toString()))
			.andExpect(jsonPath("$.items[2].phase").value("FINDING_MENTOR"))
			.andExpect(jsonPath("$.items[2].need").value("MENTOR"))
			.andExpect(jsonPath("$.items[2].mentor").value(org.hamcrest.Matchers.nullValue()))
			.andExpect(jsonPath("$.items[2].recruitment").value(org.hamcrest.Matchers.nullValue()))
			.andExpect(jsonPath("$.items[2].customerKind").value("BUSINESS"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(body).as("AD-36: never the Customer's name or contact, the Crew, or a price")
			.doesNotContain("Nguyễn Thị Lan")
			.doesNotContain("lan@example.com")
			.doesNotContain(this.lan.toString())
			.doesNotContain(vy.toString())
			.doesNotContain(TeamTestSupport.login(this.jdbc, vy))
			.doesNotContain("price");

		assertIds("?phase=RECRUITING", recruiting);
		assertIds("?phase=FINDING_CREW", noMember);
		assertIds("?phase=FINDING_MENTOR", open);
		assertIds("?phase=FINDING_MENTOR&phase=FINDING_CREW", noMember, open);
		assertIds("?phase=RECRUITING&type=NEW_FEATURE");
		// The Skill filter also matches the Skills a recruiting Job seeks, as the keyword does.
		assertIds("?skill=" + TeamTestSupport.SUPABASE, recruiting, noMember);
	}

	/** X6: a Starting or Active Job recruiting with no counted member looks for its Crew; with one it recruits more. */
	@Test
	void thePhaseFilterReadsStartingAndActiveJobsToo() throws Exception {
		UUID ha = TeamTestSupport.mentor(this.jdbc, "Phạm Thu Hà", List.of(TeamTestSupport.REACT), "React");
		UUID started = crewJob("Đang bắt đầu, có người", "STARTING", ha, true, 1, null,
				MutableClock.DEFAULT_INSTANT.minus(Duration.ofHours(1)));
		crewRow(started, member(), "ACTIVE", true);
		UUID longMentor = TeamTestSupport.mentor(this.jdbc, "Trần Đức Long", List.of(TeamTestSupport.REACT), "React");
		UUID running = crewJob("Đang làm, chưa ai", "ACTIVE", longMentor, true, 1, null,
				MutableClock.DEFAULT_INSTANT.minus(Duration.ofHours(2)));

		list("").andExpect(jsonPath("$.items[0].id").value(started.toString()))
			.andExpect(jsonPath("$.items[0].phase").value("RECRUITING"))
			.andExpect(jsonPath("$.items[1].id").value(running.toString()))
			.andExpect(jsonPath("$.items[1].phase").value("FINDING_CREW"));
		assertIds("?phase=RECRUITING", started);
		assertIds("?phase=FINDING_CREW", running);
		assertIds("?phase=FINDING_MENTOR");
	}

	/** Story 20.4, AD-41: the phase shown counts an ACTIVE row only while its account is unlocked. */
	@Test
	void aLockedMembersRowCountsForNothingInThePhaseShown() throws Exception {
		UUID ha = TeamTestSupport.mentor(this.jdbc, "Phạm Thu Hà", List.of(TeamTestSupport.REACT), "React");
		UUID job = crewJob("Thành viên bị khóa", "ACTIVE", ha, true, 1, null, MutableClock.DEFAULT_INSTANT);
		UUID vy = member();
		crewRow(job, vy, "ACTIVE", true);
		list("").andExpect(jsonPath("$.items[0].phase").value("RECRUITING"));

		IdentityTestSupport.lock(this.jdbc, vy, "Vi phạm");

		list("").andExpect(jsonPath("$.items[0].id").value(job.toString()))
			.andExpect(jsonPath("$.items[0].phase").value("FINDING_CREW"));
		this.mvc.perform(get("/api/v1/mentor/job-posts/" + job).with(as(this.hung, Role.MENTOR)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.phase").value("FINDING_CREW"));
		// The filter counts the rows in SQL until the lock's listener releases the row (Story 20.4).
		assertIds("?phase=RECRUITING", job);
	}

	/**
	 * AD-36, AD-43: a Job where the reader is the Customer, the Mentor, or a member (assigned, active, or left) is
	 * neither listed nor found, and neither is one that takes nobody: recruitment off, no slot left, or hidden.
	 */
	@Test
	void noJobWhereTheReaderHoldsARoleNorOneThatTakesNobodyIsListedOrFound() throws Exception {
		UUID ha = TeamTestSupport.mentor(this.jdbc, "Phạm Thu Hà", List.of(TeamTestSupport.REACT), "React");
		Instant since = MutableClock.DEFAULT_INSTANT.minus(Duration.ofHours(1));
		UUID control = crewJob("Việc chung đang tuyển", "AGREEMENT", ha, true, 1, null, since);
		UUID own = openJob(product(this.hung), this.hung, "Việc chung của Hùng", MutableClock.DEFAULT_INSTANT);
		crewJob("Việc chung Hùng hướng dẫn", "AGREEMENT", this.hung, true, 2, null, since);
		UUID vy = member();
		UUID assigned = crewJob("Việc chung chờ Vy ký", "AGREEMENT", ha, true, 2, null, since);
		crewRow(assigned, vy, "ASSIGNED", false);
		UUID minh = member();
		UUID active = crewJob("Việc chung Minh đang làm", "ACTIVE", ha, true, 2, null, since);
		crewRow(active, minh, "ACTIVE", true);
		UUID tung = member();
		UUID left = crewJob("Việc chung Tùng đã rời", "STARTING", ha, true, 2, null, since);
		crewRow(left, tung, "RELEASED", true);
		crewJob("Việc chung không tuyển", "AGREEMENT", ha, false, 2, null, since);
		crewJob("Việc chung đủ người", "AGREEMENT", ha, true, 0, null, since);
		UUID lam = member();
		UUID declined = crewJob("Việc chung Lâm đã từ chối", "AGREEMENT", ha, true, 2, null, since);
		crewRow(declined, lam, "RELEASED", false);
		UUID hidden = crewJob("Việc chung bị ẩn", "AGREEMENT", ha, true, 2, null, since);
		this.jdbc.sql("UPDATE job.job SET hidden_at = now(), hidden_reason = 'Vi phạm' WHERE id = :id")
			.param("id", hidden)
			.update();

		assertThat(ids(this.hung, "")).containsExactlyInAnyOrder(control.toString(), assigned.toString(),
				active.toString(), left.toString(), declined.toString());
		assertThat(ids(this.hung, "?q=viec chung")).containsExactlyInAnyOrder(control.toString(), assigned.toString(),
				active.toString(), left.toString(), declined.toString());
		// A pick declined before it was signed holds no role (AD-43): its Job stays listed and found.
		assertThat(ids(lam, "")).contains(declined.toString());
		assertThat(ids(lam, "?q=viec chung")).contains(declined.toString());
		assertThat(ids(vy, "?q=viec chung")).contains(control.toString(), own.toString())
			.doesNotContain(assigned.toString());
		assertThat(ids(minh, "")).contains(control.toString()).doesNotContain(active.toString());
		assertThat(ids(tung, "")).contains(control.toString()).doesNotContain(left.toString());
		assertThat(ids(this.lan, "")).as("the Customer of every other one").containsExactly(own.toString());
	}

	/**
	 * MEN-3 (UX-DR98): a listed Job reads with its phase, its Mentor and what the recruitment seeks, for its own
	 * people too, who see their role; once it is no longer listed, the plain gate's NOT_FOUND.
	 */
	@Test
	void aListedJobReadsWithItsRecruitmentAndOneNoLongerListedIsNotFound() throws Exception {
		UUID ha = TeamTestSupport.mentor(this.jdbc, "Phạm Thu Hà", List.of(TeamTestSupport.REACT), "React");
		UUID job = crewJob("Đang tuyển thêm", "STARTING", ha, true, 2, "Cần người làm trang bán hàng",
				MutableClock.DEFAULT_INSTANT);
		UUID vy = member();
		crewRow(job, vy, "ACTIVE", true);

		String body = this.mvc.perform(get("/api/v1/mentor/job-posts/" + job).with(as(this.hung, Role.MENTOR)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.title").value("Đang tuyển thêm"))
			.andExpect(jsonPath("$.phase").value("RECRUITING"))
			.andExpect(jsonPath("$.need").value("CREW"))
			.andExpect(jsonPath("$.mentor.displayName").value("Phạm Thu Hà"))
			.andExpect(jsonPath("$.recruitment.openSlots").value(2))
			.andExpect(jsonPath("$.recruitment.skills[0]").value("Supabase"))
			.andExpect(jsonPath("$.recruitment.note").value("Cần người làm trang bán hàng"))
			.andExpect(jsonPath("$.lapsesAt").value(org.hamcrest.Matchers.nullValue()))
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(body).doesNotContain(vy.toString()).doesNotContain(TeamTestSupport.login(this.jdbc, vy));
		this.mvc.perform(get("/api/v1/mentor/job-posts/" + job + "/customer-rating").with(as(this.hung, Role.MENTOR)))
			.andExpect(status().isOk());
		// The Job's own Customer reads it too, and MEN-3 shows their role in it in place of an action.
		this.mvc.perform(get("/api/v1/mentor/job-posts/" + job).with(as(this.lan, Role.CUSTOMER)))
			.andExpect(status().isOk());
		this.mvc.perform(get("/api/v1/jobs/" + job + "/role").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.role").value("CUSTOMER"));

		this.jdbc.sql("UPDATE job.job SET recruiting = false WHERE id = :id").param("id", job).update();

		this.mvc.perform(get("/api/v1/mentor/job-posts/" + job).with(as(this.hung, Role.MENTOR)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));
		this.mvc.perform(get("/api/v1/mentor/job-posts/" + job + "/customer-rating").with(as(this.hung, Role.MENTOR)))
			.andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/jobs/" + job + "/role").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.role").value("CUSTOMER"));
	}

	/** FR-12, AD-20: a recruiting Job's screenshot is read on MEN-3 while the Job is listed, and only then. */
	@Test
	void aRecruitingJobsScreenshotIsReadWhileItIsListed() throws Exception {
		UUID job = draft();
		String added = this.mvc.perform(multipart("/api/v1/jobs/" + job + "/screenshots")
			.file(new MockMultipartFile("file", "trang-dat-ban.png", "image/png", png()))
			.with(as(this.lan, Role.CUSTOMER))
			.with(csrf())).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		UUID file = UUID.fromString(com.jayway.jsonpath.JsonPath.read(added, "$.fileId"));
		UUID ha = TeamTestSupport.mentor(this.jdbc, "Phạm Thu Hà", List.of(TeamTestSupport.REACT), "React");
		Timestamp now = Timestamp.from(MutableClock.DEFAULT_INSTANT);
		this.jdbc.sql("""
				UPDATE job.job SET state = 'AGREEMENT', rules = '{}'::jsonb, selection_days = 14, opened_at = :now,
				    mentor_account_id = :mentor, selected_application_id = :application, selected_at = :now,
				    recruiting = true, recruiting_since = :now, open_slots = 1
				WHERE id = :id
				""")
			.param("id", job)
			.param("now", now)
			.param("mentor", ha)
			.param("application", UUID.randomUUID())
			.update();
		UUID minh = member();

		this.mvc.perform(get("/api/v1/files/" + file).with(as(minh, null))).andExpect(status().isOk());

		this.jdbc.sql("UPDATE job.job SET recruiting = false WHERE id = :id").param("id", job).update();
		this.mvc.perform(get("/api/v1/files/" + file).with(as(minh, null))).andExpect(status().isNotFound());
	}

	/** SG-43: a page of rows reads its Customers' Rating summaries in one call, keyed by the Job, never the Customer. */
	@Test
	void aPageOfRowsReadsItsCustomersRatingsInOneCall() throws Exception {
		UUID open = openJob("Tin mở", "BUG_FIX", TeamTestSupport.REACT, "React", "UNDER_2M", "2026-03-01",
				MutableClock.DEFAULT_INSTANT);
		UUID draft = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, job_types, created_at, updated_at)
				VALUES (:id, :product, :owner, 'DRAFT', 'Bản nháp', ARRAY['BUG_FIX'], now(), now())
				""").param("id", draft).param("product", this.product).param("owner", this.lan).update();

		// Lan holds two published Ratings from two Mentors; Bình, another Customer, none.
		publishedRating(this.lan, 5);
		publishedRating(this.lan, 4);
		UUID binh = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		UUID binhs = openJob(product(binh), binh, "Tin của Bình", MutableClock.DEFAULT_INSTANT);

		String body = this.mvc
			.perform(get("/api/v1/mentor/customer-ratings").param("post", open.toString(), draft.toString(), binhs.toString())
				.with(as(this.hung, Role.MENTOR)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[0].postId").value(open.toString()))
			.andExpect(jsonPath("$[0].average").value(4.5))
			.andExpect(jsonPath("$[0].count").value(2))
			.andExpect(jsonPath("$[1].postId").value(binhs.toString()))
			.andExpect(jsonPath("$[1].average").value(org.hamcrest.Matchers.nullValue()))
			.andExpect(jsonPath("$[1].count").value(0))
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(body).doesNotContain(this.lan.toString()).doesNotContain(binh.toString());
		this.mvc.perform(get("/api/v1/mentor/customer-ratings").with(as(this.hung, Role.MENTOR)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(0));
		String[] tooMany = java.util.stream.IntStream.range(0, 21)
			.mapToObj(i -> UUID.randomUUID().toString())
			.toArray(String[]::new);
		this.mvc.perform(get("/api/v1/mentor/customer-ratings").param("post", tooMany).with(as(this.hung, Role.MENTOR)))
			.andExpect(status().isBadRequest());
	}

	@Test
	void capacityNamesOnlyTheMentorJobLimitAndNoTeam() throws Exception {
		// Story 21.2: no Team and no free Student is asked for; only the Mentor Job limit stops a bid.
		capacity().andExpect(status().isOk())
			.andExpect(jsonPath("$.teamSize").doesNotExist())
			.andExpect(jsonPath("$.freeStudents").doesNotExist())
			.andExpect(jsonPath("$.activeJobs").value(0))
			.andExpect(jsonPath("$.jobLimit").value(3))
			.andExpect(jsonPath("$.reasons.length()").value(0));

		this.jdbc.sql("UPDATE settings.rule_value SET value = 1 WHERE rule_key = 'MENTOR_ACTIVE_JOB_LIMIT'").update();
		try {
			UUID running = openJob("Đang chọn", "BUG_FIX", TeamTestSupport.REACT, "React", "UNDER_2M", "2026-03-01",
					MutableClock.DEFAULT_INSTANT);
			this.jdbc.sql("""
					UPDATE job.job SET state = 'SELECTING', mentor_account_id = :m, selected_application_id = :a,
					    selected_at = now(), opened_at = NULL, lapses_at = NULL WHERE id = :id
					""").param("m", this.hung).param("a", UUID.randomUUID()).param("id", running).update();
			capacity().andExpect(jsonPath("$.activeJobs").value(1))
				.andExpect(jsonPath("$.reasons[0]").value("MENTOR_JOB_LIMIT"))
				.andExpect(jsonPath("$.reasons.length()").value(1));
		}
		finally {
			this.jdbc.sql("UPDATE settings.rule_value SET value = 3 WHERE rule_key = 'MENTOR_ACTIVE_JOB_LIMIT'").update();
		}
	}

	private ResultActions list(String query) throws Exception {
		return this.mvc.perform(get("/api/v1/mentor/job-posts" + query).with(as(this.hung, Role.MENTOR)));
	}

	private ResultActions capacity() throws Exception {
		return this.mvc.perform(get("/api/v1/mentor/capacity").with(as(this.hung, Role.MENTOR)));
	}

	private void assertIds(String query, UUID... ids) throws Exception {
		assertThat(ids(this.hung, query)).as(query)
			.containsExactly(java.util.Arrays.stream(ids).map(UUID::toString).toArray(String[]::new));
	}

	private List<String> ids(UUID reader, String query) throws Exception {
		String body = this.mvc.perform(get("/api/v1/mentor/job-posts" + query).with(as(reader, null)))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return com.jayway.jsonpath.JsonPath.read(body, "$.items[*].id");
	}

	private void assertTitles(String query, String... titles) throws Exception {
		String body = list(query).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		List<String> found = com.jayway.jsonpath.JsonPath.read(body, "$.items[*].title");
		assertThat(found).as(query).containsExactly(titles);
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

	/** An Open Job Post of {@code owner}, opened at {@code openedAt}. */
	private UUID openJob(UUID product, UUID owner, String title, Instant openedAt) {
		UUID id = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, job_types, rules, selection_days,
				    opened_at, lapses_at, version, created_at, updated_at)
				VALUES (:id, :product, :owner, 'OPEN', :title, ARRAY['BUG_FIX'], '{}'::jsonb, 14, :opened, :lapses, 1,
				    :opened, :opened)
				""")
			.param("id", id)
			.param("product", product)
			.param("owner", owner)
			.param("title", title)
			.param("opened", Timestamp.from(openedAt))
			.param("lapses", Timestamp.from(openedAt.plus(Duration.ofDays(14))))
			.update();
		return id;
	}

	/**
	 * A Job of Lan past Open, on a Product of its own, with its Mentor and the Crew recruitment setting Story 21.4's
	 * commands will write: React on the Job Post, Supabase sought, on since {@code since} while {@code recruiting}.
	 */
	private UUID crewJob(String title, String state, UUID mentor, boolean recruiting, int slots, @Nullable String note,
			Instant since) {
		UUID id = UUID.randomUUID();
		Instant opened = MutableClock.DEFAULT_INSTANT.minus(Duration.ofDays(10));
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, skill_ids, skill_names, budget, deadline, rules, selection_days, opened_at, lapses_at,
				    mentor_account_id, selected_application_id, selected_at, recruiting, recruiting_since, open_slots,
				    recruit_skill_ids, recruit_skill_names, recruit_note, version, created_at, updated_at)
				VALUES (:id, :product, :owner, :state, :title, 'Lỗi', 'Đúng', 'Khách', ARRAY['BUG_FIX'],
				    CAST(:skills AS UUID[]), ARRAY['React'], 'UNDER_2M', DATE '2026-03-01', '{}'::jsonb, 14, :opened,
				    :lapses, :mentor, :application, :opened, :recruiting, :since, :slots, CAST(:sought AS UUID[]),
				    ARRAY['Supabase'], :note, 1, :opened, :opened)
				""")
			.param("id", id)
			.param("product", product(this.lan))
			.param("owner", this.lan)
			.param("state", state)
			.param("title", title)
			.param("skills", "{" + TeamTestSupport.REACT + "}")
			.param("opened", Timestamp.from(opened))
			.param("lapses", Timestamp.from(opened.plus(Duration.ofDays(14))))
			.param("mentor", mentor)
			.param("application", UUID.randomUUID())
			.param("recruiting", recruiting)
			.param("since", recruiting ? Timestamp.from(since) : null)
			.param("slots", slots)
			.param("sought", "{" + TeamTestSupport.SUPABASE + "}")
			.param("note", note)
			.update();
		return id;
	}

	/** A published Rating a Mentor gave {@code customer} for a Job of their own (FR-49). */
	private void publishedRating(UUID customer, int stars) {
		UUID window = UUID.randomUUID();
		UUID mentor = IdentityTestSupport.insertAccount(this.jdbc, Role.MENTOR, null);
		Timestamp at = Timestamp.from(MutableClock.DEFAULT_INSTANT.minus(Duration.ofDays(20)));
		this.jdbc.sql("""
				INSERT INTO reputation.rating_window (job_id, product_id, customer_account_id, mentor_account_id, job_title,
				    opened_at, ends_at, pair_published_at)
				VALUES (:job, :product, :customer, :mentor, 'Việc đã xong', :at, :ends, :at)
				""")
			.param("job", window)
			.param("product", this.product)
			.param("customer", customer)
			.param("mentor", mentor)
			.param("at", at)
			.param("ends", Timestamp.from(MutableClock.DEFAULT_INSTANT.minus(Duration.ofDays(6))))
			.update();
		this.jdbc.sql("""
				INSERT INTO reputation.rating (id, job_id, kind, rater_account_id, ratee_account_id, stars, comment, created_at)
				VALUES (:id, :job, 'MENTOR_TO_CUSTOMER', :mentor, :customer, :stars, 'Rõ ràng', :at)
				""")
			.param("id", UUID.randomUUID())
			.param("job", window)
			.param("mentor", mentor)
			.param("customer", customer)
			.param("stars", stars)
			.param("at", at)
			.update();
	}

	/** A person with GitHub linked and no role yet. */
	private UUID member() {
		return IdentityTestSupport.insertAccount(this.jdbc, Role.STUDENT, null);
	}

	/** A Crew row; a released one that was activated left the Job, one never activated declined its pick. */
	private void crewRow(UUID job, UUID member, String status, boolean activated) {
		Instant at = MutableClock.DEFAULT_INSTANT.minus(Duration.ofDays(5));
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
			.param("reason", released ? (activated ? "LEFT" : "DECLINED") : null)
			.param("at", Timestamp.from(at))
			.update();
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
