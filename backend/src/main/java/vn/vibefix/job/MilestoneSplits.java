package vn.vibefix.job;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import vn.vibefix.job.MilestoneSplitFinalized.Finality;

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

	/** Who saved a version: the Mentor, the draft Deadline, an Administrator, or the release, with the Mentor alone. */
	enum VersionKind {
		DRAFT, DEFAULT, DECISION, MENTOR_ONLY
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
	 * What SG-23's daily check and a gone Job's Balance lines read of a split.
	 * @param milestoneNumber the Milestone's number when the split was created
	 * @param mentorId the split's Mentor, empty once an erasure anonymized them
	 * @param latestDueAt its latest due time: the later of the draft deadline and its current confirmation round's
	 * @param finalSince when it became {@code FINAL}
	 */
	record SplitState(UUID splitId, UUID milestoneId, int milestoneNumber, @Nullable UUID mentorId, Status status,
			Instant latestDueAt, @Nullable Instant finalSince) {
	}

	/**
	 * FR-61's split rules for a Job (UX-DR110): what a picked person accepts by signing and what each save is held to, in
	 * one definition so they match.
	 * @param mentorShareCapPercent read live; each version stores the cap it was checked against
	 * @param draftDays the Job's copy, taken when it was published
	 * @param confirmationDays the Job's copy, taken when it was published
	 * @param editLimit read live: the versions the Mentor may save after the first
	 */
	record SplitRules(int mentorShareCapPercent, int draftDays, int confirmationDays, int editLimit) {
	}

	/**
	 * One party's percentage as the Mentor typed it: a number, since whether it is whole is {@code job}'s to answer
	 * ({@code SPLIT_INVALID}), and an {@code int} would quietly drop the fraction.
	 */
	record PercentLine(UUID partyId, BigDecimal percent) {
	}

	/**
	 * A valid draft, for {@code payment}'s server-side preview through {@code shared.CrewSplit} (AD-33).
	 * @param mentorPartyId who receives the remainder of the whole-VND split
	 * @param percents every party's whole percentage by party, in the fixed order, a party at 0 included
	 */
	record PreviewBasis(UUID milestoneId, UUID mentorPartyId, Map<UUID, Integer> percents) {
	}

	/**
	 * A party as a reader of the split sees them.
	 * @param accountId empty once an erasure anonymized the person
	 * @param leftAt when they left the Crew, a lock included, while their Crew row still exists
	 */
	record PartyRead(UUID partyId, @Nullable UUID accountId, boolean mentor, boolean wholeInterval, boolean activeAtRelease,
			@Nullable Instant leftAt) {
	}

	/**
	 * One party's line of a version.
	 * @param confirmed the party confirmed this version; the Mentor never has a confirmation
	 */
	record LineRead(UUID partyId, int percent, boolean confirmed) {
	}

	/**
	 * A version, immutable, with every party's line (X11).
	 * @param capPercent the cap it was checked against; empty when no cap applied
	 */
	record VersionRead(int versionNo, VersionKind kind, @Nullable Integer capPercent, Instant createdAt, List<LineRead> lines) {
	}

	/**
	 * A split as one of its parties reads it (AD-20, X11), newest Milestone first.
	 * @param jobTitle the Job's title while a row of the Job exists, a bare one included
	 * @param jobLive the Job still has its screens: its row exists and its Product was not deleted
	 * @param cap the cap a save is held to now; empty when none applies (X5)
	 * @param editsUsed the Mentor's versions after the first
	 * @param editsLeft of {@code editLimit}, read live, so never below 0 when an Administrator lowers it
	 * @param draftNear the draft deadline is within 48 h
	 * @param draftLate the draft deadline has passed by the Clock and no draft exists: the default is on its way
	 * @param nextConfirmationDueAt when a round started now would be due, by the Job's copied days: N-29's date, so the
	 * screen computes no date
	 * @param confirmationDueAt the current round's due time, once a version was sent
	 * @param confirmationNear that due time is within 48 h
	 * @param pastDue {@code PROPOSED} and past that due time: A-09's "being finalized" state
	 * @param confirmed the parties other than the Mentor who confirmed the latest version
	 * @param confirmers the parties other than the Mentor
	 * @param viewerIsMentor the reader is this split's Mentor
	 * @param viewerConfirmed the reader confirmed the latest version
	 * @param latest the latest version, once one exists
	 * @param earlier the versions before it, newest first
	 */
	record SplitRead(UUID splitId, UUID milestoneId, int milestoneNumber, @Nullable String jobTitle, boolean jobLive,
			Status status, @Nullable Finality finality, boolean capApplies, @Nullable Integer cap, int editsUsed, int editsLeft,
			int editLimit,
			Instant draftDueAt, boolean draftNear, boolean draftLate, Instant nextConfirmationDueAt,
			@Nullable Instant confirmationDueAt, boolean confirmationNear, boolean pastDue, @Nullable Instant finalAt,
			int confirmed, int confirmers, boolean viewerIsMentor, boolean viewerConfirmed, List<PartyRead> parties, @Nullable VersionRead latest,
			List<VersionRead> earlier) {
	}

	/** Whether a split of the Job is not {@code FINAL}. */
	boolean hasOpenSplit(UUID jobId);

	/**
	 * The Product's Jobs with a split not {@code FINAL}, for a participant about to delete or retire that Product's
	 * data. It takes the Job rows {@code FOR UPDATE} first, in ID order, and reads the splits in a statement of its own,
	 * so a finalization cannot slip between this read and the caller's deferral: one under way is waited for and seen,
	 * and a later one waits until the caller's transaction commits its deferral, then finds the bare row. A deletion
	 * never upgrades a lock behind a queued finalization this way.
	 */
	Set<UUID> openSplitJobs(UUID productId);

	/** {@code job.splitPartiesOf}: the Milestone's parties, in their fixed order; empty without a split. */
	List<SplitParty> splitPartiesOf(UUID milestoneId);

	/** The Milestone's split, if a release created one. */
	Optional<SplitState> splitOf(UUID milestoneId);

	/** Every {@code FINAL} split that became final before {@code cutoff}. */
	List<SplitState> finalBefore(Instant cutoff);

	/** The Job's split rules: the Commitment panel's figures and those a save is held to. */
	SplitRules rulesOf(UUID jobId);

	/**
	 * The Job's splits {@code viewerId} is a party of, from the party rows and never the role in the Job, so a former
	 * member keeps them after the Job ends, and a final or open split stays readable after the Job is gone. The Job's
	 * Mentor with no split yet gets an empty list.
	 * @throws vn.vibefix.shared.ApiException {@code NOT_FOUND} for anyone else, the Job's Customer always
	 */
	List<SplitRead> readableBy(UUID jobId, UUID viewerId);

	/**
	 * Checks a draft by AD-29's rules without saving it, for the split's Mentor.
	 * @throws vn.vibefix.shared.ApiException {@code NOT_FOUND} for a non-party, {@code FORBIDDEN MENTOR_ONLY} for another
	 * party, {@code VALIDATION SPLIT_INVALID} for an invalid draft
	 */
	PreviewBasis previewBasis(UUID splitId, UUID mentorId, List<PercentLine> lines);

}
