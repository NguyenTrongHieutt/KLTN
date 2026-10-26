package vn.vibefix.identity.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import vn.vibefix.files.PreparedFile;
import vn.vibefix.identity.MentorApprovalStatus;
import vn.vibefix.identity.internal.MentorApplications.Document;
import vn.vibefix.identity.internal.MentorApplications.Fields;
import vn.vibefix.identity.internal.ProfileController.ProfileSkill;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;
import vn.vibefix.shared.Role;

/**
 * MEN-5 (`/ho-so/nguoi-huong-dan`, FR-74, until Story 19.7): the Mentor application, which every
 * Mentor may read and save whatever its approval status, since it is how a Mentor gets approved, and
 * its optional document. The display name, the experience, the years, and the Skills are the
 * profile's (Story 19.3); the rest is the application's own. Any other role finds nothing here.
 */
@RestController
@RequestMapping("/api/v1/profile/mentor")
@Access(Gate.SIGNED_IN)
class MentorProfileController {

	/** A LinkedIn profile address, as identity.mentor_application checks it too. */
	static final String LINKEDIN = "^https://www\\.linkedin\\.com/in/[A-Za-z0-9_%-]{3,100}/?$";

	/**
	 * @param privacyNoticeVersion the personal-data notice version the Mentor accepted; the first save,
	 * which submits the application, needs the current one
	 * @param otherLink GitHub, a blog, or a product, {@code https} only
	 */
	record MentorApplicationRequest(@NotBlank @Size(max = 100) String displayName,
			@NotBlank @Size(max = 2000) String experience, @NotNull @Min(0) @Max(60) Integer yearsOfExperience,
			@NotNull @Size(min = 1, max = 100) List<@NotNull UUID> skillIds,
			@NotBlank @Pattern(regexp = LINKEDIN) String linkedinUrl,
			@Nullable @Size(max = 300) @Pattern(regexp = ProfileController.LINK) String otherLink,
			@Nullable String privacyNoticeVersion) {
	}

	/**
	 * MEN-5: the Mentor application as saved, and where it stands.
	 * @param approvalStatus {@code PENDING} until an Administrator decides (FR-74)
	 * @param submittedAt absent until the first save, which submits it
	 * @param document the attached document's name, type, and size; only Administrators read it
	 * @param decidedAt once decided, when the latest decision was made
	 * @param rejectionReason while {@code REJECTED}, the reason the Administrator gave
	 * @param reapplyAfter while {@code REJECTED}, from when the Mentor may submit again
	 */
	record MentorProfileView(MentorApprovalStatus approvalStatus, @Nullable String displayName,
			@Nullable String experience, @Nullable Integer yearsOfExperience, List<ProfileSkill> skills,
			@Nullable String linkedinUrl, @Nullable String otherLink, @Nullable MentorDocumentView document,
			@Nullable Instant submittedAt, @Nullable Instant decidedAt, @Nullable String rejectionReason,
			@Nullable Instant reapplyAfter) {
	}

	/** @param submitted whether this save was the submission */
	record SavedView(boolean submitted) {
	}

	record MentorDocumentView(String name, String mediaType, long size) {
	}

	private final MentorApplications applications;

	private final MentorDecisions decisions;

	private final Profiles profiles;

	private final Accounts accounts;

	private final CurrentCaller caller;

	MentorProfileController(MentorApplications applications, MentorDecisions decisions, Profiles profiles,
			Accounts accounts, CurrentCaller caller) {
		this.applications = applications;
		this.decisions = decisions;
		this.profiles = profiles;
		this.accounts = accounts;
		this.caller = caller;
	}

	/** MEN-5's read: the profile's fields this application shows, and the application itself. */
	@GetMapping
	MentorProfileView read() {
		UUID mentor = requireMentor();
		MentorApprovalStatus status = this.accounts.findById(mentor)
			.map(Account::mentorApprovalStatus)
			.orElse(MentorApprovalStatus.PENDING);
		Profiles.Profile profile = this.profiles.profile(mentor);
		List<ProfileSkill> skills = this.profiles.skills(profile.skillIds())
			.stream()
			.map(skill -> new ProfileSkill(skill.id(), skill.name(), skill.active()))
			.toList();
		Optional<MentorApplications.Application> application = this.applications.find(mentor);
		Optional<MentorDecisions.Decision> decision = status == MentorApprovalStatus.PENDING ? Optional.empty()
				: this.decisions.latest(mentor);
		Document document = application.map(MentorApplications.Application::document).orElse(null);
		return new MentorProfileView(status, profile.displayName(), profile.experience(), profile.yearsOfExperience(),
				skills, application.map(MentorApplications.Application::linkedinUrl).orElse(null),
				application.map(MentorApplications.Application::otherLink).orElse(null),
				document == null ? null : new MentorDocumentView(document.name(), document.mediaType(), document.size()),
				application.map(MentorApplications.Application::submittedAt).orElse(null),
				decision.map(MentorDecisions.Decision::decidedAt).orElse(null),
				decision.map(MentorDecisions.Decision::reason).orElse(null),
				decision.map(MentorDecisions.Decision::reapplyAfter).orElse(null));
	}

	/** Saves the application; the first save submits it for an Administrator's review. */
	@PutMapping
	SavedView save(@Valid @RequestBody MentorApplicationRequest request) {
		UUID mentor = requireMentor();
		String otherLink = request.otherLink() == null || request.otherLink().isBlank() ? null : request.otherLink().strip();
		boolean submitted = this.applications.save(mentor, new Fields(request.displayName(), request.experience(),
				request.yearsOfExperience(), request.skillIds(), request.linkedinUrl(), otherLink),
				request.privacyNoticeVersion());
		return new SavedView(submitted);
	}

	/**
	 * Attaches a CV or a certificate, an image or a PDF of at most 10 MB (AD-21), in place of the
	 * earlier one. Only Administrators read it, and it is deleted 30 days after the decision.
	 */
	@PostMapping(path = "/document", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@ResponseStatus(HttpStatus.CREATED)
	MentorDocumentView uploadDocument(@RequestPart("file") MultipartFile file) {
		UUID mentor = requireMentor();
		byte[] content;
		try {
			content = file.getBytes();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
		PreparedFile prepared = this.applications.prepareDocument(mentor, name, content);
		Document stored = this.applications.storeDocument(mentor, prepared);
		return new MentorDocumentView(stored.name(), stored.mediaType(), stored.size());
	}

	@DeleteMapping("/document")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void deleteDocument() {
		this.applications.deleteDocument(requireMentor());
	}

	private UUID requireMentor() {
		Caller current = this.caller.get();
		if (current.role() != Role.MENTOR) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		return current.requireAccountId();
	}

}
