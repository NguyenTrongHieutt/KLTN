package vn.vibefix.work.internal;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;
import vn.vibefix.work.internal.Boards.ChecklistLine;
import vn.vibefix.work.internal.Boards.Item;
import vn.vibefix.work.internal.Boards.Priority;
import vn.vibefix.work.internal.Boards.Status;
import vn.vibefix.work.internal.Boards.Type;
import vn.vibefix.work.internal.Reviews.Decision;
import vn.vibefix.work.internal.Reviews.SelfCheck;
import vn.vibefix.work.internal.WorkAccess.BoardViewer;
import vn.vibefix.work.internal.WorkItems.Draft;
import vn.vibefix.work.internal.WorkTextCheck.Resolution;
import vn.vibefix.work.internal.WorkViews.BoardView;
import vn.vibefix.work.internal.WorkViews.WorkItemDetailView;

/**
 * JOB-2 "Bảng công việc" (FR-22, FR-23, AD-33, AD-39, UX-DR36): the Board of an Active Job, keyed by the
 * Job, and its Work Items. The Mentor and the active Crew read and write; a former member and a
 * granted Administrator read what they may; everyone else finds nothing. Every edit carries the Work
 * Item's version and passes the FR-47 check.
 */
@RestController
@Access(Gate.SIGNED_IN)
class BoardController {

	record ChecklistLineRequest(@NotNull @Size(max = 300) String text, boolean done) {
	}

	/**
	 * The fields of a Work Item, sent in full on every save.
	 * @param version the version the edit was made on; only for an edit
	 * @param sensitive {@code CHECK} first; {@code MASK} after the FR-47 warning
	 */
	record WorkItemRequest(@NotNull Type type, @NotBlank @Size(max = 500) String title,
			@Nullable @Size(max = 4000) String description, @Nullable @Size(max = 20) List<@Valid ChecklistLineRequest> checklist,
			@NotNull UUID milestoneId, @Nullable Priority priority,
			@Nullable @DecimalMin("0.5") @DecimalMax("999") BigDecimal estimateHours, @Nullable UUID assigneeId,
			@Nullable Long version, @Nullable Resolution sensitive) {

		Draft draft() {
			if (this.estimateHours != null && this.estimateHours.remainder(new BigDecimal("0.5")).signum() != 0) {
				throw new ApiException(ErrorCode.VALIDATION);
			}
			List<ChecklistLine> lines = this.checklist == null ? List.of()
					: this.checklist.stream()
						.filter(line -> !line.text().isBlank())
						.map(line -> new ChecklistLine(line.text().strip(), line.done()))
						.toList();
			return new Draft(this.type, this.title.strip(), this.description == null ? "" : this.description.strip(), lines,
					this.milestoneId, this.priority, this.estimateHours, this.assigneeId);
		}

		Resolution resolution() {
			return this.sensitive == null ? Resolution.CHECK : this.sensitive;
		}

	}

	/** A Board move, made on the Work Item's {@code version}. */
	record MoveRequest(@NotNull Status to, @NotNull Long version) {
	}

	/** The four Self-check answers; each must be {@code true} (AD-39). */
	record SelfCheckRequest(boolean meetsCriteria, boolean tested, boolean docsUpdated, boolean noSecrets) {
	}

	/**
	 * "Gửi duyệt" on {@code version}.
	 * @param note optional, FR-47 checked
	 */
	record SubmitRequest(@NotNull Long version, @NotNull @Valid SelfCheckRequest selfCheck,
			@Nullable @Size(max = 4000) String note, @Nullable Resolution sensitive) {
	}

	/** "Duyệt" or "Trả lại" on {@code version}, with the Mentor's comment (FR-47 checked). */
	record ReviewRequest(@NotNull Long version, @NotNull Decision decision, @Nullable @Size(max = 4000) String comment,
			@Nullable Resolution sensitive) {
	}

	private final Boards boards;

	private final WorkAccess access;

	private final WorkReads reads;

	private final WorkItems items;

	private final Reviews reviews;

	private final WorkViews views;

	private final CurrentCaller callers;

	BoardController(Boards boards, WorkAccess access, WorkReads reads, WorkItems items, Reviews reviews, WorkViews views,
			CurrentCaller callers) {
		this.boards = boards;
		this.access = access;
		this.reads = reads;
		this.items = items;
		this.reviews = reviews;
		this.views = views;
		this.callers = callers;
	}

	/** The Board of the Job {@code boardId} (a Board's ID is its Job's), as the viewer may see it. */
	@GetMapping("/api/v1/boards/{boardId}/work-items")
	BoardView workItems(@PathVariable UUID boardId) {
		BoardViewer viewer = this.access.reader(boardId, this.callers.get());
		return this.views.board(viewer, this.reads.items(viewer), this.reads.latestReviews(viewer));
	}

	/** "Thêm việc": a new Work Item, keyed after the Board's last (FR-23). */
	@PostMapping("/api/v1/boards/{boardId}/work-items")
	@ResponseStatus(HttpStatus.CREATED)
	WorkItemDetailView createWorkItem(@PathVariable UUID boardId, @Valid @RequestBody WorkItemRequest body) {
		BoardViewer writer = this.access.writer(boardId, this.callers.get());
		Item created = this.items.create(writer, body.draft(), body.resolution());
		return this.views.detail(writer, created, this.reads.historyOf(writer, created.id()));
	}

	/** One Work Item with its history, as the viewer may see it. */
	@GetMapping("/api/v1/work-items/{workItemId}")
	WorkItemDetailView workItem(@PathVariable UUID workItemId) {
		Item item = this.boards.item(workItemId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		BoardViewer viewer = this.access.reader(item.jobId(), this.callers.get());
		Item seen = this.reads.item(viewer, item);
		return this.views.detail(viewer, seen, this.reads.historyOf(viewer, item.id()));
	}

	/** Saves an edit made on {@code version}; a newer version answers {@code STALE} (AD-23). */
	@PutMapping("/api/v1/work-items/{workItemId}")
	WorkItemDetailView updateWorkItem(@PathVariable UUID workItemId, @Valid @RequestBody WorkItemRequest body) {
		Item item = this.boards.item(workItemId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		BoardViewer writer = this.access.writer(item.jobId(), this.callers.get());
		if (body.version() == null) {
			throw new ApiException(ErrorCode.VALIDATION);
		}
		Item updated = this.items.update(writer, workItemId, body.version(), body.draft(), body.resolution());
		return this.views.detail(writer, updated, this.reads.historyOf(writer, updated.id()));
	}

	/** "Chuyển sang…": a move between "Cần làm" and "Đang làm" (FR-24, AD-39). */
	@PostMapping("/api/v1/work-items/{workItemId}/move")
	WorkItemDetailView moveWorkItem(@PathVariable UUID workItemId, @Valid @RequestBody MoveRequest body) {
		Item item = this.boards.item(workItemId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		BoardViewer writer = this.access.writer(item.jobId(), this.callers.get());
		Item moved = this.items.move(writer, workItemId, body.version(), body.to());
		return this.views.detail(writer, moved, this.reads.historyOf(writer, moved.id()));
	}

	/** "Gửi duyệt": the assignee submits with the Self-check Checklist (FR-25, AD-39). */
	@PostMapping("/api/v1/work-items/{workItemId}/submit")
	WorkItemDetailView submitWorkItem(@PathVariable UUID workItemId, @Valid @RequestBody SubmitRequest body) {
		Item item = this.boards.item(workItemId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		BoardViewer writer = this.access.writer(item.jobId(), this.callers.get());
		SelfCheckRequest answers = body.selfCheck();
		Item submitted = this.reviews.submit(writer, workItemId, body.version(),
				new SelfCheck(answers.meetsCriteria(), answers.tested(), answers.docsUpdated(), answers.noSecrets()),
				body.note() == null ? "" : body.note().strip(), resolution(body.sensitive()));
		return this.views.detail(writer, submitted, this.reads.historyOf(writer, submitted.id()));
	}

	/** "Duyệt" or "Trả lại": the Mentor's review with a comment (FR-25, AD-39). */
	@PostMapping("/api/v1/work-items/{workItemId}/review")
	WorkItemDetailView reviewWorkItem(@PathVariable UUID workItemId, @Valid @RequestBody ReviewRequest body) {
		Item item = this.boards.item(workItemId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		BoardViewer writer = this.access.writer(item.jobId(), this.callers.get());
		Item reviewed = this.reviews.review(writer, workItemId, body.version(), body.decision(),
				body.comment() == null ? "" : body.comment(), resolution(body.sensitive()));
		return this.views.detail(writer, reviewed, this.reads.historyOf(writer, reviewed.id()));
	}

	private static Resolution resolution(@Nullable Resolution resolution) {
		return resolution == null ? Resolution.CHECK : resolution;
	}

}
