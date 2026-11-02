package vn.vibefix.payment.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.vibefix.shared.ErasureBlocker;
import vn.vibefix.shared.PersonalDataParticipant;
import vn.vibefix.shared.PersonalDataSection;

/**
 * {@code payment}'s part in exports, erasure, and Product deletion (AD-24): a Customer exports their
 * Payment Orders. The ledger, its accounts, the Payment Orders, and the refunds are financial records under NFR-10's
 * legal retention, and other people's Balances stand on them (A-01): a Product deletion and an erasure keep them, and an
 * erasure anonymizes the Customer on the orders and refunds. A running Job already keeps its Customer from being erased
 * ({@code job}'s blocker). Only the share holds of those Jobs go, until Story 22.4 retires the rest of them.
 */
@Component
class PaymentPersonalData implements PersonalDataParticipant {

	private final JdbcClient jdbc;

	PaymentPersonalData(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Set<String> tables() {
		return Set.of("payment.payment_order", "payment.ledger_account", "payment.ledger_entry", "payment.refund",
				"payment.share_hold");
	}

	@Override
	public Optional<PersonalDataSection> export(UUID accountId) {
		List<Map<String, Object>> orders = this.jdbc.sql("""
				SELECT txn_ref, amount_vnd, status, created_at, finished_at FROM payment.payment_order
				WHERE customer_account_id = :id ORDER BY created_at, id
				""").param("id", accountId).query((rs, n) -> {
			Map<String, Object> order = new LinkedHashMap<>();
			order.put("reference", rs.getString("txn_ref"));
			order.put("amountVnd", rs.getLong("amount_vnd"));
			order.put("status", rs.getString("status"));
			order.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
			order.put("finishedAt", rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant().toString());
			return order;
		}).list();
		return orders.isEmpty() ? Optional.empty() : Optional.of(new PersonalDataSection("payment", Map.of("paymentOrders", orders)));
	}

	@Override
	public void eraseAccount(UUID accountId) {
		this.jdbc.sql("""
				DELETE FROM payment.share_hold WHERE job_id IN (SELECT job_id FROM payment.payment_order WHERE customer_account_id = :id)
				""").param("id", accountId).update();
		for (String table : List.of("payment.payment_order", "payment.refund")) {
			this.jdbc.sql("UPDATE " + table + " SET customer_account_id = NULL WHERE customer_account_id = :id")
				.param("id", accountId)
				.update();
		}
	}

	@Override
	public void deleteProduct(UUID productId) {
		this.jdbc.sql("DELETE FROM payment.share_hold WHERE product_id = :id").param("id", productId).update();
	}

	@Override
	public List<ErasureBlocker> erasureBlockers(UUID accountId) {
		return List.of();
	}

}
