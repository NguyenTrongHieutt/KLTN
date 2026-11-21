package vn.vibefix.operations;

import java.util.Optional;
import java.util.UUID;

/** Requests, looks up and wakes External Operations (AD-6). */
public interface ExternalOperations {

	/**
	 * Records {@code request} in the caller's transaction, so the effect is requested exactly when the
	 * change that causes it commits. An existing key is joined: while it is pending or running the same
	 * operation is returned, once it succeeded its stored result, and after any other completion the
	 * operation as it ended (an Administrator retries it, FR-64).
	 * @throws IllegalArgumentException for a type without an {@link OperationHandler}
	 * @throws IllegalStateException when the key exists with another payload: a payload never changes
	 * @throws org.springframework.transaction.IllegalTransactionStateException without a transaction
	 */
	OperationTicket request(OperationRequest request);

	Optional<OperationTicket> find(String key);

	/**
	 * Wakes one operation that waits for something outside to happen, such as a GitHub Actions run to complete (Story
	 * 24.5a). A pending operation is due now, whatever it deferred to. A wake that arrives while an attempt is running
	 * is not lost: when that attempt defers, its deferral resumes at once, since the attempt may have looked before
	 * what woke it happened. A completed operation is left as it is. It joins the caller's transaction, or runs in one
	 * of its own.
	 * @return whether the operation was pending or running, so the wake took
	 */
	boolean wake(UUID operationId);

}
