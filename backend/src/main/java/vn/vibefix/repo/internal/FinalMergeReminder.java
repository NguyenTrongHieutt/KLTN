package vn.vibefix.repo.internal;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.job.JobBusy;
import vn.vibefix.job.JobBusy.BusyJob;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.repo.internal.FinalMergeAuthorizations.Authorization;
import vn.vibefix.timers.DeadlineHandler;
import vn.vibefix.timers.DeadlineKind;
import vn.vibefix.timers.DeadlineOutcome;
import vn.vibefix.timers.DueDeadline;

/**
 * {@value #KEY} (FR-37, FR-48; author decision 2026-10-21): 2 days before the FR-37 deadline, an
 * authorization still {@code REQUESTED} reminds the Customer, saying when an install request waits for an
 * organization owner. Pausable: a frozen Job pauses it. Collaborators are looked up on use.
 */
@Component
class FinalMergeReminder implements DeadlineHandler {

	static final String KEY = "repo.final_merge_reminder";

	static final DeadlineKind KIND = new DeadlineKind(KEY, true, true);

	private final ObjectProvider<FinalMergeAuthorizations> authorizations;

	private final ObjectProvider<JobBusy> busy;

	private final ObjectProvider<Notifications> notifications;

	FinalMergeReminder(ObjectProvider<FinalMergeAuthorizations> authorizations, ObjectProvider<JobBusy> busy,
			ObjectProvider<Notifications> notifications) {
		this.authorizations = authorizations;
		this.busy = busy;
		this.notifications = notifications;
	}

	@Override
	public DeadlineKind kind() {
		return KIND;
	}

	@Override
	@Transactional
	public DeadlineOutcome handle(DueDeadline deadline) {
		Authorization authorization = this.authorizations.getObject().find(deadline.subjectId()).orElse(null);
		if (authorization == null || !"REQUESTED".equals(authorization.status()) || authorization.customerId() == null) {
			return DeadlineOutcome.OBSOLETE;
		}
		BusyJob job = this.busy.getObject().job(authorization.jobId()).orElse(null);
		if (job == null) {
			return DeadlineOutcome.OBSOLETE;
		}
		if (job.frozen()) {
			return DeadlineOutcome.REPAUSE;
		}
		String waiting = authorization.requestSeenAt() == null || authorization.requestOrg() == null ? ""
				: ". Yêu cầu cài ứng dụng hợp nhất vẫn chờ chủ tổ chức " + authorization.requestOrg() + " duyệt";
		this.notifications.getObject()
			.send(new NotificationRequest(FinalMergeAuthorizations.REMINDER, deadline.id(),
					List.of(new Recipient(authorization.customerId(), Audience.CUSTOMER)),
					Map.of("job", job.title(), "date", FinalMergeAuthorizations.dayMonth(authorization.deadlineAt()), "waiting",
							waiting),
					FinalMergeAuthorizations.link(job.jobId()), job.jobId(), null));
		return DeadlineOutcome.FIRED;
	}

}
