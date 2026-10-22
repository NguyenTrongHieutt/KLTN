package vn.vibefix.job.internal;

/** The eight Job Types of the PRD glossary (FR-9); Assessment never combines with another. */
enum JobType {

	ASSESSMENT, BUG_FIX, NEW_FEATURE, MODIFICATION, IMPROVEMENT, SECURITY_TESTING, DEPLOYMENT_OPERATIONS, DOCUMENTATION;

	/** A Code Job Type: every type but Assessment (FR-8: blocked while an Intake Finding is open). */
	boolean code() {
		return this != ASSESSMENT;
	}

	/** A Job Type that changes the running Product, whose handover needs a preview (FR-32): not Assessment or Documentation. */
	boolean changesProduct() {
		return this != ASSESSMENT && this != DOCUMENTATION;
	}

}
