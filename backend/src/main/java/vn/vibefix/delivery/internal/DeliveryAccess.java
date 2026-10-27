package vn.vibefix.delivery.internal;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.job.Crews.Relationship;
import vn.vibefix.job.JobRole;
import vn.vibefix.job.JobRoles;
import vn.vibefix.job.MilestoneHandovers;
import vn.vibefix.job.MilestoneHandovers.HandoverJob;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.ErrorCode;

/**
 * Who reads and makes handovers (FR-32, AD-20, AD-41, AD-43), by their role in the Job: the Job's Customer, its
 * Mentor, and its {@code ACTIVE} Crew members read; only the Mentor makes one. Everyone else finds nothing.
 */
@Component
class DeliveryAccess {

	enum Kind {
		CUSTOMER, MENTOR, CREW
	}

	record Viewer(HandoverJob job, UUID accountId, Kind kind) {

		boolean mentor() {
			return this.kind == Kind.MENTOR;
		}

	}

	private final MilestoneHandovers handovers;

	private final JobRoles roles;

	DeliveryAccess(MilestoneHandovers handovers, JobRoles roles) {
		this.handovers = handovers;
		this.roles = roles;
	}

	/** A reader of the Job's handovers. */
	@Transactional(readOnly = true)
	Viewer reader(UUID jobId, Caller caller) {
		HandoverJob job = this.handovers.job(jobId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		UUID account = caller.accountId();
		if (account == null || job.mentorId() == null) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		JobRole role = this.roles.roleOf(jobId, caller);
		if (role.isCustomer()) {
			return new Viewer(job, account, Kind.CUSTOMER);
		}
		if (role.isMentor()) {
			return new Viewer(job, account, Kind.MENTOR);
		}
		if (role.crewKind() == Relationship.ACTIVE) {
			return new Viewer(job, account, Kind.CREW);
		}
		throw new ApiException(ErrorCode.NOT_FOUND);
	}

	/**
	 * The Mentor, who alone makes a handover.
	 * @throws ApiException {@code FORBIDDEN MENTOR_ONLY} for the Customer or a Crew member
	 */
	@Transactional(readOnly = true)
	Viewer maker(UUID jobId, Caller caller) {
		Viewer viewer = reader(jobId, caller);
		if (!viewer.mentor()) {
			throw new ApiException(ErrorCode.FORBIDDEN, DeliveryReason.MENTOR_ONLY);
		}
		return viewer;
	}

}
