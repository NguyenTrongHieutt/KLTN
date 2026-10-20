package vn.vibefix.job.internal;

import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.shared.PlatformCounts;

/** ADM-9 (FR-62, FR-35, FR-68): Jobs by state and by Job Type, Crews by size, overdue Milestones, and
 * acceptances by how they happened. */
@Component
class JobCounts implements PlatformCounts {

	private final JdbcClient jdbc;

	JobCounts(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	@Transactional(readOnly = true)
	public List<Count> counts() {
		List<Count> counts = new ArrayList<>();
		grouped(counts, "jobsByState", "SELECT state AS key, count(*) AS value FROM job.job GROUP BY state");
		grouped(counts, "jobsByType", """
				SELECT type AS key, count(*) AS value FROM job.job, unnest(job_types) AS type GROUP BY type
				""");
		grouped(counts, "crewsBySize", """
				SELECT CASE WHEN members >= 4 THEN '4+' ELSE members::text END AS key, count(*) AS value FROM (
				    SELECT j.id, count(r.id) FILTER (WHERE r.status = 'ACTIVE') AS members FROM job.job j
				    LEFT JOIN job.crew_row r ON r.job_id = j.id
				    WHERE j.state IN ('STARTING', 'ACTIVE', 'CLOSING') GROUP BY j.id) sized
				GROUP BY 1
				""");
		grouped(counts, "milestones", """
				SELECT 'OVERDUE' AS key, count(*) AS value FROM job.milestone
				WHERE removed_at IS NULL AND overdue_since IS NOT NULL AND state IN ('FUNDED', 'REVISION_REQUESTED')
				""");
		grouped(counts, "acceptances", """
				SELECT CASE WHEN auto_accepted THEN 'AUTOMATIC' ELSE 'MANUAL' END AS key, count(*) AS value FROM job.milestone
				WHERE removed_at IS NULL AND accepted_at IS NOT NULL GROUP BY 1
				""");
		return counts;
	}

	private void grouped(List<Count> counts, String group, String sql) {
		this.jdbc.sql(sql).query((rs, n) -> counts.add(new Count(group, rs.getString("key"), rs.getLong("value")))).list();
	}

}
