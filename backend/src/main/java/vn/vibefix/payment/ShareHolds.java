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

	/** {@code payment.transfersOf}: the transfers recorded on the Milestone, in the order they were recorded. */
	List<Transfer> transfersOf(UUID milestoneId);

}
