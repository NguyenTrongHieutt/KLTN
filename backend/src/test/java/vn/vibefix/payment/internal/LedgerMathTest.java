package vn.vibefix.payment.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import vn.vibefix.payment.internal.LedgerMath.Entry;

/** AD-15's one definition of releasable and released money. */
class LedgerMathTest {

	@Test
	void releasableIsFundedLessPaidOutLessRequestedRefunds() {
		List<Entry> entries = List.of(new Entry("FUND", "MILESTONE", 2_000_000), new Entry("TOPUP_APPLIED", null, 500_000),
				new Entry("RELEASE", null, 400_000), new Entry("REFUND_REQUESTED", "MILESTONE", 100_000),
				new Entry("FUND", "UNAPPLIED", 300_000), new Entry("REFUND_REQUESTED", "UNAPPLIED", 300_000));

		assertThat(LedgerMath.funded(entries)).as("the unapplied bucket is not the Milestone's").isEqualTo(2_500_000);
		assertThat(LedgerMath.releasable(entries)).isEqualTo(2_000_000);
		assertThat(LedgerMath.releasedTotal(entries)).isEqualTo(400_000);
	}

	@Test
	void payingAHoldOutLeavesPaidOutAndTheReleasedTotalUnchanged() {
		List<Entry> entries = new ArrayList<>(List.of(new Entry("FUND", "MILESTONE", 1_000_000), new Entry("RELEASE", null, 700_000),
				new Entry("RELEASE_HELD", null, 300_000)));
		long paidOut = LedgerMath.paidOut(entries);
		long released = LedgerMath.releasedTotal(entries);

		entries.add(new Entry("HELD_RELEASED", null, 300_000));
		entries.add(new Entry("RELEASE", null, 300_000));

		assertThat(LedgerMath.paidOut(entries)).isEqualTo(paidOut).isEqualTo(1_000_000);
		assertThat(LedgerMath.releasedTotal(entries)).isEqualTo(released);
		assertThat(LedgerMath.releasable(entries)).isZero();
	}

	@Test
	void anAppliedTopUpCountsOnceAndItsRefundCountsAgainstTheMilestone() {
		// Story 14.2: the top-up's FUND sits in the Change Request bucket; its TOPUP_APPLIED is what counts.
		List<Entry> entries = List.of(new Entry("FUND", "MILESTONE", 1_000_000), new Entry("FUND", "CHANGE_REQUEST", 200_000),
				new Entry("TOPUP_APPLIED", null, 200_000), new Entry("REFUND_REQUESTED", "CHANGE_REQUEST", 200_000),
				new Entry("RELEASE", null, 1_000_000));

		assertThat(LedgerMath.funded(entries)).isEqualTo(1_200_000);
		assertThat(LedgerMath.refundsRequested(entries)).isEqualTo(200_000);
		assertThat(LedgerMath.releasable(entries)).isZero();
		assertThat(LedgerMath.balanced(entries)).isTrue();
	}

	@Test
	void aFinalMilestoneBalancesWhenPaidOutAndRefundsMakeUpTheFund() {
		assertThat(LedgerMath.balanced(List.of(new Entry("FUND", "MILESTONE", 1_000_000), new Entry("RELEASE", null, 600_000),
				new Entry("REFUND_REQUESTED", "MILESTONE", 400_000)))).isTrue();
		assertThat(LedgerMath.balanced(List.of(new Entry("FUND", "MILESTONE", 1_000_000), new Entry("RELEASE", null, 600_000))))
			.isFalse();
	}

}
