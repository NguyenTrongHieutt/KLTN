package vn.vibefix.job.internal;

import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import vn.vibefix.identity.MentorGate;
import vn.vibefix.job.internal.Agreements.AccessLevel;
import vn.vibefix.job.internal.Agreements.Environment;
import vn.vibefix.job.internal.ExternalAccessGrants.Item;
import vn.vibefix.job.internal.ExternalAccessGrants.Viewer;
import vn.vibefix.job.internal.Jobs.Job;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;
import vn.vibefix.shared.Role;

/**
 * External Access after confirmation (FR-66, UX-DR83): N-8's guidance with the identifiers for the
 * Customer on JOB-6, and each person's own identifier for the Mentor on JOB-6 and a Crew member on STU-4.
 * The Job's Customer, its Mentor, and its Crew members whose rows are not released read it; anyone else
 * finds nothing.
 */
@RestController
@Access(Gate.SIGNED_IN)
class ExternalAccessController {

	/**
	 * One item. {@code live} shows "Hệ thống thật" (FR-16); {@code granted} is the Customer's "Đã cấp quyền"; {@code identifiers} is for the Customer
	 * only; {@code mine} is the Mentor's or Crew member's own identifier.
	 */
	record ExternalAccessItemView(UUID id, String system, String purpose, Environment environment, AccessLevel accessLevel,
			boolean live, @Nullable Boolean granted, List<IdentifierView> identifiers, @Nullable String mine) {
	}

	/** One person's identifier beside their GitHub username. */
	record IdentifierView(String githubLogin, String identifier) {
	}

	/** The items, and whether the Job still takes identifiers. */
	record ExternalAccessView(Viewer viewer, boolean open, List<ExternalAccessItemView> items) {
	}

	/** The caller's own identifier for one item; an empty one clears it. */
	record IdentifierRequest(@NotNull @Size(max = ExternalAccessGrants.MAX_IDENTIFIER) String identifier) {
	}

	private final ExternalAccessGrants grants;

	private final MentorGate gate;

	private final CurrentCaller callers;

	ExternalAccessController(ExternalAccessGrants grants, MentorGate gate, CurrentCaller callers) {
		this.grants = grants;
		this.gate = gate;
		this.callers = callers;
	}

	@GetMapping("/api/v1/jobs/{jobId}/external-access")
	ExternalAccessView view(@PathVariable UUID jobId) {
		Caller caller = this.callers.get();
		Job job = job(jobId);
		return view(job, viewer(job, caller), caller.requireAccountId());
	}

	/** The Mentor's or a Crew member's identifier for one item. */
	@PutMapping("/api/v1/jobs/{jobId}/external-access/{accessId}/identifier")
	ExternalAccessView identifier(@PathVariable UUID jobId, @PathVariable UUID accessId,
			@Valid @RequestBody IdentifierRequest body) {
		Caller caller = this.callers.get();
		Job job = job(jobId);
		Viewer viewer = viewer(job, caller);
		if (viewer == Viewer.CUSTOMER) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		this.gate.requireActiveAccount(caller.requireAccountId());
		this.grants.enter(job, caller.requireAccountId(), accessId, body.identifier());
		return view(job, viewer, caller.requireAccountId());
	}

	/** The Customer's "Đã cấp quyền" for one item. */
	@PostMapping("/api/v1/jobs/{jobId}/external-access/{accessId}/granted")
	ExternalAccessView granted(@PathVariable UUID jobId, @PathVariable UUID accessId) {
		Caller caller = this.callers.get();
		Job job = job(jobId);
		if (viewer(job, caller) != Viewer.CUSTOMER) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		this.grants.granted(job, accessId);
		return view(job, Viewer.CUSTOMER, caller.requireAccountId());
	}

	private ExternalAccessView view(Job job, Viewer viewer, UUID accountId) {
		List<ExternalAccessItemView> items = this.grants.items(job, viewer, accountId)
			.stream()
			.map(ExternalAccessController::itemView)
			.toList();
		boolean open = job.state() != JobState.CLOSED && job.state() != JobState.CANCELLED;
		return new ExternalAccessView(viewer, open, items);
	}

	private static ExternalAccessItemView itemView(Item item) {
		return new ExternalAccessItemView(item.access().id(), item.access().system(), item.access().purpose(),
				item.access().environment(), item.access().accessLevel(), item.live(), item.granted(),
				item.identifiers().stream().map(found -> new IdentifierView(found.githubLogin(), found.identifier())).toList(),
				item.mine());
	}

	private Job job(UUID jobId) {
		return this.grants.job(jobId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	/** The Job's Customer, its Mentor, or one of its Crew; anyone else finds nothing. */
	private Viewer viewer(Job job, Caller caller) {
		UUID account = caller.requireAccountId();
		if (caller.hasRole(Role.CUSTOMER) && job.customerId().equals(account)) {
			return Viewer.CUSTOMER;
		}
		if (caller.hasRole(Role.MENTOR) && account.equals(job.mentorId())) {
			return Viewer.MENTOR;
		}
		if (caller.hasRole(Role.STUDENT) && this.grants.onCrew(job.id(), account)) {
			return Viewer.CREW;
		}
		throw new ApiException(ErrorCode.NOT_FOUND);
	}

}
