package vn.vibefix.job;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

	/** {@link #activeJobCount} for a page of Mentors in one read; every Mentor asked for is a key (SG-40). */
	Map<UUID, Integer> activeJobCounts(Collection<UUID> mentorIds);

	/** Which of these Mentors have a free Student, from one {@code team} read and one {@code identity} read. */
	Set<UUID> withFreeStudents(Collection<UUID> mentorIds);

	/**
	 * Work history (AD-17): for each Mentor, per Job Type name, how many distinct Customers they
	 * closed a Job of that type for, so at most one Job counts per Customer–Mentor pair. A Mentor with
	 * none is left out, and is new (FR-14).
	 */
	Map<UUID, Map<String, Integer>> workHistory(Collection<UUID> mentorIds);

	/** Mentors who closed a Job of any of these Job Types (AD-17's pool beside shared Skills). */
	Set<UUID> experiencedIn(Collection<String> jobTypes);

}
