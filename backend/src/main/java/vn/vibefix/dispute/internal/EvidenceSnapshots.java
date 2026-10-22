package vn.vibefix.dispute.internal;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;
import vn.vibefix.chat.ChatEvidence;
import vn.vibefix.chat.ChatEvidence.ChatExtent;
import vn.vibefix.delivery.DeliveryEvidence;
import vn.vibefix.delivery.DeliveryEvidence.CriterionStatus;
import vn.vibefix.dispute.internal.Disputes.Dispute;
import vn.vibefix.job.AgreementHistory;
import vn.vibefix.job.AgreementHistory.ConfirmedVersion;
import vn.vibefix.job.JobOversight;
import vn.vibefix.payment.Ledger;
import vn.vibefix.payment.Ledger.EvidenceView;
import vn.vibefix.repo.DriftEvidence;
import vn.vibefix.work.WorkEvidence;

/**
 * A decision's evidence snapshot (SG-27, AD-31, AD-24): written with the decision, from the same owning
 * modules' queries the Evidence Package reads, as IDs, versions, counts, and totals, never copies of the
 * content and never an account ID. It stays with the decision after the NFR-10 retirement of the Job's
 * chat, Pages, and Work Items, so the decision remains explainable.
 */
@Component
class EvidenceSnapshots {

	/** @param status {@code SUBMITTED}, {@code ACCEPTED}, {@code REVISION_REQUESTED}, or {@code VOIDED} */
	record SnapshotReport(UUID handoverId, int milestoneNumber, int round, String status, int criteria, int reportedMet,
			int checkedMet) {
	}

	record SnapshotWorkItem(UUID workItemId, int number, long version, String status) {
	}

	record SnapshotPage(UUID pageId, int versionNo) {
	}

	/** @param lastMessageId the newest Job Chat message when the decision was made */
	record SnapshotChat(int messageCount, @Nullable UUID lastMessageId, @Nullable Instant lastSentAt) {
	}

	/** @param moneyInVnd FUND into the Milestone bucket plus TOPUP_APPLIED */
	record SnapshotPayment(UUID milestoneId, int number, long moneyInVnd, long refundsVnd, long releasedTotalVnd,
			long releasableVnd) {
	}

	record SnapshotDrift(UUID id, String kind, @Nullable String headSha, int commits) {
	}

	/** @param side {@code CUSTOMER} or {@code MENTOR} */
	record SnapshotStatement(String side, Instant submittedAt) {
	}

	/** Everything the decision was based on, as it stood at {@code takenAt}. */
	record EvidenceSnapshot(Instant takenAt, List<Integer> agreementVersions, List<SnapshotReport> reports,
			List<SnapshotWorkItem> workItems, List<SnapshotPage> pages, SnapshotChat jobChat, List<SnapshotPayment> payments,
			List<SnapshotDrift> driftWarnings, List<SnapshotStatement> statements) {
	}

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final JdbcClient jdbc;

	private final AgreementHistory agreements;

	private final DeliveryEvidence deliveries;

	private final WorkEvidence work;

	private final ChatEvidence chat;

	private final Ledger ledger;

	private final JobOversight jobs;

	private final DriftEvidence drift;

	private final DisputeStatements statements;

	EvidenceSnapshots(JdbcClient jdbc, AgreementHistory agreements, DeliveryEvidence deliveries, WorkEvidence work,
			ChatEvidence chat, Ledger ledger, JobOversight jobs, DriftEvidence drift, DisputeStatements statements) {
		this.jdbc = jdbc;
		this.agreements = agreements;
		this.deliveries = deliveries;
		this.work = work;
		this.chat = chat;
		this.ledger = ledger;
		this.jobs = jobs;
		this.drift = drift;
		this.statements = statements;
	}

	/** The Dispute's evidence as it stands at {@code at}. */
	EvidenceSnapshot take(Dispute dispute, Instant at) {
		UUID job = dispute.jobId();
		List<SnapshotReport> reports = this.deliveries.reports(job)
			.stream()
			.map(report -> new SnapshotReport(report.handoverId(), report.milestoneNumber(), report.round(), report.status(),
					report.criteria().size(), (int) report.criteria().stream().filter(CriterionStatus::reportedMet).count(),
					(int) report.criteria().stream().filter(criterion -> Boolean.TRUE.equals(criterion.customerMet())).count()))
			.toList();
		ChatExtent extent = this.chat.jobChat(job);
		List<SnapshotPayment> payments = this.jobs.job(job)
			.map(JobOversight.JobLine::milestones)
			.orElse(List.of())
			.stream()
			.filter(milestone -> dispute.milestoneId() == null || milestone.id().equals(dispute.milestoneId()))
			.map(milestone -> {
				EvidenceView view = this.ledger.evidenceView(milestone.id());
				long in = view.moneyIn()
					.stream()
					.filter(line -> "TOPUP_APPLIED".equals(line.type()) || "MILESTONE".equals(line.bucket()))
					.mapToLong(Ledger.MoneyIn::amountVnd)
					.sum();
				long refunds = view.refunds().stream().mapToLong(Ledger.RefundLine::amountVnd).sum();
				return new SnapshotPayment(milestone.id(), milestone.number(), in, refunds, view.releasedTotal(),
						view.releasable());
			})
			.toList();
		return new EvidenceSnapshot(at,
				this.agreements.confirmedVersions(job).stream().map(ConfirmedVersion::versionNo).toList(), reports,
				this.work.workItems(job)
					.stream()
					.map(item -> new SnapshotWorkItem(item.workItemId(), item.number(), item.version(), item.status()))
					.toList(),
				this.work.pages(job).stream().map(page -> new SnapshotPage(page.pageId(), page.versionNo())).toList(),
				new SnapshotChat(extent.messageCount(), extent.lastMessageId(), extent.lastSentAt()), payments,
				this.drift.driftWarnings(job)
					.stream()
					.map(warning -> new SnapshotDrift(warning.id(), warning.kind(), warning.headSha(), warning.commits().size()))
					.toList(),
				this.statements.of(dispute.id())
					.stream()
					.map(statement -> new SnapshotStatement(statement.side().name(), statement.submittedAt()))
					.toList());
	}

	/** The snapshot as the decision row stores it, written once with the decision and never changed. */
	static String json(EvidenceSnapshot snapshot) {
		return JSON.writeValueAsString(snapshot);
	}

	/** The decision's snapshot; empty before the decision, and for a decision recorded before Story 15.3. */
	Optional<EvidenceSnapshot> of(UUID disputeId) {
		return this.jdbc.sql("""
				SELECT evidence_snapshot::text FROM dispute.decision WHERE dispute_id = :id AND evidence_snapshot IS NOT NULL
				""")
			.param("id", disputeId)
			.query((rs, n) -> JSON.readValue(rs.getString(1), EvidenceSnapshot.class))
			.optional();
	}

}
