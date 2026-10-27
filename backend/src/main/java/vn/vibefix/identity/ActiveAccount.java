package vn.vibefix.identity;

import java.util.UUID;

/**
 * The global gate of every command that creates no new work (AD-19): Job commands from J4 until Closed or
 * Cancelled, the duties a Job leaves after it ends, reading Job Posts, and the {@code team} commands until
 * Story 21.7. The account is read from the database on every call, never from the session, so a lock or an
 * erasure applies to the next request. New work asks {@link GitHubGate} instead.
 */
public interface ActiveAccount {

	/**
	 * @throws vn.vibefix.shared.ApiException {@code FORBIDDEN} with {@code ACCOUNT_LOCKED} for a locked, erased,
	 * or unknown account
	 */
	void requireActiveAccount(UUID accountId);

}
