package vn.vibefix.job.internal;

/** {@code job}'s refusal reasons ({@code reason.job.*} in {@code vi.json}). */
enum JobReason {

	/** Assessment is chosen together with another Job Type (FR-9). */
	ASSESSMENT_ALONE,

	/** A Code Job Type while the Product has an open Intake Finding (FR-8). */
	OPEN_FINDINGS,

	/** Only a Draft is edited here. */
	JOB_NOT_DRAFT,

	/** The Job's screenshots would pass 200 MB (AD-34). */
	JOB_FILE_QUOTA,

	/** Publishing needs every guided answer, a Job Type, the budget, and a future deadline (FR-9). */
	JOB_INCOMPLETE,

	/** A Code Job is published while the Product has an open Intake Finding (FR-8). */
	INTAKE_FINDING_OPEN,

	/** The Job is no longer in a state the transition leaves from (AD-4). */
	STATE_CHANGED,

	/** An open Dispute freezes the Job's Milestones (FR-52). */
	JOB_FROZEN,

	/** J3: the Mentor was revoked, locked, or erased since applying (FR-13, FR-74). */
	MENTOR_NOT_AVAILABLE,

	/** J3: the Mentor reached the FR-61 limit on active Jobs (FR-13). */
	MENTOR_AT_JOB_LIMIT,

	/** J3: no verified, unlocked Student of the Mentor's Team is free (FR-13). */
	MENTOR_NO_FREE_STUDENT,

	/** J5: the Customer cancels the selection before the FR-15 signature period ends. */
	NDA_PERIOD_RUNNING,

	/** The Agreement draft is not complete enough to share; {@code problems} lists why (FR-16). */
	AGREEMENT_INCOMPLETE,

	/** The Mentor confirmed the Agreement: the Crew stays as it is until J6 or J10 (FR-17, AD-41). */
	CREW_LOCKED,

	/** After J6 a member leaves only through a Crew change, a lock, or the Job's end (AD-41). */
	CREW_CHANGE_REQUIRED,

	/** With large Crews off, a Job has one Crew row that is not released (AD-30). */
	CREW_LIMIT,

	/** Only a verified Student joins a Crew (FR-5, FR-75). */
	STUDENT_NOT_VERIFIED,

	/** The Student is on another running Crew (FR-75, AD-33). */
	STUDENT_ON_CREW,

	/** The Student shares a person-link signal with the Mentor or the Customer; never says which (AD-19). */
	CONFLICT_OF_INTEREST,

	/** The Student contributed to the Source Repository (FR-75). */
	APPLICANT_IS_CONTRIBUTOR,

	/** GitHub could not say who contributed, so the addition waits (fails closed). */
	CONTRIBUTORS_UNKNOWN,

	/** Shares: whole percentages summing to 100, each Crew member above 0, the Mentor within the cap (FR-75). */
	SHARES_INVALID,

	/** The Student is not a party of the version, or the Crew is not confirmed yet (FR-17, AD-41). */
	CREW_NOT_CONFIRMED,

	/** The Customer already approved this draft version (FR-17). */
	AGREEMENT_APPROVED,

	/** The Customer asked for changes on this version; the Mentor shares a new one first (FR-16). */
	CHANGES_REQUESTED,

	/** A Code Job's approval needs the Freeze Commitment N-6 (FR-17). */
	FREEZE_COMMITMENT_REQUIRED,

	/** The Mentor confirms only the Customer's approved latest version (FR-17). */
	AGREEMENT_NOT_APPROVED,

	/** A confirmed Agreement changes only through a Change Request (FR-17, AD-29). */
	AGREEMENT_CONFIRMED,

	/** The live draft has edits the Customer has not seen; the Mentor shares them first (AD-29). */
	DRAFT_CHANGED

}
