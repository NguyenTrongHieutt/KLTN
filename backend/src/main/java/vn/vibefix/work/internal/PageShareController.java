package vn.vibefix.work.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.Gate;
import vn.vibefix.work.internal.PageController.PageNodeView;
import vn.vibefix.work.internal.PageShares.Choices;

/**
 * Shared earlier Pages (FR-70, UX-DR59): the Customer's "Chia sẻ tài liệu cũ" checklist on JOB-6, and the
 * shared Jobs with their customer-visible Pages, which the new Job's Mentor and {@code ACTIVE} Crew read on
 * "Tài liệu từ công việc trước". A shared Page itself is read through {@code GET /api/v1/pages/{pageId}}.
 */
@RestController
@Access(Gate.SIGNED_IN)
class PageShareController {

	/**
	 * Another Job of the Product, as the checklist offers it.
	 * @param assessmentReport the Job has an Assessment Report ("Báo cáo đánh giá")
	 * @param pages its customer-visible Pages
	 * @param shared the Customer shares it with this Job now
	 */
	record PageShareOptionView(UUID jobId, String title, String state, boolean assessmentReport, int pages,
			boolean shared) {
	}

	/** @param editable the Customer may change the checklist in the Job's state now */
	record PageSharesView(boolean editable, List<PageShareOptionView> jobs) {
	}

	/** "Lưu chia sẻ": exactly the Jobs to share; an empty list ends every share. */
	record PageSharesRequest(@NotNull @Size(max = 100) List<@NotNull UUID> jobIds) {
	}

	/** A shared earlier Job and the Pages its shared reader sees, in tree order. */
	record SharedJobView(UUID jobId, String title, List<PageNodeView> pages) {
	}

	private final PageShares shares;

	private final WorkAccess access;

	private final Pages pages;

	private final CurrentCaller callers;

	PageShareController(PageShares shares, WorkAccess access, Pages pages, CurrentCaller callers) {
		this.shares = shares;
		this.access = access;
		this.pages = pages;
		this.callers = callers;
	}

	@GetMapping("/api/v1/jobs/{jobId}/page-shares")
	PageSharesView pageShares(@PathVariable UUID jobId) {
		return view(this.shares.choices(this.callers.get(), jobId));
	}

	@PutMapping("/api/v1/jobs/{jobId}/page-shares")
	PageSharesView sharePages(@PathVariable UUID jobId, @Valid @RequestBody PageSharesRequest body) {
		return view(this.shares.share(this.callers.get(), jobId, body.jobIds()));
	}

	@GetMapping("/api/v1/jobs/{jobId}/shared-pages")
	List<SharedJobView> sharedPages(@PathVariable UUID jobId) {
		return this.access.sharedReaders(jobId, this.callers.get())
			.stream()
			.map(viewer -> new SharedJobView(viewer.board().jobId(), viewer.title(),
					this.pages.visible(viewer).stream().map(PageController::node).toList()))
			.toList();
	}

	private static PageSharesView view(Choices choices) {
		return new PageSharesView(choices.editable(),
				choices.jobs()
					.stream()
					.map(option -> new PageShareOptionView(option.job().jobId(), option.job().title(), option.job().state(),
							option.assessmentReport(), option.pages(), option.shared()))
					.toList());
	}

}
