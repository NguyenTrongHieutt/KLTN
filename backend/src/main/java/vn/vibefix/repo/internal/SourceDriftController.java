package vn.vibefix.repo.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.job.Crews.Relationship;
import vn.vibefix.job.JobBusy;
import vn.vibefix.job.JobBusy.BusyJob;
import vn.vibefix.job.JobRole;
import vn.vibefix.job.JobRoles;
import vn.vibefix.repo.internal.SourceDrift.Warning;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;

/**
 * JOB-H's and JOB-1's Drift Warning (FR-20, UX-DR27): what changed in the Source Repository, for the
 * Customer, the Mentor, and the active Crew; and the Customer's re-confirmation of a renamed or
 * transferred one. Everyone else finds nothing.
 */
@RestController
@Access(Gate.SIGNED_IN)
class SourceDriftController {

	/**
	 * One warning.
	 * @param kind {@code COMMITS}, {@code DELETED_OR_PRIVATE}, {@code ARCHIVED}, {@code RENAMED}, or {@code TRANSFERRED}
	 * @param commits how many commits triggered it
	 * @param fullName the repository's new name, for a rename or transfer
	 */
	record DriftWarningView(String kind, Instant detectedAt, int commits, @Nullable String fullName, boolean reconfirmed) {
	}

	/** @param reconfirmNeeded the viewer is the Customer and a rename or transfer awaits their re-confirmation */
	record DriftView(List<DriftWarningView> warnings, boolean reconfirmNeeded) {
	}

	private final SourceDrift drift;

	private final JobBusy busy;

	private final JobRoles roles;

	private final CurrentCaller callers;

	SourceDriftController(SourceDrift drift, JobBusy busy, JobRoles roles, CurrentCaller callers) {
		this.drift = drift;
		this.busy = busy;
		this.roles = roles;
		this.callers = callers;
	}

	@GetMapping("/api/v1/jobs/{jobId}/drift")
	DriftView drift(@PathVariable UUID jobId) {
		Caller caller = this.callers.get();
		BusyJob job = party(jobId, caller);
		return view(job, caller);
	}

	/** "Xác nhận lại dự án gốc" (FR-20): the Customer only. */
	@PostMapping("/api/v1/jobs/{jobId}/source/reconfirm")
	DriftView reconfirm(@PathVariable UUID jobId) {
		Caller caller = this.callers.get();
		BusyJob job = party(jobId, caller);
		if (!customer(job, caller)) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		this.drift.reconfirm(job);
		return view(job, caller);
	}

	private DriftView view(BusyJob job, Caller caller) {
		List<Warning> warnings = this.drift.warnings(job.jobId());
		return new DriftView(warnings.stream()
			.map(warning -> new DriftWarningView(warning.kind().name(), warning.detectedAt(), warning.commits().size(),
					warning.fullName(), warning.reconfirmedAt() != null))
			.toList(), customer(job, caller) && warnings.stream().anyMatch(Warning::awaitingReconfirmation));
	}

	/** The Code Job's Customer, its Mentor, or an active Crew member, by their role in the Job (AD-43). */
	private BusyJob party(UUID jobId, Caller caller) {
		BusyJob job = this.busy.job(jobId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (!job.code()) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		JobRole role = this.roles.roleOf(jobId, caller);
		if (!role.isCustomer() && !role.isMentor() && role.crewKind() != Relationship.ACTIVE) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		return job;
	}

	private boolean customer(BusyJob job, Caller caller) {
		return this.roles.roleOf(job.jobId(), caller).isCustomer();
	}

}
