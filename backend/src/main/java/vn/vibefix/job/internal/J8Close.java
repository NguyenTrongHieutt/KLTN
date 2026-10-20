package vn.vibefix.job.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.jdbc.core.simple.JdbcClient;
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
 * J8 (FR-37, FR-67): a confirmed closure moves an Active Job with no Held Milestone to Closing, with M6
 * for each Planned Milestone in the same apply. It records when, and tells the Customer, the Mentor,
 * and the active Crew ({@code job.job_closing}); it releases no Crew row (AD-41).
 */
@Component
class J8Close implements JobLifecycle.Step {

	static final String CLOSING = "job.job_closing";

	private final JdbcClient jdbc;

	private final CrewService crews;

	private final Notifications notifications;

	J8Close(JdbcClient jdbc, CrewService crews, Notifications notifications) {
		this.jdbc = jdbc;
		this.crews = crews;
		this.notifications = notifications;
	}

	@Override
	public Transition transition() {
		return Transition.J8;
	}

	/**
	 * @throws ApiException {@code GUARD_FAILED MILESTONE_HELD}, except in a cancellation, whose cascade
	 * cancels the Held Milestone with M7 first (FR-40)
	 */
	@Override
	public void check(Applying applying) {
		if (!applying.has(Cancellations.INPUT) && Closures.held(this.jdbc, applying.job().id())) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.MILESTONE_HELD);
		}
	}

	@Override
	public void apply(Applying applying) {
		applying.set("closing_at", applying.now());
	}

	@Override
	public void afterWrite(Applying applying) {
		Job job = applying.job();
		this.notifications.send(new NotificationRequest(CLOSING, applying.transitionId(), people(job, this.crews),
				Map.of("job", job.title()), "/cong-viec/" + job.id(), job.id(), null));
	}

	/** The Customer, the Mentor, and the active Crew. */
	static List<Recipient> people(Job job, CrewService crews) {
		List<Recipient> people = new ArrayList<>(List.of(new Recipient(job.customerId(), Audience.CUSTOMER),
				new Recipient(Objects.requireNonNull(job.mentorId()), Audience.MENTOR)));
		crews.rows(job.id())
			.stream()
			.filter(row -> "ACTIVE".equals(row.status()))
			.forEach(row -> people.add(new Recipient(row.studentId(), Audience.CREW_ACTIVE)));
		return people;
	}

}
