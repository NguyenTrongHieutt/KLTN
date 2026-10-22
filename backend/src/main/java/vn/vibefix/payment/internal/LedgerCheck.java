package vn.vibefix.payment.internal;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
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
import vn.vibefix.operations.ExternalOperations;
import vn.vibefix.operations.OperationRequest;

/**
 * SG-23's daily ledger check (AD-15): a final Milestone whose paid out plus requested refunds differ
 * from what was funded, and an unapplied FUND that no refund reverses, each go to the failed-operations
 * queue as a {@value LedgerMismatchOperation#TYPE} operation, once a day. So do three of AD-40's hold
 * cases: a raiser's RELEASE_HELD still unreversed after their hold ended, a Mentor's RELEASE_HELD still
 * unreversed on a Milestone with no open hold, and an open hold on a Milestone that became final without a
 * release. The fourth, a Team Issue open too long, is {@code dispute}'s.
 */
@Component
class LedgerCheck {

	private static final Set<String> FINAL = Set.of("ACCEPTED", "CANCELLED", "SETTLED");

	private final JdbcClient jdbc;

	private final JdbcLedger ledger;

	private final MilestoneFunding funding;

	private final ExternalOperations operations;

	private final Clock clock;

	LedgerCheck(JdbcClient jdbc, JdbcLedger ledger, MilestoneFunding funding, ExternalOperations operations, Clock clock) {
		this.jdbc = jdbc;
		this.ledger = ledger;
		this.funding = funding;
		this.operations = operations;
		this.clock = clock;
	}

	@Scheduled(cron = "${vibefix.payment.ledger-check-cron:0 0 3 * * *}", zone = "Asia/Ho_Chi_Minh")
	void daily() {
		check();
	}

	/**
	 * Runs the check now.
	 * @return the IDs found out of balance: Milestones, unapplied FUND entries, the Team Issues of holds
	 * left on a final Milestone, and held entries left unreversed
	 */
	@Transactional
	List<UUID> check() {
		List<UUID> mismatches = new ArrayList<>();
		List<UUID> jobs = this.jdbc.sql("SELECT DISTINCT job_id FROM payment.ledger_account").query(UUID.class).list();
		for (UUID jobId : jobs) {
			FundingJob job = this.funding.byId(jobId).orElse(null);
			if (job == null) {
				continue;
			}
			for (FundingMilestone milestone : job.milestones()) {
				if (!FINAL.contains(milestone.state())) {
					continue;
				}
				if (!LedgerMath.balanced(this.ledger.entries(milestone.milestoneId()))) {
					mismatches.add(milestone.milestoneId());
				}
				mismatches.addAll(this.jdbc.sql("""
						SELECT h.team_issue_id FROM payment.share_hold h
						WHERE h.milestone_id = :id AND h.ended_at IS NULL
						  AND NOT EXISTS (SELECT 1 FROM payment.ledger_entry e
						                  WHERE e.milestone_id = h.milestone_id AND e.entry_type IN ('RELEASE', 'RELEASE_HELD'))
						""").param("id", milestone.milestoneId()).query(UUID.class).list());
			}
		}
		mismatches.addAll(this.jdbc.sql("""
				SELECT e.id FROM payment.ledger_entry e JOIN payment.share_hold h ON h.team_issue_id = e.hold_id
				WHERE e.entry_type = 'RELEASE_HELD' AND h.ended_at IS NOT NULL
				  AND NOT EXISTS (SELECT 1 FROM payment.ledger_entry r WHERE r.reverses_entry_id = e.id)
				""").query(UUID.class).list());
		mismatches.addAll(this.jdbc.sql("""
				SELECT e.id FROM payment.ledger_entry e
				WHERE e.entry_type = 'RELEASE_HELD' AND e.hold_id IS NULL
				  AND NOT EXISTS (SELECT 1 FROM payment.ledger_entry r WHERE r.reverses_entry_id = e.id)
				  AND NOT EXISTS (SELECT 1 FROM payment.share_hold h WHERE h.milestone_id = e.milestone_id AND h.ended_at IS NULL)
				""").query(UUID.class).list());
		mismatches.addAll(this.jdbc.sql("""
				SELECT f.id FROM payment.ledger_entry f
				WHERE f.entry_type = 'FUND' AND f.bucket = 'UNAPPLIED'
				  AND NOT EXISTS (SELECT 1 FROM payment.refund r WHERE r.fund_entry_id = f.id)
				""").query(UUID.class).list());
		LocalDate today = LocalDate.now(this.clock.withZone(VnpaySandbox.VIETNAM));
		for (UUID subject : mismatches) {
			this.operations.request(new OperationRequest(LedgerMismatchOperation.TYPE,
					UUID.nameUUIDFromBytes((subject + ":" + today).getBytes(StandardCharsets.UTF_8)),
					Map.of("subjectId", subject.toString(), "day", today.toString()), null, null, 0));
		}
		return mismatches;
	}

}
