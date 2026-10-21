package vn.vibefix.product;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * The Source Repository checked again before a Code Job starts (FR-18, AD-13): still the registered
 * repository, still public, and still administered by the Customer, read with their kept sign-in token.
 */
public interface ProductSources {

	/** Why the Source Repository may not be copied now. */
	enum Block {

		/** Gone, private, or no longer the repository the Product registered (its GitHub ID changed). */
		SOURCE_NOT_PUBLIC,

		/** The Customer no longer administers it. */
		NO_ADMIN_PERMISSION,

		/** The Customer's kept token is missing or refused: they are asked to sign in again. */
		SIGN_IN_AGAIN

	}

	/**
	 * What the check found, with what a scan of the repository needs.
	 * @param block why it may not be copied, or none
	 * @param defaultBranch the branch GitHub names as default, as read now
	 */
	record SourceCheck(@Nullable Block block, UUID productId, UUID customerId, String productName,
			String repositoryFullName, long repositoryGithubId, String defaultBranch) {
	}

	/**
	 * Reads the Product's Source Repository now.
	 * @throws vn.vibefix.shared.ApiException {@code NOT_FOUND} for no such Product
	 * @throws vn.vibefix.github.GitHubException when GitHub cannot answer now: try again later
	 */
	SourceCheck check(UUID productId);

}
