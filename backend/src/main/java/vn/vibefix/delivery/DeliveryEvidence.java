package vn.vibefix.delivery;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * A Job's Milestone Handovers as a Dispute's Evidence Package and a Split Evidence Package read them (FR-53, FR-84,
 * AD-31, AD-40): each Delivery Report with every criterion's reported state next to the Customer's mark, what the
 * handover carried beside them (its preview or why there is none, its images and its links), and how it was decided.
 */
public interface DeliveryEvidence {

	/**
	 * One criterion of a Delivery Report.
	 * @param reportedMet the Mentor's report
	 * @param customerMet the Customer's mark once they decided; {@code null} before
	 */
	record CriterionStatus(UUID criterionId, int number, String statement, boolean reportedMet, @Nullable String reportNote,
			@Nullable Boolean customerMet, @Nullable String customerComment) {
	}

	/** One image a handover carries, read through {@code /api/v1/files/{fileId}} (FR-32). */
	record Image(UUID fileId, String fileName, long size) {
	}

	/** A link's kind as the Mentor picked it: a recording or video, or a document (Story 24.3). */
	enum LinkKind {

		VIDEO, DOCUMENT

	}

	/**
	 * One link a handover carries (FR-10, FR-32, AD-32, AD-35), which vibefix never requests.
	 * @param domain its host as typed, lowercased ASCII, read from the URL's text alone
	 */
	record Link(LinkKind kind, String label, String url, String domain) {
	}

	/**
	 * One Milestone Handover.
	 * @param status {@code SUBMITTED}, {@code ACCEPTED}, {@code REVISION_REQUESTED}, or {@code VOIDED}
	 * @param auto accepted by the System when the check period passed
	 * @param previewUrl the preview the Mentor linked, which the Customer opened before deciding; {@code null} without one
	 * @param previewDomain that preview's domain, read from its text
	 * @param noPreviewReason why a preview was impossible, in the Mentor's words; {@code null} beside a preview
	 * @param images the images in the Mentor's order
	 * @param links the links in the Mentor's order
	 * @param daysLate how many days after the Milestone's due date in force the handover came, 0 when on time (FR-87,
	 * AD-48); {@code null} for a handover made before the figure was recorded
	 */
	record Report(UUID handoverId, UUID milestoneId, int milestoneNumber, String milestoneTitle, int round, String status,
			Instant submittedAt, @Nullable Instant decidedAt, boolean auto, @Nullable Instant voidedAt,
			@Nullable String handoverCommit, List<CriterionStatus> criteria, @Nullable String previewUrl,
			@Nullable String previewDomain, @Nullable String noPreviewReason, List<Image> images, List<Link> links,
			@Nullable Integer daysLate) {

		public Report {
			criteria = List.copyOf(criteria);
			images = List.copyOf(images);
			links = List.copyOf(links);
		}

	}

	/** Every handover of the Job, oldest first. */
	List<Report> reports(UUID jobId);

	/**
	 * One commit of a Milestone's Handover Commits (FR-84, AD-33).
	 * @param message the first line of its message
	 * @param authorGithubId its author's GitHub numeric ID; empty when GitHub resolved no account
	 */
	record CommitLine(UUID handoverId, int round, String sha, String message, @Nullable Long authorGithubId) {
	}

	/** The commits every handover of the Milestone recorded, oldest first: a Split Evidence Package's (AD-40). */
	List<CommitLine> commitsOf(UUID milestoneId);

	/**
	 * Whether any Handover of the Milestone recorded a commit list GitHub's compare had cut (FR-32, Story 8.2):
	 * past 250 commits the range is incomplete, so a count over {@link #commitsOf} is a floor and not a count. The
	 * Delivery Report says so beside the list; a caller that freezes a figure instead (FR-50) must not publish one.
	 */
	boolean commitsTruncatedOf(UUID milestoneId);

}
