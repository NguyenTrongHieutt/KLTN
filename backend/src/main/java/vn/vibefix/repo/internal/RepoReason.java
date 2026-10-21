package vn.vibefix.repo.internal;

/** Why {@code repo} refused a command, as the {@code reason} of its Problem Details. */
enum RepoReason {

	/** Data deletion is confirmed once the Job is Closing, Closed, or Cancelled (FR-66). */
	JOB_NOT_ENDED,

	/** Only the Mentor and the Crew confirm data deletion (FR-66). */
	NOT_A_DUTY,

	/** "Thử lại" starts a new Setup only after a block (Story 7.2). */
	SETUP_NOT_BLOCKED,

	/** A Setup runs only while a Code Job is Starting (AD-11). */
	JOB_NOT_STARTING,

	/** A Dispute freezes the Job, so no new Setup starts (AD-28). */
	JOB_FROZEN

}
