package vn.vibefix.timers;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Schedules Deadlines and freezes a Job's pausable ones (AD-7); every call joins the caller's transaction. */
public interface Deadlines {

	/**
	 * Records the Deadline. A pausable kind whose Job is frozen starts paused, with the time it has
	 * left from now.
	 * @return the Deadline's ID
	 * @throws IllegalArgumentException for a kind no {@link DeadlineHandler} runs, or a job-scoped kind
	 * without a Job
	 * @throws org.springframework.transaction.IllegalTransactionStateException without a transaction
	 */
	UUID schedule(DeadlineRequest request);

	/**
	 * Pauses the Job's pending pausable Deadlines, each keeping {@code remaining = max(0, due − now)};
	 * until {@link #unfreeze}, new pausable Deadlines of the Job start paused. Freezing a frozen Job
	 * changes nothing.
	 */
	void freeze(UUID jobId);

	/**
	 * When the pending Deadline of {@code kind} for {@code subjectId} is due now, such as the
	 * Auto-acceptance date a reminder restates (SG-26); empty when it is paused or finished.
	 */
	Optional<Instant> dueAt(String kind, UUID subjectId);

	/** Resumes the Job's paused Deadlines: each is due {@code now + remaining}. Unfreezing a Job that is not frozen resumes any it has paused. */
	void unfreeze(UUID jobId);

}
