package vn.vibefix.dispute.internal;

import java.time.Duration;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.dispute.internal.SplitResolutions.Resolution;
import vn.vibefix.job.MilestoneSplits;
import vn.vibefix.job.MilestoneSplits.SplitCase;
import vn.vibefix.timers.DeadlineHandler;
import vn.vibefix.timers.DeadlineKind;
import vn.vibefix.timers.DeadlineOutcome;
import vn.vibefix.timers.DueDeadline;

/**
 * {@value #KEY} (FR-84, AD-7, AD-40): a Split Resolution still undecided when its copied days pass alerts every Administrator;
 * the filter reads the same stored due time. Non-pausable, its generation the case: once the case ended it is {@code OBSOLETE},
 * and it has no other ending, since an undecided case simply stays open. Collaborators are looked up on use, since
 * {@code timers} collects its handlers first.
 */
@Component
class SplitResolutionUndecidedDeadline implements DeadlineHandler {

	static final String KEY = "dispute.split_resolution_undecided";

	static final DeadlineKind KIND = new DeadlineKind(KEY, false, true);

	private final ObjectProvider<SplitResolutions> resolutions;

	private final ObjectProvider<MilestoneSplits> splits;

	private final ObjectProvider<AuditLog> auditLog;

	SplitResolutionUndecidedDeadline(ObjectProvider<SplitResolutions> resolutions, ObjectProvider<MilestoneSplits> splits,
			ObjectProvider<AuditLog> auditLog) {
		this.resolutions = resolutions;
		this.splits = splits;
		this.auditLog = auditLog;
	}

	@Override
	public DeadlineKind kind() {
		return KIND;
	}

	@Override
	@Transactional
	public DeadlineOutcome handle(DueDeadline deadline) {
		SplitResolutions cases = this.resolutions.getObject();
		if (cases.find(deadline.subjectId()).isEmpty()) {
			return DeadlineOutcome.OBSOLETE;
		}
		Resolution resolution = cases.lockedResolution(deadline.subjectId());
		if (!resolution.open() || !deadline.generation().equals(resolution.id().toString())) {
			return DeadlineOutcome.OBSOLETE;
		}
		SplitCase split = this.splits.getObject().caseOf(resolution.splitId()).orElse(null);
		cases.tellAdministrators(SplitResolutions.UNDECIDED, resolution.id(),
				Map.of("n", String.valueOf(resolution.milestoneNumber()), "job", split == null ? "" : SplitResolutions.title(split),
						"days", String.valueOf(Duration.between(resolution.raisedAt(), resolution.undecidedDueAt()).toDays())));
		this.auditLog.getObject()
			.record(AuditEntry
				.of(AuditActor.system(), "dispute.split_resolution_undecided",
						AuditObject.of("dispute.split_resolution", resolution.id()))
				.forJob(resolution.jobId()));
		return DeadlineOutcome.FIRED;
	}

}
