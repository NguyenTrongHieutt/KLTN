package vn.vibefix.dispute.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import vn.vibefix.dispute.internal.Disputes.Dispute;
import vn.vibefix.dispute.internal.Disputes.Side;
import vn.vibefix.job.Crews.Relationship;
import vn.vibefix.job.JobPosts;
import vn.vibefix.job.JobPosts.JobPostState;
import vn.vibefix.job.JobRole;
import vn.vibefix.job.JobRoles;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;

/**
 * JOB-1's Dispute (FR-52, UX-DR22, UX-DR56): the Customer and the Mentor open one, and with the active
 * Crew they read the open one, which drives the Frozen banner. The other side sends its statement in the
 * period the Dispute copied (Story 15.1), and both sides read the statements. Everyone else finds nothing.
 */
@RestController
@Access(Gate.SIGNED_IN)
class DisputeController {

	/** @param side {@code CUSTOMER} or {@code MENTOR} */
	record DisputeStatementView(String side, boolean mine, String text, Instant submittedAt) {
	}

	/**
	 * @param concern {@code HELD_MILESTONE} or {@code NEXT_STATE}
	 * @param statements what each side sent; the Crew reads none
	 * @param canState the reader is the other side and has not sent one yet
	 */
	record OpenDisputeView(UUID id, String openedBy, boolean mine, String reason, String concern,
			@Nullable UUID milestoneId, Instant openedAt, Instant statementDueAt, List<DisputeStatementView> statements,
			boolean canState) {
	}

	record DisputeStatementRequest(@NotBlank @Size(max = 4000) String text) {
	}

	/** The latest decision, totals only (AD-20). */
	record DecidedDisputeView(UUID id, String nextState, String reasoning, @Nullable Long releaseVnd,
			@Nullable Long refundVnd, Instant decidedAt) {
	}

	/** @param canOpen the viewer may open one now */
	record JobDisputeView(UUID jobId, String audience, boolean canOpen, @Nullable OpenDisputeView open,
			@Nullable DecidedDisputeView decided) {
	}

	record OpenDisputeRequest(@NotBlank @Size(max = 2000) String reason) {
	}

	private record Viewer(JobPostState job, UUID accountId, @Nullable Side side) {
	}

	private final Disputes disputes;

	private final DisputeDecisions decisions;

	private final JobPosts posts;

	private final JobRoles roles;

	private final CurrentCaller callers;

	private final DisputeStatements statements;

	DisputeController(Disputes disputes, DisputeDecisions decisions, JobPosts posts, JobRoles roles,
			CurrentCaller callers, DisputeStatements statements) {
		this.statements = statements;
		this.disputes = disputes;
		this.decisions = decisions;
		this.posts = posts;
		this.roles = roles;
		this.callers = callers;
	}

	@GetMapping("/api/v1/boards/{boardId}/dispute")
	JobDisputeView dispute(@PathVariable("boardId") UUID jobId) {
		return view(viewer(jobId, this.callers.get()));
	}

	/** "Mở tranh chấp", through the Open-Dispute dialog. */
	@PostMapping("/api/v1/boards/{boardId}/disputes")
	@ResponseStatus(HttpStatus.CREATED)
	JobDisputeView open(@PathVariable("boardId") UUID jobId, @Valid @RequestBody OpenDisputeRequest body) {
		Viewer viewer = viewer(jobId, this.callers.get());
		if (viewer.side() == null) {
			throw new ApiException(ErrorCode.FORBIDDEN, DisputeReason.MENTOR_ONLY);
		}
		this.disputes.open(jobId, viewer.accountId(), viewer.side(), body.reason());
		return view(viewer(jobId, this.callers.get()));
	}

	/** "Gửi trình bày": the other side's one statement (Story 15.1). */
	@PostMapping("/api/v1/boards/{boardId}/dispute/statement")
	JobDisputeView statement(@PathVariable("boardId") UUID jobId, @Valid @RequestBody DisputeStatementRequest body) {
		Viewer viewer = viewer(jobId, this.callers.get());
		if (viewer.side() == null) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		this.statements.submit(jobId, viewer.accountId(), viewer.side(), body.text());
		return view(viewer(jobId, this.callers.get()));
	}

	/** The Customer, the Mentor, or an active Crew member (side empty), by role in the Job, once it has a Mentor. */
	private Viewer viewer(UUID jobId, Caller caller) {
		JobPostState job = this.posts.post(jobId)
			.filter(found -> found.mentorId() != null)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		UUID account = caller.accountId();
		if (account == null) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		JobRole role = this.roles.roleOf(jobId, caller);
		if (role.isCustomer()) {
			return new Viewer(job, account, Side.CUSTOMER);
		}
		if (role.isMentor()) {
			return new Viewer(job, account, Side.MENTOR);
		}
		if (role.crewKind() == Relationship.ACTIVE) {
			return new Viewer(job, account, null);
		}
		throw new ApiException(ErrorCode.NOT_FOUND);
	}

	private JobDisputeView view(Viewer viewer) {
		Dispute open = this.disputes.open(viewer.job().jobId()).orElse(null);
		boolean canOpen = viewer.side() != null && open == null && Disputes.DISPUTABLE.contains(viewer.job().state());
		DecidedDisputeView decided = open != null ? null
				: this.disputes.latestDecided(viewer.job().jobId())
					.flatMap(dispute -> this.decisions.decision(dispute.id()))
					.map(decision -> new DecidedDisputeView(decision.disputeId(), decision.nextState(), decision.reasoning(),
							decision.releaseVnd(), decision.refundVnd(), decision.decidedAt()))
					.orElse(null);
		List<DisputeStatementView> statements = open == null || viewer.side() == null ? List.of()
				: this.statements.of(open.id())
					.stream()
					.map(statement -> new DisputeStatementView(statement.side().name(), statement.side() == viewer.side(),
							statement.text(), statement.submittedAt()))
					.toList();
		boolean canState = open != null && DisputeStatements.mayState(open, viewer.side())
				&& statements.stream().noneMatch(DisputeStatementView::mine);
		return new JobDisputeView(viewer.job().jobId(), viewer.side() == null ? "CREW" : viewer.side().name(), canOpen,
				open == null ? null : new OpenDisputeView(open.id(), open.openedBy().name(), open.openedBy() == viewer.side(),
						open.reason(), open.concern(), open.milestoneId(), open.openedAt(), open.statementDueAt(), statements,
						canState),
				decided);
	}

}
