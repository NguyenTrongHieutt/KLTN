package vn.vibefix.job.internal;

import java.time.Instant;

import org.springframework.stereotype.Component;

import vn.vibefix.job.internal.JobLifecycle.Applying;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;

/**
 * J4 (FR-15): the selected Mentor signed the NDA, which {@code matching} recorded and vouches for, and
 * the Job enters Agreement. The facts carry the signature's version and time; {@code matching} closes
 * the held Applications, and Job Chat, drafting, and the Crew arrive with Epic 4.
 */
@Component
class J4Confirm implements JobLifecycle.Step {

	@Override
	public Transition transition() {
		return Transition.J4;
	}

	/** @throws ApiException {@code NOT_FOUND} for anyone but the Job's Mentor */
	@Override
	public void check(Applying applying) {
		if (!applying.actor().is(applying.job().mentorId())) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
	}

	@Override
	public void apply(Applying applying) {
		applying.fact("mentorId", String.valueOf(applying.job().mentorId()));
		applying.fact("ndaVersion", (String) applying.input("ndaVersion"));
		applying.fact("signedAt", ((Instant) applying.input("signedAt")).toString());
	}

}
