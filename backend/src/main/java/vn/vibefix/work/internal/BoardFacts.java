package vn.vibefix.work.internal;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.work.WorkFacts;

/** {@link WorkFacts} from {@code work.work_item} and its history. */
@Component
class BoardFacts implements WorkFacts {

	private final JdbcClient jdbc;

	BoardFacts(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, Integer> approvedItems(UUID jobId) {
		Map<UUID, Integer> counts = new HashMap<>();
		this.jdbc.sql("""
				SELECT w.assignee_account_id, count(*) AS n FROM work.work_item w
				WHERE w.job_id = :job AND w.status = 'DONE' AND w.assignee_account_id IS NOT NULL
				  AND (SELECT h.action FROM work.work_item_history h
				       WHERE h.work_item_id = w.id AND h.action IN ('SUBMITTED', 'APPROVED', 'RETURNED')
				       ORDER BY h.seq DESC LIMIT 1) = 'APPROVED'
				GROUP BY w.assignee_account_id
				""").param("job", jobId).query((rs, n) -> {
			counts.put(rs.getObject("assignee_account_id", UUID.class), rs.getInt("n"));
			return null;
		}).list();
		return counts;
	}

	@Override
	@Transactional(readOnly = true)
	public int reviewsWritten(UUID jobId, UUID accountId) {
		return this.jdbc.sql("""
				SELECT count(*) FROM work.work_item_history
				WHERE job_id = :job AND actor_account_id = :account AND action IN ('APPROVED', 'RETURNED')
				""").param("job", jobId).param("account", accountId).query(Integer.class).single();
	}

}
