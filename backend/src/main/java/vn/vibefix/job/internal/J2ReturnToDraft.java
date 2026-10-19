package vn.vibefix.job.internal;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import vn.vibefix.job.internal.JobLifecycle.Applying;
import vn.vibefix.job.internal.Jobs.Job;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;

/**
 * J2 (FR-9): an Open Job Post returns to Draft when its Customer withdraws it ({@code USER}) or its
 * selection period lapses ({@code AUTO}, by {@link JobSelectionLapse}). The Job Post's copied rules go
 * with it, so the next J1 copies the values then in force. Only a lapse tells the Customer
 * ({@value #LAPSED}); Open Applications close once they exist (Story 3.4).
 */
@Component
class J2ReturnToDraft implements JobLifecycle.Step {

	static final String LAPSED = "job.job_post_lapsed";

	private final Notifications notifications;

	J2ReturnToDraft(Notifications notifications) {
		this.notifications = notifications;
	}

	@Override
	public Transition transition() {
		return Transition.J2;
	}

	/** @throws ApiException {@code NOT_FOUND} when someone but the Job's Customer withdraws it */
	@Override
	public void check(Applying applying) {
		boolean allowed = applying.trigger() == Trigger.AUTO ? applying.actor().isSystem()
				: applying.actor().is(applying.job().customerId());
		if (!allowed) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
	}

	@Override
	public void apply(Applying applying) {
		applying.setJson("rules", null);
		applying.set("selection_days", null);
		applying.set("opened_at", null);
		applying.set("lapses_at", null);
		boolean lapsed = applying.trigger() == Trigger.AUTO;
		applying.fact("reason", lapsed ? "LAPSED" : "WITHDRAWN");
		if (lapsed) {
			Job job = applying.job();
			this.notifications.send(new NotificationRequest(LAPSED, applying.transitionId(),
					List.of(new Recipient(job.customerId(), Audience.CUSTOMER)), Map.of("job", job.title()),
					"/cong-viec/" + job.id() + "/sua", job.id(), null));
		}
	}

}
