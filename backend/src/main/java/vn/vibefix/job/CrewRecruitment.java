package vn.vibefix.job;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * The Mentor's Crew recruitment as {@code matching} reads it (AD-3, AD-41, Story 21.4): {@code job} owns the setting
 * (on or off, the open slots, the Skills sought and the note) and the Crew cap, so {@code matching} never counts Crew
 * rows itself. A Crew Application is sent only through {@link #requireRecruiting}, which takes the Job row lock first
 * (AD-4, review 2026-10-27 A-14): an application then commits before the Mentor, J8 or J10 can turn recruitment off,
 * and {@link CrewRecruitmentClosed} lapses it, or it is refused.
 */
public interface CrewRecruitment {

	/**
	 * A Job's recruitment as it stands.
	 * @param state the Job state's name, for example {@code AGREEMENT}
	 * @param hidden an Administrator hid the Job Post (FR-60)
	 * @param mentorId the Job's Mentor; absent before J3
	 * @param on recruitment is on
	 * @param openSlots the places the Mentor still seeks
	 * @param unreleased the Job's Crew rows that are not {@code RELEASED}
	 * @param cap the most unreleased rows a Crew holds: 5, or 1 with {@code features.largeCrews} off (AD-30)
	 * @param skillIds the Skills sought, in the Mentor's order
	 * @param skillNames their current names, in the same order
	 * @param signedMember at least one counted {@code ACTIVE} row: an unlocked member has signed (R7)
	 * @param crewVersion the Crew version every Crew command carries (AD-23), read with the rest; no Customer DTO holds it
	 */
	record Recruitment(UUID jobId, UUID productId, UUID customerId, @Nullable UUID mentorId, String title, String state,
			boolean hidden, boolean on, int openSlots, int unreleased, int cap, List<UUID> skillIds, List<String> skillNames,
			@Nullable String note, boolean signedMember, long crewVersion) {

		public Recruitment {
			skillIds = List.copyOf(skillIds);
			skillNames = List.copyOf(skillNames);
		}

		/** Recruitment is on in a state that takes Crew members: from J4 until J8 (AD-41). */
		public boolean recruiting() {
			return this.on && (this.state.equals("AGREEMENT") || this.state.equals("STARTING") || this.state.equals("ACTIVE"));
		}

		/** A place is still open and the Crew is under its cap. */
		public boolean freeSlot() {
			return this.openSlots > 0 && this.unreleased < this.cap;
		}

	}

	/** The Job's recruitment, read without a lock; empty for no such Job. */
	Optional<Recruitment> recruitment(UUID jobId);

	/**
	 * {@code job.requireRecruiting} (AD-3, AD-4, A-14): takes the Job row lock, first in the caller's transaction, and
	 * checks that recruitment is on with a free slot.
	 * @throws org.springframework.transaction.IllegalTransactionStateException without a transaction
	 * @throws vn.vibefix.shared.ApiException {@code NOT_FOUND} for no such Job; {@code GUARD_FAILED RECRUITMENT_CLOSED}
	 * while recruitment is off, the Job is hidden, or it left Active; {@code GUARD_FAILED CREW_FULL} (with {@code n},
	 * the cap) without a free slot
	 */
	Recruitment requireRecruiting(UUID jobId);

	/**
	 * Every Administrator learns that a Crew Application was refused for a shared person-link signal
	 * ({@code job.conflict_of_interest_refused}, AD-19), in a transaction of its own, never naming the signal.
	 */
	void conflictRefused();

	/** Why recruitment closed: what a lapsed Crew Application tells its applicant (R6). */
	enum ClosedBy {

		/** The Mentor turned "Nhận đơn xin vào nhóm" off. */
		MENTOR,

		/** J8: the Job entered Closing. */
		CLOSING,

		/** J10: the Job was cancelled. */
		CANCELLED

	}

}
