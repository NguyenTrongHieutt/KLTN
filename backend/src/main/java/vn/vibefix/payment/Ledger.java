package vn.vibefix.payment;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

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

	/**
	 * Money into the Milestone.
	 * @param type {@code FUND} or {@code TOPUP_APPLIED}
	 * @param bucket {@code MILESTONE}, or {@code CHANGE_REQUEST} for a top-up's own FUND
	 */
	record MoneyIn(String type, @Nullable String bucket, long amountVnd, Instant at) {
	}

	/**
	 * One refund of the Milestone.
	 * @param status {@code REQUESTED} or {@code CONFIRMED}
	 */
	record RefundLine(long amountVnd, String reason, String status, Instant requestedAt, @Nullable Instant confirmedAt) {
	}

	/** A Dispute's payment evidence: totals and refunds, never per-party release entries (AD-15, AD-31). */
	record EvidenceView(UUID milestoneId, List<MoneyIn> moneyIn, List<RefundLine> refunds, long releasedTotal,
			long releasable) {

		public EvidenceView {
			moneyIn = List.copyOf(moneyIn);
			refunds = List.copyOf(refunds);
		}

	}

	/** The Milestone's FUND and TOPUP_APPLIED entries, its refunds, and its totals, oldest first. */
	EvidenceView evidenceView(UUID milestoneId);

}
