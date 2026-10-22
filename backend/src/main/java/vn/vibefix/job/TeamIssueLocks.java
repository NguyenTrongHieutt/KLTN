package vn.vibefix.job;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * What a Team Issue write needs from {@code job} (AD-4, AD-40): the Job row lock that every raise, answer,
 * acceptance, escalation, decision, Deadline outcome, and moot ending takes first, so that a hold and a
 * release (M5, M8) never interleave. The lock writes nothing and changes neither {@code Job.version} nor
 * {@code crewVersion}; it never takes the busy marker, and a freeze never blocks it.
 */
public interface TeamIssueLocks {

	/**
	 * The Job under its row lock, with the named Milestone as the issue reads it.
	 * @param mentorId the Job's Mentor, who answers the issue
	 * @param milestoneNumber the Milestone's number on the Job; {@code null} without a Milestone
	 * @param milestoneHeld whether the Milestone is Funded, Revision Requested, or Submitted
	 */
	record TeamIssueJob(UUID jobId, UUID productId, String title, String state, @Nullable UUID mentorId,
			@Nullable Integer milestoneNumber, boolean milestoneHeld) {
	}

	/**
	 * {@code job.lockForTeamIssue}: locks the Job row.
	 * @param milestoneId the Milestone of a {@code SHARE} issue; {@code null} for a {@code RATING} issue
	 * @param requireHeld used by a raise: refuse unless the Milestone is Held
	 * @return empty for an unknown Job, or a Milestone that is not the Job's
	 * @throws vn.vibefix.shared.ApiException {@code GUARD_FAILED MILESTONE_NOT_HELD} with {@code requireHeld}
	 * on a Milestone that is not Held
	 */
	Optional<TeamIssueJob> lockForTeamIssue(UUID jobId, @Nullable UUID milestoneId, boolean requireHeld);

	/** The Jobs where the Student's Crew row was ever active, the newest first (AD-41 {@code EVER_ACTIVE}). */
	List<UUID> crewJobsOf(UUID studentId);

}
