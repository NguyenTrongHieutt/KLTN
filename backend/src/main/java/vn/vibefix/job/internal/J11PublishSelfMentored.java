package vn.vibefix.job.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import vn.vibefix.job.internal.JobLifecycle.Applying;
import vn.vibefix.job.internal.Jobs.Job;
import vn.vibefix.product.ProductPins;
import vn.vibefix.settings.Rule;
import vn.vibefix.settings.Settings;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;

/**
 * J11 (FR-85, AD-47, AD-4): the Customer publishes a Self-mentored Job, and the Draft goes straight to Agreement. Nobody
 * bids, so there is no Open state, no selection, no NDA and no Job Chat. The Customer is recorded as the Mentor, with
 * the time they became it; the FR-61 rule values are copied as J1 copies them (AD-7); the Product is pinned while the
 * Job runs (AD-30); the Agreement deadline is set and drafting starts with the Customer's to-do (FR-16, FR-40). The Job
 * keeps the version of notice N-34 the Customer read. The facts carry the Mentor, the deadline and that version: {@code chat} opens the Crew
 * Chat alone, {@code matching} opens the recruitment to-do, and the Agreement stall is scheduled, each by its listener.
 */
@Component
class J11PublishSelfMentored implements JobLifecycle.Step {

	/** The version of notice N-34 a publication must name ({@code screen.jobPost.selfMentored.noticeVersion}). */
	static final String NOTICE_VERSION = "1.0";

	private final Settings settings;

	private final ProductPins pins;

	private final Agreements agreements;

	private final Jobs jobs;

	private final boolean selfMentoredJobs;

	J11PublishSelfMentored(Settings settings, ProductPins pins, Agreements agreements, Jobs jobs,
			@Value("${vibefix.features.self-mentored-jobs:false}") boolean selfMentoredJobs) {
		this.settings = settings;
		this.pins = pins;
		this.agreements = agreements;
		this.jobs = jobs;
		this.selfMentoredJobs = selfMentoredJobs;
	}

	@Override
	public Transition transition() {
		return Transition.J11;
	}

	/**
	 * @throws ApiException {@code NOT_FOUND} for anyone but the Job's Customer; {@code GUARD_FAILED SELF_MENTORED_OFF}
	 * with the mode switched off; {@code GUARD_FAILED JOB_MODE_MISMATCH} for a Draft of a normal Job, which J1 publishes;
	 * {@code GUARD_FAILED JOB_INCOMPLETE} with the {@code missing} answers; {@code VALIDATION SELF_MENTORED_NOTICE}
	 * unless the current version of N-34 is named
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
		if (!job.selfMentored()) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.JOB_MODE_MISMATCH);
		}
		List<String> missing = J1Publish.missing(job, LocalDate.ofInstant(applying.now(), J1Publish.VIETNAM));
		if (!missing.isEmpty()) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.JOB_INCOMPLETE, Map.of("missing", missing));
		}
		if (!applying.has("noticeVersion") || !NOTICE_VERSION.equals(applying.input("noticeVersion"))) {
			throw new ApiException(ErrorCode.VALIDATION, JobReason.SELF_MENTORED_NOTICE);
		}
	}

	@Override
	public void apply(Applying applying) {
		Job job = applying.job();
		// The Job's own copy of the rule is written by this same statement, so the period is read from the settings.
		Instant due = applying.now().plus(Duration.ofDays(this.settings.value(Rule.AGREEMENT_STALL_DAYS)));
		applying.setJson("rules", J1Publish.rules(this.settings));
		applying.set("selection_days", J1Publish.SELECTION_DAYS);
		applying.set("mentor_account_id", job.customerId());
		applying.set("selected_at", applying.now());
		applying.set("agreement_due_at", due);
		applying.set("self_mentored_notice_version", NOTICE_VERSION);
		this.pins.pinForJob(job.productId(), job.id());
		applying.fact("mentorId", job.customerId().toString());
		applying.fact("selfMentored", "true");
		applying.fact("noticeVersion", NOTICE_VERSION);
		applying.fact("agreementDueAt", due.toString());
	}

	@Override
	public void afterWrite(Applying applying) {
		// The written row: it now names the Customer as the Mentor, whom the draft's to-do goes to.
		Job written = this.jobs.find(applying.job().customerId(), applying.job().id()).orElseThrow();
		Instant due = Objects.requireNonNull(applying.now())
			.plus(Duration.ofDays(this.settings.value(Rule.AGREEMENT_STALL_DAYS)));
		this.agreements.start(written, due);
	}

}
