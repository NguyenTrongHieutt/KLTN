package vn.vibefix.payment.internal;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.job.MilestoneFunding;
import vn.vibefix.job.MilestoneFunding.FundingJob;
import vn.vibefix.job.MilestoneFunding.FundingMilestone;
import vn.vibefix.job.MilestoneSplits;
import vn.vibefix.job.MilestoneSplits.SplitState;
import vn.vibefix.job.MilestoneSplits.Status;
import vn.vibefix.operations.ExternalOperations;
import vn.vibefix.operations.OperationRequest;

/**
 * SG-23's daily ledger check (AD-15): a final Milestone whose paid out plus requested refunds differ
 * from what was funded, a Milestone of a gone Job included, and an unapplied FUND that no refund reverses, each go
 * to the failed-operations queue as a {@value LedgerMismatchOperation#TYPE} operation, once a day. A Team Issue open too
 * long is {@code dispute}'s.
 * <p>
 * Pooled money (NFR-4) goes there as a {@value SplitPoolOverdueOperation#TYPE} operation, once a day each: a
 * RELEASE_POOLED unreversed more than {@value #POOL_SLACK_DAYS} days after its split's latest due time unless the split
 * is {@code IN_RESOLUTION}, a status read from {@code job}, never {@code dispute} (A-12); a RELEASE_POOLED with no split;
 * and a split final for more than an hour without its POOL_RELEASED.
 */
@Component
class LedgerCheck {

	/** The slack after a pool's latest due time (AD-15). */
	static final int POOL_SLACK_DAYS = 7;

	private static final Duration FINAL_SLACK = Duration.ofHours(1);

	private static final Set<String> FINAL = Set.of("ACCEPTED", "CANCELLED", "SETTLED");

	private final JdbcClient jdbc;

	private final JdbcLedger ledger;

	private final MilestoneFunding funding;

	private final MilestoneSplits splits;

	private final ExternalOperations operations;

	private final Clock clock;

	LedgerCheck(JdbcClient jdbc, JdbcLedger ledger, MilestoneFunding funding, MilestoneSplits splits,
			ExternalOperations operations, Clock clock) {
		this.jdbc = jdbc;
		this.ledger = ledger;
		this.funding = funding;
		this.splits = splits;
		this.operations = operations;
		this.clock = clock;
	}

	@Scheduled(cron = "${vibefix.payment.ledger-check-cron:0 0 3 * * *}", zone = "Asia/Ho_Chi_Minh")
	void daily() {
		check();
	}

	/**
	 * Runs the check now.
	 * @return the IDs found out of balance: Milestones, unapplied FUND entries, overdue pools, and final splits with their
	 * pool unreversed
	 */
	@Transactional
	List<UUID> check() {
		List<UUID> mismatches = new ArrayList<>();
		List<UUID> jobs = this.jdbc.sql("SELECT DISTINCT job_id FROM payment.ledger_account").query(UUID.class).list();
		for (UUID jobId : jobs) {
			FundingJob job = this.funding.byId(jobId).orElse(null);
			if (job == null) {
				mismatches.addAll(unbalancedOfGoneJob(jobId));
				continue;
			}
			for (FundingMilestone milestone : job.milestones()) {
				if (!FINAL.contains(milestone.state())) {
					continue;
				}
				if (!LedgerMath.balanced(this.ledger.entries(milestone.milestoneId()))) {
					mismatches.add(milestone.milestoneId());
				}
			}
		}
		mismatches.addAll(this.jdbc.sql("""
				SELECT f.id FROM payment.ledger_entry f
				WHERE f.entry_type = 'FUND' AND f.bucket = 'UNAPPLIED'
				  AND NOT EXISTS (SELECT 1 FROM payment.refund r WHERE r.fund_entry_id = f.id)
				""").query(UUID.class).list());
		LocalDate today = LocalDate.now(this.clock.withZone(VnpaySandbox.VIETNAM));
		for (UUID subject : mismatches) {
			request(LedgerMismatchOperation.TYPE, subject, today);
		}
		List<UUID> overdue = overduePools();
		overdue.forEach(subject -> request(SplitPoolOverdueOperation.TYPE, subject, today));
		List<UUID> found = new ArrayList<>(mismatches);
		found.addAll(overdue);
		return found;
	}

	/**
	 * The ledger of a Job whose Product was deleted stays (AD-24). Its Milestones are all final, since a Product cannot be
	 * deleted while a Job pins it, so each must balance.
	 */
	private List<UUID> unbalancedOfGoneJob(UUID jobId) {
		return this.jdbc.sql("SELECT milestone_id FROM payment.ledger_account WHERE job_id = :job")
			.param("job", jobId)
			.query(UUID.class)
			.list()
			.stream()
			.filter(milestone -> !LedgerMath.balanced(this.ledger.entries(milestone)))
			.toList();
	}

	/** The pools past their deadlines, by entry, and the final splits still holding theirs, by split. */
	private List<UUID> overduePools() {
		Instant now = this.clock.instant();
		List<UUID> overdue = new ArrayList<>();
		this.jdbc.sql("""
				SELECT e.id, e.milestone_id FROM payment.ledger_entry e
				WHERE e.entry_type = 'RELEASE_POOLED'
				  AND NOT EXISTS (SELECT 1 FROM payment.ledger_entry r WHERE r.reverses_entry_id = e.id)
				ORDER BY e.created_at, e.id
				""").query((rs, n) -> Map.entry(rs.getObject("id", UUID.class), rs.getObject("milestone_id", UUID.class))).list()
			.forEach(pool -> {
				SplitState split = this.splits.splitOf(pool.getValue()).orElse(null);
				if (split == null || (split.status() != Status.IN_RESOLUTION && split.status() != Status.FINAL
						&& split.latestDueAt().plus(Duration.ofDays(POOL_SLACK_DAYS)).isBefore(now))) {
					overdue.add(pool.getKey());
				}
			});
		Set<UUID> reversed = new HashSet<>(this.jdbc
			.sql("SELECT milestone_id FROM payment.ledger_entry WHERE entry_type = 'POOL_RELEASED'")
			.query(UUID.class)
			.list());
		this.splits.finalBefore(now.minus(FINAL_SLACK))
			.stream()
			.filter(split -> !reversed.contains(split.milestoneId()))
			.forEach(split -> overdue.add(split.splitId()));
		return overdue;
	}

	/** One operation per subject and day. */
	private void request(String type, UUID subject, LocalDate today) {
		this.operations.request(new OperationRequest(type,
				UUID.nameUUIDFromBytes((subject + ":" + today).getBytes(StandardCharsets.UTF_8)),
				Map.of("subjectId", subject.toString(), "day", today.toString()), null, null, 0));
	}

}
