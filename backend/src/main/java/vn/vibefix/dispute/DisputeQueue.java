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

	/** A decision whose execution stopped at {@code step} (FR-54, FR-64); ADM-4 retries it. */
	record FailedExecution(UUID disputeId, UUID jobId, int step, String failure, Instant failedAt) {
	}

	/** The executions that failed, the oldest first. */
	List<FailedExecution> executionsFailed();

	/** The escalated Team Issues waiting for an Administrator (FR-79, AD-40), by escalation time, the oldest first. */
	List<Waiting> teamIssuesEscalated();

}
