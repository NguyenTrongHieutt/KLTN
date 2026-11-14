package vn.vibefix.delivery.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.json.JsonMapper;
import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.delivery.internal.Handovers.Code;
import vn.vibefix.delivery.internal.Handovers.Pending;
import vn.vibefix.repo.HandoverBranches;
import vn.vibefix.repo.HandoverBranches.Range;
import vn.vibefix.repo.HandoverCopies;
import vn.vibefix.repo.HandoverCopies.Request;
import vn.vibefix.repo.HandoverCopies.Result;
import vn.vibefix.repo.HandoverCopyFinished;
import vn.vibefix.scanning.SensitiveChanges;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.ProcessedEvents;

/**
 * A Code Job's handover while the runner copies it (FR-32, AD-11, AD-46, Story 23.8a). "Gửi bàn giao" checks as
 * before and keeps what the handover will store in {@code delivery.handover_submission}, then asks {@code repo}
 * to copy the Handover Commit into {@code handover/{submissionId}} of the Intermediary; no
 * {@code delivery.handover} row exists yet, only one submission may be pending per Milestone, and no busy marker
 * is held: the team keeps working while the runner copies (AD-46).
 * <p>
 * When the copy ends ({@link HandoverCopyFinished}), the handover is recorded under the submission's own ID: M2 on
 * the Job version the form loaded, which AD-23 keeps and the busy marker holds still while the copy runs, the
 * commits linked, the Sensitive Changes classified from the run's <em>full</em> path list, and the Delivery
 * Report, the Customer's to-do and the notification as built. A copy that was refused,
 * a path list that can no longer be read, or M2 refusing (STALE among them) ends the submission with that reason
 * and closes the pull request again, so no handover is ever recorded from an incomplete list.
 */
@Component
class HandoverSubmissions {

	private static final Logger log = LoggerFactory.getLogger(HandoverSubmissions.class);

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** The module whose reason enum names a copy's own refusal (AD-23). */
	private static final String DELIVERY = "delivery";

	/** The module whose reason enum names M2's refusal, {@code STALE} among them. */
	private static final String JOB = "job";

	private static final String COLUMNS = """
			id, job_id, product_id, milestone_id, milestone_number, mentor_account_id, customer_account_id, job_version,
			range_start, handover_commit, draft::text AS draft, status, reason, reason_module
			""";

	/**
	 * One submission, as its row holds it.
	 * @param status {@code PENDING}, {@code DONE} (the handover carries this ID), or {@code REFUSED}
	 */
	record Submission(UUID id, UUID jobId, UUID productId, UUID milestoneId, int milestoneNumber, UUID mentorId,
			UUID customerId, long jobVersion, String rangeStart, String handoverCommit, Pending draft, String status,
			@Nullable String reason, @Nullable String reasonModule) {
	}

	private final JdbcClient jdbc;

	private final Handovers handovers;

	private final HandoverCopies copies;

	private final HandoverBranches branches;

	private final SensitiveChanges sensitiveChanges;

	private final AuditLog auditLog;

	private final ProcessedEvents processedEvents;

	private final Clock clock;

	/** The recording and the refusal each commit or roll back on their own, inside the listener's transaction. */
	private final TransactionTemplate ownTransaction;

	/**
	 * GitHub's own calls, with the listener's transaction suspended: {@link HandoverBranches} is read and written
	 * outside any transaction, so no connection waits on an HTTP round trip.
	 */
	private final TransactionTemplate outsideTransaction;

	HandoverSubmissions(JdbcClient jdbc, Handovers handovers, HandoverCopies copies, HandoverBranches branches,
			SensitiveChanges sensitiveChanges, AuditLog auditLog, ProcessedEvents processedEvents,
			PlatformTransactionManager transactionManager, Clock clock) {
		this.ownTransaction = new TransactionTemplate(transactionManager);
		this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.outsideTransaction = new TransactionTemplate(transactionManager);
		this.outsideTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
		this.jdbc = jdbc;
		this.handovers = handovers;
		this.copies = copies;
		this.branches = branches;
		this.sensitiveChanges = sensitiveChanges;
		this.auditLog = auditLog;
		this.processedEvents = processedEvents;
		this.clock = clock;
	}

	/**
	 * Records the submission and asks {@code repo} for its copy, in one transaction. The partial unique index keeps
	 * one submission pending per Milestone, so a second send while the runner copies writes nothing.
	 * @throws ApiException {@code GUARD_FAILED HANDOVER_COPYING} for a Milestone that already has one pending
	 */
	@Transactional
	UUID submit(Pending pending, String rangeStart, String handoverCommit, String title, String body) {
		try {
			insert(pending, rangeStart, handoverCommit);
		}
		catch (DuplicateKeyException pending2) {
			// The partial unique index: this Milestone already has a submission waiting for its copy.
			throw new ApiException(ErrorCode.GUARD_FAILED, DeliveryReason.HANDOVER_COPYING);
		}
		this.copies.start(new Request(pending.id(), pending.jobId(), pending.milestoneNumber(), rangeStart, handoverCommit,
				title, body));
		this.auditLog.record(AuditEntry
			.of(AuditActor.user(pending.mentorId()), "delivery.handover_submitted",
					AuditObject.of("delivery.handover_submission", pending.id()))
			.forJob(pending.jobId()));
		return pending.id();
	}

	private void insert(Pending pending, String rangeStart, String handoverCommit) {
		this.jdbc.sql("""
				INSERT INTO delivery.handover_submission (id, job_id, product_id, milestone_id, milestone_number,
				    mentor_account_id, customer_account_id, job_version, range_start, handover_commit, draft, status, created_at)
				VALUES (:id, :job, :product, :milestone, :number, :mentor, :customer, :version, :start, :commit,
				    CAST(:draft AS JSONB), 'PENDING', :now)
				""")
			.param("id", pending.id())
			.param("job", pending.jobId())
			.param("product", pending.productId())
			.param("milestone", pending.milestoneId())
			.param("number", pending.milestoneNumber())
			.param("mentor", pending.mentorId())
			.param("customer", pending.customerId())
			.param("version", pending.version())
			.param("start", rangeStart)
			.param("commit", handoverCommit)
			.param("draft", JSON.writeValueAsString(pending))
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
	}

	@Transactional(readOnly = true)
	Optional<Submission> find(UUID id) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM delivery.handover_submission WHERE id = :id")
			.param("id", id)
			.query(HandoverSubmissions::submission)
			.optional();
	}

	/** The Job's latest submission, whichever Milestone it hands over: what the form that sent it reads. */
	@Transactional(readOnly = true)
	Optional<Submission> latest(UUID jobId) {
		return this.jdbc
			.sql("SELECT " + COLUMNS + " FROM delivery.handover_submission WHERE job_id = :job ORDER BY created_at DESC LIMIT 1")
			.param("job", jobId)
			.query(HandoverSubmissions::submission)
			.optional();
	}

	/**
	 * The submission's copy run as vibefix last read it (AD-46, Story 23.8b), for JOB-5's Actions run status row:
	 * empty when no copy was ever asked for, and never a run link, which only an Administrator is given.
	 */
	Optional<HandoverCopies.RunState> runState(UUID submissionId) {
		return this.copies.runState(submissionId);
	}

	/** Whether the Job has a submission waiting for its copy, whichever Milestone it hands over. */
	@Transactional(readOnly = true)
	boolean copying(UUID jobId) {
		return this.jdbc
			.sql("SELECT count(*) FROM delivery.handover_submission WHERE job_id = :job AND status = 'PENDING'")
			.param("job", jobId)
			.query(Integer.class)
			.single() > 0;
	}

	@ApplicationModuleListener
	void onHandoverCopyFinished(HandoverCopyFinished event) {
		if (!this.processedEvents.markProcessed("delivery.HandoverSubmissions.onHandoverCopyFinished", event.eventId())) {
			return;
		}
		finish(event.submissionId());
	}

	/**
	 * The copy ended: the handover is recorded from the submission, or the submission ends with the reason and the
	 * pull request is closed again. GitHub is read and written with the listener's transaction suspended, and only
	 * the recording and the refusal run in a transaction of their own; a redelivery of the event closes a pull
	 * request that is still open, and changes nothing else.
	 */
	void finish(UUID submissionId) {
		Submission submission = find(submissionId).orElse(null);
		if (submission == null || "DONE".equals(submission.status())) {
			return;
		}
		Result result = this.copies.result(submissionId).orElse(null);
		if ("REFUSED".equals(submission.status())) {
			// A redelivery after the close failed, or after it was never reached.
			closeAgain(submission, result);
			return;
		}
		if (result == null || !result.copied() || result.pullNumber() == null) {
			String reason = result == null || result.refusal() == null ? HandoverCopies.Refusal.RUN_FAILED.name()
					: result.refusal().name();
			refuse(submission, DELIVERY, reason, null);
			return;
		}
		List<String> paths = result.paths();
		if (paths == null || paths.size() != result.pathCount()) {
			// FR-32: a list that is not whole never becomes a handover, however the report ended up unreadable.
			refuse(submission, DELIVERY, HandoverCopies.Refusal.PATH_LIST_UNREADABLE.name(), result.pullNumber());
			return;
		}
		Range range = this.outsideTransaction.execute(status -> range(submission));
		// FR-32: the full list the run reported, never GitHub's 300-file compare, so nothing hides past a cap.
		Code code = new Code(submission.handoverCommit(), submission.rangeStart(), result.pullNumber(),
				range == null ? List.of() : range.commits(), range != null && range.truncated(),
				this.sensitiveChanges.classify(paths));
		try {
			record(submission, code);
		}
		catch (ApiException refused) {
			// M2's own refusal, STALE among them: its reason is job's, so the screen reads it there.
			String reason = refused.reason().orElseGet(() -> refused.code().name());
			log.info("Handover submission {} was not recorded: {}", submissionId, reason);
			refuse(submission, JOB, reason, result.pullNumber());
		}
	}

	/** Ends the submission, then closes its pull request with the transaction that ended it already committed. */
	private void refuse(Submission submission, String module, String reason, @Nullable Integer pullNumber) {
		if (Boolean.TRUE.equals(this.ownTransaction.execute(status -> refused(submission, module, reason)))) {
			log.info("Handover submission {} was refused: {}.{}", submission.id(), module, reason);
		}
		close(submission, pullNumber);
	}

	/**
	 * M2 and the handover's rows, then the submission is done: one transaction of its own, so an M2 that refuses
	 * rolls all of it back without poisoning the listener's.
	 */
	private void record(Submission submission, Code code) {
		this.ownTransaction.executeWithoutResult(status -> {
			if (this.jdbc.sql("""
					UPDATE delivery.handover_submission SET status = 'DONE', finished_at = :now
					WHERE id = :id AND status = 'PENDING'
					""").param("now", Timestamp.from(this.clock.instant())).param("id", submission.id()).update() != 1) {
				return;
			}
			this.handovers.record(submission.draft(), code);
		});
	}

	/** A redelivery: the pull request of an already refused submission is closed again, which is idempotent. */
	private void closeAgain(Submission submission, @Nullable Result result) {
		close(submission, result == null ? null : result.pullNumber());
	}

	private void close(Submission submission, @Nullable Integer pullNumber) {
		if (pullNumber == null) {
			return;
		}
		this.outsideTransaction.executeWithoutResult(status -> this.branches.closeAfterRefusal(submission.jobId(),
				pullNumber));
	}

	/**
	 * The range from GitHub's compare, for the Delivery Report's "Chi tiết kỹ thuật": its commits, and whether
	 * GitHub cut them. The changed paths are the run's own, so a cut list of files changes nothing here.
	 * @return {@code null} when GitHub did not answer: the commit list is evidence, not a guard, so the handover is
	 * recorded without it rather than lost
	 */
	private @Nullable Range range(Submission submission) {
		try {
			return this.branches.range(submission.jobId(), submission.rangeStart(), submission.handoverCommit());
		}
		catch (HandoverBranches.Unavailable unavailable) {
			log.warn("The commits of handover submission {} were not read", submission.id(), unavailable);
			return null;
		}
	}

	/**
	 * The submission ends with a named reason and the module whose enum names it.
	 * @return whether this call ended it
	 */
	private boolean refused(Submission submission, String module, String reason) {
		int updated = this.jdbc.sql("""
				UPDATE delivery.handover_submission SET status = 'REFUSED', reason = :reason, reason_module = :module,
				    finished_at = :now
				WHERE id = :id AND status = 'PENDING'
				""")
			.param("reason", reason)
			.param("module", module)
			.param("now", Timestamp.from(this.clock.instant()))
			.param("id", submission.id())
			.update();
		if (updated == 1) {
			this.auditLog.record(AuditEntry
				.of(AuditActor.system(), "delivery.handover_submission_refused",
						AuditObject.of("delivery.handover_submission", submission.id()))
				.forJob(submission.jobId()));
		}
		return updated == 1;
	}

	private static Submission submission(ResultSet rs, int n) throws SQLException {
		return new Submission(rs.getObject("id", UUID.class), rs.getObject("job_id", UUID.class),
				rs.getObject("product_id", UUID.class), rs.getObject("milestone_id", UUID.class),
				rs.getInt("milestone_number"), rs.getObject("mentor_account_id", UUID.class),
				rs.getObject("customer_account_id", UUID.class), rs.getLong("job_version"), rs.getString("range_start"),
				rs.getString("handover_commit"), JSON.readValue(rs.getString("draft"), Pending.class),
				rs.getString("status"), rs.getString("reason"), rs.getString("reason_module"));
	}

}
