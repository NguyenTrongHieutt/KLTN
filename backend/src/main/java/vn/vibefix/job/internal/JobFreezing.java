package vn.vibefix.job.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.job.JobFreezes;

/** {@link JobFreezes} on {@code job.job.frozen_at}, which {@link JobLifecycle} already honours. */
@Component
class JobFreezing implements JobFreezes {

	private final JdbcClient jdbc;

	private final Jobs jobs;

	private final Clock clock;

	JobFreezing(JdbcClient jdbc, Jobs jobs, Clock clock) {
		this.jdbc = jdbc;
		this.jobs = jobs;
		this.clock = clock;
	}

	@Override
	@Transactional
	public Optional<DisputedJob> lock(UUID jobId) {
		return this.jobs.lock(jobId).map(job -> new DisputedJob(job.id(), job.productId(), job.customerId(), job.mentorId(),
				job.title(), job.state().name(), job.frozenAt() != null, this.jdbc.sql("""
						SELECT id FROM job.milestone WHERE job_id = :job AND removed_at IS NULL
						    AND state IN ('FUNDED', 'REVISION_REQUESTED', 'SUBMITTED')
						ORDER BY position LIMIT 1
						""").param("job", jobId).query(UUID.class).optional().orElse(null)));
	}

	@Override
	@Transactional
	public void freeze(UUID jobId) {
		this.jdbc.sql("UPDATE job.job SET frozen_at = :now WHERE id = :id AND frozen_at IS NULL")
			.param("id", jobId)
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
	}

	@Override
	@Transactional
	public void unfreeze(UUID jobId) {
		// FR-68, FR-87, AD-48: no handover is possible while the Job is frozen, so the frozen time never counts as late.
		// A Milestone already flagged overdue has no pending check for the thaw to push back; its flag, which holds
		// when its due date in force really ended, moves later by the time the Job was frozen.
		this.jdbc.sql("""
				UPDATE job.milestone m SET overdue_since = m.overdue_since + (CAST(:now AS TIMESTAMPTZ) - j.frozen_at)
				FROM job.job j
				WHERE j.id = :id AND m.job_id = j.id AND j.frozen_at IS NOT NULL AND j.frozen_at < :now
				  AND m.overdue_since IS NOT NULL AND m.removed_at IS NULL AND m.state IN ('FUNDED', 'REVISION_REQUESTED')
				""").param("id", jobId).param("now", Timestamp.from(this.clock.instant())).update();
		this.jdbc.sql("UPDATE job.job SET frozen_at = NULL WHERE id = :id").param("id", jobId).update();
	}

}
