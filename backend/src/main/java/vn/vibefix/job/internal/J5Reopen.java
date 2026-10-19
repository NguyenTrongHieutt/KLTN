package vn.vibefix.job.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Component;

import vn.vibefix.identity.MentorDirectory;
import vn.vibefix.identity.MentorDirectory.MentorCard;
import vn.vibefix.job.JobSelection;
import vn.vibefix.job.internal.JobLifecycle.Applying;
import vn.vibefix.job.internal.Jobs.Job;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.product.ProductPins;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.timers.DeadlineRequest;
import vn.vibefix.timers.Deadlines;

/**
 * J5 (FR-15): the selected Mentor declines, or the Customer cancels the selection once the signature
 * period has passed without a signature. The Job is Open again with a new selection period and lapse
 * Deadline, the Mentor and the selection are cleared, and the Product pin is released; a decline
 * tells the Customer. {@code matching} reopens the held Applications.
 */
@Component
class J5Reopen implements JobLifecycle.Step {

	static final String DECLINED = "job.selection_declined";

	private final ProductPins pins;

	private final Deadlines deadlines;

	private final MentorDirectory mentors;

	private final Notifications notifications;

	J5Reopen(ProductPins pins, Deadlines deadlines, MentorDirectory mentors, Notifications notifications) {
		this.pins = pins;
		this.deadlines = deadlines;
		this.mentors = mentors;
		this.notifications = notifications;
	}

	@Override
	public Transition transition() {
		return Transition.J5;
	}

	/**
	 * @throws ApiException {@code NOT_FOUND} for anyone but the Job's Mentor or Customer; {@code
	 * GUARD_FAILED NDA_PERIOD_RUNNING} when the Customer cancels before the period ends
	 */
	@Override
	public void check(Applying applying) {
		Job job = applying.job();
		boolean mentor = applying.actor().is(Objects.requireNonNull(job.mentorId()));
		boolean customer = applying.actor().is(job.customerId());
		if (!mentor && !customer) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		Instant due = Objects.requireNonNull(job.selectedAt()).plus(JobSelection.NDA_PERIOD);
		if (!mentor && applying.now().isBefore(due)) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.NDA_PERIOD_RUNNING);
		}
	}

	@Override
	public void apply(Applying applying) {
		Job job = applying.job();
		boolean declined = applying.actor().is(Objects.requireNonNull(job.mentorId()));
		Instant lapsesAt = applying.now().plus(Duration.ofDays(J1Publish.SELECTION_DAYS));
		applying.set("mentor_account_id", null);
		applying.set("selected_application_id", null);
		applying.set("selected_at", null);
		applying.set("opened_at", applying.now());
		applying.set("lapses_at", lapsesAt);
		this.pins.releaseForJob(job.productId(), job.id());
		this.deadlines.schedule(new DeadlineRequest(JobSelectionLapse.KEY, job.id(), String.valueOf(applying.version()),
				job.id(), lapsesAt));
		applying.fact("reason", declined ? "DECLINED" : "NOT_SIGNED");
		applying.fact("mentorId", job.mentorId().toString());
		applying.fact("applicationId", String.valueOf(job.selectedApplicationId()));
		applying.fact("lapsesAt", lapsesAt.toString());
	}

	@Override
	public void afterWrite(Applying applying) {
		Job job = applying.job();
		UUID mentor = Objects.requireNonNull(job.mentorId());
		if (!applying.actor().is(mentor)) {
			return;
		}
		String name = this.mentors.cards(List.of(mentor)).stream().findFirst().map(MentorCard::displayName).orElse("");
		this.notifications.send(new NotificationRequest(DECLINED, applying.transitionId(),
				List.of(new Recipient(job.customerId(), Audience.CUSTOMER)), Map.of("mentor", name),
				"/cong-viec/" + job.id(), job.id(), null));
	}

}
