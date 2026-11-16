package vn.vibefix.job.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
 * J7 (FR-18): no Job becomes Active before {@code repo}'s Setup vouches for it with {@value #ACCESS_VERIFIED}
 * (AD-11, NFR-6, Story 23.11a). The fact keeps its name and means one thing per kind: for a Code Job, Verify read
 * every grant back as write on the Working Repository; for an Assessment Job, the one private read-only copy read
 * back at the confirmed head, whose read invitations never gate it (R6). A Code Job's start is then told to the
 * Customer, the Mentor, and the active Crew.
 */
@Component
class J7Activate implements JobLifecycle.Step {

	static final String ACCESS_VERIFIED = "accessVerified";

	static final String STARTED = "job.job_started";

	private final CrewService crews;

	private final Notifications notifications;

	J7Activate(CrewService crews, Notifications notifications) {
		this.crews = crews;
		this.notifications = notifications;
	}

	@Override
	public Transition transition() {
		return Transition.J7;
	}

	/** @throws ApiException {@code GUARD_FAILED REPOSITORIES_NOT_READY} for any Job whose access is not vouched for */
	@Override
	public void check(Applying applying) {
		if (!(applying.has(ACCESS_VERIFIED) && Boolean.TRUE.equals(applying.input(ACCESS_VERIFIED)))) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.REPOSITORIES_NOT_READY);
		}
	}

	@Override
	public void apply(Applying applying) {
		applying.fact("assessment", String.valueOf(!applying.job().code()));
	}

	@Override
	public void afterWrite(Applying applying) {
		Job job = applying.job();
		if (!job.code()) {
			return;
		}
		List<Recipient> everyone = new ArrayList<>(List.of(new Recipient(job.customerId(), Audience.CUSTOMER),
				new Recipient(Objects.requireNonNull(job.mentorId()), Audience.MENTOR)));
		this.crews.rows(job.id())
			.stream()
			.filter(row -> "ACTIVE".equals(row.status()))
			.forEach(row -> everyone.add(new Recipient(row.studentId(), Audience.CREW_ACTIVE)));
		this.notifications.send(new NotificationRequest(STARTED, applying.transitionId(), everyone, Map.of("job", job.title()),
				"/cong-viec/" + job.id(), job.id(), null));
	}

}
