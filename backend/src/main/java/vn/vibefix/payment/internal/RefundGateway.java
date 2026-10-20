package vn.vibefix.payment.internal;

/**
 * Where a refund is carried out (AD-15). The gateway is fixed when a refund is requested. While spike
 * S2 is open only {@link LedgerRecordedRefund} exists; {@code VnpayRefund} joins once S2 confirms that
 * the sandbox merchant has refunds.
 */
interface RefundGateway {

	/** What the gateway answered for one attempt. */
	enum Result {
		/** The money is back; the refund is confirmed. */
		CONFIRMED,
		/** The gateway accepted it and has not confirmed yet; try reading it later, never resend. */
		PENDING
	}

	/** The name stored on each refund, {@code LEDGER} or {@code VNPAY}. */
	String key();

	/** Carries out, or reads back, one refund. */
	Result refund(Refunds.Refund refund);

}
