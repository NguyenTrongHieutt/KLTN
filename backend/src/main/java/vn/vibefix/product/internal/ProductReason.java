package vn.vibefix.product.internal;

/** {@code product}'s refusal reasons ({@code reason.product.*} in {@code vi.json}). */
enum ProductReason {

	/** The link is not a GitHub repository address. */
	NOT_GITHUB,

	/**
	 * No installation of the Source App reaches the repository, or it is suspended, or it has not accepted Contents
	 * read, Metadata read, and Actions write (AD-10, Story 23.5).
	 */
	SOURCE_APP_MISSING,

	/**
	 * The Source App is installed on all repositories, without this one, or with another repository that is not the
	 * Source Repository of one of the Customer's live Products (AD-10, R2).
	 */
	SOURCE_APP_WRONG_REPOSITORY,

	/** The Source App's token reads no such repository. */
	REPO_NOT_FOUND,

	/** The Customer's linked GitHub account does not administer the repository (AD-13). */
	NO_ADMIN_PERMISSION,

	/** A live Product already has this repository (FR-7, NFR-5). */
	REPO_ALREADY_LINKED,

	/** The repository uses Git LFS or submodules (AD-13). */
	REPO_UNSUPPORTED_CONTENT,

	/** The repository is over 250 MB (AD-13). */
	REPO_TOO_LARGE,

	/** No {@code .github/workflows/vibefix.yml} on the default branch (AD-45). */
	WORKFLOW_MISSING,

	/** The default branch's {@code vibefix.yml} is no published version (AD-45). */
	WORKFLOW_MISMATCH,

	/** GitHub could not be read just now, or the Source App is not configured. */
	GITHUB_UNAVAILABLE,

	/** A Job in Selecting to Closing pins the Product (FR-7, AD-3). */
	PRODUCT_HAS_ACTIVE_JOB,

	/** An acknowledgment is missing or of another notice version (FR-7). */
	NOTICE_REQUIRED,

	/** The Customer registered 5 Products today (AD-34). */
	PRODUCT_LIMIT,

	/**
	 * "Kiểm tra" past its seed limits (AD-34, Story 23.5c): 30 an hour per Product, or per draft repository, and 120
	 * an hour per Customer ({@link CheckWindow}).
	 */
	CHECK_LIMIT

}
