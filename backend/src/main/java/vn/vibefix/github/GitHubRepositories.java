package vn.vibefix.github;

import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

/**
 * Read-only repository metadata (AD-13, FR-7, FR-13, FR-20), with a caller's credential: {@code product} reads a
 * Source Repository, its contributors and its commits with a {@link SourceApp} token narrowed to it (Stories 23.5 and
 * 23.5b). No path reads with a Customer's linking token. Nothing is written.
 */
public interface GitHubRepositories {

	/**
	 * A repository as GitHub reports it to that credential.
	 * @param id GitHub's permanent numeric repository ID (NFR-5)
	 * @param ownerType {@code User} or {@code Organization}
	 * @param sizeKb GitHub's {@code size}, in kilobytes
	 * @param forkParent the parent's {@code owner/name} for a fork
	 * @param admin {@code permissions.admin} as the credential sees it; absent when not reported
	 * @param submodules whether the default branch has a {@code .gitmodules}
	 * @param lfs whether the default branch's {@code .gitattributes} uses {@code filter=lfs}
	 * @param archived GitHub's {@code archived}: read-only on GitHub (FR-20)
	 */
	record RepositoryMetadata(long id, String fullName, String htmlUrl, long ownerId, String ownerType,
			boolean publicRepository, long sizeKb, @Nullable String forkParent, String defaultBranch,
			@Nullable Boolean admin, boolean submodules, boolean lfs, boolean archived) {
	}

	/**
	 * Reads {@code owner/name} with {@code bearer}; follows no redirect.
	 * @return empty when GitHub answers 404 (missing, or private to this credential)
	 * @throws GitHubException for any other refusal, or when GitHub cannot be reached
	 */
	Optional<RepositoryMetadata> read(String bearer, String owner, String name);

	/**
	 * The repository's languages as GitHub counts them, most bytes first: metadata only, no file is
	 * read (FR-9, SG-8).
	 * @return empty when GitHub answers 404
	 * @throws GitHubException for any other refusal, or when GitHub cannot be reached
	 */
	List<String> languages(String bearer, String owner, String name);

	/**
	 * The GitHub numeric IDs of the repository's contributors (FR-13, metadata only), at most
	 * {@value #MAX_CONTRIBUTOR_PAGES} pages of 100; anonymous commits have no ID and are left out.
	 * Only an empty repository, which GitHub answers with 204, has none.
	 * @throws GitHubException when GitHub refuses, a 404 included (the repository is gone or this credential does not
	 * see it, so who contributed is unknown), or cannot be reached
	 */
	List<Long> contributorIds(String bearer, String owner, String name);

	/** How many pages of contributors {@link #contributorIds} reads. */
	int MAX_CONTRIBUTOR_PAGES = 5;

	/**
	 * The commits on {@code branch} after {@code baseSha}, oldest first, as GitHub's compare lists them
	 * ({@code GET /repos/{fullName}/compare/{baseSha}...{branch}}, Contents read): at most 250 (FR-20, FR-53).
	 * @return {@code ["unknown"]} when GitHub answers 404: the base commit is gone from the branch's history, so every
	 * commit counts as new
	 * @throws GitHubException for any other refusal, or when GitHub cannot be reached
	 */
	List<String> commitsSince(String bearer, String fullName, String baseSha, String branch);

	/**
	 * The current login of the GitHub account with this numeric ID ({@code GET /user/{id}}), for the admin check
	 * by ID (AD-13): a renamed account keeps its ID.
	 * @return empty when GitHub answers 404 (the account is gone)
	 * @throws GitHubException for any other refusal, or when GitHub cannot be reached
	 */
	Optional<String> login(String bearer, long githubUserId);

	/**
	 * {@code login}'s permission on the repository, as the collaborator-permission endpoint reports it
	 * ({@code GET /repos/{owner}/{name}/collaborators/{login}/permission}, Metadata read): {@code admin},
	 * {@code maintain}, {@code write}, {@code triage}, {@code read}, or {@code none}. An organization's owners read
	 * as {@code admin} (AD-13, Spike S6).
	 * @return empty when GitHub answers 404 (no such account, or a repository this credential cannot see)
	 * @throws GitHubException for any other refusal, or when GitHub cannot be reached
	 */
	Optional<String> permission(String bearer, String owner, String name, String login);

}
