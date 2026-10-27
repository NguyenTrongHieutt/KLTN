package vn.vibefix.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.job.JobRole;
import vn.vibefix.job.JobRoles;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.MutableClock;
import vn.vibefix.team.internal.TeamTestSupport;

/**
 * AD-43, Story 20.2's cross-role row: X is the Customer of Job A and the Mentor of Job B, and its account still
 * carries the shim's Customer role. X acts as the Mentor on Job B's Mentor endpoints and as the Customer on Job
 * A's, because policies read the role in each Job and never the account; Job A's Crew and Mentor endpoints and
 * Job B's Customer endpoints answer {@code NOT_FOUND}, so a role in one Job grants nothing in another.
 */
class CrossRoleIT extends IntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private JobRoles roles;

	private UUID crossRole;

	private UUID crewOfA;

	private UUID jobA;

	private UUID jobB;

	@BeforeEach
	void anAccountThatIsTheCustomerOfOneJobAndTheMentorOfAnother() {
		this.crossRole = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		UUID mentorOfA = TeamTestSupport.mentor(this.jdbc, "Đỗ Mạnh Hùng", List.of(TeamTestSupport.REACT), "React");
		UUID customerOfB = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		this.crewOfA = student("Lê Thảo Vy");
		UUID crewOfB = student("Phạm Hải Tùng");
		this.jobA = activeJob(this.crossRole, mentorOfA, "Sổ đặt bàn Quán Mây");
		this.jobB = activeJob(customerOfB, this.crossRole, "Cửa hàng online");
		crewRow(this.jobA, this.crewOfA);
		crewRow(this.jobB, crewOfB);
	}

	@Test
	void roleOfReadsEachJobOnItsOwn() {
		assertThat(this.roles.roleOf(this.jobA, this.crossRole)).isEqualTo(JobRole.CUSTOMER);
		assertThat(this.roles.roleOf(this.jobB, this.crossRole)).isEqualTo(JobRole.MENTOR);
	}

	@Test
	void theAccountActsAsTheMentorOnJobBsMentorEndpoints() throws Exception {
		this.mvc.perform(get("/api/v1/mentor/jobs/" + this.jobB).with(asCrossRole()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(this.jobB.toString()));
		this.mvc.perform(get("/api/v1/boards/" + this.jobB + "/cancellation").with(asCrossRole()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.audience").value("MENTOR"));
		this.mvc.perform(get("/api/v1/jobs/" + this.jobB + "/busy").with(asCrossRole()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.audience").value("MENTOR"));
		this.mvc.perform(get("/api/v1/jobs/" + this.jobB + "/crew-chat").with(asCrossRole())).andExpect(status().isOk());
		this.mvc.perform(get("/api/v1/jobs/" + this.jobB + "/chat").with(asCrossRole())).andExpect(status().isOk());
		this.mvc.perform(get("/api/v1/mentor/jobs/" + this.jobB + "/funding").with(asCrossRole()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.jobId").value(this.jobB.toString()));
	}

	@Test
	void jobBsCustomerEndpointsRefuseTheAccount() throws Exception {
		for (String path : List.of("/api/v1/jobs/" + this.jobB, "/api/v1/jobs/" + this.jobB + "/agreement",
				"/api/v1/jobs/" + this.jobB + "/funding", "/api/v1/jobs/" + this.jobB + "/applications")) {
			this.mvc.perform(get(path).with(asCrossRole())).andExpect(status().isNotFound());
		}
		this.mvc.perform(post("/api/v1/jobs/" + this.jobB + "/withdraw").with(asCrossRole()).with(csrf()))
			.andExpect(status().isNotFound());
	}

	@Test
	void theAccountActsAsTheCustomerOnJobAAndJobAsCrewEndpointsRefuseIt() throws Exception {
		this.mvc.perform(get("/api/v1/jobs/" + this.jobA).with(asCrossRole()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(this.jobA.toString()));
		this.mvc.perform(get("/api/v1/jobs/" + this.jobA + "/busy").with(asCrossRole()))
			.andExpect(jsonPath("$.audience").value("CUSTOMER"));
		this.mvc.perform(get("/api/v1/jobs/" + this.jobA + "/funding").with(asCrossRole()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.jobId").value(this.jobA.toString()));
		this.mvc.perform(get("/api/v1/jobs/" + this.jobA + "/chat").with(asCrossRole())).andExpect(status().isOk());

		this.mvc.perform(get("/api/v1/jobs/" + this.jobA + "/crew-chat").with(asCrossRole())).andExpect(status().isNotFound());
		this.mvc.perform(post("/api/v1/crew/jobs/" + this.jobA + "/decline").with(asCrossRole()).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content("{\"crewTermsVersionNo\":1}")).andExpect(status().isNotFound());
		for (String path : List.of("/api/v1/mentor/jobs/" + this.jobA, "/api/v1/mentor/jobs/" + this.jobA + "/crew",
				"/api/v1/mentor/jobs/" + this.jobA + "/funding")) {
			this.mvc.perform(get(path).with(asCrossRole())).andExpect(status().isNotFound());
		}
		// Job A's own Crew member reads its Crew Chat, never its Job Chat.
		RequestPostProcessor crew = IdentityTestSupport.signedIn(this.sessions, this.crewOfA, Role.STUDENT,
				this.clock.instant());
		this.mvc.perform(get("/api/v1/jobs/" + this.jobA + "/crew-chat").with(crew)).andExpect(status().isOk());
		this.mvc.perform(get("/api/v1/jobs/" + this.jobA + "/chat").with(crew)).andExpect(status().isNotFound());
	}

	private RequestPostProcessor asCrossRole() {
		return IdentityTestSupport.signedIn(this.sessions, this.crossRole, Role.CUSTOMER, this.clock.instant());
	}

	private UUID student(String name) {
		return TeamTestSupport.student(this.jdbc, name, true, List.of(TeamTestSupport.REACT), "React",
				LocalDate.of(2026, 9, 30));
	}

	/** An Active Job on a Product of its own, with its Job Chat and Crew Chat open. */
	private UUID activeJob(UUID customer, UUID mentor, String title) {
		UUID product = UUID.randomUUID();
		Instant at = MutableClock.DEFAULT_INSTANT.minus(Duration.ofDays(10));
		this.jdbc.sql("""
				INSERT INTO product.product (id, customer_account_id, name, description, ai_tools, repository_github_id,
				    repository_full_name, repository_owner_type, repository_default_branch, public_notice_version,
				    copy_permission_version, detection_notice_version, acknowledged_at, created_at)
				VALUES (:id, :owner, :name, 'Mô tả', ARRAY['LOVABLE'], :github, 'lan/san-pham', 'User', 'main', '1.0', '1.0',
				    '1.0', :at, :at)
				""")
			.param("id", product)
			.param("owner", customer)
			.param("name", title)
			.param("github", ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE))
			.param("at", Timestamp.from(at))
			.update();
		UUID job = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, budget, deadline, rules, selection_days, opened_at, lapses_at, mentor_account_id,
				    selected_application_id, selected_at, agreement_version_no, version, created_at, updated_at)
				VALUES (:id, :product, :customer, 'ACTIVE', :title, 'Lỗi', 'Đúng', 'Khách', ARRAY['BUG_FIX'], 'UNDER_2M',
				    DATE '2026-12-01', '{}'::jsonb, 14, :at, :lapses, :mentor, :application, :at, 1, 3, :at, :at)
				""")
			.param("id", job)
			.param("product", product)
			.param("customer", customer)
			.param("title", title)
			.param("at", Timestamp.from(at))
			.param("lapses", Timestamp.from(at.plus(Duration.ofDays(14))))
			.param("mentor", mentor)
			.param("application", UUID.randomUUID())
			.update();
		for (String kind : List.of("JOB", "CREW")) {
			this.jdbc.sql("INSERT INTO chat.chat (id, job_id, product_id, kind, opened_at) VALUES (:id, :job, :product, :kind, :at)")
				.param("id", UUID.randomUUID())
				.param("job", job)
				.param("product", product)
				.param("kind", kind)
				.param("at", Timestamp.from(at))
				.update();
		}
		return job;
	}

	private void crewRow(UUID job, UUID member) {
		Timestamp at = Timestamp.from(MutableClock.DEFAULT_INSTANT.minus(Duration.ofDays(9)));
		this.jdbc.sql("""
				INSERT INTO job.crew_row (id, job_id, member_account_id, status, signed_at, activated_at, created_at)
				VALUES (:id, :job, :member, 'ACTIVE', :at, :at, :at)
				""").param("id", UUID.randomUUID()).param("job", job).param("member", member).param("at", at).update();
	}

}
