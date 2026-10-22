package vn.vibefix.job;

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

}
