package vn.vibefix.dispute;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The Disputes awaiting a decision, for ADM-1's queue count (UX-DR69). */
public interface DisputeQueue {

	record Waiting(UUID disputeId, Instant openedAt) {
	}

	/** The open Disputes without a decision, the oldest first. */
	List<Waiting> awaiting();

}
