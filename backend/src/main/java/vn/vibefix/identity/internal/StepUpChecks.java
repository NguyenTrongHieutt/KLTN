package vn.vibefix.identity.internal;

import java.util.UUID;

import org.springframework.stereotype.Component;

import vn.vibefix.identity.AdministratorStepUp;

/** {@link AdministratorStepUp} through {@link StepUps}. */
@Component
class StepUpChecks implements AdministratorStepUp {

	private final StepUps stepUps;

	StepUpChecks(StepUps stepUps) {
		this.stepUps = stepUps;
	}

	@Override
	public Result check(UUID administrator, String code) {
		StepUps.Outcome outcome = this.stepUps.check(administrator, code);
		return outcome instanceof StepUps.Outcome.Refused refused ? new Result(refused.refusal(), refused.endSession())
				: new Result(null, false);
	}

}
