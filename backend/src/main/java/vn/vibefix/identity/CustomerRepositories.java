package vn.vibefix.identity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import vn.vibefix.github.GitHubRepositories.RepositoryMetadata;

/**
 * GitHub repositories as a Customer sees them (AD-13): read with the Customer's kept GitHub token, kept
 * from linking for the owner of a live Product (AD-10), which never leaves {@code identity}. The Product intake decides admin rights from what this returns.
 */
public interface CustomerRepositories {

	/**
	 * A repository read with the Customer's token, and the Customer's own GitHub user ID.
	 * @param repository empty when GitHub answers 404 (missing, or private to this token)
	 */
	record CustomerView(long customerGithubId, Optional<RepositoryMetadata> repository) {
	}

	/**
	 * @throws vn.vibefix.shared.ApiException {@code NOT_FOUND} for an account that is not active with a
	 * linked GitHub account and a kept token
	 * @throws vn.vibefix.github.GitHubException when GitHub refuses or cannot be reached
	 */
	CustomerView read(UUID customerId, String owner, String name);

	/**
	 * The repository's GitHub languages, read with the Customer's token (FR-9, metadata only).
	 * @throws vn.vibefix.shared.ApiException {@code NOT_FOUND} as for {@link #read}
	 * @throws vn.vibefix.github.GitHubException when GitHub refuses or cannot be reached
	 */
	List<String> languages(UUID customerId, String owner, String name);

	/**
	 * Whether {@code accountId}'s GitHub account is among the repository's contributors, read with the
	 * Customer's token (FR-13, metadata only): an Applicant who worked on the code may not apply.
	 * @throws vn.vibefix.shared.ApiException {@code NOT_FOUND} as for {@link #read}
	 * @throws vn.vibefix.github.GitHubException when GitHub refuses or cannot be reached
	 */
	boolean hasContributor(UUID customerId, String owner, String name, UUID accountId);

	/**
	 * The Customer's numeric GitHub user ID, with no GitHub call (AD-10): a Merge App installation made
	 * without the Customer on the page binds only when its requester or sender is this account.
	 * @return empty for an Administrator, an erased account, or one with no linked GitHub account
	 */
	Optional<Long> githubUserId(UUID customerId);

}
