package vn.vibefix.job;

import java.util.Map;
import java.util.UUID;

/**
 * The live state of a Job's Milestones (PRD section 4.0), as the Customer View shows it (FR-28): the
 * state names {@code PLANNED}, {@code FUNDED}, {@code REVISION_REQUESTED}, {@code SUBMITTED},
 * {@code ACCEPTED}, {@code CANCELLED}, and {@code SETTLED}.
 */
public interface MilestoneStates {

	/** Each live Milestone of the Job by its ID. */
	Map<UUID, String> statesOf(UUID jobId);

}
