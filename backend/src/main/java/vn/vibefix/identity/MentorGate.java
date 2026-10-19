package vn.vibefix.identity;

import java.util.UUID;

/**
 * The Mentor approval gate (AD-19, FR-74). Each Mentor endpoint names one of the two checks in its
 * matrix row, and both read the account from the database on every call, never from the session,
 * so a revocation or a lock applies to the next request.
 */
public interface MentorGate {

	/**
	 * New-work commands: browsing and searching Job Posts, applying, receiving an Invitation to Apply,
	 * signing the FR-15 NDA, inviting Students, and accepting join requests.
	 * @throws vn.vibefix.shared.ApiException {@code FORBIDDEN} with {@code ACCOUNT_LOCKED} for a locked
	 * or erased account, else with {@code MENTOR_NOT_APPROVED} unless it is an {@code APPROVED} Mentor
	 */
	void requireApprovedMentor(UUID accountId);

	/**
	 * Job commands on the Mentor's own Jobs from J4 until Closed or Cancelled, the duties a Job leaves
	 * after it ends, and removing a Student from the Team: a revoked Mentor's running Jobs continue.
	 * @throws vn.vibefix.shared.ApiException {@code FORBIDDEN} with {@code ACCOUNT_LOCKED} for a locked
	 * or erased account
	 */
	void requireActiveAccount(UUID accountId);

}
