package vn.vibefix.job;

import java.util.Set;
import java.util.UUID;

/**
 * Which of a Job's Milestones are Held Milestones (FR-24, FR-41): paid in and not yet released or
 * refunded, so their Work Items may leave "Cần làm".
 */
public interface HeldMilestones {

	/** The IDs of the Job's Milestones in Funded, Revision requested, or Submitted. */
	Set<UUID> heldOf(UUID jobId);

}
