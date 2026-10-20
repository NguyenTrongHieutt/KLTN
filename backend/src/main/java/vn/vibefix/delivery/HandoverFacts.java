package vn.vibefix.delivery;

import java.util.UUID;

/** How many of a Job's Milestones the Mentor handed over (FR-50), for its Portfolio Entries. */
public interface HandoverFacts {

	int milestonesHandedOver(UUID jobId);

}
