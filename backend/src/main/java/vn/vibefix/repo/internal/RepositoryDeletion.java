package vn.vibefix.repo.internal;

import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import vn.vibefix.job.JobBusy;
import vn.vibefix.repo.internal.RepositoryDeletions.Pending;
import vn.vibefix.timers.DeadlineHandler;
import vn.vibefix.timers.DeadlineKind;
import vn.vibefix.timers.DeadlineOutcome;
import vn.vibefix.timers.DueDeadline;

/**
 * {@value #KEY} (FR-21): 30 days after the Job is Closed or Cancelled, each of its Setups whose
 * repositories still exist gets {@value RepositoryDeletions#DELETE}. Not pausable; the subject is the Job.
 * A Job whose repositories went with its Product leaves it {@code OBSOLETE}. Collaborators are looked up on
 * use.
 */
@Component
class RepositoryDeletion implements DeadlineHandler {

	static final String KEY = "repo.repository_deletion";

	static final DeadlineKind KIND = new DeadlineKind(KEY, false, true);

	private final ObjectProvider<RepositoryDeletions> deletions;

	private final ObjectProvider<JobBusy> busy;

	RepositoryDeletion(ObjectProvider<RepositoryDeletions> deletions, ObjectProvider<JobBusy> busy) {
		this.deletions = deletions;
		this.busy = busy;
	}

	@Override
	public DeadlineKind kind() {
		return KIND;
	}

	@Override
	public DeadlineOutcome handle(DueDeadline deadline) {
		RepositoryDeletions deletions = this.deletions.getObject();
		// The Job is read only to tell a live Job from one whose rows went with its Product.
		boolean live = this.busy.getObject().job(deadline.subjectId()).isPresent();
		List<Pending> pending = deletions.pending(deadline.subjectId());
		if (!live || pending.isEmpty()) {
			return DeadlineOutcome.OBSOLETE;
		}
		pending.forEach(deletions::request);
		return DeadlineOutcome.FIRED;
	}

}
