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
	MOVE_NOT_ALLOWED

}
