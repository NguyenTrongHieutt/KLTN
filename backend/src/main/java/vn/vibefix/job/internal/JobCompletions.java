package vn.vibefix.job.internal;

import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.job.JobBusy;
import vn.vibefix.job.JobCompletion;
import vn.vibefix.job.internal.JobLifecycle.Actor;

/** {@link JobCompletion}: the marker released first, so the busy check lets J9 through (AD-28). */
@Component
class JobCompletions implements JobCompletion {

	private final JobBusy busy;

	private final JobLifecycle lifecycle;

	private final Jobs jobs;

	JobCompletions(JobBusy busy, JobLifecycle lifecycle, Jobs jobs) {
		this.busy = busy;
		this.lifecycle = lifecycle;
		this.jobs = jobs;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public boolean finish(UUID jobId, Finish how, @Nullable String operationKey) {
		long version = this.jobs.lock(jobId).map(Jobs.Job::version).orElse(-1L);
		if (operationKey != null) {
			this.busy.release(jobId, operationKey);
		}
		return version >= 0 && this.lifecycle
			.tryApply(Transition.J9, jobId, Actor.SYSTEM, Trigger.SYSTEM, version, Map.of(J9Finish.FINISH, how.name()))
			.isPresent();
	}

}
