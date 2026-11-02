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

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.identity.Capabilities;
import vn.vibefix.job.JobRules;
import vn.vibefix.job.MilestoneSplitFinalized;
import vn.vibefix.job.MilestoneSplitFinalized.Finality;
import vn.vibefix.job.MilestoneSplits;
import vn.vibefix.job.internal.Jobs.Job;
import vn.vibefix.shared.IdGenerator;

/**
 * The split a release creates (AD-4, AD-29, AD-41) and {@link MilestoneSplits}. M5 and M8 with a release create it in
 * their apply, under the Job row lock, before {@code payment} writes the pool. Its parties are the Mentor plus each
 * Crew row with {@code activated_at} whose interval overlaps the Milestone's, bounds inclusive; an unreleased row of a
 * locked account ends at its lock time, read under that lock (A-07). The flags and the cap flag are stored and nothing
 * recomputes them. With the Mentor alone it is final at once. Writing a split changes neither {@code Job.version} nor
 * {@code crewVersion}.
 */
@Component
class JobMilestoneSplits implements MilestoneSplits {

	/** The Job's copied draft period (FR-61), and FR-61's default for a Job published before the rule existed. */
	static final String DRAFT_RULE = "SPLIT_DRAFT_DAYS";

	static final int DEFAULT_DRAFT_DAYS = 7;

	private static final String STATE = """
			SELECT s.id, s.milestone_id, s.milestone_number, s.mentor_account_id, s.status, s.draft_due_at,
			       v.created_at AS final_since
			FROM job.milestone_split s
			LEFT JOIN job.milestone_split_version v ON v.split_id = s.id AND v.version_no = s.final_version_no
			""";

	/** One party as the release fixes it. */
	private record Party(UUID id, UUID accountId, boolean mentor, boolean wholeInterval, boolean activeAtRelease, int position) {
	}

	private final JdbcClient jdbc;

	private final CrewService crews;

	private final Capabilities capabilities;

	private final JobRules rules;

	private final AuditLog auditLog;

	private final ApplicationEventPublisher events;

	private final IdGenerator ids;

	private final Clock clock;

	JobMilestoneSplits(JdbcClient jdbc, CrewService crews, Capabilities capabilities, JobRules rules, AuditLog auditLog,
			ApplicationEventPublisher events, IdGenerator ids, Clock clock) {
		this.jdbc = jdbc;
		this.crews = crews;
		this.capabilities = capabilities;
		this.rules = rules;
		this.auditLog = auditLog;
		this.events = events;
		this.ids = ids;
		this.clock = clock;
	}

	/**
	 * Creates the Milestone's one split, in the apply of the release at {@code release} under the Job row lock its caller
	 * holds. With no party but the Mentor it is {@code FINAL} with a System-authored {@code MENTOR_ONLY} version, and
	 * {@link MilestoneSplitFinalized} is published in the same apply; otherwise it waits in {@code AWAITING_DRAFT}.
	 */
	void createAtRelease(Job job, UUID milestoneId, int number, Instant release) {
		List<Party> parties = partiesOf(job, milestoneId, release);
		boolean mentorOnly = parties.size() == 1;
		boolean capApplies = parties.stream().anyMatch(party -> !party.mentor() && party.activeAtRelease());
		UUID splitId = this.ids.newId();
		int days = this.rules.value(job.id(), DRAFT_RULE, DEFAULT_DRAFT_DAYS);
		Instant now = this.clock.instant();
		this.jdbc.sql("""
				INSERT INTO job.milestone_split (id, job_id, product_id, milestone_id, milestone_number, mentor_account_id, status,
				    cap_applies, released_at, draft_due_at, final_version_no, created_at)
				VALUES (:id, :job, :product, :milestone, :number, :mentor, :status, :cap, :release, :due, :final, :now)
				""")
			.param("id", splitId)
			.param("job", job.id())
			.param("product", job.productId())
			.param("milestone", milestoneId)
			.param("number", number)
			.param("mentor", job.mentorId())
			.param("status", mentorOnly ? Status.FINAL.name() : Status.AWAITING_DRAFT.name())
			.param("cap", capApplies)
			.param("release", Timestamp.from(release))
			.param("due", Timestamp.from(release.plus(Duration.ofDays(days))))
			.param("final", mentorOnly ? 1 : null, java.sql.Types.INTEGER)
			.param("now", Timestamp.from(now))
			.update();
		for (Party party : parties) {
			this.jdbc.sql("""
					INSERT INTO job.milestone_split_party (id, split_id, party_account_id, is_mentor, whole_interval, active_at_release,
					    position)
					VALUES (:id, :split, :account, :mentor, :whole, :active, :position)
					""")
				.param("id", party.id())
				.param("split", splitId)
				.param("account", party.accountId())
				.param("mentor", party.mentor())
				.param("whole", party.wholeInterval())
				.param("active", party.activeAtRelease())
				.param("position", party.position())
				.update();
		}
		if (mentorOnly) {
			finalizeForMentorAlone(job, milestoneId, number, splitId, parties.getFirst(), now);
		}
	}

	/** R9, A-10: version 1 gives the Mentor 100%, System-authored and final when saved. */
	private void finalizeForMentorAlone(Job job, UUID milestoneId, int number, UUID splitId, Party mentor, Instant now) {
		this.jdbc.sql("""
				INSERT INTO job.milestone_split_version (split_id, version_no, kind, cap_percent, author_account_id, created_at)
				VALUES (:split, 1, 'MENTOR_ONLY', NULL, NULL, :now)
				""").param("split", splitId).param("now", Timestamp.from(now)).update();
		this.jdbc.sql("""
				INSERT INTO job.milestone_split_share (split_id, version_no, party_id, percent) VALUES (:split, 1, :party, 100)
				""").param("split", splitId).param("party", mentor.id()).update();
		this.auditLog.record(AuditEntry.of(AuditActor.system(), "job.split_mentor_only", AuditObject.of("job.milestone_split", splitId))
			.forJob(job.id()));
		Map<UUID, Integer> percentages = new LinkedHashMap<>();
		percentages.put(mentor.accountId(), 100);
		this.events.publishEvent(new MilestoneSplitFinalized(this.ids.newId(), splitId, job.id(), job.productId(), milestoneId,
				number, job.title(), mentor.accountId(), 1, percentages, Finality.MENTOR_ONLY));
	}

	/** The Mentor, position 0, then each overlapping Crew row in creation order. */
	private List<Party> partiesOf(Job job, UUID milestoneId, Instant release) {
		UUID mentor = Objects.requireNonNull(job.mentorId(), "the Job's Mentor");
		Instant funded = this.jdbc.sql("SELECT funded_at FROM job.milestone WHERE id = :id")
			.param("id", milestoneId)
			.query(Timestamp.class)
			.single()
			.toInstant();
		List<CrewService.Row> rows = this.crews.rows(job.id()).stream().filter(row -> row.activatedAt() != null).toList();
		List<UUID> accounts = new ArrayList<>(rows.stream().map(CrewService.Row::studentId).toList());
		accounts.add(mentor);
		Map<UUID, Instant> locked = this.capabilities.lockedAmong(accounts);
		List<Party> parties = new ArrayList<>();
		parties.add(new Party(this.ids.newId(), mentor, true, true, !locked.containsKey(mentor), 0));
		for (CrewService.Row row : rows) {
			Instant activated = Objects.requireNonNull(row.activatedAt());
			Instant lockedAt = locked.get(row.studentId());
			Instant end = endOf(row, activated, lockedAt, release);
			if (activated.isAfter(release) || end.isBefore(funded)) {
				continue;
			}
			boolean whole = !activated.isAfter(funded) && !end.isBefore(release);
			parties.add(new Party(this.ids.newId(), row.studentId(), false, whole, row.active() && lockedAt == null, parties.size()));
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
		this.jdbc.sql("SELECT id FROM job.job WHERE product_id = :product ORDER BY id FOR SHARE")
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

	/**
	 * {@inheritDoc} A version's time bounds the moment it became final from below, so a split final for longer is always
	 * listed; one confirmed only just now, on an old version, may be listed too while its pool is being reversed.
	 */
	@Override
	@Transactional(readOnly = true)
	public List<SplitState> finalBefore(Instant cutoff) {
		return this.jdbc.sql(STATE + "WHERE s.status = 'FINAL' AND v.created_at < :cutoff ORDER BY s.created_at, s.id")
			.param("cutoff", Timestamp.from(cutoff))
			.query(JobMilestoneSplits::state)
			.list();
	}

	private static SplitState state(ResultSet rs, int n) throws SQLException {
		Timestamp finalSince = rs.getTimestamp("final_since");
		return new SplitState(rs.getObject("id", UUID.class), rs.getObject("milestone_id", UUID.class),
				rs.getInt("milestone_number"), rs.getObject("mentor_account_id", UUID.class),
				Status.valueOf(rs.getString("status")), rs.getTimestamp("draft_due_at").toInstant(),
				finalSince == null ? null : finalSince.toInstant());
	}

}
