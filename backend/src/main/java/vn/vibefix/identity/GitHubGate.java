package vn.vibefix.identity;

import java.util.UUID;

/**
 * The GitHub gate of the commands that create work (FR-80, AD-19): registering a Product, publishing a
 * Job, bidding, and signing the Mentor's NDA. Being invited to bid needs the same link, which the Mentor
 * directory reads as a Mentor's availability by the same rule. The link is read from the database on
 * every call, never from the session, so an unlink applies to the next request. The SPA shows the GitHub
 * gate (UX-DR95), and this refusal makes sure it is never the only guard. Every other command asks
 * {@link ActiveAccount}.
 */
public interface GitHubGate {

	/**
	 * Called after the endpoint's own role checks; no Mentor needs an approval (Story 19.7).
	 * @throws vn.vibefix.shared.ApiException {@code FORBIDDEN} with {@code ACCOUNT_LOCKED} for a locked or
	 * erased account, else with {@code GITHUB_NOT_LINKED} while no GitHub account is linked
	 */
	void requireGitHubLinked(UUID accountId);

}
