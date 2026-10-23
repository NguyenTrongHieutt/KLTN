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
		// Story 17.2's FR-62 formulas (SPEC.md Open Questions): completion counts the Jobs that ended after
		// their Agreement was confirmed; on time means the accepted handover was submitted by the end of the
		// due date in Vietnam.
		rate(counts, "rates", "COMPLETION", """
				SELECT count(*) FILTER (WHERE j.state = 'CLOSED') AS part,
				       count(*) FILTER (WHERE j.state = 'CLOSED' OR (j.state = 'CANCELLED'
				           AND EXISTS (SELECT 1 FROM job.confirmed_agreement c WHERE c.job_id = j.id))) AS whole
				FROM job.job j
				""");
		rate(counts, "rates", "ON_TIME", """
				SELECT count(*) FILTER (WHERE submitted_at < ((due_on + 1)::timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh')) AS part,
				       count(*) AS whole
				FROM job.milestone WHERE removed_at IS NULL AND accepted_at IS NOT NULL AND due_on IS NOT NULL
				""");
		rate(counts, "acceptances", "MANUAL_SHARE", """
				SELECT count(*) FILTER (WHERE NOT auto_accepted) AS part, count(*) AS whole FROM job.milestone
				WHERE removed_at IS NULL AND accepted_at IS NOT NULL
				""");
		rate(counts, "acceptances", "AUTOMATIC_SHARE", """
				SELECT count(*) FILTER (WHERE auto_accepted) AS part, count(*) AS whole FROM job.milestone
				WHERE removed_at IS NULL AND accepted_at IS NOT NULL
				""");
		return counts;
	}

	private void rate(List<Count> counts, String group, String key, String sql) {
		this.jdbc.sql(sql)
			.query((rs, n) -> PlatformCounts.rate(group, key, rs.getLong("part"), rs.getLong("whole")))
			.single()
			.ifPresent(counts::add);
	}

	private void grouped(List<Count> counts, String group, String sql) {
		this.jdbc.sql(sql).query((rs, n) -> counts.add(new Count(group, rs.getString("key"), rs.getLong("value")))).list();
	}

}
