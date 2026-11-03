package vn.vibefix.job;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * The Crew as every module reads it (AD-30, AD-41): policies decide on {@link #crewRelationship}, and
 * nobody reads the Crew rows otherwise. A pick, its end and a signature go through {@link CrewPicks} (Story 21.5).
 */
public interface Crews {

	enum Relationship {
		NONE, ASSIGNED, ACTIVE, FORMER
	}

	/**
	 * What an account is to a Job's Crew (AD-41). {@code FORMER} carries the read window; a row never
	 * {@code ACTIVE} has none and reads as {@code NONE} once released. A lock counts as leaving, so a locked member
	 * is {@code FORMER} once {@code job}'s listener released the row (Story 20.4).
	 */
	record CrewRelationship(Relationship kind, @Nullable Instant signedAt, @Nullable Instant windowEnd) {

		public static final CrewRelationship NONE = new CrewRelationship(Relationship.NONE, null, null);

	}

	enum Selection {

		/** Rows that are not {@code RELEASED}. */
		UNRELEASED,

		/** Rows in status {@code ACTIVE}. */
		ACTIVE,

		/** Rows that were ever {@code ACTIVE} ({@code activated_at} set). */
		EVER_ACTIVE

	}

	/** One Crew row as other modules may see it: never a share. */
	record CrewMember(UUID rowId, UUID studentId, String status, @Nullable Instant signedAt,
			@Nullable Instant activatedAt, @Nullable Instant windowEnd) {
	}

	CrewRelationship crewRelationship(UUID jobId, UUID accountId);

	List<CrewMember> crewOf(UUID jobId, Selection selection);

	/**
	 * The Crew members a Job's Portfolio Entries credit (FR-50, AD-41, Story 21.3): each person whose active window
	 * (from {@code activated_at} to {@code window_end}, or still open) overlaps the work of at least one Accepted
	 * Milestone (from its funding to its acceptance or settlement), leavers included, in the order their rows were
	 * created.
	 */
	List<UUID> activeDuringAcceptedWork(UUID jobId);

	/**
	 * Whether the Job has a counted {@code ACTIVE} row (AD-41): one whose account is unlocked, read through
	 * {@code identity.lockedAmong}, because a lock commits before its listener releases the row. AD-39's no-Crew rule reads
	 * it (R8, X7).
	 */
	boolean hasCountedMember(UUID jobId);

	/** {@code job.runningCrewOf}: the Job where the Student has a row that is not {@code RELEASED}. */
	Optional<UUID> runningCrewOf(UUID studentId);

}
