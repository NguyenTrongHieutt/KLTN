package vn.vibefix.job.internal;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.vibefix.identity.Capabilities;
import vn.vibefix.identity.Capabilities.Held;
import vn.vibefix.job.JobSelection;
import vn.vibefix.job.JobSelection.SelectedQuote;
import vn.vibefix.job.MentorWork;
import vn.vibefix.job.internal.JobLifecycle.Applying;
import vn.vibefix.job.internal.Jobs.Job;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.product.ProductPins;
import vn.vibefix.shared.AdvisoryLocks;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;

/**
 * J3 (FR-15, AD-30, SG-52): the Customer selects a bid. Under the Job row lock, one role per Job comes first
 * (AD-43): the bidder is neither the Customer nor on the Job's Crew. FR-13's guard then runs again under the
 * bidder's advisory lock, after the Job row (AD-4 lock order): the bidder still has GitHub linked and is not
 * locked ({@code MENTOR_NOT_AVAILABLE}, read now through {@link Capabilities}), and is below the live Job limit. No
 * free Student and no approval is asked for (Story 21.2). The Job records the Mentor, which never changes, pins its
 * Product, and tells the Mentor; {@code matching} puts the other bids on hold.
 */
@Component
class J3Select implements JobLifecycle.Step {

	static final String SELECTED = "job.assignee_selected";

	private final Capabilities capabilities;

	private final MentorWork work;

	private final CrewService crews;

	private final ProductPins pins;

	private final Notifications notifications;

	private final JdbcClient jdbc;

	J3Select(Capabilities capabilities, MentorWork work, CrewService crews, ProductPins pins, Notifications notifications,
			JdbcClient jdbc) {
		this.capabilities = capabilities;
		this.work = work;
		this.crews = crews;
		this.pins = pins;
		this.notifications = notifications;
		this.jdbc = jdbc;
	}

	@Override
	public Transition transition() {
		return Transition.J3;
	}

	/**
	 * @throws ApiException {@code NOT_FOUND} for anyone but the Job's Customer; {@code GUARD_FAILED ROLE_TAKEN}
	 * for a Mentor who is the Customer or has a counted Crew row on the Job, before any other guard
	 */
	@Override
	public void check(Applying applying) {
		if (!applying.actor().is(applying.job().customerId())) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		UUID mentor = mentor(applying);
		if (mentor.equals(applying.job().customerId()) || this.crews.countedRow(applying.job().id(), mentor)) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.ROLE_TAKEN);
		}
		AdvisoryLocks.lockAccount(this.jdbc, mentor);
		Held held = this.capabilities.capabilities(mentor);
		if (!held.githubVerified() || held.locked()) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.MENTOR_NOT_AVAILABLE);
		}
		if (this.work.activeJobCount(mentor) >= this.work.activeJobLimit()) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.MENTOR_AT_JOB_LIMIT);
		}
	}

	@Override
	public void apply(Applying applying) {
		UUID application = (UUID) applying.input("applicationId");
		Instant ndaDueAt = applying.now().plus(JobSelection.NDA_PERIOD);
		applying.set("mentor_account_id", mentor(applying));
		applying.set("selected_application_id", application);
		applying.set("selected_at", applying.now());
		SelectedQuote quote = (SelectedQuote) applying.input("quote");
		applying.set("quote_price_vnd", quote.priceVnd());
		applying.set("quote_timeline_days", quote.timelineDays());
		this.pins.pinForJob(applying.job().productId(), applying.job().id());
		applying.fact("mentorId", mentor(applying).toString());
		applying.fact("applicationId", application.toString());
		applying.fact("ndaDueAt", ndaDueAt.toString());
	}

	/** The Mentor learns of the selection once the Job names them, so the recipient lookup confirms it. */
	@Override
	public void afterWrite(Applying applying) {
		Job job = applying.job();
		this.notifications.send(new NotificationRequest(SELECTED, applying.transitionId(),
				List.of(new Recipient(mentor(applying), Audience.MENTOR)), Map.of("job", job.title()),
				"/cong-viec/" + job.id(), job.id(), null));
	}

	private static UUID mentor(Applying applying) {
		return (UUID) applying.input("mentorId");
	}

}
