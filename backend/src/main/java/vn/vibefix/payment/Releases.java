package vn.vibefix.payment;

import java.util.List;
import java.util.UUID;

/**
 * The release of an accepted Milestone (FR-42, AD-5, AD-15, AD-41): one RELEASE per party of the Crew
 * Terms version pinned at M1, split from {@link Ledger#releasable} by {@link vn.vibefix.shared.CrewSplit}.
 * The module that applies M5 calls it in the same transaction, after M5, so the Job row is locked before
 * the Milestone's ledger account.
 */
public interface Releases {

	/**
	 * One party's part.
	 * @param sharePercent the party's percentage in the pinned version
	 */
	record Part(UUID partyId, long amountVnd, int sharePercent) {
	}

	/**
	 * Writes the release and tells each payee ({@code payment.funds_released}).
	 * @param causeId the M5 transition, which keys the entries ({@code RELEASE:{causeId}:{party}})
	 * @return the parts written, zero parts left out; they sum to what was releasable
	 * @throws IllegalStateException when the Milestone has no pinned Crew Terms or nothing to release
	 */
	List<Part> release(UUID jobId, UUID milestoneId, UUID causeId);

	/**
	 * A decided settlement (FR-54, AD-31), after M8 in the same transaction: {@code releaseVnd} split as
	 * {@link #release} splits it, and {@code refundVnd} refunded against the Milestone's FUNDs.
	 * @param causeId the M8 transition
	 * @return the release parts written
	 * @throws IllegalStateException unless the two sum to exactly what is releasable, or when the refund
	 * could not be written in full
	 */
	List<Part> settle(UUID jobId, UUID milestoneId, UUID causeId, long releaseVnd, long refundVnd);

}
