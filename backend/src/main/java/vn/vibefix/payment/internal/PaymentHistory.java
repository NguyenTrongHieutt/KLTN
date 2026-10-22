package vn.vibefix.payment.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.identity.MentorDirectory;
import vn.vibefix.identity.StudentDirectory;
import vn.vibefix.job.MilestoneFunding;
import vn.vibefix.job.MilestoneFunding.FundingJob;
import vn.vibefix.job.MilestoneFunding.FundingMilestone;

/**
 * Where test money is (FR-44, AD-15, AD-20): a Milestone's figures; CUS-10, which never names a party;
 * the Balance of MEN-7 and STU-7, the sum of the account's RELEASE entries, with the account's own held
 * parts beside it ("Đang giữ", AD-40), which never count in it; and the Mentor's JOB-7, which lists each
 * release by party. Reads take no lock.
 */
@Component
class PaymentHistory {

	/** One Milestone's money as JOB-7 shows it: never a party's part. */
	record Figures(long fundedVnd, long topUpVnd, long refundedVnd, long releasedTotalVnd, long releasableVnd) {
	}

	/** One RELEASE entry. */
	record Release(UUID milestoneId, String group, UUID partyId, long amountVnd, @Nullable Integer percent,
			@Nullable Integer crewTermsVersionNo, @Nullable Long baseAmountVnd, Instant releasedAt) {
	}

	/** One part of a release, as the Mentor reads it. */
	record Part(UUID partyId, String name, long amountVnd, @Nullable Integer percent) {
	}

	/** One release of a Milestone, with the reader's part and, for its Mentor, the Crew's. */
	record ReleaseLine(UUID jobId, String jobTitle, int number, String milestoneTitle, Instant releasedAt, long baseAmountVnd,
			long yourPartVnd, @Nullable Integer yourPercent, @Nullable Integer crewTermsVersionNo, List<Part> crew) {
	}

	/** One of the account's own held parts: a RELEASE_HELD not reversed yet (AD-40). */
	record HeldPart(UUID jobId, String jobTitle, int number, String milestoneTitle, long amountVnd, Instant heldAt) {
	}

	/** MEN-7 or STU-7. */
	record Balance(long balanceVnd, int releases, @Nullable Long sentToCrewVnd, @Nullable Long heldVnd, List<ReleaseLine> lines,
			List<HeldPart> heldParts) {
	}

	private final JdbcClient jdbc;

	private final MilestoneFunding funding;

	private final StudentDirectory students;

	private final MentorDirectory mentors;

	PaymentHistory(JdbcClient jdbc, MilestoneFunding funding, StudentDirectory students, MentorDirectory mentors) {
		this.jdbc = jdbc;
		this.funding = funding;
		this.students = students;
		this.mentors = mentors;
	}

	@Transactional(readOnly = true)
	Figures figures(UUID milestoneId) {
		List<LedgerMath.Entry> entries = this.jdbc
			.sql("SELECT entry_type, bucket, amount_vnd FROM payment.ledger_entry WHERE milestone_id = :id")
			.param("id", milestoneId)
			.query((rs, n) -> new LedgerMath.Entry(rs.getString("entry_type"), rs.getString("bucket"), rs.getLong("amount_vnd")))
			.list();
		long funded = entries.stream()
			.filter(entry -> "FUND".equals(entry.type()) && "MILESTONE".equals(entry.bucket()))
			.mapToLong(LedgerMath.Entry::amountVnd)
			.sum();
		long topUp = entries.stream().filter(entry -> "TOPUP_APPLIED".equals(entry.type())).mapToLong(LedgerMath.Entry::amountVnd).sum();
		return new Figures(funded, topUp, LedgerMath.refundsRequested(entries), LedgerMath.releasedTotal(entries),
				LedgerMath.releasable(entries));
	}

	/** The Jobs the Customer paid for, the most recent first. */
	@Transactional(readOnly = true)
	List<FundingJob> customerJobs(UUID customerId) {
		List<UUID> jobs = this.jdbc.sql("""
				SELECT job_id FROM payment.payment_order WHERE customer_account_id = :customer
				GROUP BY job_id ORDER BY max(created_at) DESC
				""").param("customer", customerId).query(UUID.class).list();
		return jobs.stream().map(this.funding::byId).flatMap(Optional::stream).toList();
	}

	/** The confirmed refunds of the Customer's money. */
	@Transactional(readOnly = true)
	long refundedTo(UUID customerId) {
		return this.jdbc.sql("SELECT coalesce(sum(amount_vnd), 0) FROM payment.refund WHERE customer_account_id = :id AND status = 'CONFIRMED'")
			.param("id", customerId)
			.query(Long.class)
			.single();
	}

	/**
	 * MEN-7 or STU-7: the sum of the account's RELEASE entries, each release listed. A Mentor also reads
	 * the Crew's parts of their Jobs' releases, what went to the Crew, and what is held on their Jobs.
	 */
	@Transactional(readOnly = true)
	Balance balance(UUID accountId, boolean mentor) {
		List<Release> own = releases("party_account_id = :id", Map.of("id", accountId));
		Map<String, List<Release>> groups = new LinkedHashMap<>();
		if (mentor) {
			List<UUID> milestones = own.stream().map(Release::milestoneId).distinct().toList();
			if (!milestones.isEmpty()) {
				releases("milestone_id IN (:ids)", Map.of("ids", milestones))
					.forEach(release -> groups.computeIfAbsent(release.group(), key -> new ArrayList<>()).add(release));
			}
		}
		Map<UUID, String> names = names(groups.values().stream().flatMap(List::stream).map(Release::partyId).distinct().toList());
		Map<UUID, FundingJob> jobs = new HashMap<>();
		List<ReleaseLine> lines = new ArrayList<>();
		for (Release release : own) {
			FundingJob job = jobOf(release.milestoneId(), jobs);
			FundingMilestone milestone = job == null ? null : job.milestones()
				.stream()
				.filter(found -> found.milestoneId().equals(release.milestoneId()))
				.findFirst()
				.orElse(null);
			List<Part> crew = groups.getOrDefault(release.group(), List.of())
				.stream()
				.filter(part -> !part.partyId().equals(accountId))
				.map(part -> new Part(part.partyId(), names.getOrDefault(part.partyId(), ""), part.amountVnd(), part.percent()))
				.toList();
			lines.add(new ReleaseLine(job == null ? new UUID(0, 0) : job.jobId(), job == null ? "" : job.title(),
					milestone == null ? 0 : milestone.number(), milestone == null ? "" : milestone.title(), release.releasedAt(),
					release.baseAmountVnd() == null ? release.amountVnd() : release.baseAmountVnd(), release.amountVnd(),
					release.percent(), release.crewTermsVersionNo(), crew));
		}
		lines.sort(Comparator.comparing(ReleaseLine::releasedAt).reversed());
		long balance = own.stream().mapToLong(Release::amountVnd).sum();
		List<HeldPart> heldParts = heldParts(accountId, jobs);
		if (!mentor) {
			return new Balance(balance, own.size(), null, null, lines, heldParts);
		}
		long sent = lines.stream().flatMap(line -> line.crew().stream()).mapToLong(Part::amountVnd).sum();
		long held = this.funding.ofMentor(accountId)
			.stream()
			.flatMap(job -> job.milestones().stream())
			.mapToLong(milestone -> figures(milestone.milestoneId()).releasableVnd())
			.sum();
		return new Balance(balance, own.size(), sent, held, lines, heldParts);
	}

	/** The account's RELEASE_HELD entries that no HELD_RELEASED reversed, the newest first. */
	private List<HeldPart> heldParts(UUID accountId, Map<UUID, FundingJob> jobs) {
		return this.jdbc.sql("""
				SELECT e.milestone_id, e.amount_vnd, e.created_at FROM payment.ledger_entry e
				WHERE e.entry_type = 'RELEASE_HELD' AND e.party_account_id = :id
				  AND NOT EXISTS (SELECT 1 FROM payment.ledger_entry r WHERE r.reverses_entry_id = e.id)
				ORDER BY e.created_at DESC, e.id
				""").param("id", accountId).query((rs, n) -> {
			UUID milestoneId = rs.getObject("milestone_id", UUID.class);
			FundingJob job = jobOf(milestoneId, jobs);
			FundingMilestone milestone = job == null ? null : job.milestones()
				.stream()
				.filter(found -> found.milestoneId().equals(milestoneId))
				.findFirst()
				.orElse(null);
			return new HeldPart(job == null ? new UUID(0, 0) : job.jobId(), job == null ? "" : job.title(),
					milestone == null ? 0 : milestone.number(), milestone == null ? "" : milestone.title(),
					rs.getLong("amount_vnd"), rs.getTimestamp("created_at").toInstant());
		}).list();
	}

	/** The RELEASE entries of a Milestone with their version, for the Mentor's JOB-7. */
	@Transactional(readOnly = true)
	List<Release> releaseEntries(UUID milestoneId) {
		return releases("milestone_id = :id", Map.of("id", milestoneId));
	}

	/** Display names: a Student's name (or @login), a Mentor's display name. */
	Map<UUID, String> names(List<UUID> accounts) {
		Map<UUID, String> names = new HashMap<>();
		if (accounts.isEmpty()) {
			return names;
		}
		this.students.cards(accounts)
			.forEach(card -> names.put(card.accountId(), card.name() != null ? card.name() : "@" + card.githubLogin()));
		this.mentors.cards(accounts).forEach(card -> names.put(card.accountId(), card.displayName()));
		return names;
	}

	private List<Release> releases(String where, Map<String, Object> params) {
		return this.jdbc.sql("""
				SELECT milestone_id, operation_key, party_account_id, amount_vnd, share_percent, crew_terms_version_no,
				       base_amount_vnd, created_at
				FROM payment.ledger_entry WHERE entry_type = 'RELEASE' AND party_account_id IS NOT NULL AND
				""" + where + " ORDER BY created_at, id")
			.params(params)
			.query((rs, n) -> new Release(rs.getObject("milestone_id", UUID.class), group(rs.getString("operation_key")),
					rs.getObject("party_account_id", UUID.class), rs.getLong("amount_vnd"), (Integer) rs.getObject("share_percent"),
					(Integer) rs.getObject("crew_terms_version_no"), (Long) rs.getObject("base_amount_vnd"),
					rs.getTimestamp("created_at").toInstant()))
			.list();
	}

	/** A release is keyed {@code RELEASE:{transitionAuditId}:{partyAccountId}} (AD-15): its group is the transition. */
	private static String group(String operationKey) {
		String[] parts = operationKey.split(":");
		return parts.length >= 2 ? parts[1] : operationKey;
	}

	private @Nullable FundingJob jobOf(UUID milestoneId, Map<UUID, FundingJob> cache) {
		UUID jobId = this.jdbc.sql("SELECT job_id FROM payment.ledger_account WHERE milestone_id = :id")
			.param("id", milestoneId)
			.query(UUID.class)
			.optional()
			.orElse(null);
		if (jobId == null) {
			return null;
		}
		return cache.computeIfAbsent(jobId, id -> this.funding.byId(id).orElse(null));
	}

}
