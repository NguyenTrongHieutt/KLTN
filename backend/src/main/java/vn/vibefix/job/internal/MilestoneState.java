package vn.vibefix.job.internal;

import java.util.EnumSet;
import java.util.Set;

/**
 * The Milestone states of PRD section 4.0; Milestones arrive with the Agreement (Epic 4). Accepted,
 * Cancelled, and Settled are final.
 */
enum MilestoneState {

	PLANNED, FUNDED, REVISION_REQUESTED, SUBMITTED, ACCEPTED, CANCELLED, SETTLED;

	/** A Held Milestone: its funds are paid in and not yet released or refunded. */
	static Set<MilestoneState> held() {
		return EnumSet.of(FUNDED, REVISION_REQUESTED, SUBMITTED);
	}

}
