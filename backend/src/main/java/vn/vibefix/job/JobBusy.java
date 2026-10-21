package vn.vibefix.job;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * The busy marker (AD-28, AD-5): a precondition operation reserves the Job before its effect runs, and
 * its resolving transaction always releases it. While a Job is busy, every other J or M transition, a
 * Change Request taking effect, and a new Payment Order are refused with {@code JOB_BUSY}; the IPN, a
 * Dispute, chat, and work are not. Reserving and releasing do not change {@code Job.version}.
 */
public interface JobBusy {

	/** Why a precondition operation runs (AD-5). */
	enum Purpose {

		ACCEPTANCE, SETTLEMENT, FINAL_MERGE, JOB_SETUP, JOB_VERIFY

	}

	/**
	 * A set marker.
	 * @param subjectToken what the operation acts on, such as a Handover ID; none for a Setup
	 */
	record Busy(Purpose purpose, String operationKey, @Nullable String subjectToken, Instant since) {
	}

	/**
	 * What an initiator reads of the Job before it reserves or resolves.
	 * @param code a Code Job (not an Assessment alone)
	 * @param frozen an open Dispute freezes it (FR-52)
	 */
	record BusyJob(UUID jobId, UUID productId, UUID customerId, @Nullable UUID mentorId, String title, String state,
			long version, boolean code, boolean frozen, Optional<Busy> busy) {
	}

	Optional<BusyJob> job(UUID jobId);

	/**
	 * Sets the marker in the caller's transaction, under the Job's row lock. Reserving again with the
	 * same key changes nothing.
	 * @throws vn.vibefix.shared.ApiException {@code NOT_FOUND}; {@code STALE} for another version;
	 * {@code STALE STATE_CHANGED} for a state the purpose does not run in; {@code CONFLICT JOB_FROZEN};
	 * {@code JOB_BUSY} for another operation's marker
	 * @throws org.springframework.transaction.IllegalTransactionStateException without a transaction
	 */
	void reserve(UUID jobId, Purpose purpose, @Nullable String subjectToken, long expectedVersion, String operationKey);

	/**
	 * Clears the marker in the resolving transaction, when it is this operation's.
	 * @return whether it cleared it
	 * @throws org.springframework.transaction.IllegalTransactionStateException without a transaction
	 */
	boolean release(UUID jobId, String operationKey);

}
