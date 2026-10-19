package vn.vibefix.identity;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Mentors' public profile fields (FR-51, AD-36): the display name, the experience, the years, and the
 * Skills, from the approved application; never the LinkedIn link, the document, or contact details.
 */
public interface MentorDirectory {

	/**
	 * A Mentor's public card.
	 * @param skills the Skills' current names, in the Mentor's order
	 * @param available approved and unlocked: a revoked or locked Mentor takes no new work (FR-74)
	 */
	record MentorCard(UUID accountId, @Nullable String githubLogin, String displayName, int yearsOfExperience,
			List<String> skills, boolean available) {

		public MentorCard {
			skills = List.copyOf(skills);
		}

	}

	/** One Skill Category with the Mentor's Skills in it, in the catalog's order; a retired Category has no name. */
	record SkillGroup(@Nullable String category, List<String> skills) {

		public SkillGroup {
			skills = List.copyOf(skills);
		}

	}

	/**
	 * PUB-5's profile of a Mentor who was approved.
	 * @param memberSince when the account was created
	 * @param skillGroups the Skills grouped by Skill Category (UX-DR65)
	 */
	record MentorProfile(MentorCard card, String experience, Instant memberSince, List<SkillGroup> skillGroups) {

		public MentorProfile {
			skillGroups = List.copyOf(skillGroups);
		}

	}

	/**
	 * Approved, unlocked Mentors whose public fields match {@code query} and who hold one of
	 * {@code skillIds}, best match first (AD-36).
	 * @param query the keyword; blank for the Skill filter alone
	 * @param skillIds the Skill filter; empty for none
	 */
	Page<MentorCard> search(String query, Collection<UUID> skillIds, Pageable pageable);

	/**
	 * The profile of the Mentor with this GitHub username, ignoring letter case, while they are
	 * approved, or were and are now revoked or locked ({@code available} false).
	 */
	Optional<MentorProfile> byLogin(String githubLogin);

	/** The cards of these Mentors, in the order of {@code ids}; unknown IDs are left out. */
	List<MentorCard> cards(Collection<UUID> ids);

}
