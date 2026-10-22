package vn.vibefix.job;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * What {@code delivery} needs from {@code job} to hand a Milestone over (FR-32, AD-4, AD-23): the Job as
 * the handover form loads it, and M2 applied on that version with the handover as its evidence.
 */
public interface MilestoneHandovers {

	/**
	 * A Job with a Board.
	 * @param state the Job's state name
	 * @param assessment an Assessment Job, whose handover is its Assessment Report (FR-10)
	 * @param frozen an open Dispute freezes it (FR-52): Auto-acceptance waits
	 * @param previewRequired a Job Type changes the running Product, so the handover needs a preview (FR-32)
	 */
	record HandoverJob(UUID jobId, UUID productId, UUID customerId, @Nullable UUID mentorId, String title, String state,
			long version, boolean assessment, boolean frozen, boolean previewRequired) {
	}

	/**
	 * What M2 recorded.
	 * @param version the Job's version after it
	 * @param checkBy when the Customer should have checked by: the handover plus the Auto-acceptance days
	 * @param firstReminderAt the handover plus the first reminder's days (FR-35)
	 * @param secondReminderAt the handover plus the second reminder's days
	 */
	record Submitted(UUID transitionId, int number, long version, Instant checkBy, Instant firstReminderAt,
			Instant secondReminderAt) {
	}

	Optional<HandoverJob> job(UUID jobId);

	/**
	 * M2 by the Mentor on the Job version the form loaded, with {@code handoverId} as its evidence.
	 * @throws vn.vibefix.shared.ApiException {@code STALE STATE_CHANGED} for another version or a
	 * Milestone not Funded or Revision Requested; {@code FORBIDDEN MENTOR_ONLY}; {@code CONFLICT
	 * JOB_FROZEN}; {@code GUARD_FAILED HANDOVER_COMMIT_MISSING} for a Code Job
	 */
	Submitted submit(UUID jobId, UUID milestoneId, UUID mentorId, long expectedVersion, UUID handoverId);

	/**
	 * M2 with a Code Job's Handover Commit as its evidence (FR-32, AD-11): the SHA {@code delivery} pinned
	 * to {@code handover/{handoverId}}; {@code null} for an Assessment Job.
	 */
	Submitted submit(UUID jobId, UUID milestoneId, UUID mentorId, long expectedVersion, UUID handoverId,
			@Nullable String handoverCommit);

	/** What M4 or M5 recorded. */
	record Decided(UUID transitionId, int number, long version) {
	}

	/** A Milestone's Revision Rounds and how many are used (FR-34). */
	record Rounds(int total, int used) {

		public int left() {
			return Math.max(0, this.total - this.used);
		}

	}

	Optional<Rounds> rounds(UUID milestoneId);

	/**
	 * M5 by the Customer on the loaded version, for the Milestone's current handover; {@code payment}
	 * writes the release in the same transaction (AD-5).
	 * @throws vn.vibefix.shared.ApiException {@code STALE} ({@code STATE_CHANGED} or {@code
	 * HANDOVER_CHANGED}); {@code FORBIDDEN CUSTOMER_ONLY}; {@code CONFLICT JOB_FROZEN}
	 */
	Decided accept(UUID jobId, UUID milestoneId, UUID customerId, long expectedVersion, UUID handoverId);

	/**
	 * M5 by Auto-acceptance (FR-35) for the Milestone's current handover, applied by the System.
	 * @return what M5 recorded, or empty when the Milestone moved on, the handover is not its current
	 * one, or the Job is frozen; nothing is written then
	 */
	Optional<Decided> autoAccept(UUID jobId, UUID milestoneId, UUID handoverId);

	/**
	 * M4 by the Customer on the loaded version, naming {@code criteria} criteria.
	 * @throws vn.vibefix.shared.ApiException as {@link #accept}, and {@code GUARD_FAILED
	 * REVISION_ROUNDS_USED}
	 */
	Decided requestRevision(UUID jobId, UUID milestoneId, UUID customerId, long expectedVersion, UUID handoverId,
			int criteria);

}
