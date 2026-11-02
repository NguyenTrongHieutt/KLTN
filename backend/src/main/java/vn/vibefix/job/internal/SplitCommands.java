package vn.vibefix.job.internal;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.job.JobRules;
import vn.vibefix.job.MilestoneSplitFinalized.Finality;
import vn.vibefix.job.MilestoneSplits.PercentLine;
import vn.vibefix.job.MilestoneSplits.Resumed;
import vn.vibefix.job.MilestoneSplits.Status;
import vn.vibefix.job.MilestoneSplits.VersionKind;
import vn.vibefix.job.internal.SplitStore.Party;
import vn.vibefix.job.internal.SplitStore.Split;
import vn.vibefix.job.internal.SplitStore.Version;
import vn.vibefix.settings.Rule;
import vn.vibefix.settings.Settings;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.timers.DeadlineRequest;
import vn.vibefix.timers.Deadlines;

/**
 * Drafting, confirming, defaulting and the deadlines of a Milestone Split (FR-83, AD-7, AD-29). Every write takes the
 * Job row, raw so a bare row still locks, then reads the split again; none changes {@code Job.version} or
 * {@code crewVersion}, none goes through {@code JobLifecycle}, and none reads the freeze or the busy marker, so a Dispute
 * or a merge never pauses a split, and it carries on after J9, J10, or a Product's deletion. A locked party cannot act,
 * as the controller's gate refuses them, while the Deadlines still run. A Split Resolution, {@code dispute}'s, holds,
 * resumes and decides a split through these commands, under the Job row lock it took first (AD-40).
 */
@Component
class SplitCommands {

	/** What a save answers. */
	record Saved(int versionNo, Status status, Instant confirmationDueAt) {
	}

	/** What a confirmation answers. */
	record Confirmed(int versionNo, Status status) {
	}

	static final String CONFIRMATION_RULE = "SPLIT_CONFIRMATION_DAYS";

	static final int DEFAULT_CONFIRMATION_DAYS = 3;

	private static final Duration REMINDER_BEFORE = Duration.ofDays(1);

	private final SplitStore store;

	private final SplitFinalization finalization;

	private final SplitNotices notices;

	private final JobRules rules;

	private final Settings settings;

	private final Deadlines deadlines;

	private final AuditLog auditLog;

	private final Clock clock;

	SplitCommands(SplitStore store, SplitFinalization finalization, SplitNotices notices, JobRules rules, Settings settings,
			Deadlines deadlines, AuditLog auditLog, Clock clock) {
		this.store = store;
		this.finalization = finalization;
		this.notices = notices;
		this.rules = rules;
		this.settings = settings;
		this.deadlines = deadlines;
		this.auditLog = auditLog;
		this.clock = clock;
	}

	/**
	 * The Mentor saves the next version, the first draft or an edit: it goes out for a new round of confirmations.
	 * @param shownVersionNo the latest version the screen showed, 0 for none
	 * @throws ApiException {@code NOT_FOUND} for a non-party; {@code FORBIDDEN MENTOR_ONLY} for another party;
	 * {@code GUARD_FAILED} with {@code SPLIT_FINAL}, also past the due time (A-09), {@code SPLIT_IN_RESOLUTION},
	 * {@code SPLIT_DRAFT_LATE}, also for an edit of the default, or {@code SPLIT_EDIT_LIMIT}; {@code STALE} on an old
	 * version; {@code VALIDATION SPLIT_INVALID}
	 */
	@Transactional
	Saved save(UUID mentorId, UUID splitId, int shownVersionNo, List<PercentLine> lines) {
		Split split = lockedSplit(splitId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		List<Party> parties = this.store.parties(splitId);
		requireMentor(split, parties, mentorId);
		Instant now = this.clock.instant();
		refuseFinished(split, now);
		List<Version> versions = this.store.versions(splitId);
		Version latest = versions.stream().findFirst().orElse(null);
		if (latest == null ? !now.isBefore(split.draftDueAt()) : latest.kind() != VersionKind.DRAFT) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.SPLIT_DRAFT_LATE);
		}
		int latestNo = latest == null ? 0 : latest.number();
		if (shownVersionNo != latestNo) {
			throw new ApiException(ErrorCode.STALE);
		}
		int limit = this.settings.value(Rule.SPLIT_EDIT_LIMIT);
		if (latest != null && this.store.editsUsed(versions) >= limit) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.SPLIT_EDIT_LIMIT, Map.of("n", limit));
		}
		Integer cap = capOf(split);
		Map<UUID, Integer> percents = validated(split, parties, lines, cap);
		int versionNo = latestNo + 1;
		this.store.insertVersion(splitId, versionNo, VersionKind.DRAFT, cap, mentorId, percents, now);
		Instant due = startRound(split, versionNo, now);
		String title = this.store.title(split.jobId()).orElse("");
		this.notices.askToConfirm(split, title, parties, due);
		this.notices.sent(latest == null ? SplitNotices.PROPOSED : SplitNotices.EDITED, split, versionNo, title, parties, due);
		audit(AuditActor.user(mentorId), latest == null ? "job.split_draft_saved" : "job.split_edit_saved", split);
		return new Saved(versionNo, Status.PROPOSED, due);
	}

	/**
	 * A party other than the Mentor confirms the latest version, leavers and parties at 0% included. A repeat changes
	 * nothing. When every such party has confirmed it, the version is final.
	 * @throws ApiException {@code NOT_FOUND} for a non-party or before any version; {@code FORBIDDEN PARTY_ONLY} for the
	 * Mentor; {@code GUARD_FAILED} with {@code SPLIT_FINAL}, also past the due time (A-09), or
	 * {@code SPLIT_IN_RESOLUTION}; {@code STALE} on an old version
	 */
	@Transactional
	Confirmed confirm(UUID accountId, UUID splitId, int versionNo) {
		Split split = lockedSplit(splitId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		List<Party> parties = this.store.parties(splitId);
		Party party = parties.stream()
			.filter(candidate -> accountId.equals(candidate.accountId()))
			.findFirst()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (party.mentor()) {
			throw new ApiException(ErrorCode.FORBIDDEN, JobReason.PARTY_ONLY);
		}
		if (split.status() == Status.AWAITING_DRAFT) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		Instant now = this.clock.instant();
		refuseFinished(split, now);
		int latest = this.store.latest(splitId).map(Version::number).orElse(0);
		if (versionNo != latest) {
			throw new ApiException(ErrorCode.STALE);
		}
		if (!this.store.confirm(splitId, versionNo, party.id(), now)) {
			return new Confirmed(versionNo, split.status());
		}
		audit(AuditActor.user(accountId), "job.split_confirmed", split);
		this.notices.confirmed(split, versionNo, party);
		Set<UUID> confirmed = new HashSet<>(this.store.confirmers(splitId, versionNo));
		boolean everyone = parties.stream()
			.filter(candidate -> !candidate.mentor())
			.allMatch(candidate -> confirmed.contains(candidate.id()));
		if (everyone) {
			this.finalization.makeFinal(split, versionNo, Finality.CONFIRMED);
			return new Confirmed(versionNo, Status.FINAL);
		}
		return new Confirmed(versionNo, split.status());
	}

	/**
	 * The draft Deadline fired (R11, X4): with no draft saved, the System saves the {@code DEFAULT} version and a full
	 * round starts from now, however late the poller ran. The Mentor cannot edit it.
	 * @return {@code false} when the split moved on, so the Deadline is {@code OBSOLETE}
	 */
	@Transactional
	boolean saveDefault(UUID splitId) {
		Split split = lockedSplit(splitId).orElse(null);
		if (split == null || split.status() != Status.AWAITING_DRAFT) {
			return false;
		}
		Instant now = this.clock.instant();
		List<Party> parties = this.store.parties(splitId);
		Integer cap = capOf(split);
		this.store.insertVersion(splitId, 1, VersionKind.DEFAULT, cap, null, defaultPercents(parties, cap), now);
		Instant due = startRound(split, 1, now);
		String title = this.store.title(split.jobId()).orElse("");
		this.notices.askToConfirm(split, title, parties, due);
		this.notices.defaulted(split, 1, title, parties, due);
		audit(AuditActor.system(), "job.split_draft_expired", split);
		audit(AuditActor.system(), "job.split_default_saved", split);
		return true;
	}

	/**
	 * R11's default: the Mentor gets ⌊100 / parties⌋, lowered to the cap when one applies; the rest goes equally over the
	 * other parties, the leftover points one each in ascending position.
	 */
	static Map<UUID, Integer> defaultPercents(List<Party> parties, @Nullable Integer cap) {
		int mentor = 100 / parties.size();
		if (cap != null) {
			mentor = Math.min(mentor, cap);
		}
		int members = parties.size() - 1;
		int each = members == 0 ? 0 : (100 - mentor) / members;
		int leftover = members == 0 ? 0 : (100 - mentor) % members;
		Map<UUID, Integer> percents = new LinkedHashMap<>();
		for (Party party : parties) {
			if (party.mentor()) {
				percents.put(party.id(), mentor);
				continue;
			}
			percents.put(party.id(), each + (leftover-- > 0 ? 1 : 0));
		}
		return percents;
	}

	/**
	 * The confirmation Deadline fired: the version it served becomes final however late the poller ran (A-09). An open
	 * Split Resolution or a newer round leaves it {@code OBSOLETE}.
	 * @param generation the {@code {versionNo}:{round}} it serves
	 * @return {@code false} when it is obsolete
	 */
	@Transactional
	boolean finalizeAtDeadline(UUID splitId, String generation) {
		Split split = lockedSplit(splitId).orElse(null);
		if (split == null || split.status() != Status.PROPOSED) {
			return false;
		}
		int latest = this.store.latest(splitId).map(Version::number).orElse(0);
		if (!generation.equals(generation(latest, split.round()))) {
			return false;
		}
		audit(AuditActor.system(), "job.split_confirmation_expired", split);
		this.finalization.makeFinal(split, latest, Finality.DEADLINE);
		return true;
	}

	/**
	 * @return {@code false} once a draft exists, or at or past the due time when a late poller finally reaches it, since
	 * "còn 1 ngày" would be false and the save refused, so the reminder is {@code OBSOLETE}
	 */
	@Transactional
	boolean remindDraft(UUID splitId, UUID deadlineId) {
		Split split = lockedSplit(splitId).orElse(null);
		if (split == null || split.status() != Status.AWAITING_DRAFT || !this.clock.instant().isBefore(split.draftDueAt())) {
			return false;
		}
		this.notices.draftReminder(split, deadlineId);
		return true;
	}

	/**
	 * @return {@code false} once the round it serves moved on, or at or past its due time when a late poller finally
	 * reaches it, since the confirmation would be refused, so the reminder is {@code OBSOLETE}
	 */
	@Transactional
	boolean remindConfirmation(UUID splitId, String generation, UUID deadlineId) {
		Split split = lockedSplit(splitId).orElse(null);
		if (split == null || split.status() != Status.PROPOSED || split.pastDue(this.clock.instant())) {
			return false;
		}
		int latest = this.store.latest(splitId).map(Version::number).orElse(0);
		if (!generation.equals(generation(latest, split.round()))) {
			return false;
		}
		Set<UUID> confirmed = new HashSet<>(this.store.confirmers(splitId, latest));
		this.notices.confirmationReminder(split, deadlineId,
				this.store.parties(splitId).stream().filter(party -> !confirmed.contains(party.id())).toList());
		return true;
	}

	/**
	 * {@code job.holdSplit}: a Split Resolution was raised, under the lock its caller took. Nothing else moves: the round's
	 * Deadline and reminder find the status changed and end {@code OBSOLETE}.
	 */
	@Transactional
	void hold(UUID milestoneId, UUID resolutionId) {
		Split split = ofMilestone(milestoneId);
		if (split.status() != Status.PROPOSED || split.pastDue(this.clock.instant())) {
			throw new IllegalStateException("Only a split out for confirmation and in time is held");
		}
		this.store.hold(split.id());
		this.notices.held(split);
		this.auditLog.record(AuditEntry
			.of(AuditActor.system(), "job.split_held", AuditObject.of("job.milestone_split", split.id()),
					AuditObject.of("dispute.split_resolution", resolutionId))
			.forJob(split.jobId()));
	}

	/**
	 * {@code job.resumeSplit}: a withdrawal, under the lock its caller took (A-08). The latest version keeps its confirmations:
	 * when they cover every party but the Mentor it is final at once, and otherwise a new full round starts for the same
	 * version, with those who have not confirmed asked again.
	 */
	@Transactional
	Resumed resume(UUID milestoneId) {
		Split split = ofMilestone(milestoneId);
		if (split.status() != Status.IN_RESOLUTION) {
			throw new IllegalStateException("Only a split under a resolution resumes");
		}
		int versionNo = this.store.latest(split.id()).map(Version::number).orElseThrow();
		Set<UUID> confirmed = new HashSet<>(this.store.confirmers(split.id(), versionNo));
		List<Party> waiting = this.store.parties(split.id())
			.stream()
			.filter(party -> !party.mentor() && !confirmed.contains(party.id()))
			.toList();
		audit(AuditActor.system(), "job.split_resumed", split);
		if (waiting.isEmpty()) {
			this.finalization.makeFinal(split, versionNo, Finality.CONFIRMED);
			return new Resumed(Status.FINAL, null);
		}
		Instant due = startRound(split, versionNo, this.clock.instant());
		this.notices.askAgain(split, this.store.title(split.jobId()).orElse(""), waiting, due);
		return new Resumed(Status.PROPOSED, due);
	}

	/**
	 * {@code job.decideSplit}: the assigned Administrator's decision, under the lock its caller took. The {@code DECISION}
	 * version is checked against the cap stored on the latest version, stores that cap, and is final when saved.
	 * @throws ApiException {@code VALIDATION SPLIT_INVALID}, which changes nothing
	 */
	@Transactional
	int decide(UUID milestoneId, UUID resolutionId, UUID adminId, List<PercentLine> lines) {
		Split split = ofMilestone(milestoneId);
		if (split.status() != Status.IN_RESOLUTION) {
			throw new IllegalStateException("Only a split under a resolution is decided");
		}
		Version latest = this.store.latest(split.id()).orElseThrow();
		Map<UUID, Integer> percents = validated(split, this.store.parties(split.id()), lines, latest.capPercent());
		int versionNo = latest.number() + 1;
		this.store.insertVersion(split.id(), versionNo, VersionKind.DECISION, latest.capPercent(), adminId, percents,
				this.clock.instant());
		this.auditLog.record(AuditEntry
			.of(AuditActor.user(adminId), "job.split_decision_saved", AuditObject.of("job.milestone_split", split.id()),
					AuditObject.of("dispute.split_resolution", resolutionId))
			.forJob(split.jobId()));
		this.finalization.makeFinal(split, versionNo, Finality.DECISION);
		return versionNo;
	}

	/**
	 * Checks a draft by AD-29's rules against {@code cap}: read live for a save, stored on the latest version for a decision.
	 * @throws ApiException {@code VALIDATION SPLIT_INVALID} unless the parties are exactly the split's, the percentages
	 * whole and summing to 100, the Mentor's within the cap when it applies, and, when it does, each member of the whole
	 * Milestone above 0
	 */
	Map<UUID, Integer> validated(Split split, List<Party> parties, List<PercentLine> lines, @Nullable Integer cap) {
		Map<UUID, Integer> typed = new HashMap<>();
		for (PercentLine line : lines) {
			if (typed.put(line.partyId(), whole(line.percent())) != null) {
				throw invalid(cap);
			}
		}
		Set<UUID> expected = new HashSet<>(parties.stream().map(Party::id).toList());
		if (!typed.keySet().equals(expected) || typed.values().stream().mapToInt(Integer::intValue).sum() != 100) {
			throw invalid(cap);
		}
		Map<UUID, Integer> percents = new LinkedHashMap<>();
		for (Party party : parties) {
			int percent = Objects.requireNonNull(typed.get(party.id()));
			boolean tooMuch = party.mentor() && cap != null && percent > cap;
			boolean tooLittle = !party.mentor() && split.capApplies() && party.wholeInterval() && percent == 0;
			if (percent < 0 || percent > 100 || tooMuch || tooLittle) {
				throw invalid(cap);
			}
			percents.put(party.id(), percent);
		}
		return percents;
	}

	/** The cap a save is held to now: read live, and none under X5. */
	@Nullable Integer capOf(Split split) {
		return split.capApplies() ? this.settings.value(Rule.MENTOR_SHARE_CAP_PERCENT) : null;
	}

	/** A confirmation Deadline's generation: the version it serves and its round. */
	static String generation(int versionNo, int round) {
		return versionNo + ":" + round;
	}

	/** A new whole round for the version: {@code PROPOSED}, the Job's copied days, and its Deadline and reminder. */
	private Instant startRound(Split split, int versionNo, Instant now) {
		int round = split.round() + 1;
		int days = this.rules.value(split.jobId(), CONFIRMATION_RULE, DEFAULT_CONFIRMATION_DAYS);
		Instant due = now.plus(Duration.ofDays(days));
		this.store.startRound(split.id(), round, due);
		String generation = generation(versionNo, round);
		this.deadlines.schedule(new DeadlineRequest(SplitConfirmationDeadline.KEY, split.id(), generation, split.jobId(), due));
		if (days > REMINDER_BEFORE.toDays()) {
			this.deadlines.schedule(new DeadlineRequest(SplitConfirmationReminder.KEY, split.id(), generation, split.jobId(),
					due.minus(REMINDER_BEFORE)));
		}
		return due;
	}

	private Split ofMilestone(UUID milestoneId) {
		return this.store.ofMilestone(milestoneId)
			.orElseThrow(() -> new IllegalStateException("The Milestone has no split: " + milestoneId));
	}

	/** The Job row, then the split as it stands under that lock (AD-4). */
	private Optional<Split> lockedSplit(UUID splitId) {
		Split found = this.store.find(splitId).orElse(null);
		if (found == null) {
			return Optional.empty();
		}
		this.store.lockJobRow(found.jobId());
		return this.store.find(splitId);
	}

	private static void requireMentor(Split split, List<Party> parties, UUID accountId) {
		if (parties.stream().noneMatch(party -> accountId.equals(party.accountId()))) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		if (!accountId.equals(split.mentorId())) {
			throw new ApiException(ErrorCode.FORBIDDEN, JobReason.MENTOR_ONLY);
		}
	}

	/** Final, under a resolution, or past its due time, where only the Deadline moves the split on. */
	private static void refuseFinished(Split split, Instant now) {
		if (split.status() == Status.FINAL) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.SPLIT_FINAL);
		}
		if (split.status() == Status.IN_RESOLUTION) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.SPLIT_IN_RESOLUTION);
		}
		if (split.pastDue(now)) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.SPLIT_FINAL, Map.of("pastDue", true));
		}
	}

	private void audit(AuditActor actor, String action, Split split) {
		this.auditLog.record(AuditEntry.of(actor, action, AuditObject.of("job.milestone_split", split.id())).forJob(split.jobId()));
	}

	/** The percentage as a whole number, whichever way the JSON wrote it; a fraction or an absurd number has none. */
	private static int whole(BigDecimal percent) {
		try {
			return percent.intValueExact();
		}
		catch (ArithmeticException notWhole) {
			return -1;
		}
	}

	private static ApiException invalid(@Nullable Integer cap) {
		return new ApiException(ErrorCode.VALIDATION, JobReason.SPLIT_INVALID, cap == null ? Map.of() : Map.of("cap", cap));
	}

}
