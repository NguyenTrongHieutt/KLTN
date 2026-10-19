package vn.vibefix.team;

import java.util.UUID;

/**
 * What other modules may show about Teams (AD-37). It decides nothing: a Crew's eligibility comes
 * from the reservation API of Story 1.19, never from these reads.
 */
public interface Teams {

	/** The number of Students a Team holds at most (FR-6). */
	int SIZE_LIMIT = 10;

	/** Whether the Student is in the Mentor's Team now. */
	boolean isMember(UUID mentorId, UUID studentId);

	/** How many Students are in the Mentor's Team now; 0 without a Team. */
	int sizeOf(UUID mentorId);

}
