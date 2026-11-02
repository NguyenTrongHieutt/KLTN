package vn.vibefix.dispute.internal;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import vn.vibefix.identity.MentorDirectory;
import vn.vibefix.identity.PersonDirectory;

/**
 * What a Split Resolution's screens and notices call its people, as JOB-10 calls them: the split's Mentor by their directory
 * name, every other party by their shown name. A person anonymized by an erasure has no account to name.
 */
@Component
class SplitResolutionPeople {

	/** What a notice calls someone nothing names. */
	private static final String NOBODY_NAMED = "Một thành viên";

	private final PersonDirectory people;

	private final MentorDirectory mentors;

	SplitResolutionPeople(PersonDirectory people, MentorDirectory mentors) {
		this.people = people;
		this.mentors = mentors;
	}

	/** Each account's name; one nothing names is left out. */
	Map<UUID, String> names(Collection<UUID> accounts, @Nullable UUID mentorId) {
		Map<UUID, String> names = new HashMap<>();
		if (accounts.isEmpty()) {
			return names;
		}
		this.people.cards(accounts).forEach(card -> {
			if (!card.shownName().isBlank()) {
				names.put(card.accountId(), card.shownName());
			}
		});
		if (mentorId != null && accounts.contains(mentorId)) {
			this.mentors.cards(List.of(mentorId))
				.stream()
				.findFirst()
				.map(MentorDirectory.MentorCard::displayName)
				.filter(name -> !name.isBlank())
				.ifPresent(name -> names.put(mentorId, name));
		}
		return names;
	}

	/** What a notice calls one person. */
	String nameOf(UUID account, @Nullable UUID mentorId) {
		return names(List.of(account), mentorId).getOrDefault(account, NOBODY_NAMED);
	}

	/** Each account's GitHub numeric ID, by which a Handover Commit's author is matched to a party (AD-33). */
	Map<Long, UUID> byGithubId(Collection<UUID> accounts) {
		Map<Long, UUID> byId = new HashMap<>();
		if (accounts.isEmpty()) {
			return byId;
		}
		this.people.cards(accounts).forEach(card -> {
			Long githubId = card.githubUserId();
			if (githubId != null) {
				byId.put(githubId, card.accountId());
			}
		});
		return byId;
	}

}
