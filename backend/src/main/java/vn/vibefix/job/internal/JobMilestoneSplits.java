package vn.vibefix.job.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
			       greatest(s.draft_due_at, s.confirmation_due_at) AS latest_due_at, s.final_at AS final_since
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
	 * holds. With no party but the Mentor it is {@code FINAL} with a System-authored {@code MENTOR_ONLY} version, and
	 * {@link MilestoneSplitFinalized} is published in the same apply; otherwise it waits in {@code AWAITING_DRAFT}.
	 */
	void createAtRelease(Job job, UUID milestoneId, int number, Instant release) {
		Instant funded = this.jdbc.sql("SELECT funded_at FROM job.milestone WHERE id = :id")
			.param("id", milestoneId)
			.query(Timestamp.class)
			.single()
			.toInstant();
		List<Party> parties = partiesOf(job, funded, release);
		boolean capApplies = parties.stream().anyMatch(party -> !party.mentor() && party.activeAtRelease());
		UUID splitId = this.ids.newId();
		int days = this.rules.value(job.id(), DRAFT_RULE, DEFAULT_DRAFT_DAYS);
		Instant draftDue = release.plus(Duration.ofDays(days));
		this.jdbc.sql("""
				INSERT INTO job.milestone_split (id, job_id, product_id, milestone_id, milestone_number, mentor_account_id, status,
				    cap_applies, funded_at, released_at, draft_due_at, created_at)
				VALUES (:id, :job, :product, :milestone, :number, :mentor, 'AWAITING_DRAFT', :cap, :funded, :release, :due, :now)
				""")
			.param("id", splitId)
			.param("job", job.id())
			.param("product", job.productId())
			.param("milestone", milestoneId)
			.param("number", number)
			.param("mentor", job.mentorId())
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
			finalizeForMentorAlone(split, parties.getFirst());
			return;
		}
		this.deadlines.schedule(new DeadlineRequest(SplitDraftDeadline.KEY, splitId, splitId.toString(), job.id(), draftDue));
		if (days > REMINDER_BEFORE.toDays()) {
			this.deadlines.schedule(new DeadlineRequest(SplitDraftReminder.KEY, splitId, splitId.toString(), job.id(),
					draftDue.minus(REMINDER_BEFORE)));
		}
		this.notices.released(split, job.title(), this.store.parties(splitId));
	}

	/** R9, A-10: version 1 gives the Mentor 100%, System-authored and final when saved. */
	private void finalizeForMentorAlone(SplitStore.Split split, Party mentor) {
		this.store.insertVersion(split.id(), 1, VersionKind.MENTOR_ONLY, null, null, Map.of(mentor.id(), 100), this.clock.instant());
		this.finalization.makeFinal(split, 1, Finality.MENTOR_ONLY);
	}

	/** The Mentor, position 0, then each overlapping Crew row in creation order. */
	private List<Party> partiesOf(Job job, Instant funded, Instant release) {
		UUID mentor = Objects.requireNonNull(job.mentorId(), "the Job's Mentor");
		List<CrewService.Row> rows = this.crews.rows(job.id()).stream().filter(row -> row.activatedAt() != null).toList();
		List<UUID> accounts = new ArrayList<>(rows.stream().map(CrewService.Row::studentId).toList());
		accounts.add(mentor);
		Map<UUID, Instant> locked = this.capabilities.lockedAmong(accounts);
		List<Party> parties = new ArrayList<>();
		parties.add(new Party(this.ids.newId(), mentor, true, true, !locked.containsKey(mentor), 0, funded, release));
		for (CrewService.Row row : rows) {
			Instant activated = Objects.requireNonNull(row.activatedAt());
			Instant lockedAt = locked.get(row.studentId());
			Instant end = endOf(row, activated, lockedAt, release);
			if (activated.isAfter(release) || end.isBefore(funded)) {
				continue;
			}
			boolean whole = !activated.isAfter(funded) && !end.isBefore(release);
			parties.add(new Party(this.ids.newId(), row.studentId(), false, whole, row.active() && lockedAt == null, parties.size(),
					activated, end.isAfter(release) ? release : end));
		}
		return parties;
	}

	/** A row's end: its {@code window_end}; for an unreleased row of a locked account, the lock; otherwise the release. */
	private static Instant endOf(CrewService.Row row, Instant activated, Instant lockedAt, Instant release) {
		if (row.windowEnd() != null) {
			return row.windowEnd();
		}
		if (!row.released() && lockedAt != null) {
			return lockedAt.isAfter(activated) ? lockedAt : activated;
		}
		return release;
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
		Boolean customer = this.jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM job.job WHERE id = :job AND customer_account_id = :viewer)
				""").param("job", jobId).param("viewer", viewerId).query(Boolean.class).single();
		if (Boolean.TRUE.equals(customer)) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		List<SplitStore.Split> splits = this.store.ofParty(jobId, viewerId);
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
		return new SplitRead(split.id(), split.milestoneId(), split.number(), title, live, split.status(), split.finality(),
				split.capApplies(), this.commands.capOf(split), editsUsed, Math.max(0, limit - editsUsed), limit,
				split.draftDueAt(), !split.draftDueAt().isAfter(now.plus(NEAR)),
				split.status() == Status.AWAITING_DRAFT && !now.isBefore(split.draftDueAt()),
				now.plus(Duration.ofDays(this.rules.value(split.jobId(), SplitCommands.CONFIRMATION_RULE,
						SplitCommands.DEFAULT_CONFIRMATION_DAYS))),
				due, due != null && !due.isAfter(now.plus(NEAR)), split.pastDue(now), split.finalAt(), confirmed.size(), members,
				viewerId.equals(split.mentorId()), viewerParty != null && confirmed.contains(viewerParty), partyReads, latest,
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
		if (parties.stream().noneMatch(party -> mentorId.equals(party.accountId()))) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		if (!mentorId.equals(split.mentorId())) {
			throw new ApiException(ErrorCode.FORBIDDEN, JobReason.MENTOR_ONLY);
		}
		Map<UUID, Integer> percents = this.commands.validated(split, parties, lines, this.commands.capOf(split));
		UUID mentorParty = parties.stream().filter(SplitStore.Party::mentor).map(SplitStore.Party::id).findFirst().orElseThrow();
		return new PreviewBasis(split.milestoneId(), mentorParty, percents);
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
				split.status(), split.capApplies(), latest == null ? 0 : latest.number(), latest == null ? null : latest.capPercent(),
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
		UUID mentorParty = parties.stream().filter(SplitStore.Party::mentor).map(SplitStore.Party::id).findFirst().orElseThrow();
		return new PreviewBasis(split.milestoneId(), mentorParty, percents);
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
			.filter(split -> split.status() == Status.FINAL && split.mentorId() != null)
			.map(split -> {
				int versionNo = this.jdbc.sql("SELECT final_version_no FROM job.milestone_split WHERE id = :id")
					.param("id", split.id())
					.query(Integer.class)
					.single();
				return new MilestoneSplitFinalized(this.ids.newId(), split.id(), split.jobId(), split.productId(),
						split.milestoneId(), split.number(), this.store.title(split.jobId()).orElse(""),
						Objects.requireNonNull(split.mentorId()), versionNo, this.finalization.percentages(split, versionNo),
						Objects.requireNonNull(split.finality()));
			});
	}

	private static SplitState state(ResultSet rs, int n) throws SQLException {
		Timestamp finalSince = rs.getTimestamp("final_since");
		return new SplitState(rs.getObject("id", UUID.class), rs.getObject("milestone_id", UUID.class),
				rs.getInt("milestone_number"), rs.getObject("mentor_account_id", UUID.class),
				Status.valueOf(rs.getString("status")), rs.getTimestamp("latest_due_at").toInstant(),
				finalSince == null ? null : finalSince.toInstant());
	}

}
