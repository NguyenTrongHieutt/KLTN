package vn.vibefix.job;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * The live state of a Job's Milestones (PRD section 4.0), as the Customer View shows it (FR-28): the
 * state names {@code PLANNED}, {@code FUNDED}, {@code REVISION_REQUESTED}, {@code SUBMITTED},
 * {@code ACCEPTED}, {@code CANCELLED}, and {@code SETTLED}.
 */
public interface MilestoneStates {

	/** Each live Milestone of the Job by its ID. */
	Map<UUID, String> statesOf(UUID jobId);

	/**
	 * One live Milestone as the Customer View shows it (FR-33, FR-34, FR-35).
	 * @param roundsLeft the Revision Rounds not used yet
	 * @param revisionCriteria how many criteria the latest revision request named
	 * @param autoAccepted accepted by Auto-acceptance
	 * @param refunded cancelled after it was funded, so its held money was refunded in full (FR-40)
	 * @param overdueSince when a Funded or Revision Requested Milestone became overdue (FR-48); a flag, not a state
	 */
	record MilestoneDetail(String state, int roundsLeft, @Nullable Integer revisionCriteria, boolean autoAccepted,
			boolean refunded, @Nullable Instant overdueSince) {
	}

	/** Each live Milestone of the Job by its ID, with its rounds and decision. */
	Map<UUID, MilestoneDetail> detailsOf(UUID jobId);

}
