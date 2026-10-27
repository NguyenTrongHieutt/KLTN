package vn.vibefix.identity;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * What an account can show for itself now (FR-80, AD-19): its verifications and its lock. Every value is read
 * from the database on the call, never from the session and never cached, so linking or unlinking GitHub, a
 * verification that lapses, and a lock all apply to the next request. Each verification's validity is judged
 * by the injected Clock (AD-42). No capability grants anything on a Job: rights in a Job come only from the
 * role in that Job (Story 20.2).
 */
public interface Capabilities {

	/**
	 * The capabilities of one account.
	 * @param githubVerified a GitHub account is linked (Story 19.2)
	 * @param linkedinVerified a LinkedIn account is verified; always {@code false} until Story 19.6
	 * @param studentVerified a live student verification is valid today in Vietnam (Story 19.4)
	 * @param locked the account is locked (FR-59)
	 */
	record Held(boolean githubVerified, boolean linkedinVerified, boolean studentVerified, boolean locked) {

		/** What an account that does not exist holds: nothing, and it counts as locked (deny by default, AD-20). */
		public static final Held NONE = new Held(false, false, false, true);

	}

	/** The account's capabilities, read now; {@link Held#NONE} for an unknown account. */
	Held capabilities(UUID accountId);

	/**
	 * {@code identity.lockedAmong} (AD-41, Story 20.4): which of these accounts are locked now, each with the
	 * {@code locked_at} its lock wrote. A lock commits before its listeners release anything, so a guard that counts
	 * {@code ACTIVE} Crew rows reads this under the Job lock and counts a row only when its account is absent; the
	 * lock time also bounds the split-party rule of Story 22.1. Unknown and unlocked accounts are left out.
	 */
	Map<UUID, Instant> lockedAmong(Collection<UUID> accountIds);

}
