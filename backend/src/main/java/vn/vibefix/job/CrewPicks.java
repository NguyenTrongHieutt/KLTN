package vn.vibefix.job;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * The Crew's rows as {@code matching} changes them (FR-75, FR-81, AD-3, AD-4, AD-41, Story 21.5): the Mentor's pick
 * inserts an {@code ASSIGNED} row ({@link #addCrew}), an unsigned pick ends ({@link #releasePick}), and the picked
 * person's signature makes the row {@code ACTIVE} ({@link #activateCrew}). Each runs in the caller's transaction and
 * takes the Job row lock first, before {@code matching} locks its own rows (AD-4's order); the Source Repository's
 * contributors are read from GitHub before that transaction and passed in, never read under the lock (A-03). The Crew
 * cap lives only here (AD-30). No method removes a member who signed: only the member leaves (FR-75).
 */
public interface CrewPicks {

	/** What {@code matching} read of the Source Repository's contributors, before the transaction (A-03). */
	enum Contribution {

		/** The person never contributed to the Source Repository. */
		NONE,

		/** The person contributed to it: refused (FR-81). */
		CONTRIBUTOR,

		/** GitHub did not answer: refused, failing closed. */
		UNKNOWN

	}

	/** Why a pick not yet signed ends (R4). */
	enum PickEnd {

		/** The person declined, or the 3-day signature period passed. */
		DECLINED,

		/** The Mentor chose "Rút lựa chọn". */
		WITHDRAWN

	}

	/**
	 * A pick that committed.
	 * @param rowId the new {@code ASSIGNED} Crew row
	 * @param title the Job's title, for the person's notice
	 */
	record Pick(UUID rowId, UUID jobId, UUID mentorId, String title) {
	}

	/** One Milestone of the assignment projection, with its price; never a share. */
	record AssignmentMilestone(String title, @Nullable LocalDate dueOn, @Nullable Long priceVnd) {
	}

	/**
	 * The assignment projection a picked person reads before signing (AD-41, UX-DR103): the Job's title, its scope and
	 * Milestones with their prices, from the Agreement in force or, before the confirmation, the live draft; nothing
	 * else of the Job.
	 * @param rowId the person's {@code ASSIGNED} Crew row
	 * @param state the Job state's name
	 * @param scope the scope, absent before the Mentor drafted the Agreement
	 * @param codeJob a Code Job, whose signature needs N-23 (FR-18)
	 */
	record Assignment(UUID jobId, UUID rowId, String title, String state, @Nullable String scope, boolean codeJob,
			List<AssignmentMilestone> milestones) {

		public Assignment {
			milestones = List.copyOf(milestones);
		}

	}

	/**
	 * {@code job.addCrew} (AD-3, AD-4, AD-41, A-03, A-16): under the Job row lock, taken first in the caller's
	 * transaction, the refusals in order, then an {@code ASSIGNED} row naming {@code applicationId}, a new Crew version,
	 * and one place fewer while recruitment is on; at none left, recruitment turns off by itself
	 * ({@link CrewRecruitmentClosed}).
	 * @param contribution what the contributor read before the transaction said
	 * @throws org.springframework.transaction.IllegalTransactionStateException without a transaction
	 * @throws vn.vibefix.shared.ApiException {@code NOT_FOUND} for anyone but the Job's Mentor; {@code GUARD_FAILED}
	 * with {@code RECRUITMENT_CLOSED} outside Agreement, Starting and Active, {@code CREW_FULL} (with {@code n}, the
	 * cap), {@code APPLICANT_UNAVAILABLE} for a person on another running Crew or locked (never saying which),
	 * {@code CREW_REJOIN_REFUSED}, {@code ROLE_TAKEN}, {@code CONFLICT_OF_INTEREST} (Administrators are alerted),
	 * {@code APPLICANT_IS_CONTRIBUTOR} or {@code CONTRIBUTORS_UNKNOWN}
	 */
	Pick addCrew(UUID mentorId, UUID jobId, UUID applicationId, UUID accountId, Contribution contribution);

	/**
	 * {@code job.releasePick} (R4, AD-41): under the Job row lock, a pick not yet signed is released as {@code DECLINED}
	 * or {@code WITHDRAWN}, the person is free again, and a place returns while recruitment is on.
	 * @return {@code false} for a row released already (J8, a lock) or signed meanwhile, which changes nothing: nobody
	 * who signed is removed (FR-75)
	 * @throws vn.vibefix.shared.ApiException {@code NOT_FOUND} for a row of another Job
	 */
	boolean releasePick(UUID jobId, UUID rowId, PickEnd end);

	/**
	 * {@code job.activateCrew} (AD-41, AD-43, A-03, A-04): under the Job row lock, every refusal again, then the row is
	 * {@code ACTIVE} from {@code signedAt}; {@link CrewMemberAdded} follows, the Mentor and the active members hear
	 * {@code job.crew_member_joined}, and the Customer {@code job.crew_login_added} with the GitHub username only.
	 * @throws vn.vibefix.shared.ApiException {@code NOT_FOUND} for a row of another Job; {@code GUARD_FAILED} with
	 * {@code PICK_ENDED} for a released row or a Job that left Active (J8: the row stays released),
	 * {@code ROLE_TAKEN}, {@code CREW_REJOIN_REFUSED}, {@code APPLICANT_UNAVAILABLE}, {@code CONFLICT_OF_INTEREST},
	 * {@code APPLICANT_IS_CONTRIBUTOR} or {@code CONTRIBUTORS_UNKNOWN}
	 */
	void activateCrew(UUID jobId, UUID rowId, Contribution contribution, Instant signedAt);

	/** The assignment projection of the person's {@code ASSIGNED} row on the Job; empty without one. */
	Optional<Assignment> assignment(UUID jobId, UUID accountId);

}
