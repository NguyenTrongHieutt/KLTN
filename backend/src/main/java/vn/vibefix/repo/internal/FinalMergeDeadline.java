package vn.vibefix.repo.internal;

import java.util.Objects;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.job.JobFreezes;
import vn.vibefix.job.JobFreezes.DisputedJob;
import vn.vibefix.repo.internal.FinalMergeAuthorizations.Authorization;
import vn.vibefix.timers.DeadlineHandler;
import vn.vibefix.timers.DeadlineKind;
import vn.vibefix.timers.DeadlineOutcome;
import vn.vibefix.timers.DueDeadline;

/**
 * {@value #KEY} (FR-37): the FR-37 deadline, set at J8. A row still {@code REQUESTED} whose Final Merge the Customer
 * has not confirmed ends {@code DEADLINE}, and the closure fallback follows (Story 8.9, which Story 23.10 replaces).
 * A Final Merge the Customer set going finishes on its own. Pausable: it takes the Job row lock first, which
 * {@code end} does not, so a frozen Job always pauses it and nothing ends (AD-4). Collaborators are looked up on use.
 */
@Component
class FinalMergeDeadline implements DeadlineHandler {

	static final String KEY = "repo.final_merge_deadline";

	static final DeadlineKind KIND = new DeadlineKind(KEY, true, true);

	private final ObjectProvider<FinalMergeAuthorizations> authorizations;

	private final ObjectProvider<JobFreezes> freezes;

	private final ObjectProvider<FinalMerges> merges;

	FinalMergeDeadline(ObjectProvider<FinalMergeAuthorizations> authorizations, ObjectProvider<JobFreezes> freezes,
			ObjectProvider<FinalMerges> merges) {
		this.authorizations = authorizations;
		this.freezes = freezes;
		this.merges = merges;
	}

	@Override
	public DeadlineKind kind() {
		return KIND;
	}

	@Override
	@Transactional
	public DeadlineOutcome handle(DueDeadline deadline) {
		DisputedJob job = this.freezes.getObject().lock(Objects.requireNonNull(deadline.jobId())).orElse(null);
		if (job != null && job.frozen()) {
			return DeadlineOutcome.REPAUSE;
		}
		Authorization authorization = this.authorizations.getObject().find(deadline.subjectId()).orElse(null);
		if (authorization == null || !authorization.open()) {
			return DeadlineOutcome.OBSOLETE;
		}
		boolean confirmed = this.merges.getObject().find(authorization.id()).filter(FinalMerges::confirmed).isPresent();
		if (confirmed) {
			return DeadlineOutcome.OBSOLETE;
		}
		this.authorizations.getObject().end(authorization.id(), "DEADLINE");
		return DeadlineOutcome.FIRED;
	}

}
