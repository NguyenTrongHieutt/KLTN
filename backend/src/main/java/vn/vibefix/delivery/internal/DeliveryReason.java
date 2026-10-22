package vn.vibefix.delivery.internal;

/** Why {@code delivery} refused a command, as the {@code reason} of its Problem Details. */
enum DeliveryReason {

	/** Only the Job's Mentor hands a Milestone over (FR-32). */
	MENTOR_ONLY,

	/** Only a Funded or Revision Requested Milestone is handed over (FR-32). */
	MILESTONE_NOT_HELD,

	/** A Work Item of the Milestone is not in "Xong" through an approval and has no note (FR-32). */
	WORK_ITEMS_NOT_DONE,

	/** Every Acceptance Criterion of the Milestone needs "Đạt" or "Chưa đạt" and a note (FR-32). */
	CRITERION_MARK_MISSING,

	/** An Assessment Job hands over its Assessment Report, which it does not have yet (FR-10). */
	REPORT_MISSING,

	/** FR-47 found a likely credential or bulk personal data in a note or comment. */
	SENSITIVE_CONTENT,

	/** Only the Job's Customer accepts a Milestone or asks for a revision (FR-33). */
	CUSTOMER_ONLY,

	/** The handover was already accepted or sent back for a revision. */
	HANDOVER_DECIDED,

	/** A revision request names at least one criterion marked "Chưa đạt" (FR-33). */
	REVISION_CRITERIA_MISSING,

	/** Follow-up Jobs come from an Assessment Report the Customer accepted (FR-11). */
	REPORT_NOT_ACCEPTED,

	/** A follow-up cites a numbered recommendation of the accepted Report version (FR-11). */
	RECOMMENDATION_UNKNOWN,

	/** The follow-ups were submitted to the Customer already (FR-11). */
	FOLLOW_UPS_SUBMITTED,

	/** Submitting needs at least one follow-up draft (FR-11). */
	NO_FOLLOW_UPS,

	/** The follow-up is not waiting for the Customer: still a draft, or already opened or discarded. */
	FOLLOW_UP_NOT_AWAITING,

	/** The Product's scans have an open Finding, which blocks a code handover (FR-8, AD-18). */
	FINDING_OPEN,

	/** The Working Repository's head is not yet covered by a completed push scan (FR-8, FR-32). */
	COMMITS_NOT_SCANNED,

	/** No commit after the previous Handover Commit, or after the one Setup copied (FR-32). */
	NO_NEW_COMMITS,

	/** A commit of the handover changes a file under {@code .github/workflows/} (FR-32, AD-18, N-16). */
	WORKFLOW_FILES_CHANGED,

	/** GitHub did not answer while the handover read or pinned the Working Repository; try again shortly. */
	REPOSITORY_UNREADABLE,

	/** The Code Job has no Working Repository yet: J7's Setup is not done (FR-18, FR-32). */
	HANDOVER_COMMIT_MISSING,

	/** A Job Type changes the running Product: the handover needs a preview, or a reason and a recording (FR-32). */
	PREVIEW_REQUIRED,

	/** A handover carries a preview or a recording with its reason, never both (FR-32, AD-35). */
	PREVIEW_OR_RECORDING,

	/** "Không thể làm bản xem thử" needs both its reason and the recording link (FR-32). */
	RECORDING_REASON_MISSING,

	/** The preview link did not respond to the outbound check (FR-32, AD-32, UX-DR27). */
	PREVIEW_UNREACHABLE,

	/** More screenshots than a handover takes, or than may wait for one (FR-32). */
	TOO_MANY_SCREENSHOTS,

	/** A screenshot that is not the Mentor's upload waiting on this Job. */
	SCREENSHOT_UNKNOWN,

	/** The Job's uploads would pass its quota (AD-34). */
	JOB_FILE_QUOTA,

	/** A push after the handover voided it (M3, FR-32): the Customer decides on the next one. */
	HANDOVER_VOIDED

}
