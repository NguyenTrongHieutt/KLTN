package vn.vibefix.authz.fixtures;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.authz.Canaries;
import vn.vibefix.authz.JobContent;
import vn.vibefix.authz.MatrixCaller;
import vn.vibefix.authz.MatrixFixture;
import vn.vibefix.authz.MatrixWorld;
import vn.vibefix.files.FileKind;
import vn.vibefix.files.FileUpload;
import vn.vibefix.files.StoredFiles;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.shared.AdminReadGrants;
import vn.vibefix.support.MutableClock;
import vn.vibefix.support.TestFiles;
import vn.vibefix.team.internal.TeamTestSupport;

/**
 * Products and their Jobs (FR-7, FR-8, FR-9, AD-20): a registered Product ({@code {productId}}) of
 * {@link MatrixCaller#CUSTOMER}, the Customer of every fixture Job, with one open credential Finding of its intake
 * scan ({@code {findingId}}) that the Customer resolves, one Draft Job ({@code {jobId}}) holding a screenshot
 * ({@code {screenshotId}}), and one Open Job Post ({@code {postId}}) with the open bid ({@code {applicationId}}) of
 * {@link MatrixCaller#BIDDER}, a Mentor with a profile, whom the Customer invited before the bid. The bid carries proof
 * (Story 21.2): a stored file ({@code file=proof}) captioned with the {@code BID_PROOF_CAPTION} canary and the link
 * {@code BID_PROOF_LINK}, which only the Bidder and the Customer read; the Bidder also has a proof upload no bid took
 * ({@code file=proof-upload}), theirs alone. The Customer's profile ({@code {customerId}}) holds the
 * {@code CONTACT_EMAIL} canary as its contact email. Only the Customer and Administrators read the Product; only the Customer reads the
 * Draft and the bids, and any user with GitHub linked reads the Job Post. An Active Job of the same Product carries
 * the Crew and its content ({@link CrewJobWorld}), and an Agreement Job of the same Product recruits its Crew, with the
 * pending Crew Application of {@link MatrixCaller#CREW_APPLICANT} ({@link RecruitingJobWorld}, Story 21.4).
 */
public class ProductFixture implements MatrixFixture {

	private final JdbcClient jdbc;

	private final SessionRepository<? extends Session> sessions;

	private final CrewJobWorld crewJob;

	private final RecruitingJobWorld recruitingJob;

	private final StoredFiles files;

	private final PlatformTransactionManager transactionManager;

	private UUID owner;

	private UUID bidder;

	public ProductFixture(JdbcClient jdbc, SessionRepository<? extends Session> sessions, List<AdminReadGrants> grants,
			StoredFiles files, PlatformTransactionManager transactionManager, ApplicationContext context) {
		this.jdbc = jdbc;
		this.sessions = sessions;
		this.crewJob = new CrewJobWorld(jdbc, sessions, grants, files, transactionManager, context);
		this.recruitingJob = new RecruitingJobWorld(jdbc, sessions, files, transactionManager);
		this.files = files;
		this.transactionManager = transactionManager;
	}

	@Override
	public void seed(MatrixWorld world) {
		this.owner = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
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
		// The Customer's contact (FR-3, Story 19.3): the crew Job's Mentor and Crew meet it, so it is the canary.
		JobContent.customerProfile(this.jdbc, this.owner, "Chủ sản phẩm ma trận",
				Canaries.load().get("CONTACT_EMAIL").value());
		world.put("customerId", this.owner);
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
			.param("lapses", Timestamp.from(MutableClock.DEFAULT_INSTANT.plus(Duration.ofDays(14))))
			.update();
		world.put("postId", post);
		// A row's job=post binds {jobId} to the post (MatrixRow.Binding.POST_JOB), where {applicationId} lives.
		world.put("postJobId", post);
		// A Mentor profile, so a bid reaches the real bid guards (FR-13).
		this.bidder = TeamTestSupport.mentor(this.jdbc, "Người ứng tuyển ma trận", List.of(TeamTestSupport.REACT),
				"React");
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
			.param("mentor", this.bidder)
			.param("now", now)
			.update();
		world.put("applicationId", application);
		// The bid's proof (Story 21.2): a stored file and a link, each captioned; and an upload no bid took yet.
		Canaries canaries = Canaries.load();
		UUID proofFile = proofFile(this.bidder, "bang-chung.pdf");
		insertProof(application, post, product, 1, "FILE", proofFile, null, canaries.get("BID_PROOF_CAPTION").value(), now);
		insertProof(application, post, product, 2, "LINK", null, canaries.get("BID_PROOF_LINK").value(), "Liên kết", now);
		world.put("proofFileId", proofFile);
		UUID upload = proofFile(this.bidder, "cho-gui.pdf");
		insertProof(null, post, product, null, "FILE", upload, null, null, now);
		world.put("proofUploadId", upload);
		// The Customer invited the Bidder a day before the bid, which answered it (FR-14): the invitation is theirs.
		this.jdbc.sql("""
				INSERT INTO matching.invitation (id, job_id, product_id, customer_account_id, mentor_account_id, status,
				    invited_at, ended_at)
				VALUES (:id, :job, :product, :owner, :mentor, 'APPLIED', :invited, :now)
				""")
			.param("id", UUID.randomUUID())
			.param("job", post)
			.param("product", product)
			.param("owner", this.owner)
			.param("mentor", this.bidder)
			.param("invited", Timestamp.from(MutableClock.DEFAULT_INSTANT.minus(Duration.ofDays(1))))
			.param("now", now)
			.update();
		// {crewRowId}, {milestoneId}, {paymentOrderId} and {accessId} name the crew Job's own objects (CrewJobWorld),
		// whose rows bind {jobId} to it: the Draft has none of them.
		this.crewJob.seed(world, this.owner, product);
		// The recruiting Job (Story 21.4): the same Customer and Mentor, recruiting, with the Crew applicant's application.
		this.recruitingJob.seed(world, this.owner, product, this.crewJob.mentor());
	}

	/** A proof file of the account, stored through {@code files} as an upload of {@code matching.bid_proof}. */
	private UUID proofFile(UUID account, String fileName) {
		var prepared = this.files.prepare(new FileUpload("matching.bid_proof", account, fileName, TestFiles.pdf(),
				Set.of(FileKind.PDF)));
		return Objects.requireNonNull(new TransactionTemplate(this.transactionManager)
			.execute(status -> this.files.store(prepared).id()));
	}

	private void insertProof(@Nullable UUID application, UUID post, UUID product, @Nullable Integer position, String kind,
			@Nullable UUID file, @Nullable String url, @Nullable String caption, Timestamp now) {
		this.jdbc.sql("""
				INSERT INTO matching.bid_proof (id, application_id, job_id, product_id, bidder_account_id, position, kind,
				    file_id, file_name, size_bytes, url, caption, added_at)
				VALUES (:id, :application, :job, :product, :bidder, :position, :kind, :file,
				    CASE WHEN :kind = 'FILE' THEN 'bang-chung.pdf' END, CASE WHEN :kind = 'FILE' THEN 100 END, :url, :caption, :now)
				""")
			.param("id", UUID.randomUUID())
			.param("application", application)
			.param("job", post)
			.param("product", product)
			.param("bidder", this.bidder)
			.param("position", position)
			.param("kind", kind)
			.param("file", file)
			.param("url", url)
			.param("caption", caption)
			.param("now", now)
			.update();
	}

	@Override
	public Optional<RequestPostProcessor> realize(MatrixCaller caller, Map<String, String> state, MatrixWorld world) {
		return switch (caller) {
			case CUSTOMER -> Optional.of(IdentityTestSupport.signedIn(this.sessions, this.owner, Role.CUSTOMER,
					MutableClock.DEFAULT_INSTANT));
			case BIDDER -> Optional.of(IdentityTestSupport.signedIn(this.sessions, this.bidder, Role.MENTOR,
					MutableClock.DEFAULT_INSTANT));
			case CREW_APPLICANT -> this.recruitingJob.realize(caller);
			default -> this.crewJob.realize(caller);
		};
	}

}
