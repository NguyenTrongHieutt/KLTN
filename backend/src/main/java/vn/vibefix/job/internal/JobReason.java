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
	DRAFT_CHANGED,

	/** J6 and M1 wait for the confirmed Agreement (FR-17). */
	AGREEMENT_NOT_CONFIRMED,

	/** J6's FR-75 guard: the Crew is not confirmed, or a lock left a re-confirmation owed (AD-41). */
	CREW_NOT_READY,

	/** Another Code Job of the Product is in Starting, Active, or Closing (AD-4). */
	PRODUCT_HAS_RUNNING_JOB,

	/** Another Milestone of the Job is Held (FR-41). */
	MILESTONE_HELD,

	/** The Milestone is not the next Planned one, or the Job cannot fund it in its state (FR-41). */
	MILESTONE_NOT_FUNDABLE,

	/** A Code Job's J7 waits for its repositories and verified access (FR-18, Epic 7). */
	REPOSITORIES_NOT_READY,

	/** Only the Job's Mentor hands a Milestone over (FR-32). */
	MENTOR_ONLY,

	/** A Code Job's handover needs its Handover Commit, recorded from Epic 7 on (FR-32, AD-4). */
	HANDOVER_COMMIT_MISSING,

	/** Only the Job's Customer accepts a Milestone or asks for a revision (FR-33). */
	CUSTOMER_ONLY,

	/** The handover decided on is no longer the Milestone's current one (FR-33). */
	HANDOVER_CHANGED,

	/** The Milestone has no Revision Round left (FR-34). */
	REVISION_ROUNDS_USED,

	/** A closure proposal is already open on the Job (FR-37). */
	CLOSURE_OPEN,

	/** No closure proposal is open on the Job. */
	NO_CLOSURE_OPEN,

	/** The caller has already confirmed this closure proposal. */
	CLOSURE_ANSWERED,

	/** A Code Job closes after its Final Merge or ZIP Fallback (FR-38), which Epic 7 brings. */
	FINAL_MERGE_PENDING,

	/** A Change Request needs a confirmed Agreement on a Job in Agreement, Starting, or Active (FR-36). */
	NO_CONFIRMED_AGREEMENT,

	/** A Change Request is already open on the Job (FR-36). */
	CHANGE_REQUEST_OPEN,

	/** An Accepted, Cancelled, or Settled Milestone is final (FR-36). */
	MILESTONE_FINAL,

	/** The Customer approves once the Mentor added the time impact (FR-36, UX-DR57). */
	IMPACT_MISSING,

	/** A Submitted Milestone waits for the Customer's decision first (FR-36). */
	MILESTONE_SUBMITTED,

	/** The Change Request is not at that step any more. */
	CHANGE_REQUEST_NOT_AWAITING,

	/** A cancellation proposal is already open on the Job (FR-40). */
	CANCELLATION_OPEN,

	/** No cancellation proposal is open on the Job (FR-40). */
	NO_CANCELLATION_OPEN,

	/** The other side confirms a cancellation; the proposer may withdraw it (FR-40). */
	CANCELLATION_PROPOSER,

	/** J10 cancels a Job without Accepted work; one with Accepted work goes through Closing (FR-40). */
	HAS_ACCEPTED_WORK

}
