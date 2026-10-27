package vn.vibefix.authz;

/**
 * Every relationship a policy may test (AD-20) and every Crew state (AD-41), as the {@code caller}
 * column of {@code authz/matrix.csv}. A fixture in {@code vn.vibefix.authz.fixtures} realizes each
 * caller for a row; a row whose caller no fixture realizes fails.
 */
public enum MatrixCaller {

	/** Nobody signed in. */
	ANONYMOUS,

	/**
	 * A Google account with GitHub linked and no account role (Story 20.3), holding no relation to the row's
	 * objects: what every global screen's capability gate lets in.
	 */
	USER,

	/** A Google account without GitHub linked and no account role (Story 20.3), holding no relation either. */
	USER_UNVERIFIED,

	/** A Customer with no relationship to the row's objects. */
	CUSTOMER,

	/** A Mentor with no relationship to the row's objects. */
	MENTOR,

	/** A Student with no relationship to the row's objects. */
	STUDENT,

	/** An Administrator holding no read grant (AD-20). */
	ADMINISTRATOR,

	/** The Customer of the row's Product or Job. */
	OWNER,

	/** The Mentor of the row's Job. */
	JOB_MENTOR,

	/** A Crew member of the row's Job whose row is {@code ASSIGNED} (AD-41). */
	CREW_ASSIGNED,

	/** A Crew member of the row's Job whose row is {@code ACTIVE}. */
	CREW_ACTIVE,

	/** A Crew member of the row's Job whose row is {@code INACTIVE}, with a window. */
	CREW_INACTIVE,

	/** A former Crew member of the row's Job, with a window. */
	CREW_FORMER,

	/** The Mentor of a Team, who is not the Mentor of the row's Job. */
	TEAM_MENTOR,

	/** A member of a Team who is not on the row's Job: Team membership grants nothing on a Job. */
	TEAM_MEMBER,

	/** The account the row's record names: applicant, invited or requesting Student, raiser, rated person. */
	SUBJECT,

	/** The author of the row's record. */
	AUTHOR,

	/** An Administrator assigned to a Dispute on the row's Job, holding its read grant. */
	ADMIN_DISPUTE_GRANT,

	/** An Administrator assigned to an escalated Team Issue on the row's Job, holding its read grant. */
	ADMIN_TEAM_ISSUE_GRANT,

	/** An Administrator assigned to an Abuse Report, holding the grant on the one item it names. */
	ADMIN_ABUSE_REPORT_GRANT

}
