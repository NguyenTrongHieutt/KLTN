package vn.vibefix.payment;

import java.util.UUID;

/**
 * The release of an accepted or settled Milestone (FR-42, AD-5, AD-15): the whole release is held in one RELEASE_POOLED
 * with no party, and nobody, the Mentor included, is paid until the Milestone's split is final. The module that applies
 * M5 or M8 calls it in the same transaction, after the transition created the split, so the Job row is locked before the
 * Milestone's ledger account. Each call is refused unless what it writes sums to what is releasable under that lock.
 */
public interface Releases {

	/**
	 * Holds an accepted Milestone's release in its pool. A Held Milestone whose price a Change Request cut (FR-36) pools
	 * its new price, and the rest of what it holds is refunded now ({@code PRICE_CUT}).
	 * @param causeId the M5 transition, which keys the pool ({@code RELEASE_POOLED:{causeId}})
	 * @throws IllegalStateException when the Milestone has nothing to release, or when the refund could not be written
	 * in full
	 */
	void release(UUID jobId, UUID milestoneId, UUID causeId);

	/**
	 * A decided settlement (FR-54, AD-31), after M8 in the same transaction: {@code releaseVnd} held in the pool as
	 * {@link #release} holds it, none with no release, and {@code refundVnd} refunded against the Milestone's FUNDs.
	 * @param causeId the M8 transition
	 * @throws IllegalStateException unless the two sum to exactly what is releasable, or when the refund could not be
	 * written in full
	 */
	void settle(UUID jobId, UUID milestoneId, UUID causeId, long releaseVnd, long refundVnd);

}
