package vn.vibefix.dispute.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.audit.AuditReader;
import vn.vibefix.delivery.DeliveryEvidence;
import vn.vibefix.delivery.DeliveryEvidence.Report;
import vn.vibefix.dispute.internal.Disputes.Dispute;
import vn.vibefix.dispute.internal.EvidencePackage.Tab;
import vn.vibefix.identity.AccountAdministration;
import vn.vibefix.job.AgreementHistory;
import vn.vibefix.job.AgreementHistory.ConfirmedVersion;
import vn.vibefix.job.JobOversight;
import vn.vibefix.job.JobOversight.MilestoneLine;
import vn.vibefix.payment.Ledger;
import vn.vibefix.payment.Ledger.EvidenceView;
import vn.vibefix.repo.DriftEvidence;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.Gate;

/**
 * ADM-4's Evidence Package (FR-53, FR-63, AD-8, AD-20, AD-31, UX-DR58): the tabs {@code dispute} assembles on
 * demand from the owning modules' queries, each view audited, for the Dispute's assignee only. The Job Chat,
 * Work Items, and Pages tabs are {@code chat}'s and {@code work}'s endpoints under the assignee's grant. Never
 * the Crew Chat, the Crew Terms, private feedback, or per-party release entries.
 */
@RestController
@RequestMapping("/api/v1/admin/disputes/{disputeId}/evidence")
@Access(Gate.ADMINISTRATOR)
class AdminEvidenceController {

	record EvidenceCriterion(UUID criterionId, int number, String statement, String checkSteps) {
	}

	record EvidenceMilestone(UUID milestoneId, int number, String title, @Nullable Long priceVnd, @Nullable LocalDate dueOn,
			List<EvidenceCriterion> criteria) {
	}

	record EvidenceAgreementVersion(int versionNo, Instant approvedAt, Instant confirmedAt, String scope,
			@Nullable Long priceVnd, List<EvidenceMilestone> milestones) {
	}

	/** @param customerMet {@code null} until the Customer decided */
	record EvidenceReportCriterion(UUID criterionId, int number, String statement, boolean reportedMet,
			@Nullable String reportNote, @Nullable Boolean customerMet, @Nullable String customerComment) {
	}

	/** @param status {@code SUBMITTED}, {@code ACCEPTED}, {@code REVISION_REQUESTED}, or {@code VOIDED} */
	record EvidenceReport(UUID handoverId, int milestoneNumber, String milestoneTitle, int round, String status,
			Instant submittedAt, @Nullable Instant decidedAt, boolean auto, @Nullable String handoverCommit,
			List<EvidenceReportCriterion> criteria) {
	}

	/**
	 * One approval or revision request, oldest first.
	 * @param kind {@code AGREEMENT_APPROVED}, {@code AGREEMENT_CONFIRMED}, {@code HANDOVER_ACCEPTED},
	 * {@code HANDOVER_AUTO_ACCEPTED}, {@code HANDOVER_REVISION_REQUESTED}, or {@code HANDOVER_VOIDED}
	 */
	record EvidenceDecision(String kind, Instant at, @Nullable Integer versionNo, @Nullable Integer milestoneNumber,
			@Nullable String milestoneTitle, @Nullable Integer round) {
	}

	/** @param type {@code FUND} or {@code TOPUP_APPLIED}; @param bucket {@code MILESTONE} or {@code CHANGE_REQUEST} */
	record EvidenceMoneyIn(String type, @Nullable String bucket, long amountVnd, Instant at) {
	}

	record EvidenceRefund(long amountVnd, String reason, String status, Instant requestedAt, @Nullable Instant confirmedAt) {
	}

	record EvidencePayments(UUID milestoneId, int number, String title, List<EvidenceMoneyIn> moneyIn,
			List<EvidenceRefund> refunds, long releasedTotalVnd, long releasableVnd) {
	}

	record EvidenceDriftWarning(UUID id, String kind, @Nullable String headSha, @Nullable String fullName,
			List<String> commits, Instant detectedAt, @Nullable Instant reconfirmedAt) {
	}

	record EvidenceAuditEntry(UUID id, Instant occurredAt, boolean system, @Nullable String actorName, String action) {
	}

	record EvidenceAuditPage(List<EvidenceAuditEntry> items, long total, int page, int size) {
	}

	private final EvidencePackage evidence;

	private final AgreementHistory agreements;

	private final DeliveryEvidence deliveries;

	private final Ledger ledger;

	private final JobOversight jobs;

	private final DriftEvidence drift;

	private final AuditReader audit;

	private final AccountAdministration accounts;

	private final CurrentCaller callers;

	AdminEvidenceController(EvidencePackage evidence, AgreementHistory agreements, DeliveryEvidence deliveries, Ledger ledger,
			JobOversight jobs, DriftEvidence drift, AuditReader audit, AccountAdministration accounts, CurrentCaller callers) {
		this.evidence = evidence;
		this.agreements = agreements;
		this.deliveries = deliveries;
		this.ledger = ledger;
		this.jobs = jobs;
		this.drift = drift;
		this.audit = audit;
		this.accounts = accounts;
		this.callers = callers;
	}

	/** "Phiên bản thỏa thuận": every confirmed Agreement version, the first first. */
	@GetMapping("/agreement")
	List<EvidenceAgreementVersion> agreement(@PathVariable UUID disputeId) {
		Dispute dispute = view(disputeId, Tab.AGREEMENT);
		return this.agreements.confirmedVersions(dispute.jobId())
			.stream()
			.map(version -> new EvidenceAgreementVersion(version.versionNo(), version.approvedAt(), version.confirmedAt(),
					version.scope(), version.priceVnd(), version.milestones()
						.stream()
						.map(milestone -> new EvidenceMilestone(milestone.milestoneId(), milestone.number(), milestone.title(),
								milestone.priceVnd(), milestone.dueOn(), milestone.criteria()
									.stream()
									.map(criterion -> new EvidenceCriterion(criterion.criterionId(), criterion.number(),
											criterion.statement(), criterion.checkSteps()))
									.toList()))
						.toList()))
			.toList();
	}

	/** "Tiêu chí trong báo cáo bàn giao": each Delivery Report's criteria, reported and checked. */
	@GetMapping("/criteria")
	List<EvidenceReport> criteria(@PathVariable UUID disputeId) {
		Dispute dispute = view(disputeId, Tab.CRITERIA);
		return this.deliveries.reports(dispute.jobId())
			.stream()
			.map(report -> new EvidenceReport(report.handoverId(), report.milestoneNumber(), report.milestoneTitle(),
					report.round(), report.status(), report.submittedAt(), report.decidedAt(), report.auto(),
					report.handoverCommit(), report.criteria()
						.stream()
						.map(criterion -> new EvidenceReportCriterion(criterion.criterionId(), criterion.number(),
								criterion.statement(), criterion.reportedMet(), criterion.reportNote(), criterion.customerMet(),
								criterion.customerComment()))
						.toList()))
			.toList();
	}

	/** "Duyệt và yêu cầu sửa": Agreement approvals and confirmations, and how each handover was decided. */
	@GetMapping("/decisions")
	List<EvidenceDecision> decisions(@PathVariable UUID disputeId) {
		Dispute dispute = view(disputeId, Tab.DECISIONS);
		List<EvidenceDecision> lines = new ArrayList<>();
		for (ConfirmedVersion version : this.agreements.confirmedVersions(dispute.jobId())) {
			lines.add(new EvidenceDecision("AGREEMENT_APPROVED", version.approvedAt(), version.versionNo(), null, null, null));
			lines.add(new EvidenceDecision("AGREEMENT_CONFIRMED", version.confirmedAt(), version.versionNo(), null, null, null));
		}
		for (Report report : this.deliveries.reports(dispute.jobId())) {
			if (report.voidedAt() != null) {
				lines.add(handover("HANDOVER_VOIDED", report.voidedAt(), report));
			}
			else if (report.decidedAt() != null) {
				String kind = switch (report.status()) {
					case "ACCEPTED" -> report.auto() ? "HANDOVER_AUTO_ACCEPTED" : "HANDOVER_ACCEPTED";
					default -> "HANDOVER_REVISION_REQUESTED";
				};
				lines.add(handover(kind, report.decidedAt(), report));
			}
		}
		lines.sort(Comparator.comparing(EvidenceDecision::at));
		return lines;
	}

	/**
	 * "Thanh toán": {@code payment.evidenceView} of the disputed Milestone, or of every live Milestone when the
	 * Dispute names none.
	 */
	@GetMapping("/payments")
	List<EvidencePayments> payments(@PathVariable UUID disputeId) {
		Dispute dispute = view(disputeId, Tab.PAYMENTS);
		List<MilestoneLine> milestones = this.jobs.job(dispute.jobId()).map(JobOversight.JobLine::milestones).orElse(List.of());
		return milestones.stream()
			.filter(milestone -> dispute.milestoneId() == null || milestone.id().equals(dispute.milestoneId()))
			.map(milestone -> {
				EvidenceView view = this.ledger.evidenceView(milestone.id());
				return new EvidencePayments(milestone.id(), milestone.number(), milestone.title(),
						view.moneyIn()
							.stream()
							.map(line -> new EvidenceMoneyIn(line.type(), line.bucket(), line.amountVnd(), line.at()))
							.toList(),
						view.refunds()
							.stream()
							.map(refund -> new EvidenceRefund(refund.amountVnd(), refund.reason(), refund.status(),
									refund.requestedAt(), refund.confirmedAt()))
							.toList(),
						view.releasedTotal(), view.releasable());
			})
			.toList();
	}

	/** "Cảnh báo thay đổi": the Drift Warnings with the commits that triggered them. */
	@GetMapping("/drift")
	List<EvidenceDriftWarning> drift(@PathVariable UUID disputeId) {
		Dispute dispute = view(disputeId, Tab.DRIFT);
		return this.drift.driftWarnings(dispute.jobId())
			.stream()
			.map(warning -> new EvidenceDriftWarning(warning.id(), warning.kind(), warning.headSha(), warning.fullName(),
					warning.commits(), warning.detectedAt(), warning.reconfirmedAt()))
			.toList();
	}

	/** "Nhật ký kiểm toán": the Job's Audit Log, newest first, 50 entries a page. */
	@GetMapping("/audit")
	EvidenceAuditPage audit(@PathVariable UUID disputeId, @RequestParam(defaultValue = "0") int page) {
		Dispute dispute = view(disputeId, Tab.AUDIT);
		AuditReader.Page found = this.audit.search(dispute.jobId(), null, Math.max(page, 0));
		Set<UUID> actors = new HashSet<>();
		found.items().forEach(entry -> {
			if (entry.actorAccountId() != null) {
				actors.add(entry.actorAccountId());
			}
		});
		Map<UUID, String> names = this.accounts.names(actors);
		return new EvidenceAuditPage(found.items()
			.stream()
			.map(entry -> new EvidenceAuditEntry(entry.id(), entry.occurredAt(), entry.actorAccountId() == null,
					entry.actorAccountId() == null ? null : names.get(entry.actorAccountId()), entry.action()))
			.toList(), found.total(), found.page(), found.size());
	}

	private Dispute view(UUID disputeId, Tab tab) {
		return this.evidence.view(disputeId, this.callers.get().requireAccountId(), tab);
	}

	private static EvidenceDecision handover(String kind, Instant at, Report report) {
		return new EvidenceDecision(kind, at, null, report.milestoneNumber(), report.milestoneTitle(), report.round());
	}

}
