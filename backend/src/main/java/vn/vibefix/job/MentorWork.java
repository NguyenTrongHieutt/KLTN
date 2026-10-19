package vn.vibefix.job;

import java.util.List;
import java.util.UUID;

/**
 * FR-13's values, defined once (AD-33): who in a Mentor's Team is free for a new Crew, and how many
 * Jobs the Mentor holds against the FR-61 limit. {@code matching} refuses an Application with them,
 * and J3 checks them again.
 */
public interface MentorWork {

	/** {@code job.freeStudents}: the verified, unlocked members of the Mentor's Team on no running Crew. */
	List<UUID> freeStudents(UUID mentorId);

	/** {@code job.activeJobCount}: the Mentor's Jobs in Selecting, Agreement, Starting, Active, or Closing. */
	int activeJobCount(UUID mentorId);

	/** The FR-61 limit on active Jobs, read live. */
	int activeJobLimit();

}
