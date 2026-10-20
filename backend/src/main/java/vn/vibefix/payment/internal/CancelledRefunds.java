package vn.vibefix.payment.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.payment.Ledger;
import vn.vibefix.payment.internal.Refunds.Refund;

/**
 * M7 (FR-40, FR-43, AD-15): a cancelled Held Milestone is refunded exactly what is releasable, against its
 * FUND entries oldest first, through the refund operation; released money is never taken back. A second
 * call finds nothing left. M8's settlement refunds its decided total the same way (FR-54).
 */
@Component
class CancelledRefunds {

	private final JdbcClient jdbc;

	private final Ledger ledger;

	private final PaymentOrders orders;

	private final Refunds refunds;

	CancelledRefunds(JdbcClient jdbc, Ledger ledger, PaymentOrders orders, Refunds refunds) {
		this.jdbc = jdbc;
		this.ledger = ledger;
		this.orders = orders;
		this.refunds = refunds;
	}

	@Transactional
	List<Refund> refund(UUID milestoneId) {
		return refund(milestoneId, this.ledger.releasable(milestoneId), Refunds.Reason.CANCELLED);
	}

	/** Refunds {@code amount} of the Milestone's FUNDs, oldest first; the caller checks it was all refunded. */
	@Transactional
	List<Refund> refund(UUID milestoneId, long amount, Refunds.Reason reason) {
		long left = amount;
		List<Refund> refunded = new ArrayList<>();
		if (left <= 0) {
			return refunded;
		}
		for (Map<String, Object> fund : this.jdbc.sql("""
				SELECT id, payment_order_id, amount_vnd FROM payment.ledger_entry
				WHERE milestone_id = :milestone AND entry_type = 'FUND' AND bucket = 'MILESTONE' AND payment_order_id IS NOT NULL
				ORDER BY created_at, id
				""").param("milestone", milestoneId).query().listOfRows()) {
			if (left <= 0) {
				break;
			}
			PaymentOrders.Order order = this.orders.byId((UUID) fund.get("payment_order_id")).orElse(null);
			if (order == null) {
				continue;
			}
			long take = Math.min(((Number) fund.get("amount_vnd")).longValue(), left);
			Optional<Refund> refund = this.refunds.request((UUID) fund.get("id"), order, take, reason);
			if (refund.isPresent()) {
				refunded.add(refund.get());
				left -= refund.get().amountVnd();
			}
		}
		return refunded;
	}

}
