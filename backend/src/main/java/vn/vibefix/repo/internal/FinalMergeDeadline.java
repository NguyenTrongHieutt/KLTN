package vn.vibefix.repo.internal;

import java.util.Objects;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.job.JobBusy;
import vn.vibefix.job.JobBusy.BusyJob;
import vn.vibefix.job.JobCompletion;
import vn.vibefix.job.JobFreezes;
import vn.vibefix.job.JobFreezes.DisputedJob;
import vn.vibefix.repo.internal.FinalMergeAuthorizations.Authorization;
import vn.vibefix.repo.internal.FinalMerges.FinalMerge;
import vn.vibefix.timers.DeadlineHandler;
import vn.vibefix.timers.DeadlineKind;
import vn.vibefix.timers.DeadlineOutcome;
import vn.vibefix.timers.DueDeadline;

/**
 * {@value #KEY} (FR-37, FR-38, D10, Story 23.10b): the closing choice's deadline, set at J8. A row still
 * {@code REQUESTED} whose Customer has taken neither path ends the Job itself: J9 applies as
 * {@link JobCompletion.Finish#UNCONFIRMED} -- Closed, or Cancelled when Closing was entered by a confirmed
 * cancellation -- and only then does the row end {@code DEADLINE}. Since Story 23.10c this is the Job's only way
 * out of {@code CLOSING} when the Customer confirms nothing, which is why a refused Final Merge leaves the row
 * {@code REQUESTED} rather than ending it. The Customer hears {@code repo.code_receipt_unconfirmed} and keeps the
 * self-download until the repositories are deleted, which {@link Revocations} arranges on the ending transition.
 * <p>
 * {@code OBSOLETE} is for a choice already taken: the Final Merge branch was read back ({@code DONE}), or a receipt
 * is on the record. A run still out is not one of those, because it may yet be refused -- and since Story 23.10c a
 * refusal leaves the row {@code REQUESTED} instead of ending it, while this Deadline is scheduled once at J8 and
 * never again. So a run the Customer set going answers {@code REARM}: the Deadline survives to end a Job whose run
 * is refused afterwards, which is the only J9 such a Job has left (AD-5). Pausable: it takes the Job row lock
 * first, which {@code end} does not, so a frozen Job always pauses it and nothing ends (AD-4); another operation's
 * busy marker answers {@code REARM} with nothing written (AD-28). Collaborators are looked up on use.
 */
@Component
class FinalMergeDeadline implements DeadlineHandler {

	static final String KEY = "repo.final_merge_deadline";

	static final DeadlineKind KIND = new DeadlineKind(KEY, true, true);

	private final ObjectProvider<FinalMergeAuthorizations> authorizations;

	private final ObjectProvider<JobFreezes> freezes;

	private final ObjectProvider<FinalMerges> merges;

	private final ObjectProvider<CodeReceipts> receipts;

	private final ObjectProvider<JobBusy> busy;

	private final ObjectProvider<JobCompletion> completion;

	FinalMergeDeadline(ObjectProvider<FinalMergeAuthorizations> authorizations, ObjectProvider<JobFreezes> freezes,
			ObjectProvider<FinalMerges> merges, ObjectProvider<CodeReceipts> receipts, ObjectProvider<JobBusy> busy,
			ObjectProvider<JobCompletion> completion) {
		this.authorizations = authorizations;
		this.freezes = freezes;
		this.merges = merges;
		this.receipts = receipts;
		this.busy = busy;
		this.completion = completion;
	}

	@Override
	public DeadlineKind kind() {
		return KIND;
	}

	@Override
	@Transactional
	public DeadlineOutcome handle(DueDeadline deadline) {
		DisputedJob disputed = this.freezes.getObject().lock(Objects.requireNonNull(deadline.jobId())).orElse(null);
		if (disputed != null && disputed.frozen()) {
			return DeadlineOutcome.REPAUSE;
		}
		Authorization authorization = this.authorizations.getObject().find(deadline.subjectId()).orElse(null);
		if (authorization == null || !authorization.open()) {
			return DeadlineOutcome.OBSOLETE;
		}
		FinalMerge merge = this.merges.getObject().find(authorization.id()).orElse(null);
		// The code reached the Customer: the Final Merge branch was read back, or they confirmed the receipt.
		boolean received = (merge != null && "DONE".equals(merge.status()))
				|| this.receipts.getObject().find(authorization.jobId()).isPresent();
		BusyJob job = this.busy.getObject().job(authorization.jobId()).orElse(null);
		// The choice belongs to Closing, and J9 is the only transition out of it: a Job that is no longer Closing
		// has no J9 left to apply, so J9 would refuse for good and a REARM below would retry at every poll.
		if (received || job == null || !"CLOSING".equals(job.state())) {
			return DeadlineOutcome.OBSOLETE;
		}
		if (job.busy().isPresent() || (merge != null && merge.refusal() == null && FinalMerges.confirmed(merge))) {
			// AD-28: a Settlement, an acceptance or a Setup holds the Job, so nothing is written and the next poll
			// finds the row still open. A run the Customer set going is the same case: it may yet be refused, and a
			// refusal no longer ends the row (Story 23.10c), so this Deadline must still be here to end the Job.
			return DeadlineOutcome.REARM;
		}
		// J9 first, the row only after it: JobCompletion.finish writes nothing but the marker release when it is
		// refused, so a refusal leaves the choice open for the retry. Ending first would spend the choice on a Job
		// stuck in Closing, because the retry would find the row no longer REQUESTED and answer OBSOLETE.
		if (!this.completion.getObject().finish(job.jobId(), JobCompletion.Finish.UNCONFIRMED, null)) {
			return DeadlineOutcome.REARM;
		}
		this.authorizations.getObject().end(authorization.id(), "DEADLINE");
		return DeadlineOutcome.FIRED;
	}

}
