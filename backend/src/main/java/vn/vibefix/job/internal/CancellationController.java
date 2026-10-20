package vn.vibefix.job.internal;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import vn.vibefix.job.Crews.Relationship;
import vn.vibefix.job.internal.Cancellations.Proposal;
import vn.vibefix.job.internal.Cancellations.Scope;
import vn.vibefix.job.internal.ChangeRequests.Party;
import vn.vibefix.job.internal.Jobs.Job;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;
import vn.vibefix.shared.Role;

/**
 * JOB-1's "Hủy công việc" (FR-40, UX-DR16): the Customer or the Mentor proposes, the other side confirms
 * on the loaded Job version, and the proposer may withdraw; the active Crew reads the proposal. Everyone
 * else finds nothing.
 */
@RestController
@Access(Gate.SIGNED_IN)
class CancellationController {

	/** @param status {@code OPEN}, {@code CONFIRMED}, or {@code WITHDRAWN} */
	record CancellationProposalView(UUID id, String scope, String reason, String proposer, boolean mine, String status,
			Instant answerBy, boolean unanswered, Instant createdAt, @Nullable Instant decidedAt) {
	}

	/**
	 * @param held a Milestone holds money, which the confirmation refunds in full
	 * @param canPropose the Job can be cancelled and nothing is open
	 */
	record CancellationView(UUID jobId, String audience, String state, long version, boolean canPropose, boolean held,
			@Nullable CancellationProposalView proposal) {
	}

	record CancellationRequest(@NotNull Scope scope, @NotBlank @Size(max = 2000) String reason, @NotNull Long version) {
	}

	record CancellationVersionRequest(@NotNull Long version) {
	}

	private record Viewer(Job job, UUID accountId, @Nullable Party party) {
	}

	private static final Set<JobState> VISIBLE = EnumSet.of(JobState.AGREEMENT, JobState.STARTING, JobState.ACTIVE,
			JobState.CLOSING, JobState.CLOSED, JobState.CANCELLED);

	private final Cancellations cancellations;

	private final ClosureProposals closures;

	private final CrewService crews;

	private final JdbcClient jdbc;

	private final CurrentCaller callers;

	CancellationController(Cancellations cancellations, ClosureProposals closures, CrewService crews, JdbcClient jdbc,
			CurrentCaller callers) {
		this.cancellations = cancellations;
		this.closures = closures;
		this.crews = crews;
		this.jdbc = jdbc;
		this.callers = callers;
	}

	@GetMapping("/api/v1/boards/{boardId}/cancellation")
	CancellationView cancellation(@PathVariable("boardId") UUID jobId) {
		return view(viewer(jobId, this.callers.get()));
	}

	@PostMapping("/api/v1/boards/{boardId}/cancellation")
	CancellationView propose(@PathVariable("boardId") UUID jobId, @Valid @RequestBody CancellationRequest body) {
		Viewer viewer = party(jobId);
		this.cancellations.propose(jobId, viewer.accountId(), viewer.party(), body.version(), body.scope(), body.reason());
		return view(party(jobId));
	}

	/** The other side's "Xác nhận hủy". */
	@PostMapping("/api/v1/boards/{boardId}/cancellation/confirm")
	CancellationView confirm(@PathVariable("boardId") UUID jobId, @Valid @RequestBody CancellationVersionRequest body) {
		Viewer viewer = party(jobId);
		this.cancellations.confirm(jobId, viewer.accountId(), viewer.party(), body.version());
		return view(party(jobId));
	}

	@PostMapping("/api/v1/boards/{boardId}/cancellation/withdraw")
	CancellationView withdraw(@PathVariable("boardId") UUID jobId) {
		Viewer viewer = party(jobId);
		this.cancellations.withdraw(jobId, viewer.accountId(), viewer.party());
		return view(party(jobId));
	}

	private Viewer party(UUID jobId) {
		Viewer viewer = viewer(jobId, this.callers.get());
		if (viewer.party() == null) {
			throw new ApiException(ErrorCode.FORBIDDEN);
		}
		return viewer;
	}

	/** The Customer, the Mentor, or an active Crew member (party empty), from Agreement on. */
	private Viewer viewer(UUID jobId, Caller caller) {
		Job job = this.jdbc.sql("SELECT " + Jobs.COLUMNS + " FROM job.job WHERE id = :id")
			.param("id", jobId)
			.query(Jobs::job)
			.optional()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		UUID account = caller.accountId();
		if (account == null || job.mentorId() == null || !VISIBLE.contains(job.state())) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		if (caller.hasRole(Role.CUSTOMER) && account.equals(job.customerId())) {
			return new Viewer(job, account, Party.CUSTOMER);
		}
		if (caller.hasRole(Role.MENTOR) && account.equals(job.mentorId())) {
			return new Viewer(job, account, Party.MENTOR);
		}
		if (caller.hasRole(Role.STUDENT) && this.crews.crewRelationship(jobId, account).kind() == Relationship.ACTIVE) {
			return new Viewer(job, account, null);
		}
		throw new ApiException(ErrorCode.NOT_FOUND);
	}

	private CancellationView view(Viewer viewer) {
		Job job = viewer.job();
		Proposal open = this.cancellations.open(job.id()).orElse(null);
		Proposal shown = open != null ? open : this.cancellations.latest(job.id()).orElse(null);
		boolean canPropose = viewer.party() != null && open == null && Cancellations.CANCELLABLE.contains(job.state())
				&& this.closures.open(job.id()).isEmpty();
		CancellationProposalView proposal = shown == null ? null
				: new CancellationProposalView(shown.id(), shown.scope().name(), shown.reason(), shown.proposer().name(),
						shown.proposer() == viewer.party(), shown.status(), shown.answerBy(), shown.unansweredAt() != null,
						shown.createdAt(), shown.decidedAt());
		return new CancellationView(job.id(), viewer.party() == null ? "CREW" : viewer.party().name(), job.state().name(),
				job.version(), canPropose, this.cancellations.held(job.id()), proposal);
	}

}
