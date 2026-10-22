package vn.vibefix.delivery.internal;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import vn.vibefix.job.JobBusy;
import vn.vibefix.job.JobBusy.BusyJob;
import vn.vibefix.operations.Lane;
import vn.vibefix.operations.OperationHandler;
import vn.vibefix.operations.OperationRun;
import vn.vibefix.operations.Outcome;
import vn.vibefix.repo.HandoverBranches;
import vn.vibefix.repo.HandoverBranches.Unavailable;
import vn.vibefix.repo.HandoverBranches.WorkingHead;

/**
 * {@value AcceptanceMerges#TYPE} (FR-33, AD-5, AD-11, AD-28): the precondition operation of a Code Job's
 * acceptance, under the {@code ACCEPTANCE} busy marker. It runs only while the marker is its own; a
 * frozen Job waits. It reads the Working head again: one that is no longer the Handover Commit is a
 * precondition violation, resolved as M3. Otherwise it merges the handover's pull request exactly at
 * that commit and reads {@code merged} back; only then does the resolving transaction apply M5 and the
 * release. Collaborators are looked up on use.
 */
@Component
class AcceptanceMergeOperation implements OperationHandler {

	static final Duration FROZEN_WAIT = Duration.ofMinutes(15);

	private final ObjectProvider<JobBusy> busy;

	private final ObjectProvider<HandoverBranches> branches;

	private final ObjectProvider<AcceptanceMerges> merges;

	private final Clock clock;

	AcceptanceMergeOperation(ObjectProvider<JobBusy> busy, ObjectProvider<HandoverBranches> branches,
			ObjectProvider<AcceptanceMerges> merges, Clock clock) {
		this.busy = busy;
		this.branches = branches;
		this.merges = merges;
		this.clock = clock;
	}

	@Override
	public String type() {
		return AcceptanceMerges.TYPE;
	}

	@Override
	public Lane lane() {
		return Lane.GITHUB_SHORT;
	}

	@Override
	public Outcome run(OperationRun run) {
		UUID jobId = UUID.fromString(String.valueOf(run.payload().get("jobId")));
		String commit = String.valueOf(run.payload().get("commit"));
		int pull = ((Number) run.payload().get("pull")).intValue();
		BusyJob job = this.busy.getObject().job(jobId).orElse(null);
		if (job == null || job.busy().filter(marker -> marker.operationKey().equals(run.key())).isEmpty()) {
			return new Outcome.Obsolete("JOB_MOVED_ON");
		}
		if (job.frozen()) {
			return new Outcome.Deferred(this.clock.instant().plus(FROZEN_WAIT));
		}
		HandoverBranches branches = this.branches.getObject();
		try {
			if (!branches.merged(jobId, pull)) {
				String head = branches.working(jobId).map(WorkingHead::headSha).orElse(null);
				if (head == null) {
					return new Outcome.Transient("WORKING_HEAD_UNREAD");
				}
				if (!head.equals(commit)) {
					this.merges.getObject().headMoved(run.key());
					return new Outcome.Aborted("HEAD_MOVED");
				}
				if (!branches.merge(jobId, pull, commit, "Bàn giao Giai đoạn " + run.payload().get("number"))) {
					return new Outcome.Transient("MERGE_NOT_READ_BACK");
				}
			}
		}
		catch (Unavailable unavailable) {
			return new Outcome.Transient("GITHUB_UNAVAILABLE");
		}
		this.merges.getObject().merged(run.key());
		return Outcome.succeeded();
	}

	/** A pull request GitHub shows merged is the effect (AD-5). */
	@Override
	public Optional<Map<String, Object>> readBack(OperationRun run) {
		UUID jobId = UUID.fromString(String.valueOf(run.payload().get("jobId")));
		int pull = ((Number) run.payload().get("pull")).intValue();
		try {
			return this.branches.getObject().merged(jobId, pull) ? Optional.of(Map.of("merged", true)) : Optional.empty();
		}
		catch (Unavailable unavailable) {
			return Optional.empty();
		}
	}

}
