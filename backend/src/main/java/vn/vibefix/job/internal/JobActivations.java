package vn.vibefix.job.internal;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.job.JobActivation;
import vn.vibefix.job.JobBusy;
import vn.vibefix.job.internal.JobLifecycle.Actor;

/** {@link JobActivation}: the marker released first, so the busy check lets J7 through (AD-28). */
@Component
class JobActivations implements JobActivation {

	private final JobBusy busy;

	private final JobLifecycle lifecycle;

	private final Jobs jobs;

	JobActivations(JobBusy busy, JobLifecycle lifecycle, Jobs jobs) {
		this.busy = busy;
		this.lifecycle = lifecycle;
		this.jobs = jobs;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public boolean activate(UUID jobId, String operationKey) {
		long version = this.jobs.lock(jobId).map(Jobs.Job::version).orElse(-1L);
		this.busy.release(jobId, operationKey);
		return version >= 0 && this.lifecycle
			.tryApply(Transition.J7, jobId, Actor.SYSTEM, Trigger.SYSTEM, version, Map.of(J7Activate.ACCESS_VERIFIED, true))
			.isPresent();
	}

}
