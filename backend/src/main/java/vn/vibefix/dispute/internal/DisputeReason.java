package vn.vibefix.dispute.internal;

/** Why {@code dispute} refused a command, as the {@code reason} of its Problem Details. */
enum DisputeReason {

	/** A Dispute is already open on the Job (FR-52). */
	DISPUTE_ALREADY_OPEN,

	/** Only the Customer or the Job's Mentor opens a Dispute (FR-52). */
	MENTOR_ONLY,

	/** A Dispute is opened on a Job in Agreement, Starting, Active, or Closing (FR-52). */
	JOB_NOT_DISPUTABLE

}
