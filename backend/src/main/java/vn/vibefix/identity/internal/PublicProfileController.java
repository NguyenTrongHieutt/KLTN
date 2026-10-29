package vn.vibefix.identity.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.identity.PublicProfiles;
import vn.vibefix.identity.PublicProfiles.PersonFacts;
import vn.vibefix.identity.PublicProfiles.PublicPerson;
import vn.vibefix.identity.PublicProfiles.VerifiedFact;
import vn.vibefix.identity.SkillGroup;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;

/**
 * PUB-4's identity half (`/nguoi-dung/{username}`, FR-51, UX-DR96, Story 21.3), for anyone: the display name, the
 * Verified facts in their fixed order, the typed profile ("Tự khai"), the Skills by Skill Category, and the month
 * joined; {@code reputation} serves the two histories. Never contact details, a Balance, the school email, or a role
 * in a running Job. A locked person shows only that they are not active. The username is the stored GitHub username
 * of one linked account; none, or two, is not found.
 */
@RestController
@Access(Gate.PUBLIC)
class PublicProfileController {

	/**
	 * PUB-4's identity half.
	 * @param locked the person is locked: every other field is empty ("Người dùng này hiện không hoạt động trên vibefix")
	 * @param verified the three Verified facts, GitHub, then the student facts, then LinkedIn
	 * @param experience "Tự khai"; absent while not typed
	 * @param yearsOfExperience "Tự khai"; absent while not declared
	 * @param links the typed links, each "Tự khai"
	 * @param repositories the showcased repositories as {@code owner/name}, each "Tự khai"
	 * @param memberSince when the account was created; absent when locked
	 * @param own the caller is this person: "Đây là hồ sơ người khác thấy" with "Sửa hồ sơ"
	 */
	record PublicProfileView(boolean locked, @Nullable String displayName, @Nullable String githubLogin,
			List<VerifiedFact> verified, @Nullable String experience, @Nullable Integer yearsOfExperience, List<String> links,
			List<String> repositories, List<SkillGroup> skillGroups, @Nullable Instant memberSince, boolean own) {
	}

	private final PublicProfiles profiles;

	private final CurrentCaller callers;

	PublicProfileController(PublicProfiles profiles, CurrentCaller callers) {
		this.profiles = profiles;
		this.callers = callers;
	}

	@GetMapping("/api/v1/users/{username}")
	PublicProfileView person(@PathVariable String username) {
		PublicPerson person = this.profiles.byUsername(username).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		PersonFacts facts = person.facts();
		if (facts.locked()) {
			return new PublicProfileView(true, null, null, List.of(), null, null, List.of(), List.of(), List.of(), null,
					false);
		}
		Caller caller = this.callers.get();
		UUID self = caller.isUser() ? caller.requireAccountId() : null;
		return new PublicProfileView(false, facts.displayName(), facts.githubLogin(), facts.verified(), person.experience(),
				facts.yearsOfExperience(), person.links(), person.repositories(), person.skillGroups(), facts.memberSince(),
				facts.accountId().equals(self));
	}

}
