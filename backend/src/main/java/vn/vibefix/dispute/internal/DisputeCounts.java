package vn.vibefix.dispute.internal;

import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.shared.PlatformCounts;

/** ADM-9 (FR-62): Disputes by outcome, open ones counted apart. */
@Component
class DisputeCounts implements PlatformCounts {

	private final JdbcClient jdbc;

	DisputeCounts(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	@Transactional(readOnly = true)
	public List<Count> counts() {
		List<Count> counts = new ArrayList<>();
		grouped(counts, "disputesByOutcome", """
				SELECT coalesce(x.next_state, 'OPEN') AS key, count(*) AS value FROM dispute.dispute d
				LEFT JOIN dispute.decision x ON x.dispute_id = d.id GROUP BY 1
				""");
		grouped(counts, "teamIssuesByOutcome", """
				SELECT coalesce(ending, CASE WHEN escalated_at IS NOT NULL THEN 'ESCALATED' ELSE 'OPEN' END) AS key,
				       count(*) AS value
				FROM dispute.team_issue GROUP BY 1
				""");
		return counts;
	}

	private void grouped(List<Count> counts, String group, String sql) {
		this.jdbc.sql(sql).query((rs, n) -> counts.add(new Count(group, rs.getString("key"), rs.getLong("value")))).list();
	}

}
