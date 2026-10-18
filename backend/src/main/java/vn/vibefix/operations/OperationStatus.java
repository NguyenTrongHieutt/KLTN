package vn.vibefix.operations;

/** Where an External Operation stands (AD-6). The last four are completions. */
public enum OperationStatus {

	/** Waiting for its next attempt. */
	PENDING,

	/** Claimed by a worker under a lease. */
	RUNNING,

	/** The effect happened, verified by reading back where the outside system allows it. */
	SUCCEEDED,

	/** A violated precondition outside the platform: retrying cannot help. */
	ABORTED,

	/** The platform precondition no longer held, so the handler did not act. */
	OBSOLETE,

	/** Every attempt failed and the final read-back found no effect; it waits in the Administrator queue (FR-64). */
	FAILED;

	public boolean isCompleted() {
		return this != PENDING && this != RUNNING;
	}

}
