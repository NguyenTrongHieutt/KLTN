package vn.vibefix.payment;

import java.util.UUID;

/**
 * The one definition of a Milestone's money (AD-15), read by every payout and Dispute decision under
 * the Milestone's ledger account lock.
 */
public interface Ledger {

	/**
	 * Milestone-bucket FUND + TOPUP_APPLIED − paid out − REFUND_REQUESTED, where paid out = RELEASE +
	 * RELEASE_HELD − HELD_RELEASED.
	 */
	long releasable(UUID milestoneId);

	/** Paid out: the only released figure a Customer surface shows (AD-20). */
	long releasedTotal(UUID milestoneId);

}
