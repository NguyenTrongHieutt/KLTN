package vn.vibefix.identity;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Students' public profile fields (FR-51, AD-36), for approved Mentors' screens only: never contact
 * details, and the name and school only while the Student chooses to show them. Customers never
 * reach this API's callers.
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

}
