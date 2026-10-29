package vn.vibefix.job;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * FR-13's values, defined once (AD-33): how many Jobs a person holds as Mentor against the FR-61 limit, and their
 * work history. {@code matching} refuses a bid with the limit, and J3 checks it again; no free Student is asked for
 * (Story 21.2).
 */
public interface MentorWork {

	/** {@code job.activeJobCount}: the Mentor's Jobs in Selecting, Agreement, Starting, Active, or Closing. */
	int activeJobCount(UUID mentorId);

	/** The FR-61 limit on active Jobs, read live. */
	int activeJobLimit();

	/** {@link #activeJobCount} for a page of Mentors in one read; every Mentor asked for is a key (SG-40). */
	Map<UUID, Integer> activeJobCounts(Collection<UUID> mentorIds);

	/**
	 * Work history (AD-17): for each Mentor, per Job Type name, how many distinct Customers they
	 * closed a Job of that type for, so at most one Job counts per Customer–Mentor pair. A Mentor with
	 * none is left out, and is new (FR-14).
	 */
	Map<UUID, Map<String, Integer>> workHistory(Collection<UUID> mentorIds);

	/** Mentors who closed a Job of any of these Job Types (AD-17's pool beside shared Skills). */
	Set<UUID> experiencedIn(Collection<String> jobTypes);

}
