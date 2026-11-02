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
import vn.vibefix.job.JobRole;
import vn.vibefix.job.JobRoles;
import vn.vibefix.repo.internal.RevocationChecklists.RevocationItem;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;

/**
 * JOB-1's Revocation Checklist (FR-66, UX-DR51, UX-DR55): the Customer reads each item with what to remove
 * and confirms it as "Đã gỡ quyền" or "Đã đổi khóa", a leaver's in every state (R15, Story 21.6); the Mentor sees each
 * item of the Job's end done or pending in the closure section. A Board's ID is its Job's. Everyone else finds nothing.
 */
@RestController
@Access(Gate.SIGNED_IN)
class RevocationChecklistController {

	/**
	 * One item.
	 * @param identifiers the identifiers to remove, the Customer's only
	 * @param outcome {@code REMOVED} or {@code ROTATED} once confirmed
	 * @param memberLogin the member who left, on a leaver's item; the Customer's only
	 */
	record RevocationItemView(UUID accessId, String system, String purpose, String environment, String accessLevel,
			List<RevocationIdentifierView> identifiers, boolean done, @Nullable String outcome, @Nullable Instant confirmedAt,
			@Nullable String memberLogin) {
	}

	/** One identifier beside its owner's GitHub username. */
	record RevocationIdentifierView(String githubLogin, String identifier) {
	}

	/** @param audience {@code CUSTOMER} or {@code MENTOR} */
	record RevocationChecklistView(UUID jobId, String audience, List<RevocationItemView> items) {
	}

	/**
	 * "Đã gỡ quyền" ({@code REMOVED}) or "Đã đổi khóa" ({@code ROTATED}).
	 * @param memberLogin the member who left, for a leaver's item; none for the item of the Job's end
	 */
	record RevocationConfirmRequest(@NotNull @Pattern(regexp = "REMOVED|ROTATED") String outcome,
			@Nullable String memberLogin) {
	}

	private final RevocationChecklists checklists;

	private final JobBusy busy;

	private final CurrentCaller callers;

	private final JobRoles roles;

	RevocationChecklistController(RevocationChecklists checklists, JobBusy busy, CurrentCaller callers,
			JobRoles roles) {
		this.roles = roles;
		this.checklists = checklists;
		this.busy = busy;
		this.callers = callers;
	}

	@GetMapping("/api/v1/boards/{boardId}/revocation-checklist")
	RevocationChecklistView checklist(@PathVariable("boardId") UUID jobId) {
		Caller caller = this.callers.get();
		BusyJob job = this.busy.job(jobId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		JobRole role = this.roles.roleOf(jobId, caller);
		boolean customer = role.isCustomer();
		if (!customer && !role.isMentor()) {
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
		if (!this.roles.roleOf(jobId, caller).isCustomer()) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		this.checklists.confirm(jobId, accessId, body.memberLogin(), caller.requireAccountId(), body.outcome());
		return view(job, true);
	}

	private RevocationChecklistView view(BusyJob job, boolean customer) {
		List<RevocationItem> items = this.checklists.items(job.jobId());
		return new RevocationChecklistView(job.jobId(), customer ? "CUSTOMER" : "MENTOR", items.stream()
			.filter(item -> customer || item.memberLogin() == null)
			.map(item -> new RevocationItemView(item.accessId(), item.system(), item.purpose(), item.environment(),
					item.accessLevel(),
					customer ? item.identifiers()
						.stream()
						.map(identifier -> new RevocationIdentifierView(identifier.githubLogin(), identifier.identifier()))
						.toList() : List.of(),
					"CONFIRMED".equals(item.status()), item.outcome(), item.confirmedAt(), item.memberLogin()))
			.toList());
	}

}
