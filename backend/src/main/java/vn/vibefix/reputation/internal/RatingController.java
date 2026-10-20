package vn.vibefix.reputation.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
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
import vn.vibefix.identity.CustomerNames;
import vn.vibefix.identity.MentorDirectory;
import vn.vibefix.identity.StudentDirectory;
import vn.vibefix.identity.StudentDirectory.StudentCard;
import vn.vibefix.reputation.internal.Ratings.Kind;
import vn.vibefix.reputation.internal.Ratings.Rating;
import vn.vibefix.reputation.internal.Ratings.Viewer;
import vn.vibefix.reputation.internal.Ratings.ViewerRole;
import vn.vibefix.reputation.internal.Ratings.Window;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;

/**
 * JOB-1's Ratings and private feedback (FR-49, FR-78, AD-20, UX-DR27, UX-DR51, UX-DR82), under
 * {@code /api/v1/boards/{boardId}} like the Job's other closing-phase calls. The Customer, the Mentor,
 * and every Crew member who was ever active read what they may: their own Ratings and the published
 * ones; a Crew member also reads whether they gave feedback, never what. Nobody else finds anything, and
 * no response holds a hidden Rating or anyone's private feedback (NFR-1).
 */
@RestController
@Access(Gate.SIGNED_IN)
class RatingController {

	/** Someone the viewer still rates; {@code role} is {@code MENTOR}, {@code CUSTOMER}, or {@code CREW}. */
	record RateeView(UUID accountId, String name, String role) {
	}

	/** A Rating the viewer may see: their own, or a published one. */
	record RatingView(String kind, String raterRole, UUID rateeId, String rateeName, int stars, String comment,
			Instant createdAt, boolean mine) {
	}

	/**
	 * @param hiddenUntil when the Ratings the viewer waits for are shown at the latest: the window's end
	 * @param feedbackDue a Crew member may still give private feedback
	 * @param feedbackGiven a Crew member gave it; never sent to anyone else
	 */
	record RatingsView(UUID jobId, String audience, @Nullable Instant openedAt, @Nullable Instant endsAt, boolean open,
			List<RateeView> toRate, List<RatingView> ratings, @Nullable Instant hiddenUntil, @Nullable Boolean feedbackDue,
			@Nullable Boolean feedbackGiven) {
	}

	record RatingRequest(@NotNull UUID rateeId, @NotNull @Min(1) @Max(5) Integer stars,
			@NotBlank @Size(max = 2000) String comment) {
	}

	record PrivateFeedbackRequest(@NotNull @Min(1) @Max(5) Integer stars, @Nullable @Size(max = 2000) String comment) {
	}

	private final Ratings ratings;

	private final JobViewers viewers;

	private final CustomerNames customers;

	private final MentorDirectory mentors;

	private final StudentDirectory students;

	private final CurrentCaller callers;

	private final Clock clock;

	RatingController(Ratings ratings, JobViewers viewers, CustomerNames customers, MentorDirectory mentors,
			StudentDirectory students, CurrentCaller callers, Clock clock) {
		this.ratings = ratings;
		this.viewers = viewers;
		this.customers = customers;
		this.mentors = mentors;
		this.students = students;
		this.callers = callers;
		this.clock = clock;
	}

	@GetMapping("/api/v1/boards/{boardId}/ratings")
	RatingsView ratings(@PathVariable("boardId") UUID jobId) {
		return view(this.viewers.viewer(jobId, this.callers.get()));
	}

	/** One Rating (FR-49), read-only once sent. */
	@PostMapping("/api/v1/boards/{boardId}/ratings")
	@ResponseStatus(HttpStatus.CREATED)
	RatingsView rate(@PathVariable("boardId") UUID jobId, @Valid @RequestBody RatingRequest body) {
		Viewer viewer = this.viewers.viewer(jobId, this.callers.get());
		this.ratings.rate(viewer, body.rateeId(), body.stars(), body.comment());
		return view(viewer);
	}

	/** Private feedback about the Mentor (FR-78): only a Crew member who was ever active; others find nothing. */
	@PostMapping("/api/v1/boards/{boardId}/private-feedback")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void giveFeedback(@PathVariable("boardId") UUID jobId, @Valid @RequestBody PrivateFeedbackRequest body) {
		Viewer viewer = this.viewers.viewer(jobId, this.callers.get());
		if (viewer.role() != ViewerRole.CREW) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		this.ratings.giveFeedback(viewer, body.stars(), body.comment());
	}

	private RatingsView view(Viewer viewer) {
		Window window = this.ratings.window(viewer.jobId()).orElse(null);
		if (window == null) {
			return new RatingsView(viewer.jobId(), viewer.role().name(), null, null, false, List.of(), List.of(), null,
					viewer.role() == ViewerRole.CREW ? false : null, viewer.role() == ViewerRole.CREW ? false : null);
		}
		boolean open = window.open(this.clock.instant()) && window.crewPublishedAt() == null;
		List<Rating> visible = this.ratings.visibleTo(viewer.jobId(), viewer.accountId())
			.stream()
			.filter(rating -> viewer.role() != ViewerRole.CREW || viewer.accountId().equals(rating.rateeId()))
			.toList();
		Map<UUID, String> names = names(window, viewer);
		List<RatingView> ratings = new ArrayList<>();
		for (Rating rating : visible) {
			ratings.add(new RatingView(rating.kind().name(), rating.kind() == Kind.CUSTOMER_TO_MENTOR ? "CUSTOMER" : "MENTOR",
					rating.rateeId(), names.getOrDefault(rating.rateeId(), ""), rating.stars(), rating.comment(),
					rating.createdAt(), viewer.accountId().equals(rating.raterId())));
		}
		List<RateeView> toRate = this.ratings.left(viewer, window)
			.stream()
			.map(id -> new RateeView(id, names.getOrDefault(id, ""),
					id.equals(window.mentorId()) ? "MENTOR" : id.equals(window.customerId()) ? "CUSTOMER" : "CREW"))
			.toList();
		boolean waiting = viewer.role() == ViewerRole.CREW ? window.crewPublishedAt() == null : window.pairPublishedAt() == null;
		boolean crew = viewer.role() == ViewerRole.CREW;
		boolean given = crew && this.ratings.feedbackGiven(viewer.jobId(), viewer.accountId());
		return new RatingsView(viewer.jobId(), viewer.role().name(), window.openedAt(), window.endsAt(), open, toRate, ratings,
				waiting ? window.endsAt() : null, crew ? open && !given : null, crew ? given : null);
	}

	private Map<UUID, String> names(Window window, Viewer viewer) {
		Map<UUID, String> names = new HashMap<>();
		if (window.customerId() != null) {
			names.put(window.customerId(), this.customers.displayName(window.customerId()));
		}
		if (window.mentorId() != null) {
			this.mentors.cards(List.of(window.mentorId())).stream().findFirst()
				.ifPresent(card -> names.put(window.mentorId(), card.displayName()));
		}
		List<UUID> crew = this.ratings.crew(viewer.jobId());
		if (!crew.isEmpty()) {
			for (StudentCard card : this.students.cards(crew)) {
				names.put(card.accountId(), card.name() != null ? card.name() : "@" + card.githubLogin());
			}
		}
		return names;
	}

}
