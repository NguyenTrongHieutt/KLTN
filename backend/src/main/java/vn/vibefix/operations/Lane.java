package vn.vibefix.operations;

/**
 * The lanes of the seed operation policy (spine Conventions): each runs at most its configured number
 * of operations at a time ({@code vibefix.operations.lanes.*}), so slow work never starves fast work.
 */
public enum Lane {

	/**
	 * Work that waits on a GitHub Actions run or on a repository: J7's Setup, the handover copy, the Final
	 * Merge and its check, and the repository deletion. 1 at a time, 15 min, so one of them blocks the rest.
	 */
	GITHUB_LONG,

	/** Every other GitHub call: 4 at a time, 30 s. */
	GITHUB_SHORT,

	/** Email over SMTP: 2 at a time (AD-27, AD-34). */
	EMAIL,

	/** VNPay: 2 at a time. */
	VNPAY

}
