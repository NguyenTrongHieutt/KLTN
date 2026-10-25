package vn.vibefix.identity;

import java.util.List;
import java.util.UUID;

/**
 * A module's part in an account's GitHub link (FR-80, R14, AD-19, AD-24): what keeps the link as it is,
 * and whether the account's GitHub token must be kept. {@code identity} collects every participant
 * bean, so it depends on none of the modules that answer: {@code product} for a live Product, {@code job}
 * for a role in a running Job, and {@code matching} for an open bid.
 */
public interface GitHubLinkParticipant {

	/** What in this module keeps {@code accountId} from unlinking GitHub or re-linking another GitHub account. */
	List<GitHubLinkBlocker> githubLinkBlockers(UUID accountId);

	/**
	 * Whether this module needs {@code accountId}'s GitHub token kept (AD-10, AD-13): only the owner of a
	 * live Product has repositories vibefix reads with it.
	 */
	default boolean keepsRepositoryToken(UUID accountId) {
		return false;
	}

}
