package vn.vibefix.job.internal;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.timers.DeadlineHandler;
import vn.vibefix.timers.DeadlineKind;
import vn.vibefix.timers.DeadlineOutcome;
import vn.vibefix.timers.DueDeadline;

/**
 * {@value #KEY} (FR-39, AD-7): a person's closure proposal was not answered within the Job's copied
 * days, so its proposer is told and may open a Dispute. Pausable: a frozen Job pauses it. A decided
 * proposal leaves it {@code OBSOLETE}. Collaborators are looked up on use.
 */
@Component
class ClosureAnswer implements DeadlineHandler {

	static final String KEY = "job.closure_answer";

	static final DeadlineKind KIND = new DeadlineKind(KEY, true, true);

	private final ObjectProvider<ClosureProposals> proposals;

	private final ObjectProvider<Jobs> jobs;

	ClosureAnswer(ObjectProvider<ClosureProposals> proposals, ObjectProvider<Jobs> jobs) {
		this.proposals = proposals;
		this.jobs = jobs;
	}

	@Override
	public DeadlineKind kind() {
		return KIND;
	}

	@Override
	@Transactional
	public DeadlineOutcome handle(DueDeadline deadline) {
		Jobs.Job job = deadline.jobId() == null ? null : this.jobs.getObject().lock(deadline.jobId()).orElse(null);
		if (job == null) {
			return DeadlineOutcome.OBSOLETE;
		}
		if (job.frozenAt() != null) {
			return DeadlineOutcome.REPAUSE;
		}
		return this.proposals.getObject().unanswered(job, deadline.subjectId()) ? DeadlineOutcome.FIRED
				: DeadlineOutcome.OBSOLETE;
	}

}
