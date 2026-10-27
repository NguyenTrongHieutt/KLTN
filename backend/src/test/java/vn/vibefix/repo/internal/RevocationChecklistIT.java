package vn.vibefix.repo.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.job.JobTransitioned;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.team.internal.TeamTestSupport;
import vn.vibefix.timers.internal.DeadlinePoller;

/**
 * Story 8.10 (FR-66, AD-7, UX-DR51): when a Job with External Access items ends, the Customer gets one
 * Revocation Checklist item per access, a to-do each, and a notice; each confirmation is audited with its
 * time and closes its to-do; reminders come after 1 and 7 days only while something is unticked; closure
 * never waits. The Customer reads the identifiers to remove, the Mentor only done or pending.
 */
class RevocationChecklistIT extends IntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private TransactionTemplate transactions;

	@Autowired
	private ApplicationEventPublisher events;

	@Autowired
	private DeadlinePoller poller;

	private UUID lan;

	private UUID hung;

	private UUID job;

	private UUID supabase;

	private UUID vercel;

	@BeforeEach
	void aClosingJobWithTwoExternalAccessItems() {
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		this.hung = TeamTestSupport.mentor(this.jdbc, "Đỗ Mạnh Hùng",
				java.util.List.of(TeamTestSupport.REACT), "React");
		this.supabase = UUID.randomUUID();
		this.vercel = UUID.randomUUID();
		this.job = job(true);
	}

	/** A Closing Job whose confirmed Agreement lists Supabase and Vercel, or nothing. */
	private UUID job(boolean withAccess) {
		UUID id = UUID.randomUUID();
		UUID product = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO product.product (id, customer_account_id, name, description, ai_tools, repository_github_id,
				    repository_full_name, repository_owner_type, repository_default_branch, public_notice_version,
				    copy_permission_version, detection_notice_version, acknowledged_at, created_at)
				VALUES (:id, :owner, 'Sổ đặt bàn Quán Mây', 'Đặt bàn.', ARRAY['LOVABLE'], :github, 'lan/so-dat-ban', 'User', 'main',
				    '1.0', '1.0', '1.0', now(), now())
				""").param("id", product).param("owner", this.lan).param("github", System.nanoTime()).update();
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, budget, deadline, rules, selection_days, opened_at, lapses_at, mentor_account_id,
				    selected_application_id, selected_at, version, created_at, updated_at, closing_at)
				VALUES (:id, :product, :owner, 'CLOSING', 'Sửa lỗi đặt bàn', 'Lỗi', 'Đúng', 'Khách', ARRAY['BUG_FIX'],
				    'UNDER_2M', DATE '2026-12-01', '{}'::jsonb, 14, :now, :now, :mentor, :application, :now, 8, :now, :now, :now)
				""").param("id", id).param("product", product).param("owner", this.lan).param("mentor", this.hung)
			.param("application", UUID.randomUUID()).param("now", now).update();
		String items = !withAccess ? "" : """
				{"id":"%s","system":"Supabase","purpose":"Xem bảng đặt bàn","environment":"NON_PRODUCTION","accessLevel":"READ"},
				{"id":"%s","system":"Vercel","purpose":"Xem bản thử","environment":"PRODUCTION","accessLevel":"WRITE"}
				""".formatted(this.supabase, this.vercel);
		String content = """
				{"scope":"Sửa lỗi","priceVnd":1500000,"codeJob":true,"assessment":false,"skills":[],"milestones":[],
				 "externalAccess":[%s]}
				""".formatted(items);
		this.jdbc.sql("""
				INSERT INTO job.agreement_version (job_id, version_no, status, content, author_account_id, created_at)
				VALUES (:job, 1, 'SHARED', CAST(:content AS JSONB), :mentor, :now)
				""").param("job", id).param("content", content).param("mentor", this.hung).param("now", now).update();
		this.jdbc.sql("""
				INSERT INTO job.confirmed_agreement (job_id, version_no, draft_version_no, content, crew_usernames,
				    freeze_committed, customer_account_id, approved_at, mentor_account_id, confirmed_at)
				VALUES (:job, 1, 1, CAST(:content AS JSONB), '{}', true, :owner, :now, :mentor, :now)
				""").param("job", id).param("content", content).param("owner", this.lan).param("mentor", this.hung)
			.param("now", now).update();
		this.jdbc.sql("UPDATE job.job SET agreement_version_no = 1 WHERE id = :id").param("id", id).update();
		if (!withAccess) {
			return id;
		}
		int position = 0;
		for (UUID access : new UUID[] { this.supabase, this.vercel }) {
			this.jdbc.sql("""
					INSERT INTO job.external_access (id, job_id, position, system, purpose, created_at)
					VALUES (:id, :job, :position, 'x', 'y', :now)
					""").param("id", access).param("job", id).param("position", position++).param("now", now).update();
		}
		this.jdbc.sql("""
				INSERT INTO job.external_access_identifier (access_id, account_id, job_id, identifier, updated_at)
				VALUES (:access, :mentor, :job, 'hung@supabase.test', :now)
				""").param("access", this.supabase).param("mentor", this.hung).param("job", id).param("now", now).update();
		return id;
	}

	@Test
	void theEndOpensOneItemPerAccessAndTheCustomerWorksThroughIt() throws Exception {
		end("J8");
		await().atMost(Duration.ofSeconds(15)).until(() -> items() == 2);
		end("J8");
		assertThat(openTodos()).isEqualTo(2);
		assertThat(notifications("repo.revocation_checklist_pending")).isOne();
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM timers.deadline WHERE kind = 'repo.revocation_checklist_reminder' AND subject_id = :job
				""").param("job", this.job).query(Long.class).single()).isEqualTo(2);

		this.mvc.perform(get("/api/v1/boards/" + this.job + "/revocation-checklist").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.items[0].system").value("Supabase"))
			.andExpect(jsonPath("$.items[0].identifiers[0].identifier").value("hung@supabase.test"))
			.andExpect(jsonPath("$.items[0].done").value(false));
		this.mvc.perform(get("/api/v1/boards/" + this.job + "/revocation-checklist").with(as(this.hung, Role.MENTOR)))
			.andExpect(jsonPath("$.audience").value("MENTOR"))
			.andExpect(jsonPath("$.items[0].identifiers").isEmpty());
		UUID other = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		this.mvc.perform(get("/api/v1/boards/" + this.job + "/revocation-checklist").with(as(other, Role.CUSTOMER)))
			.andExpect(status().isNotFound());

		confirm(this.supabase, "REMOVED").andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].done").value(true))
			.andExpect(jsonPath("$.items[0].outcome").value("REMOVED"))
			.andExpect(jsonPath("$.items[0].confirmedAt").exists());
		confirm(this.supabase, "ROTATED").andExpect(jsonPath("$.items[0].outcome").value("REMOVED"));
		confirm(this.vercel, "LATER").andExpect(status().isBadRequest());
		this.mvc.perform(post("/api/v1/boards/" + this.job + "/revocation-checklist/" + this.vercel).with(as(this.hung, Role.MENTOR))
			.with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"outcome\":\"REMOVED\"}"))
			.andExpect(status().isNotFound());
		assertThat(openTodos()).isOne();
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM audit.audit_entry WHERE action = 'repo.revocation_item_confirmed' AND job_id = :job
				""").param("job", this.job).query(Long.class).single()).isOne();

		this.clock.advance(Duration.ofDays(1).plusMinutes(1));
		this.poller.runDue();
		assertThat(this.jdbc.sql("""
				SELECT params ->> 'n' FROM notification.notification WHERE type = 'repo.revocation_checklist_reminder'
				    AND account_id = :id
				""").param("id", this.lan).query(String.class).list()).containsExactly("1");

		confirm(this.vercel, "ROTATED").andExpect(status().isOk());
		this.clock.advance(Duration.ofDays(7));
		this.poller.runDue();
		assertThat(notifications("repo.revocation_checklist_reminder")).as("nothing left unticked").isOne();
		assertThat(openTodos()).isZero();
	}

	@Test
	void aJobWithoutExternalAccessGetsNoChecklist() {
		this.job = job(false);
		end("J10");
		await().pollDelay(Duration.ofMillis(500)).atMost(Duration.ofSeconds(5)).until(() -> true);
		assertThat(items()).isZero();
		assertThat(notifications("repo.revocation_checklist_pending")).isZero();
	}

	private void end(String transition) {
		this.transactions.executeWithoutResult(status -> this.events.publishEvent(new JobTransitioned(UUID.randomUUID(),
				UUID.randomUUID(), this.job, transition, "USER", "ACTIVE", "J8".equals(transition) ? "CLOSING" : "CANCELLED", 9,
				1, Map.of())));
	}

	private ResultActions confirm(UUID access, String outcome) throws Exception {
		return this.mvc.perform(post("/api/v1/boards/" + this.job + "/revocation-checklist/" + access)
			.with(as(this.lan, Role.CUSTOMER)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"outcome\":\"" + outcome + "\"}"));
	}

	private long items() {
		return this.jdbc.sql("SELECT count(*) FROM repo.revocation_item WHERE job_id = :job").param("job", this.job)
			.query(Long.class).single();
	}

	private long openTodos() {
		return this.jdbc.sql("""
				SELECT count(*) FROM notification.todo WHERE todo_key = 'repo.revocation_item' AND account_id = :id
				    AND closed_at IS NULL
				""").param("id", this.lan).query(Long.class).single();
	}

	private long notifications(String type) {
		return this.jdbc.sql("SELECT count(*) FROM notification.notification WHERE type = :type AND account_id = :id")
			.param("type", type).param("id", this.lan).query(Long.class).single();
	}

	private RequestPostProcessor as(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

}
