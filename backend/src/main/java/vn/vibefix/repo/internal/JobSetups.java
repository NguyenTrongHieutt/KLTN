package vn.vibefix.repo.internal;

import java.nio.charset.StandardCharsets;
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
import org.springframework.beans.factory.annotation.Value;
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
import vn.vibefix.job.JobRules;
import vn.vibefix.job.JobTransitioned;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.notification.TodoRequest;
import vn.vibefix.notification.Todos;
import vn.vibefix.operations.ExternalOperations;
import vn.vibefix.operations.OperationCompleted;
import vn.vibefix.operations.OperationRequest;
import vn.vibefix.operations.OperationStatus;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.IdGenerator;
import vn.vibefix.shared.ProcessedEvents;

/**
 * J7's Setup stage (FR-18, AD-5, AD-11, AD-28, R4, Story 23.6b): one {@code repo.job_setup} row per attempt, in two
 * stages.
 * <ol>
 * <li>Waiting for the Customer, with no busy marker: J6 of a Code Job starts the first attempt as {@code CONFIRMING}
 * and requests its {@value #OPERATION} operation, keyed by the attempt, which checks the Source App and
 * {@code vibefix.yml} again, reads the default branch's head, opens {@value #CONFIRM_TODO} for the Customer and tells
 * them with {@value #CONFIRMATION_DUE}. The marker is not held meanwhile: the Customer may take days.</li>
 * <li>The copy, under the marker: the Customer's self-confirmation of the current head (N-32), recorded in
 * {@code repo.source_confirmation} and audited, makes the attempt {@code RUNNING}, reserves the marker with purpose
 * {@code JOB_SETUP}, and requests the copy stage, an operation of the same type keyed by the confirmation.</li>
 * </ol>
 * Every resolution (done, blocked, failed) releases the marker in its own transaction; a block also tells the
 * Customer and the Mentor why, and the Customer may start a new attempt. A head that moved after the confirmation
 * (the exchange's refusal {@code sha}) sends the attempt back to waiting, with the new head; the earlier confirmation
 * stays recorded. Each step is audited.
 */
@Component
class JobSetups {

	static final String OPERATION = "repo.job_setup";

	static final String START_BLOCKED = "repo.job_start_blocked";

	/** The Customer's to-do while the Setup waits for their self-confirmation (UX-DR118). */
	static final String CONFIRM_TODO = "repo.confirm_source_clean";

	/** The Customer is asked to self-confirm the head (UX-DR118). */
	static final String CONFIRMATION_DUE = "repo.self_confirmation_due";

	/** The exchange's refusal of a copy whose head moved after the confirmation (AD-44), which waits for a new one. */
	static final String HEAD_MOVED = "sha";

	/**
	 * The step tracker's steps (UX-DR114), in EXPERIENCE.md's order: the Source checked, the Customer's
	 * self-confirmation, the private repositories created, the copy on the Customer's GitHub Actions, then the
	 * invitations and Verify (Story 7.3).
	 */
	enum Step {

		CHECK, CONFIRM, REPOSITORIES, COPY, INVITE, VERIFY

	}

	/**
	 * Why a Setup stopped (FR-18): {@code product}'s Source checks, through the Source App since Story 23.5, and the copy
	 * run's own (Story 23.6b): the exchange refused it, it kept failing, or the Customer's Actions minutes ran out.
	 */
	enum Block {

		SOURCE_APP_MISSING, SOURCE_APP_WRONG_REPOSITORY, WORKFLOW_MISSING, WORKFLOW_MISMATCH, NO_ADMIN_PERMISSION,
		EXCHANGE_REFUSED, RUN_FAILED, ACTIONS_QUOTA

	}

	/**
	 * One attempt, as its row holds it.
	 * @param headSha the default branch's head the Customer is asked to confirm, as last read
	 * @param approvedSha the head the Customer confirmed, which the copy copies
	 * @param confirmationId the confirmation the copy runs for, which keys its operation and its marker
	 * @param intermediaryAt when the Intermediary Repository was created and read back for this copy
	 * @param workingAt when the Working Repository was
	 * @param correlationId the exchange correlation of the copy run dispatched last
	 * @param runStatus that run's {@code QUEUED}, {@code IN_PROGRESS} or {@code COMPLETED}, as last read
	 * @param runConclusion its conclusion, or {@code timed_out} when vibefix stopped waiting for it
	 * @param exchangeReason the exchange's refusal: of a block {@code EXCHANGE_REFUSED}, or {@code sha} while waiting
	 * @param copiedAt when both repositories read back at {@code approvedSha}
	 */
	record Setup(UUID id, UUID jobId, UUID productId, String status, @Nullable Block block,
			@Nullable String sourceFullName, @Nullable String defaultBranch, @Nullable String headSha,
			@Nullable Instant headReadAt, @Nullable String approvedSha, @Nullable UUID confirmationId,
			@Nullable Instant confirmedAt, @Nullable Long intermediaryId, @Nullable String intermediaryName,
			@Nullable Long workingId, @Nullable String workingName, @Nullable Instant checkedAt,
			@Nullable Instant intermediaryAt, @Nullable Instant workingAt, @Nullable UUID correlationId,
			@Nullable Instant dispatchedAt, @Nullable String runStatus, @Nullable String runConclusion,
			@Nullable String exchangeReason, @Nullable Instant copiedAt, Instant startedAt, @Nullable Instant finishedAt) {

		/** The marker's key: the copy stage's operation, keyed by the confirmation; the waiting stage's before one. */
		String operationKey() {
			return OPERATION + ":" + (this.confirmationId == null ? this.id : this.confirmationId);
		}

		/** The waiting stage's operation, keyed by the attempt. */
		String waitKey() {
			return OPERATION + ":" + this.id;
		}

		/** Whether the last run dispatched is over without a copy: a failure, or vibefix stopped waiting for it. */
		boolean runSpent() {
			return this.runConclusion != null && !"success".equals(this.runConclusion);
		}

		/** The Working Repository once created: a second private copy, not a fork (D5). */
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

	/**
	 * The plain reasons in {@value #START_BLOCKED}, which the Customer and the Mentor both read (EXPERIENCE.md's reason
	 * inventory, as fragments after "Chưa thể bắt đầu {job}:").
	 */
	static final Map<Block, String> REASONS = Map.ofEntries(
			Map.entry(Block.SOURCE_APP_MISSING, "ứng dụng vibefix chưa được cài trên dự án gốc"),
			Map.entry(Block.SOURCE_APP_WRONG_REPOSITORY, "ứng dụng vibefix phải được cài cho đúng một kho là dự án gốc"),
			Map.entry(Block.WORKFLOW_MISSING, "dự án gốc chưa có file workflow vibefix.yml"),
			Map.entry(Block.WORKFLOW_MISMATCH, "file workflow vibefix.yml trong dự án gốc khác bản vibefix công bố"),
			Map.entry(Block.NO_ADMIN_PERMISSION, "tài khoản GitHub của khách hàng không còn quyền quản trị dự án gốc"),
			Map.entry(Block.EXCHANGE_REFUSED,
					"GitHub chưa xác nhận được lần chạy sao chép trên GitHub Actions; vibefix đã báo quản trị viên"),
			Map.entry(Block.RUN_FAILED, "lần sao chép dự án gốc trên GitHub Actions chưa xong; vibefix đã báo quản trị viên"),
			Map.entry(Block.ACTIONS_QUOTA,
					"GitHub Actions của dự án gốc đã hết số phút trong tháng, nên việc sao chép chưa chạy được"));

	/** The exchange's refusals the Customer fixes themselves, each with its own plain reason (EXPERIENCE.md). */
	static final Map<String, String> EXCHANGE_REASONS = Map.of("blob",
			"file workflow vibefix.yml trong dự án gốc khác bản vibefix công bố, nên GitHub Actions không được chạy việc"
					+ " sao chép");

	/** N-32's version, as the property and a self-confirmation name it: major.minor, such as {@code 1.0}. */
	static final String NOTICE_VERSION_PATTERN = "[0-9]{1,3}\\.[0-9]{1,3}";

	private static final java.util.regex.Pattern NOTICE_VERSION = java.util.regex.Pattern.compile(NOTICE_VERSION_PATTERN);

	private static final String COLUMNS = """
			id, job_id, product_id, status, blocked_reason, source_full_name, default_branch, head_sha, head_read_at,
			approved_sha, confirmation_id, confirmed_at, intermediary_github_id, intermediary_name, working_github_id,
			working_name, checked_at, intermediary_at, working_at, correlation_id, dispatched_at, run_status, run_conclusion,
			exchange_reason, copied_at, started_at, finished_at
			""";

	private final JdbcClient jdbc;

	private final JobBusy busy;

	private final ExternalOperations operations;

	private final Notifications notifications;

	private final Todos todos;

	private final AuditLog auditLog;

	private final ProcessedEvents processedEvents;

	private final IdGenerator ids;

	private final Clock clock;

	private final JobRules rules;

	private final String noticeVersion;

	/** Looked up on use: the grants need the Crew and the directories, which need {@code operations}. */
	private final ObjectProvider<JobGrants> grants;

	JobSetups(JdbcClient jdbc, JobBusy busy, ExternalOperations operations, Notifications notifications, Todos todos,
			AuditLog auditLog, ProcessedEvents processedEvents, IdGenerator ids, Clock clock, JobRules rules,
			@Value("${vibefix.repo.self-confirmation-notice-version}") String noticeVersion,
			ObjectProvider<JobGrants> grants) {
		this.grants = grants;
		this.jdbc = jdbc;
		this.busy = busy;
		this.operations = operations;
		this.notifications = notifications;
		this.todos = todos;
		this.auditLog = auditLog;
		this.processedEvents = processedEvents;
		this.ids = ids;
		this.clock = clock;
		this.rules = rules;
		if (noticeVersion == null || !NOTICE_VERSION.matcher(noticeVersion).matches()) {
			throw new IllegalStateException(
					"vibefix.repo.self-confirmation-notice-version is a version such as 1.0, not '" + noticeVersion + "'");
		}
		this.noticeVersion = noticeVersion;
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

	/** N-32's current version, which a self-confirmation must name ({@code vibefix.repo.self-confirmation-notice-version}). */
	String noticeVersion() {
		return this.noticeVersion;
	}

	/** J6 of a Code Job starts its first Setup; a Job that leaves Starting ends a Setup still waiting for the Customer. */
	@ApplicationModuleListener
	void onJobTransitioned(JobTransitioned event) {
		transitioned(event);
	}

	/** What the listener does, in the caller's transaction. Replay-safe. */
	@Transactional
	void transitioned(JobTransitioned event) {
		boolean started = "J6".equals(event.transition()) && "true".equals(event.facts().get("code"));
		boolean left = "STARTING".equals(event.from()) && !"STARTING".equals(event.to());
		if ((!started && !left)
				|| !this.processedEvents.markProcessed("repo.JobSetups.onJobTransitioned", event.eventId())) {
			return;
		}
		if (left) {
			leftStarting(event.jobId());
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
	 * The Job left Starting (J10, or J7 after Verify): an attempt still waiting for the Customer is {@code ENDED}, which
	 * is no failure, and its to-do closes, since nothing can be confirmed any more. The row is updated first, taking its
	 * lock, so a concurrent {@link #checked} either finds it no longer waiting or has opened the to-do this closes.
	 */
	private void leftStarting(UUID jobId) {
		Setup setup = latest(jobId).orElse(null);
		if (setup == null) {
			return;
		}
		int ended = this.jdbc.sql("""
				UPDATE repo.job_setup SET status = 'ENDED', exchange_reason = NULL, finished_at = :now
				WHERE id = :id AND status = 'CONFIRMING'
				""").param("now", Timestamp.from(this.clock.instant())).param("id", setup.id()).update();
		this.todos.closeAll(CONFIRM_TODO, setup.id());
		if (ended == 1) {
			audit("repo.setup_ended", setup.id(), setup.jobId());
		}
	}

	/**
	 * "Thử lại" by the Customer after a block: a new attempt on the Job as it is now, from the waiting stage.
	 * @throws ApiException {@code GUARD_FAILED SELF_CONFIRMATION_MISSING} while the latest attempt waits for the
	 * Customer's self-confirmation; {@code CONFLICT SETUP_NOT_BLOCKED} unless it is blocked; {@code STALE STATE_CHANGED}
	 * once the Job left Starting; {@code JOB_BUSY}; {@code CONFLICT JOB_FROZEN}
	 */
	@Transactional
	Setup retry(BusyJob job) {
		Setup latest = latest(job.jobId()).orElse(null);
		if (latest != null && "CONFIRMING".equals(latest.status())) {
			throw new ApiException(ErrorCode.GUARD_FAILED, RepoReason.SELF_CONFIRMATION_MISSING);
		}
		if (latest == null || !"BLOCKED".equals(latest.status())) {
			throw new ApiException(ErrorCode.CONFLICT, RepoReason.SETUP_NOT_BLOCKED);
		}
		ApiException refused = refusal(job, job.version());
		if (refused != null) {
			throw refused;
		}
		return start(job);
	}

	/** A new attempt waiting for the Customer, with no marker, and the operation that checks and reads the head. */
	private Setup start(BusyJob job) {
		UUID id = this.ids.newId();
		Instant now = this.clock.instant();
		this.jdbc.sql("""
				INSERT INTO repo.job_setup (id, job_id, product_id, status, started_at) VALUES (:id, :job, :product, 'CONFIRMING', :now)
				""").param("id", id).param("job", job.jobId()).param("product", job.productId()).param("now", Timestamp.from(now)).update();
		this.operations.request(new OperationRequest(OPERATION, id, Map.of("setupId", id.toString()), null, null, 0));
		audit("repo.setup_started", id, job.jobId());
		return find(id).orElseThrow();
	}

	/** What would refuse a confirmation now: the Job no longer Starting, busy or frozen. */
	static @Nullable ApiException refusal(BusyJob job) {
		return refusal(job, job.version());
	}

	/** What would refuse a new attempt, a confirmation or a resume now, checked before anything is written. */
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

	/**
	 * An Administrator retried a failed attempt (FR-64). One that failed copying, or whose copy runs kept failing
	 * ({@code BLOCKED RUN_FAILED}), runs again under a new reservation of its confirmation's marker; one that failed
	 * before any confirmation waits for the Customer again from its first step, with no marker.
	 */
	@Transactional
	boolean resume(Setup setup) {
		BusyJob job = this.busy.job(setup.jobId()).orElse(null);
		if (job == null || refusal(job, job.version()) != null) {
			return false;
		}
		boolean confirmed = setup.confirmationId() != null;
		int resumed = this.jdbc.sql(confirmed ? """
				UPDATE repo.job_setup SET status = 'RUNNING', blocked_reason = NULL, exchange_reason = NULL, finished_at = NULL
				WHERE id = :id AND (status = 'FAILED' OR (status = 'BLOCKED' AND blocked_reason = 'RUN_FAILED'))
				""" : """
				UPDATE repo.job_setup
				SET status = 'CONFIRMING', finished_at = NULL, checked_at = NULL, head_sha = NULL, head_read_at = NULL,
				    exchange_reason = NULL
				WHERE id = :id AND status = 'FAILED'
				""").param("id", setup.id()).update();
		if (resumed == 0) {
			return false;
		}
		if (confirmed) {
			this.busy.reserve(job.jobId(), JobBusy.Purpose.JOB_SETUP, null, job.version(), setup.operationKey());
		}
		audit("repo.setup_resumed", setup.id(), setup.jobId());
		return true;
	}

	/**
	 * The waiting stage's first step done: the Source Repository as read now and its default branch's head, which the
	 * Customer is asked to confirm. A marker an attempt from before Story 23.6 still holds under the attempt's own key is
	 * released, since nothing runs under it while the Customer decides.
	 * @return whether this attempt was still waiting for its check
	 */
	@Transactional
	boolean checked(Setup setup, String sourceFullName, String defaultBranch, String headSha) {
		Instant now = this.clock.instant();
		int updated = this.jdbc.sql("""
				UPDATE repo.job_setup
				SET source_full_name = :source, default_branch = :branch, head_sha = :sha, head_read_at = :now, checked_at = :now
				WHERE id = :id AND status = 'CONFIRMING' AND checked_at IS NULL
				""").param("source", sourceFullName).param("branch", defaultBranch).param("sha", headSha)
			.param("now", Timestamp.from(now)).param("id", setup.id()).update();
		if (updated == 0) {
			return false;
		}
		this.busy.release(setup.jobId(), setup.waitKey());
		audit("repo.setup_source_checked", setup.id(), setup.jobId());
		awaitConfirmation(setup, headSha, now);
		return true;
	}

	/**
	 * {@value #CONFIRM_TODO} for the Customer, due with the FR-40 Starting stall, and {@value #CONFIRMATION_DUE}, once per
	 * read of a head to confirm ({@code readAt}), so a head that comes back to an earlier commit is told again.
	 */
	private void awaitConfirmation(Setup setup, String headSha, Instant readAt) {
		BusyJob job = this.busy.job(setup.jobId()).orElseThrow();
		String link = "/cong-viec/" + job.jobId();
		Instant deadline = this.rules.startingDeadline(job.jobId()).orElse(null);
		this.todos.open(new TodoRequest(CONFIRM_TODO, job.customerId(), Audience.CUSTOMER, setup.id(),
				Map.of("job", job.title()), link, deadline, job.jobId()));
		UUID cause = UUID.nameUUIDFromBytes((CONFIRMATION_DUE + ":" + setup.id() + ":" + readAt)
			.getBytes(StandardCharsets.UTF_8));
		this.notifications.send(new NotificationRequest(CONFIRMATION_DUE, cause,
				List.of(new Recipient(job.customerId(), Audience.CUSTOMER)),
				Map.of("job", job.title(), "sha", headSha.substring(0, 7)), link, job.jobId(), null));
	}

	/** The confirmation found the head moved: the attempt keeps waiting, for the head read now. */
	@Transactional
	void headRead(Setup setup, String headSha) {
		this.jdbc.sql("""
				UPDATE repo.job_setup SET head_sha = :sha, head_read_at = :now
				WHERE id = :id AND status = 'CONFIRMING' AND checked_at IS NOT NULL
				""").param("sha", headSha).param("now", Timestamp.from(this.clock.instant())).param("id", setup.id()).update();
	}

	/**
	 * The Customer's self-confirmation at the current head (N-32): recorded with the account, the SHA, the notice version
	 * and the time, and audited; the to-do closes, the marker is reserved for the copy stage keyed by the confirmation,
	 * and the copy stage is requested. Nothing is dispatched without such a record.
	 * @throws ApiException {@code CONFLICT SETUP_NOT_CONFIRMING} when the attempt no longer waits; as
	 * {@link #retry} for the Job; {@code JOB_BUSY} when another marker is set
	 */
	@Transactional
	Setup confirm(BusyJob job, Setup setup, UUID accountId, String headSha) {
		ApiException refused = refusal(job, job.version());
		if (refused != null) {
			throw refused;
		}
		UUID confirmation = this.ids.newId();
		Instant now = this.clock.instant();
		int updated = this.jdbc.sql("""
				UPDATE repo.job_setup
				SET status = 'RUNNING', head_sha = :sha, head_read_at = :now, approved_sha = :sha, confirmation_id = :confirmation,
				    confirmed_at = :now, exchange_reason = NULL
				WHERE id = :id AND status = 'CONFIRMING' AND checked_at IS NOT NULL
				""").param("sha", headSha).param("now", Timestamp.from(now)).param("confirmation", confirmation)
			.param("id", setup.id()).update();
		if (updated == 0) {
			throw new ApiException(ErrorCode.CONFLICT, RepoReason.SETUP_NOT_CONFIRMING);
		}
		this.jdbc.sql("""
				INSERT INTO repo.source_confirmation (id, job_id, product_id, setup_id, account_id, head_sha, notice_version,
				    confirmed_at)
				VALUES (:id, :job, :product, :setup, :account, :sha, :version, :now)
				""").param("id", confirmation).param("job", setup.jobId()).param("product", setup.productId())
			.param("setup", setup.id()).param("account", accountId).param("sha", headSha).param("version", this.noticeVersion)
			.param("now", Timestamp.from(now)).update();
		this.auditLog.record(AuditEntry.of(AuditActor.user(accountId), "repo.source_confirmed",
				AuditObject.of("repo.source_confirmation", confirmation), AuditObject.of("repo.job_setup", setup.id()))
			.forJob(setup.jobId()));
		this.todos.close(CONFIRM_TODO, job.customerId(), setup.id());
		String key = OPERATION + ":" + confirmation;
		this.busy.reserve(job.jobId(), JobBusy.Purpose.JOB_SETUP, null, job.version(), key);
		this.operations.request(new OperationRequest(OPERATION, confirmation,
				Map.of("setupId", setup.id().toString(), "confirmationId", confirmation.toString()), null, null, 0));
		return find(setup.id()).orElseThrow();
	}

	@Transactional
	void intermediaryCreated(Setup setup, PlatformRepository repository) {
		this.jdbc.sql("UPDATE repo.job_setup SET intermediary_github_id = :github, intermediary_name = :name WHERE id = :id")
			.param("github", repository.id())
			.param("name", repository.fullName())
			.param("id", setup.id())
			.update();
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
	 * The Intermediary Repository's settings read back as AD-11 sets them. Nothing checks it is empty: the copy pushes
	 * without forcing, so what an earlier attempt pushed, an ancestor of the confirmed head, is kept.
	 * @return whether the attempt was still copying
	 */
	@Transactional
	boolean intermediaryReadBack(Setup setup) {
		return this.jdbc.sql("UPDATE repo.job_setup SET intermediary_at = :now WHERE id = :id AND status = 'RUNNING'")
			.param("now", Timestamp.from(this.clock.instant()))
			.param("id", setup.id())
			.update() == 1;
	}

	/**
	 * Both repositories' settings read back: the copy may be dispatched into them.
	 * @return whether the attempt was still copying
	 */
	@Transactional
	boolean workingReadBack(Setup setup) {
		int updated = this.jdbc.sql("UPDATE repo.job_setup SET working_at = :now WHERE id = :id AND status = 'RUNNING'")
			.param("now", Timestamp.from(this.clock.instant()))
			.param("id", setup.id())
			.update();
		if (updated == 1) {
			audit("repo.setup_repositories_ready", setup.id(), setup.jobId());
		}
		return updated == 1;
	}

	/**
	 * The copy run dispatched on the Customer's GitHub Actions under {@code correlation} (AD-44, AD-46).
	 * @return whether the attempt was still copying, so the correlation is now its run's
	 */
	@Transactional
	boolean dispatched(Setup setup, UUID correlation) {
		int updated = this.jdbc.sql("""
				UPDATE repo.job_setup
				SET correlation_id = :correlation, dispatched_at = :now, run_status = NULL, run_conclusion = NULL
				WHERE id = :id AND status = 'RUNNING'
				""").param("correlation", correlation).param("now", Timestamp.from(this.clock.instant())).param("id", setup.id())
			.update();
		if (updated == 1) {
			audit("repo.setup_copy_dispatched", setup.id(), setup.jobId());
		}
		return updated == 1;
	}

	/** The run as last read: its status, and its conclusion once it completed or vibefix stopped waiting for it. */
	@Transactional
	void runRead(Setup setup, @Nullable String status, @Nullable String conclusion) {
		this.jdbc.sql("""
				UPDATE repo.job_setup SET run_status = :status, run_conclusion = :conclusion
				WHERE id = :id AND status = 'RUNNING' AND correlation_id = :correlation
				""").param("status", status).param("conclusion", conclusion).param("id", setup.id())
			.param("correlation", setup.correlationId()).update();
	}

	/**
	 * The head moved after the confirmation (the exchange refused the copy {@code sha}): the attempt waits for the
	 * Customer again, with the head read now, its marker released and its to-do and notification sent again. The
	 * earlier confirmation stays recorded; the repositories stay created, and are read back again before the next copy.
	 * @return whether the attempt was still copying for that confirmation
	 */
	@Transactional
	boolean headMoved(Setup setup, String sourceFullName, String defaultBranch, String headSha) {
		Instant now = this.clock.instant();
		int updated = this.jdbc.sql("""
				UPDATE repo.job_setup
				SET status = 'CONFIRMING', exchange_reason = :reason, source_full_name = :source, default_branch = :branch,
				    head_sha = :sha, head_read_at = :now, checked_at = :now, approved_sha = NULL, confirmation_id = NULL,
				    confirmed_at = NULL, intermediary_at = NULL, working_at = NULL, correlation_id = NULL, dispatched_at = NULL,
				    run_status = NULL, run_conclusion = NULL
				WHERE id = :id AND status = 'RUNNING' AND confirmation_id = :confirmation
				""").param("reason", HEAD_MOVED).param("source", sourceFullName).param("branch", defaultBranch)
			.param("sha", headSha).param("now", Timestamp.from(now)).param("id", setup.id())
			.param("confirmation", setup.confirmationId()).update();
		if (updated == 0) {
			return false;
		}
		this.busy.release(setup.jobId(), setup.operationKey());
		audit("repo.setup_head_moved", setup.id(), setup.jobId());
		awaitConfirmation(setup, headSha, now);
		return true;
	}

	/**
	 * The copy read back: both repositories hold the confirmed head. The resolving transaction (AD-5) releases the
	 * marker, and the Invitations stage starts in it (Story 7.3); J7 waits for Verify.
	 */
	@Transactional
	void done(Setup setup, PlatformRepository working) {
		Instant now = this.clock.instant();
		int updated = this.jdbc.sql("""
				UPDATE repo.job_setup SET status = 'DONE', copied_at = :now, finished_at = :now WHERE id = :id AND status = 'RUNNING'
				""").param("now", Timestamp.from(now)).param("id", setup.id()).update();
		if (updated == 1) {
			this.busy.release(setup.jobId(), setup.operationKey());
			audit("repo.setup_copied", setup.id(), setup.jobId());
			audit("repo.setup_done", setup.id(), setup.jobId());
			this.grants.getObject().grantAll(setup, working);
		}
	}

	/** As {@link #block(Setup, Block, String)}, for a block that is no exchange refusal. */
	@Transactional
	void block(Setup setup, Block block) {
		block(setup, block, null);
	}

	/**
	 * As {@link #block(Setup, Block)}, only while the attempt still waits for the Customer: the confirmation's own head
	 * read found the Source App gone, and a concurrent confirmation that started the copy is never blocked from here.
	 */
	@Transactional
	void blockWaiting(Setup setup, Block block) {
		block(setup, block, null, List.of("CONFIRMING"));
	}

	/**
	 * The Setup stopped on something the Customer can fix, or that the Customer must try again after: the Job stays in
	 * Starting with its funds held, the marker is released, any open to-do closes, and the Customer and the Mentor are
	 * told why.
	 * @param exchangeReason for {@link Block#EXCHANGE_REFUSED}, the exchange's refusal, such as {@code blob}
	 */
	@Transactional
	void block(Setup setup, Block block, @Nullable String exchangeReason) {
		block(setup, block, exchangeReason, List.of("CONFIRMING", "RUNNING"));
	}

	private void block(Setup setup, Block block, @Nullable String exchangeReason, List<String> from) {
		Instant now = this.clock.instant();
		int updated = this.jdbc.sql("""
				UPDATE repo.job_setup SET status = 'BLOCKED', blocked_reason = :reason, exchange_reason = :exchange,
				    finished_at = :now
				WHERE id = :id AND status IN (:from)
				""").param("reason", block.name()).param("exchange", block == Block.EXCHANGE_REFUSED ? exchangeReason : null)
			.param("now", Timestamp.from(now)).param("id", setup.id()).param("from", from)
			.update();
		if (updated == 0) {
			return;
		}
		this.busy.release(setup.jobId(), setup.operationKey());
		this.todos.closeAll(CONFIRM_TODO, setup.id());
		audit("repo.setup_blocked", setup.id(), setup.jobId());
		BusyJob job = this.busy.job(setup.jobId()).orElseThrow();
		List<Recipient> recipients = new ArrayList<>(List.of(new Recipient(job.customerId(), Audience.CUSTOMER)));
		if (job.mentorId() != null) {
			recipients.add(new Recipient(job.mentorId(), Audience.MENTOR));
		}
		// One notice per block: an attempt an Administrator resumed after RUN_FAILED may block again.
		UUID cause = UUID.nameUUIDFromBytes((START_BLOCKED + ":" + setup.id() + ":" + now).getBytes(StandardCharsets.UTF_8));
		this.notifications.send(new NotificationRequest(START_BLOCKED, cause, recipients,
				Map.of("job", job.title(), "reason", reason(block, exchangeReason)), "/cong-viec/" + job.jobId(), job.jobId(),
				null));
	}

	/** The plain reason of a block: an exchange refusal the Customer fixes names its own. */
	static String reason(Block block, @Nullable String exchangeReason) {
		if (block == Block.EXCHANGE_REFUSED && exchangeReason != null && EXCHANGE_REASONS.containsKey(exchangeReason)) {
			return EXCHANGE_REASONS.get(exchangeReason);
		}
		return REASONS.get(block);
	}

	/** The operation ended without success: the attempt failed, and the marker is released. */
	@Transactional
	void fail(Setup setup) {
		int updated = this.jdbc.sql("""
				UPDATE repo.job_setup SET status = 'FAILED', exchange_reason = NULL, finished_at = :now
				WHERE id = :id AND status IN ('CONFIRMING', 'RUNNING')
				""").param("now", Timestamp.from(this.clock.instant())).param("id", setup.id()).update();
		if (updated == 1) {
			this.busy.release(setup.jobId(), setup.operationKey());
			this.todos.closeAll(CONFIRM_TODO, setup.id());
			audit("repo.setup_failed", setup.id(), setup.jobId());
		}
	}

	/**
	 * An attempt's operation ended without success; a block or a moved head was resolved by the attempt itself. The
	 * waiting stage's operation names the attempt and fails it before its check; the copy stage's names the
	 * confirmation and fails it while it copies, except when its runs kept failing (FR-64): that blocks it
	 * {@code RUN_FAILED}, which the Customer tries again.
	 */
	@ApplicationModuleListener
	void on(OperationCompleted completed) {
		if (!OPERATION.equals(completed.type()) || completed.status() == OperationStatus.SUCCEEDED) {
			return;
		}
		UUID cause = UUID.fromString(completed.key().substring(OPERATION.length() + 1));
		Setup waiting = find(cause).orElse(null);
		if (waiting != null) {
			if ("CONFIRMING".equals(waiting.status()) && waiting.checkedAt() == null) {
				fail(waiting);
			}
			return;
		}
		Setup copying = this.jdbc.sql("SELECT " + COLUMNS + " FROM repo.job_setup WHERE confirmation_id = :id")
			.param("id", cause)
			.query(JobSetups::setup)
			.optional()
			.filter(setup -> "RUNNING".equals(setup.status()))
			.orElse(null);
		if (copying == null) {
			return;
		}
		if (completed.status() == OperationStatus.FAILED && "RUN_FAILED".equals(completed.errorCode())) {
			block(copying, Block.RUN_FAILED);
		}
		else {
			fail(copying);
		}
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

	/** One self-confirmation as recorded (N-32): who confirmed is not part of it. */
	record Confirmation(UUID id, UUID setupId, String headSha, String noticeVersion, Instant confirmedAt) {
	}

	/** The attempt's self-confirmations, oldest first: a moved head leaves the earlier ones. */
	List<Confirmation> confirmations(UUID setupId) {
		return this.jdbc.sql("""
				SELECT id, setup_id, head_sha, notice_version, confirmed_at FROM repo.source_confirmation
				WHERE setup_id = :setup ORDER BY confirmed_at, id
				""").param("setup", setupId).query((rs, n) -> new Confirmation(rs.getObject("id", UUID.class),
						rs.getObject("setup_id", UUID.class), rs.getString("head_sha"), rs.getString("notice_version"),
						rs.getTimestamp("confirmed_at").toInstant()))
			.list();
	}

	private void audit(String action, UUID setupId, UUID jobId) {
		this.auditLog.record(AuditEntry.of(AuditActor.system(), action, AuditObject.of("repo.job_setup", setupId)).forJob(jobId));
	}

	private static Setup setup(ResultSet rs, int n) throws SQLException {
		String block = rs.getString("blocked_reason");
		return new Setup(rs.getObject("id", UUID.class), rs.getObject("job_id", UUID.class),
				rs.getObject("product_id", UUID.class), rs.getString("status"), block == null ? null : Block.valueOf(block),
				rs.getString("source_full_name"), rs.getString("default_branch"), rs.getString("head_sha"),
				instant(rs, "head_read_at"), rs.getString("approved_sha"), rs.getObject("confirmation_id", UUID.class),
				instant(rs, "confirmed_at"), rs.getObject("intermediary_github_id", Long.class), rs.getString("intermediary_name"),
				rs.getObject("working_github_id", Long.class), rs.getString("working_name"), instant(rs, "checked_at"),
				instant(rs, "intermediary_at"), instant(rs, "working_at"), rs.getObject("correlation_id", UUID.class),
				instant(rs, "dispatched_at"), rs.getString("run_status"), rs.getString("run_conclusion"),
				rs.getString("exchange_reason"), instant(rs, "copied_at"), rs.getTimestamp("started_at").toInstant(),
				instant(rs, "finished_at"));
	}

	private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp value = rs.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}

}
