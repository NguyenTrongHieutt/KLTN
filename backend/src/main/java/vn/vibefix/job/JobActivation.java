package vn.vibefix.job;

import java.util.UUID;

/**
 * J7 (FR-18, AD-5, AD-11): only {@code repo} applies it, in the resolving transaction of the operation holding the
 * Job's busy marker. For a Code Job that is Verify, the initiator of the {@code JOB_VERIFY} operation, once every
 * grant was read back as write; for an Assessment Job it is the Setup itself ({@code JOB_SETUP}), once its one
 * private read-only copy read back at the confirmed head, because its read invitations never gate the start
 * (R6, Story 23.11a).
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
