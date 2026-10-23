package vn.vibefix.admin.internal;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.admin.internal.AbuseReports.Action;
import vn.vibefix.admin.internal.AbuseReports.Report;
import vn.vibefix.identity.AccountAdministration;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;

/**
 * ADM-6's "Báo cáo vi phạm" tab (FR-69, FR-60, AD-20, UX-DR16): the queue, "Nhận xử lý", which for a chat
 * message or a Work Item note opens a grant on that one item (read through {@code chat}'s and {@code work}'s
 * endpoints, audited there), and the assignee's decision: hide through FR-60, an action taken elsewhere, or
 * dismissal, each with the outcome the reporter reads. The queue shows titles and names, never a message's or
 * a note's text.
 */
@RestController
@RequestMapping("/api/v1/admin/abuse-reports")
@Access(Gate.ADMINISTRATOR)
class AdminAbuseReportController {

	/** @param status {@code OPEN}, {@code HIDDEN}, {@code ACTION_TAKEN}, or {@code DISMISSED} */
	record AbuseReportRow(UUID id, String kind, String label, String reason, @Nullable String reporterName,
			Instant createdAt, String status, boolean assigned, boolean assignedToMe) {
	}

	/**
	 * @param hideable FR-60 hides this kind, so "Ẩn nội dung" is offered
	 * @param itemReadable the viewer is the assignee and reads the reported message or note now
	 */
	record AbuseReportDetail(UUID id, String kind, UUID targetId, @Nullable UUID jobId, String label, String reason,
			@Nullable String reporterName, @Nullable String authorName, Instant createdAt, String status, boolean assigned,
			boolean assignedToMe, boolean hideable, boolean itemReadable, @Nullable String outcome,
			@Nullable Instant decidedAt) {
	}

	/** @param hideReason what the author reads, for {@code HIDE} */
	record AbuseReportDecisionRequest(@NotNull Action action, @Nullable @Size(max = 500) String hideReason,
			@NotBlank @Size(max = 500) String outcome) {
	}

	private final AbuseReports reports;

	private final AccountAdministration accounts;

	private final CurrentCaller callers;

	AdminAbuseReportController(AbuseReports reports, AccountAdministration accounts, CurrentCaller callers) {
		this.reports = reports;
		this.accounts = accounts;
		this.callers = callers;
	}

	@GetMapping
	List<AbuseReportRow> queue() {
		UUID me = this.callers.get().requireAccountId();
		List<Report> queue = this.reports.queue();
		Set<UUID> people = new HashSet<>();
		queue.stream().map(Report::reporterId).filter(id -> id != null).forEach(people::add);
		Map<UUID, String> names = this.accounts.names(people);
		return queue.stream()
			.map(report -> new AbuseReportRow(report.id(), report.kind(), report.label(), report.reason(),
					report.reporterId() == null ? null : names.get(report.reporterId()), report.createdAt(), report.status(),
					report.assigneeId() != null, me.equals(report.assigneeId())))
			.toList();
	}

	@GetMapping("/{reportId}")
	AbuseReportDetail report(@PathVariable UUID reportId) {
		return detail(this.reports.find(reportId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)));
	}

	/** "Nhận xử lý": the viewer takes the report, over another Administrator if need be. */
	@PostMapping("/{reportId}/take")
	AbuseReportDetail take(@PathVariable UUID reportId) {
		return detail(this.reports.take(reportId, this.callers.get().requireAccountId()));
	}

	/** The assignee's decision; the reporter receives {@code admin.abuse_report_resolved}. */
	@PostMapping("/{reportId}/decision")
	AbuseReportDetail decide(@PathVariable UUID reportId, @Valid @RequestBody AbuseReportDecisionRequest body) {
		return detail(this.reports.decide(reportId, this.callers.get().requireAccountId(), body.action(), body.hideReason(),
				body.outcome()));
	}

	private AbuseReportDetail detail(Report report) {
		UUID me = this.callers.get().requireAccountId();
		Set<UUID> people = new HashSet<>();
		if (report.reporterId() != null) {
			people.add(report.reporterId());
		}
		if (report.authorId() != null) {
			people.add(report.authorId());
		}
		Map<UUID, String> names = this.accounts.names(people);
		return new AbuseReportDetail(report.id(), report.kind(), report.targetId(), report.jobId(), report.label(),
				report.reason(), report.reporterId() == null ? null : names.get(report.reporterId()),
				report.authorId() == null ? null : names.get(report.authorId()), report.createdAt(), report.status(),
				report.assigneeId() != null, me.equals(report.assigneeId()), ContentHiding.KINDS.contains(report.kind()),
				this.reports.itemReadable(report, me), report.outcome(), report.decidedAt());
	}

}
