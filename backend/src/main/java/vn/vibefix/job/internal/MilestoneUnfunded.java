package vn.vibefix.job.internal;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.job.internal.Jobs.Job;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.timers.DeadlineHandler;
import vn.vibefix.timers.DeadlineKind;
import vn.vibefix.timers.DeadlineOutcome;
import vn.vibefix.timers.DueDeadline;

/**
 * {@value #KEY} (FR-41, AD-7): M5 schedules it for the next Planned Milestone, {@link #WAIT} after the
 * acceptance. When it fires with that Milestone still Planned and the Job Active, the Customer and the
 * Mentor get {@value #REMINDER}; JOB-1 already lets either side propose closure (FR-37), since no
 * Milestone is Held. Pausable: a frozen Job pauses it. A funded or removed Milestone, or a Job that left
 * Active, leaves it {@code OBSOLETE}. Collaborators are looked up on use.
 */
@Component
class MilestoneUnfunded implements DeadlineHandler {

	static final String KEY = "job.milestone_unfunded";

	static final DeadlineKind KIND = new DeadlineKind(KEY, true, true);

	static final String REMINDER = "job.milestone_unfunded_reminder";

	/** FR-41's {@code [ASSUMPTION]}: how long the next Milestone may wait for its payment. */
	static final Duration WAIT = Duration.ofDays(14);

	private final ObjectProvider<Jobs> jobs;

	private final ObjectProvider<M1Fund> numbers;

	private final ObjectProvider<Notifications> notifications;

	private final JdbcClient jdbc;

	MilestoneUnfunded(ObjectProvider<Jobs> jobs, ObjectProvider<M1Fund> numbers, ObjectProvider<Notifications> notifications,
			JdbcClient jdbc) {
		this.jobs = jobs;
		this.numbers = numbers;
		this.notifications = notifications;
		this.jdbc = jdbc;
	}

	@Override
	public DeadlineKind kind() {
		return KIND;
	}

	@Override
	@Transactional
	public DeadlineOutcome handle(DueDeadline deadline) {
		Job job = deadline.jobId() == null ? null : this.jobs.getObject().lock(deadline.jobId()).orElse(null);
		if (job == null) {
			return DeadlineOutcome.OBSOLETE;
		}
		if (job.frozenAt() != null) {
			return DeadlineOutcome.REPAUSE;
		}
		boolean planned = this.jdbc.sql("""
				SELECT state = 'PLANNED' AND removed_at IS NULL FROM job.milestone WHERE id = :id AND job_id = :job
				""").param("id", deadline.subjectId()).param("job", job.id()).query(Boolean.class).optional().orElse(false);
		if (job.state() != JobState.ACTIVE || !planned) {
			return DeadlineOutcome.OBSOLETE;
		}
		int n = this.numbers.getObject().number(job.id(), deadline.subjectId());
		this.notifications.getObject().send(new NotificationRequest(REMINDER, deadline.id(),
				List.of(new Recipient(job.customerId(), Audience.CUSTOMER),
						new Recipient(Objects.requireNonNull(job.mentorId()), Audience.MENTOR)),
				Map.of("job", job.title(), "n", String.valueOf(n), "days", String.valueOf(WAIT.toDays())),
				"/cong-viec/" + job.id(), job.id(), null));
		return DeadlineOutcome.FIRED;
	}

}
