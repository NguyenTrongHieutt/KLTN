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

}
