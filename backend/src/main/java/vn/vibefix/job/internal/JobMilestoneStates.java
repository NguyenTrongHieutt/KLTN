package vn.vibefix.job.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.job.MilestoneStates;

/** {@link MilestoneStates} over the live Milestones. */
@Component
class JobMilestoneStates implements MilestoneStates {

	private final JdbcClient jdbc;

	JobMilestoneStates(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, String> statesOf(UUID jobId) {
		Map<UUID, String> states = new HashMap<>();
		this.jdbc.sql("SELECT id, state FROM job.milestone WHERE job_id = :job AND removed_at IS NULL")
			.param("job", jobId)
			.query((rs, n) -> states.put(rs.getObject("id", UUID.class), rs.getString("state")))
			.list();
		return states;
	}

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, MilestoneDetail> detailsOf(UUID jobId) {
		Map<UUID, MilestoneDetail> details = new HashMap<>();
		this.jdbc.sql("""
				SELECT id, state, revision_rounds - revision_rounds_used AS left_rounds, revision_criteria, auto_accepted,
				       state = 'CANCELLED' AND funded_at IS NOT NULL AS refunded,
				       CASE WHEN state IN ('FUNDED', 'REVISION_REQUESTED') THEN overdue_since END AS overdue_since, due_on,
				       CASE WHEN state IN ('SUBMITTED', 'ACCEPTED', 'SETTLED') THEN days_late END AS days_late
				FROM job.milestone WHERE job_id = :job AND removed_at IS NULL
				""")
			.param("job", jobId)
			.query((rs, n) -> details.put(rs.getObject("id", UUID.class), new MilestoneDetail(rs.getString("state"),
					Math.max(0, rs.getInt("left_rounds")), (Integer) rs.getObject("revision_criteria"),
					rs.getBoolean("auto_accepted"), rs.getBoolean("refunded"), overdueSince(rs.getTimestamp("overdue_since")),
					rs.getObject("due_on", LocalDate.class), (Integer) rs.getObject("days_late"))))
			.list();
		return details;
	}

	private static @Nullable Instant overdueSince(@Nullable Timestamp at) {
		return at == null ? null : at.toInstant();
	}

}
