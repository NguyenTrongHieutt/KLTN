package vn.vibefix.job.internal;

import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.shared.PlatformCounts;

/** ADM-9 (FR-62, FR-35, FR-68): Jobs by state and by Job Type, Crews by size, overdue Milestones, accepted
 * Milestones by whether a Revision Round was used, and acceptances by how they happened. */
@Component
class JobCounts implements PlatformCounts {

	/**
	 * FR-62's on-time formula, defined once (Story 17.2, X12; FR-87, AD-48 since Story 25.1): the first handover of
	 * {@code job.milestone m} came by the end of the due date in force at that moment. M2 records it once as
	 * {@code first_days_late}, so a later handover or a due date a rework moved never changes the answer.
	 */
	static final String ON_TIME = "m.first_days_late = 0";

	/** The Milestones {@link #ON_TIME} counts over: live, accepted, and with a due date. */
	static final String ACCEPTED_DATED = "m.removed_at IS NULL AND m.accepted_at IS NOT NULL AND m.due_on IS NOT NULL";

	/** The accepted Milestones FR-62's first-pass figures count over: live and accepted (Story 24.2). */
	static final String ACCEPTED = "removed_at IS NULL AND accepted_at IS NOT NULL";

	private final JdbcClient jdbc;

	JobCounts(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	@Transactional(readOnly = true)
	public List<Count> counts() {
		List<Count> counts = new ArrayList<>();
		grouped(counts, "jobsByState", "SELECT state AS key, count(*) AS value FROM job.job WHERE product_deleted_at IS NULL GROUP BY state");
		grouped(counts, "jobsByType", """
				SELECT type AS key, count(*) AS value FROM job.job, unnest(job_types) AS type WHERE product_deleted_at IS NULL GROUP BY type
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
		// FR-62, Story 24.2: what the Board's approved Work Items used to say, over the Milestones themselves.
		// revision_rounds_used counts the Customer's revision requests alone (M4RequestRevision); M3Void sends a
		// Milestone back without using a round, so FIRST_PASS means "accepted without a Revision Round", which is
		// what FR-62 asks for and what the label says -- never "accepted on the very first handover".
		grouped(counts, "acceptedMilestones", "SELECT CASE WHEN revision_rounds_used = 0 THEN 'FIRST_PASS'"
				+ " ELSE 'AFTER_REVISION' END AS key, count(*) AS value FROM job.milestone WHERE " + ACCEPTED + " GROUP BY 1");
		// Story 17.2's FR-62 formulas (SPEC.md Open Questions): completion counts the Jobs that ended after
		// their Agreement was confirmed; on time means the accepted handover was submitted by the end of the
		// due date in Vietnam.
		rate(counts, "rates", "COMPLETION", """
				SELECT count(*) FILTER (WHERE j.state = 'CLOSED') AS part,
				       count(*) FILTER (WHERE j.state = 'CLOSED' OR (j.state = 'CANCELLED'
				           AND EXISTS (SELECT 1 FROM job.confirmed_agreement c WHERE c.job_id = j.id))) AS whole
				FROM job.job j WHERE j.product_deleted_at IS NULL
				""");
		rate(counts, "rates", "ON_TIME", "SELECT count(*) FILTER (WHERE " + ON_TIME + ") AS part, count(*) AS whole"
				+ " FROM job.milestone m WHERE " + ACCEPTED_DATED);
		rate(counts, "acceptedMilestones", "FIRST_PASS_SHARE",
				"SELECT count(*) FILTER (WHERE revision_rounds_used = 0) AS part, count(*) AS whole FROM job.milestone"
						+ " WHERE " + ACCEPTED);
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
