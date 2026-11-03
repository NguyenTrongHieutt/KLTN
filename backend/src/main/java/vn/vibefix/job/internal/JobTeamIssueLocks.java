package vn.vibefix.job.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.job.TeamIssueLocks;

/** {@link TeamIssueLocks} on the Job row lock that transitions take first (AD-4); it writes nothing. */
@Component
class JobTeamIssueLocks implements TeamIssueLocks {

	private final JdbcClient jdbc;

	private final Jobs jobs;

	JobTeamIssueLocks(JdbcClient jdbc, Jobs jobs) {
		this.jdbc = jdbc;
		this.jobs = jobs;
	}

	@Override
	@Transactional
	public Optional<TeamIssueJob> lockForTeamIssue(UUID jobId) {
		return this.jobs.lock(jobId)
			.map(job -> new TeamIssueJob(job.id(), job.productId(), job.title(), job.state().name(), job.mentorId()));
	}

	@Override
	@Transactional(readOnly = true)
	public List<UUID> crewJobsOf(UUID studentId) {
		return this.jdbc.sql("""
				SELECT job_id FROM job.crew_row WHERE member_account_id = :id AND activated_at IS NOT NULL
				GROUP BY job_id ORDER BY max(activated_at) DESC, job_id
				""").param("id", studentId).query(UUID.class).list();
	}

}
