package vn.vibefix.job;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The Acceptance Criteria of the Agreement version in force, as {@code work} seeds its backlog from them
 * (FR-22, AD-29, AD-33): the latest confirmed version, each criterion with its stable ID.
 */
public interface AgreementCriteria {

	/**
	 * One criterion in force.
	 * @param milestoneNumber the Milestone's number from 1, in the Agreement's order
	 * @param number the criterion's number within its Milestone, from 1
	 * @param checkSteps "Cách kiểm tra", how the Customer checks it
	 */
	record CriterionInForce(UUID criterionId, UUID milestoneId, int milestoneNumber, String milestoneTitle, int number,
			String statement, String checkSteps) {
	}

	/** One Milestone in force, numbered from 1 in the Agreement's order. */
	record MilestoneInForce(UUID milestoneId, int number, String title) {
	}

	/** The Job's Milestones, and its criteria in Milestone order, then criterion order. */
	record CriteriaInForce(UUID jobId, UUID productId, int agreementVersionNo, List<MilestoneInForce> milestones,
			List<CriterionInForce> criteria) {

		public CriteriaInForce {
			milestones = List.copyOf(milestones);
			criteria = List.copyOf(criteria);
		}

	}

	/** The criteria of the Job's latest confirmed Agreement version; empty before the first confirmation. */
	Optional<CriteriaInForce> inForce(UUID jobId);

}
