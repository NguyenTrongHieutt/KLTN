package vn.vibefix.job.internal;

/** The eight Job Types of the PRD glossary (FR-9); Assessment never combines with another. */
enum JobType {

	ASSESSMENT, BUG_FIX, NEW_FEATURE, MODIFICATION, IMPROVEMENT, SECURITY_TESTING, DEPLOYMENT_OPERATIONS, DOCUMENTATION;

	/** A Code Job Type: every type but Assessment (FR-8: blocked while an Intake Finding is open). */
	boolean code() {
		return this != ASSESSMENT;
	}

}
