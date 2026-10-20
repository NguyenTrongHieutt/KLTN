package vn.vibefix.job.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.job.MilestoneSettlements;
import vn.vibefix.job.internal.JobLifecycle.Applied;
import vn.vibefix.job.internal.JobLifecycle.Planned;
import vn.vibefix.job.internal.Jobs.Job;

/** {@link MilestoneSettlements} through {@link JobLifecycle} cascades triggered by the DECISION. */
@Component
class JobSettlements implements MilestoneSettlements {

	private final JdbcClient jdbc;

	private final Jobs jobs;

	private final JobLifecycle lifecycle;

	JobSettlements(JdbcClient jdbc, Jobs jobs, JobLifecycle lifecycle) {
		this.jdbc = jdbc;
		this.jobs = jobs;
		this.lifecycle = lifecycle;
	}

	@Override
	@Transactional
	public UUID settle(UUID jobId, UUID milestoneId, long releaseVnd, long refundVnd) {
		List<Planned> cascade = List.of(Planned.milestone(Transition.M8, milestoneId,
				Map.of(M8Settle.RELEASE, releaseVnd, M8Settle.REFUND, refundVnd), Trigger.DECISION));
		return apply(jobId, cascade).getFirst().transitionId();
	}

	@Override
	@Transactional
	public void next(UUID jobId, Next next) {
		if (next == Next.CONTINUE) {
			return;
		}
		Job job = this.jobs.lock(jobId).orElseThrow();
		List<Planned> cascade = new ArrayList<>();
		boolean accepted = false;
		for (Map<String, Object> row : this.jdbc.sql("""
				SELECT id, state FROM job.milestone WHERE job_id = :job AND removed_at IS NULL ORDER BY position, created_at, id
				""").param("job", jobId).query().listOfRows()) {
			String state = (String) row.get("state");
			if ("PLANNED".equals(state)) {
				cascade.add(Planned.milestone(Transition.M6, (UUID) row.get("id"), Map.of(), Trigger.DECISION));
			}
			else if ("ACCEPTED".equals(state) || "SETTLED".equals(state)) {
				accepted = true;
			}
		}
		if (next == Next.CANCEL && !accepted) {
			cascade.add(Planned.job(Transition.J10, Trigger.DECISION));
		}
		else if (job.state() == JobState.ACTIVE) {
			Map<String, Object> input = next == Next.CANCEL ? Map.of(Cancellations.INPUT, true) : Map.of();
			cascade.add(new Planned(Transition.J8, null, input, Trigger.DECISION));
			if (!job.code()) {
				cascade.add(new Planned(Transition.J9, null, input, Trigger.SYSTEM));
			}
		}
		else if (next == Next.CANCEL) {
			cascade.add(Planned.job(Transition.J10, Trigger.DECISION));
		}
		if (!cascade.isEmpty()) {
			apply(jobId, cascade);
		}
	}

	@Override
	public boolean plannedRemains(UUID jobId) {
		return this.jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM job.milestone WHERE job_id = :job AND removed_at IS NULL AND state = 'PLANNED')
				""").param("job", jobId).query(Boolean.class).single();
	}

	@Override
	public java.util.Optional<MilestoneName> milestone(UUID jobId, UUID milestoneId) {
		return this.jdbc.sql("""
				SELECT id, number, title, state FROM (
				    SELECT id, row_number() OVER (ORDER BY position) AS number, title, state FROM job.milestone
				    WHERE job_id = :job AND removed_at IS NULL) m
				WHERE id = :id
				""")
			.param("job", jobId)
			.param("id", milestoneId)
			.query((rs, n) -> new MilestoneName(rs.getObject("id", UUID.class), rs.getInt("number"), rs.getString("title"),
					rs.getString("state")))
			.optional();
	}

	private List<Applied> apply(UUID jobId, List<Planned> cascade) {
		var refusal = this.lifecycle.refusal(jobId, Trigger.DECISION, cascade);
		if (refusal != null) {
			throw refusal;
		}
		return this.lifecycle.tryApplyCascade(jobId, Trigger.DECISION, cascade)
			.orElseThrow(() -> new IllegalStateException("The decision on " + jobId + " passed its checks but did not apply"));
	}

}
