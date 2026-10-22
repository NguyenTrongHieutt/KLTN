package vn.vibefix.payment.internal;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.payment.Ledger;
import vn.vibefix.payment.internal.LedgerMath.Entry;

/** {@link Ledger} over {@code payment.ledger_entry}: the caller holds the account lock when it decides. */
@Component
class JdbcLedger implements Ledger {

	private final JdbcClient jdbc;

	JdbcLedger(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	@Transactional(readOnly = true)
	public long releasable(UUID milestoneId) {
		return LedgerMath.releasable(entries(milestoneId));
	}

	@Override
	@Transactional(readOnly = true)
	public long releasedTotal(UUID milestoneId) {
		return LedgerMath.releasedTotal(entries(milestoneId));
	}

	@Override
	@Transactional(readOnly = true)
	public EvidenceView evidenceView(UUID milestoneId) {
		List<Entry> entries = entries(milestoneId);
		List<MoneyIn> in = this.jdbc.sql("""
				SELECT entry_type, bucket, amount_vnd, created_at FROM payment.ledger_entry
				WHERE milestone_id = :id AND entry_type IN ('FUND', 'TOPUP_APPLIED') ORDER BY created_at, id
				""").param("id", milestoneId).query((rs, n) -> new MoneyIn(rs.getString("entry_type"), rs.getString("bucket"),
				rs.getLong("amount_vnd"), rs.getTimestamp("created_at").toInstant())).list();
		List<RefundLine> refunds = this.jdbc.sql("""
				SELECT amount_vnd, reason, status, requested_at, confirmed_at FROM payment.refund
				WHERE milestone_id = :id ORDER BY requested_at, id
				""").param("id", milestoneId).query((rs, n) -> {
			Timestamp confirmed = rs.getTimestamp("confirmed_at");
			return new RefundLine(rs.getLong("amount_vnd"), rs.getString("reason"), rs.getString("status"),
					rs.getTimestamp("requested_at").toInstant(), confirmed == null ? null : confirmed.toInstant());
		}).list();
		return new EvidenceView(milestoneId, in, refunds, LedgerMath.releasedTotal(entries), LedgerMath.releasable(entries));
	}

	/** The Milestone's entries, every bucket. */
	List<Entry> entries(UUID milestoneId) {
		return this.jdbc.sql("SELECT entry_type, bucket, amount_vnd FROM payment.ledger_entry WHERE milestone_id = :id")
			.param("id", milestoneId)
			.query((rs, n) -> new Entry(rs.getString("entry_type"), rs.getString("bucket"), rs.getLong("amount_vnd")))
			.list();
	}

}
