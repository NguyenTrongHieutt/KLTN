package vn.vibefix.payment.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.payment.Ledger;
import vn.vibefix.payment.internal.Refunds.Refund;

/**
 * M7 (FR-40, FR-43, AD-15): a cancelled Held Milestone is refunded exactly what is releasable, against its
 * FUND entries oldest first, through the refund operation; released money is never taken back. A second
 * call finds nothing left.
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
		long left = this.ledger.releasable(milestoneId);
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
			long amount = Math.min(((Number) fund.get("amount_vnd")).longValue(), left);
			PaymentOrders.Order order = this.orders.byId((UUID) fund.get("payment_order_id")).orElse(null);
			if (order == null) {
				continue;
			}
			this.refunds.request((UUID) fund.get("id"), order, amount, Refunds.Reason.CANCELLED).ifPresent(refund -> {
				refunded.add(refund);
			});
			left = this.ledger.releasable(milestoneId);
		}
		return refunded;
	}

}
