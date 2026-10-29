package vn.vibefix.job.internal;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.identity.Capabilities;
import vn.vibefix.identity.CustomerRepositories;
import vn.vibefix.identity.PersonSignals;
import vn.vibefix.identity.StudentDirectory;
import vn.vibefix.identity.StudentDirectory.StudentCard;
import vn.vibefix.identity.StudentVerification;
import vn.vibefix.job.Crews;
import vn.vibefix.job.internal.Jobs.Job;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.notification.TodoRequest;
import vn.vibefix.notification.Todos;
import vn.vibefix.product.ProductDirectory;
import vn.vibefix.settings.Rule;
import vn.vibefix.settings.Settings;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.IdGenerator;
import vn.vibefix.team.Teams;

/**
 * The Crew lifecycle (FR-75, AD-30, AD-41 as revised by R-1): the Mentor adds and removes Team
 * Students and saves Crew Terms versions; each Crew member signs the NDA (through {@code matching}) and
 * confirms or declines. Every write takes the Job lock and increments {@code crew_version}; a write that
 * changes the usernames the Customer sees also increments {@code version}. Shares never leave the
 * Mentor's and the member's own views.
 */
@Component
class CrewService implements Crews {

	/** FR-75, AD-30: with {@code features.largeCrews} on, a Crew holds up to this many rows that are not released. */
	static final int LARGE_CREW_LIMIT = 5;

	static final String PICK_TODO = "job.pick_crew";

	static final String CONFIRM_TODO = "job.confirm_crew_terms";

	static final String ASSIGNED = "job.crew_assigned";

	static final String MEMBER_CONFIRMED = "job.crew_member_confirmed";

	static final String MEMBER_DECLINED = "job.crew_member_declined";

	static final String MEMBER_REMOVED = "job.crew_member_removed";

	static final String TERMS_CHANGED = "job.crew_terms_changed";

	static final String CREW_CONFIRMED = "job.crew_confirmed";

	static final String RECONFIRM_NEEDED = "job.crew_reconfirm_needed";

	/** One row as {@code job} keeps it. */
	record Row(UUID id, UUID jobId, UUID studentId, String status, @Nullable Instant signedAt,
			@Nullable Instant activatedAt, @Nullable Instant windowEnd, Instant createdAt) {

		boolean released() {
			return "RELEASED".equals(this.status);
		}

	}

	/** One Crew Terms version with its shares and confirmations. */
	record TermsVersion(int versionNo, String status, int capPercent, Instant createdAt, Map<UUID, Integer> shares,
			Set<UUID> confirmedRows) {
	}

	private static final String ROW_COLUMNS = """
			id, job_id, member_account_id, status, signed_at, activated_at, window_end, created_at
			""";

	private final JdbcClient jdbc;

	private final Teams teams;

	private final StudentVerification verification;

	private final StudentDirectory students;

	private final PersonSignals signals;

	private final CustomerRepositories repositories;

	private final ProductDirectory products;

	private final Settings settings;

	private final Notifications notifications;

	private final Todos todos;

	private final CrewAlerts alerts;

	private final AuditLog auditLog;

	private final IdGenerator ids;

	private final Clock clock;

	private final boolean largeCrews;

	private final ObjectProvider<CrewChanges> changes;

	private final Capabilities capabilities;

	CrewService(JdbcClient jdbc, Teams teams, StudentVerification verification, StudentDirectory students,
			PersonSignals signals, CustomerRepositories repositories, ProductDirectory products, Settings settings,
			Notifications notifications, Todos todos, CrewAlerts alerts, AuditLog auditLog, IdGenerator ids, Clock clock,
			@Value("${vibefix.features.large-crews:false}") boolean largeCrews, ObjectProvider<CrewChanges> changes,
			Capabilities capabilities) {
		this.jdbc = jdbc;
		this.teams = teams;
		this.verification = verification;
		this.students = students;
		this.signals = signals;
		this.repositories = repositories;
		this.products = products;
		this.settings = settings;
		this.notifications = notifications;
		this.todos = todos;
		this.alerts = alerts;
		this.auditLog = auditLog;
		this.ids = ids;
		this.clock = clock;
		this.largeCrews = largeCrews;
		this.changes = changes;
		this.capabilities = capabilities;
	}

	/** J4 (FR-75): the Mentor's "Chọn nhóm thực hiện" to-do, due at the Agreement deadline. */
	void start(Job job, Instant agreementDueAt) {
		this.todos.open(new TodoRequest(PICK_TODO, Objects.requireNonNull(job.mentorId()), Audience.MENTOR, job.id(),
				Map.of("job", job.title()), Agreements.agreementLink(job.id()), agreementDueAt, job.id()));
	}

	// ---- The Mentor's commands -------------------------------------------------------------

	/**
	 * Adds a Team Student as {@code ASSIGNED} (FR-75), checked in order under the Job lock: one role per Job
	 * (AD-43), the Crew lock, the Crew limit, verification, a running Crew, a shared person-link signal, a
	 * contribution to the Source Repository, then the Team through {@code team.reserveForCrew}.
	 * @throws ApiException {@code GUARD_FAILED ROLE_TAKEN} for the Job's Customer or Mentor
	 */
	@Transactional
	Row add(UUID mentorId, UUID jobId, UUID studentId, long crewVersion) {
		Job job = lockMentorJob(mentorId, jobId, crewVersion);
		requireNoOtherRole(job, studentId);
		requireOpenCrew(job);
		List<Row> rows = rows(jobId);
		long running = rows.stream().filter(row -> !row.released()).count();
		if (running >= (this.largeCrews ? LARGE_CREW_LIMIT : 1)) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.CREW_LIMIT);
		}
		Row row = addRow(job, mentorId, studentId);
		bump(jobId, true);
		refreshPickTodo(job);
		return row;
	}

	/**
	 * A new {@code ASSIGNED} row for a Team Student (FR-75), checked in order under the Job lock its caller
	 * holds: one role per Job (AD-43), verification, a running Crew, a shared person-link signal, a contribution
	 * to the Source Repository, then the Team through {@code team.reserveForCrew}. The Student is told and gets
	 * the confirmation to-do.
	 * @throws ApiException {@code GUARD_FAILED ROLE_TAKEN} for the Job's Customer or Mentor
	 */
	Row addRow(Job job, UUID mentorId, UUID studentId) {
		UUID jobId = job.id();
		requireNoOtherRole(job, studentId);
		if (!this.verification.isVerified(studentId)) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.STUDENT_NOT_VERIFIED);
		}
		if (runningCrewOf(studentId).isPresent()) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.STUDENT_ON_CREW);
		}
		if (this.signals.sharesPersonSignal(studentId, mentorId) || this.signals.sharesPersonSignal(studentId, job.customerId())) {
			this.alerts.conflictRefused();
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.CONFLICT_OF_INTEREST);
		}
		if (contributor(job, studentId)) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.APPLICANT_IS_CONTRIBUTOR);
		}
		UUID rowId = this.ids.newId();
		Instant now = this.clock.instant();
		Instant signed = signedAt(jobId, studentId);
		this.jdbc.sql("""
				INSERT INTO job.crew_row (id, job_id, member_account_id, status, signed_at, created_at)
				VALUES (:id, :job, :student, 'ASSIGNED', :signed, :now)
				""")
			.param("id", rowId)
			.param("job", jobId)
			.param("student", studentId)
			.param("signed", signed == null ? null : Timestamp.from(signed))
			.param("now", Timestamp.from(now))
			.update();
		this.teams.reserveForCrew(rowId, mentorId, studentId, jobId);
		this.auditLog.record(AuditEntry.of(AuditActor.user(mentorId), "job.crew_member_added", AuditObject.of("job.crew_row", rowId))
			.forJob(jobId));
		this.notifications.send(new NotificationRequest(ASSIGNED, rowId, List.of(new Recipient(studentId, Audience.CREW_ASSIGNED)),
				Map.of("job", job.title()), "/cong-viec?vai-tro=tham-gia", jobId, null));
		this.todos.open(new TodoRequest(CONFIRM_TODO, studentId, Audience.CREW_ASSIGNED, rowId, Map.of("job", job.title()),
				"/cong-viec?vai-tro=tham-gia", agreementDue(jobId), jobId));
		return row(rowId).orElseThrow();
	}

	/** Removes a member before J6 (FR-75, AD-41): the row is released and the Student is free again. */
	@Transactional
	void remove(UUID mentorId, UUID jobId, UUID rowId, long crewVersion) {
		Job job = lockMentorJob(mentorId, jobId, crewVersion);
		requireOpenCrew(job);
		Row row = row(rowId).filter(found -> found.jobId().equals(jobId) && !found.released())
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		release(row, "REMOVED");
		bump(jobId, true);
		this.auditLog.record(AuditEntry.of(AuditActor.user(mentorId), "job.crew_member_removed", AuditObject.of("job.crew_row", rowId))
			.forJob(jobId));
		this.notifications.send(new NotificationRequest(MEMBER_REMOVED, rowId,
				List.of(new Recipient(row.studentId(), Audience.SUBJECT)), Map.of("job", job.title()), "/cong-viec?vai-tro=tham-gia", null, rowId));
		refreshPickTodo(job);
	}

	/**
	 * "Xác nhận lại nhóm thực hiện" (FR-17, AD-41 as revised by R-1 H1): after a lock owed a
	 * re-confirmation, the Crew condition must hold again; the lock already released the locked member's row
	 * (Story 20.4), so nobody is removed first. Clearing the mark locks the Crew again.
	 * @throws ApiException {@code STALE} without an owed mark; {@code GUARD_FAILED CREW_NOT_CONFIRMED}
	 */
	@Transactional
	void reconfirm(UUID mentorId, UUID jobId, long crewVersion) {
		Job job = lockMentorJob(mentorId, jobId, crewVersion);
		if (job.state() != JobState.AGREEMENT || !reconfirmOwed(jobId)) {
			throw new ApiException(ErrorCode.STALE);
		}
		if (!crewConfirmed(jobId)) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.CREW_NOT_CONFIRMED);
		}
		this.jdbc.sql("UPDATE job.job SET crew_reconfirm_owed_at = NULL WHERE id = :id").param("id", jobId).update();
		bump(jobId, false);
		this.auditLog.record(AuditEntry.of(AuditActor.user(mentorId), "job.crew_reconfirmed", AuditObject.of("job.job", jobId))
			.forJob(jobId));
		this.todos.closeAll(PICK_TODO, jobId);
	}

	/**
	 * A lock counts as leaving (FR-59, FR-82, AD-19, AD-41): each unreleased row of the account on the Job is
	 * released, an {@code ACTIVE} one as {@code LEFT} and an {@code ASSIGNED} one, a pick not yet signed, as
	 * {@code DECLINED}; a window it had ends at {@code lockedAt}, the lock's own time, never the listener's. In
	 * Agreement before its confirmation the Mentor's "Chọn nhóm thực hiện" to-do opens again while the Crew condition
	 * no longer holds; after the confirmation the Crew owes a re-confirmation: the Crew lock lifts until the Mentor
	 * re-confirms, and the Mentor is told (R-1 H1). The caller holds the Job lock. Safe to replay: a released row
	 * changes nothing.
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
			boolean active = "ACTIVE".equals(row.status());
			release(row, active ? "LEFT" : "DECLINED", lockedAt);
			left |= active;
		}
		bump(job.id(), left);
		if (job.state() == JobState.AGREEMENT && job.mentorId() != null) {
			if (job.agreementVersionNo() == null) {
				refreshPickTodo(job);
			}
			else {
				this.jdbc.sql("UPDATE job.job SET crew_reconfirm_owed_at = coalesce(crew_reconfirm_owed_at, :now) WHERE id = :id")
					.param("id", job.id())
					.param("now", Timestamp.from(this.clock.instant()))
					.update();
				this.notifications.send(new NotificationRequest(RECONFIRM_NEEDED,
						UUID.nameUUIDFromBytes((rows.get(0).id() + ":locked").getBytes(StandardCharsets.UTF_8)),
						List.of(new Recipient(job.mentorId(), Audience.MENTOR)), Map.of("job", job.title()),
						Agreements.agreementLink(job.id()), job.id(), null));
				this.todos.open(new TodoRequest(PICK_TODO, job.mentorId(), Audience.MENTOR, job.id(), Map.of("job", job.title()),
						Agreements.agreementLink(job.id()), agreementDue(job.id()), job.id()));
			}
		}
		return rows;
	}

	/**
	 * The counted {@code ACTIVE} rows (AD-41, Story 20.4): an {@code ACTIVE} row counts only while its account is
	 * unlocked, read through {@code identity.lockedAmong}, because a lock commits before its listener releases the
	 * row. J6's Crew condition, a Crew change's "at least one member", and the empty-Crew check read it under the
	 * Job lock their caller holds.
	 */
	List<Row> countedActive(UUID jobId) {
		List<Row> active = rows(jobId).stream().filter(row -> "ACTIVE".equals(row.status())).toList();
		if (active.isEmpty()) {
			return active;
		}
		Map<UUID, Instant> locked = this.capabilities.lockedAmong(active.stream().map(Row::studentId).toList());
		return active.stream().filter(row -> !locked.containsKey(row.studentId())).toList();
	}

	/** The accounts among {@code accountIds} that are locked now (AD-41): {@code identity.lockedAmong}. */
	Set<UUID> lockedAmong(List<UUID> accountIds) {
		return accountIds.isEmpty() ? Set.of() : this.capabilities.lockedAmong(accountIds).keySet();
	}

	/** Whether a lock between the Agreement confirmation and J6 left the Crew owing a re-confirmation. */
	boolean reconfirmOwed(UUID jobId) {
		return this.jdbc.sql("SELECT crew_reconfirm_owed_at IS NOT NULL FROM job.job WHERE id = :id")
			.param("id", jobId)
			.query(Boolean.class)
			.single();
	}

	/**
	 * Saves a new Crew Terms version (FR-75, AD-29, AD-41): whole percentages summing to 100, each Crew
	 * member's above 0, the Mentor's within the cap read now, and exactly the rows that are not released
	 * as parties; older pending versions are voided and every {@code ACTIVE} row must confirm again.
	 * @throws ApiException {@code VALIDATION SHARES_INVALID}
	 */
	@Transactional
	TermsVersion saveTerms(UUID mentorId, UUID jobId, long crewVersion, Map<UUID, Integer> shares) {
		Job job = lockMentorJob(mentorId, jobId, crewVersion);
		requireOpenCrew(job);
		int cap = this.settings.value(Rule.MENTOR_SHARE_CAP_PERCENT);
		List<Row> running = rows(jobId).stream().filter(row -> !row.released()).toList();
		Set<UUID> crew = running.stream().map(Row::studentId).collect(Collectors.toSet());
		Set<UUID> parties = new HashSet<>(shares.keySet());
		parties.remove(mentorId);
		Integer mentorShare = shares.get(mentorId);
		boolean valid = mentorShare != null && mentorShare >= 0 && mentorShare <= cap && parties.equals(crew)
				&& !crew.isEmpty() && parties.stream().allMatch(party -> shares.get(party) != null && shares.get(party) > 0)
				&& shares.values().stream().mapToInt(Integer::intValue).sum() == 100;
		if (!valid) {
			throw new ApiException(ErrorCode.VALIDATION, JobReason.SHARES_INVALID, Map.of("cap", cap));
		}
		Map<UUID, UUID> rowOf = running.stream().collect(Collectors.toMap(Row::studentId, Row::id));
		int next = writeVersion(jobId, mentorId, cap, shares, rowOf);
		// R-1 M1: before J6 an ACTIVE row has confirmed the latest version, so a new version resets it.
		this.jdbc.sql("UPDATE job.crew_row SET status = 'ASSIGNED' WHERE job_id = :job AND status = 'ACTIVE'")
			.param("job", jobId)
			.update();
		bump(jobId, false);
		this.auditLog.record(AuditEntry.of(AuditActor.user(mentorId), "job.crew_terms_saved", AuditObject.of("job.job", jobId))
			.forJob(jobId));
		for (Row row : running) {
			this.notifications.send(new NotificationRequest(TERMS_CHANGED, UUID.nameUUIDFromBytes((row.id() + ":" + next).getBytes()),
					List.of(new Recipient(row.studentId(), Audience.CREW_ASSIGNED)), Map.of("job", job.title()), "/cong-viec?vai-tro=tham-gia",
					jobId, null));
			this.todos.closeAll(CONFIRM_TODO, row.id());
			this.todos.open(new TodoRequest(CONFIRM_TODO, row.studentId(), Audience.CREW_ASSIGNED, row.id(),
					Map.of("job", job.title()), "/cong-viec?vai-tro=tham-gia", agreementDue(jobId), jobId));
		}
		refreshPickTodo(job);
		return latest(jobId).orElseThrow();
	}

	/**
	 * Writes the next Crew Terms version as {@code PENDING} with one share per party and voids every older
	 * pending one (AD-29, AD-41).
	 * @param rowOf each Student party's Crew row
	 * @return the new version's number
	 */
	int writeVersion(UUID jobId, UUID authorId, int cap, Map<UUID, Integer> shares, Map<UUID, UUID> rowOf) {
		int next = this.jdbc.sql("SELECT coalesce(max(version_no), 0) + 1 FROM job.crew_terms_version WHERE job_id = :job")
			.param("job", jobId)
			.query(Integer.class)
			.single();
		Instant now = this.clock.instant();
		this.jdbc.sql("UPDATE job.crew_terms_version SET status = 'VOIDED' WHERE job_id = :job AND status = 'PENDING'")
			.param("job", jobId)
			.update();
		this.jdbc.sql("""
				INSERT INTO job.crew_terms_version (job_id, version_no, status, cap_percent, author_account_id, created_at)
				VALUES (:job, :no, 'PENDING', :cap, :author, :now)
				""")
			.param("job", jobId)
			.param("no", next)
			.param("cap", cap)
			.param("author", authorId)
			.param("now", Timestamp.from(now))
			.update();
		for (Map.Entry<UUID, Integer> share : shares.entrySet()) {
			this.jdbc.sql("""
					INSERT INTO job.crew_terms_share (job_id, version_no, party_account_id, crew_row_id, percent)
					VALUES (:job, :no, :party, :row, :percent)
					""")
				.param("job", jobId)
				.param("no", next)
				.param("party", share.getKey())
				.param("row", rowOf.get(share.getKey()))
				.param("percent", share.getValue())
				.update();
		}
		return next;
	}

	// ---- The Student's commands (through matching) ------------------------------------------

	@Override
	@Transactional
	public void confirm(UUID jobId, UUID studentId, int versionNo, Instant signedAt) {
		Job job = lockJob(jobId);
		Row row = openRow(jobId, studentId);
		if (job.state().compareTo(JobState.AGREEMENT) > 0) {
			this.changes.getObject().confirm(job, row, versionNo, signedAt);
			return;
		}
		requireOpenCrew(job);
		TermsVersion latest = latest(jobId).orElseThrow(() -> new ApiException(ErrorCode.STALE));
		if (latest.versionNo() != versionNo) {
			throw new ApiException(ErrorCode.STALE);
		}
		if (!latest.shares().containsKey(studentId)) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.CREW_NOT_CONFIRMED);
		}
		boolean before = crewConfirmed(jobId);
		Instant now = this.clock.instant();
		this.jdbc.sql("""
				INSERT INTO job.crew_terms_confirmation (job_id, version_no, crew_row_id, confirmed_at)
				VALUES (:job, :no, :row, :now) ON CONFLICT DO NOTHING
				""")
			.param("job", jobId)
			.param("no", versionNo)
			.param("row", row.id())
			.param("now", Timestamp.from(now))
			.update();
		this.jdbc.sql("""
				UPDATE job.crew_row SET status = 'ACTIVE', signed_at = coalesce(signed_at, :signed),
				    activated_at = coalesce(activated_at, :now)
				WHERE id = :id
				""")
			.param("id", row.id())
			.param("signed", Timestamp.from(signedAt))
			.param("now", Timestamp.from(now))
			.update();
		bump(jobId, false);
		this.auditLog.record(AuditEntry.of(AuditActor.user(studentId), "job.crew_terms_confirmed", AuditObject.of("job.crew_row", row.id()))
			.forJob(jobId));
		this.todos.closeAll(CONFIRM_TODO, row.id());
		String name = studentName(studentId);
		this.notifications.send(new NotificationRequest(MEMBER_CONFIRMED, UUID.nameUUIDFromBytes((row.id() + ":c" + versionNo).getBytes()),
				List.of(new Recipient(Objects.requireNonNull(job.mentorId()), Audience.MENTOR)),
				Map.of("job", job.title(), "student", name), Agreements.agreementLink(jobId), jobId, null));
		if (!before && crewConfirmed(jobId)) {
			this.notifications.send(new NotificationRequest(CREW_CONFIRMED, UUID.nameUUIDFromBytes((jobId + ":crew" + versionNo).getBytes()),
					List.of(new Recipient(job.mentorId(), Audience.MENTOR)), Map.of("job", job.title()),
					Agreements.agreementLink(jobId), jobId, null));
		}
		refreshPickTodo(job);
	}

	@Override
	@Transactional
	public void decline(UUID jobId, UUID studentId, int versionNo) {
		Job job = lockJob(jobId);
		Row row = openRow(jobId, studentId);
		if (job.state().compareTo(JobState.AGREEMENT) > 0) {
			this.changes.getObject().decline(job, row, versionNo);
			return;
		}
		requireOpenCrew(job);
		int latest = latest(jobId).map(TermsVersion::versionNo).orElse(0);
		if (latest != versionNo) {
			throw new ApiException(ErrorCode.STALE);
		}
		release(row, "DECLINED");
		bump(jobId, true);
		this.auditLog.record(AuditEntry.of(AuditActor.user(studentId), "job.crew_member_declined", AuditObject.of("job.crew_row", row.id()))
			.forJob(jobId));
		this.notifications.send(new NotificationRequest(MEMBER_DECLINED, row.id(),
				List.of(new Recipient(Objects.requireNonNull(job.mentorId()), Audience.MENTOR)),
				Map.of("job", job.title(), "student", studentName(studentId)), Agreements.agreementLink(jobId), jobId, null));
		refreshPickTodo(job);
	}

	// ---- Reads ------------------------------------------------------------------------------

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
			return "ACTIVE".equals(row.status()) ? new CrewRelationship(Relationship.ACTIVE, row.signedAt(), null)
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
				case ACTIVE -> "ACTIVE".equals(row.status());
				case EVER_ACTIVE -> row.activatedAt() != null;
			})
			.map(row -> new CrewMember(row.id(), row.studentId(), row.status(), row.signedAt(), row.activatedAt(), row.windowEnd()))
			.toList();
	}

	/**
	 * {@inheritDoc} The bounds are inclusive: a member active when a Milestone was funded or accepted shares its work;
	 * a Settled Milestone's work ends at its acceptance or, without one, its settlement.
	 */
	@Override
	@Transactional(readOnly = true)
	public List<UUID> activeDuringAcceptedWork(UUID jobId) {
		return this.jdbc.sql("""
				SELECT r.member_account_id FROM job.crew_row r
				WHERE r.job_id = :job AND r.activated_at IS NOT NULL
				  AND EXISTS (SELECT 1 FROM job.milestone m WHERE m.job_id = r.job_id AND m.removed_at IS NULL
				      AND m.state IN ('ACCEPTED', 'SETTLED') AND m.funded_at IS NOT NULL
				      AND r.activated_at <= coalesce(m.accepted_at, m.settled_at)
				      AND (r.window_end IS NULL OR r.window_end >= m.funded_at))
				GROUP BY r.member_account_id ORDER BY min(r.created_at), r.member_account_id
				""").param("job", jobId).query(UUID.class).list();
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

	@Override
	@Transactional(readOnly = true)
	public Optional<Assignment> assignment(UUID jobId, UUID studentId) {
		return this.jdbc.sql("""
				SELECT r.id, r.signed_at, j.job_types FROM job.crew_row r JOIN job.job j ON j.id = r.job_id
				WHERE r.job_id = :job AND r.member_account_id = :id AND r.status IN ('ASSIGNED', 'ACTIVE')
				""").param("job", jobId).param("id", studentId).query((rs, n) -> {
			String[] types = (String[]) rs.getArray("job_types").getArray();
			boolean code = Arrays.stream(types).map(JobType::valueOf).anyMatch(JobType::code);
			return new Assignment(jobId, rs.getObject("id", UUID.class), rs.getTimestamp("signed_at") != null, code,
					latest(jobId).map(TermsVersion::versionNo).orElse(0));
		}).optional();
	}

	/** The Students' rows on the Job, oldest first. */
	List<Row> rows(UUID jobId) {
		return this.jdbc.sql("SELECT " + ROW_COLUMNS + " FROM job.crew_row WHERE job_id = :job ORDER BY created_at, id")
			.param("job", jobId)
			.query(CrewService::mapRow)
			.list();
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

	/**
	 * J6 (FR-17, AD-41): the latest pending Crew Terms version comes into force and the older ones are
	 * superseded, so from J6 on exactly one version is {@code IN_FORCE}. Runs in J6's apply.
	 * @return the version now in force
	 */
	int bringIntoForce(UUID jobId) {
		int latest = this.jdbc.sql("SELECT max(version_no) FROM job.crew_terms_version WHERE job_id = :job AND status = 'PENDING'")
			.param("job", jobId)
			.query(Integer.class)
			.optional()
			.orElseThrow(() -> new IllegalStateException("J6 found no pending Crew Terms version on " + jobId));
		this.jdbc.sql("""
				UPDATE job.crew_terms_version SET status = CASE WHEN version_no = :latest THEN 'IN_FORCE' ELSE 'SUPERSEDED' END
				WHERE job_id = :job AND status IN ('PENDING', 'IN_FORCE')
				""").param("job", jobId).param("latest", latest).update();
		return latest;
	}

	/** The Crew Terms version in force, once J6 brought one into force. */
	Optional<Integer> inForce(UUID jobId) {
		return this.jdbc.sql("SELECT version_no FROM job.crew_terms_version WHERE job_id = :job AND status = 'IN_FORCE'")
			.param("job", jobId)
			.query(Integer.class)
			.optional();
	}

	/** J6's FR-75 guard: the Crew condition holds and no lock left a re-confirmation owed (R-1 H1). */
	boolean crewReady(UUID jobId) {
		return crewConfirmed(jobId) && !reconfirmOwed(jobId);
	}

	/** The latest Crew Terms version that is not voided, with its shares and confirmations. */
	Optional<TermsVersion> latest(UUID jobId) {
		return version(jobId, "status <> 'VOIDED'");
	}

	/** The Crew Terms version in force since J6, with its shares and confirmations. */
	Optional<TermsVersion> inForceTerms(UUID jobId) {
		return version(jobId, "status = 'IN_FORCE'");
	}

	private Optional<TermsVersion> version(UUID jobId, String condition) {
		return this.jdbc.sql("SELECT version_no, status, cap_percent, created_at FROM job.crew_terms_version WHERE job_id = :job AND "
				+ condition + " ORDER BY version_no DESC LIMIT 1").param("job", jobId).query((rs, n) -> {
			int no = rs.getInt("version_no");
			Map<UUID, Integer> shares = new LinkedHashMap<>();
			this.jdbc.sql("SELECT party_account_id, percent FROM job.crew_terms_share WHERE job_id = :job AND version_no = :no"
					+ " ORDER BY crew_row_id NULLS FIRST, party_account_id")
				.param("job", jobId)
				.param("no", no)
				.query(share -> {
					shares.put(share.getObject("party_account_id", UUID.class), share.getInt("percent"));
				});
			Set<UUID> confirmed = new HashSet<>(this.jdbc.sql(
					"SELECT crew_row_id FROM job.crew_terms_confirmation WHERE job_id = :job AND version_no = :no")
				.param("job", jobId)
				.param("no", no)
				.query(UUID.class)
				.list());
			return new TermsVersion(no, rs.getString("status"), rs.getInt("cap_percent"),
					rs.getTimestamp("created_at").toInstant(), shares, confirmed);
		}).optional();
	}

	/**
	 * FR-17's Crew condition (AD-41): every row that is not released signed the NDA and confirmed the
	 * latest version, its parties are exactly those rows, and at least one {@code ACTIVE} row counts: its
	 * account is unlocked ({@link #countedActive}, read under the Job lock J6 holds), since a lock commits before
	 * its listener releases the row.
	 */
	boolean crewConfirmed(UUID jobId) {
		List<Row> running = rows(jobId).stream().filter(row -> !row.released()).toList();
		Optional<TermsVersion> latest = latest(jobId);
		if (running.isEmpty() || latest.isEmpty()) {
			return false;
		}
		TermsVersion version = latest.get();
		Set<UUID> parties = new HashSet<>(version.shares().keySet());
		Set<UUID> crew = running.stream().map(Row::studentId).collect(Collectors.toSet());
		if (!parties.containsAll(crew) || parties.size() != crew.size() + 1) {
			return false;
		}
		boolean everyone = running.stream()
			.allMatch(row -> row.signedAt() != null && version.confirmedRows().contains(row.id()));
		return everyone && !countedActive(jobId).isEmpty();
	}

	Optional<Row> row(UUID rowId) {
		return this.jdbc.sql("SELECT " + ROW_COLUMNS + " FROM job.crew_row WHERE id = :id")
			.param("id", rowId)
			.query(CrewService::mapRow)
			.optional();
	}

	Job lockMentorJob(UUID mentorId, UUID jobId, long crewVersion) {
		Job job = lockJob(jobId);
		if (!mentorId.equals(job.mentorId())) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		long current = this.jdbc.sql("SELECT crew_version FROM job.job WHERE id = :id").param("id", jobId).query(Long.class).single();
		if (current != crewVersion) {
			throw new ApiException(ErrorCode.STALE);
		}
		return job;
	}

	long crewVersion(UUID jobId) {
		return this.jdbc.sql("SELECT crew_version FROM job.job WHERE id = :id").param("id", jobId).query(Long.class).single();
	}

	/** The Job as it stands, read under the lock its caller already holds. */
	Job current(UUID jobId) {
		return this.jdbc.sql("SELECT " + Jobs.COLUMNS + " FROM job.job WHERE id = :id")
			.param("id", jobId)
			.query(Jobs::job)
			.single();
	}

	private Job lockJob(UUID jobId) {
		return this.jdbc.sql("SELECT " + Jobs.COLUMNS + " FROM job.job WHERE id = :id FOR UPDATE")
			.param("id", jobId)
			.query(Jobs::job)
			.optional()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	/** Crew writes run in Agreement until the Mentor's confirmation (Story 4.4), then only after a lock. */
	private void requireOpenCrew(Job job) {
		if (job.state() != JobState.AGREEMENT) {
			throw job.state().compareTo(JobState.AGREEMENT) > 0
					? new ApiException(ErrorCode.GUARD_FAILED, JobReason.CREW_CHANGE_REQUIRED) : new ApiException(ErrorCode.STALE);
		}
		Boolean locked = this.jdbc.sql("""
				SELECT agreement_version_no IS NOT NULL AND crew_reconfirm_owed_at IS NULL FROM job.job WHERE id = :id
				""").param("id", job.id()).query(Boolean.class).single();
		if (locked) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.CREW_LOCKED);
		}
	}

	private Row openRow(UUID jobId, UUID studentId) {
		return this.jdbc.sql("SELECT " + ROW_COLUMNS
				+ " FROM job.crew_row WHERE job_id = :job AND member_account_id = :id AND status IN ('ASSIGNED', 'ACTIVE')")
			.param("job", jobId)
			.param("id", studentId)
			.query(CrewService::mapRow)
			.optional()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	/**
	 * J9 and J10 (FR-75, AD-41): every Crew row of the Job not yet released is released as
	 * {@code JOB_ENDED}, its window closed and its Student freed in the Team.
	 */
	void releaseForJobEnd(UUID jobId) {
		this.jdbc.sql("SELECT " + ROW_COLUMNS + " FROM job.crew_row WHERE job_id = :job AND status <> 'RELEASED' ORDER BY id")
			.param("job", jobId)
			.query(CrewService::mapRow)
			.list()
			.forEach(row -> release(row, "JOB_ENDED"));
	}

	/** Releases the row now; a window it had ends now. */
	void release(Row row, String reason) {
		release(row, reason, this.clock.instant());
	}

	/**
	 * Releases the row (AD-41) with {@code reason}: {@code DECLINED}, {@code REMOVED}, {@code JOB_ENDED}, or
	 * {@code LEFT}; it is released now, and a window it had ends at {@code windowEnd}, which a lock sets to its own
	 * time (Story 20.4), never before the row was activated (a row activated after the lock but before its listener
	 * ran ends where it began).
	 */
	void release(Row row, String reason, Instant windowEnd) {
		this.jdbc.sql("""
				UPDATE job.crew_row SET status = 'RELEASED', released_at = :now, released_reason = :reason,
				    window_end = CASE WHEN activated_at IS NOT NULL AND window_end IS NULL
				        THEN greatest(CAST(:end AS TIMESTAMPTZ), activated_at) ELSE window_end END
				WHERE id = :id
				""")
			.param("id", row.id())
			.param("reason", reason)
			.param("now", Timestamp.from(this.clock.instant()))
			.param("end", Timestamp.from(windowEnd))
			.update();
		this.teams.releaseCrew(row.id());
		this.todos.closeAll(CONFIRM_TODO, row.id());
	}

	void closeConfirmTodo(UUID rowId) {
		this.todos.closeAll(CONFIRM_TODO, rowId);
	}

	/** Every Crew write increments {@code crew_version}; one the Customer sees increments {@code version} too. */
	void bump(UUID jobId, boolean usernamesChanged) {
		this.jdbc.sql("UPDATE job.job SET crew_version = crew_version + 1, version = version + :bump, updated_at = :now WHERE id = :id")
			.param("id", jobId)
			.param("bump", usernamesChanged ? 1 : 0)
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
	}

	/** "Chọn nhóm thực hiện" stays open until FR-17's Crew condition holds (UX-DR15). */
	private void refreshPickTodo(Job job) {
		this.todos.closeAll(PICK_TODO, job.id());
		if (!crewConfirmed(job.id()) && job.mentorId() != null) {
			this.todos.open(new TodoRequest(PICK_TODO, job.mentorId(), Audience.MENTOR, job.id(), Map.of("job", job.title()),
					Agreements.agreementLink(job.id()), agreementDue(job.id()), job.id()));
		}
	}

	private @Nullable Instant agreementDue(UUID jobId) {
		return this.jdbc.sql("SELECT agreement_due_at FROM job.job WHERE id = :id")
			.param("id", jobId)
			.query(Timestamp.class)
			.optional()
			.map(Timestamp::toInstant)
			.orElse(null);
	}

	/** The time the Student signed this Job's NDA through an earlier row (R-1 M4), if any. */
	private @Nullable Instant signedAt(UUID jobId, UUID studentId) {
		return this.jdbc.sql("""
				SELECT min(signed_at) FROM job.crew_row WHERE job_id = :job AND member_account_id = :id AND signed_at IS NOT NULL
				""").param("job", jobId).param("id", studentId).query(Timestamp.class).optional().map(Timestamp::toInstant).orElse(null);
	}

	/** Whether the Student contributed to the Source Repository; GitHub not answering refuses, failing closed. */
	private boolean contributor(Job job, UUID studentId) {
		String[] repository = this.products.find(job.productId())
			.orElseThrow(() -> new ApiException(ErrorCode.GUARD_FAILED, JobReason.CONTRIBUTORS_UNKNOWN))
			.repositoryFullName()
			.split("/", 2);
		try {
			return this.repositories.hasContributor(job.customerId(), repository[0], repository[1], studentId);
		}
		catch (ApiException unanswered) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.CONTRIBUTORS_UNKNOWN);
		}
		catch (RuntimeException unanswered) {
			// GitHub did not answer (its own exception type belongs to the github module).
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.CONTRIBUTORS_UNKNOWN);
		}
	}

	String studentName(UUID studentId) {
		return this.students.cards(List.of(studentId))
			.stream()
			.findFirst()
			.map(StudentCard::shownName)
			.orElse("");
	}

	Map<UUID, StudentCard> cards(List<UUID> studentIds) {
		Map<UUID, StudentCard> cards = new HashMap<>();
		this.students.cards(studentIds).forEach(card -> cards.put(card.accountId(), card));
		return cards;
	}

	/** Other Jobs' titles for the Students on a running Crew: the panel's "Đang làm {Job}". */
	Map<UUID, String> runningElsewhere(UUID jobId, List<UUID> studentIds) {
		Map<UUID, String> titles = new HashMap<>();
		if (studentIds.isEmpty()) {
			return titles;
		}
		this.jdbc.sql("""
				SELECT r.member_account_id, j.title FROM job.crew_row r JOIN job.job j ON j.id = r.job_id
				WHERE r.member_account_id IN (:ids) AND r.status <> 'RELEASED' AND r.job_id <> :job
				""").param("ids", studentIds).param("job", jobId).query(rs -> {
			titles.put(rs.getObject("member_account_id", UUID.class), rs.getString("title"));
		});
		return titles;
	}

	List<UUID> openJobsOf(UUID studentId) {
		return this.jdbc.sql("""
				SELECT job_id FROM job.crew_row WHERE member_account_id = :id AND status IN ('ASSIGNED', 'ACTIVE')
				ORDER BY created_at DESC
				""").param("id", studentId).query(UUID.class).list();
	}

	private static Row mapRow(ResultSet rs, int n) throws SQLException {
		Timestamp signed = rs.getTimestamp("signed_at");
		Timestamp activated = rs.getTimestamp("activated_at");
		Timestamp window = rs.getTimestamp("window_end");
		return new Row(rs.getObject("id", UUID.class), rs.getObject("job_id", UUID.class),
				rs.getObject("member_account_id", UUID.class), rs.getString("status"),
				signed == null ? null : signed.toInstant(), activated == null ? null : activated.toInstant(),
				window == null ? null : window.toInstant(), rs.getTimestamp("created_at").toInstant());
	}

}
