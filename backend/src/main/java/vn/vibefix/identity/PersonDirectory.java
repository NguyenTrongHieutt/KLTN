package vn.vibefix.identity;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Any person's card, by account ID, whatever role they hold in any Job (AD-36): what a screen, an email, or a grant
 * needs to name them. It is not {@link MentorDirectory}, which leaves out people with no profile and shows a different
 * name. Nobody is searched here: a Mentor sees only the people who apply to their Crew.
 */
public interface PersonDirectory {

	/**
	 * A person's card.
	 * @param githubUserId the linked GitHub account's permanent numeric ID, by which {@code repo} grants and recognizes
	 * the person (AD-10 "Logins by ID"); {@code null} while GitHub is not linked
	 * @param name the full name declared at student verification, only while it is current and they show it
	 * @param available an unlocked, unerased account
	 */
	record PersonCard(UUID accountId, @Nullable String githubLogin, @Nullable Long githubUserId, @Nullable String name,
			boolean available) {

		/**
		 * What a screen calls the person: the name while they show it, else {@code @login}, else nothing when neither
		 * is known (no GitHub account linked).
		 */
		public String shownName() {
			if (this.name != null) {
				return this.name;
			}
			return this.githubLogin != null ? "@" + this.githubLogin : "";
		}

	}

	/** The cards of these people, verified or not, in the order of {@code ids}; unknown IDs are left out. */
	List<PersonCard> cards(Collection<UUID> ids);

}
