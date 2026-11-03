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
 * {@code payment}'s part in exports, erasure, and Product deletion (AD-24): a person exports their Payment Orders and
 * their own ledger lines, the parts paid to them and the entries of their own orders. The ledger, its accounts, the
 * Payment Orders, and the refunds are financial records under NFR-10's legal retention, and other people's Balances
 * stand on them (A-01): a Product deletion and an erasure keep them, and an erasure anonymizes the Customer on the
 * orders and refunds. A running Job already keeps its Customer from being erased ({@code job}'s blocker).
 */
@Component
class PaymentPersonalData implements PersonalDataParticipant {

	private final JdbcClient jdbc;

	PaymentPersonalData(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Set<String> tables() {
		return Set.of("payment.payment_order", "payment.ledger_account", "payment.ledger_entry", "payment.refund");
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
		List<Map<String, Object>> lines = ledgerLines(accountId);
		if (orders.isEmpty() && lines.isEmpty()) {
			return Optional.empty();
		}
		Map<String, Object> data = new LinkedHashMap<>();
		if (!orders.isEmpty()) {
			data.put("paymentOrders", orders);
		}
		if (!lines.isEmpty()) {
			data.put("ledgerLines", lines);
		}
		return Optional.of(new PersonalDataSection("payment", data));
	}

	/**
	 * The person's own ledger lines (AD-20, AD-24, NFR-11): each RELEASE paid to them, and the FUND, TOPUP_APPLIED and
	 * refund entries of their own Payment Orders, each with its bucket, so an applied top-up reads as one payment. A pool
	 * and its reversal name no party and are never theirs, and nothing of another person's is listed.
	 */
	private List<Map<String, Object>> ledgerLines(UUID accountId) {
		return this.jdbc.sql("""
				SELECT e.entry_type, e.bucket, e.job_id, e.milestone_id, e.amount_vnd, e.split_version_no, e.share_percent,
				    e.base_amount_vnd, o.txn_ref, e.created_at
				FROM payment.ledger_entry e LEFT JOIN payment.payment_order o ON o.id = e.payment_order_id
				WHERE (e.entry_type = 'RELEASE' AND e.party_account_id = :id)
				   OR (e.entry_type IN ('FUND', 'TOPUP_APPLIED', 'REFUND_REQUESTED', 'REFUND_CONFIRMED')
				       AND o.customer_account_id = :id)
				ORDER BY e.created_at, e.id
				""").param("id", accountId).query((rs, n) -> {
			UUID milestone = rs.getObject("milestone_id", UUID.class);
			Map<String, Object> line = new LinkedHashMap<>();
			line.put("type", rs.getString("entry_type"));
			line.put("bucket", rs.getString("bucket"));
			line.put("jobId", rs.getObject("job_id", UUID.class).toString());
			line.put("milestoneId", milestone == null ? null : milestone.toString());
			line.put("amountVnd", rs.getLong("amount_vnd"));
			line.put("splitVersionNo", rs.getObject("split_version_no", Integer.class));
			line.put("percent", rs.getObject("share_percent", Integer.class));
			line.put("baseAmountVnd", rs.getObject("base_amount_vnd", Long.class));
			line.put("paymentOrderReference", rs.getString("txn_ref"));
			line.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
			return line;
		}).list();
	}

	@Override
	public void eraseAccount(UUID accountId) {
		for (String table : List.of("payment.payment_order", "payment.refund")) {
			this.jdbc.sql("UPDATE " + table + " SET customer_account_id = NULL WHERE customer_account_id = :id")
				.param("id", accountId)
				.update();
		}
	}

	@Override
	public void deleteProduct(UUID productId) {
		// Nothing goes: the ledger, the Payment Orders and the refunds stay under legal retention (AD-24, A-01).
	}

	@Override
	public List<ErasureBlocker> erasureBlockers(UUID accountId) {
		return List.of();
	}

}
