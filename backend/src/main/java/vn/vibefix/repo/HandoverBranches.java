package vn.vibefix.repo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * A Code Job's handover on GitHub (FR-32, AD-11, AD-18, AD-33), for {@code delivery}: the Working
 * Repository's default branch as GitHub shows it now, the commits and paths of a handover's range, and
 * the pin of a Handover Commit to {@code handover/{handoverId}} in the Intermediary Repository with the
 * System's pull request. Each method reads or writes GitHub, so callers run it outside any transaction;
 * GitHub not answering is {@link Unavailable}.
 */
public interface HandoverBranches {

	/**
	 * The Working Repository's default branch now.
	 * @param startSha the commit Setup copied, where the Job's first handover range starts
	 * @param scanned whether a completed push scan covers the head (the copied commit was scanned at J7)
	 */
	record WorkingHead(String headSha, String startSha, String branch, boolean scanned) {
	}

	/**
	 * The head read back from GitHub; a head that moved requests its push scan (FR-8), so it is not
	 * scanned yet. Empty for a Job without a done Setup.
	 */
	Optional<WorkingHead> working(UUID jobId);

	/**
	 * The Working default branch's head as {@code repo} recorded it last, with no GitHub call: for the
	 * 10-minute look at handovers that may be void (FR-32). Empty without a done Setup or a recorded head.
	 */
	Optional<String> recordedHead(UUID jobId);

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
	 * Pushes {@code sha} of the Working default branch to {@code handover/{handoverId}} in the
	 * Intermediary Repository inside the sandbox, never forced, and opens the System's pull request to
	 * the default branch; both are idempotent per handover.
	 * @return the pull request's number
	 */
	int pin(UUID jobId, UUID handoverId, String sha, String title);

	/** Closes the handover's pull request after its M2 failed; a failure here is only logged. */
	void unpin(UUID jobId, int pullNumber);

	/** GitHub did not answer, or refused; the caller asks the person to try again shortly. */
	final class Unavailable extends RuntimeException {

		public Unavailable(String message, Throwable cause) {
			super(message, cause);
		}

	}

}
