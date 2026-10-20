package vn.vibefix.payment.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

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
import vn.vibefix.payment.Releases;
import vn.vibefix.shared.CrewSplit;
import vn.vibefix.shared.IdGenerator;

/**
 * {@link Releases} (FR-42, AD-15, AD-41): under the Milestone's ledger account lock, what is releasable
 * is split by the Crew Terms version pinned at M1, every party of it paid whatever its Crew state now,
 * each entry keeping the version, the percentage, and the base. The parts must sum to exactly what was
 * releasable. Each payee is told; the Customer only ever sees the released total (AD-20).
 */
@Component
class MilestoneReleases implements Releases {

	static final String RELEASED = "payment.funds_released";

	private final JdbcClient jdbc;

	private final JdbcLedger ledger;

	private final Crews crews;

	private final MilestoneFunding funding;

	private final Notifications notifications;

	private final IdGenerator ids;

	private final Clock clock;

	private final CancelledRefunds refunds;

	MilestoneReleases(JdbcClient jdbc, JdbcLedger ledger, Crews crews, MilestoneFunding funding, Notifications notifications,
			IdGenerator ids, Clock clock, CancelledRefunds refunds) {
		this.refunds = refunds;
		this.jdbc = jdbc;
		this.ledger = ledger;
		this.crews = crews;
		this.funding = funding;
		this.notifications = notifications;
		this.ids = ids;
		this.clock = clock;
	}

	@Override
	@Transactional
	public List<Part> release(UUID jobId, UUID milestoneId, UUID causeId) {
		UUID account = lockAccount(milestoneId);
		long releasable = this.ledger.releasable(account);
		if (releasable <= 0) {
			throw new IllegalStateException("The Milestone " + milestoneId + " has nothing to release");
		}
		return split(jobId, milestoneId, causeId, releasable);
	}

	@Override
	@Transactional
	public List<Part> settle(UUID jobId, UUID milestoneId, UUID causeId, long releaseVnd, long refundVnd) {
		UUID account = lockAccount(milestoneId);
		long releasable = this.ledger.releasable(account);
		if (releaseVnd < 0 || refundVnd < 0 || releaseVnd + refundVnd != releasable) {
			throw new IllegalStateException("The settlement of " + milestoneId + " does not sum to " + releasable);
		}
		List<Part> parts = releaseVnd > 0 ? split(jobId, milestoneId, causeId, releaseVnd) : List.of();
		long refunded = this.refunds.refund(milestoneId, refundVnd, Refunds.Reason.DISPUTE_DECISION)
			.stream()
			.mapToLong(refund -> refund.amountVnd())
			.sum();
		if (refunded != refundVnd || this.ledger.releasable(account) != 0) {
			throw new IllegalStateException("The settlement of " + milestoneId + " could not refund " + refundVnd);
		}
		return parts;
	}

	private UUID lockAccount(UUID milestoneId) {
		return this.jdbc.sql("SELECT milestone_id FROM payment.ledger_account WHERE milestone_id = :id FOR UPDATE")
			.param("id", milestoneId)
			.query(UUID.class)
			.optional()
			.orElseThrow(() -> new IllegalStateException("The Milestone " + milestoneId + " has no ledger account"));
	}

	/** {@code amount} split by the pinned Crew Terms, one RELEASE per party, each payee told. */
	private List<Part> split(UUID jobId, UUID milestoneId, UUID causeId, long amount) {
		ReleaseTerms terms = this.crews.releaseTermsOf(milestoneId)
			.orElseThrow(() -> new IllegalStateException("The Milestone " + milestoneId + " has no pinned Crew Terms"));
		FundingJob job = this.funding.byId(jobId)
			.orElseThrow(() -> new IllegalStateException("The Job " + jobId + " has no confirmed Agreement"));
		UUID mentor = Objects.requireNonNull(job.mentorId(), "the Job's Mentor");
		long releasable = amount;
		Map<UUID, Long> split = CrewSplit.split(releasable, terms.shares(), mentor);
		if (split.values().stream().mapToLong(Long::longValue).sum() != releasable) {
			throw new IllegalStateException("The release of " + milestoneId + " does not sum to " + releasable);
		}
		Timestamp now = Timestamp.from(this.clock.instant());
		List<Part> parts = new ArrayList<>();
		for (Map.Entry<UUID, Long> part : split.entrySet()) {
			int percent = terms.shares().get(part.getKey());
			this.jdbc.sql("""
					INSERT INTO payment.ledger_entry (id, operation_key, entry_type, job_id, product_id, milestone_id, amount_vnd,
					    party_account_id, crew_terms_version_no, share_percent, base_amount_vnd, created_at)
					VALUES (:id, :key, 'RELEASE', :job, :product, :milestone, :amount, :party, :version, :percent, :base, :now)
					""")
				.param("id", this.ids.newId())
				.param("key", "RELEASE:" + causeId + ":" + part.getKey())
				.param("job", jobId)
				.param("product", job.productId())
				.param("milestone", milestoneId)
				.param("amount", part.getValue())
				.param("party", part.getKey())
				.param("version", terms.versionNo())
				.param("percent", percent)
				.param("base", releasable)
				.param("now", now)
				.update();
			parts.add(new Part(part.getKey(), part.getValue(), percent));
		}
		int number = job.milestones()
			.stream()
			.filter(milestone -> milestone.milestoneId().equals(milestoneId))
			.findFirst()
			.map(milestone -> milestone.number())
			.orElse(0);
		for (Part part : parts) {
			Audience audience = part.partyId().equals(mentor) ? Audience.MENTOR : Audience.CREW_EVER_ACTIVE;
			this.notifications.send(new NotificationRequest(RELEASED, causeId, List.of(new Recipient(part.partyId(), audience)),
					Map.of("amount", PaymentOrders.vnd(part.amountVnd()), "job", job.title(), "n", String.valueOf(number)),
					"/so-du", jobId, null));
		}
		return parts;
	}

}
