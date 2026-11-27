package vn.vibefix.job.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.job.JobRules;
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

	private final DueDates dueDates;

	private final JobRules rules;

	JobHandovers(JdbcClient jdbc, JobLifecycle lifecycle, M1Fund numbers, DueDates dueDates, JobRules rules) {
		this.rules = rules;
		this.dueDates = dueDates;
		this.jdbc = jdbc;
		this.lifecycle = lifecycle;
		this.numbers = numbers;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<HandoverJob> job(UUID jobId) {
		return this.jdbc.sql("SELECT " + Jobs.COLUMNS + " FROM job.job WHERE id = :id AND " + Jobs.LIVE)
			.param("id", jobId)
			.query(Jobs::job)
			.optional()
			.map(job -> new HandoverJob(job.id(), job.productId(), job.customerId(), job.mentorId(), job.title(),
					job.state().name(), job.version(), !job.code(),
					job.jobTypes().stream().anyMatch(JobType::changesProduct), job.selfMentored()));
	}

	@Override
	@Transactional
	public Submitted submit(UUID jobId, UUID milestoneId, UUID mentorId, long expectedVersion, UUID handoverId) {
		return submit(jobId, milestoneId, mentorId, expectedVersion, handoverId, null, null);
	}

	@Override
	@Transactional
	public Submitted submit(UUID jobId, UUID milestoneId, UUID mentorId, long expectedVersion, UUID handoverId,
			@Nullable String handoverCommit, @Nullable Instant sentAt) {
		Map<String, Object> input = new HashMap<>(Map.of("handoverId", handoverId));
		if (handoverCommit != null) {
			input.put("handoverCommit", handoverCommit);
		}
		if (sentAt != null) {
			input.put("sentAt", sentAt);
		}
		Applied applied = this.lifecycle.applyMilestone(Transition.M2, jobId, milestoneId, Actor.user(mentorId), Trigger.USER,
				expectedVersion, input);
		Instant checkBy = after(milestoneId, "AUTO_ACCEPTANCE_DAYS", M2Submit.DEFAULT_AUTO_ACCEPTANCE_DAYS);
		Instant first = after(milestoneId, "AUTO_ACCEPTANCE_FIRST_REMINDER_DAY", DEFAULT_FIRST_REMINDER_DAY);
		Instant second = after(milestoneId, "AUTO_ACCEPTANCE_SECOND_REMINDER_DAY", DEFAULT_SECOND_REMINDER_DAY);
		int daysLate = this.jdbc.sql("SELECT coalesce(days_late, 0) FROM job.milestone WHERE id = :id")
			.param("id", milestoneId)
			.query(Integer.class)
			.single();
		return new Submitted(applied.transitionId(), this.numbers.number(jobId, milestoneId), applied.version(), checkBy,
				first, second, daysLate);
	}

	@Override
	@Transactional
	public boolean voidHandover(UUID jobId, UUID milestoneId, UUID handoverId) {
		return this.lifecycle
			.tryApplyMilestone(Transition.M3, jobId, milestoneId, Actor.SYSTEM, Trigger.SYSTEM, Map.of("handoverId", handoverId))
			.isPresent();
	}

	@Override
	@Transactional
	public Optional<Decided> autoAccept(UUID jobId, UUID milestoneId, UUID handoverId) {
		return this.lifecycle
			.tryApplyMilestone(Transition.M5, jobId, milestoneId, Actor.SYSTEM, Trigger.AUTO, Map.of("handoverId", handoverId))
			.map(applied -> new Decided(applied.transitionId(), this.numbers.number(jobId, milestoneId), applied.version()));
	}

	@Override
	@Transactional
	public Optional<Decided> acceptMerged(UUID jobId, UUID milestoneId, @Nullable UUID customerId, UUID handoverId) {
		Map<String, Object> input = Map.of("handoverId", handoverId, "merged", true);
		Optional<Applied> applied = customerId == null
				? this.lifecycle.tryApplyMilestone(Transition.M5, jobId, milestoneId, Actor.SYSTEM, Trigger.AUTO, input)
				: this.lifecycle.tryApplyMilestone(Transition.M5, jobId, milestoneId, Actor.user(customerId), Trigger.USER,
						input);
		return applied
			.map(done -> new Decided(done.transitionId(), this.numbers.number(jobId, milestoneId), done.version()));
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
	@Transactional(readOnly = true)
	public Optional<Returns> returns(UUID milestoneId) {
		return this.jdbc.sql("""
				SELECT j.id, m.handover_returns_used FROM job.milestone m JOIN job.job j ON j.id = m.job_id
				WHERE m.id = :id AND j.self_mentored
				""")
			.param("id", milestoneId)
			.query((rs, n) -> new Returns(this.rules.value(rs.getObject("id", UUID.class), M3Void.RETURN_RULE,
					M3Void.DEFAULT_RETURN_LIMIT), rs.getInt("handover_returns_used")))
			.optional()
			.filter(returns -> returns.limit() > 0);
	}

	@Override
	@Transactional
	public Decided returnHandover(UUID jobId, UUID milestoneId, UUID customerId, long expectedVersion, UUID handoverId,
			String reason) {
		Applied applied = this.lifecycle.applyMilestone(Transition.M3, jobId, milestoneId, Actor.user(customerId), Trigger.USER,
				expectedVersion, Map.of("handoverId", handoverId, "returnReason", reason));
		return new Decided(applied.transitionId(), this.numbers.number(jobId, milestoneId), applied.version());
	}

	@Override
	public Optional<LocalDate> reworkDueOn(UUID milestoneId) {
		return this.dueDates.reworkDueOn(milestoneId);
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
