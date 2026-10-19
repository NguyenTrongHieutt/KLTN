package vn.vibefix.matching.internal;

/** {@code matching}'s refusal reasons ({@code reason.matching.*} in {@code vi.json}). */
enum MatchingReason {

	/** The Job is not Open (FR-12). */
	JOB_POST_NOT_OPEN,

	/** An Administrator hid the Job Post (FR-60). */
	JOB_POST_HIDDEN,

	/** One Application per Mentor and Job (FR-13). */
	ALREADY_APPLIED,

	/** No verified, unlocked Team member is free for a new Crew (FR-13, AD-33). */
	NO_FREE_STUDENT,

	/** The Mentor holds the most Jobs the FR-61 rule allows (FR-13). */
	MENTOR_JOB_LIMIT,

	/** The Mentor shares a person-link signal with the Customer (FR-13, AD-19); never says which. */
	CONFLICT_OF_INTEREST,

	/** The Mentor contributed to the Source Repository (FR-13). */
	APPLICANT_IS_CONTRIBUTOR,

	/** GitHub could not say who contributed, so the Application waits (fails closed). */
	CONTRIBUTORS_UNKNOWN,

	/** N-5 was not acknowledged (FR-13). */
	PUBLIC_CODE_NOT_ACKNOWLEDGED,

	/** Only a submitted or held Application is withdrawn (FR-13). */
	NOT_WITHDRAWABLE

}
