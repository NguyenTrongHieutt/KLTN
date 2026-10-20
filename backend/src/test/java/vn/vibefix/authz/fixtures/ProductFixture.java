package vn.vibefix.authz.fixtures;

import java.sql.Timestamp;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import vn.vibefix.authz.MatrixCaller;
import vn.vibefix.authz.MatrixFixture;
import vn.vibefix.authz.MatrixWorld;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.shared.Role;
import vn.vibefix.support.MutableClock;

/**
 * Products and their Jobs (FR-7, FR-8, FR-9, AD-20): a Customer's registered Product
 * ({@code {productId}}), whose Customer the matrix calls {@link MatrixCaller#OWNER}, with one open
 * credential Finding of its intake scan ({@code {findingId}}) that the owner resolves, and one Draft
 * Job ({@code {jobId}}) holding a screenshot ({@code {screenshotId}}), and one Open Job Post
 * ({@code {postId}}) with another Mentor's Application ({@code {applicationId}}). Only the owner and
 * Administrators read the Product; only the owner reads the Draft and the Applications, and approved
 * Mentors read the Job Post. An Active Job of the same Product carries the Crew and its Crew Chat
 * ({@link CrewJobWorld}).
 */
public class ProductFixture implements MatrixFixture {

	private final JdbcClient jdbc;

	private final SessionRepository<? extends Session> sessions;

	private final CrewJobWorld crewJob;

	private UUID owner;

	public ProductFixture(JdbcClient jdbc, SessionRepository<? extends Session> sessions) {
		this.jdbc = jdbc;
		this.sessions = sessions;
		this.crewJob = new CrewJobWorld(jdbc, sessions);
	}

	@Override
	public void seed(MatrixWorld world) {
		this.owner = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null, null);
		UUID product = UUID.randomUUID();
		Timestamp now = Timestamp.from(MutableClock.DEFAULT_INSTANT);
		this.jdbc.sql("""
				INSERT INTO product.product (id, customer_account_id, name, description, ai_tools, repository_github_id,
				    repository_full_name, repository_owner_type, repository_default_branch, public_notice_version,
				    copy_permission_version, detection_notice_version, acknowledged_at, created_at)
				VALUES (:id, :owner, 'Sản phẩm ma trận', 'Mô tả', ARRAY['LOVABLE'], :repository, 'matrix/product', 'User',
				    'main', '1.0', '1.0', '1.0', :now, :now)
				""")
			.param("id", product)
			.param("owner", this.owner)
			.param("repository", ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE))
			.param("now", now)
			.update();
		world.put("productId", product);
		// ADM-6 hides this Product (FR-60).
		world.put("kind", "PRODUCT");
		world.put("contentId", product);
		UUID run = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO scanning.scan_run (id, product_id, customer_account_id, product_name, repository_full_name,
				    repository_github_id, purpose, status, rules_version, found_count, new_findings, requested_at,
				    finished_at)
				VALUES (:id, :product, :owner, 'Sản phẩm ma trận', 'matrix/product', 1, 'INTAKE', 'SUCCEEDED', 2, 1, 1,
				    :now, :now)
				""").param("id", run).param("product", product).param("owner", this.owner).param("now", now).update();
		UUID finding = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO scanning.finding (id, product_id, scan_run_id, category, rule_id, rule_type, file_path,
				    commit_sha, committed_at, masked_preview, fingerprint, status, created_at)
				VALUES (:id, :product, :run, 'CREDENTIAL', 'stripe-secret-key', 'Khóa bí mật Stripe', 'server/pay.js',
				    repeat('a', 40), :now, 'sk_l••••••••Q7mZ', repeat('b', 64), 'OPEN', :now)
				""").param("id", finding).param("product", product).param("run", run).param("now", now).update();
		this.jdbc.sql("INSERT INTO scanning.finding_resolver (finding_id, account_id) VALUES (:f, :a)")
			.param("f", finding)
			.param("a", this.owner)
			.update();
		world.put("findingId", finding);
		// ADM-7 retries a failed operation (FR-64); none exists in the world, so a random ID.
		world.put("operationId", UUID.randomUUID());
		UUID job = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, job_types, created_at, updated_at)
				VALUES (:id, :product, :owner, 'DRAFT', 'Việc ma trận', ARRAY['ASSESSMENT'], :now, :now)
				""").param("id", job).param("product", product).param("owner", this.owner).param("now", now).update();
		world.put("jobId", job);
		UUID screenshot = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO job.job_screenshot (job_id, file_id, file_name, size_bytes, added_at)
				VALUES (:job, :file, 'man-hinh.png', 100, :now)
				""").param("job", job).param("file", screenshot).param("now", now).update();
		world.put("screenshotId", screenshot);
		UUID post = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, budget, deadline, rules, selection_days, opened_at, lapses_at, version, created_at, updated_at)
				VALUES (:id, :product, :owner, 'OPEN', 'Tin ma trận', 'Lỗi', 'Đúng', 'Khách', ARRAY['BUG_FIX'], 'UNDER_2M',
				    DATE '2026-12-01', '{}'::jsonb, 14, :now, :lapses, 1, :now, :now)
				""")
			.param("id", post)
			.param("product", product)
			.param("owner", this.owner)
			.param("now", now)
			.param("lapses", Timestamp.from(MutableClock.DEFAULT_INSTANT.plus(java.time.Duration.ofDays(14))))
			.update();
		world.put("postId", post);
		UUID applicant = IdentityTestSupport.insertAccount(this.jdbc, Role.MENTOR, null, null);
		UUID application = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO matching.application (id, job_id, product_id, customer_account_id, mentor_account_id, status,
				    price_vnd, timeline_days, approach, public_code_notice_version, submitted_at)
				VALUES (:id, :job, :product, :owner, :mentor, 'SUBMITTED', 800000, 7, 'Cách làm', '1.0', :now)
				""")
			.param("id", application)
			.param("job", post)
			.param("product", product)
			.param("owner", this.owner)
			.param("mentor", applicant)
			.param("now", now)
			.update();
		world.put("applicationId", application);
		// No Crew row exists on the fixture's Draft: every Crew command on it finds nothing (AD-41).
		world.put("crewRowId", UUID.randomUUID());
		world.put("milestoneId", UUID.randomUUID());
		world.put("paymentOrderId", UUID.randomUUID());
		this.crewJob.seed(world, this.owner, product);
	}

	@Override
	public Set<String> stateKeys() {
		return Set.of();
	}

	@Override
	public Optional<RequestPostProcessor> realize(MatrixCaller caller, Map<String, String> state, MatrixWorld world) {
		if (caller != MatrixCaller.OWNER) {
			return this.crewJob.realize(caller);
		}
		return Optional.of(IdentityTestSupport.signedIn(this.sessions, this.owner, Role.CUSTOMER, MutableClock.DEFAULT_INSTANT));
	}

}
