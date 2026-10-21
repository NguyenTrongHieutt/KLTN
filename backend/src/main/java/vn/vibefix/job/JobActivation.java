package vn.vibefix.job;

import java.util.UUID;

/**
 * J7 for a Code Job (FR-18, AD-5, AD-11): only {@code repo}'s Verify, the initiator of the
 * {@code JOB_VERIFY} operation, applies it, in its resolving transaction, once every grant was read back
 * as write.
 */
public interface JobActivation {

	/**
	 * Releases the operation's busy marker, then applies J7 as the System with the access vouched for.
	 * A refusal (the Job moved on) writes nothing but the release.
	 * @return whether J7 applied
	 * @throws org.springframework.transaction.IllegalTransactionStateException without a transaction
	 */
	boolean activate(UUID jobId, String operationKey);

}
