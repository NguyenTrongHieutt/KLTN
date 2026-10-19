package vn.vibefix.matching.internal;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import vn.vibefix.identity.MentorDirectory;
import vn.vibefix.identity.MentorDirectory.MentorCard;
import vn.vibefix.identity.MentorGate;
import vn.vibefix.job.JobPosts;
import vn.vibefix.job.JobPosts.JobPostState;
import vn.vibefix.matching.internal.Applications.Application;
import vn.vibefix.matching.internal.Applications.EndReason;
import vn.vibefix.matching.internal.Applications.Quote;
import vn.vibefix.matching.internal.Applications.Status;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;
import vn.vibefix.shared.Role;
import vn.vibefix.team.Teams;

/**
 * Applications (FR-13, AD-20): an approved Mentor applies from MEN-3 (AD-19 new-work command) and
 * lists or withdraws their own (MEN-4); the Job's Customer reads the open ones on CUS-8. Nobody else
 * finds them.
 */
@RestController
@Access(Gate.SIGNED_IN)
class ApplicationController {

	/** MEN-3's "Ứng tuyển": the price in whole VND, the days, the approach, and N-5. */
	record ApplyRequest(@NotNull @Min(1000) @Max(1_000_000_000) Long priceVnd,
			@NotNull @Min(1) @Max(365) Integer timelineDays, @NotBlank @Size(max = 2000) String approach,
			boolean publicCodeAcknowledged) {
	}

	/** One of the Mentor's own Applications. */
	record ApplicationView(UUID id, UUID jobId, String jobTitle, Status status, long priceVnd, int timelineDays,
			String approach, Instant submittedAt, @Nullable Instant endedAt, @Nullable EndReason endReason) {
	}

	/** Who applied, as CUS-8 shows them; never contact details. */
	record ApplicantView(String displayName, @Nullable String githubLogin, int yearsOfExperience, int teamSize) {
	}

	/** One Application to the Customer's Job. */
	record JobApplicationView(UUID id, Status status, long priceVnd, int timelineDays, String approach,
			Instant submittedAt, ApplicantView mentor) {
	}

	private final Applications applications;

	private final JobPosts posts;

	private final MentorDirectory mentors;

	private final Teams teams;

	private final MentorGate gate;

	private final CurrentCaller callers;

	ApplicationController(Applications applications, JobPosts posts, MentorDirectory mentors, Teams teams,
			MentorGate gate, CurrentCaller callers) {
		this.applications = applications;
		this.posts = posts;
		this.mentors = mentors;
		this.teams = teams;
		this.gate = gate;
		this.callers = callers;
	}

	@PostMapping("/api/v1/mentor/job-posts/{postId}/applications")
	@ResponseStatus(HttpStatus.CREATED)
	ApplicationView apply(@PathVariable UUID postId, @Valid @RequestBody ApplyRequest body) {
		UUID mentor = mentor();
		this.gate.requireApprovedMentor(mentor);
		if (!body.publicCodeAcknowledged()) {
			throw new ApiException(ErrorCode.VALIDATION, MatchingReason.PUBLIC_CODE_NOT_ACKNOWLEDGED);
		}
		Application application = this.applications.submit(mentor, postId,
				new Quote(body.priceVnd(), body.timelineDays(), body.approach()));
		return view(application, new HashMap<>());
	}

	/** MEN-4 and MEN-3: the Mentor's own Applications, newest first. */
	@GetMapping("/api/v1/mentor/applications")
	List<ApplicationView> mine() {
		UUID mentor = mentor();
		this.gate.requireActiveAccount(mentor);
		Map<UUID, String> titles = new HashMap<>();
		return this.applications.ofMentor(mentor).stream().map(application -> view(application, titles)).toList();
	}

	@PostMapping("/api/v1/mentor/applications/{applicationId}/withdraw")
	ApplicationView withdraw(@PathVariable UUID applicationId) {
		UUID mentor = mentor();
		this.gate.requireActiveAccount(mentor);
		return view(this.applications.withdraw(mentor, applicationId), new HashMap<>());
	}

	/** CUS-8 "Đơn ứng tuyển": the open Applications to the Customer's Job. */
	@GetMapping("/api/v1/jobs/{jobId}/applications")
	List<JobApplicationView> ofJob(@PathVariable UUID jobId) {
		Caller caller = this.callers.get();
		if (!caller.hasRole(Role.CUSTOMER)
				|| !this.posts.customerOf(jobId).map(caller.requireAccountId()::equals).orElse(false)) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		List<Application> open = this.applications.openOfJob(jobId);
		Map<UUID, MentorCard> cards = new HashMap<>();
		this.mentors.cards(open.stream().map(Application::mentorId).toList())
			.forEach(card -> cards.put(card.accountId(), card));
		return open.stream().map(application -> {
			MentorCard card = cards.get(application.mentorId());
			ApplicantView mentor = new ApplicantView(card == null ? "" : card.displayName(),
					card == null ? null : card.githubLogin(), card == null ? 0 : card.yearsOfExperience(),
					this.teams.sizeOf(application.mentorId()));
			return new JobApplicationView(application.id(), application.status(), application.priceVnd(),
					application.timelineDays(), application.approach(), application.submittedAt(), mentor);
		}).toList();
	}

	private ApplicationView view(Application application, Map<UUID, String> titles) {
		String title = titles.computeIfAbsent(application.jobId(),
				id -> this.posts.post(id).map(JobPostState::title).orElse(""));
		return new ApplicationView(application.id(), application.jobId(), title, application.status(),
				application.priceVnd(), application.timelineDays(), application.approach(), application.submittedAt(),
				application.endedAt(), application.endReason());
	}

	/** The Mentor calling; anyone else finds nothing (SG-31). */
	private UUID mentor() {
		Caller caller = this.callers.get();
		if (!caller.hasRole(Role.MENTOR)) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		return caller.requireAccountId();
	}

}
