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
 * PUB-4's own suite (R17, AD-20, AD-33, review 2026-10-27 A-06, Stories 20.5 and 21.3): the public profile of a
 * Mentor and of a Crew member, its identity half, its two histories, and the Bid cards' histories in short, stays
 * public to everyone, which is why the matrix keeps it out of the Customer's Crew-name scan, so it must name nothing
 * of the Jobs behind it: never a Customer's name, a co-member's name or GitHub username, a Job ID, a running Job or a
 * current Crew role, and a Product's name only when the Customer allowed it on the approved summary. Each history
 * counts its own role's Jobs, Ratings and Portfolio Entries only.
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

	/** A Job still running, which no history names (R17). */
	private static final String RUNNING_JOB = "Việc đang chạy của Quán Mây";

	/** The Mentor under whom Hùng was a Crew member, on another Customer's Job. */
	private static final String OTHER_MENTOR = "Phan Văn Tâm Hướng Dẫn";

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

	private UUID tam;

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
		// Hùng was a Crew member too, under another Mentor on another Customer's Job, and rated 3 stars there.
		this.tam = TeamTestSupport.mentor(this.jdbc, OTHER_MENTOR, List.of(TeamTestSupport.REACT), "React");
		crewJob();
		// A Job still running, with Vy on its Crew: no history names it, or that she is on it.
		UUID product = JobContent.product(this.jdbc, this.lan, "Ứng dụng chạy Quán Mây");
		UUID running = JobContent.activeJob(this.jdbc, product, this.lan, this.hung, RUNNING_JOB);
		this.jobs.add(running);
		JobContent.crewRow(this.jdbc, running, this.vy, "ACTIVE", null);
	}

	@Test
	void theMentorsHistoriesCountEachRoleApartAndNameNoCustomerCoMemberOrJob() throws Exception {
		for (RequestPostProcessor viewer : viewers()) {
			String body = read("/api/v1/users/" + login(this.hung) + "/reputation", viewer);

			assertNamesNothingOfTheJobs(body, Map.of(this.vy, List.of(VY_STUDENT_NAME, VY_DISPLAY_NAME), this.minh,
					List.of(MINH_STUDENT_NAME, MINH_DISPLAY_NAME)));
			assertThat(body).as("the other Mentor").doesNotContain(OTHER_MENTOR, login(this.tam), this.tam.toString());
			JsonNode reputation = JSON.readTree(body);
			JsonNode mentor = reputation.path("mentor");
			assertThat(mentor.path("jobs").asInt()).isEqualTo(2);
			assertThat(mentor.path("jobTypes").get(0).path("jobType").asString()).isEqualTo("BUG_FIX");
			assertThat(mentor.path("jobTypes").get(0).path("count").asInt()).as("one per Customer (AD-17)").isOne();
			assertThat(mentor.path("count").asInt()).as("at most one Rating per pair (AD-33)").isOne();
			assertThat(mentor.path("average").decimalValue()).isEqualByComparingTo("5.0");
			assertThat(entries(mentor)).hasSize(2).allSatisfy(entry -> assertThat(entry.path("role").asString()).isEqualTo("MENTOR"));
			JsonNode crew = reputation.path("crew");
			assertThat(crew.path("jobs").asInt()).isOne();
			assertThat(crew.path("count").asInt()).isOne();
			assertThat(crew.path("average").decimalValue()).isEqualByComparingTo("3.0");
			assertThat(entries(crew)).hasSize(1).allSatisfy(entry -> assertThat(entry.path("role").asString()).isEqualTo("CREW"));
			assertThat(reputation.path("newcomer").asBoolean()).isFalse();
		}
	}

	@Test
	void aCrewMembersProfileAndHistoriesNameNoCustomerCoMemberOrJob() throws Exception {
		for (RequestPostProcessor viewer : viewers()) {
			String body = read("/api/v1/users/" + login(this.vy) + "/reputation", viewer);

			assertNamesNothingOfTheJobs(body, Map.of(this.minh, List.of(MINH_STUDENT_NAME, MINH_DISPLAY_NAME)));
			assertThat(body).as("the Mentor")
				.doesNotContain(login(this.hung))
				.doesNotContain(this.hung.toString())
				.doesNotContain("Đỗ Mạnh Hùng");
			JsonNode reputation = JSON.readTree(body);
			assertThat(reputation.path("mentor").path("jobs").asInt()).as("a role never held").isZero();
			assertThat(entries(reputation.path("mentor"))).isEmpty();
			assertThat(reputation.path("crew").path("jobs").asInt()).isEqualTo(2);
			assertThat(entries(reputation.path("crew"))).hasSize(2)
				.allSatisfy(entry -> assertThat(entry.path("role").asString()).isEqualTo("CREW"));

			String profile = read("/api/v1/users/" + login(this.vy), viewer);
			assertThat(profile).contains(VY_DISPLAY_NAME);
			assertThat(profile).as("PUB-4's identity half names no Job").doesNotContain(CUSTOMER_NAME, login(this.lan),
					login(this.hung), "Đỗ Mạnh Hùng", RUNNING_JOB, MINH_DISPLAY_NAME, login(this.minh));
			for (UUID job : this.jobs) {
				assertThat(profile).doesNotContain(job.toString());
			}
		}
	}

	@Test
	void theBidCardsHistoriesInShortAreThePublicOnesByUsername() throws Exception {
		for (RequestPostProcessor viewer : viewers()) {
			String body = read("/api/v1/reputation/histories?username=" + login(this.hung) + "&username=" + login(this.vy)
					+ "&username=khong-co-ai", viewer);

			assertThat(body).doesNotContain(CUSTOMER_NAME, RUNNING_JOB, this.hung.toString(), this.vy.toString(),
					ALLOWED_PRODUCT, HIDDEN_PRODUCT);
			JsonNode histories = JSON.readTree(body);
			assertThat(histories.size()).as("an unknown username is left out").isEqualTo(2);
			JsonNode hung = histories.get(0);
			assertThat(hung.path("username").asString()).isEqualTo(login(this.hung));
			assertThat(hung.path("mentor").path("jobs").asInt()).isEqualTo(2);
			assertThat(hung.path("mentor").path("portfolio").asInt()).isEqualTo(2);
			assertThat(hung.path("mentor").path("average").decimalValue()).isEqualByComparingTo("5.0");
			assertThat(hung.path("crew").path("jobs").asInt()).isOne();
			assertThat(hung.path("crew").path("portfolio").asInt()).isOne();
			assertThat(hung.path("crew").path("average").decimalValue()).isEqualByComparingTo("3.0");
			assertThat(hung.path("newcomer").asBoolean()).isFalse();
			JsonNode vy = histories.get(1);
			assertThat(vy.path("username").asString()).isEqualTo(login(this.vy));
			assertThat(vy.path("mentor").path("jobs").asInt()).isZero();
			assertThat(vy.path("crew").path("portfolio").asInt()).isEqualTo(2);
			assertThat(vy.path("newcomer").asBoolean()).isTrue();
		}
	}

	/** No Customer, no co-member by either name, login or ID, no Job ID, no running Job, and a Product only where allowed. */
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
		assertThat(body).as("a running Job").doesNotContain(RUNNING_JOB);
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

	private static List<JsonNode> entries(JsonNode role) {
		List<JsonNode> entries = new ArrayList<>();
		role.path("portfolio").forEach(entries::add);
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
		Instant closed = MutableClock.DEFAULT_INSTANT.minus(Duration.ofDays(2));
		UUID job = JobContent.job(this.jdbc, product, this.lan, this.hung, "Sửa lỗi đặt bàn", JobContent.SIGNED, "CLOSED");
		this.jobs.add(job);
		accepted(job, closed);
		JobContent.crewRow(this.jdbc, job, this.vy, "LEFT", closed);
		JobContent.crewRow(this.jdbc, job, this.minh, "LEFT", closed);
		Timestamp at = Timestamp.from(closed);
		for (Object[] entry : new Object[][] { { this.hung, "MENTOR" }, { this.vy, "CREW" }, { this.minh, "CREW" } }) {
			entry(job, product, (UUID) entry[0], (String) entry[1], productName, at);
		}
		summary(job, product, showProduct, this.hung, at);
		window(job, product, this.lan, this.hung, closed);
		rating(job, "CUSTOMER_TO_MENTOR", this.lan, this.hung, 5, at);
		rating(job, "MENTOR_TO_STUDENT", this.hung, this.vy, 5, at);
		rating(job, "MENTOR_TO_STUDENT", this.hung, this.minh, 5, at);
		rating(job, "MENTOR_TO_CUSTOMER", this.hung, this.lan, 4, at);
	}

	/** Another Customer's Job, closed with Accepted work under Tâm, with Hùng on its Crew and rated 3 stars by Tâm. */
	private void crewJob() {
		UUID owner = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		UUID product = JobContent.product(this.jdbc, owner, "Sản phẩm của khách khác");
		Instant closed = MutableClock.DEFAULT_INSTANT.minus(Duration.ofDays(3));
		UUID job = JobContent.job(this.jdbc, product, owner, this.tam, "Việc làm thành viên nhóm", JobContent.SIGNED,
				"CLOSED");
		this.jobs.add(job);
		accepted(job, closed);
		JobContent.crewRow(this.jdbc, job, this.hung, "JOB_ENDED", closed);
		Timestamp at = Timestamp.from(closed);
		entry(job, product, this.hung, "CREW", "Sản phẩm của khách khác", at);
		entry(job, product, this.tam, "MENTOR", "Sản phẩm của khách khác", at);
		window(job, product, owner, this.tam, closed);
		rating(job, "MENTOR_TO_STUDENT", this.tam, this.hung, 3, at);
	}

	/** One accepted Milestone, so J9's history counts the Job (job.historyOf). */
	private void accepted(UUID job, Instant closed) {
		JobContent.milestone(this.jdbc, job, UUID.randomUUID(), 0, "Giai đoạn 1", 800_000, "ACCEPTED",
				closed.minus(Duration.ofDays(5)), closed.minus(Duration.ofDays(1)), UUID.randomUUID(),
				closed.minus(Duration.ofDays(6)));
	}

	private void entry(UUID job, UUID product, UUID account, String role, String productName, Timestamp at) {
		boolean mentor = "MENTOR".equals(role);
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
			.param("account", account)
			.param("role", role)
			.param("productName", productName)
			.param("skills", "{" + TeamTestSupport.REACT + "}")
			.param("approved", mentor ? null : 3)
			.param("handedOver", mentor ? 1 : null)
			.param("reviews", mentor ? 4 : null)
			.param("at", at)
			.update();
	}

	private void summary(UUID job, UUID product, boolean showProduct, UUID mentor, Timestamp at) {
		this.jdbc.sql("""
				INSERT INTO reputation.portfolio_summary (job_id, product_id, status, text, show_product_name, drafted_by,
				    drafted_at, decided_by, decided_at, created_at)
				VALUES (:job, :product, 'APPROVED', 'Sửa lỗi đặt trùng bàn và thêm kiểm thử.', :show, :mentor, :at, :lan, :at, :at)
				""")
			.param("job", job)
			.param("product", product)
			.param("show", showProduct)
			.param("mentor", mentor)
			.param("lan", this.lan)
			.param("at", at)
			.update();
	}

	private void window(UUID job, UUID product, UUID customer, UUID mentor, Instant closed) {
		this.jdbc.sql("""
				INSERT INTO reputation.rating_window (job_id, product_id, customer_account_id, mentor_account_id, job_title,
				    opened_at, ends_at, pair_published_at, crew_published_at)
				VALUES (:job, :product, :customer, :mentor, 'Sửa lỗi đặt bàn', :opened, :at, :at, :at)
				""").param("job", job).param("product", product).param("customer", customer).param("mentor", mentor)
			.param("opened", Timestamp.from(closed.minus(Duration.ofDays(14))))
			.param("at", Timestamp.from(closed))
			.update();
	}

	private void rating(UUID job, String kind, UUID rater, UUID ratee, int stars, Timestamp at) {
		this.jdbc.sql("""
				INSERT INTO reputation.rating (id, job_id, kind, rater_account_id, ratee_account_id, stars, comment, created_at)
				VALUES (:id, :job, :kind, :rater, :ratee, :stars, 'Làm tốt', :at)
				""")
			.param("id", UUID.randomUUID())
			.param("job", job)
			.param("kind", kind)
			.param("rater", rater)
			.param("ratee", ratee)
			.param("stars", stars)
			.param("at", at)
			.update();
	}

}
