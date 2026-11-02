package vn.vibefix.payment;

import java.util.Map;
import java.util.UUID;

/**
 * A Milestone's split pool as a Split Resolution reads it (AD-15, AD-33): only {@code payment} knows the pool, so a decision's
 * VND lines are computed here, through {@code shared.CrewSplit}, on the server. It only reads: {@code payment}'s listener alone
 * moves the pool once the split is final.
 */
public interface SplitPools {

	/**
	 * What a set of percentages gives.
	 * @param pooledVnd the whole of the Milestone's release
	 * @param amounts each party's part, a party at 0 included; the remainder of a whole dong goes to the Mentor
	 */
	record Parts(long pooledVnd, Map<UUID, Long> amounts) {
	}

	/** The whole of the Milestone's release, 0 when it holds no pool. */
	long pooledVnd(UUID milestoneId);

	/**
	 * @param mentorPartyId who receives the remainder
	 * @param percents every party's whole percentage, summing to 100
	 */
	Parts parts(UUID milestoneId, UUID mentorPartyId, Map<UUID, Integer> percents);

}
