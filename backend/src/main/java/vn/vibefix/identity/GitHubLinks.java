package vn.vibefix.identity;

import java.util.UUID;

/**
 * An account's linked GitHub account as other modules may change it (AD-10, AD-22). Linking,
 * re-linking, and unlinking are the account owner's own actions on ACC-2 and stay in {@code identity}.
 */
public interface GitHubLinks {

	/**
	 * Deletes the account's kept GitHub token once no participant needs it any more (AD-10, SG-9): called
	 * by {@code product} when the account's last live Product is deleted, in that transaction. The link
	 * itself stays.
	 */
	void forgetRepositoryToken(UUID accountId);

}
