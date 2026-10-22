package vn.vibefix.payment;

import java.util.List;
import java.util.UUID;

/**
 * The holds that share Team Issues push down to {@code payment} (AD-3, AD-40): each Milestone's hold set
 * and the transfers its Team Issues recorded. {@code dispute} calls it in the Team Issue's transaction,
 * after {@code job.lockForTeamIssue} and the Team Issue rows, so the Milestone's ledger account lock comes
 * last (AD-4).
 */
public interface ShareHolds {

	/** A transfer a Team Issue's ending recorded: {@code points} from the Mentor's share in the pin to the raiser's. */
	record Transfer(UUID teamIssueId, UUID raiserId, int points) {
	}

	/**
	 * {@code payment.holdShares}: from now until the issue ends, the Milestone's release holds the Mentor's
	 * part and the raiser's. A second call for the same Team Issue changes nothing.
	 * @throws vn.vibefix.shared.ApiException {@code GUARD_FAILED MILESTONE_NOT_HELD} when the Milestone has
	 * no ledger account or any RELEASE or RELEASE_HELD, or the raiser is not a party of its pin
	 */
	void holdShares(UUID milestoneId, UUID teamIssueId, UUID raiserId);

	/**
	 * What the Milestone's split is taken from, for a Team Issue's preview: the base of its release once
	 * released, else what is releasable now.
	 */
	long basisOf(UUID milestoneId);

	/** {@code payment.transfersOf}: the transfers recorded on the Milestone, in the order they were recorded. */
	List<Transfer> transfersOf(UUID milestoneId);

	/**
	 * {@code payment.endHold}: executes a share Team Issue's ending under the ledger account lock. It records
	 * the transfer of {@code points} (0 for none) and ends the hold, so the Mentor leaves the hold set with
	 * the Milestone's last open one. Before the release nothing else is written, and the release applies the
	 * transfer. After it, the whole split of the release's base is recomputed by the pin adjusted by every
	 * recorded transfer, and only the raiser and the Mentor get entries: a HELD_RELEASED for each unreversed
	 * RELEASE_HELD, then a RELEASE of the new part, or a new RELEASE_HELD for a Mentor still held; a part of
	 * zero has none. Paid out does not change. A second call for the same Team Issue writes nothing.
	 * @throws IllegalStateException {@code SHARE_TRANSFER_TOO_LARGE} when the transfers would sum above the
	 * Mentor's pinned share, or when what it writes does not equal what it reverses or would leave
	 * {@code releasable} below 0 (AD-5); nothing is written then
	 */
	void endHold(UUID teamIssueId, int points);

}
