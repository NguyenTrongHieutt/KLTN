package vn.vibefix.job.internal;

import java.util.UUID;

import org.springframework.stereotype.Component;

import vn.vibefix.job.internal.JobLifecycle.Applying;

/**
 * M8 (FR-54, AD-31): an Administrator's decision settles the Held Milestone with the release and refund
 * totals {@code dispute} records. It records when and the totals, and with a release above 0 creates the
 * Milestone's one split with its parties fixed (AD-29, AD-41); {@code dispute} then has {@code payment} write the
 * RELEASE_POOLED and the refund in the same transaction. No release makes no pool and no split.
 */
@Component
class M8Settle implements JobLifecycle.Step {

	static final String RELEASE = "releaseVnd";

	static final String REFUND = "refundVnd";

	private final M1Fund numbers;

	private final JobMilestoneSplits splits;

	M8Settle(M1Fund numbers, JobMilestoneSplits splits) {
		this.numbers = numbers;
		this.splits = splits;
	}

	@Override
	public Transition transition() {
		return Transition.M8;
	}

	@Override
	public void check(Applying applying) {
		if (!applying.has(RELEASE) || !applying.has(REFUND)) {
			throw new IllegalArgumentException("M8 needs the release and refund totals");
		}
	}

	@Override
	public void apply(Applying applying) {
		applying.set("settled_at", applying.now());
		applying.set("settled_release_vnd", ((Number) applying.input(RELEASE)).longValue());
		applying.set("settled_refund_vnd", ((Number) applying.input(REFUND)).longValue());
		applying.fact("milestoneId", applying.milestoneId().toString());
	}

	@Override
	public void afterWrite(Applying applying) {
		if (((Number) applying.input(RELEASE)).longValue() > 0) {
			UUID jobId = applying.job().id();
			this.splits.createAtRelease(applying.job(), applying.milestoneId(), this.numbers.number(jobId, applying.milestoneId()),
					applying.now());
		}
	}

}
