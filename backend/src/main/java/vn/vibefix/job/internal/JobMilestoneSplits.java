package vn.vibefix.job.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.identity.Capabilities;
import vn.vibefix.job.JobRules;
import vn.vibefix.job.MilestoneSplitFinalized;
import vn.vibefix.job.MilestoneSplitFinalized.Finality;
import vn.vibefix.job.MilestoneSplits;
import vn.vibefix.job.internal.Jobs.Job;
import vn.vibefix.settings.Rule;
import vn.vibefix.settings.Settings;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.IdGenerator;
import vn.vibefix.timers.DeadlineRequest;
import vn.vibefix.timers.Deadlines;

/**
 * The split a release creates (AD-4, AD-29, AD-41) and {@link MilestoneSplits}. M5 and M8 with a release create it in
 * their apply, under the Job row lock, before {@code payment} writes the pool. Its parties are the Mentor plus each
 * Crew row with {@code activated_at} whose interval overlaps the Milestone's, bounds inclusive; an unreleased row of a
 * locked account ends at its lock time, read under that lock (A-07). The flags and the cap flag are stored and nothing
 * recomputes them. With the Mentor alone it is final at once; with Crew parties it waits for the Mentor's draft, with
 * the draft Deadline and its reminder, the Mentor's to-do, and the parties told. Writing a split changes neither
 * {@code Job.version} nor {@code crewVersion}. The release stores the Milestone's funding time and each party's active
 * interval too, as a Split Resolution reads them after the Crew rows went (AD-24, AD-40).
 *
 * <p>
 * A Self-mentored Job's split has no Mentor party (AD-29, AD-47, Story 25.6): its parties are the overlapping Crew rows
 * only, in the order of their commitment signatures, and the Customer, who is the Mentor there, is recorded as its
 * drafter and never as a party, so the Customer's payment never flows back. With one party it is final at once as
 * {@code SOLE_MEMBER}; with none, the release was refused by {@link #requireParty} before any write. The party at
 * position 0 receives the rounding remainder in every Job.
 */
@Component
class JobMilestoneSplits implements MilestoneSplits {

	/** The Job's copied draft period (FR-61), and FR-61's default for a Job published before the rule existed. */
	static final String DRAFT_RULE = "SPLIT_DRAFT_DAYS";

	static final int DEFAULT_DRAFT_DAYS = 7;

	/** A due time within this much is near: the chip warns (EXPERIENCE.md's Deadline chip). */
	private static final Duration NEAR = Duration.ofHours(48);

	private static final Duration REMINDER_BEFORE = Duration.ofDays(1);

	private static final String STATE = """
			SELECT s.id, s.milestone_id, s.milestone_number, s.mentor_account_id, s.status,
			       greatest(s.draft_due_at, s.confirmation_due_at) AS latest_due_at, s.final_at AS final_since, s.finality
			FROM job.milestone_split s
			""";

	/** One party as the release fixes it. */
	private record Party(UUID id, UUID accountId, boolean mentor, boolean wholeInterval, boolean activeAtRelease, int position,
			Instant activeFrom, Instant activeTo) {
	}

	private final JdbcClient jdbc;

	private final CrewService crews;

	private final Capabilities capabilities;

	private final JobRules rules;

	private final Settings settings;

	private final SplitStore store;

	private final SplitFinalization finalization;

	private final SplitCommands commands;

	private final SplitNotices notices;

	private final Deadlines deadlines;

	private final IdGenerator ids;

	private final Clock clock;

	private final SplitVersionGrants grants;

	JobMilestoneSplits(JdbcClient jdbc, CrewService crews, Capabilities capabilities, JobRules rules, Settings settings,
			SplitStore store, SplitFinalization finalization, SplitCommands commands, SplitNotices notices, Deadlines deadlines,
			IdGenerator ids, Clock clock, SplitVersionGrants grants) {
		this.grants = grants;
		this.jdbc = jdbc;
		this.crews = crews;
		this.capabilities = capabilities;
		this.rules = rules;
		this.settings = settings;
		this.store = store;
		this.finalization = finalization;
		this.commands = commands;
		this.notices = notices;
		this.deadlines = deadlines;
		this.ids = ids;
		this.clock = clock;
	}

	/**
	 * Creates the Milestone's one split, in the apply of the release at {@code release} under the Job row lock its caller
	 * holds. With one party it is {@code FINAL} with a System-authored version at 100% ({@code MENTOR_ONLY}, or
	 * {@code SOLE_MEMBER} in a Self-mentored Job), and {@link MilestoneSplitFinalized} is published in the same apply;
	 * otherwise it waits in {@code AWAITING_DRAFT}.
	 * @throws IllegalStateException with no party: the release's guard, {@link #requireParty}, was not run
	 */
	void createAtRelease(Job job, UUID milestoneId, int number, Instant release) {
		Instant funded = fundedAt(milestoneId);
		List<Party> parties = partiesOf(job, funded, release);
		if (parties.isEmpty()) {
			throw new IllegalStateException("A release with no split party was not refused: " + milestoneId);
		}
		// No cap in the mode: there is no Mentor share to cap (AD-29).
		boolean capApplies = !job.selfMentored() && parties.stream().anyMatch(party -> !party.mentor() && party.activeAtRelease());
		UUID splitId = this.ids.newId();
		int days = this.rules.value(job.id(), DRAFT_RULE, DEFAULT_DRAFT_DAYS);
		Instant draftDue = release.plus(Duration.ofDays(days));
		this.jdbc.sql("""
				INSERT INTO job.milestone_split (id, job_id, product_id, milestone_id, milestone_number, mentor_account_id,
				    self_mentored, status, cap_applies, funded_at, released_at, draft_due_at, created_at)
				VALUES (:id, :job, :product, :milestone, :number, :mentor, :mode, 'AWAITING_DRAFT', :cap, :funded, :release, :due,
				    :now)
				""")
			.param("id", splitId)
			.param("job", job.id())
			.param("product", job.productId())
			.param("milestone", milestoneId)
			.param("number", number)
			.param("mentor", job.mentorId())
			.param("mode", job.selfMentored())
			.param("cap", capApplies)
			.param("funded", Timestamp.from(funded))
			.param("release", Timestamp.from(release))
			.param("due", Timestamp.from(draftDue))
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
		for (Party party : parties) {
			this.jdbc.sql("""
					INSERT INTO job.milestone_split_party (id, split_id, party_account_id, is_mentor, whole_interval, active_at_release,
					    position, active_from, active_to)
					VALUES (:id, :split, :account, :mentor, :whole, :active, :position, :from, :to)
					""")
				.param("id", party.id())
				.param("split", splitId)
				.param("account", party.accountId())
				.param("mentor", party.mentor())
				.param("whole", party.wholeInterval())
				.param("active", party.activeAtRelease())
				.param("position", party.position())
				.param("from", Timestamp.from(party.activeFrom()))
				.param("to", Timestamp.from(party.activeTo()))
				.update();
		}
		SplitStore.Split split = this.store.find(splitId).orElseThrow();
		if (parties.size() == 1) {
			finalizeForOneParty(split, parties.getFirst());
			return;
		}
		this.deadlines.schedule(new DeadlineRequest(SplitDraftDeadline.KEY, splitId, splitId.toString(), job.id(), draftDue));
		if (days > REMINDER_BEFORE.toDays()) {
			this.deadlines.schedule(new DeadlineRequest(SplitDraftReminder.KEY, splitId, splitId.toString(), job.id(),
					draftDue.minus(REMINDER_BEFORE)));
		}
		this.notices.released(split, job.title(), this.store.parties(splitId));
	}

	/**
	 * R9, A-10: version 1 gives the one party 100%, System-authored and final when saved: the Mentor alone, or the one
	 * member of a Self-mentored Job's Milestone.
	 */
	private void finalizeForOneParty(SplitStore.Split split, Party party) {
		boolean member = split.selfMentored();
		this.store.insertVersion(split.id(), 1, member ? VersionKind.SOLE_MEMBER : VersionKind.MENTOR_ONLY, null, null,
				Map.of(party.id(), 100), this.clock.instant());
		this.finalization.makeFinal(split, 1, member ? Finality.SOLE_MEMBER : Finality.MENTOR_ONLY);
	}

	/**
	 * The parties a release at {@code release} fixes, from {@link CrewService#wouldBeParties}, the one definition
	 * (AD-33). A normal Job: the Mentor at position 0, then each overlapping Crew row in creation order. A Self-mentored
	 * Job: the overlapping Crew rows only, by their commitment signature ({@code activated_at}) and then the Crew row's
	 * ID, so position 0 is the member who signed first (R58).
	 */
	private List<Party> partiesOf(Job job, Instant funded, Instant release) {
		List<CrewService.ActiveSpan> spans = new ArrayList<>(this.crews.wouldBeParties(job.id(), funded, release));
		List<Party> parties = new ArrayList<>();
		if (job.selfMentored()) {
			// The lower Crew row ID as the database orders UUIDs, by their bytes: the text form sorts that way, and
			// UUID.compareTo, which compares signed halves, does not.
			spans.sort(Comparator.comparing(CrewService.ActiveSpan::from).thenComparing(span -> span.row().id().toString()));
		}
		else {
			UUID mentor = Objects.requireNonNull(job.mentorId(), "the Job's Mentor");
			boolean locked = this.capabilities.lockedAmong(List.of(mentor)).containsKey(mentor);
			parties.add(new Party(this.ids.newId(), mentor, true, true, !locked, 0, funded, release));
		}
		for (CrewService.ActiveSpan span : spans) {
			parties.add(new Party(this.ids.newId(), span.row().studentId(), false, span.whole(), span.activeAtEnd(),
					parties.size(), span.from(), span.to()));
		}
		return parties;
	}

	private Instant fundedAt(UUID milestoneId) {
		return this.jdbc.sql("SELECT funded_at FROM job.milestone WHERE id = :id")
			.param("id", milestoneId)
			.query(Timestamp.class)
			.single()
			.toInstant();
	}

	/**
	 * The guard of a release (M5, and M8 with a release above 0), before any write (AD-4, AD-29, AD-31): a Self-mentored
	 * Job's Milestone that no Crew member was active on since it was funded has no split party, and the Customer is
	 * never one, so nothing can be released on it. A normal Job always has its Mentor.
	 * @throws ApiException {@code GUARD_FAILED NO_SPLIT_PARTY}
	 */
	void requireParty(Job job, UUID milestoneId, Instant at) {
		if (job.selfMentored() && this.crews.wouldBeParties(job.id(), fundedAt(milestoneId), at).isEmpty()) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.NO_SPLIT_PARTY);
		}
	}

	@Override
	@Transactional(readOnly = true)
	public boolean hasWouldBeSplitParty(UUID milestoneId) {
		// A normal Job's Mentor is always a party; a Milestone not funded yet has nothing to release.
		Map.Entry<UUID, Timestamp> mode = this.jdbc.sql("""
				SELECT j.id, m.funded_at FROM job.milestone m JOIN job.job j ON j.id = m.job_id
				WHERE m.id = :id AND j.self_mentored AND m.funded_at IS NOT NULL
				""")
			.param("id", milestoneId)
			.query((rs, n) -> Map.entry(rs.getObject("id", UUID.class), rs.getTimestamp("funded_at")))
			.optional()
			.orElse(null);
		return mode == null
				|| !this.crews.wouldBeParties(mode.getKey(), mode.getValue().toInstant(), this.clock.instant()).isEmpty();
	}

	@Override
	@Transactional(readOnly = true)
	public boolean hasOpenSplit(UUID jobId) {
		return this.jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM job.milestone_split WHERE job_id = :job AND status <> 'FINAL')
				""").param("job", jobId).query(Boolean.class).single();
	}

	@Override
	@Transactional
	public Set<UUID> openSplitJobs(UUID productId) {
		this.jdbc.sql("SELECT id FROM job.job WHERE product_id = :product ORDER BY id FOR UPDATE")
			.param("product", productId)
			.query(UUID.class)
			.list();
		return new LinkedHashSet<>(this.jdbc.sql("""
				SELECT DISTINCT job_id FROM job.milestone_split WHERE product_id = :product AND status <> 'FINAL' ORDER BY job_id
				""").param("product", productId).query(UUID.class).list());
	}

	@Override
	@Transactional(readOnly = true)
	public List<SplitParty> splitPartiesOf(UUID milestoneId) {
		return this.jdbc.sql("""
				SELECT p.party_account_id, p.is_mentor, p.whole_interval, p.active_at_release FROM job.milestone_split_party p
				JOIN job.milestone_split s ON s.id = p.split_id
				WHERE s.milestone_id = :milestone ORDER BY p.position
				""")
			.param("milestone", milestoneId)
			.query((rs, n) -> new SplitParty(rs.getObject("party_account_id", UUID.class), rs.getBoolean("is_mentor"),
					rs.getBoolean("whole_interval"), rs.getBoolean("active_at_release")))
			.list();
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<SplitState> splitOf(UUID milestoneId) {
		return this.jdbc.sql(STATE + "WHERE s.milestone_id = :milestone")
			.param("milestone", milestoneId)
			.query(JobMilestoneSplits::state)
			.optional();
	}

	@Override
	@Transactional(readOnly = true)
	public List<PartySplit> ofParty(UUID accountId) {
		return this.jdbc.sql("SELECT s.id, s.job_id, s.milestone_id, s.milestone_number, p.is_mentor, s.status, s.finality,"
				+ " s.final_at, s.released_at, s.draft_due_at, s.confirmation_due_at,"
				+ " EXISTS (SELECT 1 FROM job.job j WHERE j.id = s.job_id AND j." + Jobs.LIVE + ") AS job_live"
				+ " FROM job.milestone_split_party p JOIN job.milestone_split s ON s.id = p.split_id"
				+ " WHERE p.party_account_id = :account ORDER BY s.released_at DESC, s.milestone_number DESC, s.id")
			.param("account", accountId)
			.query((rs, n) -> {
				Timestamp finalAt = rs.getTimestamp("final_at");
				Timestamp due = rs.getTimestamp("confirmation_due_at");
				String finality = rs.getString("finality");
				return new PartySplit(rs.getObject("id", UUID.class), rs.getObject("job_id", UUID.class),
						rs.getObject("milestone_id", UUID.class), rs.getInt("milestone_number"), rs.getBoolean("is_mentor"),
						Status.valueOf(rs.getString("status")), finality == null ? null : Finality.valueOf(finality),
						finalAt == null ? null : finalAt.toInstant(), rs.getTimestamp("released_at").toInstant(),
						rs.getTimestamp("draft_due_at").toInstant(), due == null ? null : due.toInstant(),
						rs.getBoolean("job_live"));
			})
			.list();
	}

	@Override
	@Transactional(readOnly = true)
	public List<SplitState> finalBefore(Instant cutoff) {
		return this.jdbc.sql(STATE + "WHERE s.status = 'FINAL' AND s.final_at < :cutoff ORDER BY s.created_at, s.id")
			.param("cutoff", Timestamp.from(cutoff))
			.query(JobMilestoneSplits::state)
			.list();
	}

	@Override
	@Transactional(readOnly = true)
	public SplitRules rulesOf(UUID jobId) {
		return new SplitRules(this.settings.value(Rule.MENTOR_SHARE_CAP_PERCENT),
				this.rules.value(jobId, DRAFT_RULE, DEFAULT_DRAFT_DAYS),
				this.rules.value(jobId, SplitCommands.CONFIRMATION_RULE, SplitCommands.DEFAULT_CONFIRMATION_DAYS),
				this.settings.value(Rule.SPLIT_EDIT_LIMIT));
	}

	@Override
	@Transactional(readOnly = true)
	public List<SplitRead> readableBy(UUID jobId, UUID viewerId) {
		// A normal Job's Customer never reads a split (AD-20); a Self-mentored Job's is the drafter of each (AD-29).
		Boolean customer = this.jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM job.job WHERE id = :job AND customer_account_id = :viewer AND NOT self_mentored)
				""").param("job", jobId).param("viewer", viewerId).query(Boolean.class).single();
		if (Boolean.TRUE.equals(customer)) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		List<SplitStore.Split> splits = this.store.ofPartyOrDrafter(jobId, viewerId);
		if (splits.isEmpty()) {
			Boolean mentor = this.jdbc.sql("SELECT EXISTS (SELECT 1 FROM job.job WHERE id = :job AND mentor_account_id = :viewer AND "
					+ Jobs.LIVE + ")").param("job", jobId).param("viewer", viewerId).query(Boolean.class).single();
			if (!Boolean.TRUE.equals(mentor)) {
				throw new ApiException(ErrorCode.NOT_FOUND);
			}
			return List.of();
		}
		String title = this.store.title(jobId).orElse(null);
		boolean live = this.store.live(jobId);
		int limit = this.settings.value(Rule.SPLIT_EDIT_LIMIT);
		Instant now = this.clock.instant();
		return splits.stream().map(split -> read(split, title, live, viewerId, limit, now)).toList();
	}

	private SplitRead read(SplitStore.Split split, @Nullable String title, boolean live, UUID viewerId, int limit,
			Instant now) {
		List<SplitStore.Party> parties = this.store.parties(split.id());
		Map<UUID, Instant> left = this.store.leftAt(split.jobId(),
				parties.stream().map(SplitStore.Party::accountId).filter(Objects::nonNull).toList());
		List<PartyRead> partyReads = parties.stream()
			.map(party -> new PartyRead(party.id(), party.accountId(), party.mentor(), party.wholeInterval(), party.activeAtRelease(),
					party.accountId() == null ? null : left.get(party.accountId())))
			.toList();
		List<SplitStore.Version> versions = this.store.versions(split.id());
		List<VersionRead> reads = versions.stream().map(version -> versionRead(split, parties, version)).toList();
		VersionRead latest = reads.stream().findFirst().orElse(null);
		Set<UUID> confirmed = latest == null ? Set.of()
				: new LinkedHashSet<>(this.store.confirmers(split.id(), latest.versionNo()));
		UUID viewerParty = parties.stream()
			.filter(party -> viewerId.equals(party.accountId()))
			.map(SplitStore.Party::id)
			.findFirst()
			.orElse(null);
		int members = (int) parties.stream().filter(party -> !party.mentor()).count();
		Instant due = split.confirmationDueAt();
		int editsUsed = this.store.editsUsed(versions);
		return new SplitRead(split.id(), split.milestoneId(), split.number(), title, live, split.selfMentored(),
				SplitStore.remainderParty(parties).id(), split.status(), split.finality(),
				split.capApplies(), this.commands.capOf(split), editsUsed, Math.max(0, limit - editsUsed), limit,
				split.draftDueAt(), !split.draftDueAt().isAfter(now.plus(NEAR)),
				split.status() == Status.AWAITING_DRAFT && !now.isBefore(split.draftDueAt()),
				now.plus(Duration.ofDays(this.rules.value(split.jobId(), SplitCommands.CONFIRMATION_RULE,
						SplitCommands.DEFAULT_CONFIRMATION_DAYS))),
				due, due != null && !due.isAfter(now.plus(NEAR)), split.pastDue(now), split.finalAt(), confirmed.size(), members,
				split.draftedBy(viewerId), viewerParty != null && confirmed.contains(viewerParty), partyReads, latest,
				reads.isEmpty() ? List.of() : reads.subList(1, reads.size()));
	}

	/** A version with every party's line, in the fixed order, a party the version left out at 0. */
	private VersionRead versionRead(SplitStore.Split split, List<SplitStore.Party> parties, SplitStore.Version version) {
		Map<UUID, Integer> percents = this.store.percents(split.id(), version.number());
		Set<UUID> confirmed = new LinkedHashSet<>(this.store.confirmers(split.id(), version.number()));
		List<LineRead> lines = parties.stream()
			.map(party -> new LineRead(party.id(), percents.getOrDefault(party.id(), 0), confirmed.contains(party.id())))
			.toList();
		return new VersionRead(version.number(), version.kind(), version.capPercent(), version.createdAt(), lines);
	}

	@Override
	@Transactional(readOnly = true)
	public PreviewBasis previewBasis(UUID splitId, UUID mentorId, List<PercentLine> lines) {
		SplitStore.Split split = this.store.find(splitId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		List<SplitStore.Party> parties = this.store.parties(splitId);
		SplitCommands.requireDrafter(split, parties, mentorId);
		Map<UUID, Integer> percents = this.commands.validated(split, parties, lines, this.commands.capOf(split));
		return new PreviewBasis(split.milestoneId(), SplitStore.remainderParty(parties).id(), percents);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<SplitCase> caseOf(UUID splitId) {
		return this.store.find(splitId).map(this::splitCase);
	}

	@Override
	@Transactional
	public Optional<SplitCase> lockForSplit(UUID jobId, UUID milestoneId) {
		this.store.lockJobRow(jobId);
		return this.store.ofMilestone(milestoneId).filter(split -> split.jobId().equals(jobId)).map(this::splitCase);
	}

	private SplitCase splitCase(SplitStore.Split split) {
		SplitStore.Version latest = this.store.latest(split.id()).orElse(null);
		SplitStore.Interval interval = this.store.interval(split.id());
		return new SplitCase(split.id(), split.jobId(), split.productId(), split.milestoneId(), split.number(),
				this.store.title(split.jobId()).orElse(null), this.store.customerOf(split.jobId()).orElse(null), split.mentorId(),
				split.selfMentored(), split.status(), split.capApplies(), latest == null ? 0 : latest.number(),
				latest == null ? null : latest.capPercent(),
				split.confirmationDueAt(), split.pastDue(this.clock.instant()), interval.fundedAt(), interval.releasedAt(),
				this.store.caseParties(split.id()));
	}

	@Override
	@Transactional
	public void holdSplit(UUID milestoneId, UUID resolutionId) {
		this.commands.hold(milestoneId, resolutionId);
	}

	@Override
	@Transactional
	public Resumed resumeSplit(UUID milestoneId) {
		return this.commands.resume(milestoneId);
	}

	@Override
	@Transactional(readOnly = true)
	public PreviewBasis decisionBasis(UUID splitId, List<PercentLine> lines) {
		SplitStore.Split split = this.store.find(splitId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		List<SplitStore.Party> parties = this.store.parties(splitId);
		Integer cap = this.store.latest(splitId).map(SplitStore.Version::capPercent).orElse(null);
		Map<UUID, Integer> percents = this.commands.validated(split, parties, lines, cap);
		return new PreviewBasis(split.milestoneId(), SplitStore.remainderParty(parties).id(), percents);
	}

	@Override
	@Transactional
	public int decideSplit(UUID milestoneId, UUID resolutionId, UUID adminId, List<PercentLine> lines) {
		return this.commands.decide(milestoneId, resolutionId, adminId, lines);
	}

	@Override
	@Transactional(readOnly = true)
	public List<EvidenceVersion> versionsOf(UUID splitId) {
		return this.store.evidence(splitId);
	}

	@Override
	@Transactional
	public List<EvidenceVersion> evidenceUnderGrant(UUID adminId, UUID splitId) {
		SplitStore.Split split = this.store.find(splitId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		return this.grants.versions(adminId, split.jobId(), splitId);
	}

	@Override
	@Transactional(readOnly = true)
	public boolean jobContentKept(UUID jobId) {
		return this.store.contentKept(jobId);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<MilestoneSplitFinalized> finalSplitOf(UUID milestoneId) {
		return this.store.ofMilestone(milestoneId)
			.filter(split -> split.status() == Status.FINAL
					&& SplitStore.remainderParty(this.store.parties(split.id())).accountId() != null)
			.map(split -> {
				int versionNo = this.jdbc.sql("SELECT final_version_no FROM job.milestone_split WHERE id = :id")
					.param("id", split.id())
					.query(Integer.class)
					.single();
				return new MilestoneSplitFinalized(this.ids.newId(), split.id(), split.jobId(), split.productId(),
						split.milestoneId(), split.number(), this.store.title(split.jobId()).orElse(""),
						Objects.requireNonNull(SplitStore.remainderParty(this.store.parties(split.id())).accountId()), versionNo,
						this.finalization.percentages(split, versionNo),
						Objects.requireNonNull(split.finality()));
			});
	}

	private static SplitState state(ResultSet rs, int n) throws SQLException {
		Timestamp finalSince = rs.getTimestamp("final_since");
		String finality = rs.getString("finality");
		return new SplitState(rs.getObject("id", UUID.class), rs.getObject("milestone_id", UUID.class),
				rs.getInt("milestone_number"), rs.getObject("mentor_account_id", UUID.class),
				Status.valueOf(rs.getString("status")), rs.getTimestamp("latest_due_at").toInstant(),
				finalSince == null ? null : finalSince.toInstant(), finality == null ? null : Finality.valueOf(finality));
	}

}
