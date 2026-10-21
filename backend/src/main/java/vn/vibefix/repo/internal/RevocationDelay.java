package vn.vibefix.repo.internal;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import vn.vibefix.repo.internal.JobGrants.Grant;
import vn.vibefix.timers.DeadlineHandler;
import vn.vibefix.timers.DeadlineKind;
import vn.vibefix.timers.DeadlineOutcome;
import vn.vibefix.timers.DueDeadline;

/**
 * {@value #KEY} (FR-21): a revocation still retrying 15 minutes after its trigger alerts every
 * Administrator with {@value Revocations#DELAYED}; the operation keeps trying. Not pausable: a frozen
 * Job's access ends all the same. A grant read back as revoked leaves it {@code OBSOLETE}.
 * Collaborators are looked up on use.
 */
@Component
class RevocationDelay implements DeadlineHandler {

	static final String KEY = "repo.revocation_delay";

	static final DeadlineKind KIND = new DeadlineKind(KEY, false, true);

	private final ObjectProvider<JobGrants> grants;

	private final ObjectProvider<Revocations> revocations;

	RevocationDelay(ObjectProvider<JobGrants> grants, ObjectProvider<Revocations> revocations) {
		this.grants = grants;
		this.revocations = revocations;
	}

	@Override
	public DeadlineKind kind() {
		return KIND;
	}

	@Override
	public DeadlineOutcome handle(DueDeadline deadline) {
		Grant grant = this.grants.getObject().find(deadline.subjectId()).orElse(null);
		if (grant == null || !"REVOKING".equals(grant.status())) {
			return DeadlineOutcome.OBSOLETE;
		}
		this.revocations.getObject().delayed(grant);
		return DeadlineOutcome.FIRED;
	}

}
