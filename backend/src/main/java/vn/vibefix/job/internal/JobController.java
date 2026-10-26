package vn.vibefix.job.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import vn.vibefix.files.PreparedFile;
import vn.vibefix.identity.GitHubGate;
import vn.vibefix.identity.MentorDirectory;
import vn.vibefix.job.JobSelection;
import vn.vibefix.job.internal.JobLifecycle.Actor;
import vn.vibefix.job.internal.Jobs.Draft;
import vn.vibefix.job.internal.Jobs.Job;
import vn.vibefix.job.internal.Jobs.Screenshot;
import vn.vibefix.job.internal.Jobs.SkillOnJob;
import vn.vibefix.product.ProductDirectory;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;
import vn.vibefix.shared.Role;

/**
 * CUS-6, CUS-7, and JOB-1 (FR-9, AD-20): a Customer's Jobs with their guided answers, Job Types,
 * Skills, budget range, deadline, and screenshots, published (J1) and withdrawn (J2) through
 * {@link JobLifecycle}. Only the Job's Customer reads, edits, or moves one; any other caller finds
 * nothing.
 */
@RestController
@RequestMapping("/api/v1/jobs")
@Access(Gate.SIGNED_IN)
class JobController {

	/** What CUS-6 sends; every answer but the title may wait until publishing. */
	record JobDraftRequest(@Nullable UUID productId, @NotBlank @Size(max = 120) String title,
			@Nullable @Size(max = 2000) String problem, @Nullable @Size(max = 2000) String expected,
			@Nullable @Size(max = 1000) String appUsers, @Nullable @Size(max = 1000) String doneCheck,
			@NotNull Set<@NotNull JobType> jobTypes, @NotNull List<@NotNull UUID> skillIds, @Nullable Budget budget,
			@Nullable LocalDate deadline) {

		Draft draft() {
			return new Draft(this.title, this.problem, this.expected, this.appUsers, this.doneCheck, this.jobTypes,
					this.skillIds, this.budget, this.deadline);
		}

	}

	record JobCreatedView(UUID id) {
	}

	record SkillView(UUID id, String name, boolean active) {
	}

	record ScreenshotView(UUID fileId, String fileName, long sizeBytes, Instant addedAt, String url) {

		static ScreenshotView of(Screenshot screenshot) {
			return new ScreenshotView(screenshot.fileId(), screenshot.fileName(), screenshot.sizeBytes(),
					screenshot.addedAt(), "/api/v1/files/" + screenshot.fileId());
		}

	}

	/** The Job's Mentor from J3 on, as the Customer sees them. */
	record JobMentorView(String displayName, @Nullable String githubLogin) {
	}

	/**
	 * One Job as CUS-6 edits it and JOB-1 shows it; an Open Job Post has its opening and lapse times,
	 * and from J3 on the Job has its Mentor and the NDA's due time (FR-15).
	 */
	record JobView(UUID id, UUID productId, String productName, JobState state, String title, @Nullable String problem,
			@Nullable String expected, @Nullable String appUsers, @Nullable String doneCheck, List<JobType> jobTypes,
			List<SkillView> skills, @Nullable Budget budget, @Nullable LocalDate deadline, List<ScreenshotView> screenshots,
			Instant createdAt, Instant updatedAt, long version, @Nullable Instant openedAt, @Nullable Instant lapsesAt,
			@Nullable JobMentorView mentor, @Nullable Instant selectedAt, @Nullable Instant ndaDueAt,
			boolean canCancelSelection, boolean agreementShared, boolean agreementConfirmed) {
	}

	/** A decision at the Job version the screen loaded (AD-23). */
	record DecisionRequest(@NotNull Long version) {
	}

	/** One Job as CUS-7 lists it. */
	record JobSummaryView(UUID id, UUID productId, String productName, JobState state, String title,
			List<JobType> jobTypes, Instant updatedAt) {
	}

	private final Jobs jobs;

	private final JobLifecycle lifecycle;

	private final ProductDirectory products;

	private final MentorDirectory mentors;

	private final Agreements agreements;

	private final CurrentCaller callers;

	private final Clock clock;

	private final GitHubGate gitHub;

	JobController(Jobs jobs, JobLifecycle lifecycle, ProductDirectory products, MentorDirectory mentors,
			Agreements agreements, CurrentCaller callers, Clock clock, GitHubGate gitHub) {
		this.gitHub = gitHub;
		this.jobs = jobs;
		this.agreements = agreements;
		this.lifecycle = lifecycle;
		this.products = products;
		this.mentors = mentors;
		this.callers = callers;
		this.clock = clock;
	}

	/** CUS-7: the Customer's Jobs, most recently changed first. */
	@GetMapping
	List<JobSummaryView> jobs() {
		Map<UUID, String> names = new HashMap<>();
		return this.jobs.list(customer())
			.stream()
			.map(job -> new JobSummaryView(job.id(), job.productId(), productName(names, job.productId()), job.state(),
					job.title(), job.jobTypes(), job.updatedAt()))
			.toList();
	}

	/** CUS-6 "Lưu nháp" on a new Job. */
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	JobCreatedView create(@Valid @RequestBody JobDraftRequest body) {
		if (body.productId() == null) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		return new JobCreatedView(this.jobs.create(customer(), body.productId(), body.draft()));
	}

	@GetMapping("/{jobId}")
	JobView job(@PathVariable UUID jobId) {
		return view(this.jobs.find(customer(), jobId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)));
	}

	/** CUS-6 "Lưu nháp" on the Customer's Draft. */
	@PutMapping("/{jobId}")
	JobView update(@PathVariable UUID jobId, @Valid @RequestBody JobDraftRequest body) {
		UUID customer = customer();
		this.jobs.update(customer, jobId, body.draft());
		return view(this.jobs.find(customer, jobId).orElseThrow());
	}

	/** CUS-6 "Đăng tin", after N-3: J1 makes the Draft an Open Job Post (FR-9); GitHub must be linked (FR-80). */
	@PostMapping("/{jobId}/publish")
	JobView publish(@PathVariable UUID jobId) {
		this.gitHub.requireGitHubLinked(customer());
		return transition(jobId, Transition.J1);
	}

	/** JOB-1 "Rút tin": J2 returns the Open Job Post to Draft (FR-9). */
	@PostMapping("/{jobId}/withdraw")
	JobView withdraw(@PathVariable UUID jobId) {
		return transition(jobId, Transition.J2);
	}

	/**
	 * JOB-1 "Hủy lựa chọn" (FR-15): once the signature period passed without a signature, J5 returns
	 * the Job to Open at the version the screen loaded.
	 */
	@PostMapping("/{jobId}/selection/cancel")
	JobView cancelSelection(@PathVariable UUID jobId, @Valid @RequestBody DecisionRequest body) {
		UUID customer = customer();
		this.jobs.find(customer, jobId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		this.lifecycle.apply(Transition.J5, jobId, Actor.user(customer), Trigger.USER, body.version(), Map.of());
		return view(this.jobs.find(customer, jobId).orElseThrow());
	}

	private JobView transition(UUID jobId, Transition transition) {
		UUID customer = customer();
		this.jobs.find(customer, jobId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		this.lifecycle.apply(transition, jobId, Actor.user(customer), Trigger.USER);
		return view(this.jobs.find(customer, jobId).orElseThrow());
	}

	/** CUS-6 screenshots: an image or a PDF of at most 10 MB, within 200 MB per Job (FR-9, NFR-3). */
	@PostMapping(path = "/{jobId}/screenshots", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@ResponseStatus(HttpStatus.CREATED)
	ScreenshotView addScreenshot(@PathVariable UUID jobId, @RequestPart("file") MultipartFile file) {
		UUID customer = customer();
		this.jobs.find(customer, jobId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		byte[] content;
		try {
			content = file.getBytes();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
		PreparedFile prepared = this.jobs.prepareScreenshot(customer, name, content);
		return ScreenshotView.of(this.jobs.addScreenshot(customer, jobId, prepared));
	}

	@DeleteMapping("/{jobId}/screenshots/{screenshotId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void removeScreenshot(@PathVariable UUID jobId, @PathVariable UUID screenshotId) {
		this.jobs.removeScreenshot(customer(), jobId, screenshotId);
	}

	private JobView view(Job job) {
		List<SkillView> skills = this.jobs.skills(job)
			.stream()
			.map((SkillOnJob skill) -> new SkillView(skill.id(), skill.name(), skill.active()))
			.toList();
		return new JobView(job.id(), job.productId(), productName(new HashMap<>(), job.productId()), job.state(),
				job.title(), job.problem(), job.expected(), job.appUsers(), job.doneCheck(), job.jobTypes(), skills,
				job.budget(), job.deadline(), this.jobs.screenshots(job.id()).stream().map(ScreenshotView::of).toList(),
				job.createdAt(), job.updatedAt(), job.version(), job.openedAt(), job.lapsesAt(), mentor(job),
				job.selectedAt(), job.selectedAt() == null ? null : job.selectedAt().plus(JobSelection.NDA_PERIOD),
				job.state() == JobState.SELECTING && job.selectedAt() != null
						&& !this.clock.instant().isBefore(job.selectedAt().plus(JobSelection.NDA_PERIOD)),
				this.agreements.shared(job.id()), job.agreementVersionNo() != null);
	}

	private @Nullable JobMentorView mentor(Job job) {
		if (job.mentorId() == null) {
			return null;
		}
		return this.mentors.cards(List.of(job.mentorId()))
			.stream()
			.findFirst()
			.map(card -> new JobMentorView(card.displayName(), card.githubLogin()))
			.orElse(null);
	}

	private String productName(Map<UUID, String> names, UUID productId) {
		return names.computeIfAbsent(productId,
				id -> this.products.find(id).map(ProductDirectory.ProductInfo::name).orElse(""));
	}

	private UUID customer() {
		Caller caller = this.callers.get();
		if (!caller.hasRole(Role.CUSTOMER)) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		return caller.requireAccountId();
	}

}
