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
	JOB_FROZEN,

	/** No renamed or transferred Source Repository waits for re-confirmation (FR-20). */
	NOTHING_TO_RECONFIRM,

	/** The re-confirmed repository is gone, private, or not the registered one (FR-20, AD-13). */
	SOURCE_NOT_PUBLIC,

	/** The Customer no longer administers the re-confirmed repository (FR-20, AD-13). */
	NO_ADMIN_PERMISSION,

	/** The Customer's kept token is missing or refused: they sign in again first (AD-13). */
	SIGN_IN_AGAIN,

	/** The Job has no open Final Merge authorization to install the Merge App for (FR-37, AD-10). */
	NO_AUTHORIZATION_OPEN,

	/** The Final Merge is not checked and waiting for the Customer's confirmation (FR-37). */
	FINAL_MERGE_NOT_READY,

	/** The Customer already confirmed the Final Merge, so the ZIP can no longer be chosen instead (FR-38). */
	FINAL_MERGE_CONFIRMED

}
