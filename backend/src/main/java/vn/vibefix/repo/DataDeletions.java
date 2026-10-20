package vn.vibefix.repo;

import java.util.UUID;

/**
 * Whether a person confirmed deleting the Customer's data after a Job (FR-50, FR-66): {@code reputation}
 * asks before it publishes their Portfolio Entry. Closure never waits for it.
 */
public interface DataDeletions {

	/** Whether {@code accountId} confirmed every item for the Job. */
	boolean confirmed(UUID jobId, UUID accountId);

}
