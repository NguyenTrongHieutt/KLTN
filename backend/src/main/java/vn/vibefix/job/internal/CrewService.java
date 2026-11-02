package vn.vibefix.job.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.identity.Capabilities;
import vn.vibefix.identity.PersonDirectory;
import vn.vibefix.identity.PersonDirectory.PersonCard;
import vn.vibefix.job.Crews;
import vn.vibefix.job.internal.Jobs.Job;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.IdGenerator;

/**
 * The Crew rows (FR-75, AD-30, AD-41, Story 21.5): a pick inserts an {@code ASSIGNED} row naming its Crew Application
 * ({@link JobCrewPicks}), the person's signature makes it {@code ACTIVE}, and a row ends {@code RELEASED} as
 * {@code DECLINED}, {@code WITHDRAWN}, {@code LEFT} or {@code JOB_ENDED}. Every write runs under the Job row lock its
 * caller holds and increments {@code crew_version}; a write that changes the usernames the Customer sees, an
 * activation or the release of an active row, also increments {@code version}. Crew Terms are no longer written: J6
 * pins one in-force version giving the Mentor 100% ({@link #pinMentorOnly}), which M1 and the share holds read until
 * Story 22.4 retires it (C-3); a release splits by the Milestone's own split (Story 22.1). Nobody removes a member who
 * signed: they leave ({@link #leave}, Story 21.6), or a lock counts as leaving.
 */
@Component
class CrewService implements Crews {

	/** FR-75, AD-30: with {@code features.largeCrews} on, a Crew holds up to this many rows that are not released. */
	static final int LARGE_CREW_LIMIT = 5;

	/** Where a member may leave (FR-82, AD-41): in Closing, J8 already ended their access. */
	static final Set<JobState> LEAVES = EnumSet.of(JobState.AGREEMENT, JobState.STARTING, JobState.ACTIVE);

	/**
	 * A Crew row {@code r} whose active window (from {@code activated_at} to {@code window_end}, or still open) overlaps
	 * the work of at least one Accepted Milestone of its Job (from its funding to its acceptance or, without one, its
	 * settlement), bounds included: whom a Portfolio Entry and the history as Crew member credit (FR-50, AD-41).
	 */
	static final String ACTIVE_DURING_ACCEPTED_WORK = """
			r.activated_at IS NOT NULL AND EXISTS (SELECT 1 FROM job.milestone m WHERE m.job_id = r.job_id
			    AND m.removed_at IS NULL AND m.state IN ('ACCEPTED', 'SETTLED') AND m.funded_at IS NOT NULL
			    AND r.activated_at <= coalesce(m.accepted_at, m.settled_at)
			    AND (r.window_end IS NULL OR r.window_end >= m.funded_at))""";

	/**
	 * One row as {@code job} keeps it.
	 * @param leftNote the reason the member gave when leaving, for JOB-9's Mentor alone; {@code null} on a lock
	 */
	record Row(UUID id, UUID jobId, UUID studentId, String status, @Nullable Instant signedAt,
			@Nullable Instant activatedAt, @Nullable Instant windowEnd, Instant createdAt,
			@Nullable String releasedReason, @Nullable UUID applicationId, @Nullable String leftNote) {

		boolean released() {
			return "RELEASED".equals(this.status);
		}

		boolean active() {
			return "ACTIVE".equals(this.status);
		}

		boolean assigned() {
			return "ASSIGNED".equals(this.status);
		}

	}

	private static final String ROW_COLUMNS = """
			id, job_id, member_account_id, status, signed_at, activated_at, window_end, created_at, released_reason,
			crew_application_id, left_note
			""";

	private final JdbcClient jdbc;

	private final PersonDirectory people;

	private final Capabilities capabilities;

	private final AuditLog auditLog;

	private final IdGenerator ids;

	private final Clock clock;

	CrewService(JdbcClient jdbc, PersonDirectory people, Capabilities capabilities, AuditLog auditLog, IdGenerator ids,
			Clock clock) {
		this.auditLog = auditLog;
		this.jdbc = jdbc;
		this.people = people;
		this.capabilities = capabilities;
		this.ids = ids;
		this.clock = clock;
	}

	// ---- Writes, under the Job row lock the caller holds ------------------------------------------

	/** A pick's {@code ASSIGNED} row for the account, naming its Crew Application (AD-41). */
	Row insertPick(UUID jobId, UUID accountId, UUID applicationId) {
		UUID rowId = this.ids.newId();
		this.jdbc.sql("""
				INSERT INTO job.crew_row (id, job_id, member_account_id, status, crew_application_id, created_at)
				VALUES (:id, :job, :member, 'ASSIGNED', :application, :now)
				""")
			.param("id", rowId)
			.param("job", jobId)
			.param("member", accountId)
			.param("application", applicationId)
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
		return row(rowId).orElseThrow();
	}

	/** The signature makes the row {@code ACTIVE} (AD-41): signed when the person signed, active from now. */
	void activate(Row row, Instant signedAt) {
		this.jdbc.sql("""
				UPDATE job.crew_row SET status = 'ACTIVE', signed_at = :signed, activated_at = :now
				WHERE id = :id AND status = 'ASSIGNED'
				""")
			.param("id", row.id())
			.param("signed", Timestamp.from(signedAt))
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
	}

	/**
	 * A lock counts as leaving (FR-59, FR-82, AD-19, AD-41): each unreleased row of the account on the Job is
	 * released, an {@code ACTIVE} one as {@code LEFT} and an {@code ASSIGNED} one, a pick not yet signed, as
	 * {@code DECLINED}; a window it had ends at {@code lockedAt}, the lock's own time, never the listener's. The caller
	 * holds the Job lock. Safe to replay: a released row changes nothing.
	 * @return the rows released, as they were before
	 */
	List<Row> releaseLocked(Job job, UUID accountId, Instant lockedAt) {
		List<Row> rows = this.jdbc.sql("SELECT " + ROW_COLUMNS
				+ " FROM job.crew_row WHERE job_id = :job AND member_account_id = :id AND status <> 'RELEASED' ORDER BY id")
			.param("job", job.id())
			.param("id", accountId)
			.query(CrewService::mapRow)
			.list();
		if (rows.isEmpty()) {
			return rows;
		}
		boolean left = false;
		for (Row row : rows) {
			release(row, row.active() ? "LEFT" : "DECLINED", lockedAt);
			left |= row.active();
		}
		bump(job.id(), left);
		return rows;
	}

	/**
	 * The member leaves (FR-82, AD-41, UX-DR104): their {@code ACTIVE} row is released as {@code LEFT}, its window ending
	 * now, with the reason they gave, and it cannot be undone. A busy marker or a freeze blocks nothing. The caller holds
	 * the Job lock.
	 * @param note the reason, for JOB-9's Mentor alone; {@code null} for none
	 * @return the row as it was before
	 * @throws ApiException {@code NOT_FOUND} for an account with no {@code ACTIVE} row; {@code GUARD_FAILED LEAVE_CLOSED}
	 * in Closing, where J8 already ended their access
	 */
	Row leave(Job job, UUID accountId, @Nullable String note) {
		Row row = rows(job.id()).stream()
			.filter(found -> found.studentId().equals(accountId) && found.active())
			.findFirst()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (!LEAVES.contains(job.state())) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.LEAVE_CLOSED);
		}
		release(row, "LEFT");
		if (note != null) {
			this.jdbc.sql("UPDATE job.crew_row SET left_note = :note WHERE id = :id")
				.param("note", note)
				.param("id", row.id())
				.update();
		}
		bump(job.id(), true);
		this.auditLog.record(AuditEntry.of(AuditActor.user(accountId), "job.crew_member_left", AuditObject.of("job.crew_row", row.id()))
			.forJob(job.id()));
		return row;
	}

	/**
	 * J9 and J10 (FR-75, AD-41): every Crew row of the Job not yet released is released as {@code JOB_ENDED}, its window
	 * closed.
	 */
	void releaseForJobEnd(UUID jobId) {
		unreleased(jobId).forEach(row -> {
			release(row, "JOB_ENDED");
			if (row.assigned()) {
				auditPickReleased(row);
			}
		});
	}

	/**
	 * J8 (AD-41, A-04): every pick not yet signed is released as {@code JOB_ENDED}, so nobody signs into a Closing Job;
	 * an {@code ACTIVE} row stays until J9 or J10.
	 * @return the picks released, as they were before
	 */
	List<Row> releasePicksForJobEnd(UUID jobId) {
		List<Row> picks = unreleased(jobId).stream().filter(Row::assigned).toList();
		picks.forEach(row -> {
			release(row, "JOB_ENDED");
			auditPickReleased(row);
		});
		if (!picks.isEmpty()) {
			bump(jobId, false);
		}
		return picks;
	}

	private void auditPickReleased(Row row) {
		this.auditLog.record(AuditEntry.of(AuditActor.system(), "job.crew_pick_released", AuditObject.of("job.crew_row", row.id()))
			.forJob(row.jobId()));
	}

	/** Releases the row now; a window it had ends now. */
	void release(Row row, String reason) {
		release(row, reason, this.clock.instant());
	}

	/**
	 * Releases the row (AD-41) with {@code reason}: {@code DECLINED}, {@code WITHDRAWN}, {@code JOB_ENDED}, or
	 * {@code LEFT}; it is released now, and a window it had ends at {@code windowEnd}, which a lock sets to its own
	 * time (Story 20.4), never before the row was activated (a row activated after the lock but before its listener
	 * ran ends where it began).
	 */
	void release(Row row, String reason, Instant windowEnd) {
		this.jdbc.sql("""
				UPDATE job.crew_row SET status = 'RELEASED', released_at = :now, released_reason = :reason,
				    window_end = CASE WHEN activated_at IS NOT NULL AND window_end IS NULL
				        THEN greatest(CAST(:end AS TIMESTAMPTZ), activated_at) ELSE window_end END
				WHERE id = :id AND status <> 'RELEASED'
				""")
			.param("id", row.id())
			.param("reason", reason)
			.param("now", Timestamp.from(this.clock.instant()))
			.param("end", Timestamp.from(windowEnd))
			.update();
	}

	/** Every Crew write increments {@code crew_version}; one the Customer sees increments {@code version} too. */
	void bump(UUID jobId, boolean usernamesChanged) {
		this.jdbc.sql("UPDATE job.job SET crew_version = crew_version + 1, version = version + :bump, updated_at = :now WHERE id = :id")
			.param("id", jobId)
			.param("bump", usernamesChanged ? 1 : 0)
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
	}

	/**
	 * J6 (C-3, Story 21.5): Crew Terms are no longer written, so J6 brings one version into force that gives the Mentor
	 * 100%: M1 pins it, and the share holds keep working until Story 22.4 retires them; a release no longer reads it
	 * (Story 22.1). A version still pending from before is voided; a version already in force (a Job that started before)
	 * is kept. Runs in J6's apply.
	 * @return the version in force
	 */
	int pinMentorOnly(UUID jobId, UUID mentorId) {
		Optional<Integer> inForce = inForce(jobId);
		if (inForce.isPresent()) {
			return inForce.get();
		}
		this.jdbc.sql("UPDATE job.crew_terms_version SET status = 'VOIDED' WHERE job_id = :job AND status = 'PENDING'")
			.param("job", jobId)
			.update();
		int next = this.jdbc.sql("SELECT coalesce(max(version_no), 0) + 1 FROM job.crew_terms_version WHERE job_id = :job")
			.param("job", jobId)
			.query(Integer.class)
			.single();
		this.jdbc.sql("""
				INSERT INTO job.crew_terms_version (job_id, version_no, status, cap_percent, author_account_id, created_at)
				VALUES (:job, :no, 'IN_FORCE', 100, :mentor, :now)
				""")
			.param("job", jobId)
			.param("no", next)
			.param("mentor", mentorId)
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
		this.jdbc.sql("""
				INSERT INTO job.crew_terms_share (job_id, version_no, party_account_id, crew_row_id, percent)
				VALUES (:job, :no, :mentor, NULL, 100)
				""").param("job", jobId).param("no", next).param("mentor", mentorId).update();
		return next;
	}

	// ---- Reads ------------------------------------------------------------------------------

	/**
	 * The counted {@code ACTIVE} rows (AD-41, Story 20.4): an {@code ACTIVE} row counts only while its account is
	 * unlocked, read through {@code identity.lockedAmong}, because a lock commits before its listener releases the
	 * row. J6's guard, the recruitment's "a member has signed" and the empty-Crew check read it under the Job lock
	 * their caller holds.
	 */
	List<Row> countedActive(UUID jobId) {
		List<Row> active = rows(jobId).stream().filter(Row::active).toList();
		if (active.isEmpty()) {
			return active;
		}
		Map<UUID, Instant> locked = this.capabilities.lockedAmong(active.stream().map(Row::studentId).toList());
		return active.stream().filter(row -> !locked.containsKey(row.studentId())).toList();
	}

	@Override
	@Transactional(readOnly = true)
	public boolean hasCountedMember(UUID jobId) {
		return !countedActive(jobId).isEmpty();
	}

	/**
	 * Whether the account's row on the Job was released as {@code LEFT} after it was active, a lock's included (AD-41):
	 * what "· đã rời" reads, also after the Job ends, and never the Job's state.
	 */
	boolean left(UUID jobId, UUID accountId) {
		return this.jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM job.crew_row WHERE job_id = :job AND member_account_id = :id
				    AND released_reason = 'LEFT' AND activated_at IS NOT NULL)
				""").param("job", jobId).param("id", accountId).query(Boolean.class).single();
	}

	/**
	 * The Milestone numbers N-28 names for a member about to leave (R9): each Held Milestone, and each Milestone with a
	 * split not {@code FINAL} where the account is a party, from 1 in the Agreement's order.
	 */
	List<Integer> partyMilestones(UUID jobId, UUID accountId) {
		return this.jdbc.sql("""
				SELECT n FROM (SELECT state, CAST(row_number() OVER (ORDER BY position, created_at, id) AS INTEGER) AS n
				    FROM job.milestone WHERE job_id = :job AND removed_at IS NULL) numbered WHERE state IN (:held)
				UNION
				SELECT s.milestone_number FROM job.milestone_split s JOIN job.milestone_split_party p ON p.split_id = s.id
				WHERE s.job_id = :job AND s.status <> 'FINAL' AND p.party_account_id = :id
				ORDER BY 1
				""")
			.param("job", jobId)
			.param("id", accountId)
			.param("held", MilestoneState.held().stream().map(Enum::name).toList())
			.query(Integer.class)
			.list();
	}

	/** Whether the account is locked now (AD-19): {@code identity.lockedAmong}. */
	boolean locked(UUID accountId) {
		return !this.capabilities.lockedAmong(List.of(accountId)).isEmpty();
	}

	@Override
	@Transactional(readOnly = true)
	public CrewRelationship crewRelationship(UUID jobId, UUID accountId) {
		List<Row> mine = this.jdbc.sql("SELECT " + ROW_COLUMNS + " FROM job.crew_row WHERE job_id = :job AND member_account_id = :id"
				+ " ORDER BY created_at DESC, id")
			.param("job", jobId)
			.param("id", accountId)
			.query(CrewService::mapRow)
			.list();
		Optional<Row> open = mine.stream().filter(row -> !row.released()).findFirst();
		if (open.isPresent()) {
			Row row = open.get();
			return row.active() ? new CrewRelationship(Relationship.ACTIVE, row.signedAt(), null)
					: new CrewRelationship(Relationship.ASSIGNED, row.signedAt(), null);
		}
		return mine.stream()
			.filter(row -> row.activatedAt() != null)
			.findFirst()
			.map(row -> new CrewRelationship(Relationship.FORMER, row.signedAt(), row.windowEnd()))
			.orElse(CrewRelationship.NONE);
	}

	@Override
	@Transactional(readOnly = true)
	public List<CrewMember> crewOf(UUID jobId, Selection selection) {
		return rows(jobId).stream()
			.filter(row -> switch (selection) {
				case UNRELEASED -> !row.released();
				case ACTIVE -> row.active();
				case EVER_ACTIVE -> row.activatedAt() != null;
			})
			.map(row -> new CrewMember(row.id(), row.studentId(), row.status(), row.signedAt(), row.activatedAt(), row.windowEnd()))
			.toList();
	}

	/**
	 * {@inheritDoc} By {@link #ACTIVE_DURING_ACCEPTED_WORK}: a member active when a Milestone was funded or accepted
	 * shares its work, the history as Crew member counts the same Jobs (AD-41).
	 */
	@Override
	@Transactional(readOnly = true)
	public List<UUID> activeDuringAcceptedWork(UUID jobId) {
		return this.jdbc.sql("SELECT r.member_account_id FROM job.crew_row r WHERE r.job_id = :job AND "
				+ ACTIVE_DURING_ACCEPTED_WORK + " GROUP BY r.member_account_id ORDER BY min(r.created_at), r.member_account_id")
			.param("job", jobId)
			.query(UUID.class)
			.list();
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<UUID> runningCrewOf(UUID studentId) {
		return this.jdbc.sql("SELECT job_id FROM job.crew_row WHERE member_account_id = :id AND status <> 'RELEASED'")
			.param("id", studentId)
			.query(UUID.class)
			.optional();
	}

	/**
	 * Whether the account has a counted Crew row on the Job (AD-43): one that is not released, or that was
	 * released after it was activated. The triggers of {@code V202610271100__job_one_role_per_job.sql} use the same
	 * rule.
	 */
	boolean countedRow(UUID jobId, UUID accountId) {
		return this.jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM job.crew_row WHERE job_id = :job AND member_account_id = :id
				    AND (status <> 'RELEASED' OR activated_at IS NOT NULL))
				""").param("job", jobId).param("id", accountId).query(Boolean.class).single();
	}

	/**
	 * One role per Job (AD-43): the Job's Customer or Mentor never joins its Crew. The command checks it first,
	 * under the Job lock, and the {@code job.crew_row} trigger is the backstop.
	 * @throws ApiException {@code GUARD_FAILED ROLE_TAKEN}
	 */
	static void requireNoOtherRole(Job job, UUID memberId) {
		if (memberId.equals(job.customerId()) || memberId.equals(job.mentorId())) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.ROLE_TAKEN);
		}
	}

	/** The Job's rows, oldest first. */
	List<Row> rows(UUID jobId) {
		return this.jdbc.sql("SELECT " + ROW_COLUMNS + " FROM job.crew_row WHERE job_id = :job ORDER BY created_at, id")
			.param("job", jobId)
			.query(CrewService::mapRow)
			.list();
	}

	private List<Row> unreleased(UUID jobId) {
		return this.jdbc.sql("SELECT " + ROW_COLUMNS + " FROM job.crew_row WHERE job_id = :job AND status <> 'RELEASED' ORDER BY id")
			.param("job", jobId)
			.query(CrewService::mapRow)
			.list();
	}

	Optional<Row> row(UUID rowId) {
		return this.jdbc.sql("SELECT " + ROW_COLUMNS + " FROM job.crew_row WHERE id = :id")
			.param("id", rowId)
			.query(CrewService::mapRow)
			.optional();
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<ReleaseTerms> releaseTermsOf(UUID milestoneId) {
		return this.jdbc.sql("SELECT job_id, crew_terms_version_no FROM job.milestone WHERE id = :id AND crew_terms_version_no IS NOT NULL")
			.param("id", milestoneId)
			.query((rs, n) -> {
				UUID jobId = rs.getObject("job_id", UUID.class);
				int no = rs.getInt("crew_terms_version_no");
				Map<UUID, Integer> shares = new LinkedHashMap<>();
				this.jdbc.sql("SELECT party_account_id, percent FROM job.crew_terms_share WHERE job_id = :job AND version_no = :no"
						+ " ORDER BY crew_row_id NULLS FIRST, party_account_id")
					.param("job", jobId)
					.param("no", no)
					.query(share -> {
						shares.put(share.getObject("party_account_id", UUID.class), share.getInt("percent"));
					});
				return new ReleaseTerms(no, Map.copyOf(shares));
			})
			.optional();
	}

	/** The Crew Terms version in force, once J6 brought one into force. */
	Optional<Integer> inForce(UUID jobId) {
		return this.jdbc.sql("SELECT version_no FROM job.crew_terms_version WHERE job_id = :job AND status = 'IN_FORCE'")
			.param("job", jobId)
			.query(Integer.class)
			.optional();
	}

	/**
	 * The Job locked as its Mentor's command needs it, at the Crew version the screen loaded (AD-23).
	 * @throws ApiException {@code NOT_FOUND} for anyone but the Job's Mentor; {@code STALE} for another Crew version
	 */
	Job lockMentorJob(UUID mentorId, UUID jobId, long crewVersion) {
		Job job = lockJob(jobId);
		if (!mentorId.equals(job.mentorId())) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		if (crewVersion(jobId) != crewVersion) {
			throw new ApiException(ErrorCode.STALE);
		}
		return job;
	}

	long crewVersion(UUID jobId) {
		return this.jdbc.sql("SELECT crew_version FROM job.job WHERE id = :id").param("id", jobId).query(Long.class).single();
	}

	/** The Job as it stands, read under the lock its caller already holds. */
	Job current(UUID jobId) {
		return this.jdbc.sql("SELECT " + Jobs.COLUMNS + " FROM job.job WHERE id = :id AND " + Jobs.LIVE)
			.param("id", jobId)
			.query(Jobs::job)
			.single();
	}

	/**
	 * The Job row lock, first in the caller's transaction (AD-4).
	 * @throws ApiException {@code NOT_FOUND} for no such Job
	 */
	Job lockJob(UUID jobId) {
		return this.jdbc.sql("SELECT " + Jobs.COLUMNS + " FROM job.job WHERE id = :id AND " + Jobs.LIVE + " FOR UPDATE")
			.param("id", jobId)
			.query(Jobs::job)
			.optional()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	Map<UUID, PersonCard> cards(List<UUID> studentIds) {
		Map<UUID, PersonCard> cards = new HashMap<>();
		if (studentIds.isEmpty()) {
			return cards;
		}
		this.people.cards(studentIds).forEach(card -> cards.put(card.accountId(), card));
		return cards;
	}

	/** The GitHub usernames of the Job's {@code ACTIVE} members, in the order they joined: what the Customer sees. */
	List<String> activeUsernames(UUID jobId) {
		List<UUID> active = rows(jobId).stream().filter(Row::active).map(Row::studentId).toList();
		Map<UUID, PersonCard> cards = cards(active);
		return active.stream()
			.map(cards::get)
			.filter(card -> card != null && card.githubLogin() != null)
			.map(PersonCard::githubLogin)
			.toList();
	}

	/** The accounts among {@code accountIds} that are locked now (AD-41): {@code identity.lockedAmong}. */
	Set<UUID> lockedAmong(List<UUID> accountIds) {
		return accountIds.isEmpty() ? Set.of() : this.capabilities.lockedAmong(accountIds).keySet();
	}

	private static Row mapRow(ResultSet rs, int n) throws SQLException {
		Timestamp signed = rs.getTimestamp("signed_at");
		Timestamp activated = rs.getTimestamp("activated_at");
		Timestamp window = rs.getTimestamp("window_end");
		return new Row(rs.getObject("id", UUID.class), rs.getObject("job_id", UUID.class),
				rs.getObject("member_account_id", UUID.class), rs.getString("status"),
				signed == null ? null : signed.toInstant(), activated == null ? null : activated.toInstant(),
				window == null ? null : window.toInstant(), rs.getTimestamp("created_at").toInstant(),
				rs.getString("released_reason"), rs.getObject("crew_application_id", UUID.class), rs.getString("left_note"));
	}

}
