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
