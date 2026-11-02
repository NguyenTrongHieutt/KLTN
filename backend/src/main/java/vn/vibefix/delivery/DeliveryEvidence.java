package vn.vibefix.delivery;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * A Job's Milestone Handovers as a Dispute's Evidence Package reads them (FR-53, AD-31): each Delivery
 * Report with every criterion's reported state next to the Customer's mark, and how the handover was decided.
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

	/**
	 * One Milestone Handover.
	 * @param status {@code SUBMITTED}, {@code ACCEPTED}, {@code REVISION_REQUESTED}, or {@code VOIDED}
	 * @param auto accepted by the System when the check period passed
	 */
	record Report(UUID handoverId, UUID milestoneId, int milestoneNumber, String milestoneTitle, int round, String status,
			Instant submittedAt, @Nullable Instant decidedAt, boolean auto, @Nullable Instant voidedAt,
			@Nullable String handoverCommit, List<CriterionStatus> criteria) {

		public Report {
			criteria = List.copyOf(criteria);
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

}
