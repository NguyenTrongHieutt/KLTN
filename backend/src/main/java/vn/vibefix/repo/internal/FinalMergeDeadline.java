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
 * {@value #KEY} (FR-37, AD-10): the FR-37 deadline, set at J8. An authorization still {@code REQUESTED}
 * ends {@code DEADLINE}, and the ZIP Fallback follows (Story 8.9). A bound one waits for the Customer's
 * confirmation of the Sensitive Changes (Story 8.8). Pausable: a frozen Job pauses it. Collaborators are
 * looked up on use.
 */
@Component
class FinalMergeDeadline implements DeadlineHandler {

	static final String KEY = "repo.final_merge_deadline";

	static final DeadlineKind KIND = new DeadlineKind(KEY, true, true);

	private final ObjectProvider<FinalMergeAuthorizations> authorizations;

	private final ObjectProvider<JobBusy> busy;

	FinalMergeDeadline(ObjectProvider<FinalMergeAuthorizations> authorizations, ObjectProvider<JobBusy> busy) {
		this.authorizations = authorizations;
		this.busy = busy;
	}

	@Override
	public DeadlineKind kind() {
		return KIND;
	}

	@Override
	@Transactional
	public DeadlineOutcome handle(DueDeadline deadline) {
		Authorization authorization = this.authorizations.getObject().find(deadline.subjectId()).orElse(null);
		if (authorization == null || !"REQUESTED".equals(authorization.status())) {
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
