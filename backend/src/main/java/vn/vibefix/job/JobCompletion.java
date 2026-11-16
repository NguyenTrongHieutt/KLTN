package vn.vibefix.job;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * J9 for a Code Job (FR-37, FR-38, AD-11): only {@code repo} applies it, in the resolving transaction of
 * the Final Merge or of the Customer's own code receipt, so a Code Job never closes before its code reached the
 * Customer one way or the other.
 * <p>
 * Story 23.10b (FR-38, D10) adds the one ending that is not a receiving: the closing choice's deadline passes with
 * neither path taken, and the Job ends {@link Finish#UNCONFIRMED} with the Customer still free to fetch the code
 * from the Intermediary Repository until it is deleted.
 */
public interface JobCompletion {

	/** How the Job's code reached the Customer. */
	enum Finish {

		/** The Final Merge was read back merged (FR-37). */
		MERGED,

		/**
		 * The Customer confirmed they have the finished code, which they downloaded or cloned from the
		 * Intermediary Repository themselves: the closing choice's second path (FR-38, D10, Story 23.10a).
		 */
		SELF_DOWNLOAD,

		/**
		 * The closing choice's deadline passed with neither path taken (FR-38, D10, Story 23.10b): the Job ends
		 * anyway, and "khách chưa xác nhận đã nhận code" is what it records. The code has not reached the Customer
		 * as far as vibefix knows, so they keep their read of the Intermediary Repository, the to-do
		 * {@code repo.download_before_deletion}, and the right to confirm the receipt late, until the repositories
		 * are deleted.
		 */
		UNCONFIRMED

	}

	/**
	 * Releases the operation's busy marker when one is named, then applies J9 as the System: to Closed, or
	 * to Cancelled when the Job entered Closing by a cancellation. A refusal writes nothing but the release.
	 * @return whether J9 applied
	 * @throws org.springframework.transaction.IllegalTransactionStateException without a transaction
	 */
	boolean finish(UUID jobId, Finish how, @Nullable String operationKey);

}
