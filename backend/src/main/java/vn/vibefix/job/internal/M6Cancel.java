package vn.vibefix.job.internal;

import org.springframework.stereotype.Component;

import vn.vibefix.job.internal.JobLifecycle.Applying;

/** M6 (FR-36, FR-40, J8): a Planned Milestone is cancelled; no money moves. It records when. */
@Component
class M6Cancel implements JobLifecycle.Step {

	@Override
	public Transition transition() {
		return Transition.M6;
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
