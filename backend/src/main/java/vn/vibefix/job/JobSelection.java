package vn.vibefix.job;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.IntSupplier;

import org.jspecify.annotations.Nullable;

/**
 * J3 as {@code matching} asks for it (FR-15, AD-4): the Customer selects an Application, and the Job
 * records its Mentor. {@code matching} owns the Applications and checks that this one belongs to the
 * Job before asking; the Job's lifecycle checks everything else. J12, the switch of an Open Job to self-mentored,
 * is asked for here too, because its guard counts the Job's Applications (FR-85).
 */
public interface JobSelection {

	/** The FR-15 signature period: the selected Mentor signs the NDA within it. */
	Duration NDA_PERIOD = Duration.ofDays(3);

	/** The selected Application's quote, which the Agreement draft starts from and is compared with (FR-16). */
	record SelectedQuote(long priceVnd, int timelineDays) {
	}

	/**
	 * Applies J3 to the Customer's Open Job.
	 * @param quote the selected Application's price and timeline
	 * @param expectedVersion the Job version the Customer's screen loaded
	 * @return the Job's version after J3
	 * @throws vn.vibefix.shared.ApiException {@code NOT_FOUND} for another Customer's Job; {@code STALE}
	 * for another version or a Job no longer Open; {@code GUARD_FAILED} with {@code ROLE_TAKEN},
	 * {@code MENTOR_NOT_AVAILABLE} (no longer GitHub-linked, or locked), or {@code MENTOR_AT_JOB_LIMIT}
	 */
	long select(UUID jobId, UUID customerId, UUID mentorId, UUID applicationId, SelectedQuote quote,
			long expectedVersion);

	/**
	 * Applies J12 to the Customer's Open Job (FR-85, AD-47): the Job becomes self-mentored and enters Agreement, with
	 * its Customer as its Mentor. {@code matching} owns the Applications, so it counts the open ones; the step reads
	 * the count under the Job's lock and refuses the switch while it is above zero.
	 * @param noticeVersion the version of notice N-34 the Customer acknowledged, if any
	 * @param openApplications the Job's Applications that are submitted, on hold or selected, counted when asked
	 * @param expectedVersion the Job version the Customer's screen loaded
	 * @return the Job's version after J12
	 * @throws vn.vibefix.shared.ApiException {@code NOT_FOUND} for another Customer's Job; {@code STALE} for another
	 * version or a Job no longer Open; {@code GUARD_FAILED} with {@code SELF_MENTORED_OFF} or {@code OPEN_APPLICATIONS}
	 * (with {@code count}); {@code VALIDATION SELF_MENTORED_NOTICE}
	 */
	long selfMentor(UUID jobId, UUID customerId, @Nullable String noticeVersion, IntSupplier openApplications,
			long expectedVersion);

	/**
	 * Applies J4 once {@code matching} recorded the selected Mentor's NDA signature.
	 * @throws vn.vibefix.shared.ApiException {@code NOT_FOUND} for anyone but the Job's Mentor;
	 * {@code STALE} for a Job no longer Selecting
	 */
	void confirm(UUID jobId, UUID mentorId, String ndaVersion, Instant signedAt);

}
