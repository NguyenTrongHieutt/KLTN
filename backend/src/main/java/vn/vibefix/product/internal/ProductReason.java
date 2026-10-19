package vn.vibefix.product.internal;

/** {@code product}'s refusal reasons ({@code reason.product.*} in {@code vi.json}). */
enum ProductReason {

	/** The link is not a GitHub repository address. */
	NOT_GITHUB,

	/** GitHub answers 404: the repository is missing, or private to the Customer's token. */
	REPO_NOT_FOUND,

	/** GitHub reports the repository as not public. */
	REPO_PRIVATE,

	/** The Customer does not administer the repository (AD-13). */
	NO_ADMIN_PERMISSION,

	/** A live Product already has this repository (FR-7, NFR-5). */
	REPO_ALREADY_LINKED,

	/** The repository uses Git LFS or submodules (AD-13). */
	REPO_UNSUPPORTED_CONTENT,

	/** The repository is over 250 MB (AD-13). */
	REPO_TOO_LARGE,

	/** GitHub could not be read just now. */
	GITHUB_UNAVAILABLE,

	/** An acknowledgment is missing or of another notice version (FR-7). */
	NOTICE_REQUIRED,

	/** The Customer registered 5 Products today (AD-34). */
	PRODUCT_LIMIT

}
