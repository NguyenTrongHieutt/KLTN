package vn.vibefix.repo.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.job.Crews;
import vn.vibefix.job.Crews.Relationship;
import vn.vibefix.job.JobBusy;
import vn.vibefix.job.JobBusy.BusyJob;
import vn.vibefix.repo.internal.JobSetups.Setup;
import vn.vibefix.repo.internal.JobSetups.Step;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;
import vn.vibefix.shared.Role;

/**
 * JOB-H's step tracker while a Code Job is Starting (UX-DR23, AD-11): the Setup's steps as the Customer,
 * the Mentor, and each active Crew member read them, and the Customer's "Thử lại" after a block. Everyone
 * else finds nothing.
 */
@RestController
@Access(Gate.SIGNED_IN)
class JobSetupController {

	/** One step: {@code DONE}, {@code CURRENT}, {@code PENDING}, {@code BLOCKED}, or {@code FAILED}. */
	record SetupStepView(String key, String state) {
	}

	/**
	 * @param status {@code NONE} before any Setup, else the latest attempt's {@code RUNNING}, {@code DONE},
	 * {@code BLOCKED}, or {@code FAILED}
	 * @param blockedReason the plain reason of a block
	 * @param openFindings for {@code INTAKE_FINDING_OPEN}, how many
	 * @param canRetry the viewer is the Customer and may start a new attempt
	 */
	record SetupView(UUID jobId, String jobState, String status, List<SetupStepView> steps, @Nullable String blockedReason,
			@Nullable Integer openFindings, boolean canRetry) {
	}

	private final JobSetups setups;

	private final JobBusy busy;

	private final Crews crews;

	private final CurrentCaller callers;

	JobSetupController(JobSetups setups, JobBusy busy, Crews crews, CurrentCaller callers) {
		this.setups = setups;
		this.busy = busy;
		this.crews = crews;
		this.callers = callers;
	}

	@GetMapping("/api/v1/jobs/{jobId}/setup")
	SetupView setup(@PathVariable UUID jobId) {
		Caller caller = this.callers.get();
		BusyJob job = reader(jobId, caller);
		return view(job, caller);
	}

	/** "Thử lại" (FR-18): the Customer starts a new attempt once the latest one is blocked. */
	@PostMapping("/api/v1/jobs/{jobId}/setup/retry")
	SetupView retry(@PathVariable UUID jobId) {
		Caller caller = this.callers.get();
		BusyJob job = reader(jobId, caller);
		if (!customer(job, caller)) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		this.setups.retry(job);
		return view(this.busy.job(jobId).orElseThrow(), caller);
	}

	private BusyJob reader(UUID jobId, Caller caller) {
		BusyJob job = this.busy.job(jobId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		UUID account = caller.accountId();
		if (account == null) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		boolean mentor = caller.hasRole(Role.MENTOR) && account.equals(job.mentorId());
		boolean crew = caller.hasRole(Role.STUDENT)
				&& this.crews.crewRelationship(jobId, account).kind() == Relationship.ACTIVE;
		if (!customer(job, caller) && !mentor && !crew) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		return job;
	}

	private static boolean customer(BusyJob job, Caller caller) {
		return caller.hasRole(Role.CUSTOMER) && job.customerId().equals(caller.accountId());
	}

	private SetupView view(BusyJob job, Caller caller) {
		Setup setup = this.setups.latest(job.jobId()).orElse(null);
		if (setup == null) {
			List<SetupStepView> pending = new ArrayList<>();
			for (Step step : Step.values()) {
				pending.add(new SetupStepView(step.name(), "PENDING"));
			}
			return new SetupView(job.jobId(), job.state(), "NONE", pending, null, null, false);
		}
		boolean[] done = { setup.checkedAt() != null, setup.intermediaryAt() != null,
				setup.workingAt() != null || "DONE".equals(setup.status()) };
		List<SetupStepView> steps = new ArrayList<>();
		boolean reached = false;
		for (Step step : Step.values()) {
			String state;
			if (done[step.ordinal()]) {
				state = "DONE";
			}
			else if (reached) {
				state = "PENDING";
			}
			else {
				reached = true;
				state = switch (setup.status()) {
					case "BLOCKED" -> "BLOCKED";
					case "FAILED" -> "FAILED";
					default -> "CURRENT";
				};
			}
			steps.add(new SetupStepView(step.name(), state));
		}
		boolean canRetry = customer(job, caller) && "BLOCKED".equals(setup.status()) && "STARTING".equals(job.state());
		return new SetupView(job.jobId(), job.state(), setup.status(), steps,
				setup.block() == null ? null : setup.block().name(), setup.openFindings(), canRetry);
	}

}
