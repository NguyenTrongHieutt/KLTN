package vn.vibefix.job.internal;

import org.springframework.stereotype.Component;

import vn.vibefix.job.internal.JobLifecycle.Applying;

/**
 * M8 (FR-54, AD-31): an Administrator's decision settles the Held Milestone with the release and refund
 * totals {@code dispute} records; {@code payment} writes the entries in the same transaction. It records
 * when and the totals.
 */
@Component
class M8Settle implements JobLifecycle.Step {

	static final String RELEASE = "releaseVnd";

	static final String REFUND = "refundVnd";

	private final CrewChanges changes;

	M8Settle(CrewChanges changes) {
		this.changes = changes;
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

	/** Story 11.3, AD-41: the Milestone left Held, so a waiting Crew change may take effect now. */
	@Override
	public void afterWrite(Applying applying) {
		this.changes.evaluate(applying.job().id(), false);
	}

}
