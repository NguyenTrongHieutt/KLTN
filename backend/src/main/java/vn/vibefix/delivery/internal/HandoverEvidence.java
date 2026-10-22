package vn.vibefix.delivery.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.delivery.DeliveryEvidence;

/**
 * {@link DeliveryEvidence} over {@code delivery.handover}: the Mentor's criteria as reported
 * ({@code handover_criterion}) next to the Customer's checks ({@code handover_check}).
 */
@Component
class HandoverEvidence implements DeliveryEvidence {

	private final JdbcClient jdbc;

	HandoverEvidence(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	@Transactional(readOnly = true)
	public List<Report> reports(UUID jobId) {
		return this.jdbc.sql("""
				SELECT id, milestone_id, milestone_number, milestone_title, round, status, created_at, decided_at, auto,
				       voided_at, handover_commit
				FROM delivery.handover WHERE job_id = :job ORDER BY created_at, seq
				""").param("job", jobId).query((rs, n) -> {
			UUID handover = rs.getObject("id", UUID.class);
			return new Report(handover, rs.getObject("milestone_id", UUID.class), rs.getInt("milestone_number"),
					rs.getString("milestone_title"), rs.getInt("round"), rs.getString("status"),
					rs.getTimestamp("created_at").toInstant(), instant(rs.getTimestamp("decided_at")), rs.getBoolean("auto"),
					instant(rs.getTimestamp("voided_at")), rs.getString("handover_commit"), criteria(handover));
		}).list();
	}

	private List<CriterionStatus> criteria(UUID handoverId) {
		return this.jdbc.sql("""
				SELECT c.criterion_id, c.number, c.statement, c.met, c.note, k.met AS customer_met, k.comment
				FROM delivery.handover_criterion c
				LEFT JOIN delivery.handover_check k ON k.handover_id = c.handover_id AND k.criterion_id = c.criterion_id
				WHERE c.handover_id = :handover ORDER BY c.number
				""").param("handover", handoverId).query((rs, n) -> {
			boolean checked = rs.getObject("customer_met") != null;
			return new CriterionStatus(rs.getObject("criterion_id", UUID.class), rs.getInt("number"), rs.getString("statement"),
					rs.getBoolean("met"), rs.getString("note"), checked ? rs.getBoolean("customer_met") : null,
					rs.getString("comment"));
		}).list();
	}

	private static @Nullable Instant instant(@Nullable Timestamp at) {
		return at == null ? null : at.toInstant();
	}

}
