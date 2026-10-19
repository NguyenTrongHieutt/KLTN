package vn.vibefix.job.internal;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.vibefix.identity.MentorDirectory;
import vn.vibefix.identity.MentorDirectory.MentorCard;
import vn.vibefix.job.JobSelection;
import vn.vibefix.job.MentorWork;
import vn.vibefix.job.internal.JobLifecycle.Applying;
import vn.vibefix.job.internal.Jobs.Job;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.product.ProductPins;
import vn.vibefix.shared.AdvisoryLocks;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;

/**
 * J3 (FR-15, AD-30): the Customer selects an Application. FR-13's guard runs again under the Mentor's
 * lock, after the Job row (AD-4 lock order): the Mentor is still approved and unlocked, below the
 * live Job limit, and has a free Student. The Job records the Mentor, which never changes, pins its
 * Product, and tells the Mentor; {@code matching} puts the other Applications on hold.
 */
@Component
class J3Select implements JobLifecycle.Step {

	static final String SELECTED = "job.assignee_selected";

	private final MentorDirectory mentors;

	private final MentorWork work;

	private final ProductPins pins;

	private final Notifications notifications;

	private final JdbcClient jdbc;

	J3Select(MentorDirectory mentors, MentorWork work, ProductPins pins, Notifications notifications, JdbcClient jdbc) {
		this.mentors = mentors;
		this.work = work;
		this.pins = pins;
		this.notifications = notifications;
		this.jdbc = jdbc;
	}

	@Override
	public Transition transition() {
		return Transition.J3;
	}

	@Override
	public void check(Applying applying) {
		if (!applying.actor().is(applying.job().customerId())) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		UUID mentor = mentor(applying);
		AdvisoryLocks.lockAccount(this.jdbc, mentor);
		boolean available = this.mentors.cards(List.of(mentor)).stream().findFirst().map(MentorCard::available).orElse(false);
		if (!available) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.MENTOR_NOT_AVAILABLE);
		}
		if (this.work.activeJobCount(mentor) >= this.work.activeJobLimit()) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.MENTOR_AT_JOB_LIMIT);
		}
		if (this.work.freeStudents(mentor).isEmpty()) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.MENTOR_NO_FREE_STUDENT);
		}
	}

	@Override
	public void apply(Applying applying) {
		UUID application = (UUID) applying.input("applicationId");
		Instant ndaDueAt = applying.now().plus(JobSelection.NDA_PERIOD);
		applying.set("mentor_account_id", mentor(applying));
		applying.set("selected_application_id", application);
		applying.set("selected_at", applying.now());
		this.pins.pinForJob(applying.job().productId(), applying.job().id());
		applying.fact("mentorId", mentor(applying).toString());
		applying.fact("applicationId", application.toString());
		applying.fact("ndaDueAt", ndaDueAt.toString());
	}

	/** The Mentor learns of the selection once the Job names them, so the recipient lookup confirms it. */
	@Override
	public void afterWrite(Applying applying) {
		Job job = applying.job();
		this.notifications.send(new NotificationRequest(SELECTED, applying.transitionId(),
				List.of(new Recipient(mentor(applying), Audience.MENTOR)), Map.of("job", job.title()),
				"/cong-viec/" + job.id(), job.id(), null));
	}

	private static UUID mentor(Applying applying) {
		return (UUID) applying.input("mentorId");
	}

}
