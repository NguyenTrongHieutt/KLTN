package vn.vibefix.team.internal;

/** {@code team}'s refusal reasons ({@code reason.team.*} in {@code vi.json}). */
enum TeamReason {

	/** The Student is in a Team already (AD-37). */
	STUDENT_IN_TEAM,

	/** The Team holds 10 Students (FR-6). */
	TEAM_FULL,

	/** The Student is not verified (FR-5). */
	MEMBER_NOT_VERIFIED,

	/** The Mentor is revoked or locked: the Team takes no new members (AD-37). */
	TEAM_CLOSED,

	/** An invitation or a join request between them is already waiting for an answer. */
	REQUEST_PENDING,

	/** The Mentor sent 20 invitations today (spine Seed limits). */
	TEAM_INVITE_LIMIT,

	/** The Student sent 5 join requests today (spine Seed limits). */
	JOIN_REQUEST_LIMIT,

	/** The invitation or join request was answered, lapsed, or cancelled. */
	REQUEST_CLOSED,

	/** The Student is on a running Crew, so they can neither leave nor be removed (AD-37). */
	STUDENT_ON_CREW,

	/** The Student is in no Team, or in another Mentor's (AD-37). */
	NOT_IN_TEAM

}
