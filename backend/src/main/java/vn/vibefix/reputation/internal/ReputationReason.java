package vn.vibefix.reputation.internal;

/** Why {@code reputation} refused a command, as the {@code reason} of its Problem Details. */
enum ReputationReason {

	/** The Rating Window has not opened yet: it opens when the Job closes (FR-49). */
	RATING_WINDOW_NOT_OPEN,

	/** The Rating Window has ended (FR-49). */
	RATING_WINDOW_CLOSED,

	/** A Rating or a feedback is given once (FR-49, FR-78). */
	ALREADY_SUBMITTED,

	/** Crew members give private feedback; they do not rate (FR-49). */
	NOT_A_RATER,

	/** The Customer rates the Mentor; the Mentor rates the Customer and each Crew member (FR-49). */
	RATEE_NOT_ALLOWED

}
