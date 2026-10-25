package vn.vibefix.identity;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
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
	 * @param githubUserId the linked GitHub account's permanent numeric ID, by which {@code repo} grants
	 * and recognizes the Mentor (AD-10 "Logins by ID"); {@code null} while GitHub is not linked
	 * @param skills the Skills' current names, in the Mentor's order
	 * @param available approved and unlocked: a revoked or locked Mentor takes no new work (FR-74)
	 */
	record MentorCard(UUID accountId, @Nullable String githubLogin, @Nullable Long githubUserId, String displayName,
			int yearsOfExperience,
			List<String> skills, boolean available) {

		public MentorCard {
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
	 * {@code skillIds}: by relevance, then account ID, never by Rating (AD-36, FR-14); by name without
	 * a keyword.
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

	/** An approved, unlocked Mentor's Skills by ID, in their order (AD-38). */
	record MentorSkills(UUID accountId, List<UUID> skillIds) {

		public MentorSkills {
			skillIds = List.copyOf(skillIds);
		}

	}

	/**
	 * Recommendations' pool (AD-17): approved, unlocked Mentors holding any of {@code skillIds}, the
	 * most shared Skills first, then account ID; at most {@code limit}.
	 */
	List<MentorSkills> holdingAny(Collection<UUID> skillIds, int limit);

	/** The Skills of these Mentors while approved and unlocked; any other ID is left out. */
	Map<UUID, List<UUID>> skillIdsOf(Collection<UUID> mentorIds);

}
