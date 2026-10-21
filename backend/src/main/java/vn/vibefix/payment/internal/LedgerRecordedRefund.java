package vn.vibefix.payment.internal;

import org.springframework.stereotype.Component;

/**
 * "Hoàn tiền mô phỏng" (AD-15): the refund is recorded on vibefix only, as simulated test-mode money,
 * for the tests and as the fallback setting {@code vibefix.payment.refund-gateway=ledger}. It confirms at
 * once.
 */
@Component
class LedgerRecordedRefund implements RefundGateway {

	static final String KEY = "LEDGER";

	@Override
	public String key() {
		return KEY;
	}

	@Override
	public Result refund(Refunds.Refund refund) {
		return new Result.Confirmed(null);
	}

}
