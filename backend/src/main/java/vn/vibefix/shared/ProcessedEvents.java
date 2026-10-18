package vn.vibefix.shared;

import java.util.UUID;

/**
 * Makes a listener replay-safe (AD-9). Publications are delivered at least once: they are
 * republished on restart and resubmitted after failures, so a listener may see an event again.
 * A listener without a natural unique key of its own calls {@link #markProcessed} first, in its own
 * transaction, and does nothing when it returns {@code false}. A failed attempt rolls the marker
 * back with everything else, so the retry applies the event.
 */
public interface ProcessedEvents {

	/**
	 * Records that {@code listener} processed {@code eventId}, in the caller's transaction.
	 * @param listener a stable name of the listener, for example its class and method
	 * @return {@code true} the first time; {@code false} for a replay, which counts as success
	 * @throws org.springframework.transaction.IllegalTransactionStateException without a transaction
	 */
	boolean markProcessed(String listener, UUID eventId);

}
