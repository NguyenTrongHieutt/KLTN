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

}
