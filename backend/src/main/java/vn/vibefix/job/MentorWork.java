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

	/**
	 * The Milestones a Mentor's Jobs had accepted with a due date, and how many of them were on time by FR-62's
	 * formula (X12): the accepted handover was submitted before the end of the due day, Vietnam time.
	 * @param onTime the ones on time
	 * @param total every accepted Milestone with a due date
	 */
	record OnTime(int onTime, int total) {

		public static final OnTime NONE = new OnTime(0, 0);

	}

	/**
	 * {@link OnTime} as Mentor for a page of people in one read (AD-17, X12); every person asked for is a key, with
	 * {@link OnTime#NONE} for none.
	 */
	Map<UUID, OnTime> onTimeMilestones(Collection<UUID> mentorIds);

}
