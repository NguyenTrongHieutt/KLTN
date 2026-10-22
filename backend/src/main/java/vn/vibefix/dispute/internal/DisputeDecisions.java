package vn.vibefix.dispute.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.dispute.DisputeQueue;
import vn.vibefix.dispute.internal.Disputes.Dispute;
import vn.vibefix.identity.AdministratorStepUp;
import vn.vibefix.job.MilestoneSettlements;
import vn.vibefix.job.MilestoneSettlements.Next;
import vn.vibefix.payment.Ledger;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;

/**
 * Taking and deciding Disputes (FR-54, AD-20, AD-31, SG-10): an Administrator takes a Dispute (another may
 * take it over), and only the assignee records a decision after a fresh step-up. The amounts must sum to
 * what the Held Milestone holds; "Tiếp tục" needs a Planned Milestone left. The decision is recorded and
 * audited with its reasoning and totals, and its execution starts at step 0.
 */
@Component
class DisputeDecisions implements DisputeQueue {

	record Decision(UUID disputeId, @Nullable UUID milestoneId, @Nullable Long releasableVnd, @Nullable Long releaseVnd,
			@Nullable Long refundVnd, String nextState, String reasoning, Instant decidedAt) {
	}

	record Execution(UUID disputeId, String status, int step, @Nullable String failure) {
	}

	/** How a decision ended: recorded, or refused by the step-up (thrown after commit). */
	sealed interface Outcome {

		record Recorded() implements Outcome {
		}

		record Refused(ApiException refusal, boolean endSession) implements Outcome {
		}

	}

	private final JdbcClient jdbc;

	private final Disputes disputes;

	private final MilestoneSettlements settlements;

	private final Ledger ledger;

	private final AdministratorStepUp stepUp;

	private final AuditLog auditLog;

	private final Clock clock;

	private final ObjectProvider<DisputeStatements> statements;

	DisputeDecisions(JdbcClient jdbc, Disputes disputes, MilestoneSettlements settlements, Ledger ledger, AdministratorStepUp stepUp,
			AuditLog auditLog, Clock clock, ObjectProvider<DisputeStatements> statements) {
		this.statements = statements;
		this.jdbc = jdbc;
		this.disputes = disputes;
		this.settlements = settlements;
		this.ledger = ledger;
		this.stepUp = stepUp;
		this.auditLog = auditLog;
		this.clock = clock;
	}

	@Override
	public List<Waiting> awaiting() {
		return this.jdbc.sql("""
				SELECT d.id, d.opened_at FROM dispute.dispute d
				WHERE d.status = 'OPEN' AND NOT EXISTS (SELECT 1 FROM dispute.decision x WHERE x.dispute_id = d.id)
				ORDER BY d.opened_at, d.id
				""").query((rs, n) -> new Waiting(rs.getObject("id", UUID.class), rs.getTimestamp("opened_at").toInstant())).list();
	}

	@Override
	public List<FailedExecution> executionsFailed() {
		return this.jdbc.sql("""
				SELECT d.id, d.job_id, x.step, x.failure, x.updated_at FROM dispute.execution x
				JOIN dispute.dispute d ON d.id = x.dispute_id
				WHERE x.status = 'EXECUTION_FAILED' ORDER BY x.updated_at, d.id
				""").query((rs, n) -> new FailedExecution(rs.getObject("id", UUID.class), rs.getObject("job_id", UUID.class),
				rs.getInt("step"), rs.getString("failure"), rs.getTimestamp("updated_at").toInstant())).list();
	}

	@Override
	public List<Waiting> teamIssuesEscalated() {
		return this.jdbc.sql("SELECT id, escalated_at FROM dispute.team_issue WHERE phase = 'ESCALATED' ORDER BY escalated_at, id")
			.query((rs, n) -> new Waiting(rs.getObject("id", UUID.class), rs.getTimestamp("escalated_at").toInstant()))
			.list();
	}

	/** "Nhận xử lý": the Administrator takes the Dispute, over another if need be. */
	@Transactional
	void take(UUID disputeId, UUID administrator) {
		Dispute dispute = this.disputes.find(disputeId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (decision(disputeId).isPresent()) {
			throw new ApiException(ErrorCode.CONFLICT, DisputeReason.ALREADY_DECIDED);
		}
		this.jdbc.sql("UPDATE dispute.dispute SET assigned_admin_id = :admin, assigned_at = :now WHERE id = :id")
			.param("id", disputeId)
			.param("admin", administrator)
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
		this.auditLog.record(AuditEntry.of(AuditActor.user(administrator), "dispute.dispute_taken",
				AuditObject.of("dispute.dispute", disputeId)).forJob(dispute.jobId()));
	}

	/**
	 * The assignee's decision, after the step-up, in one transaction with its execution row.
	 * @throws ApiException {@code FORBIDDEN NOT_ASSIGNED}; {@code CONFLICT ALREADY_DECIDED};
	 * {@code VALIDATION AMOUNTS_MISMATCH} or {@code NOTHING_TO_CONTINUE}; {@code VALIDATION}
	 */
	@Transactional
	Outcome decide(UUID disputeId, UUID administrator, @Nullable Long releaseVnd, @Nullable Long refundVnd, Next next,
			String reasoning, String stepUpCode) {
		Dispute dispute = this.disputes.find(disputeId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (!administrator.equals(assignee(disputeId))) {
			throw new ApiException(ErrorCode.FORBIDDEN, DisputeReason.NOT_ASSIGNED);
		}
		if (decision(disputeId).isPresent() || !"OPEN".equals(dispute.status())) {
			throw new ApiException(ErrorCode.CONFLICT, DisputeReason.ALREADY_DECIDED);
		}
		if (!this.statements.getObject().decidable(dispute)) {
			throw new ApiException(ErrorCode.GUARD_FAILED, DisputeReason.STATEMENT_PERIOD_OPEN,
					Map.of("until", dispute.statementDueAt().toString()));
		}
		String text = reasoning.strip();
		if (text.isEmpty() || text.length() > 4000) {
			throw new ApiException(ErrorCode.VALIDATION);
		}
		UUID milestone = dispute.milestoneId();
		Long releasable = milestone == null ? null : this.ledger.releasable(milestone);
		if (milestone != null && (releaseVnd == null || refundVnd == null || releaseVnd < 0 || refundVnd < 0
				|| releaseVnd + refundVnd != releasable)) {
			throw new ApiException(ErrorCode.VALIDATION, DisputeReason.AMOUNTS_MISMATCH,
					Map.of("releasableVnd", releasable));
		}
		if (next == Next.CONTINUE && !this.settlements.plannedRemains(dispute.jobId())) {
			throw new ApiException(ErrorCode.VALIDATION, DisputeReason.NOTHING_TO_CONTINUE);
		}
		AdministratorStepUp.Result stepUp = this.stepUp.check(administrator, stepUpCode);
		if (!stepUp.passed()) {
			return new Outcome.Refused(stepUp.refusal(), stepUp.endSession());
		}
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO dispute.decision (dispute_id, admin_account_id, milestone_id, releasable_vnd, release_vnd, refund_vnd,
				    next_state, reasoning, decided_at)
				VALUES (:id, :admin, :milestone, :releasable, :release, :refund, :next, :reasoning, :now)
				""")
			.param("id", disputeId)
			.param("admin", administrator)
			.param("milestone", milestone)
			.param("releasable", releasable)
			.param("release", milestone == null ? null : releaseVnd)
			.param("refund", milestone == null ? null : refundVnd)
			.param("next", next.name())
			.param("reasoning", text)
			.param("now", now)
			.update();
		this.jdbc.sql("INSERT INTO dispute.execution (dispute_id, status, step, updated_at) VALUES (:id, 'RUNNING', 0, :now)")
			.param("id", disputeId)
			.param("now", now)
			.update();
		this.auditLog.record(AuditEntry.of(AuditActor.user(administrator), "dispute.decision_recorded",
				AuditObject.of("dispute.dispute", disputeId)).forJob(dispute.jobId()));
		return new Outcome.Recorded();
	}

	@Nullable UUID assignee(UUID disputeId) {
		return this.jdbc.sql("SELECT assigned_admin_id FROM dispute.dispute WHERE id = :id")
			.param("id", disputeId)
			.query(UUID.class)
			.optional()
			.orElse(null);
	}

	Optional<Decision> decision(UUID disputeId) {
		return this.jdbc.sql("SELECT * FROM dispute.decision WHERE dispute_id = :id").param("id", disputeId).query((rs, n) -> {
			UUID milestone = rs.getObject("milestone_id", UUID.class);
			return new Decision(disputeId, milestone, milestone == null ? null : rs.getLong("releasable_vnd"),
					milestone == null ? null : rs.getLong("release_vnd"), milestone == null ? null : rs.getLong("refund_vnd"),
					rs.getString("next_state"), rs.getString("reasoning"), rs.getTimestamp("decided_at").toInstant());
		}).optional();
	}

	Optional<Execution> execution(UUID disputeId) {
		return this.jdbc.sql("SELECT * FROM dispute.execution WHERE dispute_id = :id")
			.param("id", disputeId)
			.query((rs, n) -> new Execution(disputeId, rs.getString("status"), rs.getInt("step"), rs.getString("failure")))
			.optional();
	}

}
