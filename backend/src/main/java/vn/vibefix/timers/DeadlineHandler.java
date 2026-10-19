package vn.vibefix.timers;

/**
 * Runs one kind of Deadline (AD-3 port); the owning module implements it. {@link #handle} runs in the
 * transaction that claimed the row, so its effects and the outcome commit together; an exception rolls
 * both back, and the Deadline is tried again at a later poll.
 */
public interface DeadlineHandler {

	DeadlineKind kind();

	DeadlineOutcome handle(DueDeadline deadline);

}
