package vn.vibefix.github;

import java.util.Optional;

import org.jspecify.annotations.Nullable;

/**
 * Read-only repository metadata (AD-13, FR-7), with a caller's credential: the Product intake
 * reads it with the Customer's own sign-in token, through {@code identity}. Nothing is written.
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
	 */
	record RepositoryMetadata(long id, String fullName, String htmlUrl, long ownerId, String ownerType,
			boolean publicRepository, long sizeKb, @Nullable String forkParent, String defaultBranch,
			@Nullable Boolean admin, boolean submodules, boolean lfs) {
	}

	/**
	 * Reads {@code owner/name} with {@code bearer}; follows no redirect.
	 * @return empty when GitHub answers 404 (missing, or private to this credential)
	 * @throws GitHubException for any other refusal, or when GitHub cannot be reached
	 */
	Optional<RepositoryMetadata> read(String bearer, String owner, String name);

}
