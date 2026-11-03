package vn.vibefix.payment.internal;

import java.util.Collection;

import org.jspecify.annotations.Nullable;

/**
 * AD-15's arithmetic over one Milestone's entries, kept apart so it can be proved without a database:
 * paid out = RELEASE_POOLED − POOL_RELEASED + RELEASE; releasable = Milestone-bucket FUND + TOPUP_APPLIED
 * − paid out − REFUND_REQUESTED on the Milestone, an applied top-up's own refunds included (its FUND sits in
 * the Change Request bucket, FR-36); the released total is paid out. A final split (its POOL_RELEASED with the RELEASE
 * entries that pay it) leaves paid out unchanged.
 */
final class LedgerMath {

	/** One entry as the formulas read it. */
	record Entry(String type, @Nullable String bucket, long amountVnd) {
	}

	private LedgerMath() {
	}

	static long funded(Collection<Entry> entries) {
		return sum(entries, "FUND", "MILESTONE") + sum(entries, "TOPUP_APPLIED", null);
	}

	static long paidOut(Collection<Entry> entries) {
		return sum(entries, "RELEASE_POOLED", null) - sum(entries, "POOL_RELEASED", null) + sum(entries, "RELEASE", null);
	}

	static long refundsRequested(Collection<Entry> entries) {
		return sum(entries, "REFUND_REQUESTED", "MILESTONE") + sum(entries, "REFUND_REQUESTED", "CHANGE_REQUEST");
	}

	static long releasable(Collection<Entry> entries) {
		return funded(entries) - paidOut(entries) - refundsRequested(entries);
	}

	static long releasedTotal(Collection<Entry> entries) {
		return paidOut(entries);
	}

	/** A final Milestone balances: paid out + requested refunds = funded (NFR-4, SG-23). */
	static boolean balanced(Collection<Entry> entries) {
		return paidOut(entries) + refundsRequested(entries) == funded(entries);
	}

	private static long sum(Collection<Entry> entries, String type, @Nullable String bucket) {
		return entries.stream()
			.filter(entry -> entry.type().equals(type) && (bucket == null || bucket.equals(entry.bucket())))
			.mapToLong(Entry::amountVnd)
			.sum();
	}

}
