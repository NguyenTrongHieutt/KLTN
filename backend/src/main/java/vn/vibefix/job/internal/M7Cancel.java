package vn.vibefix.job.internal;

import org.springframework.stereotype.Component;

import vn.vibefix.job.internal.JobLifecycle.Applying;

/**
 * M7 (FR-40, FR-43): the Held Milestone (Funded, Revision Requested, or Submitted) is cancelled in a
 * confirmed cancellation; {@code payment}'s listener refunds exactly what is releasable. It records when.
 */
@Component
class M7Cancel implements JobLifecycle.Step {

	@Override
	public Transition transition() {
		return Transition.M7;
	}

	@Override
	public void check(Applying applying) {
	}

	@Override
	public void apply(Applying applying) {
		applying.set("cancelled_at", applying.now());
		applying.fact("milestoneId", applying.milestoneId().toString());
	}

}
