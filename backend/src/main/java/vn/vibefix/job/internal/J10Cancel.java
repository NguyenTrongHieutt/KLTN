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
 * J10 (FR-40, FR-21, FR-66, AD-41): a Job without Accepted work is cancelled from Agreement, Starting,
 * or Active, after its cascade cancelled the Milestones (M6, and M7 with the refund). It records when and
 * tells the Customer, the Mentor, and the active Crew ({@code job.job_cancelled}); then the Mentor's
 * contact visibility ends, the Product pin is released, and every Crew row is released as
 * {@code JOB_ENDED}. The chats and the data-deletion confirmations follow through their listeners.
 */
@Component
class J10Cancel implements JobLifecycle.Step {

	static final String CANCELLED = "job.job_cancelled";

	private final JdbcClient jdbc;

	private final CrewService crews;

	private final ContactVisibility contacts;

	private final ProductPins pins;

	private final Notifications notifications;

	private final CrewChanges changes;

	J10Cancel(JdbcClient jdbc, CrewService crews, ContactVisibility contacts, ProductPins pins,
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
		return Transition.J10;
	}

	/** @throws ApiException {@code GUARD_FAILED HAS_ACCEPTED_WORK} */
	@Override
	public void check(Applying applying) {
		boolean accepted = this.jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM job.milestone WHERE job_id = :job AND removed_at IS NULL
				    AND state IN ('ACCEPTED', 'SETTLED'))
				""").param("job", applying.job().id()).query(Boolean.class).single();
		if (accepted) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.HAS_ACCEPTED_WORK);
		}
	}

	@Override
	public void apply(Applying applying) {
		applying.set("cancelled_at", applying.now());
	}

	@Override
	public void afterWrite(Applying applying) {
		Job job = applying.job();
		this.notifications.send(new NotificationRequest(CANCELLED, applying.transitionId(), J8Close.people(job, this.crews),
				Map.of("job", job.title()), "/cong-viec/" + job.id(), job.id(), null));
		this.contacts.end(job.customerId(), Objects.requireNonNull(job.mentorId()), job.id(), applying.now());
		this.pins.releaseForJob(job.productId(), job.id());
		this.changes.voidWaiting(job.id());
		this.crews.releaseForJobEnd(job.id());
	}

}
