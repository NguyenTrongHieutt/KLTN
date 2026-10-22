package vn.vibefix.delivery.internal;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import vn.vibefix.delivery.internal.Handovers.Handover;
import vn.vibefix.job.MilestoneHandovers;
import vn.vibefix.job.MilestoneHandovers.HandoverJob;
import vn.vibefix.timers.DeadlineHandler;
import vn.vibefix.timers.DeadlineKind;
import vn.vibefix.timers.DeadlineOutcome;
import vn.vibefix.timers.DueDeadline;

/**
 * {@value #KEY} (FR-35, AD-7): a handover the Customer leaves undecided until its check-by time is
 * accepted automatically, with M5 as {@code AUTO} and exactly an acceptance's effects, the split release
 * included. Pausable: a frozen Job pauses it ({@code REPAUSE}); a decided, replaced, or refused handover
 * leaves it {@code OBSOLETE}. The busy marker (AD-28), which would answer {@code REARM}, comes with Epic 7.
 * Its collaborators are looked up on use: {@code timers} collects its handlers before the handover
 * commands that schedule Deadlines exist.
 */
@Component
class AutoAcceptance implements DeadlineHandler {

	static final String KEY = "delivery.auto_acceptance";

	static final DeadlineKind KIND = new DeadlineKind(KEY, true, true);

	private final ObjectProvider<Handovers> handovers;

	private final ObjectProvider<Decisions> decisions;

	private final ObjectProvider<MilestoneHandovers> jobs;

	private final ObjectProvider<AcceptanceMerges> merges;

	AutoAcceptance(ObjectProvider<Handovers> handovers, ObjectProvider<Decisions> decisions,
			ObjectProvider<MilestoneHandovers> jobs, ObjectProvider<AcceptanceMerges> merges) {
		this.handovers = handovers;
		this.decisions = decisions;
		this.jobs = jobs;
		this.merges = merges;
	}

	@Override
	public DeadlineKind kind() {
		return KIND;
	}

	@Override
	public DeadlineOutcome handle(DueDeadline deadline) {
		Handover handover = this.handovers.getObject().find(deadline.subjectId()).orElse(null);
		if (handover == null || !handover.id().toString().equals(deadline.generation())
				|| !"SUBMITTED".equals(handover.status())) {
			return DeadlineOutcome.OBSOLETE;
		}
		HandoverJob job = this.jobs.getObject().job(handover.jobId()).orElse(null);
		if (job == null) {
			return DeadlineOutcome.OBSOLETE;
		}
		if (job.frozen()) {
			return DeadlineOutcome.REPAUSE;
		}
		if (!job.assessment() && this.handovers.getObject().code(handover.id()).isPresent()) {
			// FR-33: a Code Job's Auto-acceptance merges first, as the Customer's would.
			return this.merges.getObject().requestAutomatically(handover);
		}
		return this.decisions.getObject().acceptAutomatically(handover, job.customerId()) ? DeadlineOutcome.FIRED
				: DeadlineOutcome.OBSOLETE;
	}

}
