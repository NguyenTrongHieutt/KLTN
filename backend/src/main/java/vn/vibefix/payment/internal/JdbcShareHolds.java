package vn.vibefix.payment.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.job.Crews;
import vn.vibefix.payment.ShareHolds;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;

/**
 * {@link ShareHolds} on {@code payment.share_hold} (AD-40): a hold is refused under the ledger account lock
 * once anything was released, so a hold and a release never both win; the release reads the open holds
 * and the recorded transfers under the same lock.
 */
@Component
class JdbcShareHolds implements ShareHolds {

	private final JdbcClient jdbc;

	private final Crews crews;

	private final Clock clock;

	JdbcShareHolds(JdbcClient jdbc, Crews crews, Clock clock) {
		this.jdbc = jdbc;
		this.crews = crews;
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
				SELECT EXISTS (SELECT 1 FROM payment.ledger_entry WHERE milestone_id = :id AND entry_type IN ('RELEASE', 'RELEASE_HELD'))
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

	/** The open holds of the Milestone: each raiser with their Team Issue. The caller holds the account lock. */
	Map<UUID, UUID> openHolds(UUID milestoneId) {
		Map<UUID, UUID> holds = new LinkedHashMap<>();
		this.jdbc.sql("""
				SELECT raiser_account_id, team_issue_id FROM payment.share_hold
				WHERE milestone_id = :id AND ended_at IS NULL ORDER BY held_at, team_issue_id
				""")
			.param("id", milestoneId)
			.query(rs -> {
				holds.put(rs.getObject("raiser_account_id", UUID.class), rs.getObject("team_issue_id", UUID.class));
			});
		return holds;
	}

	/**
	 * The pin adjusted by the recorded transfers (AD-33, AD-40): each moves its points from the Mentor to
	 * the raiser.
	 * @throws IllegalStateException {@code SHARE_TRANSFER_TOO_LARGE} when they sum above the Mentor's pinned share
	 */
	Map<UUID, Integer> adjusted(Map<UUID, Integer> pin, UUID mentorId, List<Transfer> transfers) {
		Map<UUID, Integer> shares = new HashMap<>(pin);
		int moved = transfers.stream().mapToInt(Transfer::points).sum();
		if (moved > pin.getOrDefault(mentorId, 0)) {
			throw new IllegalStateException("SHARE_TRANSFER_TOO_LARGE: " + moved + " points moved from the Mentor's "
					+ pin.getOrDefault(mentorId, 0));
		}
		for (Transfer transfer : transfers) {
			shares.merge(mentorId, -transfer.points(), Integer::sum);
			shares.merge(transfer.raiserId(), transfer.points(), Integer::sum);
		}
		return shares;
	}

}
