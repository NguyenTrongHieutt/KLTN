package vn.vibefix.identity;

import java.util.UUID;

/**
 * The global gate of every user command that creates no new work (AD-19): Job commands from J4 until Closed
 * or Cancelled, the duties a Job leaves after it ends, and the {@code team} commands until Story 21.7. The
 * account is read from the database on every call, never from the session, so a lock or an erasure applies to
 * the next request. New work and reading "Tìm việc" ask {@link GitHubGate} instead. An Administrator holds no
 * role in any Job (FR-2), so both gates refuse one.
 */
public interface ActiveAccount {

	/**
	 * @throws vn.vibefix.shared.ApiException {@code FORBIDDEN} for an Administrator; {@code FORBIDDEN} with
	 * {@code ACCOUNT_LOCKED} for a locked, erased, or unknown account
	 */
	void requireActiveAccount(UUID accountId);

}
