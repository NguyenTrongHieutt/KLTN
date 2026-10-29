package vn.vibefix.matching.internal;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import vn.vibefix.timers.DeadlineHandler;
import vn.vibefix.timers.DeadlineKind;
import vn.vibefix.timers.DeadlineOutcome;
import vn.vibefix.timers.DueDeadline;

/**
 * {@value #KEY} (FR-81, R4, AD-7, Story 21.5): a pick still waiting for its signature 3 days after the Mentor picked it
 * lapses; the row is released as {@code DECLINED}, and the Mentor and the person learn it. Not pausable: a Dispute
 * freezing the Job does not hold a person's signature period. The generation is the application, so a pick that was
 * signed or ended leaves the Deadline {@code OBSOLETE}. Collaborators are looked up on use: {@code timers} collects its
 * handlers before the commands exist.
 */
@Component
class CommitmentDue implements DeadlineHandler {

	static final String KEY = "matching.commitment_due";

	static final DeadlineKind KIND = new DeadlineKind(KEY, false, true);

	private final ObjectProvider<CrewCommitments> commitments;

	CommitmentDue(ObjectProvider<CrewCommitments> commitments) {
		this.commitments = commitments;
	}

	@Override
	public DeadlineKind kind() {
		return KIND;
	}

	@Override
	public DeadlineOutcome handle(DueDeadline deadline) {
		return this.commitments.getObject().lapse(deadline.subjectId()) ? DeadlineOutcome.FIRED : DeadlineOutcome.OBSOLETE;
	}

}
