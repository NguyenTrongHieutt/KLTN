package vn.vibefix.identity.internal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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

import vn.vibefix.identity.internal.Profiles.CustomerKind;
import vn.vibefix.identity.internal.Profiles.CustomerProfile;
import vn.vibefix.identity.internal.Profiles.StudentProfile;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;
import vn.vibefix.shared.Role;

/**
 * The caller's own profile (FR-3, FR-4, FR-51): CUS-11 for a Customer, the profile part of STU-5 for
 * a Student. Only the owner reads and saves it here; a profile of another role answers
 * {@code NOT_FOUND}. The GitHub username comes from sign-in and is not part of it.
 */
@RestController
@RequestMapping("/api/v1/profile")
@Access(Gate.SIGNED_IN)
class ProfileController {

	/** A showcased repository: {@code owner/name}, or its {@code https://github.com/} address. */
	static final String REPOSITORY = "^(?:https://github\\.com/)?[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9._-]{1,100}?(?:\\.git)?/?$";

	/**
	 * @param githubLogin the GitHub username, from sign-in
	 * @param customer the Customer's profile, once saved
	 * @param student the Student's profile, once saved
	 */
	record ProfileView(Role role, @Nullable String githubLogin, @Nullable CustomerProfile customer,
			@Nullable StudentProfileView student) {
	}

	/** @param skills the chosen Skills with their current names, in the Student's order (FR-4, AD-38) */
	record StudentProfileView(@Nullable String realName, @Nullable String school, @Nullable Integer yearOfStudy,
			@Nullable String linkedinUrl, boolean showNameAndSchool, List<String> repositories, List<ProfileSkill> skills) {
	}

	/** @param active {@code false} for a Skill the catalog retired: it stays, but cannot be added again */
	record ProfileSkill(UUID id, String name, boolean active) {
	}

	record CustomerProfileRequest(@NotNull CustomerKind kind, @NotBlank @Size(max = 100) String displayName,
			@NotBlank @Email @Size(max = 254) String contactEmail,
			@Nullable @Pattern(regexp = "^\\+?(?:[0-9] ?){7,14}[0-9]$") String phone) {
	}

	record StudentProfileRequest(@Nullable @Size(max = 100) String realName, @Nullable @Size(max = 150) String school,
			@Nullable @Min(1) @Max(8) Integer yearOfStudy,
			@Nullable @Pattern(regexp = "^https://www\\.linkedin\\.com/in/[A-Za-z0-9_%-]{3,100}/?$") String linkedinUrl,
			@NotNull Boolean showNameAndSchool,
			@NotNull @Size(max = 5) List<@NotBlank @Pattern(regexp = REPOSITORY) String> repositories,
			@NotNull @Size(max = 100) List<@NotNull UUID> skillIds) {
	}

	private final Profiles profiles;

	private final Accounts accounts;

	private final CurrentCaller caller;

	ProfileController(Profiles profiles, Accounts accounts, CurrentCaller caller) {
		this.profiles = profiles;
		this.accounts = accounts;
		this.caller = caller;
	}

	@GetMapping
	ProfileView profile() {
		Caller current = this.caller.get();
		UUID id = current.requireAccountId();
		Role role = current.role();
		String login = this.accounts.findById(id).map(Account::githubLogin).orElse(null);
		return new ProfileView(role, login, role == Role.CUSTOMER ? this.profiles.customer(id).orElse(null) : null,
				role == Role.STUDENT ? this.profiles.student(id).map(this::view).orElse(null) : null);
	}

	private StudentProfileView view(StudentProfile profile) {
		List<ProfileSkill> skills = this.profiles.skills(profile.skillIds())
			.stream()
			.map(skill -> new ProfileSkill(skill.id(), skill.name(), skill.active()))
			.toList();
		return new StudentProfileView(profile.realName(), profile.school(), profile.yearOfStudy(), profile.linkedinUrl(),
				profile.showNameAndSchool(), profile.repositories(), skills);
	}

	@PutMapping("/customer")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void saveCustomer(@Valid @RequestBody CustomerProfileRequest request) {
		UUID id = requireRole(Role.CUSTOMER);
		this.profiles.saveCustomer(id, new CustomerProfile(request.kind(), request.displayName().strip(),
				request.contactEmail().strip(), blankToNull(request.phone())));
	}

	@PutMapping("/student")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void saveStudent(@Valid @RequestBody StudentProfileRequest request) {
		UUID id = requireRole(Role.STUDENT);
		this.profiles.saveStudent(id, new StudentProfile(blankToNull(request.realName()), blankToNull(request.school()),
				request.yearOfStudy(), blankToNull(request.linkedinUrl()), request.showNameAndSchool(),
				repositories(request.repositories()), request.skillIds()));
	}

	private UUID requireRole(Role role) {
		Caller current = this.caller.get();
		if (current.role() != role) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		return current.requireAccountId();
	}

	/** {@code owner/name} once each, in order, from either spelling. */
	static List<String> repositories(List<String> given) {
		LinkedHashSet<String> seen = new LinkedHashSet<>();
		List<String> keys = new ArrayList<>();
		for (String repository : given) {
			String reference = repository.strip().replaceFirst("^https://github\\.com/", "").replaceFirst("/$", "")
				.replaceFirst("\\.git$", "");
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
