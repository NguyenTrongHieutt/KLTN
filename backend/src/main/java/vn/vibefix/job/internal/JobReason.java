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
	JOB_FILE_QUOTA

}
