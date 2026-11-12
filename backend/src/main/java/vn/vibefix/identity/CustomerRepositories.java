package vn.vibefix.identity;

import java.util.Optional;
import java.util.UUID;

/**
 * A Customer's GitHub account as other modules need it (AD-10, AD-13). The contributor check reads with the
 * Customer's kept GitHub token, kept from linking for the owner of a live Product, which never leaves
 * {@code identity}, until Story 23.5b moves it to the Source App. Source Repositories themselves are read through the
 * Source App (Story 23.5), never with this token.
 */
public interface CustomerRepositories {

	/**
	 * Whether {@code accountId}'s GitHub account is among the repository's contributors, read with the
	 * Customer's token (FR-13, metadata only): an Applicant who worked on the code may not apply.
	 * @throws vn.vibefix.shared.ApiException {@code NOT_FOUND} for an account that is not active with a linked GitHub
	 * account and a kept token
	 * @throws vn.vibefix.github.GitHubException when GitHub refuses or cannot be reached
	 */
	boolean hasContributor(UUID customerId, String owner, String name, UUID accountId);

	/**
	 * The Customer's numeric GitHub user ID, with no GitHub call (AD-10): a Merge App installation made
	 * without the Customer on the page binds only when its requester or sender is this account, and the Source
	 * checks read its admin right by this ID (AD-13).
	 * @return empty for an Administrator, an erased account, or one with no linked GitHub account
	 */
	Optional<Long> githubUserId(UUID customerId);

}
