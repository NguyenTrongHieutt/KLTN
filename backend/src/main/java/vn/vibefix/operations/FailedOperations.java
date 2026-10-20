package vn.vibefix.operations;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Operations that ended {@link OperationStatus#FAILED} (AD-6, FR-64). They wait in ADM-7's queue,
 * where an Administrator may retry one; the {@code operations.operation_failed} alert reaches
 * Administrators with the notifications.
 */
public interface FailedOperations {

	/**
	 * One failed operation; it holds no payload.
	 * @param jobId the Job its payload names, when it names one
	 */
	record FailedOperation(UUID operationId, String key, String type, String errorCode, int attempts,
			Instant failedAt, @Nullable UUID jobId) {
	}

	/** Every failed operation, oldest first. */
	List<FailedOperation> findAll();

	/**
	 * FR-64, AD-6: runs a failed operation again from its first attempt. Its first step reads back
	 * whether the effect already happened, so an applied effect is never repeated; a transition it
	 * would cause is still refused while a Dispute freezes the Job.
	 * @return whether it was failed and is pending again
	 */
	boolean retry(UUID operationId);

}
