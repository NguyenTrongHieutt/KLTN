package vn.vibefix.job.internal;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import vn.vibefix.timers.DeadlineHandler;
import vn.vibefix.timers.DeadlineKind;
import vn.vibefix.timers.DeadlineOutcome;
import vn.vibefix.timers.DueDeadline;

/**
 * {@value #KEY} (FR-67, AD-7): the System's closure proposal is confirmed when nobody declined it within
 * the Job's copied days, applying the closure with J8 as {@code AUTO}. Pausable: a frozen Job pauses it.
 * The generation is the proposal; a decided proposal leaves it {@code OBSOLETE}. Collaborators are looked
 * up on use, since {@code timers} collects its handlers first.
 */
@Component
class ClosureAutoConfirm implements DeadlineHandler {

	static final String KEY = "job.closure_auto_confirm";

	static final DeadlineKind KIND = new DeadlineKind(KEY, true, true);

	private final ObjectProvider<Closures> closures;

	private final ObjectProvider<Jobs> jobs;

	ClosureAutoConfirm(ObjectProvider<Closures> closures, ObjectProvider<Jobs> jobs) {
		this.closures = closures;
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
		return this.closures.getObject().autoConfirm(deadline.subjectId()) ? DeadlineOutcome.FIRED : DeadlineOutcome.OBSOLETE;
	}

}
