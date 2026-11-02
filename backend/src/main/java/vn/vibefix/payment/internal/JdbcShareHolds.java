package vn.vibefix.payment.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.job.Crews;
import vn.vibefix.job.Crews.ReleaseTerms;
import vn.vibefix.job.MilestoneFunding;
import vn.vibefix.job.MilestoneFunding.FundingJob;
import vn.vibefix.payment.ShareHolds;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;

/**
 * {@link ShareHolds} on {@code payment.share_hold} (AD-40): a hold is refused under the ledger account lock once
 * anything was released or pooled, so a hold and a release never both win. Since Story 22.1 the holds retire from the
 * money: a release reads none of them, an ending writes nothing to the ledger, and Story 22.4 removes the rest.
 */
@Component
class JdbcShareHolds implements ShareHolds {

	private final JdbcClient jdbc;

	private final Crews crews;

	private final JdbcLedger ledger;

	private final MilestoneFunding funding;

	private final Clock clock;

	JdbcShareHolds(JdbcClient jdbc, Crews crews, JdbcLedger ledger, MilestoneFunding funding, Clock clock) {
		this.jdbc = jdbc;
		this.crews = crews;
		this.ledger = ledger;
		this.funding = funding;
		this.clock = clock;
	}

	@Override
	@Transactional
	public void holdShares(UUID milestoneId, UUID teamIssueId, UUID raiserId) {
		Map<String, Object> account = this.jdbc
			.sql("SELECT job_id, product_id FROM payment.ledger_account WHERE milestone_id = :id FOR UPDATE")
			.param("id", milestoneId)
			.query((rs, n) -> Map.<String, Object>of("job", rs.getObject("job_id", UUID.class), "product",
					rs.getObject("product_id", UUID.class)))
			.optional()
			.orElseThrow(() -> new ApiException(ErrorCode.GUARD_FAILED, PaymentReason.MILESTONE_NOT_HELD));
		boolean released = this.jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM payment.ledger_entry WHERE milestone_id = :id
				    AND entry_type IN ('RELEASE', 'RELEASE_HELD', 'RELEASE_POOLED'))
				""").param("id", milestoneId).query(Boolean.class).single();
		boolean party = this.crews.releaseTermsOf(milestoneId)
			.map(terms -> terms.shares().containsKey(raiserId))
			.orElse(false);
		if (released || !party) {
			throw new ApiException(ErrorCode.GUARD_FAILED, PaymentReason.MILESTONE_NOT_HELD);
		}
		this.jdbc.sql("""
				INSERT INTO payment.share_hold (team_issue_id, milestone_id, job_id, product_id, raiser_account_id, held_at)
				VALUES (:issue, :milestone, :job, :product, :raiser, :now)
				ON CONFLICT (team_issue_id) DO NOTHING
				""")
			.param("issue", teamIssueId)
			.param("milestone", milestoneId)
			.param("job", account.get("job"))
			.param("product", account.get("product"))
			.param("raiser", raiserId)
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
	}

	@Override
	@Transactional(readOnly = true)
	public List<Transfer> transfersOf(UUID milestoneId) {
		return this.jdbc.sql("""
				SELECT team_issue_id, raiser_account_id, transfer_points FROM payment.share_hold
				WHERE milestone_id = :id AND transfer_points > 0 ORDER BY ended_at, team_issue_id
				""")
			.param("id", milestoneId)
			.query((rs, n) -> new Transfer(rs.getObject("team_issue_id", UUID.class),
					rs.getObject("raiser_account_id", UUID.class), rs.getInt("transfer_points")))
			.list();
	}

	@Override
	@Transactional(readOnly = true)
	public long basisOf(UUID milestoneId) {
		return this.jdbc.sql("""
				SELECT max(CASE WHEN entry_type = 'RELEASE_POOLED' THEN amount_vnd ELSE base_amount_vnd END)
				FROM payment.ledger_entry
				WHERE milestone_id = :id AND entry_type IN ('RELEASE_POOLED', 'RELEASE', 'RELEASE_HELD')
				""").param("id", milestoneId).query(Long.class).optional().orElseGet(() -> this.ledger.releasable(milestoneId));
	}

	@Override
	@Transactional
	public void endHold(UUID teamIssueId, int points) {
		if (points < 0 || points > 100) {
			throw new IllegalArgumentException("A transfer is 0 to 100 points, not " + points);
		}
		UUID milestoneId = this.jdbc.sql("SELECT milestone_id FROM payment.share_hold WHERE team_issue_id = :id")
			.param("id", teamIssueId)
			.query(UUID.class)
			.optional()
			.orElseThrow(() -> new IllegalStateException("The Team Issue " + teamIssueId + " holds nothing"));
		this.jdbc.sql("SELECT milestone_id FROM payment.ledger_account WHERE milestone_id = :id FOR UPDATE")
			.param("id", milestoneId)
			.query(UUID.class)
			.single();
		Map<String, Object> hold = this.jdbc
			.sql("SELECT job_id, ended_at FROM payment.share_hold WHERE team_issue_id = :id FOR UPDATE")
			.param("id", teamIssueId)
			.query((rs, n) -> {
				Map<String, Object> row = new HashMap<>();
				row.put("job", rs.getObject("job_id", UUID.class));
				row.put("ended", rs.getTimestamp("ended_at") != null);
				return row;
			})
			.single();
		if ((Boolean) hold.get("ended")) {
			return;
		}
		this.jdbc.sql("UPDATE payment.share_hold SET ended_at = :now, transfer_points = :points WHERE team_issue_id = :id")
			.param("now", Timestamp.from(this.clock.instant()))
			.param("points", points)
			.param("id", teamIssueId)
			.update();
		ReleaseTerms terms = this.crews.releaseTermsOf(milestoneId)
			.orElseThrow(() -> new IllegalStateException("The Milestone " + milestoneId + " has no pinned Crew Terms"));
		FundingJob job = this.funding.byId((UUID) hold.get("job"))
			.orElseThrow(() -> new IllegalStateException("The Job " + hold.get("job") + " has no confirmed Agreement"));
		int moved = transfersOf(milestoneId).stream().mapToInt(Transfer::points).sum();
		int pinned = terms.shares().getOrDefault(job.mentorId(), 0);
		if (moved > pinned) {
			throw new IllegalStateException("SHARE_TRANSFER_TOO_LARGE: " + moved + " points moved from the Mentor's " + pinned);
		}
	}

}
