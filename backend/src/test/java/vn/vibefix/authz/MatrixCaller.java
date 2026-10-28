package vn.vibefix.authz;

/**
 * AD-20's callers (Story 20.5), as the {@code caller} column of {@code authz/matrix.csv}: the global ones, one per
 * role or subject relation in a Job, the cross-role person, and an Administrator with and without each grant. A
 * fixture in {@code vn.vibefix.authz.fixtures} realizes each caller for a row; a row whose caller no fixture
 * realizes fails. Every matrix caller but {@link #ANONYMOUS} is a real account, so it passes the same per-request
 * account check as a real one (AD-19): an Administrator signs in through a stored session, and a person's request
 * carries its authentication itself ({@code IdentityTestSupport.signedIn}); the harness-only {@link #AUTHOR} has no
 * account row, its caller set directly. No caller is an account role: rights inside a Job come only from the role in
 * that Job (AD-43). {@code CREW_APPLICANT} and the split-party variants of {@link #CREW_ACTIVE} and
 * {@link #CREW_FORMER} arrive with Stories 21.4 and 22.1, which add their endpoints (readiness C-8).
 */
public enum MatrixCaller {

	/** Nobody signed in. */
	ANONYMOUS,

	/** An unrelated Google account without GitHub linked and with no role anywhere. */
	USER_UNVERIFIED,

	/**
	 * An unrelated Google account with GitHub linked and no role anywhere: what every global screen's capability
	 * gate lets in, and what every Job-scoped policy hides each Job from.
	 */
	USER,

	/**
	 * A locked account that held an {@code ACTIVE} row on the crew Job, which the lock released as {@code LEFT}
	 * (Story 20.4). A locked account's session ends, so every request arrives anonymous.
	 */
	LOCKED,

	/** The Customer of every fixture Job and the owner of the Product. */
	CUSTOMER,

	/** The Mentor of every fixture Job that has one. */
	MENTOR,

	/** The applicant of {@code {applicationId}}: an open bid on the Open Job Post, a subject relation, not a role. */
	BIDDER,

	/** A Crew member of the crew Job whose row is {@code ASSIGNED}: picked, not signed (AD-41). */
	CREW_ASSIGNED,

	/** A Crew member of the crew Job whose row is {@code ACTIVE}. */
	CREW_ACTIVE,

	/**
	 * A former Crew member of the crew Job: released {@code LEFT} by a lock and then unlocked (deferred B16), with a
	 * window that ended before the Job's later content was written.
	 */
	CREW_FORMER,

	/**
	 * An {@code ACTIVE} member of the crew Job who is also the Customer of a second Active Job, seeded with its own
	 * Crew canaries ({@code CROSS_*}): on the crew Job they answer as {@link #CREW_ACTIVE} does, and nothing of the
	 * second Job's Crew reaches them (AD-20, SG-64).
	 */
	CROSS_ROLE,

	/** The account the row's record names: the recipient, the profile owner, the suggester. */
	SUBJECT,

	/** An Administrator holding no read grant (AD-20). */
	ADMINISTRATOR,

	/** The Administrator who took the crew Job's Dispute, holding its read grant ({@code forDispute}). */
	ADMIN_DISPUTE_GRANT,

	/**
	 * The Administrator who took an escalated Team Issue on the crew Job, holding its read grant: the Crew Chat, the
	 * Crew Terms, and the raiser's Work Items ({@code forEscalatedTeamIssue}).
	 */
	ADMIN_TEAM_ISSUE_GRANT,

	/** An Administrator assigned to an Abuse Report, holding the grant on the one item it names. */
	ADMIN_ABUSE_REPORT_GRANT,

	/** The author of the harness's own record, for {@code AuthorizationHarnessIT} only; no matrix row uses it. */
	AUTHOR

}
