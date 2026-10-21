package vn.vibefix.payment.internal;

import java.time.Instant;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.vibefix.payment.internal.VnpaySandbox.Answer;

/**
 * AD-15's refund gateway (Spike S2, 2026-10-21): one VNPay {@code refund} per FUND, carrying the whole
 * amount it returns, type {@code 02} when that is the FUND's paid amount and {@code 03} otherwise.
 * VNPay refunds a transaction once, and {@code querydr} never shows the refund, so its signed answer is
 * the result: {@code 00} confirms the refund with its own {@code vnp_TransactionNo}; {@code 93},
 * {@code 94} ("Too many refund count": refunded already), or any other signed code is refused into the
 * FR-64 queue, where an Administrator checks the merchant portal. An answer without a valid signature is
 * no answer, and is retried with the same {@code vnp_RequestId}.
 */
@Component
class VnpayRefund implements RefundGateway {

	static final String KEY = "VNPAY";

	/** VNPay's refund of the whole payment. */
	static final String FULL = "02";

	/** VNPay's refund of part of the payment. */
	static final String PARTIAL = "03";

	/** Who asks for the refund, as VNPay records it. */
	static final String CREATED_BY = "vibefix";

	/** The address VNPay records for a call from the server. */
	static final String SERVER_ADDRESS = "127.0.0.1";

	/** The paid transaction a FUND recorded, as the refund names it. */
	private record Paid(String transactionNo, long amountVnd, String txnRef, Instant createdAt) {
	}

	private final VnpaySandbox sandbox;

	private final JdbcClient jdbc;

	VnpayRefund(VnpaySandbox sandbox, JdbcClient jdbc) {
		this.sandbox = sandbox;
		this.jdbc = jdbc;
	}

	@Override
	public String key() {
		return KEY;
	}

	@Override
	public Result refund(Refunds.Refund refund) {
		if (this.sandbox.missingSetting() != null) {
			return new Result.Refused("VNPAY_NOT_CONFIGURED");
		}
		Paid paid = this.jdbc.sql("""
				SELECT f.vnp_transaction_no, f.amount_vnd, o.txn_ref, o.created_at
				FROM payment.ledger_entry f JOIN payment.payment_order o ON o.id = f.payment_order_id
				WHERE f.id = :fund AND f.entry_type = 'FUND'
				""")
			.param("fund", refund.fundEntryId())
			.query((rs, n) -> new Paid(rs.getString("vnp_transaction_no"), rs.getLong("amount_vnd"), rs.getString("txn_ref"),
					rs.getTimestamp("created_at").toInstant()))
			.optional()
			.orElse(null);
		if (paid == null) {
			return new Result.Refused("FUND_NOT_FOUND");
		}
		// vnp_TransactionDate is the payment's vnp_CreateDate, which the order's creation time wrote.
		Answer answer = this.sandbox.refund(refund.vnpRequestId(), paid.txnRef(),
				refund.amountVnd() == paid.amountVnd() ? FULL : PARTIAL, refund.amountVnd(), paid.transactionNo(),
				VnpaySandbox.TIME.format(paid.createdAt()), CREATED_BY, "Hoan tien vibefix " + paid.txnRef().substring(0, 8),
				SERVER_ADDRESS);
		if (answer.status() == 0) {
			return new Result.Unanswered("VNPAY_UNREACHABLE");
		}
		if (!answer.hashVerified()) {
			return new Result.Unanswered("VNPAY_UNSIGNED_ANSWER");
		}
		String code = answer.fields().getOrDefault("vnp_ResponseCode", "");
		if ("00".equals(code)) {
			return new Result.Confirmed(answer.fields().get("vnp_TransactionNo"));
		}
		return new Result.Refused("VNPAY_REFUND_" + (code.matches("[0-9A-Z]{1,4}") ? code : "UNKNOWN"));
	}

}
