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

	/**
	 * A party as a Split Resolution reads them: the release fixed all of it, the interval included, so it outlives the
	 * Crew rows (AD-24, AD-40).
	 * @param accountId empty once an erasure anonymized the person
	 * @param activeFrom when they became active on the Crew, the Mentor's being the Milestone's funding; empty on a split
	 * older than Story 22.3 whose row had gone
	 * @param activeTo when that interval ended, at the release at the latest
	 */
	record CaseParty(UUID partyId, @Nullable UUID accountId, boolean mentor, boolean wholeInterval, boolean activeAtRelease,
			@Nullable Instant activeFrom, @Nullable Instant activeTo) {
	}

	/**
	 * A split as {@code dispute} reads it for a Split Resolution, from the split's own rows, so it works after J9, after J10
	 * and on a bare row.
	 * @param jobTitle the title while a row of the Job exists
	 * @param customerId the Job's Customer while a row names one; never a party
	 * @param mentorId the split's Mentor, empty once an erasure anonymized them
	 * @param latestVersionNo 0 before any version
	 * @param latestCap the cap stored on the latest version; empty when none applies (X5) or before any version
	 * @param confirmationDueAt the current round's due time
	 * @param pastDue {@code PROPOSED} and past that due time by the Clock (A-09)
	 * @param fundedAt when the Milestone was funded; empty on a split older than Story 22.3 whose Milestone had gone
	 */
	record SplitCase(UUID splitId, UUID jobId, UUID productId, UUID milestoneId, int milestoneNumber, @Nullable String jobTitle,
			@Nullable UUID customerId, @Nullable UUID mentorId, Status status, boolean capApplies, int latestVersionNo,
			@Nullable Integer latestCap, @Nullable Instant confirmationDueAt, boolean pastDue, @Nullable Instant fundedAt,
			Instant releasedAt, List<CaseParty> parties) {
	}

	/**
	 * What a withdrawal did (AD-40, A-08).
	 * @param status {@code PROPOSED} with a new round, or {@code FINAL} when the kept confirmations covered every party
	 * but the Mentor
	 * @param confirmationDueAt the new round's due time; empty when it became final
	 */
	record Resumed(Status status, @Nullable Instant confirmationDueAt) {
	}

	/** One party's line of a version as the Split Evidence Package shows it, with when they confirmed it. */
	record EvidenceLine(UUID partyId, int percent, @Nullable Instant confirmedAt) {
	}

	/**
	 * One version as the Split Evidence Package shows it.
	 * @param authorId who saved it, the System for a {@code DEFAULT} one; empty too once an erasure anonymized them
	 * @param capPercent the cap it was checked against
	 */
	record EvidenceVersion(int versionNo, VersionKind kind, @Nullable UUID authorId, @Nullable Integer capPercent,
			Instant createdAt, List<EvidenceLine> lines) {
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

	/** The split as a Split Resolution reads it, a bare row's included. */
	Optional<SplitCase> caseOf(UUID splitId);

	/**
	 * {@code job.lockForSplit} (AD-4, AD-40): the Job row lock, raw so a bare row still locks. It writes nothing and changes
	 * neither {@code Job.version} nor {@code crewVersion}. A Split Resolution's every write takes it first.
	 * @return the Milestone's split as it stands under that lock
	 */
	Optional<SplitCase> lockForSplit(UUID jobId, UUID milestoneId);

	/**
	 * {@code job.holdSplit}, under {@link #lockForSplit}: the split is {@code IN_RESOLUTION}, so nobody edits or confirms, the
	 * confirmation Deadline and its reminder end {@code OBSOLETE} by their own handlers, and {@code job.confirm_split}
	 * closes. The pool stays where it is. Its caller read the Clock under the lock and checked the due time: a due time passing
	 * since then does not undo that.
	 * @throws IllegalStateException unless the split is {@code PROPOSED}
	 */
	void holdSplit(UUID milestoneId, UUID resolutionId);

	/**
	 * {@code job.resumeSplit}, under {@link #lockForSplit} (AD-40, A-08): {@code PROPOSED} with a new round and a full period,
	 * the latest version keeping its confirmations and {@code job.confirm_split} reopening for those who have not given
	 * theirs; or, when those cover every party but the Mentor, the version is final at once.
	 * @throws IllegalStateException unless the split is {@code IN_RESOLUTION}
	 */
	Resumed resumeSplit(UUID milestoneId);

	/**
	 * The decision's percentages by AD-29's rules against the cap stored on the latest version, none under X5, without saving
	 * them: for the Administrator's preview and the check before the step-up.
	 * @throws vn.vibefix.shared.ApiException {@code VALIDATION SPLIT_INVALID}
	 */
	PreviewBasis decisionBasis(UUID splitId, List<PercentLine> lines);

	/**
	 * {@code job.decideSplit}, under {@link #lockForSplit} (AD-29, AD-40): saves the {@code DECISION} version as the
	 * Administrator's, checked against the cap stored on the latest version, and makes it final, which publishes
	 * {@link MilestoneSplitFinalized}. A refusal changes nothing.
	 * @return the {@code DECISION} version's number
	 * @throws vn.vibefix.shared.ApiException {@code VALIDATION SPLIT_INVALID}
	 * @throws IllegalStateException unless the split is {@code IN_RESOLUTION}
	 */
	int decideSplit(UUID milestoneId, UUID resolutionId, UUID adminId, List<PercentLine> lines);

	/**
	 * Every version of the split, oldest first, with who saved it and who confirmed it when, for the identifiers a decision
	 * record keeps. Not an Administrator's read: {@link #evidenceUnderGrant} is that.
	 */
	List<EvidenceVersion> versionsOf(UUID splitId);

	/**
	 * {@link #versionsOf} for an Administrator, under a grant naming this split, and audited {@code job.admin_read} (AD-8,
	 * AD-20).
	 * @throws vn.vibefix.shared.ApiException {@code NOT_FOUND} unless an open grant of theirs covers the split's versions
	 */
	List<EvidenceVersion> evidenceUnderGrant(UUID adminId, UUID splitId);

	/**
	 * Whether the Job's own content, which a Split Resolution's evidence reads, still stands (AD-24): its row is there, its
	 * Product was not deleted, and no retention run retired it. A split not {@code FINAL} keeps its evidence either way; once
	 * the split is final, what waited for it goes.
	 */
	boolean jobContentKept(UUID jobId);

	/**
	 * The Milestone's final split as {@link MilestoneSplitFinalized} carried it, for FR-64's retry of a refused one
	 * ({@code payment.split_pool_refused}), with a new event ID; empty unless it is {@code FINAL} with its Mentor named.
	 */
	Optional<MilestoneSplitFinalized> finalSplitOf(UUID milestoneId);

}
