package vn.vibefix.identity;

import java.util.Optional;
import java.util.UUID;

/**
 * An account's linked GitHub numeric ID, as other modules need it (AD-10, AD-13). The link keeps identity only: no
 * GitHub token is kept (R31), and Source Repositories and their contributors are read through the Source App.
 */
public interface GitHubAccountIds {

	/**
	 * The account's numeric GitHub user ID, with no GitHub call (AD-10): a Merge App installation made without the
	 * Customer on the page binds only when its requester or sender is this account, the Source checks read the
	 * Customer's admin right by this ID (AD-13), and the contributor guard looks for a bidder's, an applicant's or a
	 * signer's ID among the Source Repository's contributors (FR-13, FR-81).
	 * @return empty for an Administrator, an erased account, or one with no linked GitHub account
	 */
	Optional<Long> githubUserId(UUID accountId);

}
