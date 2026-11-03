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
import vn.vibefix.identity.PersonDirectory;
import vn.vibefix.job.JobRoles;
import vn.vibefix.job.MilestoneFunding;
import vn.vibefix.job.MilestoneFunding.FundingJob;
import vn.vibefix.job.MilestoneFunding.FundingMilestone;
import vn.vibefix.job.MilestoneSplits;
import vn.vibefix.job.MilestoneSplits.SplitState;

/**
 * Where test money is (FR-44, AD-15, AD-20): a Milestone's figures; CUS-10, which never names a party;
 * the Balance of MEN-7 and STU-7, the sum of the account's RELEASE entries, so pooled money never counts in it; and
 * the Mentor's JOB-7, which lists each release by party. Who reads a party's part is decided by the role in each Job
 * ({@code job.roleOf}, AD-43), never by an account role (Story 20.3). Reads take no lock. A Customer's views treat the
 * Jobs of a deleted Product as gone, though their ledger stays (AD-24).
 */
@Component
class PaymentHistory {

	/** One Milestone's money as JOB-7 shows it: never a party's part. */
	record Figures(long fundedVnd, long topUpVnd, long refundedVnd, long releasedTotalVnd, long releasableVnd) {
	}

	/** One RELEASE entry. */
	record Release(UUID milestoneId, String group, UUID partyId, long amountVnd, @Nullable Integer percent,
			@Nullable Integer splitVersionNo, @Nullable Long baseAmountVnd, Instant releasedAt) {
	}

	/** One part of a release, as the Mentor reads it. */
	record Part(UUID partyId, String name, long amountVnd, @Nullable Integer percent) {
	}

	/** One release of a Milestone, with the reader's part and, for its Mentor, the Crew's. */
	record ReleaseLine(UUID jobId, String jobTitle, int number, String milestoneTitle, Instant releasedAt, long baseAmountVnd,
			long yourPartVnd, @Nullable Integer yourPercent, @Nullable Integer splitVersionNo, List<Part> crew) {
	}

	/** MEN-7 or STU-7. */
	record Balance(long balanceVnd, int releases, @Nullable Long sentToCrewVnd, @Nullable Long heldVnd, List<ReleaseLine> lines) {
	}

	private final JdbcClient jdbc;

	private final MilestoneFunding funding;

	private final PersonDirectory people;

	private final MentorDirectory mentors;

	private final JobRoles roles;

	private final MilestoneSplits splits;

	PaymentHistory(JdbcClient jdbc, MilestoneFunding funding, PersonDirectory people, MentorDirectory mentors,
			JobRoles roles, MilestoneSplits splits) {
		this.splits = splits;
		this.jdbc = jdbc;
		this.funding = funding;
		this.people = people;
		this.mentors = mentors;
		this.roles = roles;
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

	/** The confirmed refunds of the Customer's money, on the Jobs they still see. */
	@Transactional(readOnly = true)
	long refundedTo(UUID customerId) {
		List<UUID> jobs = customerJobs(customerId).stream().map(FundingJob::jobId).toList();
		if (jobs.isEmpty()) {
			return 0;
		}
		return this.jdbc.sql("""
				SELECT coalesce(sum(amount_vnd), 0) FROM payment.refund
				WHERE customer_account_id = :id AND status = 'CONFIRMED' AND job_id IN (:jobs)
				""")
			.param("id", customerId)
			.param("jobs", jobs)
			.query(Long.class)
			.single();
	}

	/**
	 * MEN-7 or STU-7, for any user: the sum of the account's RELEASE entries, each release listed. The Crew's parts
	 * of a release are shown only on the Jobs whose Mentor the account is ({@code job.roleOf}, AD-43); what went to
	 * the Crew and what is held on the Jobs it mentors are given only to an account that mentors a Job. A Job whose Product
	 * was deleted has no title or role any more: its split still says the Milestone's number and who its Mentor was.
	 */
	@Transactional(readOnly = true)
	Balance balance(UUID accountId) {
		List<Release> own = releases("party_account_id = :id", Map.of("id", accountId));
		Map<UUID, FundingJob> jobs = new HashMap<>();
		Map<UUID, Boolean> mentorOf = new HashMap<>();
		List<UUID> mentored = own.stream().map(Release::milestoneId).distinct().filter(milestoneId -> {
			FundingJob job = jobOf(milestoneId, jobs);
			if (job == null) {
				return this.splits.splitOf(milestoneId).filter(split -> accountId.equals(split.mentorId())).isPresent();
			}
			return mentorOf.computeIfAbsent(job.jobId(), jobId -> this.roles.roleOf(jobId, accountId).isMentor());
		}).toList();
		Map<String, List<Release>> groups = new LinkedHashMap<>();
		if (!mentored.isEmpty()) {
			releases("milestone_id IN (:ids)", Map.of("ids", mentored))
				.forEach(release -> groups.computeIfAbsent(release.group(), key -> new ArrayList<>()).add(release));
		}
		Map<UUID, String> names = names(groups.values().stream().flatMap(List::stream).map(Release::partyId).distinct().toList(),
				accountId);
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
			int number = milestone != null ? milestone.number()
					: this.splits.splitOf(release.milestoneId()).map(SplitState::milestoneNumber).orElse(0);
			lines.add(new ReleaseLine(job == null ? new UUID(0, 0) : job.jobId(), job == null ? "" : job.title(), number,
					milestone == null ? "" : milestone.title(), release.releasedAt(),
					release.baseAmountVnd() == null ? release.amountVnd() : release.baseAmountVnd(), release.amountVnd(),
					release.percent(), release.splitVersionNo(), crew));
		}
		lines.sort(Comparator.comparing(ReleaseLine::releasedAt).reversed());
		long balance = own.stream().mapToLong(Release::amountVnd).sum();
		List<FundingJob> mentoring = this.funding.ofMentor(accountId);
		if (mentored.isEmpty() && mentoring.isEmpty()) {
			return new Balance(balance, own.size(), null, null, lines);
		}
		long sent = lines.stream().flatMap(line -> line.crew().stream()).mapToLong(Part::amountVnd).sum();
		long held = mentoring.stream()
			.flatMap(job -> job.milestones().stream())
			.mapToLong(milestone -> figures(milestone.milestoneId()).releasableVnd())
			.sum();
		return new Balance(balance, own.size(), sent, held, lines);
	}

	/** The RELEASE entries of a Milestone with their version, for the Mentor's JOB-7. */
	@Transactional(readOnly = true)
	List<Release> releaseEntries(UUID milestoneId) {
		return releases("milestone_id = :id", Map.of("id", milestoneId));
	}

	/**
	 * Display names of one Job's parties: the Mentor's display name for {@code mentorId}, which the caller read
	 * from the Job by its role there (AD-43), and a Crew member's shown name for everyone else.
	 */
	Map<UUID, String> names(List<UUID> accounts, UUID mentorId) {
		Map<UUID, String> names = new HashMap<>();
		List<UUID> crew = accounts.stream().filter(account -> !account.equals(mentorId)).toList();
		if (!crew.isEmpty()) {
			this.people.cards(crew).forEach(card -> names.put(card.accountId(), card.shownName()));
		}
		if (accounts.contains(mentorId)) {
			this.mentors.cards(List.of(mentorId)).forEach(card -> names.put(card.accountId(), card.displayName()));
		}
		return names;
	}

	private List<Release> releases(String where, Map<String, Object> params) {
		return this.jdbc.sql("""
				SELECT milestone_id, operation_key, party_account_id, amount_vnd, share_percent, split_version_no,
				       base_amount_vnd, created_at
				FROM payment.ledger_entry WHERE entry_type = 'RELEASE' AND party_account_id IS NOT NULL AND
				""" + where + " ORDER BY created_at, id")
			.params(params)
			.query((rs, n) -> new Release(rs.getObject("milestone_id", UUID.class), group(rs.getString("operation_key")),
					rs.getObject("party_account_id", UUID.class), rs.getLong("amount_vnd"), (Integer) rs.getObject("share_percent"),
					(Integer) rs.getObject("split_version_no"), (Long) rs.getObject("base_amount_vnd"),
					rs.getTimestamp("created_at").toInstant()))
			.list();
	}

	/** A release is keyed {@code RELEASE:{milestoneSplitId}:{partyAccountId}} (AD-15): its group is the split. */
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
