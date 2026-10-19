package vn.vibefix.team;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What other modules may know of Teams (AD-37). {@code job} decides a Crew member's eligibility with
 * {@link #reserveForCrew} alone; {@link #isMember} and {@link #sizeOf} serve display only.
 */
public interface Teams {

	/** The number of Students a Team holds at most (FR-6). */
	int SIZE_LIMIT = 10;

	/** Whether the Student is in the Mentor's Team now. */
	boolean isMember(UUID mentorId, UUID studentId);

	/** How many Students are in the Mentor's Team now; 0 without a Team. */
	int sizeOf(UUID mentorId);

	/**
	 * The Students in the Mentor's Team now, by account ID; empty without a Team. {@code job} counts
	 * the free ones among them (FR-13, AD-33).
	 */
	List<UUID> members(UUID mentorId);

	/**
	 * {@link #members} for a page of Mentors in one read (SG-40): every Mentor asked for is a key, with
	 * an empty list without a Team. Recommendations count free Students with it (AD-17).
	 */
	Map<UUID, List<UUID>> membersOf(Collection<UUID> mentorIds);

	/**
	 * Checks that the Student is in the Mentor's Team and reserves them for the Crew row, in the
	 * caller's transaction: it locks the Student's active membership first, so a leave, a removal, or
	 * a release running at once either ends it before (and this refuses) or waits and is refused.
	 * Reserving a Crew row again changes nothing.
	 * @throws vn.vibefix.shared.ApiException {@code GUARD_FAILED NOT_IN_TEAM} when the Student is in no
	 * Team or in another Mentor's
	 */
	void reserveForCrew(UUID crewRowId, UUID mentorId, UUID studentId, UUID jobId);

	/**
	 * Ends the Crew row's reservation, in the caller's transaction. A Student whose Mentor is gone, or
	 * who is locked, leaves the Team once their last reservation ends.
	 */
	void releaseCrew(UUID crewRowId);

}
