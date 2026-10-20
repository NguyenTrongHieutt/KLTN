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
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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
 */
@Component
class DueDates {

	static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

	static final Duration SOON = Duration.ofDays(2);

	static final String DUE_SOON = "job.milestone_due_soon";

	static final String OVERDUE = "job.milestone_overdue";

	private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("dd/MM");

	private static final List<String> OPEN = List.of("FUNDED", "REVISION_REQUESTED");

	private record Row(UUID jobId, String state, @Nullable LocalDate dueOn, int number) {
	}

	private final JdbcClient jdbc;

	private final CrewService crews;

	private final Deadlines deadlines;

	private final Notifications notifications;

	private final Clock clock;

	DueDates(JdbcClient jdbc, CrewService crews, Deadlines deadlines, Notifications notifications, Clock clock) {
		this.jdbc = jdbc;
		this.crews = crews;
		this.deadlines = deadlines;
		this.notifications = notifications;
		this.clock = clock;
	}

	/** When a Milestone due on {@code dueOn} becomes overdue: the end of that day in Vietnam time. */
	static Instant dueAt(LocalDate dueOn) {
		return dueOn.plusDays(1).atStartOfDay(VIETNAM).toInstant();
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
