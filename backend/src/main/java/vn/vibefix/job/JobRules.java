package vn.vibefix.job;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The FR-61 rule values a Job was published with (AD-7): another module's Deadline for the Job runs by
 * the Job's copy, never by today's setting.
 */
public interface JobRules {

	/**
	 * @param rule the rule's name, for example {@code FINAL_MERGE_AUTHORIZATION_DAYS}
	 * @return the Job's copied value, else {@code fallback} for a Job published before the rule existed
	 */
	int value(UUID jobId, String rule, int fallback);

	/**
	 * When the Job's FR-40 Starting stall passes ({@code STARTING_STALL_DAYS} after J6), as its Deadline is due now: the
	 * due date of the Customer's J7 self-confirmation (Story 23.6b).
	 * @return empty while a freeze pauses it, once it passed, or for a Job not Starting
	 */
	Optional<Instant> startingDeadline(UUID jobId);

}
