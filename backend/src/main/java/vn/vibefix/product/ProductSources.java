package vn.vibefix.product;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * The Source Repository checked again before a Code Job starts and before the Final Merge (FR-18, AD-13, AD-45):
 * read through the Customer's Source App binding (Story 23.5), with a token narrowed to the one repository after
 * the selection rule holds on a fresh read; still the registered repository, still administered by the Customer's
 * linked GitHub account, and holding a published {@code vibefix.yml} on its default branch.
 */
public interface ProductSources {

	/** Why the Source Repository may not be used now; each is something the Customer can fix. */
	enum Block {

		/** No bound installation reaches it, or it is suspended or lacks AD-10's permissions. */
		SOURCE_APP_MISSING,

		/** The installation selects all repositories, not this one, or another that is no live Product's. */
		SOURCE_APP_WRONG_REPOSITORY,

		/** No {@code .github/workflows/vibefix.yml} on the default branch. */
		WORKFLOW_MISSING,

		/** The default branch's {@code vibefix.yml} is no published version. */
		WORKFLOW_MISMATCH,

		/** The Customer no longer administers it. */
		NO_ADMIN_PERMISSION,

		/**
		 * The Customer's kept token is missing or refused (AD-10). No longer returned since Story 23.5, which reads
		 * through the Source App; it stays until Story 23.5b retires the linking token.
		 */
		RELINK_GITHUB

	}

	/**
	 * What the check found, with the Source Repository as the Product registered it.
	 * @param block why it may not be used, or none
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
	 * The Customer re-confirms a renamed or transferred Source Repository (FR-20, AD-13): found by its new name
	 * through the Source App, it must keep the registered ID, pass the selection rule, be administered by them, and
	 * hold the published workflow. Only then is the new name recorded, so it becomes the Final Merge target.
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
