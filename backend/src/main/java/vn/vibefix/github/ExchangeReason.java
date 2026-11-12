package vn.vibefix.github;

import java.util.Locale;

/**
 * Why the Actions token exchange refused a run's ID token (AD-44), one per check, in the order the
 * exchange runs them. The first five come before the ID token is verified and the correlation is known
 * to be open: a forged token names nothing reliably, so they are only counted and logged by kind. From
 * {@link #REPLAY} on, the token is GitHub's and names an open or exchanged correlation: the refusal is
 * recorded on it, audited, and alerted.
 */
public enum ExchangeReason {

	/** A malformed token, an algorithm other than RS256, a key set that cannot be read, or a bad signature. */
	SIGNATURE,

	/** Another issuer than GitHub Actions'. */
	ISSUER,

	/** An audience other than {@code vibefix} alone. */
	AUDIENCE,

	/** {@code exp}, {@code nbf} or {@code iat} missing or outside 60 s of skew. */
	EXPIRY,

	/** A correlation that is unknown, or no longer open. */
	CORRELATION,

	/** A correlation already exchanged: one token per correlation. */
	REPLAY,

	/** {@code repository} and {@code repository_id} other than the target's. */
	REPOSITORY,

	/** An event other than {@code workflow_dispatch}. */
	EVENT,

	/** A runner other than GitHub-hosted. */
	RUNNER,

	/**
	 * The run as the dispatching App reads it: missing, another workflow file, a head other than
	 * {@code workflow_sha}, another triggering actor than the App's bot, or a title without the correlation.
	 */
	RUN,

	/** The workflow file at {@code workflow_sha} is not an accepted blob, or is past its end date. */
	BLOB,

	/** For {@code copy} with a confirmed SHA: the run's head is another commit. */
	SHA,

	/** A ref other than the target's default branch. */
	REF;

	/** Whether the refusal comes before the token is verified and the correlation known open: counted only. */
	public boolean beforeVerification() {
		return ordinal() <= CORRELATION.ordinal();
	}

	/** The lower-case code, as audit actions and alerts name it, for example {@code blob}. */
	public String code() {
		return name().toLowerCase(Locale.ROOT);
	}

}
