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
	RATEE_NOT_ALLOWED,

	/** The Mentor drafts the Portfolio summary (FR-50). */
	MENTOR_ONLY,

	/** The Customer approves, edits, or declines the Portfolio summary (FR-50). */
	CUSTOMER_ONLY,

	/** The Job closed without Accepted work, so it has no Portfolio Entry (FR-50). */
	NO_PORTFOLIO,

	/** The summary is not at that step: already drafted, or already decided (FR-50). */
	SUMMARY_NOT_AWAITING

}
