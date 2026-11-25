package vn.vibefix.job.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
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
import vn.vibefix.job.AgreementCriteria;
import vn.vibefix.job.AgreementCriteria.CriteriaInForce;
import vn.vibefix.job.AgreementCriteria.MilestoneInForce;
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
 * voided handover (M3) moves it to the rework due date when that is later ({@link #rework}, {@link #reworked}), and
 * funding later than the planned start (M1) moves it by the days funding was late ({@link #fundedLate}); the
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

	/** FR-87: a Milestone whose work started later than planned is due later by the same days, for the three sides. */
	static final String DUE_MOVED = "job.milestone_due_moved";

	/** Why a due date moved (FR-87): a revision request (M4), a voided handover (M3), or funding later than planned (M1). */
	enum Cause {

		REVISION, VOID, LATE_FUNDING

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

	private final AgreementCriteria criteria;

	private final Clock clock;

	DueDates(JdbcClient jdbc, CrewService crews, Deadlines deadlines, Notifications notifications, JobRules rules,
			AuditLog auditLog, AgreementCriteria criteria, Clock clock) {
		this.criteria = criteria;
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
	 * FR-68, AD-48: when the Milestone's due date in force really ends. It is the end of {@code dueOn} in Vietnam time,
	 * or later when a freeze pushed the Milestone's pending overdue Deadline back by the time it was paused. A Deadline
	 * that already ran, or one of an earlier due date, adds nothing: the plain end of the day then stands.
	 */
	Instant effectiveDueAt(UUID milestoneId, LocalDate dueOn) {
		Instant plain = dueAt(dueOn);
		return this.deadlines.dueAt(MilestoneOverdue.KEY, milestoneId).filter(pending -> pending.isAfter(plain)).orElse(plain);
	}

	/**
	 * FR-87, AD-48: the due date a rework at {@code event} moves the Milestone to, which is the event's day in Vietnam
	 * time plus the Job's {@code REWORK_DAYS}; empty when the Milestone has no due date, or when that day does not end
	 * later than its due date in force does ({@link #effectiveDueAt}), so that a rework never shortens the time a
	 * freeze gave back.
	 */
	private Optional<LocalDate> reworkMove(UUID jobId, UUID milestoneId, @Nullable LocalDate dueOn, Instant event) {
		if (dueOn == null) {
			return Optional.empty();
		}
		LocalDate rework = LocalDate.ofInstant(event, VIETNAM)
			.plusDays(this.rules.value(jobId, REWORK_RULE, DEFAULT_REWORK_DAYS));
		return dueAt(rework).isAfter(effectiveDueAt(milestoneId, dueOn)) ? Optional.of(rework) : Optional.empty();
	}

	/**
	 * The due date a revision request made now would move the Milestone to, for the revision dialog (UX-DR128); empty
	 * when the request would move nothing, so the dialog never announces a "new" date that is the current one. It is
	 * the date {@link #rework} writes, unless the day changes in Vietnam between the two.
	 */
	@Transactional(readOnly = true)
	Optional<LocalDate> reworkDueOn(UUID milestoneId) {
		Row row = row(milestoneId);
		return row == null ? Optional.empty() : reworkMove(row.jobId(), milestoneId, row.dueOn(), this.clock.instant());
	}

	/**
	 * In the {@code apply} of M3 or M4 (FR-87, AD-48): when the rework due date ends later than the Milestone's due
	 * date in force, the transition's own statement writes it and clears the overdue flag, and the facts
	 * {@code dueFrom}, {@code dueTo} and {@code dueCause} say so. Otherwise nothing moves. A Milestone with no due date
	 * gets none.
	 */
	void rework(Applying applying, Cause cause) {
		Row row = row(applying.milestoneId());
		LocalDate dueOn = row == null ? null : row.dueOn();
		LocalDate moved = reworkMove(applying.job().id(), applying.milestoneId(), dueOn, applying.now()).orElse(null);
		if (dueOn == null || moved == null) {
			return;
		}
		applying.set("due_on", moved);
		applying.set("overdue_since", null);
		applying.fact("dueFrom", dueOn.toString());
		applying.fact("dueTo", moved.toString());
		applying.fact("dueCause", cause.name());
	}

	/**
	 * FR-87, AD-48: how many days late a handover at {@code at} is: the started 24-hour periods after the Milestone's
	 * due date in force ended, and 0 when it is not after it or the Milestone has no due date.
	 */
	int daysLate(UUID milestoneId, Instant at) {
		Row row = row(milestoneId);
		if (row == null || row.dueOn() == null) {
			return 0;
		}
		Instant due = effectiveDueAt(milestoneId, row.dueOn());
		if (!at.isAfter(due)) {
			return 0;
		}
		long millis = Duration.between(due, at).toMillis();
		return (int) Math.min(Integer.MAX_VALUE, (millis + 86_399_999L) / 86_400_000L);
	}

	/**
	 * In the {@code afterWrite} of M3 or M4, once {@link #rework} moved the date: the new pair of Deadlines (the earlier
	 * pair ends {@code OBSOLETE} by its generation), the three sides are told, and the move is audited with its cause.
	 */
	void reworked(Applying applying) {
		if (applying.factOf("dueCause") == null) {
			return;
		}
		schedule(applying.job().id(), applying.milestoneId());
		announce(applying);
	}

	/**
	 * In the {@code apply} of M1 (FR-87, AD-48): a Milestone funded later than its planned start ({@link #plannedStart})
	 * is due later by the same number of days, in Vietnam time. Funded on or before it, or with no due date or no
	 * planned start to tell, nothing moves. The facts {@code dueFrom}, {@code dueTo}, {@code dueCause} and
	 * {@code dueDays} say what moved; M1's {@code afterWrite} arms the Deadlines for the date in force and calls
	 * {@link #announce}.
	 */
	void fundedLate(Applying applying) {
		Row row = row(applying.milestoneId());
		if (row == null || row.dueOn() == null) {
			return;
		}
		LocalDate planned = plannedStart(applying.job().id(), applying.milestoneId()).orElse(null);
		if (planned == null) {
			return;
		}
		long late = ChronoUnit.DAYS.between(planned, LocalDate.ofInstant(applying.now(), VIETNAM));
		if (late <= 0) {
			return;
		}
		LocalDate moved = row.dueOn().plusDays(late);
		applying.set("due_on", moved);
		applying.fact("dueFrom", row.dueOn().toString());
		applying.fact("dueTo", moved.toString());
		applying.fact("dueCause", Cause.LATE_FUNDING.name());
		applying.fact("dueDays", String.valueOf(late));
	}

	/**
	 * When the Milestone's work was planned to start (FR-87), in Vietnam time; empty when it cannot be told.
	 * <ul>
	 * <li>A Milestone after another: the previous Milestone's agreed due date in the Agreement in force.</li>
	 * <li>The Job's first funding: the day the first of its counted Crew members signed, before which the Customer
	 * could not pay (J6 needs a signed member), so the wait for a Crew is never counted as late funding.</li>
	 * <li>Never earlier than the day the Agreement in force was confirmed: a Change Request confirmed after the
	 * previous Milestone's due date stated the dates again, knowing the calendar, so only the days after it count.</li>
	 * <li>A Milestone that is first only because a Change Request removed the ones before it has no planned start,
	 * and is not moved.</li>
	 * </ul>
	 */
	private Optional<LocalDate> plannedStart(UUID jobId, UUID milestoneId) {
		CriteriaInForce inForce = this.criteria.inForce(jobId).orElse(null);
		if (inForce == null) {
			return Optional.empty();
		}
		List<MilestoneInForce> milestones = inForce.milestones();
		int index = 0;
		while (index < milestones.size() && !milestones.get(index).milestoneId().equals(milestoneId)) {
			index++;
		}
		if (index == milestones.size()) {
			return Optional.empty();
		}
		LocalDate start = index > 0 ? milestones.get(index - 1).dueOn() : firstSignedOn(jobId, milestoneId);
		if (start == null) {
			return Optional.empty();
		}
		LocalDate confirmed = this.jdbc
			.sql("SELECT confirmed_at FROM job.confirmed_agreement WHERE job_id = :job AND version_no = :version")
			.param("job", jobId)
			.param("version", inForce.agreementVersionNo())
			.query((rs, n) -> LocalDate.ofInstant(rs.getTimestamp(1).toInstant(), VIETNAM))
			.single();
		return Optional.of(start.isBefore(confirmed) ? confirmed : start);
	}

	/**
	 * For the Job's first funding, the day the first of its counted Crew members signed; {@code null} when another
	 * Milestone of the Job was funded before, one a Change Request removed since included, or no member has signed.
	 */
	private @Nullable LocalDate firstSignedOn(UUID jobId, UUID milestoneId) {
		Boolean fundedBefore = this.jdbc
			.sql("SELECT EXISTS (SELECT 1 FROM job.milestone WHERE job_id = :job AND id <> :id AND funded_at IS NOT NULL)")
			.param("job", jobId)
			.param("id", milestoneId)
			.query(Boolean.class)
			.single();
		if (fundedBefore) {
			return null;
		}
		return this.crews.countedActive(jobId)
			.stream()
			.map(CrewService.Row::signedAt)
			.filter(Objects::nonNull)
			.min(Instant::compareTo)
			.map(signed -> LocalDate.ofInstant(signed, VIETNAM))
			.orElse(null);
	}

	/**
	 * After the write of a transition that moved the due date (FR-87): the Customer, the Mentor and the active Crew are
	 * told, and the move is audited with its cause (FR-63). Does nothing when the transition moved no date.
	 */
	void announce(Applying applying) {
		String cause = applying.factOf("dueCause");
		if (cause == null) {
			return;
		}
		Job job = applying.job();
		UUID milestoneId = applying.milestoneId();
		Row row = Objects.requireNonNull(row(milestoneId));
		List<Recipient> everyone = new ArrayList<>(List.of(new Recipient(job.customerId(), Audience.CUSTOMER)));
		everyone.addAll(team(job));
		Map<String, String> params = new HashMap<>(params(job, row));
		boolean lateFunding = Cause.LATE_FUNDING.name().equals(cause);
		if (lateFunding) {
			params.put("days", Objects.requireNonNull(applying.factOf("dueDays")));
		}
		this.notifications.send(new NotificationRequest(lateFunding ? DUE_MOVED : REWORK_DUE, applying.transitionId(), everyone,
				params, "/cong-viec/" + job.id(), job.id(), null));
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
