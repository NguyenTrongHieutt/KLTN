package vn.vibefix.payment.internal;

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

	/** The Milestone's entries, every bucket. */
	List<Entry> entries(UUID milestoneId) {
		return this.jdbc.sql("SELECT entry_type, bucket, amount_vnd FROM payment.ledger_entry WHERE milestone_id = :id")
			.param("id", milestoneId)
			.query((rs, n) -> new Entry(rs.getString("entry_type"), rs.getString("bucket"), rs.getLong("amount_vnd")))
			.list();
	}

}
