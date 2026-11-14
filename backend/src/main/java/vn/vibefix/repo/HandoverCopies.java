package vn.vibefix.repo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * A Code Job's handover copy on the runner (FR-32, AD-11, AD-46, Story 23.8a), for {@code delivery}. The Mentor's
 * "Gửi bàn giao" keeps a pending submission and asks here; {@code repo} then runs one External Operation on the
 * GitHub-long lane, with no busy marker (AD-46: only the J7 copy and the Final Merge hold one, and the team keeps
 * working while the runner copies): it records an exchange correlation, dispatches the published copy
 * workflow on the runner through the Platform App, polls the run, reads the run's path report, reads
 * {@code handover/{submissionId}} back at the Handover Commit, and opens the System's pull request into the
 * Intermediary's default branch. When it ends, {@link HandoverCopyFinished} tells {@code delivery}, which records
 * the handover or ends the submission with the reason; nothing is copied in vibefix's sandbox, and no code or path
 * ever reaches a vibefix server's disk.
 */
public interface HandoverCopies {

	/** The branch one submission's commit is pushed to, which becomes the handover's own branch. */
	static String branch(UUID submissionId) {
		return "handover/" + submissionId;
	}

	/**
	 * What to copy.
	 * @param submissionId the pending submission, which is also the handover's ID once it is recorded
	 * @param fromSha where the handover's range starts: the previous Handover Commit, or the commit Setup copied
	 * @param handoverCommit the Handover Commit, the Working default branch's head when the Mentor sent it
	 * @param title the pull request's title
	 * @param body the pull request's body
	 */
	record Request(UUID submissionId, UUID jobId, String fromSha, String handoverCommit, String title, String body) {
	}

	/** Why a copy ended without a handover (FR-32, FR-64). */
	enum Refusal {

		/** The run reported a change under {@code .github/workflows/}, so it pushed nothing (N-16). */
		WORKFLOW_FILES_CHANGED,

		/** The run failed, timed out, or did not read back; the attempts ran out. */
		RUN_FAILED,

		/** The exchange refused the run's ID token, so it got no token. */
		EXCHANGE_REFUSED,

		/** The run finished but its path report is missing or unreadable, so no list can be classified. */
		PATH_LIST_UNREADABLE

	}

	/**
	 * How a copy ended.
	 * @param pullNumber the System's pull request, for a copy that succeeded
	 * @param paths every path the range changed, as the run reported them, for a copy that succeeded;
	 * {@code null} when the report can no longer be read whole, which is no empty list
	 * @param pathCount how many paths the run reported, which {@code paths} holds exactly; 0 without a report
	 */
	record Result(UUID submissionId, UUID jobId, boolean copied, @Nullable Refusal refusal, @Nullable Integer pullNumber,
			@Nullable List<String> paths, int pathCount) {

		public Result {
			paths = paths == null ? null : List.copyOf(paths);
		}

	}

	/**
	 * Requests the copy in the caller's transaction, which holds the pending submission that guards against a
	 * second one. Requesting the same submission again changes nothing.
	 */
	void start(Request request);

	/** How the submission's copy ended, or empty while it is still running or was never asked for. */
	Optional<Result> result(UUID submissionId);

}
