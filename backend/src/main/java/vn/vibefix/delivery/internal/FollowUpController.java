package vn.vibefix.delivery.internal;

import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import vn.vibefix.delivery.internal.DeliveryAccess.Kind;
import vn.vibefix.delivery.internal.DeliveryAccess.Viewer;
import vn.vibefix.delivery.internal.FollowUps.Accepted;
import vn.vibefix.delivery.internal.FollowUps.Content;
import vn.vibefix.delivery.internal.FollowUps.FollowUp;
import vn.vibefix.job.JobPosts;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;

/**
 * JOB-1's "Công việc tiếp theo" (FR-11, UX-DR16): the Mentor drafts follow-up Jobs from the accepted
 * Assessment Report's numbered recommendations and submits them; the Customer opens each as a Draft Job
 * Post in CUS-6, or discards it. The Customer reads only submitted follow-ups; the Crew finds nothing.
 */
@RestController
@Access(Gate.SIGNED_IN)
class FollowUpController {

	record RecommendationView(int number, String text) {
	}

	/** @param status {@code DRAFTING}, {@code SUBMITTED}, {@code OPENED}, or {@code DISCARDED} */
	record FollowUpView(UUID id, int recommendationNo, String title, List<String> jobTypes, List<UUID> skillIds,
			String scope, List<String> criteria, int effortDays, String status, @Nullable UUID draftJobId) {
	}

	/**
	 * @param available the Assessment Report is accepted, so follow-ups may be drafted
	 * @param submitted the Mentor has sent them to the Customer
	 * @param skillIds the assessed Job's Skills, where a new draft starts
	 */
	record FollowUpsView(UUID jobId, String audience, boolean available, boolean submitted,
			@Nullable Integer reportVersionNo, List<RecommendationView> recommendations, List<FollowUpView> followUps,
			List<UUID> skillIds) {
	}

	record FollowUpRequest(@NotNull @Min(1) Integer recommendationNo, @NotBlank @Size(max = 120) String title,
			@NotNull @Size(min = 1, max = 8) List<@NotBlank String> jobTypes, @NotNull @Size(max = 10) List<@NotNull UUID> skillIds,
			@NotBlank @Size(max = 2000) String scope, @NotNull @Size(min = 1, max = 10) List<@NotBlank @Size(max = 300) String> criteria,
			@NotNull @Min(1) @Max(365) Integer effortDays) {

		Content content() {
			return new Content(this.recommendationNo, this.title, this.jobTypes, this.skillIds, this.scope, this.criteria,
					this.effortDays);
		}

	}

	/** Where CUS-6 opens the new Draft. */
	record OpenedView(UUID draftJobId) {
	}

	private final FollowUps followUps;

	private final DeliveryAccess access;

	private final JobPosts posts;

	private final CurrentCaller callers;

	FollowUpController(FollowUps followUps, DeliveryAccess access, JobPosts posts, CurrentCaller callers) {
		this.followUps = followUps;
		this.access = access;
		this.posts = posts;
		this.callers = callers;
	}

	@GetMapping("/api/v1/boards/{boardId}/follow-ups")
	FollowUpsView followUps(@PathVariable("boardId") UUID jobId) {
		return view(viewer(jobId));
	}

	@PostMapping("/api/v1/boards/{boardId}/follow-ups")
	@ResponseStatus(HttpStatus.CREATED)
	FollowUpsView create(@PathVariable("boardId") UUID jobId, @Valid @RequestBody FollowUpRequest body) {
		Viewer viewer = viewer(jobId);
		this.followUps.create(viewer, body.content());
		return view(viewer);
	}

	/** "Gửi khách hàng": every draft at once. */
	@PostMapping("/api/v1/boards/{boardId}/follow-ups/submit")
	FollowUpsView submit(@PathVariable("boardId") UUID jobId) {
		Viewer viewer = viewer(jobId);
		this.followUps.submit(viewer);
		return view(viewer);
	}

	@PutMapping("/api/v1/follow-ups/{followUpId}")
	FollowUpsView update(@PathVariable UUID followUpId, @Valid @RequestBody FollowUpRequest body) {
		Viewer viewer = viewerOf(followUpId);
		this.followUps.update(viewer, followUpId, body.content());
		return view(viewer);
	}

	@DeleteMapping("/api/v1/follow-ups/{followUpId}")
	FollowUpsView delete(@PathVariable UUID followUpId) {
		Viewer viewer = viewerOf(followUpId);
		this.followUps.delete(viewer, followUpId);
		return view(viewer);
	}

	/** "Mở để đăng tin": a Draft Job Post pre-filled from the follow-up, for CUS-6. */
	@PostMapping("/api/v1/follow-ups/{followUpId}/open")
	OpenedView open(@PathVariable UUID followUpId) {
		return new OpenedView(this.followUps.open(viewerOf(followUpId), followUpId));
	}

	/** "Bỏ qua". */
	@PostMapping("/api/v1/follow-ups/{followUpId}/discard")
	FollowUpsView discard(@PathVariable UUID followUpId) {
		Viewer viewer = viewerOf(followUpId);
		this.followUps.discard(viewer, followUpId);
		return view(viewer);
	}

	/** The Customer or the Mentor; the Crew finds nothing. */
	private Viewer viewer(UUID jobId) {
		Viewer viewer = this.access.reader(jobId, this.callers.get());
		if (viewer.kind() == Kind.CREW) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		return viewer;
	}

	private Viewer viewerOf(UUID followUpId) {
		return viewer(this.followUps.jobOf(followUpId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)));
	}

	private FollowUpsView view(Viewer viewer) {
		Accepted accepted = this.followUps.accepted(viewer.job()).orElse(null);
		List<FollowUp> all = this.followUps.of(viewer.job().jobId());
		boolean submitted = all.stream().anyMatch(followUp -> !"DRAFTING".equals(followUp.status()));
		List<FollowUpView> shown = all.stream()
			.filter(followUp -> viewer.kind() == Kind.MENTOR || !"DRAFTING".equals(followUp.status()))
			.map(followUp -> new FollowUpView(followUp.id(), followUp.recommendationNo(), followUp.title(),
					followUp.jobTypes(), followUp.skillIds(), followUp.scope(), followUp.criteria(), followUp.effortDays(),
					followUp.status(), viewer.kind() == Kind.CUSTOMER ? followUp.draftJobId() : null))
			.toList();
		List<RecommendationView> recommendations = accepted == null ? List.of()
				: this.followUps.recommendations(accepted)
					.stream()
					.map(item -> new RecommendationView(item.number(), item.text()))
					.toList();
		List<UUID> skills = this.posts.post(viewer.job().jobId()).map(JobPosts.JobPostState::skillIds).orElse(List.of());
		return new FollowUpsView(viewer.job().jobId(), viewer.kind().name(), accepted != null, submitted,
				accepted == null ? null : accepted.versionNo(), recommendations, shown, skills);
	}

}
