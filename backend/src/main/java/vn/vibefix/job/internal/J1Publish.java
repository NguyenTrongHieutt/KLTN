package vn.vibefix.job.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.UUID;

import org.springframework.stereotype.Component;

import vn.vibefix.job.internal.JobLifecycle.Applying;
import vn.vibefix.job.internal.Jobs.Job;
import vn.vibefix.scanning.Scans;
import vn.vibefix.settings.Rule;
import vn.vibefix.settings.Settings;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.timers.DeadlineRequest;
import vn.vibefix.timers.Deadlines;

/**
 * J1 (FR-9): the Customer publishes a complete Draft as an Open Job Post. A Code Job waits until the
 * Product has no open Intake Finding; an Assessment Job publishes with open Findings (FR-8). The FR-61
 * rule values and the selection period are copied onto the Job, so a later settings change never
 * alters it (AD-7), and the selection lapse is scheduled for the end of the period.
 */
@Component
class J1Publish implements JobLifecycle.Step {

	/** The selection period of a Job Post (FR-9, addendum B2), fixed rather than an FR-61 setting. */
	static final int SELECTION_DAYS = 14;

	static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

	private final Scans scans;

	private final Settings settings;

	private final Deadlines deadlines;

	J1Publish(Scans scans, Settings settings, Deadlines deadlines) {
		this.scans = scans;
		this.settings = settings;
		this.deadlines = deadlines;
	}

	@Override
	public Transition transition() {
		return Transition.J1;
	}

	/**
	 * @throws ApiException {@code NOT_FOUND} for anyone but the Job's Customer; {@code GUARD_FAILED
	 * JOB_INCOMPLETE} with the {@code missing} answers; {@code GUARD_FAILED INTAKE_FINDING_OPEN} with
	 * the open {@code count}
	 */
	@Override
	public void check(Applying applying) {
		Job job = applying.job();
		if (!applying.actor().is(job.customerId())) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		List<String> missing = missing(job, LocalDate.ofInstant(applying.now(), VIETNAM));
		if (!missing.isEmpty()) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.JOB_INCOMPLETE, Map.of("missing", missing));
		}
		if (job.code()) {
			int open = this.scans.openFindingCounts(List.of(job.productId())).getOrDefault(job.productId(), 0);
			if (open > 0) {
				throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.INTAKE_FINDING_OPEN, Map.of("count", open));
			}
		}
	}

	@Override
	public void apply(Applying applying) {
		Instant lapsesAt = applying.now().plus(Duration.ofDays(SELECTION_DAYS));
		applying.setJson("rules", rules());
		applying.set("selection_days", SELECTION_DAYS);
		applying.set("opened_at", applying.now());
		applying.set("lapses_at", lapsesAt);
		UUID jobId = applying.job().id();
		this.deadlines.schedule(new DeadlineRequest(JobSelectionLapse.KEY, jobId, String.valueOf(applying.version()),
				jobId, lapsesAt));
		applying.fact("lapsesAt", lapsesAt.toString());
	}

	/** The answers publishing needs that the Draft lacks, in CUS-6's order (FR-9). */
	static List<String> missing(Job job, LocalDate today) {
		List<String> missing = new ArrayList<>();
		if (job.problem() == null) {
			missing.add("problem");
		}
		if (job.expected() == null) {
			missing.add("expected");
		}
		if (job.appUsers() == null) {
			missing.add("appUsers");
		}
		if (job.jobTypes().isEmpty()) {
			missing.add("jobTypes");
		}
		if (job.budget() == null) {
			missing.add("budget");
		}
		if (job.deadline() == null || !job.deadline().isAfter(today)) {
			missing.add("deadline");
		}
		return missing;
	}

	/** Every FR-61 rule value as it stands now, as a JSON object keyed by the rule's name. */
	private String rules() {
		StringJoiner json = new StringJoiner(",", "{", "}");
		for (Rule rule : Rule.values()) {
			json.add("\"" + rule.name() + "\":" + this.settings.value(rule));
		}
		return json.toString();
	}

}
