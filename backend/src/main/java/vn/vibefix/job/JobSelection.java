package vn.vibefix.job;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * J3 as {@code matching} asks for it (FR-15, AD-4): the Customer selects an Application, and the Job
 * records its Mentor. {@code matching} owns the Applications and checks that this one belongs to the
 * Job before asking; the Job's lifecycle checks everything else.
 */
public interface JobSelection {

	/** The FR-15 signature period: the selected Mentor signs the NDA within it. */
	Duration NDA_PERIOD = Duration.ofDays(3);

	/**
	 * Applies J3 to the Customer's Open Job.
	 * @param expectedVersion the Job version the Customer's screen loaded
	 * @return the Job's version after J3
	 * @throws vn.vibefix.shared.ApiException {@code NOT_FOUND} for another Customer's Job; {@code STALE}
	 * for another version or a Job no longer Open; {@code GUARD_FAILED} with {@code MENTOR_NOT_AVAILABLE},
	 * {@code MENTOR_AT_JOB_LIMIT}, or {@code MENTOR_NO_FREE_STUDENT}
	 */
	long select(UUID jobId, UUID customerId, UUID mentorId, UUID applicationId, long expectedVersion);

	/**
	 * Applies J4 once {@code matching} recorded the selected Mentor's NDA signature.
	 * @throws vn.vibefix.shared.ApiException {@code NOT_FOUND} for anyone but the Job's Mentor;
	 * {@code STALE} for a Job no longer Selecting
	 */
	void confirm(UUID jobId, UUID mentorId, String ndaVersion, Instant signedAt);

}
