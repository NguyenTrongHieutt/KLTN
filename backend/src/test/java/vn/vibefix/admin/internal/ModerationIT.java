package vn.vibefix.admin.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import vn.vibefix.identity.MentorApprovalStatus;
import vn.vibefix.identity.internal.AdministratorTestSupport;
import vn.vibefix.identity.internal.AdministratorTestSupport.Administrator;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.job.JobPosts;
import vn.vibefix.product.ProductDirectory;
import vn.vibefix.reputation.Reputation;
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.team.internal.TeamTestSupport;

/**
 * ADM-6 (FR-60, FR-14, FR-49, FR-63): a hidden Job Post leaves browse, lapses its invitations, and tells
 * its applicants; a hidden Product hides its Open Job Posts; a hidden Page is read by no one; a hidden
 * Rating leaves the summary. Each author is told the reason, and each hide is audited once.
 */
class ModerationIT extends IntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private ApplicationContext context;

	@Autowired
	private JobPosts posts;

	@Autowired
	private ProductDirectory products;

	@Autowired
	private Reputation reputation;

	private UUID lan;

	private UUID hung;

	private UUID product;

	@Test
	void aHiddenJobPostLapsesItsInvitationsAndTellsTheApplicantsAndTheAuthor() throws Exception {
		Administrator ha = AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		world();
		UUID post = openPost();
		UUID invitation = invite(post);
		apply(post, this.hung);
		this.mvc.perform(get("/api/v1/admin/moderation").param("kind", "JOB_POST").with(admin(ha)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].id").value(post.toString()))
			.andExpect(jsonPath("$[0].authorName").value("Nguyễn Thị Lan"));

		hide(ha, "JOB_POST", post, "Nội dung lừa đảo").andExpect(status().isOk())
			.andExpect(jsonPath("$.hiddenReason").value("Nội dung lừa đảo"));
		assertThat(this.posts.post(post).orElseThrow().hidden()).isTrue();
		await().atMost(Duration.ofSeconds(10)).until(() -> "LAPSED".equals(text("SELECT status FROM matching.invitation WHERE id = '"
				+ invitation + "'")));
		await().atMost(Duration.ofSeconds(10)).until(() -> notices("job.job_post_hidden", this.hung) == 1);
		assertThat(this.jdbc.sql("""
				SELECT params ->> 'reason' FROM notification.notification WHERE type = 'admin.content_hidden' AND account_id = :lan
				""").param("lan", this.lan).query(String.class).single()).isEqualTo("Nội dung lừa đảo");
		assertThat(count("SELECT count(*) FROM audit.audit_entry WHERE action = 'admin.content_hidden'")).isEqualTo(1);
		hide(ha, "JOB_POST", post, "Lần nữa").andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("ALREADY_HIDDEN"));
		this.mvc.perform(get("/api/v1/admin/moderation").param("kind", "JOB_POST").param("hidden", "true").with(admin(ha)))
			.andExpect(jsonPath("$[0].id").value(post.toString()));
		hide(ha, "UNKNOWN", post, "Thử").andExpect(status().isNotFound());
		awaitListeners();
	}

	@Test
	void aHiddenProductHidesItsOpenJobPosts() throws Exception {
		Administrator ha = AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		world();
		UUID post = openPost();
		hide(ha, "PRODUCT", this.product, "Sản phẩm vi phạm bản quyền").andExpect(status().isOk());
		assertThat(this.products.hidden(List.of(this.product))).containsExactly(this.product);
		await().atMost(Duration.ofSeconds(10))
			.until(() -> "Sản phẩm của tin tuyển này đã bị ẩn".equals(text("SELECT coalesce(hidden_reason, '') FROM job.job WHERE id = '"
					+ post + "'")));
		awaitListeners();
	}

	@Test
	void aHiddenPageIsReadByNoOneAndAHiddenRatingLeavesTheSummary() throws Exception {
		Administrator ha = AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		world();
		UUID job = activeJob();
		UUID page = page(job);
		this.mvc.perform(get("/api/v1/pages/" + page).with(as(this.hung, Role.MENTOR))).andExpect(status().isOk());
		hide(ha, "PAGE", page, "Chứa thông tin cá nhân").andExpect(status().isOk());
		this.mvc.perform(get("/api/v1/pages/" + page).with(as(this.hung, Role.MENTOR))).andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/boards/" + job + "/pages").with(as(this.hung, Role.MENTOR)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.id == '" + page + "')]").isEmpty());

		UUID rating = rating(job);
		assertThat(this.reputation.visibleSummary(this.hung).count()).isEqualTo(1);
		hide(ha, "RATING", rating, "Lời lẽ xúc phạm").andExpect(status().isOk());
		assertThat(this.reputation.visibleSummary(this.hung).count()).isZero();
		assertThat(notices("admin.content_hidden", this.lan)).isEqualTo(1);
		assertThat(notices("admin.content_hidden", this.hung)).isEqualTo(1);
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

	private UUID openPost() {
		UUID post = UUID.randomUUID();
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, budget, deadline, rules, selection_days, opened_at, lapses_at, version, created_at, updated_at)
				VALUES (:id, :product, :owner, 'OPEN', 'Sửa lỗi đặt bàn', 'Lỗi trùng giờ', 'Đúng', 'Khách', ARRAY['BUG_FIX'],
				    'UNDER_2M', DATE '2026-12-01', '{}'::jsonb, 14, :now, :lapses, 1, :now, :now)
				""")
			.param("id", post)
			.param("product", this.product)
			.param("owner", this.lan)
			.param("now", now)
			.param("lapses", Timestamp.from(this.clock.instant().plus(Duration.ofDays(14))))
			.update();
		return post;
	}

	private UUID invite(UUID post) {
		UUID id = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO matching.invitation (id, job_id, product_id, customer_account_id, mentor_account_id, status, invited_at)
				VALUES (:id, :job, :product, :lan, :mentor, 'OPEN', now())
				""").param("id", id).param("job", post).param("product", this.product).param("lan", this.lan)
			.param("mentor", UUID.randomUUID()).update();
		return id;
	}

	private void apply(UUID post, UUID mentor) {
		this.jdbc.sql("""
				INSERT INTO matching.application (id, job_id, product_id, customer_account_id, mentor_account_id, status,
				    price_vnd, timeline_days, approach, public_code_notice_version, submitted_at)
				VALUES (:id, :job, :product, :owner, :mentor, 'SUBMITTED', 800000, 7, 'Cách làm', '1.0', now())
				""").param("id", UUID.randomUUID()).param("job", post).param("product", this.product).param("owner", this.lan)
			.param("mentor", mentor).update();
	}

	private UUID activeJob() {
		UUID job = UUID.randomUUID();
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, budget, deadline, rules, selection_days, opened_at, lapses_at, mentor_account_id,
				    selected_application_id, selected_at, version, created_at, updated_at)
				VALUES (:id, :product, :owner, 'ACTIVE', 'Sửa sổ đặt bàn', 'Lỗi', 'Đúng', 'Khách', ARRAY['BUG_FIX'],
				    'UNDER_2M', DATE '2026-12-01', '{}'::jsonb, 14, :now, :now, :mentor, :application, :now, 6, :now, :now)
				""").param("id", job).param("product", this.product).param("owner", this.lan).param("mentor", this.hung)
			.param("application", UUID.randomUUID()).param("now", now).update();
		this.jdbc.sql("""
				INSERT INTO work.board (job_id, product_id, code, next_number, created_at) VALUES (:job, :product, 'SDB', 1, :now)
				""").param("job", job).param("product", this.product).param("now", now).update();
		return job;
	}

	private UUID page(UUID job) {
		UUID page = UUID.randomUUID();
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO work.page (id, job_id, title, version_no, created_by_account_id, created_at, updated_at)
				VALUES (:id, :job, 'Ghi chú', 1, :mentor, :now, :now)
				""").param("id", page).param("job", job).param("mentor", this.hung).param("now", now).update();
		this.jdbc.sql("""
				INSERT INTO work.page_version (page_id, version_no, title, body, author_account_id, created_at)
				VALUES (:id, 1, 'Ghi chú', 'Nội dung', :mentor, :now)
				""").param("id", page).param("mentor", this.hung).param("now", now).update();
		return page;
	}

	private UUID rating(UUID job) {
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO reputation.rating_window (job_id, product_id, customer_account_id, mentor_account_id, job_title,
				    opened_at, ends_at, pair_published_at)
				VALUES (:job, :product, :lan, :mentor, 'Sửa sổ đặt bàn', :now, :ends, :now)
				""").param("job", job).param("product", this.product).param("lan", this.lan).param("mentor", this.hung)
			.param("now", now).param("ends", Timestamp.from(this.clock.instant().plus(Duration.ofDays(14)))).update();
		UUID rating = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO reputation.rating (id, job_id, kind, rater_account_id, ratee_account_id, stars, comment, created_at)
				VALUES (:id, :job, 'CUSTOMER_TO_MENTOR', :lan, :mentor, 1, 'Tệ', :now)
				""").param("id", rating).param("job", job).param("lan", this.lan).param("mentor", this.hung).param("now", now)
			.update();
		return rating;
	}

	private ResultActions hide(Administrator administrator, String kind, UUID id, String reason) throws Exception {
		return this.mvc.perform(post("/api/v1/admin/moderation/" + kind + "/" + id + "/hide").with(admin(administrator))
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"reason\":\"" + reason + "\"}"));
	}

	private long notices(String type, UUID account) {
		return this.jdbc.sql("SELECT count(*) FROM notification.notification WHERE type = :type AND account_id = :account")
			.param("type", type)
			.param("account", account)
			.query(Long.class)
			.single();
	}

	private void awaitListeners() {
		await().atMost(Duration.ofSeconds(10))
			.until(() -> count("SELECT count(*) FROM platform.event_publication WHERE completion_date IS NULL") == 0);
	}

	private long count(String sql) {
		return this.jdbc.sql(sql).query(Long.class).single();
	}

	private String text(String sql) {
		return this.jdbc.sql(sql).query(String.class).single();
	}

	private RequestPostProcessor admin(Administrator administrator) {
		return IdentityTestSupport.signedIn(this.sessions, administrator.accountId(), Role.ADMINISTRATOR, this.clock.instant());
	}

	private RequestPostProcessor as(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

}
