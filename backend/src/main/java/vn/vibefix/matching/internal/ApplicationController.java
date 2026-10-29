package vn.vibefix.matching.internal;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import vn.vibefix.identity.ActiveAccount;
import vn.vibefix.identity.GitHubGate;
import vn.vibefix.identity.MentorDirectory;
import vn.vibefix.identity.MentorDirectory.MentorCard;
import vn.vibefix.job.JobPosts;
import vn.vibefix.job.JobPosts.JobPostState;
import vn.vibefix.job.JobRoles;
import vn.vibefix.job.JobSelection;
import vn.vibefix.job.JobSelection.SelectedQuote;
import vn.vibefix.matching.internal.Applications.Application;
import vn.vibefix.matching.internal.Applications.EndReason;
import vn.vibefix.matching.internal.Applications.Quote;
import vn.vibefix.matching.internal.Applications.Status;
import vn.vibefix.matching.internal.BidProofs.Item;
import vn.vibefix.matching.internal.BidProofs.ProofKind;
import vn.vibefix.matching.internal.BidProofs.Proof;
import vn.vibefix.matching.internal.SourceContributors.Contribution;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;

/**
 * Bids (FR-13, FR-15, AD-20, Story 21.2): any user with GitHub linked bids from FND-1 (AD-19 new-work command) with a
 * quote and at most five proof items, except on their own Job ({@code ROLE_TAKEN}), and an unlocked user lists or
 * withdraws their own (CUS-7, MEN-3); the Job's Customer reads the open ones with their proof on CUS-8 and selects one
 * (J3). Nobody else finds them. The Source Repository's contributors are read before the bid's transaction (A-03).
 */
@RestController
@Access(Gate.SIGNED_IN)
class ApplicationController {

	/**
	 * FND-1's "Gửi chào giá": the price in whole VND, the days, the approach, N-5, and the proof in its order.
	 * @param proof at most five items; more answer {@code VALIDATION PROOF_LIMIT}; absent for none
	 */
	record ApplyRequest(@NotNull @Min(1000) @Max(1_000_000_000) Long priceVnd,
			@NotNull @Min(1) @Max(365) Integer timelineDays, @NotBlank @Size(max = 2000) String approach,
			boolean publicCodeAcknowledged, @Nullable List<@Valid @NotNull BidProofRequest> proof) {
	}

	/**
	 * One proof item (AD-21): a file the bidder uploaded for this bid ({@code fileId}), or an {@code https} link
	 * ({@code url}), with a caption.
	 */
	record BidProofRequest(@NotNull ProofKind kind, @Nullable UUID fileId, @Nullable String url,
			@NotBlank @Size(max = BidProofs.MAX_CAPTION) String caption) {
	}

	/**
	 * One proof item of a bid, as the bidder and the Job's Customer read it.
	 * @param fileUrl a file's download, served as an attachment; {@code null} for a link
	 * @param url a link, opened with its {@code domain} and N-11; {@code null} for a file
	 */
	record BidProofView(int position, ProofKind kind, String caption, @Nullable UUID fileId, @Nullable String fileName,
			@Nullable Long sizeBytes, @Nullable String fileUrl, @Nullable String url, @Nullable String domain) {
	}

	/** One of the user's own bids, with its proof. */
	record ApplicationView(UUID id, UUID jobId, String jobTitle, Status status, long priceVnd, int timelineDays,
			String approach, Instant submittedAt, @Nullable Instant endedAt, @Nullable EndReason endReason,
			List<BidProofView> proof) {
	}

	/**
	 * Who bid, as CUS-8 shows them; never contact details.
	 * @param yearsOfExperience absent while the Mentor has not declared it
	 */
	record ApplicantView(String displayName, @Nullable String githubLogin, @Nullable Integer yearsOfExperience) {
	}

	/** CUS-8 "Chọn": the Job version the screen loaded (AD-23). */
	record SelectRequest(@NotNull Long version) {
	}

	record SelectedView(long version) {
	}

	/** The NDA panel's "Ký cam kết": the text version shown and, for a Code Job, N-23 ticked. */
	record NdaRequest(@NotBlank String ndaVersion, boolean commitEmailNoticeAcknowledged) {
	}

	/**
	 * One bid on the Customer's Job, with its proof.
	 * @param invited the Customer invited this Mentor: a chip that changes neither order nor choice (UX-DR50)
	 */
	record JobApplicationView(UUID id, Status status, long priceVnd, int timelineDays, String approach,
			Instant submittedAt, ApplicantView mentor, boolean invited, List<BidProofView> proof) {
	}

	private final Applications applications;

	private final BidProofs proofs;

	private final SourceContributors contributors;

	private final JobPosts posts;

	private final MentorDirectory mentors;

	private final ActiveAccount gate;

	private final JobSelection selection;

	private final NdaSignatures signatures;

	private final Invitations invitations;

	private final CurrentCaller callers;

	private final GitHubGate gitHub;

	private final JobRoles roles;

	ApplicationController(Applications applications, BidProofs proofs, SourceContributors contributors, JobPosts posts,
			MentorDirectory mentors, ActiveAccount gate, JobSelection selection, NdaSignatures signatures,
			Invitations invitations, CurrentCaller callers, GitHubGate gitHub, JobRoles roles) {
		this.gitHub = gitHub;
		this.roles = roles;
		this.applications = applications;
		this.proofs = proofs;
		this.contributors = contributors;
		this.posts = posts;
		this.mentors = mentors;
		this.gate = gate;
		this.selection = selection;
		this.signatures = signatures;
		this.invitations = invitations;
		this.callers = callers;
	}

	@PostMapping("/api/v1/mentor/job-posts/{postId}/applications")
	@ResponseStatus(HttpStatus.CREATED)
	ApplicationView apply(@PathVariable UUID postId, @Valid @RequestBody ApplyRequest body) {
		UUID mentor = bidder();
		this.gitHub.requireGitHubLinked(mentor);
		this.applications.requireNotOwnJob(mentor, postId);
		if (!this.mentors.hasProfileName(mentor)) {
			throw new ApiException(ErrorCode.GUARD_FAILED, MatchingReason.PROFILE_REQUIRED);
		}
		if (!body.publicCodeAcknowledged()) {
			throw new ApiException(ErrorCode.VALIDATION, MatchingReason.PUBLIC_CODE_NOT_ACKNOWLEDGED);
		}
		List<Item> proof = BidProofs.requireShape(body.proof() == null ? List.of()
				: body.proof().stream().map(item -> new Item(item.kind(), item.fileId(), item.url(), item.caption())).toList());
		// A-03: GitHub is read here, before the bid's transaction and its locks, and the answer is passed in.
		Contribution contribution = this.contributors.read(postId, mentor);
		Application application = this.applications.submit(mentor, postId,
				new Quote(body.priceVnd(), body.timelineDays(), body.approach()), proof, contribution);
		return views(List.of(application)).getFirst();
	}

	/** CUS-7 "Việc tôi hướng dẫn" and MEN-3: the user's own bids, newest first, each with its proof. */
	@GetMapping("/api/v1/mentor/applications")
	List<ApplicationView> mine() {
		UUID mentor = bidder();
		this.gate.requireActiveAccount(mentor);
		return views(this.applications.ofMentor(mentor));
	}

	/** MEN-3's "Rút": the bid ends, and the Job's Customer learns it. */
	@PostMapping("/api/v1/mentor/applications/{applicationId}/withdraw")
	ApplicationView withdraw(@PathVariable UUID applicationId) {
		UUID mentor = bidder();
		this.gate.requireActiveAccount(mentor);
		return views(List.of(this.applications.withdraw(mentor, applicationId))).getFirst();
	}

	/** CUS-8 "Chào giá": the open bids on the Customer's Job, each with its proof. */
	@GetMapping("/api/v1/jobs/{jobId}/applications")
	List<JobApplicationView> ofJob(@PathVariable UUID jobId) {
		customerOf(jobId);
		List<Application> open = this.applications.openOfJob(jobId);
		Set<UUID> invited = new HashSet<>(this.invitations.invitedMentors(jobId));
		Map<UUID, MentorCard> cards = new HashMap<>();
		this.mentors.cards(open.stream().map(Application::mentorId).toList())
			.forEach(card -> cards.put(card.accountId(), card));
		Map<UUID, List<Proof>> proof = this.proofs.of(open.stream().map(Application::id).toList());
		return open.stream().map(application -> {
			MentorCard card = cards.get(application.mentorId());
			ApplicantView mentor = new ApplicantView(card == null ? "" : card.displayName(),
					card == null ? null : card.githubLogin(), card == null ? null : card.yearsOfExperience());
			return new JobApplicationView(application.id(), application.status(), application.priceVnd(),
					application.timelineDays(), application.approach(), application.submittedAt(), mentor,
					invited.contains(application.mentorId()), proofViews(proof.get(application.id())));
		}).toList();
	}

	/**
	 * CUS-8 "Chọn" (FR-15): J3 for a submitted Application of the Customer's Job, at the version the
	 * screen loaded; a newer Job answers {@code STALE} and nothing changes.
	 */
	@PostMapping("/api/v1/jobs/{jobId}/applications/{applicationId}/select")
	SelectedView select(@PathVariable UUID jobId, @PathVariable UUID applicationId,
			@Valid @RequestBody SelectRequest body) {
		UUID customer = customerOf(jobId);
		Application application = this.applications.ofJob(jobId, applicationId)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (application.status() != Status.SUBMITTED) {
			throw new ApiException(ErrorCode.STALE);
		}
		return new SelectedView(
				this.selection.select(jobId, customer, application.mentorId(), applicationId,
						new SelectedQuote(application.priceVnd(), application.timelineDays()), body.version()));
	}

	/**
	 * JOB-1's NDA panel "Ký cam kết" (FR-15, AD-19 new-work command): the signature and J4, in one
	 * transaction, by the Mentor J3 recorded on the Job (AD-43).
	 */
	@PostMapping("/api/v1/mentor/jobs/{jobId}/nda")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void sign(@PathVariable UUID jobId, @Valid @RequestBody NdaRequest body) {
		UUID mentor = mentorOf(jobId);
		this.gitHub.requireGitHubLinked(mentor);
		this.signatures.sign(mentor, jobId, body.ndaVersion(), body.commitEmailNoticeAcknowledged());
	}

	/** The Job's Customer calling, by their role in the Job (AD-43); anyone else finds nothing. */
	private UUID customerOf(UUID jobId) {
		Caller caller = this.callers.get();
		if (!this.roles.roleOf(jobId, caller).isCustomer()) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		return caller.requireAccountId();
	}

	/** The Job's Mentor calling, by their role in the Job (AD-43); anyone else finds nothing. */
	private UUID mentorOf(UUID jobId) {
		Caller caller = this.callers.get();
		if (!this.roles.roleOf(jobId, caller).isMentor()) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		return caller.requireAccountId();
	}

	private List<ApplicationView> views(List<Application> applications) {
		Map<UUID, String> titles = new HashMap<>();
		Map<UUID, List<Proof>> proof = this.proofs.of(applications.stream().map(Application::id).toList());
		return applications.stream().map(application -> {
			String title = titles.computeIfAbsent(application.jobId(),
					id -> this.posts.post(id).map(JobPostState::title).orElse(""));
			return new ApplicationView(application.id(), application.jobId(), title, application.status(),
					application.priceVnd(), application.timelineDays(), application.approach(), application.submittedAt(),
					application.endedAt(), application.endReason(), proofViews(proof.get(application.id())));
		}).toList();
	}

	private static List<BidProofView> proofViews(@Nullable List<Proof> proof) {
		if (proof == null) {
			return List.of();
		}
		return proof.stream().map(item -> {
			boolean file = item.kind() == ProofKind.FILE;
			return new BidProofView(item.position(), item.kind(), item.caption(), item.fileId(), item.fileName(),
					item.size(), file ? "/api/v1/files/" + item.fileId() : null, file ? null : item.url(),
					file ? null : item.domain());
		}).toList();
	}

	/**
	 * The user calling, for bidding (behind the GitHub gate) and their own bids (behind the unlocked-user gate);
	 * no account role gates them (Story 20.3), and an Administrator, who holds no role in any Job, finds nothing
	 * (SG-31).
	 */
	private UUID bidder() {
		Caller caller = this.callers.get();
		if (!caller.isUser()) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		return caller.requireAccountId();
	}

}
