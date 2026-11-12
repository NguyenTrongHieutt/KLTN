package vn.vibefix.repo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * A Code Job's handover on GitHub (FR-32, FR-33, AD-11, AD-18, AD-33), for {@code delivery}: the Working
 * Repository's default branch as GitHub shows it now, the commits and paths of a handover's range,
 * whether a path changed over it, the pin of a Handover Commit to {@code handover/{handoverId}} in the
 * Intermediary Repository with the System's pull request, its merge at acceptance, and its close after M3 or
 * M4. Heads are compared only at acceptance (D9): the merge names the Handover Commit, and GitHub refusing it
 * with 409 is {@link HeadMoved}. Each method reads or writes GitHub, so callers run it outside any
 * transaction; GitHub not answering is {@link Unavailable}.
 */
public interface HandoverBranches {

	/**
	 * The Working Repository's default branch now.
	 * @param startSha the commit Setup copied, where the Job's first handover range starts
	 */
	record WorkingHead(String headSha, String startSha, String branch) {
	}

	/**
	 * The head read live from GitHub. Empty for a Job without a done Setup.
	 * @throws Unavailable when GitHub does not answer, or shows no default branch
	 */
	Optional<WorkingHead> working(UUID jobId);

	/**
	 * One commit of a range.
	 * @param message the first line of its message
	 * @param authorId the author's GitHub numeric ID, or {@code null} when GitHub resolved no account
	 */
	record Commit(String sha, String message, @Nullable Long authorId) {
	}

	/**
	 * From {@code fromSha} (excluded) to {@code toSha}: the commits, oldest first, and every path they
	 * changed; {@code truncated} when GitHub cut either list.
	 */
	record Range(String fromSha, String toSha, List<Commit> commits, List<String> paths, boolean truncated) {

		public Range {
			commits = List.copyOf(commits);
			paths = List.copyOf(paths);
		}

	}

	Range range(UUID jobId, String fromSha, String toSha);

	/**
	 * Whether {@code path}, a file or a folder of the Working Repository, differs from {@code fromSha} to
	 * {@code toSha}: present at only one of them, or a different object at each. It compares the whole
	 * object, so no cut list of changed paths can hide a change under it (FR-32, AD-18); a path present at
	 * neither commit is unchanged.
	 */
	boolean pathChanged(UUID jobId, String fromSha, String toSha, String path);

	/**
	 * Pushes {@code sha} of the Working default branch to {@code handover/{handoverId}} in the
	 * Intermediary Repository inside the sandbox, never forced, and opens the System's pull request to
	 * the default branch; both are idempotent per handover.
	 * @return the pull request's number
	 */
	int pin(UUID jobId, UUID handoverId, String sha, String title);

	/** Closes the handover's pull request after its M2 failed; a failure here is only logged. */
	void unpin(UUID jobId, int pullNumber);

	/**
	 * Merges the handover's pull request into the Intermediary default branch exactly at {@code sha}
	 * (FR-33, AD-11), with GitHub's {@code sha} precondition, and reads it back.
	 * @return whether GitHub shows it merged
	 * @throws HeadMoved when GitHub answers 409: {@code handover/{handoverId}} no longer holds {@code sha}
	 */
	boolean merge(UUID jobId, int pullNumber, String sha, String title);

	/** Whether the handover's pull request is merged, read back from GitHub. */
	boolean merged(UUID jobId, int pullNumber);

	/**
	 * Closes the pull request of a handover that M3 voided or M4 sent back (FR-33, R26), idempotently: it reads
	 * the pull request first and leaves one GitHub shows merged as it is; otherwise it posts {@code comment} once,
	 * marked for this handover so a retry finds it, then sets the title to the current one plus
	 * {@code " (lần {round})"} and closes it in one PATCH. The {@code handover/{handoverId}} branch is kept.
	 * @return {@code false} when GitHub shows the pull request merged, so nothing was changed
	 */
	boolean close(UUID jobId, UUID handoverId, int pullNumber, int round, String comment);

	/** GitHub did not answer, or refused; the caller asks the person to try again shortly. */
	final class Unavailable extends RuntimeException {

		public Unavailable(String message, Throwable cause) {
			super(message, cause);
		}

	}

	/**
	 * GitHub refused the acceptance merge with 409 (FR-33, D9): the handover branch no longer holds the
	 * Handover Commit, a precondition violation that M3 resolves.
	 */
	final class HeadMoved extends RuntimeException {

		public HeadMoved(String message, Throwable cause) {
			super(message, cause);
		}

	}

}
