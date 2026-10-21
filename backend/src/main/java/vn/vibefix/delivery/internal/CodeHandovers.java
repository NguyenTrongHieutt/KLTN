package vn.vibefix.delivery.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import vn.vibefix.delivery.internal.DeliveryAccess.Viewer;
import vn.vibefix.delivery.internal.Handovers.Code;
import vn.vibefix.delivery.internal.Handovers.Draft;
import vn.vibefix.delivery.internal.Handovers.Handover;
import vn.vibefix.repo.HandoverBranches;
import vn.vibefix.repo.HandoverBranches.Range;
import vn.vibefix.repo.HandoverBranches.Unavailable;
import vn.vibefix.repo.HandoverBranches.WorkingHead;
import vn.vibefix.scanning.SensitiveChanges;
import vn.vibefix.scanning.SensitiveChanges.SensitiveChange;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.IdGenerator;

/**
 * A Code Job's handover on GitHub (FR-8, FR-32, AD-11, AD-18), outside any transaction: the Working
 * Repository's head read back, its range from the previous Handover Commit (or the commit Setup copied),
 * and the checks it must pass. "Gửi bàn giao" checks again on the live head, pins that commit through
 * {@code repo}, and only then lets {@link Handovers#record} apply M2; if that fails, the pull request is
 * closed again.
 */
@Component
class CodeHandovers {

	/**
	 * What the Working Repository holds for the next handover now.
	 * @param head {@code null} when it was not read
	 * @param range {@code null} without new commits, or when it was not read
	 * @param problems the refusals, as {@link DeliveryReason} codes
	 */
	record Check(@Nullable WorkingHead head, @Nullable String rangeStart, @Nullable Range range, List<SensitiveChange> sensitive,
			List<String> problems) {
	}

	private final HandoverBranches branches;

	private final Handovers handovers;

	private final SensitiveChanges sensitiveChanges;

	private final IdGenerator ids;

	CodeHandovers(HandoverBranches branches, Handovers handovers, SensitiveChanges sensitiveChanges, IdGenerator ids) {
		this.branches = branches;
		this.handovers = handovers;
		this.sensitiveChanges = sensitiveChanges;
		this.ids = ids;
	}

	/** The checks of the next handover of the Job, on its head as GitHub shows it now. */
	Check check(UUID jobId) {
		List<String> problems = new ArrayList<>();
		WorkingHead head;
		try {
			head = this.branches.working(jobId).orElse(null);
		}
		catch (Unavailable unavailable) {
			return new Check(null, null, null, List.of(), List.of(DeliveryReason.REPOSITORY_UNREADABLE.name()));
		}
		if (head == null) {
			// No done Setup: J7 has not given the Job its repositories yet.
			return new Check(null, null, null, List.of(), List.of(DeliveryReason.HANDOVER_COMMIT_MISSING.name()));
		}
		String start = this.handovers.lastHandoverCommit(jobId).orElse(head.startSha());
		if (head.headSha().equals(start)) {
			return new Check(head, start, null, List.of(), List.of(DeliveryReason.NO_NEW_COMMITS.name()));
		}
		if (!head.scanned()) {
			problems.add(DeliveryReason.COMMITS_NOT_SCANNED.name());
		}
		Range range;
		try {
			range = this.branches.range(jobId, start, head.headSha());
		}
		catch (Unavailable unavailable) {
			problems.add(DeliveryReason.REPOSITORY_UNREADABLE.name());
			return new Check(head, start, null, List.of(), problems);
		}
		if (range.commits().isEmpty()) {
			problems.add(DeliveryReason.NO_NEW_COMMITS.name());
		}
		if (range.paths().stream().anyMatch(SensitiveChanges::workflow)) {
			problems.add(DeliveryReason.WORKFLOW_FILES_CHANGED.name());
		}
		return new Check(head, start, range, this.sensitiveChanges.classify(range.paths()), problems);
	}

	/**
	 * "Gửi bàn giao" for a Code Job: the drafted handover, checked again on the live head, pinned, then
	 * recorded with M2.
	 * @throws ApiException {@code GUARD_FAILED} with the first of the check's problems, or
	 * {@code REPOSITORY_UNREADABLE} when the pin fails; and {@link Handovers#record}'s refusals
	 */
	Handover handOver(Viewer mentor, Draft draft, long version) {
		UUID jobId = mentor.job().jobId();
		Check check = check(jobId);
		List<String> problems = check.problems();
		if (!problems.isEmpty() || check.head() == null || check.range() == null) {
			String reason = problems.isEmpty() ? DeliveryReason.REPOSITORY_UNREADABLE.name() : problems.get(0);
			throw new ApiException(ErrorCode.GUARD_FAILED, DeliveryReason.valueOf(reason));
		}
		UUID id = this.ids.newId();
		String commit = check.head().headSha();
		int pull;
		try {
			pull = this.branches.pin(jobId, id, commit,
					"Bàn giao Giai đoạn " + draft.milestone().number() + ": " + draft.milestone().title());
		}
		catch (Unavailable unavailable) {
			throw new ApiException(ErrorCode.GUARD_FAILED, DeliveryReason.REPOSITORY_UNREADABLE);
		}
		try {
			return this.handovers.record(mentor, draft, version, id, new Code(commit, check.rangeStart(), pull,
					check.range().commits(), check.range().truncated(), check.sensitive()));
		}
		catch (RuntimeException refused) {
			this.branches.unpin(jobId, pull);
			throw refused;
		}
	}

}
