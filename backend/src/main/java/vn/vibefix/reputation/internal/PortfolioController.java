package vn.vibefix.reputation.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import vn.vibefix.reputation.internal.Portfolios.Entry;
import vn.vibefix.reputation.internal.Portfolios.Status;
import vn.vibefix.reputation.internal.Portfolios.Summary;
import vn.vibefix.reputation.internal.Ratings.Viewer;
import vn.vibefix.reputation.internal.Ratings.ViewerRole;
import vn.vibefix.reputation.internal.Ratings.Window;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.Gate;

/**
 * JOB-1's portfolio section (FR-50, UX-DR55), under {@code /api/v1/boards/{boardId}} like the Job's other
 * closing-phase calls: the summary for the Customer and the Mentor, and each person's own entry with
 * whether it is published. A Crew member reads the summary only once approved.
 */
@RestController
@Access(Gate.SIGNED_IN)
class PortfolioController {

	/** The viewer's own entry, frozen at J9. */
	record EntryView(String role, String title, List<String> jobTypes, List<String> skills, int milestones,
			@Nullable Integer workItemsApproved, @Nullable Integer milestonesHandedOver, @Nullable Integer reviewsWritten,
			@Nullable Instant publishedAt) {
	}

	/**
	 * @param exists the Job closed with Accepted work
	 * @param summaryStatus {@code DRAFT_DUE}, {@code AWAITING_APPROVAL}, {@code APPROVED}, {@code DECLINED},
	 * or {@code LAPSED}
	 * @param productName for the Customer's "Cho phép hiện tên sản phẩm" and the Mentor
	 * @param open the summary may still be drafted or decided
	 */
	record PortfolioView(UUID jobId, String audience, boolean exists, @Nullable String summaryStatus,
			@Nullable String summaryText, @Nullable Boolean showProductName, @Nullable String productName,
			@Nullable Instant decideBy, boolean open, @Nullable EntryView mine) {
	}

	record SummaryRequest(@NotBlank @Size(max = 600) String text) {
	}

	record ApprovalRequest(@NotBlank @Size(max = 600) String text, @NotNull Boolean showProductName) {
	}

	private final Portfolios portfolios;

	private final Ratings ratings;

	private final JobViewers viewers;

	private final SkillNames skills;

	private final CurrentCaller callers;

	private final Clock clock;

	PortfolioController(Portfolios portfolios, Ratings ratings, JobViewers viewers, SkillNames skills,
			CurrentCaller callers, Clock clock) {
		this.portfolios = portfolios;
		this.ratings = ratings;
		this.viewers = viewers;
		this.skills = skills;
		this.callers = callers;
		this.clock = clock;
	}

	@GetMapping("/api/v1/boards/{boardId}/portfolio")
	PortfolioView portfolio(@PathVariable("boardId") UUID jobId) {
		return view(this.viewers.viewer(jobId, this.callers.get()));
	}

	/** The Mentor drafts the summary once (FR-50). */
	@PostMapping("/api/v1/boards/{boardId}/portfolio/summary")
	PortfolioView draft(@PathVariable("boardId") UUID jobId, @Valid @RequestBody SummaryRequest body) {
		Viewer viewer = this.viewers.viewer(jobId, this.callers.get());
		this.portfolios.draft(viewer, body.text());
		return view(viewer);
	}

	/** The Customer approves the summary, possibly edited, and chooses whether the Product name shows. */
	@PostMapping("/api/v1/boards/{boardId}/portfolio/summary/approve")
	PortfolioView approve(@PathVariable("boardId") UUID jobId, @Valid @RequestBody ApprovalRequest body) {
		Viewer viewer = this.viewers.viewer(jobId, this.callers.get());
		this.portfolios.approve(viewer, body.text(), body.showProductName());
		return view(viewer);
	}

	/** The Customer declines: the entries keep no summary. */
	@PostMapping("/api/v1/boards/{boardId}/portfolio/summary/decline")
	PortfolioView decline(@PathVariable("boardId") UUID jobId) {
		Viewer viewer = this.viewers.viewer(jobId, this.callers.get());
		this.portfolios.decline(viewer);
		return view(viewer);
	}

	private PortfolioView view(Viewer viewer) {
		Summary summary = this.portfolios.summary(viewer.jobId()).orElse(null);
		if (summary == null) {
			return new PortfolioView(viewer.jobId(), viewer.role().name(), false, null, null, null, null, null, false, null);
		}
		boolean crew = viewer.role() == ViewerRole.CREW;
		Window window = this.ratings.window(viewer.jobId()).orElse(null);
		boolean open = summary.pending() && window != null && window.open(this.clock.instant());
		Entry mine = viewer.role() == ViewerRole.CUSTOMER ? null
				: this.portfolios.entry(viewer.jobId(), viewer.accountId()).orElse(null);
		String product = crew ? null
				: this.portfolios.entry(viewer.jobId(), window != null && window.mentorId() != null ? window.mentorId()
						: viewer.accountId()).map(Entry::productName).orElse(null);
		String text = crew && summary.status() != Status.APPROVED ? null : summary.text();
		return new PortfolioView(viewer.jobId(), viewer.role().name(), true, summary.status().name(), text,
				crew ? null : summary.showProductName(), product, window == null ? null : window.endsAt(), open,
				mine == null ? null : entryView(mine));
	}

	private EntryView entryView(Entry entry) {
		return new EntryView(entry.role(), entry.jobTitle(), entry.jobTypes(), this.skills.namesOf(entry.skillIds()),
				entry.milestones(), entry.workItemsApproved(), entry.milestonesHandedOver(), entry.reviewsWritten(),
				entry.publishedAt());
	}

}
