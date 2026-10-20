package vn.vibefix.team.internal;

import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.shared.PlatformCounts;

/** ADM-9 (FR-62): open Teams by their number of current members. */
@Component
class TeamCounts implements PlatformCounts {

	private final JdbcClient jdbc;

	TeamCounts(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	@Transactional(readOnly = true)
	public List<Count> counts() {
		List<Count> counts = new ArrayList<>();
		grouped(counts, "teamsBySize", """
				SELECT CASE WHEN members >= 4 THEN '4+' ELSE members::text END AS key, count(*) AS value FROM (
				    SELECT t.id, count(m.id) AS members FROM team.team t
				    LEFT JOIN team.membership m ON m.team_id = t.id AND m.left_at IS NULL
				    WHERE t.status = 'OPEN' GROUP BY t.id) sized
				GROUP BY 1
				""");
		return counts;
	}

	private void grouped(List<Count> counts, String group, String sql) {
		this.jdbc.sql(sql).query((rs, n) -> counts.add(new Count(group, rs.getString("key"), rs.getLong("value")))).list();
	}

}
