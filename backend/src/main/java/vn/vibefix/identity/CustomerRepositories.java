package vn.vibefix.identity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import vn.vibefix.github.GitHubRepositories.RepositoryMetadata;

/**
 * GitHub repositories as a Customer sees them (AD-13): read with the Customer's kept sign-in token,
 * which never leaves {@code identity}. The Product intake decides admin rights from what this returns.
 */
public interface CustomerRepositories {

	/**
	 * A repository read with the Customer's token, and the Customer's own GitHub user ID.
	 * @param repository empty when GitHub answers 404 (missing, or private to this token)
	 */
	record CustomerView(long customerGithubId, Optional<RepositoryMetadata> repository) {
	}

	/**
	 * @throws vn.vibefix.shared.ApiException {@code NOT_FOUND} for an account that is not a Customer
	 * with a kept token
	 * @throws vn.vibefix.github.GitHubException when GitHub refuses or cannot be reached
	 */
	CustomerView read(UUID customerId, String owner, String name);

	/**
	 * The repository's GitHub languages, read with the Customer's token (FR-9, metadata only).
	 * @throws vn.vibefix.shared.ApiException {@code NOT_FOUND} as for {@link #read}
	 * @throws vn.vibefix.github.GitHubException when GitHub refuses or cannot be reached
	 */
	List<String> languages(UUID customerId, String owner, String name);

}
