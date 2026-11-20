package vn.vibefix.dispute.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import vn.vibefix.delivery.DeliveryEvidence;
import vn.vibefix.delivery.DeliveryEvidence.Report;

/**
 * One Delivery Report as both Evidence Packages show it (FR-53, FR-84, AD-31, AD-40, Story 24.3b): the Dispute's
 * "criteria" tab and the Split Resolution's "Bàn giao" tab read the same view, from the one mapping here, so the two
 * never drift. Each handover carries its criteria with the Mentor's marks and notes beside the Customer's, the
 * preview or why there is none, its images (read through {@code /api/v1/files/{fileId}}, whose policy asks the
 * case) and its links, each with the domain read from its text; nothing a link names is ever requested (AD-32).
 */
final class EvidenceReports {

	/** @param customerMet {@code null} until the Customer decided */
	record EvidenceReportCriterion(UUID criterionId, int number, String statement, boolean reportedMet,
			@Nullable String reportNote, @Nullable Boolean customerMet, @Nullable String customerComment) {
	}

	/** The preview the Mentor linked, with its domain. */
	record EvidencePreview(String url, String domain) {
	}

	/** One image, read through {@code /api/v1/files/{fileId}}. */
	record EvidenceImage(UUID fileId, String fileName, long size) {
	}

	/**
	 * One link in the Mentor's order.
	 * @param domain its host as typed, in ASCII
	 */
	record EvidenceLink(DeliveryEvidence.LinkKind kind, String label, String url, String domain) {
	}

	/**
	 * One handover.
	 * @param status {@code SUBMITTED}, {@code ACCEPTED}, {@code REVISION_REQUESTED}, or {@code VOIDED}
	 * @param preview absent when the Mentor gave a reason instead
	 * @param noPreviewReason why a preview was impossible, in the Mentor's words
	 */
	record EvidenceReport(UUID handoverId, int milestoneNumber, String milestoneTitle, int round, String status,
			Instant submittedAt, @Nullable Instant decidedAt, boolean auto, @Nullable String handoverCommit,
			List<EvidenceReportCriterion> criteria, @Nullable EvidencePreview preview, @Nullable String noPreviewReason,
			List<EvidenceImage> images, List<EvidenceLink> links) {
	}

	private EvidenceReports() {
	}

	/** The view of {@code report}. */
	static EvidenceReport of(Report report) {
		return new EvidenceReport(report.handoverId(), report.milestoneNumber(), report.milestoneTitle(), report.round(),
				report.status(), report.submittedAt(), report.decidedAt(), report.auto(), report.handoverCommit(),
				report.criteria()
					.stream()
					.map(criterion -> new EvidenceReportCriterion(criterion.criterionId(), criterion.number(),
							criterion.statement(), criterion.reportedMet(), criterion.reportNote(), criterion.customerMet(),
							criterion.customerComment()))
					.toList(),
				preview(report), report.noPreviewReason(),
				report.images()
					.stream()
					.map(image -> new EvidenceImage(image.fileId(), image.fileName(), image.size()))
					.toList(),
				report.links()
					.stream()
					.map(link -> new EvidenceLink(link.kind(), link.label(), link.url(), link.domain()))
					.toList());
	}

	private static @Nullable EvidencePreview preview(Report report) {
		String url = report.previewUrl();
		String domain = report.previewDomain();
		return url == null || domain == null ? null : new EvidencePreview(url, domain);
	}

}
