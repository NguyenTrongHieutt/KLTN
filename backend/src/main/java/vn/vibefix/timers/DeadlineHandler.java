package vn.vibefix.timers;

/**
 * Runs one kind of Deadline (AD-3 port); the owning module implements it. {@link #handle} runs in the
 * transaction that claimed the row, so its effects and the outcome commit together; an exception rolls
 * both back, and the Deadline is tried again at a later poll. The handler of a pausable kind first takes
 * the Job row lock and, on a frozen Job, answers {@link DeadlineOutcome#REPAUSE} before anything else
 * (AD-4): the freeze skips a row the poller holds, so only the handler can pause it.
 */
public interface DeadlineHandler {

	DeadlineKind kind();

	DeadlineOutcome handle(DueDeadline deadline);

}
