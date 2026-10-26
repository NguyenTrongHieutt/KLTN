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
 * Skills, from the profile of an account holding the {@code MENTOR} role (until Story 20.3); never contact
 * details. Nobody approves a Mentor (Story 19.7): GitHub linked is what new work needs (Q8).
 */
public interface MentorDirectory {

	/**
	 * A Mentor's public card.
	 * @param githubUserId the linked GitHub account's permanent numeric ID, by which {@code repo} grants
	 * and recognizes the Mentor (AD-10 "Logins by ID"); {@code null} while GitHub is not linked
	 * @param yearsOfExperience absent while the Mentor has not declared it ("Chưa khai")
	 * @param skills the Skills' current names, in the Mentor's order
	 * @param available unlocked, not erased, and with GitHub linked: any other Mentor takes no new work and
	 * cannot be invited to bid (AD-19)
	 */
	record MentorCard(UUID accountId, @Nullable String githubLogin, @Nullable Long githubUserId, String displayName,
			@Nullable Integer yearsOfExperience, List<String> skills, boolean available) {

		public MentorCard {
			skills = List.copyOf(skills);
		}

	}

	/**
	 * PUB-5's profile of a Mentor.
	 * @param memberSince when the account was created
	 * @param skillGroups the Skills grouped by Skill Category (UX-DR65)
	 */
	record MentorProfile(MentorCard card, String experience, Instant memberSince, List<SkillGroup> skillGroups) {

		public MentorProfile {
			skillGroups = List.copyOf(skillGroups);
		}

	}

	/**
	 * Unlocked Mentors whose public fields match {@code query} and who hold one of {@code skillIds}: by
	 * relevance, then account ID, never by Rating (AD-36, FR-14); by name without a keyword. One without
	 * GitHub linked is listed, not available.
	 * @param query the keyword; blank for the Skill filter alone
	 * @param skillIds the Skill filter; empty for none
	 */
	Page<MentorCard> search(String query, Collection<UUID> skillIds, Pageable pageable);

	/**
	 * The profile of the Mentor with this GitHub username, ignoring letter case, unless the account is
	 * erased; a locked one is not {@code available}. A Mentor with no profile yet is found too, with nothing
	 * declared.
	 */
	Optional<MentorProfile> byLogin(String githubLogin);

	/** The cards of these Mentors, in the order of {@code ids}; unknown IDs are left out. */
	List<MentorCard> cards(Collection<UUID> ids);

	/** An available Mentor's Skills by ID, in their order (AD-38). */
	record MentorSkills(UUID accountId, List<UUID> skillIds) {

		public MentorSkills {
			skillIds = List.copyOf(skillIds);
		}

	}

	/**
	 * Recommendations' pool (AD-17): available Mentors holding any of {@code skillIds}, the
	 * most shared Skills first, then account ID; at most {@code limit}.
	 */
	List<MentorSkills> holdingAny(Collection<UUID> skillIds, int limit);

	/** The Skills of these Mentors while available; any other ID is left out. */
	Map<UUID, List<UUID>> skillIdsOf(Collection<UUID> mentorIds);

	/** The Mentor's own Skills on their profile, available or not, as they see them; empty without a profile. */
	List<UUID> profileSkillIds(UUID mentorId);

	/** Whether the Mentor's profile has a display name (ACC-1), which a bid needs (Story 19.7). */
	boolean hasProfileName(UUID mentorId);

}
