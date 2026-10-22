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
	NOT_FAILED,

	/** The Crew member already has a Team Issue on the Job that has not ended (FR-79, AD-40). */
	TEAM_ISSUE_OPEN,

	/** One share Team Issue per Milestone and one per Rating, ended or not (FR-79, AD-40). */
	TEAM_ISSUE_ALREADY_RAISED,

	/** A Rating is contested within 14 days of becoming visible (FR-79). */
	TEAM_ISSUE_WINDOW_CLOSED,

	/** The Mentor answers a Team Issue once and cannot revise it (AD-40). */
	ALREADY_ANSWERED,

	/** The answer comes before the answer period's due time (AD-40). */
	ANSWER_PERIOD_ENDED,

	/** "Chấp nhận" and "Nhờ quản trị viên xem xét" are chosen in the escalation period, before its due time (AD-40). */
	ESCALATION_PERIOD_NOT_OPEN,

	/** "Chấp nhận" needs the Mentor's answer (AD-40). */
	NO_ANSWER,

	/** The transfer passes the Mentor's pinned share less what is recorded or offered on the Milestone (AD-40). */
	SHARE_TRANSFER_TOO_LARGE,

	/** The ending could not be executed; Administrators retry it (AD-40, FR-64). */
	ENDING_REFUSED

}
