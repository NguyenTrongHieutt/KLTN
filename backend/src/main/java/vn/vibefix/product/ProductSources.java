package vn.vibefix.product;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * The Source Repository checked again before a Code Job starts (FR-18, AD-13): still the registered
 * repository, still public, and still administered by the Customer, read with their kept GitHub token.
 */
public interface ProductSources {

	/** Why the Source Repository may not be copied now. */
	enum Block {

		/** Gone, private, or no longer the repository the Product registered (its GitHub ID changed). */
		SOURCE_NOT_PUBLIC,

		/** The Customer no longer administers it. */
		NO_ADMIN_PERMISSION,

		/** The Customer's kept token is missing or refused: they are asked to re-link GitHub (AD-10). */
		RELINK_GITHUB

	}

	/**
	 * What the check found, with what a scan of the repository needs.
	 * @param block why it may not be copied, or none
	 * @param defaultBranch the branch GitHub names as default, as read now
	 */
	record SourceCheck(@Nullable Block block, UUID productId, UUID customerId, String productName,
			String repositoryFullName, long repositoryGithubId, String defaultBranch) {
	}

	/** The Source Repository as the Product registered it: its permanent ID and the name it is used by. */
	record Registered(UUID productId, UUID customerId, long repositoryGithubId, String repositoryFullName) {
	}

	java.util.Optional<Registered> registered(UUID productId);

	/**
	 * The Customer re-confirms a renamed or transferred Source Repository (FR-20, AD-13): read by its new
	 * name with their token, it must keep the registered ID, stay public, and be administered by them. Only
	 * then is the new name recorded, so it becomes the Final Merge target.
	 * @return why it may not be used, or none once the new name is recorded
	 * @throws vn.vibefix.github.GitHubException when GitHub cannot answer now
	 */
	@Nullable Block reconfirm(UUID productId, String newFullName);

	/**
	 * Reads the Product's Source Repository now.
	 * @throws vn.vibefix.shared.ApiException {@code NOT_FOUND} for no such Product
	 * @throws vn.vibefix.github.GitHubException when GitHub cannot answer now: try again later
	 */
	SourceCheck check(UUID productId);

}
