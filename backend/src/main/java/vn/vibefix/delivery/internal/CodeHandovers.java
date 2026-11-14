package vn.vibefix.delivery.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import vn.vibefix.delivery.internal.DeliveryAccess.Viewer;
import vn.vibefix.delivery.internal.Handovers.Draft;
import vn.vibefix.delivery.internal.Handovers.Pending;
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
 * A Code Job's handover on GitHub (FR-32, AD-11, AD-18, Story 23.8a), outside any transaction: the Working
 * Repository's head read back, its range from the previous Handover Commit (or the commit Setup copied), and
 * the checks the form shows. GitHub's compare lists at most 300 changed files, so what it says about
 * {@code .github/workflows/} is a hint on the form; the copy run itself lists every path with full history and
 * checks the folder with {@code git diff --quiet}, and its report decides (N-16).
 * <p>
 * "Gửi bàn giao" checks again on the live head and keeps the handover pending ({@link HandoverSubmissions}): the
 * runner copies the Handover Commit, and only then is the handover recorded with M2. Nothing is pushed here.
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

	private final HandoverSubmissions submissions;

	private final SensitiveChanges sensitiveChanges;

	private final IdGenerator ids;

	CodeHandovers(HandoverBranches branches, Handovers handovers, HandoverSubmissions submissions,
			SensitiveChanges sensitiveChanges, IdGenerator ids) {
		this.branches = branches;
		this.handovers = handovers;
		this.submissions = submissions;
		this.sensitiveChanges = sensitiveChanges;
		this.ids = ids;
	}

	/** The checks of the next handover of the Job, on its head as GitHub shows it now. */
	Check check(UUID jobId) {
		List<String> problems = new ArrayList<>();
		if (this.submissions.copying(jobId)) {
			// One copy at a time: the Job is busy until the runner's run ends (AD-28).
			return new Check(null, null, null, List.of(), List.of(DeliveryReason.HANDOVER_COPYING.name()));
		}
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
		List<SensitiveChange> sensitive = this.sensitiveChanges.classify(range.paths());
		if (range.paths().stream().anyMatch(SensitiveChanges::workflow)) {
			// The hint from the compare; the copy run's own check is what refuses the handover.
			problems.add(DeliveryReason.WORKFLOW_FILES_CHANGED.name());
		}
		return new Check(head, start, range, sensitive, problems);
	}

	/**
	 * "Gửi bàn giao" for a Code Job: the drafted handover, checked again on the live head, kept pending, and the
	 * copy asked for on the runner. No handover exists until the copy ends.
	 * @return the submission, which is the handover's own ID once it is recorded
	 * @param teamCode the Mentor's N-33 duty confirmation, recorded with the handover (FR-25, R28, Story 23.8b)
	 * @throws ApiException {@code GUARD_FAILED} with the first of the check's problems; and the busy marker's
	 * refusals ({@code JOB_BUSY}, {@code STALE}, {@code CONFLICT JOB_FROZEN})
	 */
	UUID handOver(Viewer mentor, Draft draft, long version, Handovers.@Nullable TeamCodeConfirmation teamCode) {
		UUID jobId = mentor.job().jobId();
		Check check = check(jobId);
		List<String> problems = check.problems();
		if (!problems.isEmpty() || check.head() == null || check.range() == null || check.rangeStart() == null) {
			String reason = problems.isEmpty() ? DeliveryReason.REPOSITORY_UNREADABLE.name() : problems.get(0);
			throw new ApiException(ErrorCode.GUARD_FAILED, DeliveryReason.valueOf(reason));
		}
		Pending pending = Pending.of(mentor, draft, version, this.ids.newId(), teamCode);
		return this.submissions.submit(pending, check.rangeStart(), check.head().headSha(),
				"Bàn giao Giai đoạn " + pending.milestoneNumber() + ": " + pending.milestoneTitle(),
				"Bàn giao " + pending.id() + " của vibefix: lần lưu " + check.head().headSha() + ".");
	}

}
