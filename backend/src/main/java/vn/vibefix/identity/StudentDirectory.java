package vn.vibefix.identity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Students' public profile fields (FR-51, AD-36): never contact details, and the name and school
 * only while the Student chooses to show them. Search serves approved Mentors' screens; the public
 * profile (PUB-4) is for anyone, and no Customer screen links to it.
 */
public interface StudentDirectory {

	/**
	 * A Student's public card.
	 * @param name the Student's name, only while they show it
	 * @param school the Student's school, only while they show it
	 * @param yearOfStudy the year of study, only while they show their name and school
	 * @param skills the Skills' current names, in the Student's order
	 * @param verifiedUntil the last day of the verification, if they ever verified
	 * @param available an unlocked, unerased account
	 */
	record StudentCard(UUID accountId, @Nullable String githubLogin, @Nullable String name, @Nullable String school,
			@Nullable Integer yearOfStudy, List<String> skills, boolean verified, @Nullable LocalDate verifiedUntil,
			boolean available) {

		public StudentCard {
			skills = List.copyOf(skills);
		}

	}

	/**
	 * Verified, unlocked Students whose public fields match {@code query} and who hold one of
	 * {@code skillIds}, best match first (AD-36).
	 * @param query the keyword; blank for the Skill filter alone
	 * @param skillIds the Skill filter; empty for none
	 */
	Page<StudentCard> search(String query, Collection<UUID> skillIds, Pageable pageable);

	/** The verified, unlocked Student with this GitHub username, ignoring letter case. */
	Optional<StudentCard> byLogin(String githubLogin);

	/** The cards of these Students, verified or not, in the order of {@code ids}; unknown IDs are left out. */
	List<StudentCard> cards(Collection<UUID> ids);

	/**
	 * A Student's public profile (PUB-4, FR-51): the card and the Skills by Skill Category.
	 * @param memberSince when the account was created
	 */
	record StudentProfile(StudentCard card, Instant memberSince, List<SkillGroup> skillGroups) {

		public StudentProfile {
			skillGroups = List.copyOf(skillGroups);
		}

	}

	/**
	 * The public profile of the Student with this GitHub username, ignoring letter case, verified or
	 * not, while the account is unlocked and not erased (PUB-4, FR-51).
	 */
	Optional<StudentProfile> profileByLogin(String githubLogin);

}
