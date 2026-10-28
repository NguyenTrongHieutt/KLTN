package vn.vibefix.job.internal;

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

import org.hamcrest.Matchers;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.MutableClock;
import vn.vibefix.team.internal.TeamTestSupport;

/**
 * Story 20.6, AD-43, UX-DR86, UX-DR87: JOB-H reads the caller's role in one Job, whatever its state, and "Công
 * việc" lists every Job where the caller holds a role, newest change first, a former member's included; a Job
 * where the caller holds no role is not found, and an Administrator holds none anywhere.
 */
class JobRoleControllerIT extends IntegrationTest {

	private static final LocalDate VERIFIED_UNTIL = LocalDate.of(2026, 9, 30);

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Test
	void eachRoleHolderReadsTheirRoleInTheJobAndEveryoneElseFindsNothing() throws Exception {
		UUID customer = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		UUID mentor = TeamTestSupport.mentor(this.jdbc, "Đỗ Mạnh Hùng", List.of(TeamTestSupport.REACT), "React");
		UUID assigned = member("Thành viên được chọn");
		UUID active = member("Thành viên đang làm");
		UUID former = member("Thành viên đã rời");
		UUID declined = member("Thành viên đã từ chối");
		UUID unrelated = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		UUID admin = IdentityTestSupport.insertAccount(this.jdbc, Role.ADMINISTRATOR, null);
		UUID job = job(customer, mentor, "CLOSED", 10);
		crewRow(job, assigned, "ASSIGNED", false);
		crewRow(job, active, "ACTIVE", true);
		crewRow(job, former, "RELEASED", true);
		crewRow(job, declined, "RELEASED", false);

		role(customer, Role.CUSTOMER, job).andExpect(status().isOk())
			.andExpect(jsonPath("$.role").value("CUSTOMER"))
			.andExpect(jsonPath("$.crewKind").doesNotExist());
		role(mentor, Role.MENTOR, job).andExpect(jsonPath("$.role").value("MENTOR"));
		role(assigned, Role.STUDENT, job).andExpect(jsonPath("$.role").value("CREW"))
			.andExpect(jsonPath("$.crewKind").value("ASSIGNED"));
		role(active, Role.STUDENT, job).andExpect(jsonPath("$.crewKind").value("ACTIVE"));
		role(former, Role.STUDENT, job).andExpect(jsonPath("$.role").value("CREW"))
			.andExpect(jsonPath("$.crewKind").value("FORMER"));
		for (UUID nobody : List.of(declined, unrelated)) {
			role(nobody, Role.CUSTOMER, job).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
		}
		role(admin, Role.ADMINISTRATOR, job).andExpect(status().isNotFound());
		role(customer, Role.CUSTOMER, UUID.randomUUID()).andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/jobs/" + job + "/role")).andExpect(status().isUnauthorized());
	}

	@Test
	void myJobsListsEveryJobWhereTheCallerHoldsARoleNewestFirst() throws Exception {
		UUID lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		UUID other = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		UUID hung = TeamTestSupport.mentor(this.jdbc, "Đỗ Mạnh Hùng", List.of(TeamTestSupport.REACT), "React");
		UUID admin = IdentityTestSupport.insertAccount(this.jdbc, Role.ADMINISTRATOR, null);
		// Lan posts Job A and mentors Job B; she was on Job C's Crew and left; she declined Job D's pick.
		UUID postedDraft = job(lan, null, "DRAFT", 1);
		UUID mentored = job(other, lan, "ACTIVE", 3);
		UUID left = job(other, hung, "ACTIVE", 5);
		crewRow(left, lan, "RELEASED", true);
		UUID declined = job(other, hung, "AGREEMENT", 7);
		crewRow(declined, lan, "RELEASED", false);
		job(other, null, "OPEN", 2);

		this.mvc.perform(get("/api/v1/my-jobs").with(as(lan, Role.CUSTOMER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(3))
			.andExpect(jsonPath("$[*].jobId").value(Matchers.contains(postedDraft.toString(), mentored.toString(),
					left.toString())))
			.andExpect(jsonPath("$[0].role").value("CUSTOMER"))
			.andExpect(jsonPath("$[0].state").value("DRAFT"))
			.andExpect(jsonPath("$[0].productName").value("Sổ đặt bàn Quán Mây"))
			.andExpect(jsonPath("$[0].crewKind").doesNotExist())
			.andExpect(jsonPath("$[1].role").value("MENTOR"))
			.andExpect(jsonPath("$[2].role").value("CREW"))
			.andExpect(jsonPath("$[2].crewKind").value("FORMER"))
			.andExpect(jsonPath("$[2].title").value("Việc số 5"));
		this.mvc.perform(get("/api/v1/my-jobs").with(as(hung, Role.MENTOR)))
			.andExpect(jsonPath("$[*].role").value(Matchers.everyItem(Matchers.is("MENTOR"))))
			.andExpect(jsonPath("$.length()").value(2));
		this.mvc.perform(get("/api/v1/my-jobs").with(as(admin, Role.ADMINISTRATOR))).andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/my-jobs")).andExpect(status().isUnauthorized());
	}

	private ResultActions role(UUID account, Role role, UUID job) throws Exception {
		return this.mvc.perform(get("/api/v1/jobs/" + job + "/role").with(as(account, role)));
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor as(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

	private UUID member(String name) {
		return TeamTestSupport.student(this.jdbc, name, true, List.of(TeamTestSupport.REACT), "React", VERIFIED_UNTIL);
	}

	/** A Job on its own Product, changed {@code daysAgo} days before now; titled "Việc số {daysAgo}". */
	private UUID job(UUID customer, @Nullable UUID mentor, String state, int daysAgo) {
		UUID id = UUID.randomUUID();
		Instant at = MutableClock.DEFAULT_INSTANT.minus(Duration.ofDays(30));
		Instant updated = MutableClock.DEFAULT_INSTANT.minus(Duration.ofDays(daysAgo));
		boolean open = !"DRAFT".equals(state);
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, budget, deadline, rules, selection_days, opened_at, lapses_at, mentor_account_id,
				    selected_application_id, selected_at, closing_at, closed_at, version, created_at, updated_at)
				VALUES (:id, :product, :customer, :state, :title, 'Lỗi', 'Đúng', 'Khách', ARRAY['BUG_FIX'],
				    'UNDER_2M', DATE '2026-12-01', CAST(:rules AS jsonb), :days, :opened, :lapses, :mentor, :application, :selected,
				    :closing, :closed, 1, :at, :updated)
				""")
			.param("id", id)
			.param("product", product(customer))
			.param("customer", customer)
			.param("state", state)
			.param("title", "Việc số " + daysAgo)
			.param("rules", open ? "{}" : null)
			.param("days", open ? 14 : null)
			.param("opened", open ? Timestamp.from(at) : null)
			.param("lapses", open ? Timestamp.from(at.plus(Duration.ofDays(14))) : null)
			.param("mentor", mentor)
			.param("application", mentor == null ? null : UUID.randomUUID())
			.param("selected", mentor == null ? null : Timestamp.from(at))
			.param("closing", "CLOSED".equals(state) ? Timestamp.from(at.plus(Duration.ofDays(10))) : null)
			.param("closed", "CLOSED".equals(state) ? Timestamp.from(at.plus(Duration.ofDays(12))) : null)
			.param("at", Timestamp.from(at))
			.param("updated", Timestamp.from(updated))
			.update();
		return id;
	}

	private void crewRow(UUID job, UUID member, String status, boolean activated) {
		Instant at = MutableClock.DEFAULT_INSTANT.minus(Duration.ofDays(20));
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
			.param("end", activated && released ? Timestamp.from(at.plus(Duration.ofDays(5))) : null)
			.param("released", released ? Timestamp.from(at.plus(Duration.ofDays(5))) : null)
			.param("reason", released ? (activated ? "LEFT" : "DECLINED") : null)
			.param("at", Timestamp.from(at))
			.update();
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
			.param("id", id)
			.param("owner", owner)
			.param("github", ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE))
			.param("now", now)
			.update();
		return id;
	}

}
