package vn.vibefix.matching.internal;

/** {@code matching}'s refusal reasons ({@code reason.matching.*} in {@code vi.json}). */
enum MatchingReason {

	/** The Job is not Open (FR-12). */
	JOB_POST_NOT_OPEN,

	/** An Administrator hid the Job Post (FR-60). */
	JOB_POST_HIDDEN,

	/**
	 * One role per Job (AD-43): the bidder, or the invited person, is the Job's own Customer; or a Crew applicant
	 * already holds a role in the Job (its Customer, its Mentor, a bidder still in play, or a Crew member). It maps to
	 * the UX copy {@code OWN_JOB} for the Customer, under the key {@code reason.matching.ROLE_TAKEN}, and to
	 * {@code ROLE_IN_JOB_TAKEN} for the other roles.
	 */
	ROLE_TAKEN,

	/** One Application per Mentor and Job (FR-13). */
	ALREADY_APPLIED,

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
	PROFILE_REQUIRED,

	/** A bid carries at most five proof items, files and links together (FR-13, SG-58). */
	PROOF_LIMIT,

	/** A proof link is not {@code https} with a host (AD-21, the shared link rule). */
	LINK_INVALID,

	/** A proof file is not one of the bidder's own uploads waiting for this Job's bid, or is named twice. */
	PROOF_FILE_UNKNOWN,

	/**
	 * A Crew Application finds the Mentor's recruitment off, the Job Post hidden, or the Job past Active (FR-81,
	 * Story 21.4); {@code job.requireRecruiting} answers the same under the Job row lock.
	 */
	RECRUITMENT_CLOSED,

	/** A Crew Application finds no free slot (FR-81, R2); the detail member {@code n} is the Crew cap. */
	CREW_FULL,

	/**
	 * The applicant was once an {@code ACTIVE} member of this Job and left (FR-82, R3): nobody rejoins a Job. Its copy is
	 * the UX's {@code LEFT_THIS_JOB}.
	 */
	CREW_REJOIN_REFUSED,

	/**
	 * The applicant holds a Crew row that is not {@code RELEASED} on another Job (R1, X8): refused when they apply, not
	 * only at signing. Its copy is the UX's {@code CREW_MEMBER_BUSY}, which names that Job from the applicant's own list.
	 */
	ON_ANOTHER_CREW,

	/** Only a pending Crew Application is declined (FR-81). */
	NOT_DECLINABLE

}
