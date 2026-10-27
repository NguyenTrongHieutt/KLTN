package vn.vibefix.job.internal;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.job.TeamIssueLocks;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;

/** {@link TeamIssueLocks} on the Job row lock that transitions take first (AD-4); it writes nothing. */
@Component
class JobTeamIssueLocks implements TeamIssueLocks {

	/** The Held Milestone states (FR-41). */
	private static final Set<String> HELD = Set.of("FUNDED", "REVISION_REQUESTED", "SUBMITTED");

	private final JdbcClient jdbc;

	private final Jobs jobs;

	JobTeamIssueLocks(JdbcClient jdbc, Jobs jobs) {
		this.jdbc = jdbc;
		this.jobs = jobs;
	}

	@Override
	@Transactional
	public Optional<TeamIssueJob> lockForTeamIssue(UUID jobId, @Nullable UUID milestoneId, boolean requireHeld) {
		Jobs.Job job = this.jobs.lock(jobId).orElse(null);
		if (job == null) {
			return Optional.empty();
		}
		Integer number = null;
		boolean held = false;
		if (milestoneId != null) {
			Milestone milestone = this.jdbc.sql("""
					SELECT id, state, n FROM (
					    SELECT id, state, row_number() OVER (ORDER BY position, created_at, id) AS n
					    FROM job.milestone WHERE job_id = :job AND removed_at IS NULL) m
					WHERE id = :id
					""")
				.param("job", jobId)
				.param("id", milestoneId)
				.query((rs, n) -> new Milestone(rs.getString("state"), rs.getInt("n")))
				.optional()
				.orElse(null);
			if (milestone == null && !requireHeld) {
				return Optional.empty();
			}
			number = milestone == null ? null : milestone.number();
			held = milestone != null && HELD.contains(milestone.state());
		}
		if (requireHeld && !held) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.MILESTONE_NOT_HELD);
		}
		return Optional.of(new TeamIssueJob(job.id(), job.productId(), job.title(), job.state().name(), job.mentorId(), number,
				held));
	}

	@Override
	@Transactional(readOnly = true)
	public List<UUID> crewJobsOf(UUID studentId) {
		return this.jdbc.sql("""
				SELECT job_id FROM job.crew_row WHERE member_account_id = :id AND activated_at IS NOT NULL
				GROUP BY job_id ORDER BY max(activated_at) DESC, job_id
				""").param("id", studentId).query(UUID.class).list();
	}

	private record Milestone(String state, int number) {
	}

}
