package vn.vibefix.repo.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.job.JobBusy;
import vn.vibefix.job.JobBusy.BusyJob;
import vn.vibefix.job.JobCompletion;
import vn.vibefix.repo.internal.FinalMergeAuthorizations.Authorization;
import vn.vibefix.repo.internal.FinalMerges.FinalMerge;
import vn.vibefix.repo.internal.JobSetups.Setup;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.timers.Deadlines;

/**
 * The code receipt (FR-38, D10, UX-DR116, Story 23.10a): the Customer's own record that they have the finished code.
 * "Tôi đã tự tải / clone từ GitHub" writes one row per Job -- who confirmed and when -- in the transaction that
 * spends the closing choice, closes its to-do and applies J9 with {@code SELF_DOWNLOAD}: Closed, or Cancelled when
 * Closing was entered by a confirmed cancellation. A Job already Cancelled at J10 with accepted work keeps the path
 * until its repositories are deleted, and has no J9 left to apply.
 * <p>
 * The Final Merge is never recorded here: a branch read back is its own evidence
 * ({@code repo.final_merge.status = 'DONE'}), and once it is read back the receipt is refused. The two paths do not
 * close each other before that: a run that is dispatched, out, or failed with a named reason leaves the receipt
 * allowed, and the Final Merge's busy marker goes with it, so J9 passes and the run still out finds its marker gone
 * and ends {@code Obsolete}.
 */
@Component
class CodeReceipts {

	/** How long before the closing-choice deadline its chip warns (EXPERIENCE.md, Deadline chip). */
	static final Duration SOON = Duration.ofHours(48);

	/** The Job states the closing choice and the self-download belong to: Closing, and after it. */
	private static final List<String> ENDING = List.of("CLOSING", "CLOSED", "CANCELLED");

	/** One receipt, as its row holds it. */
	record CodeReceipt(UUID jobId, UUID setupId, UUID confirmedBy, Instant confirmedAt) {
	}

	/**
	 * Which ways of receiving the code are open now (UX-DR116).
	 * @param deadlineAt the closing-choice deadline, from J8 on
	 * @param near the deadline is within 48 h, so its chip warns
	 * @param finalMerge "Hợp nhất vào dự án của bạn" may still be set going
	 * @param selfDownload "Tôi đã tự tải / clone từ GitHub" may still be confirmed
	 * @param intermediaryUrl the Intermediary Repository's page, which the Customer reads until it is deleted
	 * @param deletionAt when the repositories are deleted, once the Job has ended and the Deadline is running
	 */
	record Choice(@Nullable Instant deadlineAt, boolean near, boolean finalMerge, boolean selfDownload,
			@Nullable String intermediaryUrl, @Nullable Instant deletionAt) {
	}

	private final JdbcClient jdbc;

	private final FinalMergeAuthorizations authorizations;

	private final FinalMerges merges;

	private final JobSetups setups;

	private final JobHandoverBranches branches;

	private final JobBusy busy;

	private final JobCompletion completion;

	private final Deadlines deadlines;

	private final AuditLog auditLog;

	private final Clock clock;

	CodeReceipts(JdbcClient jdbc, FinalMergeAuthorizations authorizations, FinalMerges merges, JobSetups setups,
			JobHandoverBranches branches, JobBusy busy, JobCompletion completion, Deadlines deadlines, AuditLog auditLog,
			Clock clock) {
		this.jdbc = jdbc;
		this.authorizations = authorizations;
		this.merges = merges;
		this.setups = setups;
		this.branches = branches;
		this.busy = busy;
		this.completion = completion;
		this.deadlines = deadlines;
		this.auditLog = auditLog;
		this.clock = clock;
	}

	/**
	 * "Bạn đã có bản mã cuối cùng?" confirmed (FR-38): the receipt is recorded, the choice spent, and J9 applied.
	 * <p>
	 * What is already settled, and then whether either path is open, are both answered before the Job's version
	 * (AD-23): this command's own success bumps that version, and a Customer whose choice is long gone is told that
	 * rather than asked to reload.
	 * @throws ApiException {@code CONFLICT CODE_RECEIPT_RECORDED}; {@code CONFLICT FINAL_MERGE_DONE};
	 * {@code CONFLICT NO_CLOSING_CHOICE_OPEN}; {@code STALE}; {@code JOB_BUSY}
	 */
	@Transactional
	CodeReceipt confirm(BusyJob job, UUID customerId, long expectedVersion) {
		if (find(job.jobId()).isPresent()) {
			throw new ApiException(ErrorCode.CONFLICT, RepoReason.CODE_RECEIPT_RECORDED);
		}
		Authorization authorization = this.authorizations.latest(job.jobId()).orElse(null);
		FinalMerge merge = authorization == null ? null : this.merges.find(authorization.id()).orElse(null);
		if (merge != null && "DONE".equals(merge.status())) {
			throw new ApiException(ErrorCode.CONFLICT, RepoReason.FINAL_MERGE_DONE);
		}
		Setup setup = readable(job.jobId()).orElse(null);
		// The row is the choice's identity and the step is read back through it, so a Job without one has no choice.
		if (authorization == null || setup == null || !selfDownloadOpen(job, authorization)) {
			throw new ApiException(ErrorCode.CONFLICT, RepoReason.NO_CLOSING_CHOICE_OPEN);
		}
		if (job.version() != expectedVersion) {
			throw new ApiException(ErrorCode.STALE);
		}
		int recorded = this.jdbc.sql("""
				INSERT INTO repo.code_receipt (job_id, setup_id, confirmed_by, confirmed_at)
				VALUES (:job, :setup, :by, :now)
				ON CONFLICT DO NOTHING
				""")
			.param("job", job.jobId())
			.param("setup", setup.id())
			.param("by", customerId)
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
		if (recorded == 0) {
			// A confirmation that raced this one wrote the Job's receipt first, and that one is the record.
			throw new ApiException(ErrorCode.CONFLICT, RepoReason.CODE_RECEIPT_RECORDED);
		}
		if (authorization.open()) {
			this.authorizations.used(authorization.id());
		}
		// A Final Merge run still out holds the marker: it goes with the receipt, so J9 passes and the run ends
		// Obsolete on its next step. A Job already ended (a cancellation at J10, or the closure fallback) has no J9.
		boolean applied = this.completion.finish(job.jobId(), JobCompletion.Finish.SELF_DOWNLOAD,
				job.busy().filter(marker -> marker.purpose() == JobBusy.Purpose.FINAL_MERGE)
					.map(JobBusy.Busy::operationKey)
					.orElse(null));
		if (!applied && "CLOSING".equals(job.state())) {
			// Another operation's marker refused J9 (AD-28), a refusal JobLifecycle.tryApply hides. Nothing may
			// commit: the choice would be spent on a Job that never closed. The Customer confirms again after it.
			throw new ApiException(ErrorCode.JOB_BUSY);
		}
		this.auditLog.record(AuditEntry
			.of(AuditActor.user(customerId), "repo.code_receipt_recorded", AuditObject.of("repo.code_receipt", job.jobId()))
			.forJob(job.jobId()));
		return find(job.jobId()).orElseThrow();
	}

	/** The Job's receipt, which Stories 23.10b and 23.10c read as "the Customer said they have the code". */
	@Transactional(readOnly = true)
	Optional<CodeReceipt> find(UUID jobId) {
		return this.jdbc
			.sql("SELECT job_id, setup_id, confirmed_by, confirmed_at FROM repo.code_receipt WHERE job_id = :job")
			.param("job", jobId)
			.query((rs, n) -> new CodeReceipt(rs.getObject("job_id", UUID.class), rs.getObject("setup_id", UUID.class),
					rs.getObject("confirmed_by", UUID.class), rs.getTimestamp("confirmed_at").toInstant()))
			.optional();
	}

	/** The closing choice as JOB-1 reads it: the deadline, and which of the two paths is still open. */
	@Transactional(readOnly = true)
	Choice choice(BusyJob job, Authorization authorization, boolean customer) {
		FinalMerge merge = this.merges.find(authorization.id()).orElse(null);
		boolean spent = find(job.jobId()).isPresent() || (merge != null && "DONE".equals(merge.status()));
		// The deadline runs while the Job is Closing: an ended Job has no date left to choose before.
		Instant deadline = "CLOSING".equals(job.state()) ? authorization.deadlineAt() : null;
		String intermediary = customer ? this.branches.intermediaryUrl(job.jobId()).orElse(null) : null;
		return new Choice(deadline, deadline != null && !deadline.isAfter(this.clock.instant().plus(SOON)),
				!spent && authorization.open() && "CLOSING".equals(job.state()),
				!spent && selfDownloadOpen(job, authorization) && readable(job.jobId()).isPresent(), intermediary,
				this.deadlines.dueAt(RepositoryDeletion.KEY, job.jobId()).orElse(null));
	}

	/**
	 * Whether the Customer may still say they have the code: while the choice is open in Closing, and afterwards for
	 * as long as there is accepted work to download -- a Final Merge that failed with its reason, a Job Closed by the
	 * closure fallback, or one Cancelled at J10 (R5, D10).
	 */
	private boolean selfDownloadOpen(BusyJob job, @Nullable Authorization authorization) {
		if (!ENDING.contains(job.state())) {
			return false;
		}
		boolean open = authorization != null && authorization.open() && "CLOSING".equals(job.state());
		return open || acceptedWork(job.jobId());
	}

	/** Whether a handover was merged into the Intermediary default branch: the code there is the Customer's to take. */
	private boolean acceptedWork(UUID jobId) {
		return this.jdbc.sql("SELECT EXISTS (SELECT 1 FROM repo.handover_merge WHERE job_id = :job)")
			.param("job", jobId)
			.query(Boolean.class)
			.single();
	}

	/** The Setup whose Intermediary Repository the Customer can still read, which is what they download from. */
	private Optional<Setup> readable(UUID jobId) {
		return this.setups.latest(jobId)
			.filter(setup -> "DONE".equals(setup.status()) && setup.intermediary() != null)
			.filter(setup -> this.jdbc.sql("SELECT deleted_at IS NULL FROM repo.job_setup WHERE id = :id")
				.param("id", setup.id())
				.query(Boolean.class)
				.optional()
				.orElse(false));
	}

	/** Who confirmed, for the erasure: the row says nothing else, so it goes with the Customer's other rows. */
	static void erase(JdbcClient jdbc, UUID accountId) {
		jdbc.sql("DELETE FROM repo.code_receipt WHERE confirmed_by = :id").param("id", accountId).update();
	}

}
