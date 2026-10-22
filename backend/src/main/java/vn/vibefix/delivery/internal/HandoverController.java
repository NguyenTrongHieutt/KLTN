package vn.vibefix.delivery.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import vn.vibefix.delivery.internal.Decisions.Check;
import vn.vibefix.delivery.internal.DeliveryAccess.Kind;
import vn.vibefix.delivery.internal.DeliveryAccess.Viewer;
import vn.vibefix.delivery.internal.Handovers.CodeRecord;
import vn.vibefix.delivery.internal.Handovers.CriterionMark;
import vn.vibefix.delivery.internal.Handovers.Draft;
import vn.vibefix.delivery.internal.Handovers.Evidence;
import vn.vibefix.delivery.internal.Handovers.Handover;
import vn.vibefix.delivery.internal.Handovers.ItemNote;
import vn.vibefix.delivery.internal.Handovers.Preflight;
import vn.vibefix.delivery.internal.Handovers.Preview;
import vn.vibefix.delivery.internal.HandoverScreenshots.Screenshot;
import vn.vibefix.delivery.internal.Handovers.Resolution;
import vn.vibefix.identity.MentorDirectory;
import vn.vibefix.identity.MentorDirectory.MentorCard;
import vn.vibefix.job.MilestoneHandovers;
import vn.vibefix.job.MilestoneHandovers.Rounds;
import vn.vibefix.files.PreparedFile;
import vn.vibefix.payment.Ledger;
import vn.vibefix.scanning.SensitiveChanges;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;
import vn.vibefix.shared.IdGenerator;
import vn.vibefix.work.MilestoneWork.ReportVersion;

/**
 * JOB-5 "Bàn giao" (FR-10, FR-32, AD-20, AD-23, UX-DR53, UX-DR54): the Job's handovers, the Mentor's
 * preflight and "Gửi bàn giao", and the Delivery Report, under {@code /api/v1/boards/{boardId}} like the
 * Job's other Active-phase reads (a Board's ID is its Job's). The Customer, the Mentor, and the active
 * Crew read; only the Mentor hands over; the Customer's report names no Work Item (AD-39).
 */
@RestController
@Access(Gate.SIGNED_IN)
class HandoverController {

	record HandoverMilestoneView(UUID milestoneId, int number, String title) {
	}

	/**
	 * One handover in the list.
	 * @param round the n-th handover of its Milestone
	 * @param checkBy when the Customer should have checked by
	 */
	record HandoverSummaryView(UUID id, int number, String milestoneTitle, int round, String status, Instant createdAt,
			Instant checkBy, boolean auto) {
	}

	/**
	 * JOB-5's list.
	 * @param audience {@code CUSTOMER}, {@code MENTOR}, or {@code CREW}
	 * @param waiting for the Mentor, the Milestone that waits for a handover
	 */
	record HandoverListView(UUID jobId, String title, String audience, boolean assessment,
			@Nullable HandoverMilestoneView waiting, List<HandoverSummaryView> handovers) {
	}

	record HandoverCriterionInputView(UUID criterionId, int number, String statement, String checkSteps) {
	}

	/** @param approved in "Xong" through the Mentor's approval */
	record HandoverWorkItemView(UUID id, String key, String title, String status, boolean approved) {
	}

	record HandoverReportRefView(UUID pageId, int versionNo, String title) {
	}

	/**
	 * The "Tạo bàn giao" form.
	 * @param version the Job's version, which the handover carries (AD-23)
	 * @param problems what would refuse the handover now, as reason codes
	 * @param sensitiveChanges a Code Job's Sensitive Changes in the commits it would hand over (UX-DR54)
	 * @param commitCount how many commits a Code Job would hand over
	 * @param previewRequired the Job Types change the running Product: a preview, or a reason and a recording
	 * @param screenshots the Mentor's uploads waiting for this handover
	 */
	record HandoverPreflightView(HandoverMilestoneView milestone, String state, long version, boolean assessment,
			List<HandoverCriterionInputView> criteria, List<HandoverWorkItemView> workItems,
			@Nullable HandoverReportRefView report, List<String> problems, List<SensitiveChangeView> sensitiveChanges,
			int commitCount, boolean previewRequired, List<HandoverScreenshotView> screenshots) {
	}

	/** One screenshot: read through {@code /api/v1/files/{fileId}}. */
	record HandoverScreenshotView(UUID fileId, String fileName, long size) {
	}

	/**
	 * A link to try the change, with its domain for N-11 (AD-35).
	 * @param checkedAt when the preview last responded; for the Mentor and the Crew only
	 */
	record HandoverLinkView(String url, String domain, @Nullable Instant checkedAt) {
	}

	/** The preview, or the recording with why a preview is impossible (FR-32). */
	record HandoverPreviewView(@Nullable HandoverLinkView preview, @Nullable HandoverLinkView recording,
			@Nullable String noPreviewReason) {
	}

	/** "Kiểm tra đường dẫn" on the form. */
	record PreviewCheckRequest(@NotNull @Size(max = 500) @Pattern(regexp = "^https://\\S+$") String url) {
	}

	/** @param reason why it did not respond, as the check's code */
	record PreviewCheckView(boolean reachable, String domain, @Nullable String reason) {
	}

	/** One Sensitive Change: the path, shown in mono, and its kind, which the screen explains plainly. */
	record SensitiveChangeView(String path, SensitiveChanges.Kind kind) {
	}

	/** One commit of a Code handover, as "Chi tiết kỹ thuật" lists it (NFR-3: text only). */
	record HandoverCommitView(String sha, String message, List<String> workItemKeys) {
	}

	/** A Page updated during the Milestone; the Customer sees only customer-visible ones. */
	record HandoverPageView(UUID pageId, String title, int versionNo) {
	}

	/**
	 * What a Code Job's handover pinned (FR-32, AD-11, AD-33).
	 * @param unattributed commits whose author GitHub resolved to no account
	 * @param truncated GitHub listed only part of the range
	 */
	record HandoverCodeView(String handoverCommit, int pullNumber, List<HandoverCommitView> commits, int unattributed,
			boolean truncated, List<SensitiveChangeView> sensitiveChanges, List<HandoverPageView> pages) {
	}

	record HandoverCriterionMarkRequest(@NotNull UUID criterionId, boolean met,
			@NotNull @Size(max = Handovers.MAX_NOTE) String note) {
	}

	record HandoverItemNoteRequest(@NotNull UUID workItemId, @NotNull @Size(max = Handovers.MAX_NOTE) String note) {
	}

	/**
	 * "Gửi bàn giao".
	 * @param version the Job's version the form loaded
	 * @param sensitive {@code CHECK} first; {@code MASK} after the FR-47 warning
	 */
	record HandoverRequest(@NotNull UUID milestoneId, @NotNull Long version,
			@NotNull @Size(max = 100) List<@Valid HandoverCriterionMarkRequest> criteria,
			@Nullable @Size(max = 200) List<@Valid HandoverItemNoteRequest> itemNotes, @Nullable Resolution sensitive,
			@Nullable @Size(max = 500) @Pattern(regexp = "^https://\\S+$") String previewUrl,
			@Nullable @Size(max = 500) @Pattern(regexp = "^https://\\S+$") String recordingUrl,
			@Nullable @Size(max = 500) String noPreviewReason,
			@Nullable @Size(max = HandoverScreenshots.MAX) List<@NotNull UUID> screenshotIds) {
	}

	/** One criterion as the Mentor marked it: their own statement, never a criterion done (AD-33). */
	record HandoverCriterionView(UUID criterionId, int number, String statement, String checkSteps, boolean met,
			String note) {
	}

	record HandoverItemNoteView(UUID workItemId, String key, String title, String status, String note) {
	}

	record HandoverReportView(UUID pageId, int versionNo, String title, String body) {
	}

	/** The Customer's mark on one criterion, once the handover is decided. */
	record HandoverCheckView(UUID criterionId, boolean met, @Nullable String comment) {
	}

	/**
	 * What the Customer decides with, while the handover waits for them.
	 * @param releaseVnd what acceptance releases to the Mentor's Crew, in test-mode VND
	 * @param revisionAllowed a Revision Round is left (FR-34)
	 */
	record HandoverDecisionView(long releaseVnd, boolean revisionAllowed) {
	}

	record HandoverCheckRequest(@NotNull UUID criterionId, boolean met, @Nullable @Size(max = Handovers.MAX_NOTE) String comment) {
	}

	/**
	 * "Nghiệm thu giai đoạn" or "Yêu cầu sửa".
	 * @param version the Job's version the check loaded (AD-23)
	 * @param checks the Customer's marks; a revision names those "Chưa đạt"
	 * @param sensitive {@code CHECK} first; {@code MASK} after the FR-47 warning on a comment
	 */
	record HandoverDecisionRequest(@NotNull Long version, @Nullable @Size(max = 100) List<@Valid HandoverCheckRequest> checks,
			@Nullable Resolution sensitive) {
	}

	/**
	 * The Delivery Report.
	 * @param itemNotes the notes on Work Items that were not done; empty for the Customer (AD-39)
	 * @param report an Assessment Job's Report at the version handed over
	 * @param version the Job's version, which a decision carries (AD-23)
	 * @param roundsLeft the Milestone's Revision Rounds left (FR-34)
	 * @param decision for the Customer while the handover waits for them
	 * @param checks the Customer's marks once decided
	 * @param auto accepted by Auto-acceptance (FR-35)
	 * @param code a Code Job's pinned commit, its range, Sensitive Changes, and updated Pages
	 */
	record DeliveryReportView(UUID id, UUID jobId, String jobTitle, int number, String milestoneTitle, int round,
			String status, String mentorName, Instant createdAt, Instant checkBy, boolean assessment, String audience,
			List<HandoverCriterionView> criteria, List<HandoverItemNoteView> itemNotes, @Nullable HandoverReportView report,
			long version, int roundsLeft, @Nullable HandoverDecisionView decision, @Nullable Instant decidedAt,
			List<HandoverCheckView> checks, boolean auto, @Nullable HandoverCodeView code, HandoverPreviewView preview,
			List<HandoverScreenshotView> screenshots) {
	}

	private final DeliveryAccess access;

	private final Handovers handovers;

	private final Decisions decisions;

	private final MilestoneHandovers jobs;

	private final Ledger ledger;

	private final MentorDirectory mentors;

	private final CurrentCaller callers;

	private final CodeHandovers code;

	private final IdGenerator ids;

	private final PreviewChecks previews;

	private final HandoverScreenshots screenshots;

	HandoverController(DeliveryAccess access, Handovers handovers, Decisions decisions, MilestoneHandovers jobs, Ledger ledger,
			MentorDirectory mentors, CurrentCaller callers, CodeHandovers code, IdGenerator ids, PreviewChecks previews,
			HandoverScreenshots screenshots) {
		this.code = code;
		this.ids = ids;
		this.previews = previews;
		this.screenshots = screenshots;
		this.access = access;
		this.handovers = handovers;
		this.decisions = decisions;
		this.jobs = jobs;
		this.ledger = ledger;
		this.mentors = mentors;
		this.callers = callers;
	}

	@GetMapping("/api/v1/boards/{boardId}/handovers")
	HandoverListView handovers(@PathVariable("boardId") UUID jobId) {
		Viewer viewer = this.access.reader(jobId, this.callers.get());
		HandoverMilestoneView waiting = viewer.mentor()
				? this.handovers.waiting(jobId)
					.map(milestone -> new HandoverMilestoneView(milestone.milestoneId(), milestone.number(), milestone.title()))
					.orElse(null)
				: null;
		return new HandoverListView(jobId, viewer.job().title(), viewer.kind().name(), viewer.job().assessment(), waiting,
				this.handovers.of(jobId)
					.stream()
					.map(handover -> new HandoverSummaryView(handover.id(), handover.number(), handover.milestoneTitle(),
							handover.round(), handover.status(), handover.createdAt(), handover.checkBy(), handover.auto()))
					.toList());
	}

	/**
	 * The Mentor's preflight for "Tạo bàn giao" (UX-DR54).
	 * @param milestoneId the Milestone to hand over; by default the one waiting, else the first in force
	 */
	@GetMapping("/api/v1/boards/{boardId}/handovers/preflight")
	HandoverPreflightView preflight(@PathVariable("boardId") UUID jobId,
			@RequestParam(required = false) @Nullable UUID milestoneId) {
		Viewer mentor = this.access.maker(jobId, this.callers.get());
		UUID target = milestoneId != null ? milestoneId
				: this.handovers.waiting(jobId)
					.or(() -> this.handovers.milestones(jobId).stream().findFirst())
					.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND))
					.milestoneId();
		// A Code Job's checks read GitHub, so they run here, outside the preflight's transaction.
		CodeHandovers.Check code = mentor.job().assessment() ? null : this.code.check(jobId);
		Preflight preflight = this.handovers.preflight(mentor, target, code);
		ReportVersion report = preflight.report();
		return new HandoverPreflightView(
				new HandoverMilestoneView(preflight.milestone().milestoneId(), preflight.milestone().number(),
						preflight.milestone().title()),
				preflight.state(), preflight.jobVersion(), preflight.assessment(),
				preflight.criteria()
					.stream()
					.map(c -> new HandoverCriterionInputView(c.criterionId(), c.number(), c.statement(), c.checkSteps()))
					.toList(),
				preflight.items()
					.stream()
					.map(i -> new HandoverWorkItemView(i.id(), i.key(), i.title(), i.status(), i.approved()))
					.toList(),
				report == null ? null : new HandoverReportRefView(report.pageId(), report.versionNo(), report.title()),
				preflight.problems(),
				preflight.sensitive().stream().map(change -> new SensitiveChangeView(change.path(), change.kind())).toList(),
				preflight.commitCount(), mentor.job().previewRequired(),
				this.screenshots.pending(jobId, mentor.accountId()).stream().map(HandoverController::screenshotView).toList());
	}

	/** "Kiểm tra đường dẫn": the preview check before sending (UX-DR27); "Gửi bàn giao" checks again. */
	@PostMapping("/api/v1/boards/{boardId}/handovers/preview-check")
	PreviewCheckView checkPreview(@PathVariable("boardId") UUID jobId, @Valid @RequestBody PreviewCheckRequest body) {
		this.access.maker(jobId, this.callers.get());
		PreviewChecks.Result result = this.previews.check(body.url());
		return new PreviewCheckView(result.reachable(), result.domain(), result.reason());
	}

	/** A screenshot for the next handover: an image of at most the upload limit, re-encoded (AD-21). */
	@PostMapping(path = "/api/v1/boards/{boardId}/handovers/screenshots", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@ResponseStatus(HttpStatus.CREATED)
	HandoverScreenshotView addScreenshot(@PathVariable("boardId") UUID jobId, @RequestPart("file") MultipartFile file) {
		Viewer mentor = this.access.maker(jobId, this.callers.get());
		byte[] content;
		try {
			content = file.getBytes();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		PreparedFile prepared = this.screenshots.prepare(mentor.accountId(),
				file.getOriginalFilename() == null ? "" : file.getOriginalFilename(), content);
		return screenshotView(this.screenshots.add(mentor, prepared));
	}

	/** Removes an upload that no handover carries yet. */
	@DeleteMapping("/api/v1/boards/{boardId}/handovers/screenshots/{fileId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void removeScreenshot(@PathVariable("boardId") UUID jobId, @PathVariable UUID fileId) {
		this.screenshots.remove(this.access.maker(jobId, this.callers.get()), fileId);
	}

	private static HandoverScreenshotView screenshotView(Screenshot screenshot) {
		return new HandoverScreenshotView(screenshot.fileId(), screenshot.fileName(), screenshot.size());
	}

	/** "Gửi bàn giao": M2 with the Delivery Report (FR-32). */
	@PostMapping("/api/v1/boards/{boardId}/handovers")
	@ResponseStatus(HttpStatus.CREATED)
	DeliveryReportView handOver(@PathVariable("boardId") UUID jobId, @Valid @RequestBody HandoverRequest body) {
		Viewer mentor = this.access.maker(jobId, this.callers.get());
		Draft draft = this.handovers.draft(mentor, body.milestoneId(),
				body.criteria().stream().map(c -> new CriterionMark(c.criterionId(), c.met(), c.note())).toList(),
				body.itemNotes() == null ? List.of()
						: body.itemNotes().stream().map(n -> new ItemNote(n.workItemId(), n.note())).toList(),
				body.sensitive() == null ? Resolution.CHECK : body.sensitive(),
				new Evidence(body.previewUrl(), body.recordingUrl(), body.noPreviewReason(),
						body.screenshotIds() == null ? List.of() : body.screenshotIds()));
		// AD-32: the preview responds now, or nothing is pinned or written.
		if (draft.previewUrl() != null) {
			PreviewChecks.Result result = this.previews.check(draft.previewUrl());
			if (!result.reachable()) {
				throw new ApiException(ErrorCode.GUARD_FAILED, DeliveryReason.PREVIEW_UNREACHABLE,
						java.util.Map.of("domain", result.domain()));
			}
		}
		// A Code Job's commit is pinned on GitHub between the draft and M2 (AD-11).
		Handover handover = mentor.job().assessment()
				? this.handovers.record(mentor, draft, body.version(), this.ids.newId(), null)
				: this.code.handOver(mentor, draft, body.version());
		return report(mentor, handover);
	}

	/** The Delivery Report (UX-DR53). */
	@GetMapping("/api/v1/handovers/{handoverId}")
	DeliveryReportView handover(@PathVariable UUID handoverId) {
		Handover handover = this.handovers.find(handoverId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		Viewer viewer = this.access.reader(handover.jobId(), this.callers.get());
		return report(viewer, handover);
	}

	/** "Nghiệm thu giai đoạn": M5 with the split release (FR-33, FR-42, AD-5). */
	@PostMapping("/api/v1/handovers/{handoverId}/accept")
	DeliveryReportView accept(@PathVariable UUID handoverId, @Valid @RequestBody HandoverDecisionRequest body) {
		Handover handover = this.handovers.find(handoverId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		Viewer viewer = this.access.reader(handover.jobId(), this.callers.get());
		Handover decided = this.decisions.accept(viewer, handoverId, body.version(), checks(body));
		return report(this.access.reader(handover.jobId(), this.callers.get()), decided);
	}

	/** "Yêu cầu sửa ({n} tiêu chí)": M4, one Revision Round (FR-33, FR-34). */
	@PostMapping("/api/v1/handovers/{handoverId}/revision")
	DeliveryReportView requestRevision(@PathVariable UUID handoverId, @Valid @RequestBody HandoverDecisionRequest body) {
		Handover handover = this.handovers.find(handoverId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		Viewer viewer = this.access.reader(handover.jobId(), this.callers.get());
		Handover decided = this.decisions.requestRevision(viewer, handoverId, body.version(), checks(body),
				body.sensitive() == null ? Resolution.CHECK : body.sensitive());
		return report(this.access.reader(handover.jobId(), this.callers.get()), decided);
	}

	private static List<Check> checks(HandoverDecisionRequest body) {
		return body.checks() == null ? List.of()
				: body.checks().stream().map(c -> new Check(c.criterionId(), c.met(), c.comment())).toList();
	}

	private DeliveryReportView report(Viewer viewer, Handover handover) {
		String mentor = handover.mentorId() == null ? ""
				: this.mentors.cards(List.of(handover.mentorId())).stream().findFirst().map(MentorCard::displayName).orElse("");
		List<HandoverItemNoteView> notes = viewer.kind() == Kind.CUSTOMER ? List.of()
				: this.handovers.notes(handover.id())
					.stream()
					.map(n -> new HandoverItemNoteView(n.workItemId(), n.key(), n.title(), n.status(), n.note()))
					.toList();
		HandoverReportView report = this.handovers.report(handover)
			.map(r -> new HandoverReportView(r.pageId(), r.versionNo(), r.title(), r.body()))
			.orElse(null);
		int roundsLeft = this.jobs.rounds(handover.milestoneId()).map(Rounds::left).orElse(0);
		HandoverDecisionView decision = viewer.kind() == Kind.CUSTOMER && "SUBMITTED".equals(handover.status())
				? new HandoverDecisionView(this.ledger.releasable(handover.milestoneId()), roundsLeft > 0)
				: null;
		return new DeliveryReportView(handover.id(), handover.jobId(), viewer.job().title(), handover.number(),
				handover.milestoneTitle(), handover.round(), handover.status(), mentor, handover.createdAt(),
				handover.checkBy(), viewer.job().assessment(), viewer.kind().name(),
				this.handovers.criteria(handover.id())
					.stream()
					.map(c -> new HandoverCriterionView(c.criterionId(), c.number(), c.statement(), c.checkSteps(), c.met(),
							c.note()))
					.toList(),
				notes, report, viewer.job().version(), roundsLeft, decision, handover.decidedAt(),
				this.decisions.checks(handover.id())
					.stream()
					.map(c -> new HandoverCheckView(c.criterionId(), c.met(), c.comment()))
					.toList(),
				handover.auto(), this.handovers.code(handover.id()).map(found -> codeView(viewer, found)).orElse(null),
				previewView(viewer, this.handovers.preview(handover.id()).orElse(new Preview(null, null, null, null))),
				this.screenshots.of(handover.id()).stream().map(HandoverController::screenshotView).toList());
	}

	/** N-11 needs the domain beside each link; the check's time is for the Mentor and the Crew. */
	private static HandoverPreviewView previewView(Viewer viewer, Preview preview) {
		boolean team = viewer.kind() != Kind.CUSTOMER;
		return new HandoverPreviewView(
				preview.previewUrl() == null ? null
						: new HandoverLinkView(preview.previewUrl(), PreviewChecks.domain(preview.previewUrl()),
								team ? preview.previewCheckedAt() : null),
				preview.recordingUrl() == null ? null
						: new HandoverLinkView(preview.recordingUrl(), PreviewChecks.domain(preview.recordingUrl()), null),
				preview.noPreviewReason());
	}

	private static HandoverCodeView codeView(Viewer viewer, CodeRecord code) {
		return new HandoverCodeView(code.commit(), code.pullNumber(),
				code.commits().stream().map(c -> new HandoverCommitView(c.sha(), c.message(), c.workItemKeys())).toList(),
				code.unattributed(), code.truncated(),
				code.sensitive().stream().map(change -> new SensitiveChangeView(change.path(), change.kind())).toList(),
				code.pages()
					.stream()
					.filter(page -> viewer.kind() != Kind.CUSTOMER || page.customerVisible())
					.map(page -> new HandoverPageView(page.pageId(), page.title(), page.versionNo()))
					.toList());
	}

}
