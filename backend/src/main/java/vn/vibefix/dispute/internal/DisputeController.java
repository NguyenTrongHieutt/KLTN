package vn.vibefix.dispute.internal;

import java.time.Instant;
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
import vn.vibefix.job.Crews;
import vn.vibefix.job.Crews.Relationship;
import vn.vibefix.job.JobPosts;
import vn.vibefix.job.JobPosts.JobPostState;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;
import vn.vibefix.shared.Role;

/**
 * JOB-1's Dispute (FR-52, UX-DR22, UX-DR56): the Customer and the Mentor open one, and with the active
 * Crew they read the open one, which drives the Frozen banner. Everyone else finds nothing.
 */
@RestController
@Access(Gate.SIGNED_IN)
class DisputeController {

	/** @param concern {@code HELD_MILESTONE} or {@code NEXT_STATE} */
	record OpenDisputeView(UUID id, String openedBy, boolean mine, String reason, String concern,
			@Nullable UUID milestoneId, Instant openedAt) {
	}

	/** @param canOpen the viewer may open one now */
	record JobDisputeView(UUID jobId, String audience, boolean canOpen, @Nullable OpenDisputeView open) {
	}

	record OpenDisputeRequest(@NotBlank @Size(max = 2000) String reason) {
	}

	private record Viewer(JobPostState job, UUID accountId, @Nullable Side side) {
	}

	private final Disputes disputes;

	private final JobPosts posts;

	private final Crews crews;

	private final CurrentCaller callers;

	DisputeController(Disputes disputes, JobPosts posts, Crews crews, CurrentCaller callers) {
		this.disputes = disputes;
		this.posts = posts;
		this.crews = crews;
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

	/** The Customer, the Mentor, or an active Crew member (side empty), once the Job has a Mentor. */
	private Viewer viewer(UUID jobId, Caller caller) {
		JobPostState job = this.posts.post(jobId)
			.filter(found -> found.mentorId() != null)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		UUID account = caller.accountId();
		if (account == null) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		if (caller.hasRole(Role.CUSTOMER) && account.equals(job.customerId())) {
			return new Viewer(job, account, Side.CUSTOMER);
		}
		if (caller.hasRole(Role.MENTOR) && account.equals(job.mentorId())) {
			return new Viewer(job, account, Side.MENTOR);
		}
		if (caller.hasRole(Role.STUDENT) && this.crews.crewRelationship(jobId, account).kind() == Relationship.ACTIVE) {
			return new Viewer(job, account, null);
		}
		throw new ApiException(ErrorCode.NOT_FOUND);
	}

	private JobDisputeView view(Viewer viewer) {
		Dispute open = this.disputes.open(viewer.job().jobId()).orElse(null);
		boolean canOpen = viewer.side() != null && open == null && Disputes.DISPUTABLE.contains(viewer.job().state());
		return new JobDisputeView(viewer.job().jobId(), viewer.side() == null ? "CREW" : viewer.side().name(), canOpen,
				open == null ? null : new OpenDisputeView(open.id(), open.openedBy().name(), open.openedBy() == viewer.side(),
						open.reason(), open.concern(), open.milestoneId(), open.openedAt()));
	}

}
