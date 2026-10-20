package vn.vibefix.payment.internal;

import org.springframework.stereotype.Component;

/**
 * "Hoàn tiền mô phỏng" (AD-15): the refund is recorded on vibefix only, as simulated test-mode money,
 * because the sandbox merchant's refunds are not confirmed (spike S2). It confirms at once.
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
		return Result.CONFIRMED;
	}

}
