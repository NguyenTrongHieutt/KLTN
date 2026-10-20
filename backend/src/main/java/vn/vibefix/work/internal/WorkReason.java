package vn.vibefix.work.internal;

/** Why {@code work} refused a command, as the {@code reason} of its Problem Details. */
enum WorkReason {

	/** Only the Job's Mentor sets the assignee or the priority (AD-39). */
	MENTOR_ONLY,

	/** The assignee must be an active Crew member of this Job with an unlocked account (AD-39, AD-41). */
	NOT_CREW_MEMBER,

	/** A seeded Story's type and Milestone never change (AD-33). */
	SEEDED_FIELD_FIXED,

	/** The Milestone is not one of the Agreement in force. */
	MILESTONE_UNKNOWN,

	/** The Job is no longer Active, so its Board is read-only. */
	BOARD_READ_ONLY,

	/** FR-47 found a likely credential or bulk personal data; the dialog lists the masked matches. */
	SENSITIVE_CONTENT,

	/** Only the Mentor and the Work Item's assignee move it (AD-39). */
	NOT_ASSIGNEE,

	/** A Work Item leaves "Cần làm" only while its Milestone is held (FR-24). */
	MILESTONE_NOT_HELD,

	/** The move is not one of the Board's: submission, approval, and return have their own commands (AD-39). */
	MOVE_NOT_ALLOWED,

	/** A submission answers all four Self-check items (FR-25, AD-39). */
	SELF_CHECK_INCOMPLETE,

	/** "Duyệt" and "Trả lại" each need a written comment (FR-25, AD-39). */
	REVIEW_COMMENT_REQUIRED,

	/** The Page tree has at most two levels (FR-29). */
	PAGE_TOO_DEEP,

	/** The parent Page is not one of this Job's. */
	PAGE_PARENT_UNKNOWN,

	/** The Assessment Report template is offered only on an Assessment Job (FR-10). */
	TEMPLATE_NOT_AVAILABLE,

	/** The Job already has its Assessment Report. */
	REPORT_EXISTS,

	/** The Assessment Report is always customer-visible (FR-31). */
	REPORT_ALWAYS_VISIBLE,

	/** Only an Attachment's author removes it, and only one's own note is corrected (FR-76). */
	NOT_AUTHOR,

	/** An Attachment stays once its Work Item is Done (FR-76). */
	WORK_ITEM_DONE,

	/** A link must be {@code https} with a host and no user info, at most 2000 characters (FR-76). */
	LINK_INVALID,

	/** FR-47 found a likely credential or personal data in a link's URL; masking it would break the link. */
	LINK_SENSITIVE,

	/** The Job's uploads would pass 200 MB (AD-34). */
	JOB_FILE_QUOTA,

	/** A note needs some text. */
	NOTE_EMPTY,

	/** The note a correction quotes is not one of this Work Item's. */
	QUOTE_UNKNOWN

}
