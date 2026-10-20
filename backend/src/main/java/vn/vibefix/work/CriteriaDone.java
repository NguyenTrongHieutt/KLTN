package vn.vibefix.work;

import java.util.UUID;

/**
 * {@code work.criteriaDone} (AD-33): the one definition of how many Acceptance Criteria of a Milestone
 * are done. Only criteria of the Agreement in force count, and a criterion is done only while its
 * seeded Story is in "Xong" because the Mentor approved it (FR-25, FR-28); a Delivery Report's met or
 * not met is never counted.
 */
public interface CriteriaDone {

	/** The Milestone's criteria in force, and how many of them are done. */
	record Progress(int done, int total) {
	}

	Progress criteriaDone(UUID jobId, UUID milestoneId);

}
