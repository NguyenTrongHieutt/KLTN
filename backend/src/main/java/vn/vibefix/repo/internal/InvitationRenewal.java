package vn.vibefix.repo.internal;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import vn.vibefix.job.JobBusy;
import vn.vibefix.repo.internal.JobGrants.Grant;
import vn.vibefix.timers.DeadlineHandler;
import vn.vibefix.timers.DeadlineKind;
import vn.vibefix.timers.DeadlineOutcome;
import vn.vibefix.timers.DueDeadline;

/**
 * {@value #KEY} (FR-18, FR-75, AD-7, AD-11): GitHub expires an invitation after 7 days, so a grant still
 * pending is invited again, with read permission, while the Job is Starting. A Crew change member's is
 * renewed while the Job is Active, at most {@value JobGrants#CREW_CHANGE_RENEWALS} times; then the
 * Mentor and the Administrators hear of it. Not pausable. The generation is the grant's renewal count, so
 * an accepted or renewed grant leaves it {@code OBSOLETE}. Collaborators are looked up on use.
 */
@Component
class InvitationRenewal implements DeadlineHandler {

	static final String KEY = "repo.invitation_renewal";

	static final DeadlineKind KIND = new DeadlineKind(KEY, false, true);

	private final ObjectProvider<JobGrants> grants;

	private final ObjectProvider<JobBusy> busy;

	InvitationRenewal(ObjectProvider<JobGrants> grants, ObjectProvider<JobBusy> busy) {
		this.grants = grants;
		this.busy = busy;
	}

	@Override
	public DeadlineKind kind() {
		return KIND;
	}

	@Override
	public DeadlineOutcome handle(DueDeadline deadline) {
		JobGrants grants = this.grants.getObject();
		Grant grant = grants.find(deadline.subjectId()).orElse(null);
		if (grant == null || !"INVITED".equals(grant.status()) || !String.valueOf(grant.renewals()).equals(deadline.generation())) {
			return DeadlineOutcome.OBSOLETE;
		}
		String state = this.busy.getObject().job(grant.jobId()).map(JobBusy.BusyJob::state).orElse("");
		if ("STARTING".equals(state)) {
			grants.reinvite(grant);
			return DeadlineOutcome.FIRED;
		}
		if ("ACTIVE".equals(state) && grant.crewChange()) {
			if (grant.renewals() >= JobGrants.CREW_CHANGE_RENEWALS) {
				grants.exhausted(grant);
			}
			else {
				grants.reinvite(grant);
			}
			return DeadlineOutcome.FIRED;
		}
		return DeadlineOutcome.OBSOLETE;
	}

}
