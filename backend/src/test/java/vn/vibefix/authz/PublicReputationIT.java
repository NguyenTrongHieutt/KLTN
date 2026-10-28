package vn.vibefix.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.MutableClock;
import vn.vibefix.team.internal.TeamTestSupport;

/**
 * PUB-4's own suite (R17, AD-20, review 2026-10-27 A-06, Story 20.5): the public reputation of a Mentor and of a
 * Crew member stays public to everyone, which is why the matrix keeps it out of the Customer's Crew-name scan, so
 * it must name nothing of the Jobs behind it: never a Customer's name, a co-member's name or GitHub username, or a
 * Job ID, and a Product's name only when the Customer allowed it on the approved summary.
 */
class PublicReputationIT extends IntegrationTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String CUSTOMER_NAME = "Trần Thị Lan Khách";

	/** Each Crew member's two names: the shown student name, from their verification, and the display name. */
	private static final String VY_STUDENT_NAME = "Lê Thảo Vy";

	private static final String VY_DISPLAY_NAME = "Thảo Vy Hiển Thị";

	private static final String MINH_STUDENT_NAME = "Nguyễn Văn Minh";

	private static final String MINH_DISPLAY_NAME = "Văn Minh Đồng Đội";

	private static final String ALLOWED_PRODUCT = "Sổ đặt bàn Quán Mây";

	private static final String HIDDEN_PRODUCT = "Ứng dụng nội bộ Quán Mây";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	private UUID lan;

	private UUID hung;

	private UUID vy;

	private UUID minh;

	private final List<UUID> jobs = new ArrayList<>();

	@BeforeEach
	void twoJobsClosedWithAcceptedWork() {
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		JobContent.customerProfile(this.jdbc, this.lan, CUSTOMER_NAME, "lan.khach@example.test");
		this.hung = TeamTestSupport.mentor(this.jdbc, "Đỗ Mạnh Hùng", List.of(TeamTestSupport.REACT), "React");
		this.vy = JobContent.member(this.jdbc, VY_STUDENT_NAME);
		displayName(this.vy, VY_DISPLAY_NAME);
		this.minh = JobContent.member(this.jdbc, MINH_STUDENT_NAME);
		displayName(this.minh, MINH_DISPLAY_NAME);
		closedJob(ALLOWED_PRODUCT, true);
		closedJob(HIDDEN_PRODUCT, false);
	}

	@Test
	void theMentorsReputationNamesNoCustomerCoMemberOrJob() throws Exception {
		for (RequestPostProcessor viewer : viewers()) {
			String body = read("/api/v1/mentors/" + login(this.hung) + "/reputation", viewer);

			assertNamesNothingOfTheJobs(body, Map.of(this.vy, List.of(VY_STUDENT_NAME, VY_DISPLAY_NAME), this.minh,
					List.of(MINH_STUDENT_NAME, MINH_DISPLAY_NAME)));
			assertThat(portfolio(body)).hasSize(2);
		}
	}

	@Test
	void aCrewMembersReputationNamesNoCustomerCoMemberOrJob() throws Exception {
		for (RequestPostProcessor viewer : viewers()) {
			String body = read("/api/v1/students/" + login(this.vy) + "/reputation", viewer);

			assertNamesNothingOfTheJobs(body, Map.of(this.minh, List.of(MINH_STUDENT_NAME, MINH_DISPLAY_NAME)));
			assertThat(body).as("the Mentor")
				.doesNotContain(login(this.hung))
				.doesNotContain(this.hung.toString())
				.doesNotContain("Đỗ Mạnh Hùng");
			assertThat(portfolio(body)).hasSize(2).allSatisfy(entry -> assertThat(entry.path("role").asString()).isEqualTo("CREW"));
		}
	}

	/** No Customer, no co-member by either name, login or ID, no Job ID, and a Product only where allowed. */
	private void assertNamesNothingOfTheJobs(String body, Map<UUID, List<String>> coMembers) {
		assertThat(body).as("the Customer").doesNotContain(CUSTOMER_NAME, login(this.lan), this.lan.toString(),
				"lan.khach@example.test");
		coMembers.forEach((coMember, names) -> {
			assertThat(body).as("a co-member").doesNotContain(login(coMember), coMember.toString());
			names.forEach(name -> assertThat(body).as("a co-member's name").doesNotContain(name));
		});
		for (UUID job : this.jobs) {
			assertThat(body).as("a Job ID").doesNotContain(job.toString());
		}
		assertThat(body).as("the Product the Customer allowed").contains(ALLOWED_PRODUCT);
		assertThat(body).as("the Product the Customer did not allow").doesNotContain(HIDDEN_PRODUCT);
	}

	/** Anyone, signed in or not: the Customer of both Jobs and the co-member included. */
	private List<RequestPostProcessor> viewers() {
		return List.of(request -> request,
				IdentityTestSupport.signedIn(this.sessions, this.lan, Role.CUSTOMER, MutableClock.DEFAULT_INSTANT),
				IdentityTestSupport.signedIn(this.sessions, this.minh, Role.STUDENT, MutableClock.DEFAULT_INSTANT));
	}

	private String read(String path, RequestPostProcessor viewer) throws Exception {
		MockHttpServletResponse response = this.mvc.perform(get(path).with(viewer)).andReturn().getResponse();
		assertThat(response.getStatus()).as(path).isEqualTo(200);
		return response.getContentAsString(StandardCharsets.UTF_8);
	}

	private static List<JsonNode> portfolio(String body) {
		List<JsonNode> entries = new ArrayList<>();
		JSON.readTree(body).path("portfolio").forEach(entries::add);
		return entries;
	}

	private void displayName(UUID account, String name) {
		this.jdbc.sql("UPDATE identity.profile SET display_name = :name WHERE account_id = :id")
			.param("name", name)
			.param("id", account)
			.update();
	}

	private String login(UUID account) {
		return TeamTestSupport.login(this.jdbc, account);
	}

	/**
	 * A Job of Lan's Product closed with Accepted work, its published Portfolio Entries for Hùng, Vy and Minh, the
	 * Ratings shown, and the summary Lan approved, naming the Product only when {@code showProduct}.
	 */
	private void closedJob(String productName, boolean showProduct) {
		UUID product = JobContent.product(this.jdbc, this.lan, productName);
		UUID job = JobContent.activeJob(this.jdbc, product, this.lan, this.hung, "Sửa lỗi đặt bàn");
		this.jobs.add(job);
		Instant closed = MutableClock.DEFAULT_INSTANT.minus(Duration.ofDays(2));
		this.jdbc.sql("UPDATE job.job SET state = 'CLOSED' WHERE id = :id").param("id", job).update();
		JobContent.crewRow(this.jdbc, job, this.vy, "LEFT", closed);
		JobContent.crewRow(this.jdbc, job, this.minh, "LEFT", closed);
		Timestamp at = Timestamp.from(closed);
		for (Object[] entry : new Object[][] { { this.hung, "MENTOR" }, { this.vy, "CREW" }, { this.minh, "CREW" } }) {
			boolean mentor = "MENTOR".equals(entry[1]);
			this.jdbc.sql("""
					INSERT INTO reputation.portfolio_entry (id, job_id, product_id, account_id, role, job_title, product_name,
					    job_types, skill_ids, milestones_accepted, work_items_approved, milestones_handed_over, reviews_written,
					    closed_at, published_at, created_at)
					VALUES (:id, :job, :product, :account, :role, 'Sửa lỗi đặt bàn', :productName, ARRAY['BUG_FIX'],
					    CAST(:skills AS UUID[]), 1, :approved, :handedOver, :reviews, :at, :at, :at)
					""")
				.param("id", UUID.randomUUID())
				.param("job", job)
				.param("product", product)
				.param("account", entry[0])
				.param("role", entry[1])
				.param("productName", productName)
				.param("skills", "{" + TeamTestSupport.REACT + "}")
				.param("approved", mentor ? null : 3)
				.param("handedOver", mentor ? 1 : null)
				.param("reviews", mentor ? 4 : null)
				.param("at", at)
				.update();
		}
		this.jdbc.sql("""
				INSERT INTO reputation.portfolio_summary (job_id, product_id, status, text, show_product_name, drafted_by,
				    drafted_at, decided_by, decided_at, created_at)
				VALUES (:job, :product, 'APPROVED', 'Sửa lỗi đặt trùng bàn và thêm kiểm thử.', :show, :mentor, :at, :lan, :at, :at)
				""")
			.param("job", job)
			.param("product", product)
			.param("show", showProduct)
			.param("mentor", this.hung)
			.param("lan", this.lan)
			.param("at", at)
			.update();
		this.jdbc.sql("""
				INSERT INTO reputation.rating_window (job_id, product_id, customer_account_id, mentor_account_id, job_title,
				    opened_at, ends_at, pair_published_at, crew_published_at)
				VALUES (:job, :product, :lan, :hung, 'Sửa lỗi đặt bàn', :opened, :at, :at, :at)
				""").param("job", job).param("product", product).param("lan", this.lan).param("hung", this.hung)
			.param("opened", Timestamp.from(closed.minus(Duration.ofDays(14))))
			.param("at", at)
			.update();
		rating(job, "CUSTOMER_TO_MENTOR", this.lan, this.hung, at);
		rating(job, "MENTOR_TO_STUDENT", this.hung, this.vy, at);
		rating(job, "MENTOR_TO_STUDENT", this.hung, this.minh, at);
	}

	private void rating(UUID job, String kind, UUID rater, UUID ratee, Timestamp at) {
		this.jdbc.sql("""
				INSERT INTO reputation.rating (id, job_id, kind, rater_account_id, ratee_account_id, stars, comment, created_at)
				VALUES (:id, :job, :kind, :rater, :ratee, 5, 'Làm tốt', :at)
				""")
			.param("id", UUID.randomUUID())
			.param("job", job)
			.param("kind", kind)
			.param("rater", rater)
			.param("ratee", ratee)
			.param("at", at)
			.update();
	}

}
