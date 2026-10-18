package vn.vibefix.operations;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Operations that ended {@link OperationStatus#FAILED} (AD-6, FR-64). They wait in the Administrator
 * queue, whose screen and retry arrive with their own story; the {@code operations.operation_failed}
 * alert reaches Administrators with the notifications.
 */
public interface FailedOperations {

	/** One failed operation; it holds no payload. */
	record FailedOperation(UUID operationId, String key, String type, String errorCode, int attempts,
			Instant failedAt) {
	}

	/** Every failed operation, oldest first. */
	List<FailedOperation> findAll();

}
