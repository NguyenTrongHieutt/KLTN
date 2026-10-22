package vn.vibefix.repo.internal;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.job.JobBusy;
import vn.vibefix.job.JobBusy.BusyJob;
import vn.vibefix.repo.internal.FinalMergeAuthorizations.Authorization;
import vn.vibefix.timers.DeadlineHandler;
import vn.vibefix.timers.DeadlineKind;
import vn.vibefix.timers.DeadlineOutcome;
import vn.vibefix.timers.DueDeadline;

/**
 * {@value #KEY} (FR-37, AD-10): the FR-37 deadline, set at J8. An authorization still {@code REQUESTED},
 * or bound but whose Final Merge the Customer has not confirmed, ends {@code DEADLINE}, and the ZIP
 * Fallback follows (Story 8.9). A confirmed merge finishes on its own. Pausable: a frozen Job pauses it.
 * Collaborators are looked up on use.
 */
@Component
class FinalMergeDeadline implements DeadlineHandler {

	static final String KEY = "repo.final_merge_deadline";

	static final DeadlineKind KIND = new DeadlineKind(KEY, true, true);

	private final ObjectProvider<FinalMergeAuthorizations> authorizations;

	private final ObjectProvider<JobBusy> busy;

	private final ObjectProvider<FinalMerges> merges;

	FinalMergeDeadline(ObjectProvider<FinalMergeAuthorizations> authorizations, ObjectProvider<JobBusy> busy,
			ObjectProvider<FinalMerges> merges) {
		this.authorizations = authorizations;
		this.busy = busy;
		this.merges = merges;
	}

	@Override
	public DeadlineKind kind() {
		return KIND;
	}

	@Override
	@Transactional
	public DeadlineOutcome handle(DueDeadline deadline) {
		Authorization authorization = this.authorizations.getObject().find(deadline.subjectId()).orElse(null);
		if (authorization == null || !authorization.open()) {
			return DeadlineOutcome.OBSOLETE;
		}
		boolean confirmed = this.merges.getObject()
			.find(authorization.id())
			.filter(merge -> "CONFIRMED".equals(merge.status()) || "MERGED".equals(merge.status()))
			.isPresent();
		if (confirmed) {
			return DeadlineOutcome.OBSOLETE;
		}
		BusyJob job = this.busy.getObject().job(authorization.jobId()).orElse(null);
		if (job != null && job.frozen()) {
			return DeadlineOutcome.REPAUSE;
		}
		this.authorizations.getObject().end(authorization.id(), "DEADLINE");
		return DeadlineOutcome.FIRED;
	}

}
