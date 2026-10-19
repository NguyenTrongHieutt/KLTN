package vn.vibefix.matching.internal;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import vn.vibefix.job.JobPosts;
import vn.vibefix.job.JobPosts.JobPostState;
import vn.vibefix.matching.internal.Applications.Application;
import vn.vibefix.matching.internal.Applications.Status;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.timers.DeadlineHandler;
import vn.vibefix.timers.DeadlineKind;
import vn.vibefix.timers.DeadlineOutcome;
import vn.vibefix.timers.DueDeadline;

/**
 * {@value #KEY} (FR-15, AD-7): the FR-15 signature period ends 3 days after J3. While the selected
 * Application is still the selected one and the Job is Selecting, the Customer learns
 * ({@value #OVERDUE}) and may cancel the selection (J5). Not pausable; the generation is the
 * Application, so a signed or declined one leaves the Deadline {@code OBSOLETE}. Collaborators are
 * looked up on use: {@code timers} collects its handlers before the commands exist.
 */
@Component
class NdaDue implements DeadlineHandler {

	static final String KEY = "matching.nda_due";

	static final String OVERDUE = "matching.nda_overdue";

	static final DeadlineKind KIND = new DeadlineKind(KEY, false, true);

	private final ObjectProvider<Applications> applications;

	private final ObjectProvider<JobPosts> posts;

	private final ObjectProvider<Notifications> notifications;

	NdaDue(ObjectProvider<Applications> applications, ObjectProvider<JobPosts> posts,
			ObjectProvider<Notifications> notifications) {
		this.applications = applications;
		this.posts = posts;
		this.notifications = notifications;
	}

	@Override
	public DeadlineKind kind() {
		return KIND;
	}

	@Override
	public DeadlineOutcome handle(DueDeadline deadline) {
		Application application = this.applications.getObject().byId(deadline.subjectId()).orElse(null);
		if (application == null || application.status() != Status.SELECTED) {
			return DeadlineOutcome.OBSOLETE;
		}
		JobPostState post = this.posts.getObject().post(application.jobId()).orElse(null);
		if (post == null || !"SELECTING".equals(post.state())) {
			return DeadlineOutcome.OBSOLETE;
		}
		this.notifications.getObject().send(new NotificationRequest(OVERDUE, deadline.id(),
				List.of(new Recipient(post.customerId(), Audience.CUSTOMER)), Map.of("job", post.title()),
				"/cong-viec/" + post.jobId(), post.jobId(), null));
		return DeadlineOutcome.FIRED;
	}

}
