package vn.vibefix.job.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.job.MilestoneHandovers;
import vn.vibefix.job.internal.JobLifecycle.Actor;
import vn.vibefix.job.internal.JobLifecycle.Applied;

/** {@link MilestoneHandovers} (FR-32, AD-4): the Job for the handover form, and M2 on its version. */
@Component
class JobHandovers implements MilestoneHandovers {

	private final JdbcClient jdbc;

	private final JobLifecycle lifecycle;

	private final M1Fund numbers;

	JobHandovers(JdbcClient jdbc, JobLifecycle lifecycle, M1Fund numbers) {
		this.jdbc = jdbc;
		this.lifecycle = lifecycle;
		this.numbers = numbers;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<HandoverJob> job(UUID jobId) {
		return this.jdbc.sql("SELECT " + Jobs.COLUMNS + " FROM job.job WHERE id = :id")
			.param("id", jobId)
			.query(Jobs::job)
			.optional()
			.map(job -> new HandoverJob(job.id(), job.productId(), job.customerId(), job.mentorId(), job.title(),
					job.state().name(), job.version(), !job.code()));
	}

	@Override
	@Transactional
	public Submitted submit(UUID jobId, UUID milestoneId, UUID mentorId, long expectedVersion, UUID handoverId) {
		Applied applied = this.lifecycle.applyMilestone(Transition.M2, jobId, milestoneId, Actor.user(mentorId), Trigger.USER,
				expectedVersion, Map.of("handoverId", handoverId));
		Instant checkBy = this.jdbc.sql("""
				SELECT submitted_at + make_interval(days => coalesce((j.rules ->> 'AUTO_ACCEPTANCE_DAYS')::int, :days))
				FROM job.milestone m JOIN job.job j ON j.id = m.job_id WHERE m.id = :milestone
				""")
			.param("milestone", milestoneId)
			.param("days", M2Submit.DEFAULT_AUTO_ACCEPTANCE_DAYS)
			.query(Timestamp.class)
			.single()
			.toInstant();
		return new Submitted(applied.transitionId(), this.numbers.number(jobId, milestoneId), applied.version(), checkBy);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<Rounds> rounds(UUID milestoneId) {
		return this.jdbc.sql("SELECT revision_rounds, revision_rounds_used FROM job.milestone WHERE id = :id")
			.param("id", milestoneId)
			.query((rs, n) -> new Rounds(rs.getInt("revision_rounds"), rs.getInt("revision_rounds_used")))
			.optional();
	}

	@Override
	@Transactional
	public Decided accept(UUID jobId, UUID milestoneId, UUID customerId, long expectedVersion, UUID handoverId) {
		Applied applied = this.lifecycle.applyMilestone(Transition.M5, jobId, milestoneId, Actor.user(customerId), Trigger.USER,
				expectedVersion, Map.of("handoverId", handoverId));
		return new Decided(applied.transitionId(), this.numbers.number(jobId, milestoneId), applied.version());
	}

	@Override
	@Transactional
	public Decided requestRevision(UUID jobId, UUID milestoneId, UUID customerId, long expectedVersion, UUID handoverId,
			int criteria) {
		Applied applied = this.lifecycle.applyMilestone(Transition.M4, jobId, milestoneId, Actor.user(customerId), Trigger.USER,
				expectedVersion, Map.of("handoverId", handoverId, "criteria", criteria));
		return new Decided(applied.transitionId(), this.numbers.number(jobId, milestoneId), applied.version());
	}

}
