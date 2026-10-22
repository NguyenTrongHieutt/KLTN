package vn.vibefix.job.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.job.MilestoneHandovers;
import vn.vibefix.job.internal.JobLifecycle.Actor;
import vn.vibefix.job.internal.JobLifecycle.Applied;

/** {@link MilestoneHandovers} (FR-32, AD-4): the Job for the handover form, and M2 on its version. */
@Component
class JobHandovers implements MilestoneHandovers {

	static final int DEFAULT_FIRST_REMINDER_DAY = 3;

	static final int DEFAULT_SECOND_REMINDER_DAY = 6;

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
					job.state().name(), job.version(), !job.code(), job.frozenAt() != null,
					job.jobTypes().stream().anyMatch(JobType::changesProduct)));
	}

	@Override
	@Transactional
	public Submitted submit(UUID jobId, UUID milestoneId, UUID mentorId, long expectedVersion, UUID handoverId) {
		return submit(jobId, milestoneId, mentorId, expectedVersion, handoverId, null);
	}

	@Override
	@Transactional
	public Submitted submit(UUID jobId, UUID milestoneId, UUID mentorId, long expectedVersion, UUID handoverId,
			@Nullable String handoverCommit) {
		Map<String, Object> input = handoverCommit == null ? Map.of("handoverId", handoverId)
				: Map.of("handoverId", handoverId, "handoverCommit", handoverCommit);
		Applied applied = this.lifecycle.applyMilestone(Transition.M2, jobId, milestoneId, Actor.user(mentorId), Trigger.USER,
				expectedVersion, input);
		Instant checkBy = after(milestoneId, "AUTO_ACCEPTANCE_DAYS", M2Submit.DEFAULT_AUTO_ACCEPTANCE_DAYS);
		Instant first = after(milestoneId, "AUTO_ACCEPTANCE_FIRST_REMINDER_DAY", DEFAULT_FIRST_REMINDER_DAY);
		Instant second = after(milestoneId, "AUTO_ACCEPTANCE_SECOND_REMINDER_DAY", DEFAULT_SECOND_REMINDER_DAY);
		return new Submitted(applied.transitionId(), this.numbers.number(jobId, milestoneId), applied.version(), checkBy,
				first, second);
	}

	@Override
	@Transactional
	public Optional<Decided> autoAccept(UUID jobId, UUID milestoneId, UUID handoverId) {
		return this.lifecycle
			.tryApplyMilestone(Transition.M5, jobId, milestoneId, Actor.SYSTEM, Trigger.AUTO, Map.of("handoverId", handoverId))
			.map(applied -> new Decided(applied.transitionId(), this.numbers.number(jobId, milestoneId), applied.version()));
	}

	/** The Milestone's handover time plus the Job's copied {@code rule} days. */
	private Instant after(UUID milestoneId, String rule, int fallback) {
		return this.jdbc.sql("""
				SELECT m.submitted_at + make_interval(days => coalesce((j.rules ->> :rule)::int, :days))
				FROM job.milestone m JOIN job.job j ON j.id = m.job_id WHERE m.id = :milestone
				""")
			.param("milestone", milestoneId)
			.param("rule", rule)
			.param("days", fallback)
			.query(Timestamp.class)
			.single()
			.toInstant();
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
