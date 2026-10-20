package vn.vibefix.delivery.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import vn.vibefix.delivery.internal.Handovers.Handover;
import vn.vibefix.job.MilestoneHandovers;
import vn.vibefix.job.MilestoneHandovers.HandoverJob;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.timers.DeadlineHandler;
import vn.vibefix.timers.DeadlineKind;
import vn.vibefix.timers.DeadlineOutcome;
import vn.vibefix.timers.Deadlines;
import vn.vibefix.timers.DueDeadline;

/**
 * {@value #KEY} (FR-35, SG-26): the Customer is reminded twice, generations {@code 1} and {@code 2}, that
 * the System accepts the Milestone on its Auto-acceptance date. The date and the days left come from the
 * Auto-acceptance Deadline's due time now, so a reminder after a pause restates the new date. Pausable;
 * a decided handover leaves it {@code OBSOLETE}. Its collaborators are looked up on use, as for
 * {@link AutoAcceptance}.
 */
@Component
class AcceptanceReminder implements DeadlineHandler {

	static final String KEY = "delivery.acceptance_reminder";

	static final String REMINDER = "delivery.acceptance_reminder";

	static final DeadlineKind KIND = new DeadlineKind(KEY, true, true);

	private final ObjectProvider<Handovers> handovers;

	private final ObjectProvider<MilestoneHandovers> jobs;

	private final ObjectProvider<Deadlines> deadlines;

	private final Notifications notifications;

	private final Clock clock;

	AcceptanceReminder(ObjectProvider<Handovers> handovers, ObjectProvider<MilestoneHandovers> jobs,
			ObjectProvider<Deadlines> deadlines, Notifications notifications, Clock clock) {
		this.handovers = handovers;
		this.jobs = jobs;
		this.deadlines = deadlines;
		this.notifications = notifications;
		this.clock = clock;
	}

	@Override
	public DeadlineKind kind() {
		return KIND;
	}

	@Override
	public DeadlineOutcome handle(DueDeadline deadline) {
		Handover handover = this.handovers.getObject().find(deadline.subjectId()).orElse(null);
		if (handover == null || !"SUBMITTED".equals(handover.status())) {
			return DeadlineOutcome.OBSOLETE;
		}
		HandoverJob job = this.jobs.getObject().job(handover.jobId()).orElse(null);
		if (job == null) {
			return DeadlineOutcome.OBSOLETE;
		}
		if (job.frozen()) {
			return DeadlineOutcome.REPAUSE;
		}
		Instant due = this.deadlines.getObject().dueAt(AutoAcceptance.KEY, handover.id()).orElse(null);
		if (due == null) {
			return DeadlineOutcome.OBSOLETE;
		}
		long hours = Math.max(0, Duration.between(this.clock.instant(), due).toHours());
		long days = Math.max(1, (hours + 23) / 24);
		this.notifications.send(new NotificationRequest(REMINDER, deadline.id(),
				List.of(new Recipient(job.customerId(), Audience.CUSTOMER)),
				Map.of("n", String.valueOf(days), "m", String.valueOf(handover.number()), "date", Handovers.dayMonth(due)),
				"/cong-viec/" + job.jobId() + "/ban-giao/" + handover.id(), job.jobId(), null));
		return DeadlineOutcome.FIRED;
	}

}
