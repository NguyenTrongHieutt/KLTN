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
	JOB_FROZEN

}
