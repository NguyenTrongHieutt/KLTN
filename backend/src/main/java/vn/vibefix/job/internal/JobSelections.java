package vn.vibefix.job.internal;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.job.JobSelection;
import vn.vibefix.job.internal.JobLifecycle.Actor;

/** {@link JobSelection} through {@link JobLifecycle}, the only code that changes a Job's state. */
@Component
class JobSelections implements JobSelection {

	private final JobLifecycle lifecycle;

	JobSelections(JobLifecycle lifecycle) {
		this.lifecycle = lifecycle;
	}

	@Override
	@Transactional
	public long select(UUID jobId, UUID customerId, UUID mentorId, UUID applicationId, long expectedVersion) {
		return this.lifecycle.apply(Transition.J3, jobId, Actor.user(customerId), Trigger.USER, expectedVersion,
				Map.of("mentorId", mentorId, "applicationId", applicationId)).version();
	}

}
