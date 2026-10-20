package vn.vibefix.job.internal;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.job.internal.Jobs.Job;
import vn.vibefix.timers.DeadlineHandler;
import vn.vibefix.timers.DeadlineKind;
import vn.vibefix.timers.DeadlineOutcome;
import vn.vibefix.timers.DueDeadline;

/**
 * {@value #KEY} (FR-48, AD-7): the Milestone is due in 2 days, so the Mentor and the active Crew are
 * reminded. Pausable: a frozen Job
 * pauses it. A Milestone handed over, decided, or given a new due date leaves it {@code OBSOLETE}.
 * Collaborators are looked up on use.
 */
@Component
class MilestoneDueSoon implements DeadlineHandler {

	static final String KEY = "job.milestone_due_soon";

	static final DeadlineKind KIND = new DeadlineKind(KEY, true, true);

	private final ObjectProvider<DueDates> dueDates;

	private final ObjectProvider<Jobs> jobs;

	MilestoneDueSoon(ObjectProvider<DueDates> dueDates, ObjectProvider<Jobs> jobs) {
		this.dueDates = dueDates;
		this.jobs = jobs;
	}

	@Override
	public DeadlineKind kind() {
		return KIND;
	}

	@Override
	@Transactional
	public DeadlineOutcome handle(DueDeadline deadline) {
		Job job = deadline.jobId() == null ? null : this.jobs.getObject().lock(deadline.jobId()).orElse(null);
		if (job == null) {
			return DeadlineOutcome.OBSOLETE;
		}
		if (job.frozenAt() != null) {
			return DeadlineOutcome.REPAUSE;
		}
		return this.dueDates.getObject().dueSoon(job, deadline.subjectId(), deadline.generation(), deadline.id())
				? DeadlineOutcome.FIRED : DeadlineOutcome.OBSOLETE;
	}

}
