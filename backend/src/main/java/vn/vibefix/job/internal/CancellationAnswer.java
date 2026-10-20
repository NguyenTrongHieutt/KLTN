package vn.vibefix.job.internal;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import vn.vibefix.timers.DeadlineHandler;
import vn.vibefix.timers.DeadlineKind;
import vn.vibefix.timers.DeadlineOutcome;
import vn.vibefix.timers.DueDeadline;

/**
 * {@value #KEY} (FR-40, AD-7): the other side did not answer a cancellation proposal within the Job's
 * copied days, so its proposer is told. Pausable: a frozen Job pauses it. The generation is the proposal;
 * a decided proposal leaves it {@code OBSOLETE}. Collaborators are looked up on use.
 */
@Component
class CancellationAnswer implements DeadlineHandler {

	static final String KEY = "job.cancellation_answer";

	static final DeadlineKind KIND = new DeadlineKind(KEY, true, true);

	private final ObjectProvider<Cancellations> cancellations;

	private final ObjectProvider<Jobs> jobs;

	CancellationAnswer(ObjectProvider<Cancellations> cancellations, ObjectProvider<Jobs> jobs) {
		this.cancellations = cancellations;
		this.jobs = jobs;
	}

	@Override
	public DeadlineKind kind() {
		return KIND;
	}

	@Override
	public DeadlineOutcome handle(DueDeadline deadline) {
		if (deadline.jobId() != null && this.jobs.getObject().lock(deadline.jobId()).map(job -> job.frozenAt() != null)
			.orElse(false)) {
			return DeadlineOutcome.REPAUSE;
		}
		return this.cancellations.getObject().unanswered(deadline.subjectId()) ? DeadlineOutcome.FIRED
				: DeadlineOutcome.OBSOLETE;
	}

}
