package vn.vibefix.timers;

/** What a {@link DeadlineHandler} did with a due Deadline (AD-7). */
public enum DeadlineOutcome {

	/** The rule ran; the Deadline is finished. */
	FIRED,

	/** The generation no longer matches, so nothing ran: the lazy cancellation. */
	OBSOLETE,

	/** The Job is busy; the Deadline is due again at the next poll. */
	REARM,

	/** The Job is frozen; the Deadline pauses with the time it had left when the Job froze. */
	REPAUSE

}
