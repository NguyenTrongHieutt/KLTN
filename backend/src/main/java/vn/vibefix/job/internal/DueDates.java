package vn.vibefix.job.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.job.JobRules;
import vn.vibefix.job.internal.JobLifecycle.Applying;
import vn.vibefix.job.internal.Jobs.Job;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.timers.DeadlineRequest;
import vn.vibefix.timers.Deadlines;

/**
 * FR-48, FR-68, AD-7, AD-33: a held Milestone's due date. It is due at the end of its due date in
 * Vietnam time; {@link MilestoneDueSoon} runs 2 days before and {@link MilestoneOverdue} at it, both
 * pausable, so a freeze pushes them back by the paused time (FR-68's effective due time). The generation
 * is the due date, so a pair a new due date superseded is {@code OBSOLETE}. Overdue is a flag, not a
 * state: it moves no money, and a handover (M2) or a later due date clears it. No automatic penalty.
 * <p>
 * FR-87, AD-48 (Story 25.1): {@code job.milestone.due_on} is the due date in force. A revision request (M4) or a
 * voided handover (M3) moves it to the rework due date when that is later ({@link #rework}, {@link #reworked}); the
 * confirmed Agreement keeps the date the parties agreed, and no Agreement version is written.
 */
@Component
class DueDates {

	static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

	static final Duration SOON = Duration.ofDays(2);

	static final String DUE_SOON = "job.milestone_due_soon";

	static final String OVERDUE = "job.milestone_overdue";

	/** FR-87: a revision request or a voided handover gave the Milestone a later due date, for the three sides. */
	static final String REWORK_DUE = "job.milestone_rework_due";

	/** The rule value's name on {@code job.job.rules} (FR-61). */
	static final String REWORK_RULE = "REWORK_DAYS";

	/** {@code REWORK_DAYS} for a Job published before the rule existed. */
	static final int DEFAULT_REWORK_DAYS = 5;

	/** FR-63: the Audit Log holds no values, so the cause of a due-date move is the action's last part. */
	static final String AUDIT_MOVED = "job.milestone_due_moved.";

	/** Why a due date moved (FR-87): a revision request (M4), or a voided handover (M3). */
	enum Cause {

		REVISION, VOID

	}

	private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("dd/MM");

	private static final List<String> OPEN = List.of("FUNDED", "REVISION_REQUESTED");

	private record Row(UUID jobId, String state, @Nullable LocalDate dueOn, int number) {
	}

	private final JdbcClient jdbc;

	private final CrewService crews;

	private final Deadlines deadlines;

	private final Notifications notifications;

	private final JobRules rules;

	private final AuditLog auditLog;

	private final Clock clock;

	DueDates(JdbcClient jdbc, CrewService crews, Deadlines deadlines, Notifications notifications, JobRules rules,
			AuditLog auditLog, Clock clock) {
		this.jdbc = jdbc;
		this.crews = crews;
		this.deadlines = deadlines;
		this.notifications = notifications;
		this.rules = rules;
		this.auditLog = auditLog;
		this.clock = clock;
	}

	/** When a Milestone due on {@code dueOn} becomes overdue: the end of that day in Vietnam time. */
	static Instant dueAt(LocalDate dueOn) {
		return dueOn.plusDays(1).atStartOfDay(VIETNAM).toInstant();
	}

	/**
	 * FR-87, AD-48: the due date a Milestone due on {@code dueOn} has once it is sent back for rework at {@code event}:
	 * the later of {@code dueOn} and the event's day in Vietnam time plus {@code reworkDays}. Never earlier than
	 * {@code dueOn}.
	 */
	static LocalDate reworkDue(LocalDate dueOn, Instant event, int reworkDays) {
		LocalDate rework = LocalDate.ofInstant(event, VIETNAM).plusDays(reworkDays);
		return rework.isAfter(dueOn) ? rework : dueOn;
	}

	/** The due date in force a rework at {@code event} would give the Milestone; {@code null} when it has no due date. */
	@Nullable LocalDate reworkDue(UUID jobId, @Nullable LocalDate dueOn, Instant event) {
		return dueOn == null ? null : reworkDue(dueOn, event, this.rules.value(jobId, REWORK_RULE, DEFAULT_REWORK_DAYS));
	}

	/**
	 * The due date in force a Milestone would have if it were sent back for rework now, for the revision dialog
	 * (UX-DR128); empty when it has no due date. The date the dialog shows is the date {@link #rework} writes.
	 */
	@Transactional(readOnly = true)
	Optional<LocalDate> reworkDueOn(UUID milestoneId) {
		Row row = row(milestoneId);
		return row == null ? Optional.empty() : Optional.ofNullable(reworkDue(row.jobId(), row.dueOn(), this.clock.instant()));
	}

	/**
	 * In the {@code apply} of M3 or M4 (FR-87, AD-48): when the rework due date is later than the Milestone's due date,
	 * the transition's own statement writes it and clears the overdue flag, and the facts {@code dueFrom}, {@code dueTo}
	 * and {@code dueCause} say so. Otherwise nothing moves. A Milestone with no due date gets none.
	 */
	void rework(Applying applying, Cause cause) {
		Row row = row(applying.milestoneId());
		LocalDate dueOn = row == null ? null : row.dueOn();
		LocalDate moved = reworkDue(applying.job().id(), dueOn, applying.now());
		if (dueOn == null || moved == null || !moved.isAfter(dueOn)) {
			return;
		}
		applying.set("due_on", moved);
		applying.set("overdue_since", null);
		applying.fact("dueFrom", dueOn.toString());
		applying.fact("dueTo", moved.toString());
		applying.fact("dueCause", cause.name());
	}

	/**
	 * In the {@code afterWrite} of M3 or M4, once {@link #rework} moved the date: the new pair of Deadlines (the earlier
	 * pair ends {@code OBSOLETE} by its generation), the three sides are told, and the move is audited with its cause.
	 */
	void reworked(Applying applying) {
		String cause = applying.factOf("dueCause");
		if (cause == null) {
			return;
		}
		Job job = applying.job();
		UUID milestoneId = applying.milestoneId();
		schedule(job.id(), milestoneId);
		Row row = Objects.requireNonNull(row(milestoneId));
		List<Recipient> everyone = new ArrayList<>(List.of(new Recipient(job.customerId(), Audience.CUSTOMER)));
		everyone.addAll(team(job));
		this.notifications.send(new NotificationRequest(REWORK_DUE, applying.transitionId(), everyone, params(job, row),
				"/cong-viec/" + job.id(), job.id(), null));
		this.auditLog.record(AuditEntry
			.of(applying.actor().audit(), AUDIT_MOVED + cause.toLowerCase(Locale.ROOT), AuditObject.of("job.milestone", milestoneId))
			.forJob(job.id()));
	}

	/** M1, or a held Milestone's new due date: the due-soon reminder (unless already past) and the overdue check. */
	@Transactional
	void schedule(UUID jobId, UUID milestoneId) {
		Row row = row(milestoneId);
		if (row == null || row.dueOn() == null) {
			return;
		}
		Instant due = dueAt(row.dueOn());
		String generation = row.dueOn().toString();
		Instant soon = due.minus(SOON);
		if (soon.isAfter(this.clock.instant())) {
			this.deadlines.schedule(new DeadlineRequest(MilestoneDueSoon.KEY, milestoneId, generation, jobId, soon));
		}
		this.deadlines.schedule(new DeadlineRequest(MilestoneOverdue.KEY, milestoneId, generation, jobId, due));
	}

	/** A Change Request gave the Milestone a later due date: a held one loses its flag and gets a new pair. */
	@Transactional
	void rescheduled(UUID jobId, UUID milestoneId) {
		int held = this.jdbc.sql("""
				UPDATE job.milestone SET overdue_since = NULL
				WHERE id = :id AND job_id = :job AND state IN ('FUNDED', 'REVISION_REQUESTED', 'SUBMITTED')
				""").param("id", milestoneId).param("job", jobId).update();
		if (held > 0) {
			schedule(jobId, milestoneId);
		}
	}

	/**
	 * The due-soon reminder came: the Mentor and the active Crew are told once per due date, while the
	 * Milestone is still Funded or Revision Requested on that date.
	 * @return whether it applied
	 */
	@Transactional
	boolean dueSoon(Job job, UUID milestoneId, String generation, UUID deadlineId) {
		Row row = current(milestoneId, generation);
		if (row == null) {
			return false;
		}
		int marked = this.jdbc.sql("""
				UPDATE job.milestone SET due_soon_sent_for = due_on
				WHERE id = :id AND due_soon_sent_for IS DISTINCT FROM due_on
				""").param("id", milestoneId).update();
		if (marked == 0) {
			return false;
		}
		this.notifications.send(new NotificationRequest(DUE_SOON, deadlineId, team(job), params(job, row),
				"/cong-viec/" + job.id(), job.id(), null));
		return true;
	}

	/**
	 * The effective due time passed with the Milestone still Funded or Revision Requested on that date:
	 * the flag is recorded once, and the Customer, the Mentor, and the active Crew are told.
	 * @return whether it applied
	 */
	@Transactional
	boolean overdue(Job job, UUID milestoneId, String generation, UUID deadlineId) {
		Row row = current(milestoneId, generation);
		if (row == null) {
			return false;
		}
		int flagged = this.jdbc.sql("UPDATE job.milestone SET overdue_since = :now WHERE id = :id AND overdue_since IS NULL")
			.param("id", milestoneId)
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
		if (flagged == 0) {
			return false;
		}
		List<Recipient> everyone = new ArrayList<>(List.of(new Recipient(job.customerId(), Audience.CUSTOMER)));
		everyone.addAll(team(job));
		this.notifications.send(new NotificationRequest(OVERDUE, deadlineId, everyone, params(job, row),
				"/cong-viec/" + job.id(), job.id(), null));
		return true;
	}

	private @Nullable Row current(UUID milestoneId, String generation) {
		Row row = row(milestoneId);
		if (row == null || row.dueOn() == null || !OPEN.contains(row.state()) || !generation.equals(row.dueOn().toString())) {
			return null;
		}
		return row;
	}

	private @Nullable Row row(UUID milestoneId) {
		return this.jdbc.sql("""
				SELECT job_id, state, due_on, n FROM (
				    SELECT id, job_id, state, due_on, row_number() OVER (PARTITION BY job_id ORDER BY position, created_at, id) AS n
				    FROM job.milestone WHERE removed_at IS NULL
				      AND job_id = (SELECT job_id FROM job.milestone WHERE id = :id)) numbered
				WHERE id = :id
				""")
			.param("id", milestoneId)
			.query((rs, n) -> new Row(rs.getObject("job_id", UUID.class), rs.getString("state"),
					rs.getObject("due_on", LocalDate.class), rs.getInt("n")))
			.optional()
			.orElse(null);
	}

	private List<Recipient> team(Job job) {
		List<Recipient> team = new ArrayList<>(List.of(new Recipient(Objects.requireNonNull(job.mentorId()), Audience.MENTOR)));
		this.crews.rows(job.id())
			.stream()
			.filter(row -> "ACTIVE".equals(row.status()))
			.forEach(row -> team.add(new Recipient(row.studentId(), Audience.CREW_ACTIVE)));
		return team;
	}

	private static Map<String, String> params(Job job, Row row) {
		return Map.of("job", job.title(), "n", String.valueOf(row.number()),
				"date", DAY_MONTH.format(Objects.requireNonNull(row.dueOn())));
	}

}
