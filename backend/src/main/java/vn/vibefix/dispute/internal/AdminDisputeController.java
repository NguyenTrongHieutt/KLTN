package vn.vibefix.dispute.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import vn.vibefix.dispute.internal.DisputeDecisions.Decision;
import vn.vibefix.dispute.internal.DisputeDecisions.Execution;
import vn.vibefix.dispute.internal.DisputeDecisions.Outcome;
import vn.vibefix.dispute.internal.Disputes.Dispute;
import vn.vibefix.job.JobPosts;
import vn.vibefix.job.MilestoneSettlements;
import vn.vibefix.job.MilestoneSettlements.Next;
import vn.vibefix.payment.Ledger;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;

/**
 * ADM-4 (FR-54, AD-20, AD-31, UX-DR58): the Disputes, one Dispute with its Held Milestone and releasable
 * amount, "Nhận xử lý", the assignee's decision behind the step-up, its execution "Bước {k}/4", and a
 * retry of a failed execution. Totals only, never per-party parts. The other side's statements show, and
 * the decision waits for one or for the end of their period (Story 15.1); the list flags Disputes still
 * undecided 7 days after they opened.
 */
@RestController
@RequestMapping("/api/v1/admin/disputes")
@Access(Gate.ADMINISTRATOR)
class AdminDisputeController {

	/**
	 * @param executionStatus {@code RUNNING}, {@code DONE}, or {@code EXECUTION_FAILED}, once decided
	 * @param hasStatement "Đã có trình bày"
	 * @param undecidedLong open and opened more than 7 days ago: "chưa quyết sau 7 ngày"
	 */
	record AdminDisputeRow(UUID id, UUID jobId, String jobTitle, String openedBy, Instant openedAt, String status,
			boolean assigned, boolean assignedToMe, @Nullable String executionStatus, boolean hasStatement,
			boolean undecidedLong) {
	}

	/** @param side {@code CUSTOMER} or {@code MENTOR} */
	record AdminStatementView(String side, String text, Instant submittedAt) {
	}

	record HeldMilestoneView(UUID milestoneId, int number, String title, long releasableVnd) {
	}

	record DecisionView(@Nullable Long releaseVnd, @Nullable Long refundVnd, String nextState, String reasoning,
			Instant decidedAt) {
	}

	record ExecutionView(String status, int step, @Nullable String failure) {
	}

	/**
	 * @param decidable a statement arrived or the period passed, so the decision form is offered
	 */
	record AdminDisputeDetail(UUID id, UUID jobId, String jobTitle, String openedBy, String reason, String concern,
			Instant openedAt, String status, boolean assigned, boolean assignedToMe, @Nullable HeldMilestoneView held,
			boolean plannedRemains, @Nullable DecisionView decision, @Nullable ExecutionView execution,
			List<AdminStatementView> statements, Instant statementDueAt, boolean decidable) {
	}

	/** How long a Dispute waits before the list flags it (FR-52). */
	static final Duration UNDECIDED = Duration.ofDays(7);

	record DisputeDecisionRequest(@Nullable @Min(0) Long releaseVnd, @Nullable @Min(0) Long refundVnd,
			@NotNull Next nextState, @NotBlank @Size(max = 4000) String reasoning,
			@NotBlank @Pattern(regexp = "^[0-9]{6}$") String stepUpCode) {
	}

	private final Disputes disputes;

	private final DisputeDecisions decisions;

	private final DisputeExecution execution;

	private final JobPosts posts;

	private final MilestoneSettlements settlements;

	private final Ledger ledger;

	private final JdbcClient jdbc;

	private final CurrentCaller callers;

	private final DisputeStatements statements;

	private final Clock clock;

	AdminDisputeController(Disputes disputes, DisputeDecisions decisions, DisputeExecution execution, JobPosts posts,
			MilestoneSettlements settlements, Ledger ledger, JdbcClient jdbc, CurrentCaller callers,
			DisputeStatements statements, Clock clock) {
		this.statements = statements;
		this.clock = clock;
		this.disputes = disputes;
		this.decisions = decisions;
		this.execution = execution;
		this.posts = posts;
		this.settlements = settlements;
		this.ledger = ledger;
		this.jdbc = jdbc;
		this.callers = callers;
	}

	/** The open Disputes, oldest first, then the decided ones, most recent first. */
	@GetMapping
	List<AdminDisputeRow> disputes() {
		UUID me = this.callers.get().requireAccountId();
		return this.jdbc.sql("""
				SELECT id FROM dispute.dispute ORDER BY status = 'OPEN' DESC,
				    CASE WHEN status = 'OPEN' THEN opened_at END ASC, opened_at DESC, id
				LIMIT 200
				""").query(UUID.class).list().stream().map(id -> {
			Dispute dispute = this.disputes.find(id).orElseThrow();
			UUID assignee = this.decisions.assignee(id);
			return new AdminDisputeRow(id, dispute.jobId(), title(dispute.jobId()), dispute.openedBy().name(),
					dispute.openedAt(), dispute.status(), assignee != null, me.equals(assignee),
					this.decisions.execution(id).map(Execution::status).orElse(null), !this.statements.of(id).isEmpty(),
					"OPEN".equals(dispute.status()) && dispute.openedAt().isBefore(this.clock.instant().minus(UNDECIDED)));
		}).toList();
	}

	@GetMapping("/{disputeId}")
	AdminDisputeDetail dispute(@PathVariable UUID disputeId) {
		return detail(disputeId, this.callers.get().requireAccountId());
	}

	/** "Nhận xử lý": the viewer takes it, over another Administrator if need be. */
	@PostMapping("/{disputeId}/take")
	AdminDisputeDetail take(@PathVariable UUID disputeId) {
		UUID me = this.callers.get().requireAccountId();
		this.decisions.take(disputeId, me);
		return detail(disputeId, me);
	}

	/** The assignee's decision after the step-up; its execution runs at once. */
	@PostMapping("/{disputeId}/decision")
	AdminDisputeDetail decide(@PathVariable UUID disputeId, @Valid @RequestBody DisputeDecisionRequest body,
			HttpServletRequest request) {
		UUID me = this.callers.get().requireAccountId();
		Outcome outcome = this.decisions.decide(disputeId, me, body.releaseVnd(), body.refundVnd(), body.nextState(),
				body.reasoning(), body.stepUpCode());
		if (outcome instanceof Outcome.Refused refused) {
			if (refused.endSession()) {
				SecurityContextHolder.clearContext();
				HttpSession session = request.getSession(false);
				if (session != null) {
					session.invalidate();
				}
			}
			throw refused.refusal();
		}
		this.execution.run(disputeId);
		return detail(disputeId, me);
	}

	/** "Thử lại": the steps a failed execution has left. */
	@PostMapping("/{disputeId}/retry")
	AdminDisputeDetail retry(@PathVariable UUID disputeId) {
		UUID me = this.callers.get().requireAccountId();
		this.disputes.find(disputeId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		Execution current = this.decisions.execution(disputeId)
			.filter(found -> "EXECUTION_FAILED".equals(found.status()))
			.orElseThrow(() -> new ApiException(ErrorCode.CONFLICT, DisputeReason.NOT_FAILED));
		this.jdbc.sql("UPDATE dispute.execution SET status = 'RUNNING', failure = NULL WHERE dispute_id = :id")
			.param("id", current.disputeId())
			.update();
		this.execution.run(disputeId);
		return detail(disputeId, me);
	}

	private AdminDisputeDetail detail(UUID disputeId, UUID me) {
		Dispute dispute = this.disputes.find(disputeId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		UUID assignee = this.decisions.assignee(disputeId);
		Decision decision = this.decisions.decision(disputeId).orElse(null);
		HeldMilestoneView held = dispute.milestoneId() == null ? null
				: this.settlements.milestone(dispute.jobId(), dispute.milestoneId())
					.map(milestone -> new HeldMilestoneView(milestone.milestoneId(), milestone.number(), milestone.title(),
							decision != null && decision.releasableVnd() != null ? decision.releasableVnd()
									: this.ledger.releasable(milestone.milestoneId())))
					.orElse(null);
		return new AdminDisputeDetail(disputeId, dispute.jobId(), title(dispute.jobId()), dispute.openedBy().name(),
				dispute.reason(), dispute.concern(), dispute.openedAt(), dispute.status(), assignee != null,
				me.equals(assignee), held, this.settlements.plannedRemains(dispute.jobId()),
				decision == null ? null
						: new DecisionView(decision.releaseVnd(), decision.refundVnd(), decision.nextState(), decision.reasoning(),
								decision.decidedAt()),
				this.decisions.execution(disputeId)
					.map(found -> new ExecutionView(found.status(), found.step(), found.failure()))
					.orElse(null),
				this.statements.of(disputeId)
					.stream()
					.map(statement -> new AdminStatementView(statement.side().name(), statement.text(), statement.submittedAt()))
					.toList(),
				dispute.statementDueAt(), this.statements.decidable(dispute));
	}

	private String title(UUID jobId) {
		return this.posts.post(jobId).map(JobPosts.JobPostState::title).orElse("");
	}

}
