package vn.vibefix.payment.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.job.MilestoneFunding;
import vn.vibefix.payment.Releases;
import vn.vibefix.shared.IdGenerator;

/**
 * {@link Releases} (FR-42, AD-5, AD-15): under the Milestone's ledger account lock, one RELEASE_POOLED of the whole
 * release, which a partial unique index allows once per Milestone, plus any REFUND_REQUESTED. The transition is refused
 * unless they sum to what was releasable. Nobody is paid here: {@link SplitPoolExecution} turns the pool into RELEASE
 * entries once the split is final. The Customer only ever sees the released total (AD-20).
 */
@Component
class MilestoneReleases implements Releases {

	private final JdbcClient jdbc;

	private final JdbcLedger ledger;

	private final MilestoneFunding funding;

	private final IdGenerator ids;

	private final Clock clock;

	private final CancelledRefunds refunds;

	MilestoneReleases(JdbcClient jdbc, JdbcLedger ledger, MilestoneFunding funding, IdGenerator ids, Clock clock,
			CancelledRefunds refunds) {
		this.jdbc = jdbc;
		this.ledger = ledger;
		this.funding = funding;
		this.ids = ids;
		this.clock = clock;
		this.refunds = refunds;
	}

	@Override
	@Transactional
	public void release(UUID jobId, UUID milestoneId, UUID causeId) {
		UUID productId = lockAccount(milestoneId);
		long releasable = this.ledger.releasable(milestoneId);
		if (releasable <= 0) {
			throw new IllegalStateException("The Milestone " + milestoneId + " has nothing to release");
		}
		long price = this.funding.byId(jobId)
			.flatMap(job -> job.milestones().stream().filter(milestone -> milestone.milestoneId().equals(milestoneId)).findFirst())
			.map(milestone -> milestone.priceVnd())
			.orElse(0L);
		long cut = price > 0 && releasable > price ? releasable - price : 0;
		pool(jobId, productId, milestoneId, causeId, releasable - cut);
		refund(milestoneId, cut, Refunds.Reason.PRICE_CUT);
	}

	@Override
	@Transactional
	public void settle(UUID jobId, UUID milestoneId, UUID causeId, long releaseVnd, long refundVnd) {
		UUID productId = lockAccount(milestoneId);
		long releasable = this.ledger.releasable(milestoneId);
		if (releaseVnd < 0 || refundVnd < 0 || releaseVnd + refundVnd != releasable) {
			throw new IllegalStateException("The settlement of " + milestoneId + " does not sum to " + releasable);
		}
		if (releaseVnd > 0) {
			pool(jobId, productId, milestoneId, causeId, releaseVnd);
		}
		refund(milestoneId, refundVnd, Refunds.Reason.DISPUTE_DECISION);
	}

	/** The account's row lock, and the Product its entries name. */
	private UUID lockAccount(UUID milestoneId) {
		return this.jdbc.sql("SELECT product_id FROM payment.ledger_account WHERE milestone_id = :id FOR UPDATE")
			.param("id", milestoneId)
			.query(UUID.class)
			.optional()
			.orElseThrow(() -> new IllegalStateException("The Milestone " + milestoneId + " has no ledger account"));
	}

	private void pool(UUID jobId, UUID productId, UUID milestoneId, UUID causeId, long amount) {
		this.jdbc.sql("""
				INSERT INTO payment.ledger_entry (id, operation_key, entry_type, job_id, product_id, milestone_id, amount_vnd, created_at)
				VALUES (:id, :key, 'RELEASE_POOLED', :job, :product, :milestone, :amount, :now)
				""")
			.param("id", this.ids.newId())
			.param("key", "RELEASE_POOLED:" + causeId)
			.param("job", jobId)
			.param("product", productId)
			.param("milestone", milestoneId)
			.param("amount", amount)
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
	}

	/** The refund written in full, which leaves nothing releasable: the pool and the refund made up all of it. */
	private void refund(UUID milestoneId, long amount, Refunds.Reason reason) {
		long refunded = this.refunds.refund(milestoneId, amount, reason).stream().mapToLong(refund -> refund.amountVnd()).sum();
		if (refunded != amount || this.ledger.releasable(milestoneId) != 0) {
			throw new IllegalStateException("The release of " + milestoneId + " could not refund " + amount);
		}
	}

}
