package vn.vibefix.job.internal;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntSupplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.vibefix.job.internal.JobLifecycle.Applying;
import vn.vibefix.job.internal.Jobs.Job;
import vn.vibefix.product.ProductPins;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;

/**
 * J12 (FR-85, AD-47, AD-4): the Customer switches an Open Job nobody bids on to self-mentored, and it goes straight to
 * Agreement. From there it is J11's Job: the Customer is recorded as the Mentor, with the time they became it; the
 * Product is pinned while the Job runs (AD-30); the Agreement deadline is set and drafting starts with the Customer's
 * to-do (FR-16, FR-40). Unlike J11 it copies no rule value: the Job keeps the ones J1 copied (AD-7), and the deadline is
 * counted from them. The selection period ends by itself, because its Deadline holds the version J1 left and finds the
 * Job no longer Open. {@code matching} owns the Applications, so it counts the open ones for the guard and its listener
 * lapses the Invitations to Apply and withdraws a bid that raced the switch (B-18). The switch is one-way: no transition
 * leads back to Open, and no command writes the mode after it.
 */
@Component
class J12SwitchToSelfMentored implements JobLifecycle.Step {

	/** The input that counts the Job's open Applications, read under the Job's lock: an {@link IntSupplier}. */
	static final String OPEN_APPLICATIONS = "openApplications";

	private final ProductPins pins;

	private final Agreements agreements;

	private final Jobs jobs;

	private final JdbcClient jdbc;

	private final boolean selfMentoredJobs;

	J12SwitchToSelfMentored(ProductPins pins, Agreements agreements, Jobs jobs, JdbcClient jdbc,
			@Value("${vibefix.features.self-mentored-jobs:false}") boolean selfMentoredJobs) {
		this.pins = pins;
		this.agreements = agreements;
		this.jobs = jobs;
		this.jdbc = jdbc;
		this.selfMentoredJobs = selfMentoredJobs;
	}

	@Override
	public Transition transition() {
		return Transition.J12;
	}

	/**
	 * @throws ApiException {@code NOT_FOUND} for anyone but the Job's Customer; {@code GUARD_FAILED SELF_MENTORED_OFF}
	 * with the mode switched off; {@code GUARD_FAILED OPEN_APPLICATIONS} with their {@code count} while a bid is open;
	 * {@code VALIDATION SELF_MENTORED_NOTICE} unless the current version of N-34 is named
	 */
	@Override
	public void check(Applying applying) {
		Job job = applying.job();
		if (!applying.actor().is(job.customerId())) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		if (!this.selfMentoredJobs) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.SELF_MENTORED_OFF);
		}
		int open = ((IntSupplier) applying.input(OPEN_APPLICATIONS)).getAsInt();
		if (open > 0) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.OPEN_APPLICATIONS, Map.of("count", open));
		}
		if (!applying.has("noticeVersion")
				|| !J11PublishSelfMentored.NOTICE_VERSION.equals(applying.input("noticeVersion"))) {
			throw new ApiException(ErrorCode.VALIDATION, JobReason.SELF_MENTORED_NOTICE);
		}
	}

	@Override
	public void apply(Applying applying) {
		Job job = applying.job();
		// The Job keeps the rule values J1 copied, so the period is the one it was published with (AD-7).
		Instant due = applying.now().plus(StallDeadlines.period(this.jdbc, job.id(), JobState.AGREEMENT));
		applying.set("self_mentored", true);
		applying.set("mentor_account_id", job.customerId());
		applying.set("selected_at", applying.now());
		// A bid selected before (J3, then J5) is no quote of this Agreement, and nobody is selected here.
		applying.set("selected_application_id", null);
		applying.set("quote_price_vnd", null);
		applying.set("quote_timeline_days", null);
		applying.set("agreement_due_at", due);
		applying.set("self_mentored_notice_version", J11PublishSelfMentored.NOTICE_VERSION);
		this.pins.pinForJob(job.productId(), job.id());
		applying.fact("mentorId", job.customerId().toString());
		applying.fact("selfMentored", "true");
		applying.fact("noticeVersion", J11PublishSelfMentored.NOTICE_VERSION);
		applying.fact("agreementDueAt", due.toString());
	}

	@Override
	public void afterWrite(Applying applying) {
		// The written row: it now names the Customer as the Mentor, whom the draft's to-do goes to.
		Job written = this.jobs.find(applying.job().customerId(), applying.job().id()).orElseThrow();
		this.agreements.start(written, Instant.parse(Objects.requireNonNull(applying.factOf("agreementDueAt"))));
	}

}
