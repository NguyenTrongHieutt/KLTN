package vn.vibefix.job.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.vibefix.job.MilestoneSplitFinalized.Finality;
import vn.vibefix.job.MilestoneSplits.CaseParty;
import vn.vibefix.job.MilestoneSplits.EvidenceLine;
import vn.vibefix.job.MilestoneSplits.EvidenceVersion;
import vn.vibefix.job.MilestoneSplits.Status;
import vn.vibefix.job.MilestoneSplits.VersionKind;

/**
 * The rows of the Milestone Splits (AD-29), which every read and write of a split goes through. Each write happens under
 * the Job row lock {@link #lockJobRow} takes, and none changes {@code Job.version} or {@code crewVersion}.
 */
@Component
class SplitStore {

	/**
	 * A split's row.
	 * @param round how many confirmation rounds it has had
	 * @param confirmationDueAt the current round's due time: {@code Deadlines.dueAt} would read the earliest pending row,
	 * and an old round's stays until it ends {@code OBSOLETE}
	 */
	record Split(UUID id, UUID jobId, UUID productId, UUID milestoneId, int number, @Nullable UUID mentorId, Status status,
			boolean capApplies, Instant draftDueAt, int round, @Nullable Instant confirmationDueAt,
			@Nullable Instant finalAt, @Nullable Finality finality) {

		/** {@code PROPOSED} and past its due time by the Clock: nothing supersedes the version now (A-09). */
		boolean pastDue(Instant now) {
			return this.status == Status.PROPOSED && this.confirmationDueAt != null && !now.isBefore(this.confirmationDueAt);
		}

	}

	/** A party's row, fixed at the release. */
	record Party(UUID id, @Nullable UUID accountId, boolean mentor, boolean wholeInterval, boolean activeAtRelease) {
	}

	/** A version's row, without its lines. */
	record Version(int number, VersionKind kind, @Nullable Integer capPercent, Instant createdAt) {
	}

	/** The Milestone's interval: {@code fundedAt} is empty on a split older than Story 22.3 whose Milestone had gone. */
	record Interval(@Nullable Instant fundedAt, Instant releasedAt) {
	}

	private static final String COLUMNS = """
			id, job_id, product_id, milestone_id, milestone_number, mentor_account_id, status, cap_applies, draft_due_at,
			confirmation_round, confirmation_due_at, final_at, finality""";

	private final JdbcClient jdbc;

	SplitStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	Optional<Split> find(UUID splitId) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM job.milestone_split WHERE id = :id")
			.param("id", splitId)
			.query(SplitStore::split)
			.optional();
	}

	/** The Milestone's one split, if a release created it. */
	Optional<Split> ofMilestone(UUID milestoneId) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM job.milestone_split WHERE milestone_id = :milestone")
			.param("milestone", milestoneId)
			.query(SplitStore::split)
			.optional();
	}

	/** The Job's splits {@code accountId} is a party of, newest Milestone first. */
	List<Split> ofParty(UUID jobId, UUID accountId) {
		return this.jdbc.sql("SELECT " + COLUMNS + """
				 FROM job.milestone_split s
				WHERE s.job_id = :job AND EXISTS (SELECT 1 FROM job.milestone_split_party p
				    WHERE p.split_id = s.id AND p.party_account_id = :account)
				ORDER BY s.milestone_number DESC, s.created_at DESC, s.id
				""").param("job", jobId).param("account", accountId).query(SplitStore::split).list();
	}

	/**
	 * AD-29's Job row lock: raw, with no {@code Jobs.LIVE}, because a lock queued behind a Product deletion must still
	 * lock the bare row it leaves, and {@code FOR UPDATE} re-evaluates a filter on the updated row. No row at all means
	 * the Job's last split was final before it went. A split write reads the split again after this.
	 */
	void lockJobRow(UUID jobId) {
		this.jdbc.sql("SELECT id FROM job.job WHERE id = :id FOR UPDATE").param("id", jobId).query(UUID.class).optional();
	}

	/** Every party in the fixed order: the Mentor first, then Crew row creation order. */
	List<Party> parties(UUID splitId) {
		return this.jdbc.sql("""
				SELECT id, party_account_id, is_mentor, whole_interval, active_at_release FROM job.milestone_split_party
				WHERE split_id = :split ORDER BY position
				""")
			.param("split", splitId)
			.query((rs, n) -> new Party(rs.getObject("id", UUID.class), rs.getObject("party_account_id", UUID.class),
					rs.getBoolean("is_mentor"), rs.getBoolean("whole_interval"), rs.getBoolean("active_at_release")))
			.list();
	}

	/** Every party in the fixed order, with the active interval the release stored. */
	List<CaseParty> caseParties(UUID splitId) {
		return this.jdbc.sql("""
				SELECT id, party_account_id, is_mentor, whole_interval, active_at_release, active_from, active_to
				FROM job.milestone_split_party WHERE split_id = :split ORDER BY position
				""")
			.param("split", splitId)
			.query((rs, n) -> new CaseParty(rs.getObject("id", UUID.class), rs.getObject("party_account_id", UUID.class),
					rs.getBoolean("is_mentor"), rs.getBoolean("whole_interval"), rs.getBoolean("active_at_release"),
					instant(rs.getTimestamp("active_from")), instant(rs.getTimestamp("active_to"))))
			.list();
	}

	/** When the Milestone was funded and released, as the split stored them. */
	Interval interval(UUID splitId) {
		return this.jdbc.sql("SELECT funded_at, released_at FROM job.milestone_split WHERE id = :id")
			.param("id", splitId)
			.query((rs, n) -> new Interval(instant(rs.getTimestamp("funded_at")), rs.getTimestamp("released_at").toInstant()))
			.single();
	}

	/** Every version, newest first. */
	List<Version> versions(UUID splitId) {
		return this.jdbc.sql("""
				SELECT version_no, kind, cap_percent, created_at FROM job.milestone_split_version
				WHERE split_id = :split ORDER BY version_no DESC
				""")
			.param("split", splitId)
			.query((rs, n) -> new Version(rs.getInt("version_no"), VersionKind.valueOf(rs.getString("kind")),
					rs.getObject("cap_percent", Integer.class), rs.getTimestamp("created_at").toInstant()))
			.list();
	}

	Optional<Version> latest(UUID splitId) {
		return versions(splitId).stream().findFirst();
	}

	/** The edits used: the Mentor's versions after the first (AD-29). */
	int editsUsed(List<Version> versions) {
		return Math.max(0, (int) versions.stream().filter(version -> version.kind() == VersionKind.DRAFT).count() - 1);
	}

	/** Saves a version and its lines; both stay as written. */
	void insertVersion(UUID splitId, int versionNo, VersionKind kind, @Nullable Integer capPercent, @Nullable UUID authorId,
			Map<UUID, Integer> percents, Instant now) {
		this.jdbc.sql("""
				INSERT INTO job.milestone_split_version (split_id, version_no, kind, cap_percent, author_account_id, created_at)
				VALUES (:split, :version, :kind, :cap, :author, :now)
				""")
			.param("split", splitId)
			.param("version", versionNo)
			.param("kind", kind.name())
			.param("cap", capPercent, java.sql.Types.INTEGER)
			.param("author", authorId)
			.param("now", Timestamp.from(now))
			.update();
		percents.forEach((party, percent) -> this.jdbc.sql("""
				INSERT INTO job.milestone_split_share (split_id, version_no, party_id, percent)
				VALUES (:split, :version, :party, :percent)
				""")
			.param("split", splitId)
			.param("version", versionNo)
			.param("party", party)
			.param("percent", percent)
			.update());
	}

	/** Each party's whole percentage in the version, by party; a party the version left out has no entry. */
	Map<UUID, Integer> percents(UUID splitId, int versionNo) {
		Map<UUID, Integer> percents = new LinkedHashMap<>();
		this.jdbc.sql("""
				SELECT party_id, percent FROM job.milestone_split_share WHERE split_id = :split AND version_no = :version
				""")
			.param("split", splitId)
			.param("version", versionNo)
			.query((rs, n) -> Map.entry(rs.getObject("party_id", UUID.class), rs.getInt("percent")))
			.list()
			.forEach(entry -> percents.put(entry.getKey(), entry.getValue()));
		return percents;
	}

	/** The parties who confirmed the version; earlier confirmations stay on their own version. */
	List<UUID> confirmers(UUID splitId, int versionNo) {
		return new ArrayList<>(this.jdbc.sql("""
				SELECT party_id FROM job.milestone_split_confirmation WHERE split_id = :split AND version_no = :version
				""").param("split", splitId).param("version", versionNo).query(UUID.class).list());
	}

	/** @return whether this confirmation is new: a repeat changes nothing */
	boolean confirm(UUID splitId, int versionNo, UUID partyId, Instant now) {
		return this.jdbc.sql("""
				INSERT INTO job.milestone_split_confirmation (split_id, version_no, party_id, confirmed_at)
				VALUES (:split, :version, :party, :now) ON CONFLICT DO NOTHING
				""")
			.param("split", splitId)
			.param("version", versionNo)
			.param("party", partyId)
			.param("now", Timestamp.from(now))
			.update() > 0;
	}

	/** {@code IN_RESOLUTION}: nothing moves until a withdrawal or a decision (AD-29, AD-40). */
	void hold(UUID splitId) {
		this.jdbc.sql("UPDATE job.milestone_split SET status = 'IN_RESOLUTION' WHERE id = :id").param("id", splitId).update();
	}

	/** Every version, oldest first, with who saved it, each line and when its party confirmed it (AD-40). */
	List<EvidenceVersion> evidence(UUID splitId) {
		Map<Integer, List<EvidenceLine>> lines = new HashMap<>();
		this.jdbc.sql("""
				SELECT s.version_no, s.party_id, s.percent, c.confirmed_at
				FROM job.milestone_split_share s JOIN job.milestone_split_party p ON p.id = s.party_id
				LEFT JOIN job.milestone_split_confirmation c
				  ON c.split_id = s.split_id AND c.version_no = s.version_no AND c.party_id = s.party_id
				WHERE s.split_id = :split ORDER BY s.version_no, p.position
				""").param("split", splitId).query(rs -> {
			lines.computeIfAbsent(rs.getInt("version_no"), version -> new ArrayList<>())
				.add(new EvidenceLine(rs.getObject("party_id", UUID.class), rs.getInt("percent"),
						instant(rs.getTimestamp("confirmed_at"))));
		});
		return this.jdbc.sql("""
				SELECT version_no, kind, author_account_id, cap_percent, created_at FROM job.milestone_split_version
				WHERE split_id = :split ORDER BY version_no
				""")
			.param("split", splitId)
			.query((rs, n) -> new EvidenceVersion(rs.getInt("version_no"), VersionKind.valueOf(rs.getString("kind")),
					rs.getObject("author_account_id", UUID.class), rs.getObject("cap_percent", Integer.class),
					rs.getTimestamp("created_at").toInstant(), lines.getOrDefault(rs.getInt("version_no"), List.of())))
			.list();
	}

	/** The Customer a Job's row names, a bare one included; an erased one is nobody. */
	Optional<UUID> customerOf(UUID jobId) {
		return this.jdbc.sql("SELECT customer_account_id FROM job.job WHERE id = :id")
			.param("id", jobId)
			.query(UUID.class)
			.optional()
			.filter(customer -> !JobPersonalData.NOBODY.equals(customer));
	}

	/** The next round of confirmations: {@code PROPOSED}, with the due time the Job's copied days give. */
	void startRound(UUID splitId, int round, Instant due) {
		this.jdbc.sql("""
				UPDATE job.milestone_split SET status = 'PROPOSED', confirmation_round = :round, confirmation_due_at = :due
				WHERE id = :id
				""").param("round", round).param("due", Timestamp.from(due)).param("id", splitId).update();
	}

	/** The title a Job's row holds, a bare one included; none once the row went. */
	Optional<String> title(UUID jobId) {
		return this.jdbc.sql("SELECT title FROM job.job WHERE id = :id").param("id", jobId).query(String.class).optional();
	}

	/** Whether the Job still has its screens: a bare row, or none at all, has none. */
	boolean live(UUID jobId) {
		return this.jdbc.sql("SELECT EXISTS (SELECT 1 FROM job.job WHERE id = :id AND " + Jobs.LIVE + ")")
			.param("id", jobId)
			.query(Boolean.class)
			.single();
	}

	/** Whether the Job's row stands with its Product, and no retention run retired that Product's data since the Job began. */
	boolean contentKept(UUID jobId) {
		return this.jdbc.sql("SELECT EXISTS (SELECT 1 FROM job.job j WHERE j.id = :id AND " + Jobs.LIVE + """
				 AND NOT EXISTS (SELECT 1 FROM job.product_retention r
				    WHERE r.product_id = j.product_id AND r.retired_at >= j.created_at))
				""").param("id", jobId).query(Boolean.class).single();
	}

	/** When the party's Crew row says they left, a lock included, while the row exists. */
	Map<UUID, Instant> leftAt(UUID jobId, List<UUID> accounts) {
		Map<UUID, Instant> left = new HashMap<>();
		if (accounts.isEmpty()) {
			return left;
		}
		this.jdbc.sql("""
				SELECT member_account_id, window_end FROM job.crew_row
				WHERE job_id = :job AND member_account_id IN (:accounts) AND released_reason = 'LEFT' AND window_end IS NOT NULL
				""")
			.param("job", jobId)
			.param("accounts", accounts)
			.query((rs, n) -> Map.entry(rs.getObject("member_account_id", UUID.class), rs.getTimestamp("window_end").toInstant()))
			.list()
			.forEach(entry -> left.put(entry.getKey(), entry.getValue()));
		return left;
	}

	private static @Nullable Instant instant(@Nullable Timestamp at) {
		return at == null ? null : at.toInstant();
	}

	private static Split split(ResultSet rs, int n) throws SQLException {
		Timestamp due = rs.getTimestamp("confirmation_due_at");
		Timestamp finalAt = rs.getTimestamp("final_at");
		String finality = rs.getString("finality");
		return new Split(rs.getObject("id", UUID.class), rs.getObject("job_id", UUID.class),
				rs.getObject("product_id", UUID.class), rs.getObject("milestone_id", UUID.class), rs.getInt("milestone_number"),
				rs.getObject("mentor_account_id", UUID.class), Status.valueOf(rs.getString("status")),
				rs.getBoolean("cap_applies"), rs.getTimestamp("draft_due_at").toInstant(), rs.getInt("confirmation_round"),
				due == null ? null : due.toInstant(), finalAt == null ? null : finalAt.toInstant(),
				finality == null ? null : Finality.valueOf(finality));
	}

}
