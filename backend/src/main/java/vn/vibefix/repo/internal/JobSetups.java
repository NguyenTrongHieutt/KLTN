package vn.vibefix.repo.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.github.PlatformRepositories.PlatformRepository;
import vn.vibefix.job.JobBusy;
import vn.vibefix.job.JobBusy.BusyJob;
import vn.vibefix.job.JobTransitioned;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.operations.ExternalOperations;
import vn.vibefix.operations.OperationCompleted;
import vn.vibefix.operations.OperationRequest;
import vn.vibefix.operations.OperationStatus;
import vn.vibefix.product.ProductSources.SourceCheck;
import vn.vibefix.scanning.ScanPurpose;
import vn.vibefix.scanning.ScanTarget;
import vn.vibefix.scanning.Scans;
import vn.vibefix.scanning.ShaRange;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.IdGenerator;
import vn.vibefix.shared.ProcessedEvents;

/**
 * J7's Setup stage (FR-18, AD-5, AD-11, AD-28): one {@code repo.job_setup} row per attempt. J6 of a
 * Code Job starts the first attempt: the row, the {@value #OPERATION} operation, and the busy marker
 * with purpose {@code JOB_SETUP}, in one transaction. Every resolution (done, blocked, failed) releases
 * the marker in its own transaction; a block also tells the Customer and the Mentor why, and the
 * Customer may start a new attempt. Each step is audited.
 */
@Component
class JobSetups {

	static final String OPERATION = "repo.job_setup";

	static final String START_BLOCKED = "repo.job_start_blocked";

	/** The step tracker's steps (UX-DR23): Setup's three, then the invitations and Verify (Story 7.3). */
	enum Step {

		CHECK, INTERMEDIARY, WORKING, INVITE, VERIFY

	}

	/** Why a Setup stopped on something the Customer can fix (FR-18). */
	enum Block {

		INTAKE_FINDING_OPEN, SOURCE_NOT_PUBLIC, NO_ADMIN_PERMISSION, SIGN_IN_AGAIN

	}

	/** One attempt, as its row holds it. */
	record Setup(UUID id, UUID jobId, UUID productId, String status, @Nullable Block block, @Nullable Integer openFindings,
			@Nullable UUID scanRunId, @Nullable String sourceFullName, @Nullable String defaultBranch,
			@Nullable String approvedSha, @Nullable Long intermediaryId, @Nullable String intermediaryName,
			@Nullable Long workingId, @Nullable String workingName, @Nullable Instant checkedAt,
			@Nullable Instant intermediaryAt, @Nullable Instant workingAt, Instant startedAt, @Nullable Instant finishedAt) {

		String operationKey() {
			return OPERATION + ":" + this.id;
		}

		/** The Working Repository once forked. */
		@Nullable PlatformRepository working() {
			return this.workingId == null || this.workingName == null ? null
					: new PlatformRepository(this.workingId, this.workingName.substring(this.workingName.indexOf('/') + 1),
							this.workingName);
		}

		/** The Intermediary Repository once created; its row keeps the full name. */
		@Nullable PlatformRepository intermediary() {
			return this.intermediaryId == null || this.intermediaryName == null ? null
					: new PlatformRepository(this.intermediaryId,
							this.intermediaryName.substring(this.intermediaryName.indexOf('/') + 1), this.intermediaryName);
		}

	}

	/** The plain reasons in {@value #START_BLOCKED}, which the Customer and the Mentor both read. */
	private static final Map<Block, String> REASONS = Map.of(Block.SOURCE_NOT_PUBLIC, "dự án gốc không còn công khai",
			Block.NO_ADMIN_PERMISSION, "tài khoản GitHub của khách hàng không còn quyền quản trị dự án gốc",
			Block.SIGN_IN_AGAIN, "khách hàng cần đăng nhập lại để vibefix kiểm tra dự án gốc");

	private static final String COLUMNS = """
			id, job_id, product_id, status, blocked_reason, open_findings, scan_run_id, source_full_name, default_branch,
			approved_sha, intermediary_github_id, intermediary_name, working_github_id, working_name, checked_at,
			intermediary_at, working_at, started_at, finished_at
			""";

	private final JdbcClient jdbc;

	private final JobBusy busy;

	private final ExternalOperations operations;

	private final Scans scans;

	private final Notifications notifications;

	private final AuditLog auditLog;

	private final ProcessedEvents processedEvents;

	private final IdGenerator ids;

	private final Clock clock;

	/** Looked up on use: the grants need the Crew and the directories, which need {@code operations}. */
	private final ObjectProvider<JobGrants> grants;

	private final ObjectProvider<PushScans> pushScans;

	JobSetups(JdbcClient jdbc, JobBusy busy, ExternalOperations operations, Scans scans, Notifications notifications,
			AuditLog auditLog, ProcessedEvents processedEvents, IdGenerator ids, Clock clock, ObjectProvider<JobGrants> grants,
			ObjectProvider<PushScans> pushScans) {
		this.grants = grants;
		this.pushScans = pushScans;
		this.jdbc = jdbc;
		this.busy = busy;
		this.operations = operations;
		this.scans = scans;
		this.notifications = notifications;
		this.auditLog = auditLog;
		this.processedEvents = processedEvents;
		this.ids = ids;
		this.clock = clock;
	}

	/** The repositories of a Job are named by its ID, so a retried step finds what an earlier one created. */
	static String intermediaryName(UUID jobId) {
		return key(jobId) + "-intermediary";
	}

	static String workingName(UUID jobId) {
		return key(jobId) + "-working";
	}

	private static String key(UUID jobId) {
		return "job-" + jobId.toString().replace("-", "");
	}

	/** J6 of a Code Job starts its first Setup, after J6 commits. */
	@ApplicationModuleListener
	void onJobTransitioned(JobTransitioned event) {
		started(event);
	}

	/** What the listener does, in the caller's transaction. Replay-safe. */
	@Transactional
	void started(JobTransitioned event) {
		if (!"J6".equals(event.transition()) || !"true".equals(event.facts().get("code"))
				|| !this.processedEvents.markProcessed("repo.JobSetups.onJobTransitioned", event.eventId())) {
			return;
		}
		BusyJob job = this.busy.job(event.jobId()).orElse(null);
		if (job == null || refusal(job, event.version()) != null) {
			// AD-28: a System request the state has moved past is an audited no-op.
			this.auditLog.record(AuditEntry.of(AuditActor.system(), "repo.setup_not_started", AuditObject.of("job.job",
					event.jobId())).forJob(event.jobId()));
			return;
		}
		start(job);
	}

	/**
	 * "Thử lại" by the Customer after a block: a new attempt on the Job as it is now.
	 * @throws ApiException {@code CONFLICT SETUP_NOT_BLOCKED} unless the latest attempt is blocked;
	 * {@code STALE STATE_CHANGED} once the Job left Starting; {@code JOB_BUSY}; {@code CONFLICT JOB_FROZEN}
	 */
	@Transactional
	Setup retry(BusyJob job) {
		Setup latest = latest(job.jobId()).orElse(null);
		if (latest == null || !"BLOCKED".equals(latest.status())) {
			throw new ApiException(ErrorCode.CONFLICT, RepoReason.SETUP_NOT_BLOCKED);
		}
		ApiException refused = refusal(job, job.version());
		if (refused != null) {
			throw refused;
		}
		return start(job);
	}

	private Setup start(BusyJob job) {
		UUID id = this.ids.newId();
		Instant now = this.clock.instant();
		this.jdbc.sql("""
				INSERT INTO repo.job_setup (id, job_id, product_id, status, started_at) VALUES (:id, :job, :product, 'RUNNING', :now)
				""").param("id", id).param("job", job.jobId()).param("product", job.productId()).param("now", Timestamp.from(now)).update();
		this.busy.reserve(job.jobId(), JobBusy.Purpose.JOB_SETUP, null, job.version(), OPERATION + ":" + id);
		this.operations.request(new OperationRequest(OPERATION, id, Map.of("setupId", id.toString()), null, null, 0));
		audit("repo.setup_started", id, job.jobId());
		return find(id).orElseThrow();
	}

	/** What would refuse a new attempt now, checked before anything is written. */
	private static @Nullable ApiException refusal(BusyJob job, long version) {
		if (!job.code() || !"STARTING".equals(job.state())) {
			return new ApiException(ErrorCode.STALE, RepoReason.JOB_NOT_STARTING);
		}
		if (job.busy().isPresent()) {
			return new ApiException(ErrorCode.JOB_BUSY);
		}
		if (job.frozen()) {
			return new ApiException(ErrorCode.CONFLICT, RepoReason.JOB_FROZEN);
		}
		if (job.version() != version) {
			return new ApiException(ErrorCode.STALE);
		}
		return null;
	}

	/** An Administrator retried a failed attempt (FR-64): it runs again under a new reservation. */
	@Transactional
	boolean resume(Setup setup) {
		BusyJob job = this.busy.job(setup.jobId()).orElse(null);
		if (job == null || refusal(job, job.version()) != null) {
			return false;
		}
		int resumed = this.jdbc.sql("UPDATE repo.job_setup SET status = 'RUNNING', finished_at = NULL WHERE id = :id AND status = 'FAILED'")
			.param("id", setup.id())
			.update();
		if (resumed == 0) {
			return false;
		}
		this.busy.reserve(job.jobId(), JobBusy.Purpose.JOB_SETUP, null, job.version(), setup.operationKey());
		audit("repo.setup_resumed", setup.id(), setup.jobId());
		return true;
	}

	/** Step 1: the J7 scan is requested once per attempt, in its own transaction. */
	@Transactional
	UUID requestScan(Setup setup, SourceCheck source) {
		UUID run = this.scans.requestScan(new ScanTarget(source.productId(), source.customerId(), source.productName(),
				source.repositoryFullName(), source.repositoryGithubId()), ShaRange.wholeHistory(), ScanPurpose.J7);
		this.jdbc.sql("UPDATE repo.job_setup SET scan_run_id = :run, source_full_name = :source WHERE id = :id")
			.param("run", run)
			.param("source", source.repositoryFullName())
			.param("id", setup.id())
			.update();
		return run;
	}

	/** A failed scan is asked for again on the next attempt. */
	@Transactional
	void forgetScan(Setup setup) {
		this.jdbc.sql("UPDATE repo.job_setup SET scan_run_id = NULL WHERE id = :id").param("id", setup.id()).update();
	}

	/** Step 1 done: the commit the J7 scan approved, which is all that is ever copied. */
	@Transactional
	void checked(Setup setup, String defaultBranch, String sha) {
		this.jdbc.sql("""
				UPDATE repo.job_setup SET default_branch = :branch, approved_sha = :sha, checked_at = :now WHERE id = :id
				""").param("branch", defaultBranch).param("sha", sha).param("now", Timestamp.from(this.clock.instant()))
			.param("id", setup.id()).update();
		audit("repo.setup_source_checked", setup.id(), setup.jobId());
	}

	@Transactional
	void intermediaryCreated(Setup setup, PlatformRepository repository) {
		this.jdbc.sql("UPDATE repo.job_setup SET intermediary_github_id = :github, intermediary_name = :name WHERE id = :id")
			.param("github", repository.id())
			.param("name", repository.fullName())
			.param("id", setup.id())
			.update();
	}

	/** Step 2 done: the Intermediary Repository holds the approved commit. */
	@Transactional
	void copied(Setup setup) {
		this.jdbc.sql("UPDATE repo.job_setup SET intermediary_at = :now WHERE id = :id")
			.param("now", Timestamp.from(this.clock.instant()))
			.param("id", setup.id())
			.update();
		audit("repo.intermediary_copied", setup.id(), setup.jobId());
	}

	@Transactional
	void workingCreated(Setup setup, PlatformRepository repository) {
		this.jdbc.sql("UPDATE repo.job_setup SET working_github_id = :github, working_name = :name WHERE id = :id")
			.param("github", repository.id())
			.param("name", repository.fullName())
			.param("id", setup.id())
			.update();
	}

	/**
	 * Step 3 done and verified: the resolving transaction (AD-5) releases the marker, and the Invitations
	 * stage starts in it (Story 7.3); J7 waits for Verify.
	 */
	@Transactional
	void done(Setup setup, PlatformRepository working) {
		Instant now = this.clock.instant();
		int updated = this.jdbc.sql("""
				UPDATE repo.job_setup SET status = 'DONE', working_at = :now, finished_at = :now WHERE id = :id AND status = 'RUNNING'
				""").param("now", Timestamp.from(now)).param("id", setup.id()).update();
		if (updated == 1) {
			this.busy.release(setup.jobId(), setup.operationKey());
			audit("repo.setup_done", setup.id(), setup.jobId());
			this.pushScans.getObject().recordCopy(setup, working);
			this.grants.getObject().grantAll(setup, working);
		}
	}

	/**
	 * Step 1 found something the Customer can fix: the Job stays in Starting with its funds held, the
	 * marker is released, and the Customer and the Mentor are told why.
	 */
	@Transactional
	void block(Setup setup, Block block, @Nullable Integer openFindings) {
		int updated = this.jdbc.sql("""
				UPDATE repo.job_setup SET status = 'BLOCKED', blocked_reason = :reason, open_findings = :findings, finished_at = :now
				WHERE id = :id AND status = 'RUNNING'
				""").param("reason", block.name()).param("findings", openFindings)
			.param("now", Timestamp.from(this.clock.instant())).param("id", setup.id()).update();
		if (updated == 0) {
			return;
		}
		this.busy.release(setup.jobId(), setup.operationKey());
		audit("repo.setup_blocked", setup.id(), setup.jobId());
		BusyJob job = this.busy.job(setup.jobId()).orElseThrow();
		List<Recipient> recipients = new ArrayList<>(List.of(new Recipient(job.customerId(), Audience.CUSTOMER)));
		if (job.mentorId() != null) {
			recipients.add(new Recipient(job.mentorId(), Audience.MENTOR));
		}
		String reason = block == Block.INTAKE_FINDING_OPEN ? "còn " + openFindings + " vấn đề bảo mật chưa xử lý"
				: REASONS.get(block);
		this.notifications.send(new NotificationRequest(START_BLOCKED, setup.id(), recipients,
				Map.of("job", job.title(), "reason", reason), "/cong-viec/" + job.jobId(), job.jobId(), null));
	}

	/** The operation ended without success: the attempt failed, and the marker is released. */
	@Transactional
	void fail(Setup setup) {
		int updated = this.jdbc.sql("""
				UPDATE repo.job_setup SET status = 'FAILED', finished_at = :now WHERE id = :id AND status = 'RUNNING'
				""").param("now", Timestamp.from(this.clock.instant())).param("id", setup.id()).update();
		if (updated == 1) {
			this.busy.release(setup.jobId(), setup.operationKey());
			audit("repo.setup_failed", setup.id(), setup.jobId());
		}
	}

	/** An attempt's operation ended without success; a block was resolved by the attempt itself. */
	@ApplicationModuleListener
	void on(OperationCompleted completed) {
		if (!OPERATION.equals(completed.type()) || completed.status() == OperationStatus.SUCCEEDED) {
			return;
		}
		UUID id = UUID.fromString(completed.key().substring(OPERATION.length() + 1));
		find(id).filter(setup -> "RUNNING".equals(setup.status())).ifPresent(this::fail);
	}

	Optional<Setup> find(UUID setupId) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM repo.job_setup WHERE id = :id")
			.param("id", setupId)
			.query(JobSetups::setup)
			.optional();
	}

	/** The Job's latest attempt. */
	Optional<Setup> latest(UUID jobId) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM repo.job_setup WHERE job_id = :job ORDER BY started_at DESC, id DESC LIMIT 1")
			.param("job", jobId)
			.query(JobSetups::setup)
			.optional();
	}

	private void audit(String action, UUID setupId, UUID jobId) {
		this.auditLog.record(AuditEntry.of(AuditActor.system(), action, AuditObject.of("repo.job_setup", setupId)).forJob(jobId));
	}

	private static Setup setup(ResultSet rs, int n) throws SQLException {
		String block = rs.getString("blocked_reason");
		return new Setup(rs.getObject("id", UUID.class), rs.getObject("job_id", UUID.class),
				rs.getObject("product_id", UUID.class), rs.getString("status"), block == null ? null : Block.valueOf(block),
				rs.getObject("open_findings", Integer.class), rs.getObject("scan_run_id", UUID.class),
				rs.getString("source_full_name"), rs.getString("default_branch"), rs.getString("approved_sha"),
				rs.getObject("intermediary_github_id", Long.class), rs.getString("intermediary_name"),
				rs.getObject("working_github_id", Long.class), rs.getString("working_name"), instant(rs, "checked_at"),
				instant(rs, "intermediary_at"), instant(rs, "working_at"), rs.getTimestamp("started_at").toInstant(),
				instant(rs, "finished_at"));
	}

	private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp value = rs.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}

}
