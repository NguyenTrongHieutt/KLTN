package vn.vibefix.repo.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.job.Crews.Relationship;
import vn.vibefix.job.JobBusy;
import vn.vibefix.job.JobBusy.BusyJob;
import vn.vibefix.job.JobRole;
import vn.vibefix.job.JobRoles;
import vn.vibefix.scanning.Finding;
import vn.vibefix.scanning.FindingCategory;
import vn.vibefix.scanning.Scans;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;

/**
 * The Job's open Findings (FR-8, UX-DR31): the Customer, the Mentor, and each active Crew member read
 * them, masked; a credential's resolver, the Mentor for pushed code, confirms "Đã đổi khóa". Everyone else
 * finds nothing.
 */
@RestController
@Access(Gate.SIGNED_IN)
class JobFindingController {

	/**
	 * One open Finding, masked as everywhere (AD-18).
	 * @param canRotate the viewer resolves this credential
	 */
	record JobFindingView(UUID id, String category, String type, String path, String commit, String maskedPreview,
			Instant createdAt, boolean canRotate) {
	}

	private final Scans scans;

	private final JobBusy busy;

	private final JobRoles roles;

	private final CurrentCaller callers;

	JobFindingController(Scans scans, JobBusy busy, JobRoles roles, CurrentCaller callers) {
		this.scans = scans;
		this.busy = busy;
		this.roles = roles;
		this.callers = callers;
	}

	@GetMapping("/api/v1/jobs/{jobId}/findings")
	List<JobFindingView> findings(@PathVariable UUID jobId) {
		Caller caller = this.callers.get();
		BusyJob job = party(jobId, caller);
		return views(job, caller.requireAccountId());
	}

	/** "Đã đổi khóa" (FR-8): the resolver confirms the credential was rotated. */
	@PostMapping("/api/v1/jobs/{jobId}/findings/{findingId}/rotation")
	List<JobFindingView> rotated(@PathVariable UUID jobId, @PathVariable UUID findingId) {
		Caller caller = this.callers.get();
		BusyJob job = party(jobId, caller);
		this.scans.confirmRotation(job.productId(), findingId, caller.requireAccountId());
		return views(job, caller.requireAccountId());
	}

	private List<JobFindingView> views(BusyJob job, UUID viewer) {
		return this.scans.openFindings(job.productId())
			.stream()
			.map((Finding finding) -> new JobFindingView(finding.id(), finding.category().name(), finding.type(), finding.path(),
					finding.commit().substring(0, Math.min(7, finding.commit().length())), finding.maskedPreview(),
					finding.createdAt(),
					finding.category() == FindingCategory.CREDENTIAL && this.scans.mayResolve(finding.id(), viewer)))
			.toList();
	}

	/** The Customer, the Mentor, or an active Crew member of a Code Job past Draft, by role in the Job (AD-43). */
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

}
