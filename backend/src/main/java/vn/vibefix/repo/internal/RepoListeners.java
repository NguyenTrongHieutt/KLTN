package vn.vibefix.repo.internal;

import java.util.List;
import java.util.Map;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import vn.vibefix.job.JobTransitioned;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.notification.TodoRequest;
import vn.vibefix.notification.Todos;
import vn.vibefix.repo.internal.DataDeletionConfirmations.Duty;
import vn.vibefix.shared.ProcessedEvents;

/**
 * J8 and J10 start the data-deletion duty (FR-66, UX-DR15, UX-DR16): the Mentor and every Crew member who
 * was ever active, locked and removed ones included, get {@code repo.confirm_data_deletion} (tier 3) and
 * {@code repo.data_deletion_confirmation_pending}. Replay-safe.
 */
@Component
class RepoListeners {

	static final String PENDING = "repo.data_deletion_confirmation_pending";

	private final DataDeletionConfirmations confirmations;

	private final Todos todos;

	private final Notifications notifications;

	private final ProcessedEvents processedEvents;

	RepoListeners(DataDeletionConfirmations confirmations, Todos todos, Notifications notifications,
			ProcessedEvents processedEvents) {
		this.confirmations = confirmations;
		this.todos = todos;
		this.notifications = notifications;
		this.processedEvents = processedEvents;
	}

	@ApplicationModuleListener
	void onJobTransitioned(JobTransitioned event) {
		boolean ends = "J8".equals(event.transition()) || "J10".equals(event.transition());
		if (!ends || !this.processedEvents.markProcessed("repo.RepoListeners.onJobTransitioned", event.eventId())) {
			return;
		}
		this.confirmations.job(event.jobId()).ifPresent(job -> {
			for (Duty duty : this.confirmations.duties(job)) {
				Audience audience = duty.mentor() ? Audience.MENTOR : Audience.CREW_EVER_ACTIVE;
				this.todos.open(new TodoRequest(DataDeletionConfirmations.TODO, duty.accountId(), audience, job.jobId(),
						Map.of("job", job.title()), "/cong-viec/" + job.jobId(), null, job.jobId()));
				this.notifications.send(new NotificationRequest(PENDING, event.transitionId(),
						List.of(new Recipient(duty.accountId(), audience)), Map.of("job", job.title()),
						"/cong-viec/" + job.jobId(), job.jobId(), null));
			}
		});
	}

}
