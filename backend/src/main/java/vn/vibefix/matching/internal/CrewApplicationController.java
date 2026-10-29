package vn.vibefix.matching.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import vn.vibefix.catalog.Catalog;
import vn.vibefix.files.PreparedFile;
import vn.vibefix.identity.ActiveAccount;
import vn.vibefix.identity.GitHubGate;
import vn.vibefix.identity.PublicProfiles;
import vn.vibefix.identity.PublicProfiles.PersonFacts;
import vn.vibefix.job.CrewRecruitment;
import vn.vibefix.job.CrewRecruitment.Recruitment;
import vn.vibefix.job.JobPosts;
import vn.vibefix.job.JobPosts.JobPostState;
import vn.vibefix.job.JobRoles;
import vn.vibefix.matching.internal.ApplicationController.ApplicantView;
import vn.vibefix.matching.internal.ApplicationController.BidProofRequest;
import vn.vibefix.matching.internal.ApplicationController.BidProofView;
import vn.vibefix.matching.internal.ApplicationController.FitMetricsView;
import vn.vibefix.matching.internal.BidProofController.BidProofUploadView;
import vn.vibefix.matching.internal.BidProofs.Item;
import vn.vibefix.matching.internal.BidProofs.Proof;
import vn.vibefix.matching.internal.BidProofs.Upload;
import vn.vibefix.matching.internal.CrewApplications.CrewApplication;
import vn.vibefix.matching.internal.CrewApplications.Draft;
import vn.vibefix.matching.internal.CrewApplications.EndReason;
import vn.vibefix.matching.internal.CrewApplications.Status;
import vn.vibefix.matching.internal.FitMetrics.Metrics;
import vn.vibefix.matching.internal.SourceContributors.Contribution;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;

/**
 * Crew Applications (FR-81, AD-20, AD-43, UX-DR102, UX-DR103, Story 21.4): any user with GitHub linked applies from
 * FND-2 to a Job whose Mentor recruits (AD-19 new-work command), with a note, Skills and at most five proof items, and an
 * unlocked user lists or withdraws their own (MEN-3, CUS-7); the Job's Mentor reads them as JOB-9's Applicant cards and
 * declines one with "Không chọn". Nobody else finds them: the Customer gets {@code NOT_FOUND} for every endpoint here,
 * and an Administrator, who holds no role in any Job, finds nothing (SG-31); no Administrator grant covers them. The
 * Source Repository's contributors are read before the application's transaction (A-03).
 */
@RestController
@Access(Gate.SIGNED_IN)
class CrewApplicationController {

	/**
	 * FND-2's "Gửi đơn": a note to the Mentor, the Skills (pre-filled from ACC-1), N-5, and the proof in its order; no
	 * price and no share (R5).
	 * @param skillIds at most 10, each active in the catalog
	 * @param proof at most five items; more answer {@code VALIDATION PROOF_LIMIT}; absent for none
	 */
	record CrewApplyRequest(@NotBlank @Size(max = 2000) String note,
			@Nullable @Size(max = CrewApplications.MAX_SKILLS) List<@NotNull UUID> skillIds, boolean publicCodeAcknowledged,
			@Nullable List<@Valid @NotNull BidProofRequest> proof) {
	}

	/** A Skill named on an application, as it was named when sent. */
	record CrewApplicationSkillView(UUID id, String name) {
	}

	/**
	 * One of the user's own Crew Applications, with its proof (MEN-3, CUS-7).
	 * @param lapsesAt when it lapses while it is pending (R6)
	 * @param endReason why it ended: {@code DECLINED} ("not picked"), {@code WITHDRAWN}, {@code APPLICANT_UNAVAILABLE},
	 * or a lapse ({@code EXPIRED}, {@code RECRUITMENT_CLOSED}, {@code JOB_CLOSING}, {@code JOB_CANCELLED})
	 */
	record CrewApplicationView(UUID id, UUID jobId, String jobTitle, Status status, String note,
			List<CrewApplicationSkillView> skills, Instant submittedAt, Instant lapsesAt, @Nullable Instant endedAt,
			@Nullable EndReason endReason, List<BidProofView> proof) {
	}

	/**
	 * One Applicant card on JOB-9 (UX-DR101, UX-DR102), in the Bid card's order: the applicant's Verified facts, then the
	 * note, Skills and proof, then (from {@code reputation} by the GitHub username) the histories, then the Fit metrics
	 * against the Skills sought. A closed one ("Đơn đã đóng") carries neither its proof nor its metrics.
	 * @param fitMetrics absent for a closed application, or an applicant {@code identity} no longer knows
	 */
	record ApplicantCardView(UUID id, Status status, String note, List<CrewApplicationSkillView> skills,
			Instant submittedAt, Instant lapsesAt, @Nullable Instant endedAt, @Nullable EndReason endReason,
			ApplicantView applicant, List<BidProofView> proof, @Nullable FitMetricsView fitMetrics) {
	}

	private final CrewApplications applications;

	private final CrewApplicationProofs proofs;

	private final SourceContributors contributors;

	private final CrewRecruitment recruitment;

	private final JobPosts posts;

	private final JobRoles roles;

	private final PublicProfiles profiles;

	private final FitMetrics fitMetrics;

	private final Catalog catalog;

	private final GitHubGate gitHub;

	private final ActiveAccount gate;

	private final CurrentCaller callers;

	CrewApplicationController(CrewApplications applications, CrewApplicationProofs proofs,
			SourceContributors contributors, CrewRecruitment recruitment, JobPosts posts, JobRoles roles,
			PublicProfiles profiles, FitMetrics fitMetrics, Catalog catalog, GitHubGate gitHub, ActiveAccount gate,
			CurrentCaller callers) {
		this.applications = applications;
		this.proofs = proofs;
		this.contributors = contributors;
		this.recruitment = recruitment;
		this.posts = posts;
		this.roles = roles;
		this.profiles = profiles;
		this.fitMetrics = fitMetrics;
		this.catalog = catalog;
		this.gitHub = gitHub;
		this.gate = gate;
		this.callers = callers;
	}

	/**
	 * FND-2's "Gửi đơn" (FR-81): the cheap refusals first, so an application refused anyway asks GitHub nothing; then
	 * GitHub is read here, before the application's transaction and its locks, and the answer is passed in (A-03).
	 */
	@PostMapping("/api/v1/mentor/job-posts/{postId}/crew-applications")
	@ResponseStatus(HttpStatus.CREATED)
	CrewApplicationView apply(@PathVariable UUID postId, @Valid @RequestBody CrewApplyRequest body) {
		UUID applicant = user();
		this.gitHub.requireGitHubLinked(applicant);
		if (!body.publicCodeAcknowledged()) {
			throw new ApiException(ErrorCode.VALIDATION, MatchingReason.PUBLIC_CODE_NOT_ACKNOWLEDGED);
		}
		List<Item> proof = BidProofs.requireShape(body.proof() == null ? List.of()
				: body.proof().stream().map(item -> new Item(item.kind(), item.fileId(), item.url(), item.caption())).toList());
		this.applications.precheck(applicant, postId);
		Contribution contribution = this.contributors.readForCrew(postId, applicant);
		CrewApplication sent = this.applications.send(applicant, postId,
				new Draft(body.note(), body.skillIds() == null ? List.of() : body.skillIds()), proof, contribution);
		return views(List.of(sent)).getFirst();
	}

	/** MEN-3 and CUS-7 "Việc tôi tham gia": the user's own Crew Applications, newest first, each with its proof. */
	@GetMapping("/api/v1/mentor/crew-applications")
	List<CrewApplicationView> mine() {
		UUID applicant = user();
		this.gate.requireActiveAccount(applicant);
		return views(this.applications.ofApplicant(applicant));
	}

	/** MEN-3's "Rút": the user's pending application ends. */
	@PostMapping("/api/v1/mentor/crew-applications/{crewApplicationId}/withdraw")
	CrewApplicationView withdraw(@PathVariable UUID crewApplicationId) {
		UUID applicant = user();
		this.gate.requireActiveAccount(applicant);
		return views(List.of(this.applications.withdraw(applicant, crewApplicationId))).getFirst();
	}

	/**
	 * JOB-9's Applicant cards (UX-DR102): the pending applications oldest first and never reordered, then the closed
	 * ones; each with the applicant's Verified facts, the note, Skills and proof, and the Fit metrics with the Skills
	 * sought. Only the Job's Mentor reads them.
	 */
	@GetMapping("/api/v1/mentor/jobs/{jobId}/crew-applications")
	List<ApplicantCardView> ofJob(@PathVariable UUID jobId) {
		mentorOf(jobId);
		Recruitment found = this.recruitment.recruitment(jobId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		List<CrewApplication> all = this.applications.ofJob(jobId);
		List<CrewApplication> open = all.stream().filter(application -> application.status() == Status.PENDING).toList();
		List<UUID> people = all.stream().map(CrewApplication::applicantId).distinct().toList();
		Map<UUID, PersonFacts> facts = this.profiles.factsOf(people);
		// The Skills sought, or the Job Post's while the Mentor seeks none, so a shared-Skills metric never reads "0 of 0".
		Map<UUID, Metrics> metrics = this.fitMetrics.fitMetrics(jobId,
				open.stream().map(CrewApplication::applicantId).toList(), facts,
				found.skillIds().isEmpty() ? null : found.skillIds());
		Map<UUID, String> skillNames = ApplicationController.skillNames(this.catalog, metrics.values());
		Map<UUID, List<Proof>> proof = this.proofs.of(open.stream().map(CrewApplication::id).toList());
		return all.stream().map(application -> {
			PersonFacts person = facts.get(application.applicantId());
			ApplicantView applicant = person == null ? new ApplicantView("", null, null, null, List.of())
					: new ApplicantView(person.displayName(), person.githubLogin(), person.yearsOfExperience(),
							person.memberSince(), person.verified());
			boolean pending = application.status() == Status.PENDING;
			Metrics fit = pending ? metrics.get(application.applicantId()) : null;
			return new ApplicantCardView(application.id(), application.status(), application.note(), skills(application),
					application.submittedAt(), application.lapsesAt(), application.endedAt(), application.endReason(),
					applicant, pending ? ApplicationController.proofViews(proof.get(application.id())) : List.of(),
					fit == null ? null : ApplicationController.fitView(fit, skillNames));
		}).toList();
	}

	/** JOB-9's "Không chọn" (UX-DR102): no reason asked; the applicant learns only that they were not picked. */
	@PostMapping("/api/v1/mentor/jobs/{jobId}/crew-applications/{crewApplicationId}/decline")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void decline(@PathVariable UUID jobId, @PathVariable UUID crewApplicationId) {
		UUID mentor = mentorOf(jobId);
		this.applications.decline(mentor, jobId, crewApplicationId);
	}

	/**
	 * FND-2 "Thêm tệp": an image or a PDF of at most the upload limit for the user's application to a recruiting Job
	 * where they hold no role and have no pending application, checked before anything is kept (AD-21).
	 */
	@PostMapping(path = "/api/v1/mentor/job-posts/{postId}/crew-application-files",
			consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@ResponseStatus(HttpStatus.CREATED)
	BidProofUploadView upload(@PathVariable UUID postId, @RequestPart("file") MultipartFile file) {
		UUID applicant = user();
		this.gitHub.requireGitHubLinked(applicant);
		Recruitment found = this.applications.requireApplicable(applicant, postId);
		byte[] content;
		try {
			content = file.getBytes();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		PreparedFile prepared = this.proofs.prepare(applicant,
				file.getOriginalFilename() == null ? "" : file.getOriginalFilename(), content);
		return view(this.proofs.add(applicant, found.jobId(), found.productId(), prepared));
	}

	/** FND-2: the user's uploads waiting for their application to the Job, oldest first. */
	@GetMapping("/api/v1/mentor/job-posts/{postId}/crew-application-files")
	List<BidProofUploadView> pending(@PathVariable UUID postId) {
		UUID applicant = user();
		this.gitHub.requireGitHubLinked(applicant);
		return this.proofs.pending(postId, applicant).stream().map(CrewApplicationController::view).toList();
	}

	/** Removes an upload no application carries yet, and its file. */
	@DeleteMapping("/api/v1/mentor/job-posts/{postId}/crew-application-files/{fileId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void remove(@PathVariable UUID postId, @PathVariable UUID fileId) {
		UUID applicant = user();
		this.gate.requireActiveAccount(applicant);
		this.proofs.remove(applicant, postId, fileId);
	}

	private List<CrewApplicationView> views(List<CrewApplication> applications) {
		Map<UUID, String> titles = new HashMap<>();
		Map<UUID, List<Proof>> proof = this.proofs.of(applications.stream().map(CrewApplication::id).toList());
		return applications.stream().map(application -> {
			String title = titles.computeIfAbsent(application.jobId(),
					id -> this.posts.post(id).map(JobPostState::title).orElse(""));
			return new CrewApplicationView(application.id(), application.jobId(), title, application.status(),
					application.note(), skills(application), application.submittedAt(), application.lapsesAt(),
					application.endedAt(), application.endReason(), ApplicationController.proofViews(proof.get(application.id())));
		}).toList();
	}

	private static List<CrewApplicationSkillView> skills(CrewApplication application) {
		List<CrewApplicationSkillView> skills = new ArrayList<>();
		for (int i = 0; i < application.skillIds().size(); i++) {
			skills.add(new CrewApplicationSkillView(application.skillIds().get(i), application.skillNames().get(i)));
		}
		return skills;
	}

	private static BidProofUploadView view(Upload upload) {
		return new BidProofUploadView(upload.fileId(), upload.fileName(), upload.size(), upload.addedAt(),
				"/api/v1/files/" + upload.fileId());
	}

	/** A person's account: an Administrator holds no role in any Job, so it finds nothing (SG-31). */
	private UUID user() {
		Caller caller = this.callers.get();
		if (!caller.isUser()) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		return caller.requireAccountId();
	}

	/**
	 * The Job's Mentor calling, by their role in the Job (AD-43), unlocked (AD-19 Job command); anyone else, the
	 * Customer included, finds nothing (AD-20).
	 */
	private UUID mentorOf(UUID jobId) {
		Caller caller = this.callers.get();
		if (!this.roles.roleOf(jobId, caller).isMentor()) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		UUID mentor = caller.requireAccountId();
		this.gate.requireActiveAccount(mentor);
		return mentor;
	}

}
