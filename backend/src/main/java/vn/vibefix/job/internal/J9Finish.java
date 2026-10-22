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
 * with no repository step; a Code Job closes only with {@value #FINISH}, which {@code repo} gives once
 * the Final Merge was read back or the ZIP Fallback stored (Stories 8.8 and 8.9). It records
 * when and tells the Customer, the Mentor, and the active Crew ({@code job.job_closed}) while they are
 * still active; then the Mentor's contact visibility ends, the Product pin is released, and every Crew
 * row not yet released is released as {@code JOB_ENDED}, freeing its Student, before any {@code payment}
 * write in the apply. The Chats become read-only through their own listener. The transition carries the
 * Job Post's copied {@code RATING_WINDOW_DAYS} for {@code reputation}'s Rating Window (FR-49). A Job that
 * entered Closing by a cancellation ends Cancelled instead (FR-40), with {@code job.job_cancelled}: in
 * its own cascade, or, for a Code Job, because its cancellation proposal was confirmed.
 */
@Component
class J9Finish implements JobLifecycle.Step {

	static final String CLOSED = "job.job_closed";

	/** How a Code Job's code reached the Customer, {@code MERGED} or {@code ZIP_FALLBACK}. */
	static final String FINISH = "finish";

	static final int RATING_WINDOW_DAYS = 14;

	private final JdbcClient jdbc;

	private final CrewService crews;

	private final ContactVisibility contacts;

	private final ProductPins pins;

	private final Notifications notifications;

	private final CrewChanges changes;

	J9Finish(JdbcClient jdbc, CrewService crews, ContactVisibility contacts, ProductPins pins,
			Notifications notifications, CrewChanges changes) {
		this.jdbc = jdbc;
		this.changes = changes;
		this.crews = crews;
		this.contacts = contacts;
		this.pins = pins;
		this.notifications = notifications;
	}

	@Override
	public Transition transition() {
		return Transition.J9;
	}

	/** @throws ApiException {@code GUARD_FAILED FINAL_MERGE_PENDING} for a Code Job without {@value #FINISH} */
	@Override
	public void check(Applying applying) {
		if (applying.job().code() && !applying.has(FINISH)) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.FINAL_MERGE_PENDING);
		}
		if (cancelled(applying)) {
			applying.leadTo(JobState.CANCELLED.name());
		}
	}

	/** Closing came from a cancellation: the cascade says so, or the Code Job's proposal was confirmed. */
	private boolean cancelled(Applying applying) {
		return applying.has(Cancellations.INPUT) || (applying.has(FINISH) && this.jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM job.cancellation_proposal WHERE job_id = :job AND status = 'CONFIRMED')
				""").param("job", applying.job().id()).query(Boolean.class).single());
	}

	@Override
	public void apply(Applying applying) {
		applying.set(cancelled(applying) ? "cancelled_at" : "closed_at", applying.now());
		if (applying.has(FINISH)) {
			applying.fact(FINISH, String.valueOf(applying.input(FINISH)));
		}
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
		String key = cancelled(applying) ? J10Cancel.CANCELLED : CLOSED;
		this.notifications.send(new NotificationRequest(key, applying.transitionId(), J8Close.people(job, this.crews),
				Map.of("job", job.title()), "/cong-viec/" + job.id(), job.id(), null));
		this.contacts.end(job.customerId(), Objects.requireNonNull(job.mentorId()), job.id(), applying.now());
		this.pins.releaseForJob(job.productId(), job.id());
		this.changes.voidWaiting(job.id());
		this.crews.releaseForJobEnd(job.id());
	}

}
