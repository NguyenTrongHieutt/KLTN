package vn.vibefix.payment.internal;

import org.jspecify.annotations.Nullable;

/**
 * Where a refund is carried out (AD-15). The gateway is fixed when a refund is requested:
 * {@link VnpayRefund}, or {@link LedgerRecordedRefund} for tests and as a fallback setting.
 */
interface RefundGateway {

	/** What the gateway answered for one attempt. */
	sealed interface Result {

		/**
		 * The money is back; the refund is confirmed.
		 * @param transactionNo the refund's own gateway transaction, when it has one
		 */
		record Confirmed(@Nullable String transactionNo) implements Result {
		}

		/** No answer to act on (unreachable, or not signed): try again with the same request. */
		record Unanswered(String code) implements Result {
		}

		/** The gateway answered and refused: an Administrator checks it (FR-64). */
		record Refused(String code) implements Result {
		}

	}

	/** The name stored on each refund, {@code LEDGER} or {@code VNPAY}. */
	String key();

	/** Carries out one refund. */
	Result refund(Refunds.Refund refund);

}
