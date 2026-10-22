package vn.vibefix.repo.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import vn.vibefix.job.JobBusy;
import vn.vibefix.job.JobBusy.BusyJob;
import vn.vibefix.repo.internal.RevocationChecklists.RevocationItem;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;
import vn.vibefix.shared.Role;

/**
 * JOB-1's Revocation Checklist (FR-66, UX-DR51, UX-DR55): the Customer reads each item with what to remove
 * and confirms it as "Đã gỡ quyền" or "Đã đổi khóa"; the Mentor sees each item done or pending in the
 * closure section. A Board's ID is its Job's. Everyone else finds nothing.
 */
@RestController
@Access(Gate.SIGNED_IN)
class RevocationChecklistController {

	/**
	 * One item.
	 * @param identifiers the identifiers to remove, the Customer's only
	 * @param outcome {@code REMOVED} or {@code ROTATED} once confirmed
	 */
	record RevocationItemView(UUID accessId, String system, String purpose, String environment, String accessLevel,
			List<RevocationIdentifierView> identifiers, boolean done, @Nullable String outcome, @Nullable Instant confirmedAt) {
	}

	/** One identifier beside its owner's GitHub username. */
	record RevocationIdentifierView(String githubLogin, String identifier) {
	}

	/** @param audience {@code CUSTOMER} or {@code MENTOR} */
	record RevocationChecklistView(UUID jobId, String audience, List<RevocationItemView> items) {
	}

	/** "Đã gỡ quyền" ({@code REMOVED}) or "Đã đổi khóa" ({@code ROTATED}). */
	record RevocationConfirmRequest(@NotNull @Pattern(regexp = "REMOVED|ROTATED") String outcome) {
	}

	private final RevocationChecklists checklists;

	private final JobBusy busy;

	private final CurrentCaller callers;

	RevocationChecklistController(RevocationChecklists checklists, JobBusy busy, CurrentCaller callers) {
		this.checklists = checklists;
		this.busy = busy;
		this.callers = callers;
	}

	@GetMapping("/api/v1/boards/{boardId}/revocation-checklist")
	RevocationChecklistView checklist(@PathVariable("boardId") UUID jobId) {
		Caller caller = this.callers.get();
		BusyJob job = this.busy.job(jobId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		boolean customer = caller.hasRole(Role.CUSTOMER) && job.customerId().equals(caller.accountId());
		boolean mentor = caller.hasRole(Role.MENTOR) && caller.accountId() != null && caller.accountId().equals(job.mentorId());
		if (!customer && !mentor) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		return view(job, customer);
	}

	/** @throws ApiException {@code NOT_FOUND} for anyone but the Customer, or no such item */
	@PostMapping("/api/v1/boards/{boardId}/revocation-checklist/{accessId}")
	RevocationChecklistView confirm(@PathVariable("boardId") UUID jobId, @PathVariable UUID accessId,
			@Valid @RequestBody RevocationConfirmRequest body) {
		Caller caller = this.callers.get();
		BusyJob job = this.busy.job(jobId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (!caller.hasRole(Role.CUSTOMER) || !job.customerId().equals(caller.accountId())) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		this.checklists.confirm(jobId, accessId, caller.requireAccountId(), body.outcome());
		return view(job, true);
	}

	private RevocationChecklistView view(BusyJob job, boolean customer) {
		List<RevocationItem> items = this.checklists.items(job.jobId());
		return new RevocationChecklistView(job.jobId(), customer ? "CUSTOMER" : "MENTOR", items.stream()
			.map(item -> new RevocationItemView(item.accessId(), item.system(), item.purpose(), item.environment(),
					item.accessLevel(),
					customer ? item.identifiers()
						.stream()
						.map(identifier -> new RevocationIdentifierView(identifier.githubLogin(), identifier.identifier()))
						.toList() : List.of(),
					"CONFIRMED".equals(item.status()), item.outcome(), item.confirmedAt()))
			.toList());
	}

}
