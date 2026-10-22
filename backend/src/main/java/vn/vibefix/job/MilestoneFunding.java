package vn.vibefix.job;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import vn.vibefix.shared.ApiException;

/**
 * What {@code payment} needs from {@code job} to fund a Milestone (FR-41, AD-4, AD-14): the Job and its
 * Milestones, why one cannot be funded now, and M1 with its cascade, applied without throwing.
 */
public interface MilestoneFunding {

	/** One Milestone as funding sees it. */
	record FundingMilestone(UUID milestoneId, int number, String title, long priceVnd, String state) {
	}

	/**
	 * A Job whose Agreement is confirmed.
	 * @param state the Job's state name
	 * @param frozen an open Dispute freezes it (FR-52)
	 */
	record FundingJob(UUID jobId, UUID productId, UUID customerId, @Nullable UUID mentorId, String title, String state,
			long version, boolean frozen, int agreementVersionNo, List<FundingMilestone> milestones) {
	}

	/** What M1 recorded: the Milestone's number and the Crew Terms version it pinned (AD-41). */
	record Funded(UUID transitionId, int number, int crewTermsVersionNo) {
	}

	/** The Customer's Job once its Agreement is confirmed; anyone else's, or before, is empty. */
	Optional<FundingJob> forCustomer(UUID customerId, UUID jobId);

	/** The Job once its Agreement is confirmed. */
	Optional<FundingJob> byId(UUID jobId);

	/** The Mentor's Jobs with a confirmed Agreement, newest first (MEN-7's held money). */
	List<FundingJob> ofMentor(UUID mentorId);

	/**
	 * Why the Milestone cannot be funded now, read without a lock: M1's guards and the freeze, and for
	 * Milestone 1 before J6 the Agreement and Crew guards of J6 (FR-17, FR-75).
	 * @return the refusal, or {@code null} when funding it now would apply
	 */
	@Nullable ApiException refusal(UUID jobId, UUID milestoneId);

	/**
	 * M1 with its cascade (J6 first for Milestone 1, then J7 for an Assessment Job), checked in full
	 * before the first write; a refusal writes nothing and leaves the caller's transaction usable.
	 * @param paymentOrderId the order whose verified payment this applies, recorded on the event
	 * @return what M1 recorded, or empty when a guard, the freeze, or a state refused it
	 */
	Optional<Funded> tryFund(UUID jobId, UUID milestoneId, UUID paymentOrderId);

	/** The difference a Change Request waits for on a Held Milestone before it takes effect (FR-36). */
	record TopUp(UUID changeRequestId, UUID milestoneId, int number, long amountVnd) {
	}

	/** The Job's Change Request waiting for its top-up, if any. */
	Optional<TopUp> topUpOf(UUID jobId);

	/**
	 * Why a top-up cannot be paid now, read without a lock: no request waits, or the freeze (FR-52).
	 * @return the refusal, or {@code null}
	 */
	@Nullable ApiException topUpRefusal(UUID jobId);

	/**
	 * Applies the waiting Change Request after its top-up's verified payment, in the caller's transaction;
	 * a refusal writes nothing and leaves it usable.
	 * @return the top-up applied, or empty when the request no longer waits or cannot apply now
	 */
	Optional<TopUp> tryTopUp(UUID jobId, UUID changeRequestId, UUID paymentOrderId);

}
