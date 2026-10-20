package vn.vibefix.job.internal;

import java.util.Map;
import java.util.Objects;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.vibefix.identity.ContactVisibility;
import vn.vibefix.job.internal.JobLifecycle.Applying;
import vn.vibefix.job.internal.Jobs.Job;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.product.ProductPins;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;

/**
 * J9 (FR-10, FR-38, FR-75, AD-30, AD-41): a Closing Job closes. An Assessment Job closes in J8's apply
 * with no repository step; a Code Job waits for its Final Merge or ZIP Fallback (Epic 7). It records
 * when and tells the Customer, the Mentor, and the active Crew ({@code job.job_closed}) while they are
 * still active; then the Mentor's contact visibility ends, the Product pin is released, and every Crew
 * row not yet released is released as {@code JOB_ENDED}, freeing its Student, before any {@code payment}
 * write in the apply. The Chats become read-only through their own listener. The transition carries the
 * Job Post's copied {@code RATING_WINDOW_DAYS} for {@code reputation}'s Rating Window (FR-49).
 */
@Component
class J9Finish implements JobLifecycle.Step {

	static final String CLOSED = "job.job_closed";

	static final int RATING_WINDOW_DAYS = 14;

	private final JdbcClient jdbc;

	private final CrewService crews;

	private final ContactVisibility contacts;

	private final ProductPins pins;

	private final Notifications notifications;

	J9Finish(JdbcClient jdbc, CrewService crews, ContactVisibility contacts, ProductPins pins,
			Notifications notifications) {
		this.jdbc = jdbc;
		this.crews = crews;
		this.contacts = contacts;
		this.pins = pins;
		this.notifications = notifications;
	}

	@Override
	public Transition transition() {
		return Transition.J9;
	}

	/** @throws ApiException {@code GUARD_FAILED FINAL_MERGE_PENDING} for a Code Job */
	@Override
	public void check(Applying applying) {
		if (applying.job().code()) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.FINAL_MERGE_PENDING);
		}
	}

	@Override
	public void apply(Applying applying) {
		applying.set("closed_at", applying.now());
		Integer days = this.jdbc.sql("SELECT (rules ->> 'RATING_WINDOW_DAYS')::int FROM job.job WHERE id = :id")
			.param("id", applying.job().id())
			.query(Integer.class)
			.optional()
			.orElse(null);
		applying.fact("ratingWindowDays", String.valueOf(days == null ? RATING_WINDOW_DAYS : days));
	}

	@Override
	public void afterWrite(Applying applying) {
		Job job = applying.job();
		this.notifications.send(new NotificationRequest(CLOSED, applying.transitionId(), J8Close.people(job, this.crews),
				Map.of("job", job.title()), "/cong-viec/" + job.id(), job.id(), null));
		this.contacts.end(job.customerId(), Objects.requireNonNull(job.mentorId()), job.id(), applying.now());
		this.pins.releaseForJob(job.productId(), job.id());
		this.crews.releaseForJobEnd(job.id());
	}

}
