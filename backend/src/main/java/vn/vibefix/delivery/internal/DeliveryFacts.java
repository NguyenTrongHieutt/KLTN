package vn.vibefix.delivery.internal;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.delivery.HandoverFacts;

/** {@link HandoverFacts}: the distinct Milestones with at least one Milestone Handover. */
@Component
class DeliveryFacts implements HandoverFacts {

	private final JdbcClient jdbc;

	DeliveryFacts(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	@Transactional(readOnly = true)
	public int milestonesHandedOver(UUID jobId) {
		return this.jdbc.sql("SELECT count(DISTINCT milestone_id) FROM delivery.handover WHERE job_id = :job")
			.param("job", jobId)
			.query(Integer.class)
			.single();
	}

}
