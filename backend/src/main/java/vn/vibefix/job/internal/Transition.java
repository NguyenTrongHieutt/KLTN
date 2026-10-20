package vn.vibefix.job.internal;

import static vn.vibefix.job.internal.Trigger.AUTO;
import static vn.vibefix.job.internal.Trigger.DECISION;
import static vn.vibefix.job.internal.Trigger.SYSTEM;
import static vn.vibefix.job.internal.Trigger.USER;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The transition table (PRD section 4.0, AD-4): every way a Job or a Milestone changes state, and no
 * other. Each entry names its from and to states, its triggers, its guards tagged with the FR that
 * adds them, and its effects with the module that executes each. {@link JobLifecycle} is the only
 * code that changes a state, and only through an entry whose command exists ({@link #reachable()});
 * the others arrive with their stories. {@code TransitionTableTest} holds the table to section 4.0.
 */
enum Transition {

	J1(Machine.JOB, job(JobState.DRAFT), job(JobState.OPEN), Set.of(USER), "The Customer publishes (FR-9)",
			List.of(new Guard("FR-9", "The guided answers, a Job Type, the budget, and a future deadline are given"),
					new Guard("FR-8", "A Code Job needs no open Intake Finding; an Assessment Job may have open Findings")),
			List.of(new Effect("job", "The FR-61 rule values and the selection period are copied onto the Job (AD-7)"),
					new Effect("timers", "The selection lapse is scheduled (FR-9)")),
			false, true),

	J2(Machine.JOB, job(JobState.OPEN), job(JobState.DRAFT), Set.of(USER, AUTO),
			"The Customer withdraws it, or the FR-9 selection period lapses", List.of(),
			List.of(new Effect("matching", "Open Applications close"),
					new Effect("notification", "A lapse tells the Customer (job.job_post_lapsed)")),
			false, true),

	J3(Machine.JOB, job(JobState.OPEN), job(JobState.SELECTING), Set.of(USER),
			"The Customer selects an Application (FR-15)",
			List.of(new Guard("FR-74", "The Mentor is still approved"),
					new Guard("FR-13", "The Mentor is below the active-Job limit")),
			List.of(new Effect("matching", "Other Applications are put on hold"),
					new Effect("product", "The Product is pinned while the Job runs (AD-30)"),
					new Effect("notification", "The selected Mentor is told (job.assignee_selected)")),
			false, true),

	J4(Machine.JOB, job(JobState.SELECTING), job(JobState.AGREEMENT), Set.of(USER),
			"The selected Mentor has signed the NDA (FR-15)",
			List.of(new Guard("FR-15", "The selected Mentor signed the NDA")),
			List.of(new Effect("matching", "Applications on hold close"), new Effect("chat", "Job Chat opens"),
					new Effect("job", "Drafting and Crew selection start (FR-16, FR-75)")),
			false, true),

	J5(Machine.JOB, job(JobState.SELECTING), job(JobState.OPEN), Set.of(USER),
			"The selected Mentor declines, or the Customer cancels the selection after the FR-15 signature period",
			List.of(), List.of(new Effect("matching", "Applications on hold reopen"),
					new Effect("timers", "The FR-9 selection period of J2 restarts"),
					new Effect("product", "The Product pin is released (AD-30)"),
					new Effect("notification", "A decline tells the Customer (job.selection_declined)")),
			false, true),

	J6(Machine.JOB, job(JobState.AGREEMENT), job(JobState.STARTING), Set.of(SYSTEM),
			"Milestone 1 becomes Funded (FR-41)",
			List.of(new Guard("FR-17", "The Agreement is confirmed"),
					new Guard("FR-75", "The Crew is confirmed, re-checked here")),
			List.of(new Effect("job", "The Crew Terms version the Crew confirmed comes into force before M1 records it")),
			false, true),

	J7(Machine.JOB, job(JobState.STARTING), job(JobState.ACTIVE), Set.of(SYSTEM),
			"Code Job: repositories created and access verified (FR-18); Assessment Job: immediately",
			List.of(new Guard("FR-18", "A Code Job's repositories exist and its access is verified")), List.of(), false,
			true),

	J8(Machine.JOB, job(JobState.ACTIVE), job(JobState.CLOSING), Set.of(USER, AUTO, DECISION),
			"Closure confirmed (FR-37, FR-67), or a confirmed cancellation or decision with Accepted work (FR-40, FR-54)",
			List.of(new Guard("FR-37", "No Held Milestone")),
			List.of(new Effect("job", "Remaining Planned Milestones are cancelled (M6)"),
					new Effect("repo", "Write access is revoked (FR-21)"),
					new Effect("repo", "The Revocation Checklist is presented (FR-66)"),
					new Effect("delivery", "The Final Merge authorization is requested if not yet given (FR-37)")),
			false, false),

	J9(Machine.JOB, job(JobState.CLOSING), job(JobState.CLOSED, JobState.CANCELLED), Set.of(SYSTEM),
			"Final Merge succeeded or ZIP Fallback offered (FR-38); Assessment Job: immediately; Cancelled if entered by cancellation",
			List.of(), List.of(new Effect("reputation", "The Rating Window opens (FR-49)"),
					new Effect("reputation", "Portfolio Entries are created (FR-50)"),
					new Effect("team", "The Crew is released (FR-75)")),
			false, false),

	J10(Machine.JOB, job(JobState.AGREEMENT, JobState.STARTING, JobState.ACTIVE), job(JobState.CANCELLED),
			Set.of(USER, DECISION),
			"Confirmed cancellation (FR-40) or decision (FR-54) without Accepted work, or either side alone after an FR-40 deadline",
			List.of(new Guard("FR-40", "The Job has no Accepted work")),
			List.of(new Effect("payment", "Held funds are refunded (FR-43)"),
					new Effect("repo", "Access is revoked (FR-21)"),
					new Effect("repo", "The Revocation Checklist is presented (FR-66)"),
					new Effect("team", "The Crew is released (FR-75)")),
			false, false),

	M1(Machine.MILESTONE, milestone(MilestoneState.PLANNED), milestone(MilestoneState.FUNDED), Set.of(SYSTEM),
			"Verified payment of its Payment Order (FR-41)",
			List.of(new Guard("FR-41", "No other Held Milestone on the Job"),
					new Guard("FR-17", "The Job is in Agreement (Milestone 1, after FR-17) or Active")),
			List.of(new Effect("job", "The Milestone records the Crew Terms version in force, which its release uses (FR-42)")),
			true, true),

	M2(Machine.MILESTONE, milestone(MilestoneState.FUNDED, MilestoneState.REVISION_REQUESTED),
			milestone(MilestoneState.SUBMITTED), Set.of(USER), "Milestone Handover by the Mentor (FR-32)",
			List.of(new Guard("FR-8", "Scans are clean"), new Guard("FR-32", "The Handover Commit is recorded")),
			List.of(new Effect("timers", "The Auto-acceptance timer starts (FR-35)")), true, false),

	M3(Machine.MILESTONE, milestone(MilestoneState.SUBMITTED),
			milestone(MilestoneState.FUNDED, MilestoneState.REVISION_REQUESTED), Set.of(SYSTEM),
			"A push to the handed-over branch after the Milestone Handover (FR-32); back to its previous state",
			List.of(), List.of(new Effect("delivery", "The Handover is void"),
					new Effect("timers", "The Auto-acceptance timer is cancelled"),
					new Effect("notification", "The Customer is notified")),
			true, false),

	M4(Machine.MILESTONE, milestone(MilestoneState.SUBMITTED), milestone(MilestoneState.REVISION_REQUESTED),
			Set.of(USER), "The Customer requests a revision (FR-33)",
			List.of(new Guard("FR-34", "A Revision Round remains")), List.of(), true, false),

	M5(Machine.MILESTONE, milestone(MilestoneState.SUBMITTED), milestone(MilestoneState.ACCEPTED), Set.of(USER, AUTO),
			"The Customer accepts (FR-33) or Auto-acceptance (FR-35)", List.of(),
			List.of(new Effect("repo", "Exactly the Handover Commit is merged into the Intermediary Repository"),
					new Effect("payment", "Then the funds are released (FR-42)")),
			true, false),

	M6(Machine.MILESTONE, milestone(MilestoneState.PLANNED), milestone(MilestoneState.CANCELLED), Set.of(USER, SYSTEM),
			"Confirmed cancellation (FR-40), a Change Request removing it (FR-36), or the Job entering Closing (J8)",
			List.of(), List.of(new Effect("job", "No money moves")), true, false),

	M7(Machine.MILESTONE, milestone(MilestoneState.held()), milestone(MilestoneState.CANCELLED), Set.of(USER),
			"Confirmed cancellation (FR-40) or a Change Request removing it (FR-36)", List.of(),
			List.of(new Effect("payment", "Full refund (FR-43)")), true, false),

	M8(Machine.MILESTONE, milestone(MilestoneState.held()), milestone(MilestoneState.SETTLED), Set.of(DECISION),
			"Administrator decision (FR-54)", List.of(),
			List.of(new Effect("payment", "Release and refund amounts as decided"),
					new Effect("repo", "The pull request is merged or closed as decided")),
			false, false);

	/** What a transition moves: the Job itself, or one of its Milestones. */
	enum Machine {
		JOB, MILESTONE
	}

	/** A condition checked before the first effect, tagged with the FR that adds it. */
	record Guard(String fr, String text) {
	}

	/** A consequence of the transition and the module that executes it (AD-5). */
	record Effect(String module, String text) {
	}

	private final Machine machine;

	private final Set<String> from;

	private final Set<String> to;

	private final Set<Trigger> triggers;

	private final String trigger;

	private final List<Guard> guards;

	private final List<Effect> effects;

	private final boolean frozenBlocks;

	private final boolean reachable;

	Transition(Machine machine, Set<String> from, Set<String> to, Set<Trigger> triggers, String trigger,
			List<Guard> guards, List<Effect> effects, boolean frozenBlocks, boolean reachable) {
		this.machine = machine;
		this.from = Collections.unmodifiableSet(from);
		this.to = Collections.unmodifiableSet(to);
		this.triggers = Set.copyOf(triggers);
		this.trigger = trigger;
		this.guards = List.copyOf(guards);
		this.effects = List.copyOf(effects);
		this.frozenBlocks = frozenBlocks;
		this.reachable = reachable;
	}

	Machine machine() {
		return this.machine;
	}

	/** The state names it leaves from. */
	Set<String> from() {
		return this.from;
	}

	/** The state names it may lead to: one, except J9 (Closed or Cancelled) and M3 (the previous state). */
	Set<String> to() {
		return this.to;
	}

	Set<Trigger> triggers() {
		return this.triggers;
	}

	/** The trigger and its actor, as section 4.0 states it. */
	String trigger() {
		return this.trigger;
	}

	List<Guard> guards() {
		return this.guards;
	}

	List<Effect> effects() {
		return this.effects;
	}

	/** Whether an open Dispute's freeze refuses it: M1 to M7 (section 4.0, FR-52). */
	boolean frozenBlocks() {
		return this.frozenBlocks;
	}

	/** Whether its command exists yet; the others arrive with the stories that add them. */
	boolean reachable() {
		return this.reachable;
	}

	/** The audit action of the transition, for example {@code job.transition_j1}. */
	String auditAction() {
		return "job.transition_" + name().toLowerCase(Locale.ROOT);
	}

	private static Set<String> job(JobState... states) {
		return Arrays.stream(states).map(Enum::name).collect(Collectors.toCollection(LinkedHashSet::new));
	}

	private static Set<String> milestone(MilestoneState... states) {
		return Arrays.stream(states).map(Enum::name).collect(Collectors.toCollection(LinkedHashSet::new));
	}

	private static Set<String> milestone(Set<MilestoneState> states) {
		return milestone(states.toArray(MilestoneState[]::new));
	}

}
