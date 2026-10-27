package vn.vibefix.identity;

import java.util.UUID;

/**
 * The GitHub gate of the commands that create work and of "Tìm việc" (FR-80, AD-19): checking a repository and
 * registering a Product, publishing a Job, bidding, signing the Mentor's NDA, the Crew member's NDA and Crew
 * Terms confirmation, and every read of "Tìm việc" (X1): MEN-2's Job Posts, capacity, and Recommendations, and
 * MEN-3's Job Post with its screenshots, Customer Rating, and invitation. Being invited to bid needs the same
 * link, which the Mentor directory reads as a Mentor's availability by the same rule. The link is read
 * from the database on every call, never from the session, so an unlink applies to the next request. The SPA
 * shows the GitHub gate (UX-DR95), and this refusal makes sure it is never the only guard. Every other user
 * command asks {@link ActiveAccount}. An Administrator holds no role in any Job (FR-2), so it is refused.
 */
public interface GitHubGate {

	/**
	 * Called after the endpoint's own role checks; no Mentor needs an approval (Story 19.7).
	 * @throws vn.vibefix.shared.ApiException {@code FORBIDDEN} for an Administrator; {@code FORBIDDEN} with
	 * {@code ACCOUNT_LOCKED} for a locked, erased, or unknown account, else with {@code GITHUB_NOT_LINKED} while
	 * no GitHub account is linked
	 */
	void requireGitHubLinked(UUID accountId);

}
