package vn.vibefix.admin.internal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.identity.MentorApprovalStatus;
import vn.vibefix.identity.internal.AdministratorTestSupport;
import vn.vibefix.identity.internal.AdministratorTestSupport.Administrator;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.team.internal.TeamTestSupport;

/**
 * ADM-5 and ADM-8 (FR-63, FR-68, SG-28): Jobs filter by state, overdue, and frozen with their Milestones
 * and people; the Audit Log filters by Job (carried or named) and by user (acting or named).
 */
class OversightIT extends IntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private ApplicationContext context;

	@Autowired
	private AuditLog auditLog;

	@Autowired
	private TransactionTemplate transactions;

	private UUID lan;

	private UUID hung;

	private UUID product;

	@Test
	void jobsFilterByStateOverdueAndFrozenAndTheAuditLogByJobAndUser() throws Exception {
		Administrator ha = AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		world();
		UUID overdue = job("ACTIVE", false);
		milestone(overdue, "FUNDED", true);
		UUID frozen = job("ACTIVE", true);
		milestone(frozen, "FUNDED", false);
		UUID open = job("OPEN", false);

		this.mvc.perform(get("/api/v1/admin/jobs").with(admin(ha))).andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(3));
		this.mvc.perform(get("/api/v1/admin/jobs").param("flag", "OVERDUE").with(admin(ha)))
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].id").value(overdue.toString()))
			.andExpect(jsonPath("$.items[0].customerName").value("Nguyễn Thị Lan"))
			.andExpect(jsonPath("$.items[0].mentorName").value("Đỗ Mạnh Hùng"))
			.andExpect(jsonPath("$.items[0].milestones[0].number").value(1))
			.andExpect(jsonPath("$.items[0].milestones[0].overdueSince").isNotEmpty());
		this.mvc.perform(get("/api/v1/admin/jobs").param("flag", "FROZEN").with(admin(ha)))
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].id").value(frozen.toString()));
		this.mvc.perform(get("/api/v1/admin/jobs").param("state", "OPEN").with(admin(ha)))
			.andExpect(jsonPath("$.items[0].id").value(open.toString()));
		this.mvc.perform(get("/api/v1/admin/jobs").param("state", "NOPE").with(admin(ha))).andExpect(status().isBadRequest());
		this.mvc.perform(get("/api/v1/admin/jobs/" + frozen).with(admin(ha))).andExpect(status().isOk())
			.andExpect(jsonPath("$.frozen").value(true));

		this.transactions.executeWithoutResult(status -> {
			this.auditLog.record(AuditEntry.of(AuditActor.user(this.hung), "job.milestone_submitted",
					AuditObject.of("job.milestone", UUID.randomUUID())).forJob(overdue));
			this.auditLog.record(AuditEntry.of(AuditActor.user(ha.accountId()), "dispute.dispute_taken",
					AuditObject.of("job.job", frozen)));
			this.auditLog.record(AuditEntry.of(AuditActor.system(), "identity.account_locked",
					AuditObject.of("identity.account", this.lan)));
		});
		this.mvc.perform(get("/api/v1/admin/audit").param("jobId", overdue.toString()).with(admin(ha)))
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].action").value("job.milestone_submitted"))
			.andExpect(jsonPath("$.items[0].actorName").value("Đỗ Mạnh Hùng"));
		this.mvc.perform(get("/api/v1/admin/audit").param("jobId", frozen.toString()).with(admin(ha)))
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].actorName").value("Hà Phương"));
		this.mvc.perform(get("/api/v1/admin/audit").param("accountId", this.lan.toString()).with(admin(ha)))
			.andExpect(jsonPath("$.items[?(@.action == 'identity.account_locked')].system").value(true));
		this.mvc.perform(get("/api/v1/admin/audit").param("accountId", this.hung.toString()).with(admin(ha)))
			.andExpect(jsonPath("$.items[?(@.action == 'job.milestone_submitted')]").isNotEmpty());
	}

	private void world() {
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null, null);
		this.jdbc.sql("""
				INSERT INTO identity.customer_profile (account_id, kind, display_name, contact_email, updated_at)
				VALUES (:id, 'INDIVIDUAL', 'Nguyễn Thị Lan', 'lan@example.com', now())
				""").param("id", this.lan).update();
		this.hung = TeamTestSupport.mentor(this.jdbc, MentorApprovalStatus.APPROVED, "Đỗ Mạnh Hùng",
				List.of(TeamTestSupport.REACT), "React");
		this.product = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO product.product (id, customer_account_id, name, description, ai_tools, repository_github_id,
				    repository_full_name, repository_owner_type, repository_default_branch, public_notice_version,
				    copy_permission_version, detection_notice_version, acknowledged_at, created_at)
				VALUES (:id, :owner, 'Sổ đặt bàn', 'Đặt bàn.', ARRAY['LOVABLE'], :github, 'lan/so-dat-ban', 'User', 'main',
				    '1.0', '1.0', '1.0', now(), now())
				""").param("id", this.product).param("owner", this.lan)
			.param("github", ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE)).update();
	}

	private UUID job(String state, boolean frozen) {
		UUID job = UUID.randomUUID();
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, budget, deadline, rules, selection_days, opened_at, lapses_at, mentor_account_id,
				    selected_application_id, selected_at, version, created_at, updated_at, frozen_at)
				VALUES (:id, :product, :owner, :state, 'Đánh giá sổ đặt bàn', 'Lỗi', 'Đúng', 'Khách', ARRAY['ASSESSMENT'],
				    'UNDER_2M', DATE '2026-12-01', '{}'::jsonb, 14, :now, :lapses, :mentor, :application, :selected, 6, :now,
				    :now, :frozen)
				""")
			.param("id", job)
			.param("product", this.product)
			.param("owner", this.lan)
			.param("state", state)
			.param("mentor", "OPEN".equals(state) ? null : this.hung)
			.param("application", "OPEN".equals(state) ? null : UUID.randomUUID())
			.param("now", now)
			.param("lapses", Timestamp.from(this.clock.instant().plus(Duration.ofDays(14))))
			.param("selected", "OPEN".equals(state) ? null : now)
			.param("frozen", frozen ? now : null)
			.update();
		return job;
	}

	private void milestone(UUID job, String state, boolean overdue) {
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO job.milestone (id, job_id, position, title, price_vnd, due_on, revision_rounds, created_at, state,
				    funded_at, crew_terms_version_no, overdue_since)
				VALUES (:id, :job, 0, 'Sửa lỗi', 800000, DATE '2026-01-20', 2, :now, :state, :now, 1, :overdue)
				""")
			.param("id", UUID.randomUUID())
			.param("job", job)
			.param("state", state)
			.param("now", now)
			.param("overdue", overdue ? now : null)
			.update();
	}

	private RequestPostProcessor admin(Administrator administrator) {
		return IdentityTestSupport.signedIn(this.sessions, administrator.accountId(), Role.ADMINISTRATOR, this.clock.instant());
	}

}
