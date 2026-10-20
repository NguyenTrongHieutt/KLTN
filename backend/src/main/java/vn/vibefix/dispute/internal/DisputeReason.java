package vn.vibefix.dispute.internal;

/** Why {@code dispute} refused a command, as the {@code reason} of its Problem Details. */
enum DisputeReason {

	/** A Dispute is already open on the Job (FR-52). */
	DISPUTE_ALREADY_OPEN,

	/** Only the Customer or the Job's Mentor opens a Dispute (FR-52). */
	MENTOR_ONLY,

	/** A Dispute is opened on a Job in Agreement, Starting, Active, or Closing (FR-52). */
	JOB_NOT_DISPUTABLE,

	/** Only the Administrator who took the Dispute decides it (FR-54, AD-20). */
	NOT_ASSIGNED,

	/** The release and refund amounts sum to the releasable amount (FR-54). */
	AMOUNTS_MISMATCH,

	/** "Tiếp tục" needs a Planned Milestone left (FR-54). */
	NOTHING_TO_CONTINUE,

	/** The Dispute has a decision already; decisions are final (FR-54). */
	ALREADY_DECIDED,

	/** Only a failed execution is retried (AD-31). */
	NOT_FAILED

}
