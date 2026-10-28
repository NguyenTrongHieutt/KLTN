package vn.vibefix.notification;

import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Confirms that an account holds an audience class (AD-3 port, recipient lookup): {@code identity}
 * for {@link Audience#ADMIN}, and {@code job} for the Job-scoped classes once it exists (Epic 3).
 * A class no lookup confirms is refused.
 */
public interface RecipientLookup {

	/** The classes this lookup can confirm. */
	Set<Audience> audiences();

	boolean holds(UUID accountId, Audience audience, @Nullable UUID jobId);

	/**
	 * Whether the account holds the class on the Job and, for {@link Audience#SPLIT_PARTY}, in the Milestone's split;
	 * a lookup that confirms no Milestone-scoped class answers as {@link #holds(UUID, Audience, UUID)} does.
	 */
	default boolean holds(UUID accountId, Audience audience, @Nullable UUID jobId, @Nullable UUID milestoneId) {
		return holds(accountId, audience, jobId);
	}

}
