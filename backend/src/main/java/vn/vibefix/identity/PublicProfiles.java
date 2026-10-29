package vn.vibefix.identity;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * What anyone may read of a person (FR-51, AD-42, UX-DR96, Story 21.3): PUB-4's identity half, and the same Verified
 * facts for a page of people, such as the bidders of CUS-8 and the results of "Tìm người hướng dẫn". Never contact
 * details, a Balance, or the school email; the student's school, declared name, and major only while the owner shows
 * them (X9). A person is addressed by the stored GitHub username of their linked account, refreshed at every link.
 */
public interface PublicProfiles {

	/** The Verified facts, in their fixed order (UX-DR96): GitHub, then the student facts, then LinkedIn. */
	enum FactKind {

		GITHUB, STUDENT, LINKEDIN

	}

	/**
	 * One Verified fact: "Xác minh qua {nguồn} · {tháng/năm}", or "Chưa xác minh" while {@code verifiedAt} is
	 * absent. Every list of them holds the three kinds, in {@link FactKind}'s order.
	 * @param verifiedAt when it was verified (GitHub: last linked; the student facts: verified); absent when not
	 * verified now
	 * @param githubLogin the linked GitHub username, for {@code GITHUB}
	 * @param school the school of a current student verification, unless the owner hid it (X9)
	 * @param name the full name declared at verification ("Khai khi xác minh"), unless hidden (X9)
	 * @param major the major declared at verification ("Khai khi xác minh"), unless hidden (X9)
	 */
	record VerifiedFact(FactKind kind, @Nullable Instant verifiedAt, @Nullable String githubLogin, @Nullable String school,
			@Nullable String name, @Nullable String major) {

		public boolean verified() {
			return this.verifiedAt != null;
		}

	}

	/**
	 * A person's public facts.
	 * @param displayName the one name rule (Story 19.3): the display name, else {@code @login}, else the Google name
	 * @param githubLogin the linked GitHub username; absent while GitHub is not linked
	 * @param yearsOfExperience "Tự khai"; absent while not declared
	 * @param memberSince when the account was created: "Tham gia vibefix từ {month}"
	 * @param verified the three Verified facts, in their fixed order
	 * @param skillIds the profile's Skills, in the person's order
	 * @param locked the account is locked: PUB-4 then shows only that the person is not active
	 */
	record PersonFacts(UUID accountId, String displayName, @Nullable String githubLogin, @Nullable Integer yearsOfExperience,
			Instant memberSince, List<VerifiedFact> verified, List<UUID> skillIds, boolean locked) {

		public PersonFacts {
			verified = List.copyOf(verified);
			skillIds = List.copyOf(skillIds);
		}

		/** The Verified facts held now, in their fixed order: the fit metric "Verifications held" (AD-17). */
		public List<FactKind> held() {
			return this.verified.stream().filter(VerifiedFact::verified).map(VerifiedFact::kind).toList();
		}

	}

	/**
	 * PUB-4's identity half: the facts, then the typed profile ("Tự khai"), and the Skills by Skill Category.
	 * @param experience what the person typed about their work; absent while not typed
	 * @param links the typed {@code https} links, in order
	 * @param repositories the showcased repositories as {@code owner/name}, in order
	 */
	record PublicPerson(PersonFacts facts, @Nullable String experience, List<String> links, List<String> repositories,
			List<SkillGroup> skillGroups) {

		public PublicPerson {
			links = List.copyOf(links);
			repositories = List.copyOf(repositories);
			skillGroups = List.copyOf(skillGroups);
		}

	}

	/**
	 * The person whose linked GitHub account has the username {@code username}, ignoring letter case; none when no
	 * linked account has it, or when more than one does. A locked person is found, {@code locked}.
	 */
	Optional<PublicPerson> byUsername(String username);

	/**
	 * The accounts of these usernames by the rule of {@link #byUsername}, keyed by the username as asked; a username
	 * with no person, or with a locked one, is left out.
	 */
	Map<String, UUID> unlockedAccountsOf(Collection<String> usernames);

	/** The public facts of these people, keyed by account ID, in the order asked; an unknown or erased ID is left out. */
	Map<UUID, PersonFacts> factsOf(Collection<UUID> accountIds);

}
