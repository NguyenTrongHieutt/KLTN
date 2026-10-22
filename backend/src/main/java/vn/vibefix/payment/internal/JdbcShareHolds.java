package vn.vibefix.payment.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.job.Crews;
import vn.vibefix.job.Crews.ReleaseTerms;
import vn.vibefix.job.MilestoneFunding;
import vn.vibefix.job.MilestoneFunding.FundingJob;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.payment.ShareHolds;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.CrewSplit;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.IdGenerator;

/**
 * {@link ShareHolds} on {@code payment.share_hold} (AD-40): a hold is refused under the ledger account lock
 * once anything was released, so a hold and a release never both win; the release reads the open holds
 * and the recorded transfers under the same lock, and so does every ending. A party whose held part is paid
 * out by an ending is told ({@code payment.funds_released}).
 */
@Component
class JdbcShareHolds implements ShareHolds {

	private final JdbcClient jdbc;

	private final Crews crews;

	private final JdbcLedger ledger;

	private final MilestoneFunding funding;

	private final Notifications notifications;

	private final IdGenerator ids;

	private final Clock clock;

	JdbcShareHolds(JdbcClient jdbc, Crews crews, JdbcLedger ledger, MilestoneFunding funding, Notifications notifications,
			IdGenerator ids, Clock clock) {
		this.jdbc = jdbc;
		this.crews = crews;
		this.ledger = ledger;
		this.funding = funding;
		this.notifications = notifications;
		this.ids = ids;
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

	@Override
	@Transactional(readOnly = true)
	public long basisOf(UUID milestoneId) {
		return this.jdbc.sql("""
				SELECT max(base_amount_vnd) FROM payment.ledger_entry
				WHERE milestone_id = :id AND entry_type IN ('RELEASE', 'RELEASE_HELD') AND base_amount_vnd IS NOT NULL
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
			.sql("SELECT job_id, raiser_account_id, ended_at FROM payment.share_hold WHERE team_issue_id = :id FOR UPDATE")
			.param("id", teamIssueId)
			.query((rs, n) -> {
				Map<String, Object> row = new HashMap<>();
				row.put("job", rs.getObject("job_id", UUID.class));
				row.put("raiser", rs.getObject("raiser_account_id", UUID.class));
				row.put("ended", rs.getTimestamp("ended_at") != null);
				return row;
			})
			.single();
		if ((Boolean) hold.get("ended")) {
			return;
		}
		UUID jobId = (UUID) hold.get("job");
		UUID raiser = (UUID) hold.get("raiser");
		this.jdbc.sql("UPDATE payment.share_hold SET ended_at = :now, transfer_points = :points WHERE team_issue_id = :id")
			.param("now", Timestamp.from(this.clock.instant()))
			.param("points", points)
			.param("id", teamIssueId)
			.update();
		ReleaseTerms terms = this.crews.releaseTermsOf(milestoneId)
			.orElseThrow(() -> new IllegalStateException("The Milestone " + milestoneId + " has no pinned Crew Terms"));
		FundingJob job = this.funding.byId(jobId)
			.orElseThrow(() -> new IllegalStateException("The Job " + jobId + " has no confirmed Agreement"));
		UUID mentor = job.mentorId();
		List<Transfer> transfers = transfersOf(milestoneId);
		Map<UUID, Integer> shares = adjusted(terms.shares(), mentor, transfers);
		Long base = this.jdbc.sql("""
				SELECT max(base_amount_vnd) FROM payment.ledger_entry
				WHERE milestone_id = :id AND entry_type IN ('RELEASE', 'RELEASE_HELD') AND base_amount_vnd IS NOT NULL
				""").param("id", milestoneId).query(Long.class).optional().orElse(null);
		if (base == null) {
			return;
		}
		Map<UUID, Long> split = CrewSplit.split(base, shares, mentor);
		boolean mentorHeld = !openHolds(milestoneId).isEmpty();
		String applied = transfers.isEmpty() ? null
				: transfers.stream().map(transfer -> transfer.teamIssueId().toString()).collect(Collectors.joining(",", "{", "}"));
		long reversed = 0;
		long written = 0;
		List<UUID> paid = new ArrayList<>();
		for (UUID party : List.of(raiser, mentor)) {
			List<Map<String, Object>> held = this.jdbc.sql("""
					SELECT e.id, e.amount_vnd FROM payment.ledger_entry e
					WHERE e.milestone_id = :milestone AND e.entry_type = 'RELEASE_HELD' AND e.party_account_id = :party
					  AND NOT EXISTS (SELECT 1 FROM payment.ledger_entry r WHERE r.reverses_entry_id = e.id)
					""").param("milestone", milestoneId).param("party", party).query((rs, n) -> {
				Map<String, Object> row = new HashMap<>();
				row.put("id", rs.getObject("id", UUID.class));
				row.put("amount", rs.getLong("amount_vnd"));
				return row;
			}).list();
			for (Map<String, Object> entry : held) {
				long amount = (Long) entry.get("amount");
				insert("HELD_RELEASED:" + entry.get("id"), "HELD_RELEASED", job, milestoneId, party, amount, terms.versionNo(),
						null, null, null, (UUID) entry.get("id"), null);
				reversed += amount;
			}
			long part = split.getOrDefault(party, 0L);
			if (part > 0) {
				boolean stillHeld = party.equals(mentor) && mentorHeld;
				String type = stillHeld ? "RELEASE_HELD" : "RELEASE";
				insert(type + ":" + teamIssueId + ":" + party, type, job, milestoneId, party, part, terms.versionNo(),
						shares.get(party), base, null, null, applied);
				written += part;
				if (!stillHeld) {
					paid.add(party);
				}
			}
		}
		if (written != reversed || this.ledger.releasable(milestoneId) < 0) {
			throw new IllegalStateException("The ending of " + teamIssueId + " writes " + written + " but reverses " + reversed);
		}
		int number = job.milestones()
			.stream()
			.filter(milestone -> milestone.milestoneId().equals(milestoneId))
			.findFirst()
			.map(milestone -> milestone.number())
			.orElse(0);
		for (UUID party : paid) {
			long amount = split.get(party);
			this.notifications.send(new NotificationRequest(MilestoneReleases.RELEASED, teamIssueId,
					List.of(new Recipient(party, party.equals(mentor) ? Audience.MENTOR : Audience.CREW_EVER_ACTIVE)),
					Map.of("amount", PaymentOrders.vnd(amount), "job", job.title(), "n", String.valueOf(number)), "/so-du", jobId,
					null));
		}
	}

	private void insert(String key, String type, FundingJob job, UUID milestoneId, UUID party, long amount, int versionNo,
			Integer percent, Long base, UUID hold, UUID reverses, String transfers) {
		this.jdbc.sql("""
				INSERT INTO payment.ledger_entry (id, operation_key, entry_type, job_id, product_id, milestone_id, amount_vnd,
				    party_account_id, crew_terms_version_no, share_percent, base_amount_vnd, hold_id, reverses_entry_id,
				    transfer_ids, created_at)
				VALUES (:id, :key, :type, :job, :product, :milestone, :amount, :party, :version, :percent, :base, :hold, :reverses,
				    CAST(:transfers AS UUID[]), :now)
				""")
			.param("id", this.ids.newId())
			.param("key", key)
			.param("type", type)
			.param("job", job.jobId())
			.param("product", job.productId())
			.param("milestone", milestoneId)
			.param("amount", amount)
			.param("party", party)
			.param("version", versionNo)
			.param("percent", percent)
			.param("base", base)
			.param("hold", hold)
			.param("reverses", reverses)
			.param("transfers", transfers)
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
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
