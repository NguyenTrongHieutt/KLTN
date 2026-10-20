package vn.vibefix.job.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.vibefix.identity.MentorDirectory;
import vn.vibefix.identity.MentorDirectory.MentorCard;
import vn.vibefix.job.internal.JobLifecycle.Applying;
import vn.vibefix.job.internal.Jobs.Job;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;

/**
 * M2 (FR-32, AD-4): the Mentor's Milestone Handover makes a Funded or Revision Requested Milestone
 * Submitted. {@code delivery} vouches for the handover and passes its ID, which the Milestone records
 * with the time; a Code Job also needs its Handover Commit, which Epic 7 records. The Customer gets
 * {@code job.milestone_submitted} with the date to check by: now plus the Job's copied Auto-acceptance
 * days (FR-35), whose Deadline {@code delivery} keeps from Story 6.4 on.
 */
@Component
class M2Submit implements JobLifecycle.Step {

	static final String SUBMITTED = "job.milestone_submitted";

	static final int DEFAULT_AUTO_ACCEPTANCE_DAYS = 7;

	private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

	private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("dd/MM");

	private final JdbcClient jdbc;

	private final M1Fund numbers;

	private final MentorDirectory mentors;

	private final Notifications notifications;

	M2Submit(JdbcClient jdbc, M1Fund numbers, MentorDirectory mentors, Notifications notifications) {
		this.jdbc = jdbc;
		this.numbers = numbers;
		this.mentors = mentors;
		this.notifications = notifications;
	}

	@Override
	public Transition transition() {
		return Transition.M2;
	}

	/**
	 * @throws ApiException {@code FORBIDDEN MENTOR_ONLY}; {@code STALE STATE_CHANGED} outside Active;
	 * {@code GUARD_FAILED HANDOVER_COMMIT_MISSING} for a Code Job without its commit
	 */
	@Override
	public void check(Applying applying) {
		Job job = applying.job();
		if (!Objects.equals(applying.actor().accountId(), job.mentorId())) {
			throw new ApiException(ErrorCode.FORBIDDEN, JobReason.MENTOR_ONLY);
		}
		if (job.state() != JobState.ACTIVE) {
			throw new ApiException(ErrorCode.STALE, JobReason.STATE_CHANGED);
		}
		applying.input("handoverId");
		if (job.code() && !applying.has("handoverCommit")) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.HANDOVER_COMMIT_MISSING);
		}
	}

	@Override
	public void apply(Applying applying) {
		UUID handover = (UUID) applying.input("handoverId");
		applying.set("submitted_at", applying.now());
		applying.set("handover_id", handover);
		applying.set("overdue_since", null);
		applying.fact("milestoneId", applying.milestoneId().toString());
		applying.fact("handoverId", handover.toString());
		applying.fact("number", String.valueOf(this.numbers.number(applying.job().id(), applying.milestoneId())));
		applying.fact("checkBy", checkBy(applying).toString());
	}

	@Override
	public void afterWrite(Applying applying) {
		Job job = applying.job();
		String mentor = this.mentors.cards(List.of(Objects.requireNonNull(job.mentorId()))).stream().findFirst()
			.map(MentorCard::displayName).orElse("");
		String date = DAY_MONTH.format(checkBy(applying).atZone(VIETNAM));
		this.notifications.send(new NotificationRequest(SUBMITTED, applying.transitionId(),
				List.of(new Recipient(job.customerId(), Audience.CUSTOMER)),
				Map.of("mentor", mentor, "n", String.valueOf(this.numbers.number(job.id(), applying.milestoneId())), "date",
						date),
				"/cong-viec/" + job.id() + "/ban-giao/" + applying.input("handoverId"), job.id(), null));
	}

	/** When the Customer should have checked by: the handover plus the Job's copied Auto-acceptance days. */
	Instant checkBy(Applying applying) {
		Integer days = this.jdbc.sql("SELECT (rules ->> 'AUTO_ACCEPTANCE_DAYS')::int FROM job.job WHERE id = :id")
			.param("id", applying.job().id())
			.query(Integer.class)
			.optional()
			.orElse(null);
		return applying.now().plus(Duration.ofDays(days == null ? DEFAULT_AUTO_ACCEPTANCE_DAYS : days));
	}

}
