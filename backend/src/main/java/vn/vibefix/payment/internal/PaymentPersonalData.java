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
 * Payment Orders. The money is test-mode only, so the orders and the ledger of a Customer's Jobs go
 * with those Jobs, and with the Product; a running Job already keeps its Customer from being erased
 * ({@code job}'s blocker).
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
		List<UUID> jobs = this.jdbc.sql("SELECT DISTINCT job_id FROM payment.payment_order WHERE customer_account_id = :id")
			.param("id", accountId)
			.query(UUID.class)
			.list();
		if (!jobs.isEmpty()) {
			delete("job_id IN (:ids)", Map.of("ids", jobs));
		}
	}

	@Override
	public void deleteProduct(UUID productId) {
		delete("product_id = :id", Map.of("id", productId));
	}

	@Override
	public List<ErasureBlocker> erasureBlockers(UUID accountId) {
		return List.of();
	}

	private void delete(String where, Map<String, Object> params) {
		for (String table : List.of("payment.refund", "payment.share_hold", "payment.ledger_entry", "payment.ledger_account",
				"payment.payment_order")) {
			this.jdbc.sql("DELETE FROM " + table + " WHERE " + where).params(params).update();
		}
	}

}
