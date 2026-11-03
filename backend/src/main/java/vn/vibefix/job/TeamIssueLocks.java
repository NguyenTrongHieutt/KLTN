package vn.vibefix.job;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * What a Rating Team Issue write needs from {@code job} (AD-4, AD-40): the Job row lock that every raise, answer,
 * acceptance, escalation, decision, Deadline outcome, and moot ending takes first. The lock writes nothing and changes
 * neither {@code Job.version} nor {@code crewVersion}; it never takes the busy marker, and a freeze never blocks it.
 */
public interface TeamIssueLocks {

	/**
	 * The Job under its row lock.
	 * @param mentorId the Job's Mentor, who answers the issue
	 */
	record TeamIssueJob(UUID jobId, UUID productId, String title, String state, @Nullable UUID mentorId) {
	}

	/**
	 * {@code job.lockForTeamIssue}: locks the Job row.
	 * @return empty for an unknown Job
	 */
	Optional<TeamIssueJob> lockForTeamIssue(UUID jobId);

	/** The Jobs where the Student's Crew row was ever active, the newest first (AD-41 {@code EVER_ACTIVE}). */
	List<UUID> crewJobsOf(UUID studentId);

}
