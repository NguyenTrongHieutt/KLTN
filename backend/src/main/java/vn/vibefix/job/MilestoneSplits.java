package vn.vibefix.job;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * The Milestone Splits as other modules read them (AD-15, AD-24, AD-29, AD-33). A split outlives its Job's data: these
 * ports read it after the Job is gone, and no transition, guard, or Payment Order reads a split. Only they may name a
 * Job whose Product was deleted.
 */
public interface MilestoneSplits {

	/** A split's status: {@code FINAL} never changes. */
	enum Status {
		AWAITING_DRAFT, PROPOSED, IN_RESOLUTION, FINAL
	}

	/**
	 * A party fixed at the release, never recomputed.
	 * @param accountId empty once an erasure anonymized the person
	 * @param wholeInterval active from the Milestone's funding to its release
	 * @param activeAtRelease still {@code ACTIVE} and unlocked at the release, which decides whether the cap applies
	 */
	record SplitParty(@Nullable UUID accountId, boolean mentor, boolean wholeInterval, boolean activeAtRelease) {
	}

	/**
	 * What SG-23's daily check reads of a split.
	 * @param latestDueAt its latest due time: the draft deadline until Story 22.2 adds the confirmation rounds
	 * @param finalSince when its final version was saved, once it is {@code FINAL}
	 */
	record SplitState(UUID splitId, UUID milestoneId, Status status, Instant latestDueAt, @Nullable Instant finalSince) {
	}

	/** Whether a split of the Job is not {@code FINAL}. */
	boolean hasOpenSplit(UUID jobId);

	/**
	 * The Product's Jobs with a split not {@code FINAL}, for a participant about to delete or retire that Product's
	 * data. It takes the Job rows {@code FOR SHARE} first and reads the splits in a statement of its own, so a
	 * finalization cannot slip between this read and the caller's deferral: one under way is waited for and seen, and a
	 * later one waits until the caller's transaction commits its deferral.
	 */
	Set<UUID> openSplitJobs(UUID productId);

	/** {@code job.splitPartiesOf}: the Milestone's parties, in their fixed order; empty without a split. */
	List<SplitParty> splitPartiesOf(UUID milestoneId);

	/** The Milestone's split, if a release created one. */
	Optional<SplitState> splitOf(UUID milestoneId);

	/** Every {@code FINAL} split whose final version was saved before {@code cutoff}. */
	List<SplitState> finalBefore(Instant cutoff);

}
