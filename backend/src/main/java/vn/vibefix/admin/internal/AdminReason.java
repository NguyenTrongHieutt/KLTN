package vn.vibefix.admin.internal;

/**
 * The {@code reason} of an {@code admin} refusal (AD-23); the SPA reads {@code reason.admin.{REASON}} in
 * {@code vi.json}.
 */
enum AdminReason {

	/** FR-60: the content is hidden already. */
	ALREADY_HIDDEN,

	/** FR-64: the operation is not failed, so there is nothing to retry. */
	NOT_FAILED,

	/** FR-69: the reporter has an open report on this item already. */
	ALREADY_REPORTED,

	/** FR-69, AD-34: the account filed 10 reports today. */
	ABUSE_REPORT_LIMIT,

	/** FR-69: the report is decided already. */
	ALREADY_DECIDED,

	/** FR-69: only the report's assignee decides it. */
	NOT_ASSIGNED,

	/** FR-69: FR-60 does not hide this kind; record the action taken instead. */
	NOT_HIDEABLE,

	/** FR-73: an active Skill already has this name; the detail names it. */
	SKILL_ALREADY_EXISTS,

	/** FR-73, AD-34: the account sent 5 suggestions today. */
	SKILL_SUGGESTION_LIMIT

}
