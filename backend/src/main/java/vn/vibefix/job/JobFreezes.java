package vn.vibefix.job;

import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * The freeze an open Dispute puts on a Job (FR-52, §4.0 freeze rule, AD-28): M1 to M7 and new Payment
 * Orders refuse with {@code JOB_FROZEN} while it holds. {@code dispute} freezes in the opening transaction
 * and unfreezes once its decision is executed (Story 9.5); the caller pauses the Deadlines itself.
 * {@code delivery}'s and {@code repo}'s pausable Deadline handlers call only {@link #lock}, first, to decide
 * "frozen" under the Job row lock (AD-4).
 */
public interface JobFreezes {

	/** The Job as a Dispute or a pausable Deadline's handler reads it, under its row lock (AD-4). */
	record DisputedJob(UUID jobId, UUID productId, UUID customerId, @Nullable UUID mentorId, String title, String state,
			boolean frozen, @Nullable UUID heldMilestoneId) {
	}

	/** Locks the Job row first, so {@code frozen} holds until the caller's transaction ends; empty for an unknown Job. */
	Optional<DisputedJob> lock(UUID jobId);

	/** Freezes the Job; a frozen Job stays as it is. */
	void freeze(UUID jobId);

	/** Lifts the freeze. */
	void unfreeze(UUID jobId);

}
