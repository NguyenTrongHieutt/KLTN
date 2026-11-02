package vn.vibefix.payment;

import java.util.List;
import java.util.UUID;

/**
 * The holds that share Team Issues push down to {@code payment} (AD-3, AD-40): each Milestone's hold set
 * and the transfers its Team Issues recorded. {@code dispute} calls it in the Team Issue's transaction,
 * after {@code job.lockForTeamIssue} and the Team Issue rows, so the Milestone's ledger account lock comes
 * last (AD-4). Since Story 22.1 no release reads a hold or a transfer, and Story 22.4 removes them.
 */
public interface ShareHolds {

	/** A transfer a Team Issue's ending recorded: {@code points} from the Mentor's share in the pin to the raiser's. */
	record Transfer(UUID teamIssueId, UUID raiserId, int points) {
	}

	/**
	 * {@code payment.holdShares}: records the Team Issue's hold on the Milestone. A second call for the same Team Issue
	 * changes nothing.
	 * @throws vn.vibefix.shared.ApiException {@code GUARD_FAILED MILESTONE_NOT_HELD} when the Milestone has no ledger
	 * account or any release or pool, or the raiser is not a party of its pin
	 */
	void holdShares(UUID milestoneId, UUID teamIssueId, UUID raiserId);

	/**
	 * What the Milestone's split is taken from, for a Team Issue's preview: its pool once released, else what is
	 * releasable now.
	 */
	long basisOf(UUID milestoneId);

	/** {@code payment.transfersOf}: the transfers recorded on the Milestone, in the order they were recorded. */
	List<Transfer> transfersOf(UUID milestoneId);

	/**
	 * {@code payment.endHold}: ends a share Team Issue's hold under the ledger account lock, recording the transfer of
	 * {@code points} (0 for none). It writes nothing to the ledger. A second call for the same Team Issue changes nothing.
	 * @throws IllegalStateException {@code SHARE_TRANSFER_TOO_LARGE} when the transfers would sum above the Mentor's
	 * pinned share; nothing is written then
	 */
	void endHold(UUID teamIssueId, int points);

}
