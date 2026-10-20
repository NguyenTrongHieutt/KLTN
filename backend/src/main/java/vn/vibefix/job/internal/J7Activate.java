package vn.vibefix.job.internal;

import org.springframework.stereotype.Component;

import vn.vibefix.job.internal.JobLifecycle.Applying;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;

/**
 * J7 (FR-18): an Assessment Job becomes Active at once, in M1's cascade. A Code Job's J7 waits for
 * its repositories and verified access (AD-11, Epic 7), so it is refused here.
 */
@Component
class J7Activate implements JobLifecycle.Step {

	@Override
	public Transition transition() {
		return Transition.J7;
	}

	/** @throws ApiException {@code GUARD_FAILED REPOSITORIES_NOT_READY} for a Code Job */
	@Override
	public void check(Applying applying) {
		if (applying.job().code()) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.REPOSITORIES_NOT_READY);
		}
	}

	@Override
	public void apply(Applying applying) {
		applying.fact("assessment", "true");
	}

}
