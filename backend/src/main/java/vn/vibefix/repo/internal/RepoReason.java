package vn.vibefix.repo.internal;

/** Why {@code repo} refused a command, as the {@code reason} of its Problem Details. */
enum RepoReason {

	/** Data deletion is confirmed once the Job is Closing, Closed, or Cancelled (FR-66). */
	JOB_NOT_ENDED,

	/** Only the Mentor and the Crew confirm data deletion (FR-66). */
	NOT_A_DUTY

}
