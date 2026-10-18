package vn.vibefix.operations;

import java.util.Optional;

/** Requests and looks up External Operations (AD-6). */
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

}
