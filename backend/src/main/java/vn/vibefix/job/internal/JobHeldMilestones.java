package vn.vibefix.job.internal;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.job.HeldMilestones;
import vn.vibefix.job.MilestoneStates;

/** {@link HeldMilestones} and {@link MilestoneStates} over the live Milestones and {@link MilestoneState#held()}. */
@Component
class JobHeldMilestones implements HeldMilestones, MilestoneStates {

	private final JdbcClient jdbc;

	JobHeldMilestones(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	@Transactional(readOnly = true)
	public Set<UUID> heldOf(UUID jobId) {
		return new HashSet<>(this.jdbc.sql("""
				SELECT id FROM job.milestone WHERE job_id = :job AND removed_at IS NULL AND state IN (:held)
				""")
			.param("job", jobId)
			.param("held", MilestoneState.held().stream().map(Enum::name).toList())
			.query(UUID.class)
			.list());
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
				SELECT id, state, revision_rounds - revision_rounds_used AS left_rounds, revision_criteria, auto_accepted
				FROM job.milestone WHERE job_id = :job AND removed_at IS NULL
				""")
			.param("job", jobId)
			.query((rs, n) -> details.put(rs.getObject("id", UUID.class), new MilestoneDetail(rs.getString("state"),
					Math.max(0, rs.getInt("left_rounds")), (Integer) rs.getObject("revision_criteria"),
					rs.getBoolean("auto_accepted"))))
			.list();
		return details;
	}

}
