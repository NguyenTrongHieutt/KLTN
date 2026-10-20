package vn.vibefix.delivery.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import vn.vibefix.delivery.internal.Decisions.Check;
import vn.vibefix.delivery.internal.DeliveryAccess.Kind;
import vn.vibefix.delivery.internal.DeliveryAccess.Viewer;
import vn.vibefix.delivery.internal.Handovers.CriterionMark;
import vn.vibefix.delivery.internal.Handovers.Handover;
import vn.vibefix.delivery.internal.Handovers.ItemNote;
import vn.vibefix.delivery.internal.Handovers.Preflight;
import vn.vibefix.delivery.internal.Handovers.Resolution;
import vn.vibefix.identity.MentorDirectory;
import vn.vibefix.identity.MentorDirectory.MentorCard;
import vn.vibefix.job.MilestoneHandovers;
import vn.vibefix.job.MilestoneHandovers.Rounds;
import vn.vibefix.payment.Ledger;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;
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
	 */
	record HandoverPreflightView(HandoverMilestoneView milestone, String state, long version, boolean assessment,
			List<HandoverCriterionInputView> criteria, List<HandoverWorkItemView> workItems,
			@Nullable HandoverReportRefView report, List<String> problems) {
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
			@Nullable @Size(max = 200) List<@Valid HandoverItemNoteRequest> itemNotes, @Nullable Resolution sensitive) {
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
	 */
	record DeliveryReportView(UUID id, UUID jobId, String jobTitle, int number, String milestoneTitle, int round,
			String status, String mentorName, Instant createdAt, Instant checkBy, boolean assessment, String audience,
			List<HandoverCriterionView> criteria, List<HandoverItemNoteView> itemNotes, @Nullable HandoverReportView report,
			long version, int roundsLeft, @Nullable HandoverDecisionView decision, @Nullable Instant decidedAt,
			List<HandoverCheckView> checks, boolean auto) {
	}

	private final DeliveryAccess access;

	private final Handovers handovers;

	private final Decisions decisions;

	private final MilestoneHandovers jobs;

	private final Ledger ledger;

	private final MentorDirectory mentors;

	private final CurrentCaller callers;

	HandoverController(DeliveryAccess access, Handovers handovers, Decisions decisions, MilestoneHandovers jobs, Ledger ledger,
			MentorDirectory mentors, CurrentCaller callers) {
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
		Preflight preflight = this.handovers.preflight(mentor, target);
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
				preflight.problems());
	}

	/** "Gửi bàn giao": M2 with the Delivery Report (FR-32). */
	@PostMapping("/api/v1/boards/{boardId}/handovers")
	@ResponseStatus(HttpStatus.CREATED)
	DeliveryReportView handOver(@PathVariable("boardId") UUID jobId, @Valid @RequestBody HandoverRequest body) {
		Viewer mentor = this.access.maker(jobId, this.callers.get());
		Handover handover = this.handovers.create(mentor, body.milestoneId(), body.version(),
				body.criteria().stream().map(c -> new CriterionMark(c.criterionId(), c.met(), c.note())).toList(),
				body.itemNotes() == null ? List.of()
						: body.itemNotes().stream().map(n -> new ItemNote(n.workItemId(), n.note())).toList(),
				body.sensitive() == null ? Resolution.CHECK : body.sensitive());
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
				handover.auto());
	}

}
