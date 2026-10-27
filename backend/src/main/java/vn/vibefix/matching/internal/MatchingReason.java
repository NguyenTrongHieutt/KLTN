package vn.vibefix.matching.internal;

/** {@code matching}'s refusal reasons ({@code reason.matching.*} in {@code vi.json}). */
enum MatchingReason {

	/** The Job is not Open (FR-12). */
	JOB_POST_NOT_OPEN,

	/** An Administrator hid the Job Post (FR-60). */
	JOB_POST_HIDDEN,

	/**
	 * One role per Job (AD-43): the bidder, or the invited person, is the Job's own Customer. It maps to the UX
	 * copy {@code OWN_JOB}, under the key {@code reason.matching.ROLE_TAKEN}.
	 */
	ROLE_TAKEN,

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
	NOT_WITHDRAWABLE,

	/** The NDA was signed against a text version that is no longer current (FR-15). */
	NDA_VERSION_OUTDATED,

	/** A Code Job's Mentor signs only after acknowledging N-23 (FR-18). */
	COMMIT_EMAIL_NOT_ACKNOWLEDGED,

	/** The invited Mentor cannot take new work; never says why (FR-14, SG-41). */
	INVITEE_NOT_AVAILABLE,

	/** One invitation per Mentor and Job Post (FR-14). */
	ALREADY_INVITED,

	/** The Mentor already applied to this Job (FR-14). */
	INVITEE_ALREADY_APPLIED,

	/** 10 invitations for this Job Post today (FR-14, AD-34). */
	INVITE_LIMIT_JOB_POST,

	/** 30 invitations by this Customer today (FR-14, AD-34). */
	INVITE_LIMIT_DAILY,

	/**
	 * A bid from a Mentor whose profile has no display name yet (Story 19.7): the Customer could neither
	 * tell who applied nor select them. The SPA links to ACC-1.
	 */
	PROFILE_REQUIRED

}
