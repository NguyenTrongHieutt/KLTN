package vn.vibefix.delivery.internal;

/** Why {@code delivery} refused a command, as the {@code reason} of its Problem Details. */
enum DeliveryReason {

	/** Only the Job's Mentor hands a Milestone over (FR-32). */
	MENTOR_ONLY,

	/**
	 * A handover being copied on the runner is the team's business: the Mentor and the active Crew read it, and the
	 * Customer hears nothing until its pull request is open (FR-32, UX-DR115, Story 23.8b).
	 */
	TEAM_ONLY,

	/**
	 * A Code Job's handover names N-33's current version, the Mentor's duty confirmation that its commits hold no
	 * credential and no real data (FR-25, R28, Story 23.8b): another version means the text was reworded, so the
	 * Mentor reads it again.
	 */
	NOTICE_REQUIRED,

	/** Only a Funded or Revision Requested Milestone is handed over (FR-32). */
	MILESTONE_NOT_HELD,

	/** Every Acceptance Criterion of the Milestone needs "Đạt" or "Chưa đạt" and a note (FR-32). */
	CRITERION_MARK_MISSING,

	/** Only the Job's Customer accepts a Milestone or asks for a revision (FR-33). */
	CUSTOMER_ONLY,

	/** The handover was already accepted or sent back for a revision. */
	HANDOVER_DECIDED,

	/** A revision request names at least one criterion marked "Chưa đạt" (FR-33). */
	REVISION_CRITERIA_MISSING,

	/** Follow-up Jobs come from an Assessment Job's handover the Customer accepted (FR-11). */
	REPORT_NOT_ACCEPTED,

	/** The follow-ups were submitted to the Customer already (FR-11). */
	FOLLOW_UPS_SUBMITTED,

	/** Submitting needs at least one follow-up draft (FR-11). */
	NO_FOLLOW_UPS,

	/** The follow-up is not waiting for the Customer: still a draft, or already opened or discarded. */
	FOLLOW_UP_NOT_AWAITING,

	/** No commit after the previous Handover Commit, or after the one Setup copied (FR-32). */
	NO_NEW_COMMITS,

	/**
	 * The {@code .github/workflows} folder differs between the range's two ends: added, removed, or changed. The
	 * form reads GitHub's compare, a hint capped at 300 files; the copy run's own {@code git diff --quiet} over
	 * the full history decides, and its report refuses the handover (FR-32, AD-18, N-16, Story 23.8a).
	 */
	WORKFLOW_FILES_CHANGED,

	/** A handover of this Job is being copied onto {@code handover/{id}}; its copy finishes first (Story 23.8a). */
	HANDOVER_COPYING,

	/** The copy run on the runner failed, timed out, or did not read back after its attempts (FR-64, AD-46). */
	RUN_FAILED,

	/** vibefix's Actions token exchange refused the copy run's ID token, so it copied nothing (AD-44). */
	EXCHANGE_REFUSED,

	/**
	 * The copy run reported no readable list of changed paths, so the Sensitive Changes cannot be classified; a
	 * handover is never recorded from an incomplete list (FR-32).
	 */
	PATH_LIST_UNREADABLE,

	/** GitHub did not answer while the handover read the Working Repository; try again shortly. */
	REPOSITORY_UNREADABLE,

	/** The Code Job has no Working Repository yet: J7's Setup is not done (FR-18, FR-32). */
	HANDOVER_COMMIT_MISSING,

	/**
	 * A Job Type changes the running Product: the handover needs a preview, or "Không thể làm bản xem thử" with its
	 * reason and a "Bản ghi / video" link (FR-32).
	 */
	PREVIEW_REQUIRED,

	/**
	 * A handover carries a preview or the reason a preview is impossible, never both (FR-32, AD-35). Links of either
	 * kind may sit beside a preview; the name is kept from when the recording had a field of its own (Story 24.3).
	 */
	PREVIEW_OR_RECORDING,

	/**
	 * "Không thể làm bản xem thử" needs its reason and at least one "Bản ghi / video" link, which shows the change
	 * instead (FR-32, Story 24.3).
	 */
	RECORDING_REASON_MISSING,

	/** A handover carries at most 10 links (D3, FR-32, Story 24.3). */
	TOO_MANY_LINKS,

	/**
	 * An Assessment Job's handover carries at least one "Tài liệu" link: the platform stores no documents, so that link
	 * is how its report reaches the Customer (FR-10, Story 24.3).
	 */
	REPORT_LINK_MISSING,

	/** The preview link did not respond to the outbound check (FR-32, AD-32, UX-DR27). */
	PREVIEW_UNREACHABLE,

	/** More images than a handover takes (5), or than may wait for one (FR-32). */
	TOO_MANY_SCREENSHOTS,

	/** A screenshot that is not the Mentor's upload waiting on this Job. */
	SCREENSHOT_UNKNOWN,

	/** The Job's uploads would pass its quota (AD-34). */
	JOB_FILE_QUOTA,

	/**
	 * The acceptance merge answered 409, so M3 voided the handover (FR-33, D9): the Customer decides on the next
	 * one.
	 */
	HANDOVER_VOIDED,

	/**
	 * The Customer of a Self-mentored Job already returned this handover (FR-86): it is void, and the members hand over
	 * again.
	 */
	HANDOVER_RETURNED

}
