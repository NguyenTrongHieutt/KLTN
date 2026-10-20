package vn.vibefix.job.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

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
 * FR-40's stall deadlines (AD-7): 14 days from entering Agreement (J4) and 7 from entering Starting (J6),
 * FR-40's own values rather than FR-61 rules. When one passes with the Job still there, the Job records
 * it and both sides learn they may cancel alone.
 */
@Component
class StallDeadlines {

	static final Duration AGREEMENT = Duration.ofDays(14);

	static final Duration STARTING = Duration.ofDays(7);

	static final String AGREEMENT_PASSED = "job.agreement_deadline_passed";

	static final String STARTING_PASSED = "job.starting_deadline_passed";

	private final JdbcClient jdbc;

	private final Jobs jobs;

	private final Deadlines deadlines;

	private final Notifications notifications;

	private final Clock clock;

	StallDeadlines(JdbcClient jdbc, Jobs jobs, Deadlines deadlines, Notifications notifications, Clock clock) {
		this.jdbc = jdbc;
		this.jobs = jobs;
		this.deadlines = deadlines;
		this.notifications = notifications;
		this.clock = clock;
	}

	/** J4 or J6: the deadline of the state the Job entered. */
	@Transactional
	void schedule(UUID jobId, JobState state) {
		Instant now = this.clock.instant();
		if (state == JobState.AGREEMENT) {
			this.deadlines.schedule(new DeadlineRequest(AgreementDeadline.KEY, jobId, jobId.toString(), jobId, now.plus(AGREEMENT)));
		}
		else if (state == JobState.STARTING) {
			this.deadlines.schedule(new DeadlineRequest(StartingDeadline.KEY, jobId, jobId.toString(), jobId, now.plus(STARTING)));
		}
	}

	/**
	 * The deadline of {@code state} came: recorded and told once when the Job is still there.
	 * @return whether it applied
	 */
	@Transactional
	boolean passed(UUID jobId, JobState state) {
		Job job = this.jobs.lock(jobId).orElse(null);
		if (job == null || job.state() != state || job.mentorId() == null) {
			return false;
		}
		int recorded = this.jdbc.sql("""
				UPDATE job.job SET stall_deadline_passed_at = :now WHERE id = :id AND stall_deadline_passed_at IS NULL
				""").param("id", jobId).param("now", Timestamp.from(this.clock.instant())).update();
		if (recorded == 0) {
			return false;
		}
		this.notifications.send(new NotificationRequest(state == JobState.AGREEMENT ? AGREEMENT_PASSED : STARTING_PASSED,
				jobId, List.of(new Recipient(job.customerId(), Audience.CUSTOMER),
						new Recipient(Objects.requireNonNull(job.mentorId()), Audience.MENTOR)),
				Map.of("job", job.title()), "/cong-viec/" + jobId, jobId, null));
		return true;
	}

	/** Whether either deadline passed while the Job waited in Agreement or Starting. */
	boolean passed(UUID jobId) {
		return this.jdbc.sql("SELECT stall_deadline_passed_at IS NOT NULL FROM job.job WHERE id = :id")
			.param("id", jobId)
			.query(Boolean.class)
			.optional()
			.orElse(false);
	}

}
