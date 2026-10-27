package vn.vibefix.job.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

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
import vn.vibefix.job.JobTransitioned;
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.team.internal.TeamTestSupport;

/**
 * FR-36, AD-29: a due-date Change Request takes effect only when the Customer approved and the Mentor
 * confirmed on the loaded Job version, the Customer's needing the Mentor's time impact first; it writes
 * the next confirmed Agreement version, refuses final and Submitted Milestones, and lapses with the
 * Milestone's acceptance.
 */
class ChangeRequestIT extends IntegrationTest {

	private static final LocalDate NEW_DUE = LocalDate.parse("2027-01-20");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private ChangeRequestListeners listeners;

	@Autowired
	private ChangeRequests requests;

	private UUID lan;

	private UUID hung;

	private UUID vy;

	private UUID first;

	private UUID second;

	@Test
	void theCustomersRequestNeedsTheImpactThenBothSidesAndWritesVersionTwo() throws Exception {
		UUID job = world();
		propose(this.lan, Role.CUSTOMER, job, this.second, NEW_DUE, null).andExpect(status().isCreated())
			.andExpect(jsonPath("$.open.proposer").value("CUSTOMER"))
			.andExpect(jsonPath("$.open.items[0].number").value(2))
			.andExpect(jsonPath("$.canPropose").value(false));
		assertThat(notifications("job.change_request_proposed", this.hung)).isEqualTo(1);
		assertThat(openTodos("job.add_change_impact", this.hung)).isEqualTo(1);
		String request = JsonPath.read(read(this.lan, Role.CUSTOMER, job).andReturn().getResponse().getContentAsString(),
				"$.open.id");
		command(this.lan, Role.CUSTOMER, request, "approve", "{\"version\":" + version(job) + "}")
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("IMPACT_MISSING"));
		command(this.lan, Role.CUSTOMER, request, "impact", "{\"impact\":\"Thêm hai tuần\"}").andExpect(status().isForbidden())
			.andExpect(jsonPath("$.reason").value("MENTOR_ONLY"));
		command(this.hung, Role.MENTOR, request, "impact", "{\"impact\":\"Thêm hai tuần\"}").andExpect(status().isOk())
			.andExpect(jsonPath("$.open.impact").value("Thêm hai tuần"));
		assertThat(openTodos("job.add_change_impact", this.hung)).isZero();
		assertThat(notifications("job.change_request_impact_added", this.lan)).isEqualTo(1);
		command(this.lan, Role.CUSTOMER, request, "approve", "{\"version\":" + (version(job) - 1) + "}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("STALE"));
		command(this.lan, Role.CUSTOMER, request, "approve", "{\"version\":" + version(job) + "}").andExpect(status().isOk())
			.andExpect(jsonPath("$.open.customerApprovedAt").isNotEmpty());
		assertThat(openTodos("job.answer_change_request", this.lan)).isZero();
		assertThat(openTodos("job.answer_change_request", this.hung)).isEqualTo(1);

		long before = version(job);
		command(this.hung, Role.MENTOR, request, "confirm", "{\"version\":" + before + "}").andExpect(status().isOk())
			.andExpect(jsonPath("$.open").doesNotExist())
			.andExpect(jsonPath("$.history[0].status").value("APPLIED"))
			.andExpect(jsonPath("$.history[0].agreementVersionNo").value(2))
			.andExpect(jsonPath("$.milestones[1].dueOn").value(NEW_DUE.toString()));
		assertThat(version(job)).isEqualTo(before + 1);
		assertThat(this.jdbc.sql("SELECT agreement_version_no FROM job.job WHERE id = :id").param("id", job)
			.query(Integer.class).single()).isEqualTo(2);
		assertThat(this.jdbc.sql("""
				SELECT m ->> 'dueOn' FROM job.confirmed_agreement a, jsonb_array_elements(a.content -> 'milestones') m
				WHERE a.job_id = :job AND a.version_no = 2 AND m ->> 'id' = :milestone
				""").param("job", job).param("milestone", this.second.toString()).query(String.class).single())
			.isEqualTo(NEW_DUE.toString());
		assertThat(notifications("job.change_request_applied", null)).isEqualTo(2);
		assertThat(openTodos("job.answer_change_request", this.hung)).isZero();
		this.mvc.perform(get("/api/v1/boards/" + job + "/agreement-versions").with(as(this.vy, Role.STUDENT)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.versions.length()").value(2))
			.andExpect(jsonPath("$.versions[0].versionNo").value(2))
			.andExpect(jsonPath("$.versions[1].milestones[1].dueOn").value("2026-11-30"));
		read(this.vy, Role.STUDENT, job).andExpect(status().isNotFound());

		this.jdbc.sql("UPDATE job.milestone SET state = 'ACCEPTED', accepted_at = now() WHERE id = :id")
			.param("id", this.first).update();
		propose(this.hung, Role.MENTOR, job, this.first, NEW_DUE, "Thêm một tuần").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reason").value("MILESTONE_FINAL"));
	}

	@Test
	void aSubmittedMilestoneWaitsAndAcceptanceLapsesTheRequest() throws Exception {
		UUID job = world();
		propose(this.hung, Role.MENTOR, job, this.first, NEW_DUE, "Thêm một tuần").andExpect(status().isCreated())
			.andExpect(jsonPath("$.open.impact").value("Thêm một tuần"));
		assertThat(openTodos("job.answer_change_request", this.lan)).isEqualTo(1);
		propose(this.lan, Role.CUSTOMER, job, this.second, NEW_DUE, null).andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("CHANGE_REQUEST_OPEN"));
		String request = JsonPath.read(read(this.hung, Role.MENTOR, job).andReturn().getResponse().getContentAsString(),
				"$.open.id");
		this.jdbc.sql("""
				UPDATE job.milestone SET state = 'SUBMITTED', submitted_at = now(), handover_id = :handover WHERE id = :id
				""").param("id", this.first).param("handover", UUID.randomUUID()).update();
		command(this.lan, Role.CUSTOMER, request, "approve", "{\"version\":" + version(job) + "}")
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("MILESTONE_SUBMITTED"));

		this.requests.lapse(job, this.second);
		assertThat(text("SELECT status FROM job.change_request WHERE id = '" + request + "'")).isEqualTo("OPEN");
		this.listeners.onJobTransitioned(new JobTransitioned(UUID.randomUUID(), UUID.randomUUID(), job, "M5", "USER",
				"SUBMITTED", "ACCEPTED", 9, 1, Map.of("milestoneId", this.first.toString())));
		await().atMost(Duration.ofSeconds(10))
			.until(() -> "LAPSED".equals(text("SELECT status FROM job.change_request WHERE id = '" + request + "'")));
		assertThat(notifications("job.change_request_lapsed", null)).isEqualTo(2);
		assertThat(openTodos("job.answer_change_request", this.lan)).isZero();
		read(this.lan, Role.CUSTOMER, job).andExpect(jsonPath("$.canPropose").value(true))
			.andExpect(jsonPath("$.history[0].status").value("LAPSED"));
	}

	@Test
	void theProposerWithdraws() throws Exception {
		UUID job = world();
		propose(this.lan, Role.CUSTOMER, job, this.second, NEW_DUE, null).andExpect(status().isCreated());
		String request = JsonPath.read(read(this.lan, Role.CUSTOMER, job).andReturn().getResponse().getContentAsString(),
				"$.open.id");
		this.mvc.perform(post("/api/v1/change-requests/" + request + "/withdraw").with(as(this.hung, Role.MENTOR)).with(csrf()))
			.andExpect(status().isForbidden());
		this.mvc.perform(post("/api/v1/change-requests/" + request + "/withdraw").with(as(this.lan, Role.CUSTOMER)).with(csrf()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.history[0].status").value("WITHDRAWN"));
		assertThat(openTodos("job.add_change_impact", this.hung)).isZero();
	}

	/** An Active Job with a confirmed Agreement: Milestone 1 Funded, Milestone 2 Planned. */
	private UUID world() {
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		this.hung = TeamTestSupport.mentor(this.jdbc, "Đỗ Mạnh Hùng",
				List.of(TeamTestSupport.REACT), "React");
		this.vy = TeamTestSupport.student(this.jdbc, "Trần Thảo Vy", true, List.of(TeamTestSupport.REACT), "React",
				LocalDate.parse("2026-09-30"));
		UUID product = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO product.product (id, customer_account_id, name, description, ai_tools, repository_github_id,
				    repository_full_name, repository_owner_type, repository_default_branch, public_notice_version,
				    copy_permission_version, detection_notice_version, acknowledged_at, created_at)
				VALUES (:id, :owner, 'Sổ đặt bàn', 'Đặt bàn.', ARRAY['LOVABLE'], :github, 'lan/so-dat-ban', 'User', 'main',
				    '1.0', '1.0', '1.0', now(), now())
				""").param("id", product).param("owner", this.lan)
			.param("github", ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE)).update();
		UUID job = UUID.randomUUID();
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, budget, deadline, rules, selection_days, opened_at, lapses_at, mentor_account_id,
				    selected_application_id, selected_at, version, created_at, updated_at)
				VALUES (:id, :product, :owner, 'ACTIVE', 'Sửa sổ đặt bàn', 'Lỗi', 'Đúng', 'Khách', ARRAY['BUG_FIX'],
				    'UNDER_2M', DATE '2026-12-01', '{}'::jsonb, 14, :now, :now, :mentor, :application, :now, 6, :now, :now)
				""").param("id", job).param("product", product).param("owner", this.lan).param("mentor", this.hung)
			.param("application", UUID.randomUUID()).param("now", now).update();
		this.first = UUID.randomUUID();
		this.second = UUID.randomUUID();
		String content = """
				{"scope":"Sửa lỗi","priceVnd":1600000,"codeJob":true,"assessment":false,"skills":[],"externalAccess":[],
				 "milestones":[
				   {"id":"%s","title":"Sửa lỗi trùng giờ","priceVnd":800000,"dueOn":"2026-11-15","revisionRounds":2,"criteria":[]},
				   {"id":"%s","title":"Nhắc lịch","priceVnd":800000,"dueOn":"2026-11-30","revisionRounds":2,"criteria":[]}]}
				""".formatted(this.first, this.second);
		this.jdbc.sql("""
				INSERT INTO job.agreement_version (job_id, version_no, status, content, author_account_id, created_at)
				VALUES (:job, 1, 'SHARED', CAST(:content AS JSONB), :mentor, :now)
				""").param("job", job).param("content", content).param("mentor", this.hung).param("now", now).update();
		this.jdbc.sql("""
				INSERT INTO job.confirmed_agreement (job_id, version_no, draft_version_no, content, crew_usernames,
				    freeze_committed, customer_account_id, approved_at, mentor_account_id, confirmed_at)
				VALUES (:job, 1, 1, CAST(:content AS JSONB), '{}', true, :owner, :now, :mentor, :now)
				""").param("job", job).param("content", content).param("owner", this.lan).param("mentor", this.hung)
			.param("now", now).update();
		this.jdbc.sql("UPDATE job.job SET agreement_version_no = 1 WHERE id = :id").param("id", job).update();
		milestone(job, this.first, 0, "Sửa lỗi trùng giờ", "2026-11-15", true, now);
		milestone(job, this.second, 1, "Nhắc lịch", "2026-11-30", false, now);
		Timestamp signed = Timestamp.from(this.clock.instant().minus(Duration.ofDays(5)));
		this.jdbc.sql("""
				INSERT INTO job.crew_row (id, job_id, student_account_id, status, signed_at, activated_at, created_at)
				VALUES (:id, :job, :student, 'ACTIVE', :signed, :signed, now())
				""").param("id", UUID.randomUUID()).param("job", job).param("student", this.vy).param("signed", signed).update();
		return job;
	}

	private void milestone(UUID job, UUID id, int position, String title, String due, boolean funded, Timestamp now) {
		this.jdbc.sql("""
				INSERT INTO job.milestone (id, job_id, position, title, price_vnd, due_on, revision_rounds, created_at, state,
				    funded_at, crew_terms_version_no)
				VALUES (:id, :job, :position, :title, 800000, CAST(:due AS DATE), 2, :now, :state, :funded, :terms)
				""")
			.param("id", id)
			.param("job", job)
			.param("position", position)
			.param("title", title)
			.param("due", due)
			.param("now", now)
			.param("state", funded ? "FUNDED" : "PLANNED")
			.param("funded", funded ? now : null)
			.param("terms", funded ? 1 : null)
			.update();
	}

	private ResultActions read(UUID account, Role role, UUID job) throws Exception {
		return this.mvc.perform(get("/api/v1/boards/" + job + "/change-requests").with(as(account, role)));
	}

	private ResultActions propose(UUID account, Role role, UUID job, UUID milestone, LocalDate due, String impact)
			throws Exception {
		String body = """
				{"description":"Khách cần thêm thời gian kiểm tra","impact":%s,"items":[{"milestoneId":"%s","newDueOn":"%s"}],
				 "version":%d}
				""".formatted(impact == null ? "null" : "\"" + impact + "\"", milestone, due, version(job));
		return this.mvc.perform(post("/api/v1/boards/" + job + "/change-requests").with(as(account, role)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	private ResultActions command(UUID account, Role role, String request, String action, String body) throws Exception {
		return this.mvc.perform(post("/api/v1/change-requests/" + request + "/" + action).with(as(account, role)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	private long version(UUID job) {
		return this.jdbc.sql("SELECT version FROM job.job WHERE id = :id").param("id", job).query(Long.class).single();
	}

	private long notifications(String type, UUID account) {
		return this.jdbc.sql("""
				SELECT count(*) FROM notification.notification WHERE type = :type
				  AND (CAST(:account AS UUID) IS NULL OR account_id = CAST(:account AS UUID))
				""").param("type", type).param("account", account).query(Long.class).single();
	}

	private long openTodos(String key, UUID account) {
		return this.jdbc.sql("""
				SELECT count(*) FROM notification.todo WHERE todo_key = :key AND account_id = :account AND closed_at IS NULL
				""").param("key", key).param("account", account).query(Long.class).single();
	}

	private String text(String sql) {
		return this.jdbc.sql(sql).query(String.class).single();
	}

	private RequestPostProcessor as(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

}
