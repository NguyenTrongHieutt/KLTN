package vn.vibefix.job;

import java.util.UUID;

/**
 * What {@code dispute} applies when it executes a decision (FR-54, AD-31): M8 on the Held Milestone, then
 * the Job's next state, each as a DECISION that runs while the Job is frozen. The caller writes the money
 * and lifts the freeze.
 */
public interface MilestoneSettlements {

	/** The decided next state: continue, end through Closing, or cancel. */
	enum Next {
		CONTINUE, END, CANCEL
	}

	/**
	 * M8 with the totals.
	 * @return the M8 transition, which keys the money entries
	 */
	UUID settle(UUID jobId, UUID milestoneId, long releaseVnd, long refundVnd);

	/**
	 * The next state: nothing; or M6 for each Planned Milestone, then J8 (and J9 for an Assessment Job);
	 * or M6 and J10 without Accepted work, J8 and J9 to Cancelled with it.
	 */
	void next(UUID jobId, Next next);

	/** Whether a Planned Milestone remains, so "Tiếp tục" is offered. */
	boolean plannedRemains(UUID jobId);

	/** A live Milestone as ADM-4 names it: its number from 1, title, and state. */
	record MilestoneName(UUID milestoneId, int number, String title, String state) {
	}

	/** The Milestone of the Job, when it is live. */
	java.util.Optional<MilestoneName> milestone(UUID jobId, UUID milestoneId);

}
