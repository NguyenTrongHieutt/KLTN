package vn.vibefix.payment.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.identity.MentorDirectory;
import vn.vibefix.identity.PersonDirectory;
import vn.vibefix.job.MilestoneFunding;
import vn.vibefix.job.MilestoneFunding.FundingJob;
import vn.vibefix.job.MilestoneFunding.FundingMilestone;
import vn.vibefix.job.MilestoneSplitFinalized.Finality;
import vn.vibefix.job.MilestoneSplits;
import vn.vibefix.job.MilestoneSplits.PartySplit;
import vn.vibefix.job.MilestoneSplits.SplitParty;
import vn.vibefix.job.MilestoneSplits.SplitState;
import vn.vibefix.job.MilestoneSplits.Status;

/**
 * Where test money is (FR-44, AD-15, AD-20): a Milestone's figures; CUS-10, which never names a party; MEN-7, the Balance as
 * the sum of the account's RELEASE entries, so pooled money never counts in it, with one line per Job and Milestone; and
 * the Mentor's JOB-7, which lists each release by party. A line names the role held in its split, from the reader's own
 * party row ({@code job.ofParty}), or, for a release with no split row, on that Job: never {@code job.roleOf}, and never
 * another person's line. The pools are read in one batch, and JOB-10 reads its own through the same query. Reads take no
 * lock. A Customer's views treat the Jobs of a deleted Product as gone, though their ledger stays (AD-24).
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

	/** A Milestone's pool, and whether its final split reversed it: JOB-10's {@code paid}. */
	record Pool(long amountVnd, boolean reversed) {
	}

	/** The role a reader held on a line: the split's Mentor, or a Crew member of it. */
	enum LineRole {
		MENTOR, CREW
	}

	/**
	 * One part of a release, as the Mentor reads it.
	 * @param erased an erasure anonymized the person: the ledger still names them, the split's party rows no longer do
	 */
	record Part(UUID partyId, String name, boolean erased, long amountVnd, @Nullable Integer percent) {
	}

	/**
	 * One line of MEN-7: one of the reader's RELEASE entries, a zero part of a final split, or a split still pending.
	 * @param jobTitle empty once the Job is gone
	 * @param jobLive the Job still has its screens
	 * @param role the role held in the split, or on the Job for a release with no split row; empty for such a release on a
	 * Job that is gone
	 * @param status the split's, while a split row exists
	 * @param pending the pool is not reversed yet, the split being open, final and waiting for the payout, or refused: the
	 * line shows the pooled amount, which counts in no Balance
	 * @param baseAmountVnd the Milestone's release: the pool, or the base the parts were split from
	 * @param yourPartVnd the reader's RELEASE; 0 while pending and for a zero part
	 * @param crew the other parties' parts, on a line whose split names the reader its Mentor
	 */
	record ReleaseLine(UUID jobId, String jobTitle, boolean jobLive, UUID milestoneId, int number, String milestoneTitle,
			@Nullable LineRole role, @Nullable Status status, @Nullable Finality finality, @Nullable Instant finalAt,
			boolean pending, Instant releasedAt, long baseAmountVnd, long yourPartVnd, @Nullable Integer yourPercent,
			@Nullable Integer splitVersionNo, List<Part> crew) {
	}

	/**
	 * MEN-7.
	 * @param releases the reader's RELEASE entries
	 * @param sentToCrewVnd and {@code heldVnd}: for a reader who is the Mentor on a line or of a Job
	 */
	record Balance(long balanceVnd, int releases, @Nullable Long sentToCrewVnd, @Nullable Long heldVnd, List<ReleaseLine> lines) {
	}

	/** A line before it is read out: the reader's RELEASE, or none for a split they are a party of. */
	private record Seed(UUID milestoneId, UUID jobId, @Nullable PartySplit split, @Nullable Release release) {
	}

	private static final UUID NOBODY = new UUID(0, 0);

	private final JdbcClient jdbc;

	private final MilestoneFunding funding;

	private final PersonDirectory people;

	private final MentorDirectory mentors;

	private final MilestoneSplits splits;

	PaymentHistory(JdbcClient jdbc, MilestoneFunding funding, PersonDirectory people, MentorDirectory mentors,
			MilestoneSplits splits) {
		this.splits = splits;
		this.jdbc = jdbc;
		this.funding = funding;
		this.people = people;
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
	 * MEN-7, for any user (FR-44, FR-45, AD-15, AD-20): the sum of the account's RELEASE entries, and one line per Job and
	 * Milestone, merging those entries with the splits the account is a party of, so a release with no split row stays
	 * listed. A split the account has no RELEASE in is pending while its pool is not reversed, whatever its status, so money
	 * never vanishes from the screen between a final split and its payout; once reversed, it is a zero part. The other
	 * parties' parts are shown only where the reader's party row is the Mentor's, or, with no split row, where the reader
	 * is the Job's Mentor. A Job whose Product was deleted has no title any more: its split still says the Milestone's
	 * number, and a pending line its Job, whose JOB-10 stays readable. Its reads share one snapshot, so a payout
	 * committing between them never shows a paid party a zero part.
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	Balance balance(UUID accountId) {
		List<Release> own = releases("party_account_id = :id", Map.of("id", accountId));
		Map<UUID, PartySplit> splitOf = new LinkedHashMap<>();
		this.splits.ofParty(accountId).forEach(split -> splitOf.putIfAbsent(split.milestoneId(), split));
		Map<UUID, UUID> jobIds = jobIds(own.stream()
			.map(Release::milestoneId)
			.filter(milestoneId -> !splitOf.containsKey(milestoneId))
			.distinct()
			.toList());
		List<Seed> seeds = new ArrayList<>();
		Set<UUID> paid = new HashSet<>();
		for (Release release : own) {
			PartySplit split = splitOf.get(release.milestoneId());
			UUID jobId = split != null ? split.jobId() : jobIds.getOrDefault(release.milestoneId(), NOBODY);
			seeds.add(new Seed(release.milestoneId(), jobId, split, release));
			paid.add(release.milestoneId());
		}
		splitOf.values()
			.stream()
			.filter(split -> !paid.contains(split.milestoneId()))
			.forEach(split -> seeds.add(new Seed(split.milestoneId(), split.jobId(), split, null)));
		Map<UUID, Pool> pools = pools(splitOf.keySet());
		Map<UUID, Optional<FundingJob>> jobs = new HashMap<>();
		Map<UUID, LineRole> roles = new HashMap<>();
		for (Seed seed : seeds) {
			roles.computeIfAbsent(seed.milestoneId(), milestoneId -> roleOf(seed, job(seed, jobs), accountId));
		}
		// The Crew's parts, read once for every Milestone the reader is the Mentor on and that has paid out.
		List<UUID> mentored = seeds.stream()
			.filter(seed -> roles.get(seed.milestoneId()) == LineRole.MENTOR && !pending(seed, pools))
			.map(Seed::milestoneId)
			.distinct()
			.toList();
		Map<UUID, List<Release>> paidOut = new HashMap<>();
		if (!mentored.isEmpty()) {
			releases("milestone_id IN (:ids)", Map.of("ids", mentored))
				.forEach(release -> paidOut.computeIfAbsent(release.milestoneId(), key -> new ArrayList<>()).add(release));
		}
		Map<UUID, String> names = names(paidOut.values().stream().flatMap(List::stream).map(Release::partyId).distinct().toList(),
				accountId);
		List<ReleaseLine> lines = new ArrayList<>();
		for (Seed seed : seeds) {
			FundingJob job = job(seed, jobs);
			List<Part> crew = crew(seed, paidOut.getOrDefault(seed.milestoneId(), List.of()), names, accountId);
			lines.add(line(seed, job, roles.get(seed.milestoneId()), pools.get(seed.milestoneId()), pending(seed, pools), crew));
		}
		lines.sort(Comparator.comparing(ReleaseLine::releasedAt).reversed());
		long balance = own.stream().mapToLong(Release::amountVnd).sum();
		List<FundingJob> mentoring = this.funding.ofMentor(accountId);
		boolean mentor = lines.stream().anyMatch(line -> line.role() == LineRole.MENTOR);
		if (!mentor && mentoring.isEmpty()) {
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

	/** The Milestone's pool, if its release made one: JOB-10 reads it per card. */
	@Transactional(readOnly = true)
	Optional<Pool> pool(UUID milestoneId) {
		return Optional.ofNullable(pools(List.of(milestoneId)).get(milestoneId));
	}

	/** The pools of these Milestones, in one read; a Milestone whose release made none is left out. */
	@Transactional(readOnly = true)
	Map<UUID, Pool> pools(Collection<UUID> milestoneIds) {
		Map<UUID, Pool> pools = new HashMap<>();
		if (milestoneIds.isEmpty()) {
			return pools;
		}
		this.jdbc.sql("""
				SELECT e.milestone_id, e.amount_vnd,
				    EXISTS (SELECT 1 FROM payment.ledger_entry r WHERE r.reverses_entry_id = e.id) AS reversed
				FROM payment.ledger_entry e WHERE e.entry_type = 'RELEASE_POOLED' AND e.milestone_id IN (:ids)
				""")
			.param("ids", List.copyOf(milestoneIds))
			.query((rs, n) -> Map.entry(rs.getObject("milestone_id", UUID.class),
					new Pool(rs.getLong("amount_vnd"), rs.getBoolean("reversed"))))
			.list()
			.forEach(entry -> pools.put(entry.getKey(), entry.getValue()));
		return pools;
	}

	/**
	 * Display names of one Job's parties: the Mentor's display name for {@code mentorId}, which the caller read
	 * from the Job or its split, and a Crew member's shown name for everyone else.
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

	/**
	 * The accounts the Milestone's split still names as parties: a ledger party missing from them was erased (AD-24). Empty
	 * without a split, where nobody is marked so.
	 */
	@Transactional(readOnly = true)
	Set<UUID> partiesNamed(UUID milestoneId) {
		Set<UUID> named = new HashSet<>();
		this.splits.splitPartiesOf(milestoneId).stream().map(SplitParty::accountId).filter(Objects::nonNull).forEach(named::add);
		return named;
	}

	private ReleaseLine line(Seed seed, @Nullable FundingJob job, @Nullable LineRole role, @Nullable Pool pool, boolean pending,
			List<Part> crew) {
		PartySplit split = seed.split();
		Release release = seed.release();
		FundingMilestone milestone = job == null ? null : job.milestones()
			.stream()
			.filter(found -> found.milestoneId().equals(seed.milestoneId()))
			.findFirst()
			.orElse(null);
		int number = milestone != null ? milestone.number() : split != null ? split.milestoneNumber()
				: this.splits.splitOf(seed.milestoneId()).map(SplitState::milestoneNumber).orElse(0);
		boolean live = job != null;
		long pooled = pool == null ? 0 : pool.amountVnd();
		if (release != null) {
			return new ReleaseLine(seed.jobId(), job == null ? "" : job.title(), live, seed.milestoneId(), number,
					milestone == null ? "" : milestone.title(), role, split == null ? null : split.status(),
					split == null ? null : split.finality(), split == null ? null : split.finalAt(), false, release.releasedAt(),
					release.baseAmountVnd() == null ? release.amountVnd() : release.baseAmountVnd(), release.amountVnd(),
					release.percent(), release.splitVersionNo(), crew);
		}
		PartySplit open = Objects.requireNonNull(split);
		Instant at = pending || open.finalAt() == null ? open.releasedAt() : open.finalAt();
		return new ReleaseLine(seed.jobId(), job == null ? "" : job.title(), live, seed.milestoneId(), number,
				milestone == null ? "" : milestone.title(), role, open.status(), open.finality(), open.finalAt(), pending, at,
				pooled, 0, pending ? null : 0, null, pending ? List.of() : crew);
	}

	/** The other parties' parts on a line whose reader is its Mentor; nobody else's line holds any. */
	private List<Part> crew(Seed seed, List<Release> paidOut, Map<UUID, String> names, UUID accountId) {
		if (paidOut.isEmpty()) {
			return List.of();
		}
		String group = seed.release() == null ? null : seed.release().group();
		Set<UUID> named = seed.split() == null ? null : partiesNamed(seed.milestoneId());
		return paidOut.stream()
			.filter(part -> group == null || group.equals(part.group()))
			.filter(part -> !part.partyId().equals(accountId))
			.map(part -> new Part(part.partyId(), names.getOrDefault(part.partyId(), ""),
					named != null && !named.contains(part.partyId()), part.amountVnd(), part.percent()))
			.toList();
	}

	/** The role in the split, from the reader's party row; with no split row, on the Job while it stands. */
	private static @Nullable LineRole roleOf(Seed seed, @Nullable FundingJob job, UUID accountId) {
		if (seed.split() != null) {
			return seed.split().mentor() ? LineRole.MENTOR : LineRole.CREW;
		}
		if (job == null) {
			return null;
		}
		return accountId.equals(job.mentorId()) ? LineRole.MENTOR : LineRole.CREW;
	}

	/** A split the reader has no RELEASE in, whose pool is not reversed: JOB-10's {@code paid} is false. */
	private static boolean pending(Seed seed, Map<UUID, Pool> pools) {
		if (seed.release() != null) {
			return false;
		}
		Pool pool = pools.get(seed.milestoneId());
		return pool == null || !pool.reversed();
	}

	/** The line's Job while it stands; a split that says its Job is gone needs no read. */
	private @Nullable FundingJob job(Seed seed, Map<UUID, Optional<FundingJob>> cache) {
		if (NOBODY.equals(seed.jobId()) || (seed.split() != null && !seed.split().jobLive())) {
			return null;
		}
		return cache.computeIfAbsent(seed.jobId(), this.funding::byId).orElse(null);
	}

	/** The Job of each Milestone, from its ledger account, which outlives the Job (AD-24). */
	private Map<UUID, UUID> jobIds(List<UUID> milestoneIds) {
		Map<UUID, UUID> jobs = new HashMap<>();
		if (milestoneIds.isEmpty()) {
			return jobs;
		}
		this.jdbc.sql("SELECT milestone_id, job_id FROM payment.ledger_account WHERE milestone_id IN (:ids)")
			.param("ids", milestoneIds)
			.query((rs, n) -> Map.entry(rs.getObject("milestone_id", UUID.class), rs.getObject("job_id", UUID.class)))
			.list()
			.forEach(entry -> jobs.put(entry.getKey(), entry.getValue()));
		return jobs;
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

}
