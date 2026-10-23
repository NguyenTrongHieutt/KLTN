package vn.vibefix.work.internal;

import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.shared.PlatformCounts;

/** ADM-9 (FR-62): approved Work Items, on their first submission or after being returned. */
@Component
class WorkCounts implements PlatformCounts {

	private final JdbcClient jdbc;

	WorkCounts(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	@Transactional(readOnly = true)
	public List<Count> counts() {
		List<Count> counts = new ArrayList<>();
		grouped(counts, "workItemReviews", """
				SELECT CASE WHEN returned THEN 'RETURNED' ELSE 'FIRST_PASS' END AS key, count(*) AS value FROM (
				    SELECT work_item_id, bool_or(action = 'RETURNED') AS returned FROM work.work_item_history
				    GROUP BY work_item_id HAVING bool_or(action = 'APPROVED')) reviewed
				GROUP BY 1
				""");
		this.jdbc.sql("""
				SELECT count(*) FILTER (WHERE NOT returned) AS part, count(*) AS whole FROM (
				    SELECT work_item_id, bool_or(action = 'RETURNED') AS returned FROM work.work_item_history
				    GROUP BY work_item_id HAVING bool_or(action = 'APPROVED')) reviewed
				""")
			.query((rs, n) -> PlatformCounts.rate("workItemReviews", "FIRST_PASS_SHARE", rs.getLong("part"), rs.getLong("whole")))
			.single()
			.ifPresent(counts::add);
		return counts;
	}

	private void grouped(List<Count> counts, String group, String sql) {
		this.jdbc.sql(sql).query((rs, n) -> counts.add(new Count(group, rs.getString("key"), rs.getLong("value")))).list();
	}

}
