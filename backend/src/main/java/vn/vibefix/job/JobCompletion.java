package vn.vibefix.job;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * J9 for a Code Job (FR-37, FR-38, AD-11): only {@code repo} applies it, in the resolving transaction of
 * the Final Merge, of the Customer's own code receipt, or of the ZIP Fallback, so a Code Job never closes
 * before its code reached the Customer one way or the other.
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

		/** The ZIP of the Intermediary Repository is stored for the Customer (FR-38). */
		ZIP_FALLBACK

	}

	/**
	 * Releases the operation's busy marker when one is named, then applies J9 as the System: to Closed, or
	 * to Cancelled when the Job entered Closing by a cancellation. A refusal writes nothing but the release.
	 * @return whether J9 applied
	 * @throws org.springframework.transaction.IllegalTransactionStateException without a transaction
	 */
	boolean finish(UUID jobId, Finish how, @Nullable String operationKey);

}
