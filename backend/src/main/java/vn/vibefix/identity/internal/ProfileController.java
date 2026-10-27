package vn.vibefix.identity.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.identity.internal.Profiles.Changes;
import vn.vibefix.identity.internal.Profiles.Profile;
import vn.vibefix.identity.internal.Profiles.ProfileKind;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;
import vn.vibefix.shared.Kind;

/**
 * ACC-1 (Story 19.3, FR-3, FR-4, FR-51): the caller's one profile, whatever role it holds in a Job.
 * The typed zone ("Bạn tự nhập") saves without any approval; the verified zone ("Đã xác minh") is
 * read from the GitHub link and the current student verification, and no field of a save changes it
 * (AD-42). Only a person's account has a profile: an Administrator finds none.
 */
@RestController
@RequestMapping("/api/v1/profile")
@Access(Gate.SIGNED_IN)
class ProfileController {

	/** A showcased repository: {@code owner/name}, or its {@code https://github.com/} address. */
	static final String REPOSITORY = "^(?:https://github\\.com/)?[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9._-]{1,100}?(?:\\.git)?/?$";

	/** A typed link: {@code https} only, with no whitespace of any script, as identity.profile_link checks it too. */
	static final String LINK = "(?U)^https://\\S+$";

	/** A phone number, as identity.profile checks it. */
	static final String PHONE = "^\\+?[0-9 ]{8,20}$";

	/**
	 * @param displayName absent until the first save
	 * @param skills the chosen Skills with their current names, in the person's order (FR-4, AD-38)
	 * @param links the typed links, each shown with "Tự khai"
	 * @param repositories showcased repositories as {@code owner/name}, metadata only: their code is never fetched
	 * @param hideStudentDetails "Ẩn thông tin sinh viên trên hồ sơ công khai", off by default (X9)
	 * @param studentDetails the student details, shown "Khai khi xác minh"; absent when there are none
	 * @param verified what the System verified, read-only here
	 */
	record ProfileView(@Nullable String displayName, ProfileKind kind, @Nullable String contactEmail,
			@Nullable String phone, @Nullable String experience, @Nullable Integer yearsOfExperience,
			List<ProfileSkill> skills, List<String> links, List<String> repositories, boolean hideStudentDetails,
			@Nullable StudentDetailsView studentDetails, VerifiedView verified) {
	}

	/** @param active {@code false} for a Skill the catalog retired: it stays, but cannot be added again */
	record ProfileSkill(UUID id, String name, boolean active) {
	}

	/**
	 * The live student verification's facts as the profile holds them (Story 19.4): what the public profile
	 * shows unless {@code hideStudentDetails}, and what search reads. Absent while the verification is not
	 * current, as its facts are.
	 */
	record StudentDetailsView(@Nullable String name, @Nullable String school, @Nullable String major) {
	}

	/**
	 * ACC-1's "Đã xác minh" zone; each fact is absent while it is not verified, or lapsed.
	 * @param linkedin always absent until Story 19.6
	 */
	record VerifiedView(@Nullable GitHubFact github, @Nullable StudentFact student, @Nullable LinkedInFact linkedin) {
	}

	/** @param since when GitHub was last linked: "Xác minh qua GitHub · {tháng/năm}" */
	record GitHubFact(String login, Instant since) {
	}

	/**
	 * @param validUntil the last valid day, a 30 September
	 * @param method how the account verified: {@code SCHOOL_EMAIL}
	 * @param school the allowlisted domain the school email matched
	 * @param name the full name declared at verification ("Khai khi xác minh")
	 * @param major the major declared at verification ("Khai khi xác minh")
	 * @param verifiedAt when it was verified: "Xác minh qua email trường · {tháng/năm}"
	 */
	record StudentFact(LocalDate validUntil, String method, String school, @Nullable String name,
			@Nullable String major, Instant verifiedAt) {
	}

	/** Verified on LinkedIn (Story 19.6). */
	record LinkedInFact(Instant since) {
	}

	/**
	 * ACC-1's typed zone. Skills are checked against the catalog: at most 20, new ones active.
	 * @param kind {@code INDIVIDUAL} when absent
	 */
	record ProfileRequest(@NotBlank @Size(max = 100) String displayName, @Nullable ProfileKind kind,
			@Nullable @Email @Size(max = 254) String contactEmail,
			@Nullable @Pattern(regexp = PHONE) String phone,
			@Nullable @Size(max = 2000) String experience, @Nullable @Min(0) @Max(60) Integer yearsOfExperience,
			@NotNull @Size(max = 100) List<@NotNull UUID> skillIds,
			@NotNull @Size(max = Profiles.MAX_LINKS) List<@NotBlank @Size(max = 500) @Pattern(regexp = LINK) String> links,
			@NotNull @Size(max = Profiles.MAX_LINKS) List<@NotBlank @Pattern(regexp = REPOSITORY) String> repositories,
			@NotNull Boolean hideStudentDetails) {

		/** Links are checked without the spaces around them, as they are saved. */
		ProfileRequest {
			links = links == null ? null : links.stream().map(link -> link == null ? null : link.strip()).toList();
		}

	}

	private final Profiles profiles;

	private final Accounts accounts;

	private final StudentVerifications verifications;

	private final CurrentCaller caller;

	ProfileController(Profiles profiles, Accounts accounts, StudentVerifications verifications, CurrentCaller caller) {
		this.profiles = profiles;
		this.accounts = accounts;
		this.verifications = verifications;
		this.caller = caller;
	}

	@GetMapping
	ProfileView profile() {
		Account account = requireUser();
		UUID id = account.id();
		Profile profile = this.profiles.profile(id);
		List<ProfileSkill> skills = this.profiles.skills(profile.skillIds())
			.stream()
			.map(skill -> new ProfileSkill(skill.id(), skill.name(), skill.active()))
			.toList();
		Profiles.StudentDetails details = profile.studentDetails();
		GitHubFact github = account.githubUserId() == null || account.githubLogin() == null ? null
				: this.accounts.gitHubLinkedAt(id).map(since -> new GitHubFact(account.githubLogin(), since)).orElse(null);
		StudentFact student = this.verifications.currentVerification(id)
			.filter(found -> found.facts() != null)
			.map(found -> new StudentFact(found.validUntil(), found.provider().method(), found.facts().school(),
					found.facts().name(), found.facts().major(), found.verifiedAt()))
			.orElse(null);
		return new ProfileView(profile.displayName(), profile.kind(), profile.contactEmail(), profile.phone(),
				profile.experience(), profile.yearsOfExperience(), skills, profile.links(), profile.repositories(),
				profile.hideStudentDetails(),
				// The projection is shown only while the verification it copies is current.
				details == null || student == null ? null
						: new StudentDetailsView(details.name(), details.school(), details.major()),
				new VerifiedView(github, student, null));
	}

	/**
	 * Saves the typed zone, with no approval, whatever role the account holds; a verified fact is never
	 * written here (AD-42).
	 */
	@PutMapping
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void save(@Valid @RequestBody ProfileRequest request) {
		Account account = requireUser();
		Changes changes = new Changes(request.displayName().strip(),
				request.kind() == null ? ProfileKind.INDIVIDUAL : request.kind(), blankToNull(request.contactEmail()),
				blankToNull(request.phone()), blankToNull(request.experience()), request.yearsOfExperience(),
				request.skillIds(), links(request.links()), repositories(request.repositories()),
				request.hideStudentDetails());
		this.profiles.save(account.id(), changes);
	}

	/** The caller's account, if it is a person's; an Administrator has no profile. */
	private Account requireUser() {
		return this.accounts.findById(this.caller.get().requireAccountId())
			.filter(found -> found.kind() == Kind.USER)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	/** Each link once, in order, without surrounding spaces. */
	static List<String> links(List<String> given) {
		return List.copyOf(new LinkedHashSet<>(given.stream().map(String::strip).toList()));
	}

	/**
	 * {@code owner/name} once each, in order, from either spelling.
	 * @throws ApiException {@code VALIDATION} on {@code repositories} for a name that is empty once
	 * {@code .git} is taken off
	 */
	static List<String> repositories(List<String> given) {
		LinkedHashSet<String> seen = new LinkedHashSet<>();
		List<String> keys = new ArrayList<>();
		for (String repository : given) {
			String reference = repository.strip().replaceFirst("^https://github\\.com/", "").replaceFirst("/$", "")
				.replaceFirst("\\.git$", "");
			if (reference.endsWith("/")) {
				throw Profiles.invalid(Map.of("repositories", "Pattern"));
			}
			// GitHub names are case-insensitive: one repository, however it is spelled.
			if (seen.add(reference.toLowerCase(Locale.ROOT))) {
				keys.add(reference);
			}
		}
		return keys;
	}

	private static @Nullable String blankToNull(@Nullable String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}

}
