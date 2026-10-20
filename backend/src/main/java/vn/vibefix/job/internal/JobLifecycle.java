package vn.vibefix.job.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.identity.MentorGate;
import vn.vibefix.job.JobTransitioned;
import vn.vibefix.job.internal.Jobs.Job;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.IdGenerator;

/**
 * The only code that changes {@code Job.state} or {@code Milestone.state} (AD-4). Every apply runs in
 * one transaction: it locks the Job row, re-reads the actor's account (a locked one is refused), checks
 * the state, the freeze, and the transition's guards, bumps {@code Job.version}, lets the transition's
 * {@link Step} write its columns and run its effects, writes one audit entry, and publishes one
 * {@link JobTransitioned}. A cascade (AD-4: M1 with J6, J6 with J7) checks every transition it holds
 * before the first write and then writes them in order under one lock and one version bump, one audit
 * entry and one event each; Milestone transitions read and write {@code job.milestone.state}.
 */
@Component
class JobLifecycle {

	/** Who applies a transition: an account, re-read on every apply, or the System (AD-4). */
	record Actor(@Nullable UUID accountId) {

		static final Actor SYSTEM = new Actor(null);

		static Actor user(UUID accountId) {
			return new Actor(Objects.requireNonNull(accountId, "accountId"));
		}

		boolean isSystem() {
			return this.accountId == null;
		}

		boolean is(UUID account) {
			return account.equals(this.accountId);
		}

		AuditActor audit() {
			return this.accountId == null ? AuditActor.system() : AuditActor.user(this.accountId);
		}

	}

	/** One reachable transition's guards and effects, in the module's own words. */
	interface Step {

		Transition transition();

		/**
		 * Refuses the transition before anything is written: the actor's right to it and its guards.
		 * @throws ApiException the refusal
		 */
		void check(Applying applying);

		/** The transition's columns and effects (Deadlines, notifications), written in the apply's transaction. */
		void apply(Applying applying);

		/** Effects that need the written row, such as notifying the Mentor J3 recorded. */
		default void afterWrite(Applying applying) {
		}

	}

	/** A transition being applied to a locked Job, as its {@link Step} sees it. */
	static final class Applying {

		private static final Pattern COLUMN = Pattern.compile("[a-z][a-z_]*");

		private final Transition transition;

		private final Job job;

		private final Actor actor;

		private final Trigger trigger;

		private final UUID transitionId;

		private final Instant now;

		private final Map<String, Object> columns = new LinkedHashMap<>();

		private final List<String> jsonColumns = new ArrayList<>();

		private final Map<String, String> facts = new LinkedHashMap<>();

		private final Map<String, Object> input;

		private final String from;

		private final @Nullable UUID milestoneId;

		private final boolean dryRun;

		private Applying(Transition transition, Job job, Actor actor, Trigger trigger, UUID transitionId, Instant now,
				Map<String, Object> input, String from, @Nullable UUID milestoneId, boolean dryRun) {
			this.transition = transition;
			this.job = job;
			this.actor = actor;
			this.trigger = trigger;
			this.transitionId = transitionId;
			this.now = now;
			this.input = Map.copyOf(input);
			this.from = from;
			this.milestoneId = milestoneId;
			this.dryRun = dryRun;
		}

		/** The state it leaves: the Job's, or the Milestone's for a Milestone transition. */
		String from() {
			return this.from;
		}

		/** The Milestone a Milestone transition moves. */
		UUID milestoneId() {
			return Objects.requireNonNull(this.milestoneId, () -> this.transition + " moves no Milestone");
		}

		/**
		 * A dry run checks without the lock and must not take locks or write: a guard that locks (J6's
		 * Product lock) checks without locking.
		 */
		boolean dryRun() {
			return this.dryRun;
		}

		/** What the command brought, for example J3's Mentor and Application. */
		/** Whether the command carries {@code key}, for evidence only some Jobs need. */
		boolean has(String key) {
			return this.input.get(key) != null;
		}

		Object input(String key) {
			Object value = this.input.get(key);
			if (value == null) {
				throw new IllegalArgumentException(this.transition + " needs the input " + key);
			}
			return value;
		}

		Transition transition() {
			return this.transition;
		}

		/** The Job as it was locked, before the transition. */
		Job job() {
			return this.job;
		}

		Actor actor() {
			return this.actor;
		}

		Trigger trigger() {
			return this.trigger;
		}

		UUID transitionId() {
			return this.transitionId;
		}

		Instant now() {
			return this.now;
		}

		/** The Job's version once the transition commits: a Deadline's generation. */
		long version() {
			return this.job.version() + 1;
		}

		/** The state the transition leads to. */
		String to() {
			return this.transition.to().iterator().next();
		}

		/** Writes {@code value} to {@code column} with the state, in the same statement. */
		void set(String column, @Nullable Object value) {
			if (!COLUMN.matcher(column).matches() || List.of("id", "state", "version", "updated_at").contains(column)) {
				throw new IllegalArgumentException("A step does not write the column " + column);
			}
			this.columns.put(column, value instanceof Instant instant ? Timestamp.from(instant) : value);
		}

		/** Writes a JSON document to a JSONB {@code column}. */
		void setJson(String column, @Nullable String json) {
			set(column, json);
			this.jsonColumns.add(column);
		}

		/** A fact the event carries for the consequences. */
		void fact(String key, String value) {
			this.facts.put(key, value);
		}

	}

	/** What an apply did. */
	record Applied(UUID transitionId, long version, String to) {
	}

	/** One transition of a cascade (AD-4): the Job's own, or one of its Milestones'. */
	/**
	 * One transition of a cascade.
	 * @param trigger its own trigger when it differs from the cascade's (J8 by a person, then M6 and J9 by
	 * the System); {@code null} for the cascade's
	 */
	record Planned(Transition transition, @Nullable UUID milestoneId, Map<String, Object> input, @Nullable Trigger trigger) {

		Planned {
			input = Map.copyOf(input);
			if ((transition.machine() == Transition.Machine.MILESTONE) != (milestoneId != null)) {
				throw new IllegalArgumentException(transition + " and a Milestone ID do not match");
			}
		}

		static Planned job(Transition transition) {
			return new Planned(transition, null, Map.of(), null);
		}

		static Planned job(Transition transition, Trigger trigger) {
			return new Planned(transition, null, Map.of(), trigger);
		}

		static Planned milestone(Transition transition, UUID milestoneId, Map<String, Object> input) {
			return new Planned(transition, milestoneId, input, null);
		}

		static Planned milestone(Transition transition, UUID milestoneId, Map<String, Object> input, Trigger trigger) {
			return new Planned(transition, milestoneId, input, trigger);
		}

	}

	private final Map<Transition, Step> steps = new EnumMap<>(Transition.class);

	private final Jobs jobs;

	private final MentorGate accounts;

	private final JdbcClient jdbc;

	private final AuditLog auditLog;

	private final ApplicationEventPublisher events;

	private final IdGenerator ids;

	private final Clock clock;

	JobLifecycle(List<Step> steps, Jobs jobs, MentorGate accounts, JdbcClient jdbc, AuditLog auditLog,
			ApplicationEventPublisher events, IdGenerator ids, Clock clock) {
		for (Step step : steps) {
			if (!step.transition().reachable()) {
				throw new IllegalStateException(step.transition() + " is not reachable yet, so it has no step");
			}
			if (this.steps.put(step.transition(), step) != null) {
				throw new IllegalStateException(step.transition() + " has two steps");
			}
		}
		for (Transition transition : Transition.values()) {
			if (transition.reachable() && !this.steps.containsKey(transition)) {
				throw new IllegalStateException(transition + " is reachable but has no step");
			}
		}
		this.jobs = jobs;
		this.accounts = accounts;
		this.jdbc = jdbc;
		this.auditLog = auditLog;
		this.events = events;
		this.ids = ids;
		this.clock = clock;
	}

	/**
	 * Applies {@code transition} to the Job.
	 * @throws ApiException {@code NOT_FOUND} for no such Job; {@code FORBIDDEN ACCOUNT_LOCKED} for a
	 * locked or erased actor; {@code STALE STATE_CHANGED} when the Job is not in a state the transition
	 * leaves; {@code CONFLICT JOB_FROZEN} for M1 to M7 while a Dispute freezes the Job; the step's guards
	 * @throws IllegalStateException for a transition whose command does not exist yet
	 * @throws IllegalArgumentException for a trigger the transition does not have
	 */
	@Transactional
	Applied apply(Transition transition, UUID jobId, Actor actor, Trigger trigger) {
		return run(transition, jobId, actor, trigger, null, Map.of());
	}

	/**
	 * Applies {@code transition} with what its step needs from the command, at whatever version the Job
	 * has, for a fact another module vouches for (J4's signature).
	 */
	@Transactional
	Applied apply(Transition transition, UUID jobId, Actor actor, Trigger trigger, Map<String, Object> input) {
		return run(transition, jobId, actor, trigger, null, input);
	}

	/**
	 * Applies {@code transition} to the Job as a person's decision: the screen sends the version it
	 * loaded, and a Job that moved on since answers {@code STALE} (AD-23).
	 * @param input what the transition's step needs from the command
	 * @throws ApiException as {@link #apply(Transition, UUID, Actor, Trigger)}, and {@code STALE
	 * STATE_CHANGED} for another version
	 */
	@Transactional
	Applied apply(Transition transition, UUID jobId, Actor actor, Trigger trigger, long expectedVersion,
			Map<String, Object> input) {
		return run(transition, jobId, actor, trigger, expectedVersion, input);
	}

	/**
	 * Applies {@code transition} when the Job still has {@code expectedVersion}, as a Deadline does
	 * for the state it was scheduled in. A refusal writes nothing and leaves the caller's transaction
	 * usable; the System actor and the guards of {@code job} itself never throw through another
	 * module's transaction.
	 * @return the outcome, or empty when the Job moved on or a check refused the transition
	 */
	@Transactional
	Optional<Applied> tryApply(Transition transition, UUID jobId, Actor actor, Trigger trigger, long expectedVersion) {
		try {
			return Optional.of(run(transition, jobId, actor, trigger, expectedVersion, Map.of()));
		}
		catch (ApiException refused) {
			return Optional.empty();
		}
	}

	/**
	 * Applies a cascade as the System, as AD-4 lists them (M1 with J6, J6 with J7): every transition's
	 * state, freeze, and guards are checked first, each against the state the earlier ones lead to; then
	 * each is written in order with one {@code Job.version} bump. A refusal writes nothing and leaves the
	 * caller's transaction usable.
	 * @return the applied transitions in order, or empty when a check refused one
	 */
	@Transactional
	Optional<List<Applied>> tryApplyCascade(UUID jobId, Trigger trigger, List<Planned> cascade) {
		Job job = this.jobs.lock(jobId).orElse(null);
		if (job == null) {
			return Optional.empty();
		}
		List<Applying> checked;
		try {
			checked = check(job, Actor.SYSTEM, trigger, cascade, false);
		}
		catch (ApiException refused) {
			return Optional.empty();
		}
		List<Applied> applied = new ArrayList<>();
		for (Applying applying : checked) {
			applied.add(commit(applying));
		}
		return Optional.of(applied);
	}

	/**
	 * The first refusal the cascade would meet now, read without the lock and writing nothing, for a
	 * screen that says why an action waits.
	 * @return the refusal, or {@code null} when the cascade would apply now
	 */
	@Transactional(readOnly = true)
	@Nullable ApiException refusal(UUID jobId, Trigger trigger, List<Planned> cascade) {
		Job job = this.jdbc.sql("SELECT " + Jobs.COLUMNS + " FROM job.job WHERE id = :id")
			.param("id", jobId)
			.query(Jobs::job)
			.optional()
			.orElse(null);
		if (job == null) {
			return new ApiException(ErrorCode.NOT_FOUND);
		}
		try {
			check(job, Actor.SYSTEM, trigger, cascade, true);
			return null;
		}
		catch (ApiException refused) {
			return refused;
		}
	}

	/**
	 * Applies a Milestone transition as a person's decision (M2 by the Mentor): the screen sends the
	 * Job version it loaded, and a Job that moved on since answers {@code STALE} (AD-23).
	 * @param input what the transition's step needs, such as the evidence another module vouches for
	 * @throws ApiException {@code NOT_FOUND} for no such Job or Milestone; {@code FORBIDDEN
	 * ACCOUNT_LOCKED} for a locked or erased actor; {@code STALE STATE_CHANGED} for another version or a
	 * Milestone not in a state the transition leaves; {@code CONFLICT JOB_FROZEN}; the step's guards
	 */
	@Transactional
	Applied applyMilestone(Transition transition, UUID jobId, UUID milestoneId, Actor actor, Trigger trigger,
			long expectedVersion, Map<String, Object> input) {
		Job job = this.jobs.lock(jobId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (!actor.isSystem()) {
			this.accounts.requireActiveAccount(Objects.requireNonNull(actor.accountId()));
		}
		if (job.version() != expectedVersion) {
			throw new ApiException(ErrorCode.STALE, JobReason.STATE_CHANGED);
		}
		List<Applying> checked = check(job, actor, trigger, List.of(Planned.milestone(transition, milestoneId, input)),
				false);
		return commit(checked.get(0));
	}

	/**
	 * Applies a Milestone transition when its checks pass, as a Deadline does (M5 by Auto-acceptance). A
	 * refusal writes nothing and leaves the caller's transaction usable.
	 * @return the outcome, or empty when the Milestone moved on or a check refused it
	 */
	@Transactional
	Optional<Applied> tryApplyMilestone(Transition transition, UUID jobId, UUID milestoneId, Actor actor, Trigger trigger,
			Map<String, Object> input) {
		Job job = this.jobs.lock(jobId).orElse(null);
		if (job == null) {
			return Optional.empty();
		}
		List<Applying> checked;
		try {
			checked = check(job, actor, trigger, List.of(Planned.milestone(transition, milestoneId, input)), false);
		}
		catch (ApiException refused) {
			return Optional.empty();
		}
		return Optional.of(commit(checked.get(0)));
	}

	private List<Applying> check(Job job, Actor actor, Trigger trigger, List<Planned> cascade, boolean dryRun) {
		String jobState = job.state().name();
		Map<UUID, String> milestones = new HashMap<>();
		List<Applying> checked = new ArrayList<>();
		Instant now = this.clock.instant();
		for (Planned planned : cascade) {
			Transition transition = planned.transition();
			Trigger by = planned.trigger() != null ? planned.trigger() : trigger;
			Step step = this.steps.get(transition);
			if (step == null) {
				throw new IllegalStateException(transition + " has no command yet");
			}
			if (!transition.triggers().contains(by)) {
				throw new IllegalArgumentException(transition + " is not triggered by " + by);
			}
			String from = jobState;
			if (planned.milestoneId() != null) {
				from = milestones.containsKey(planned.milestoneId()) ? milestones.get(planned.milestoneId())
						: milestoneState(job.id(), planned.milestoneId());
				if (from == null) {
					throw new ApiException(ErrorCode.NOT_FOUND);
				}
			}
			if (!transition.from().contains(from)) {
				throw new ApiException(ErrorCode.STALE, JobReason.STATE_CHANGED);
			}
			if (transition.frozenBlocks() && job.frozenAt() != null) {
				throw new ApiException(ErrorCode.CONFLICT, JobReason.JOB_FROZEN);
			}
			Applying applying = new Applying(transition, job, actor, by, this.ids.newId(), now, planned.input(), from,
					planned.milestoneId(), dryRun);
			step.check(applying);
			if (planned.milestoneId() == null) {
				jobState = applying.to();
			}
			else {
				milestones.put(planned.milestoneId(), applying.to());
			}
			checked.add(applying);
		}
		return checked;
	}

	private Applied commit(Applying applying) {
		Step step = this.steps.get(applying.transition());
		step.apply(applying);
		writeCascade(applying);
		step.afterWrite(applying);
		AuditObject object = applying.milestoneId == null ? AuditObject.of("job.job", applying.job().id())
				: AuditObject.of("job.milestone", applying.milestoneId());
		this.auditLog.record(AuditEntry.of(applying.actor().audit(), applying.transition().auditAction(), object)
			.forJob(applying.job().id()));
		this.events.publishEvent(new JobTransitioned(this.ids.newId(), applying.transitionId(), applying.job().id(),
				applying.transition().name(), applying.trigger().name(), applying.from(), applying.to(), applying.version(),
				applying.job().agreementVersionNo(), applying.facts));
		return new Applied(applying.transitionId(), applying.version(), applying.to());
	}

	/** A cascade's write: the row is locked, and every transition of it sets the same new version. */
	private void writeCascade(Applying applying) {
		Map<String, Object> params = new HashMap<>();
		params.put("state", applying.to());
		params.put("version", applying.version());
		params.put("now", Timestamp.from(applying.now()));
		params.put("id", applying.job().id());
		StringBuilder columns = new StringBuilder();
		appendColumns(applying, columns, params);
		if (applying.milestoneId == null) {
			int updated = this.jdbc.sql("UPDATE job.job SET state = :state, version = :version, updated_at = :now" + columns
					+ " WHERE id = :id").params(params).update();
			if (updated != 1) {
				throw new IllegalStateException("The locked Job " + applying.job().id() + " is gone");
			}
			return;
		}
		params.put("milestone", applying.milestoneId());
		params.put("from", applying.from());
		int updated = this.jdbc.sql("UPDATE job.milestone SET state = :state" + columns
				+ " WHERE id = :milestone AND job_id = :id AND state = :from").params(params).update();
		if (updated != 1) {
			throw new IllegalStateException("The Milestone " + applying.milestoneId() + " changed during the apply");
		}
		this.jdbc.sql("UPDATE job.job SET version = :version, updated_at = :now WHERE id = :id").params(params).update();
	}

	private void appendColumns(Applying applying, StringBuilder sql, Map<String, Object> params) {
		int index = 0;
		for (Map.Entry<String, Object> column : applying.columns.entrySet()) {
			String param = "c" + index++;
			sql.append(", ").append(column.getKey()).append(" = ");
			sql.append(applying.jsonColumns.contains(column.getKey()) ? "CAST(:" + param + " AS JSONB)" : ":" + param);
			params.put(param, column.getValue());
		}
	}

	private @Nullable String milestoneState(UUID jobId, UUID milestoneId) {
		return this.jdbc.sql("SELECT state FROM job.milestone WHERE id = :id AND job_id = :job AND removed_at IS NULL")
			.param("id", milestoneId)
			.param("job", jobId)
			.query(String.class)
			.optional()
			.orElse(null);
	}

	private Applied run(Transition transition, UUID jobId, Actor actor, Trigger trigger, @Nullable Long expectedVersion,
			Map<String, Object> input) {
		Step step = this.steps.get(transition);
		if (step == null) {
			throw new IllegalStateException(transition + " has no command yet");
		}
		if (!transition.triggers().contains(trigger)) {
			throw new IllegalArgumentException(transition + " is not triggered by " + trigger);
		}
		Job job = this.jobs.lock(jobId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (!actor.isSystem()) {
			this.accounts.requireActiveAccount(Objects.requireNonNull(actor.accountId()));
		}
		if (expectedVersion != null && job.version() != expectedVersion) {
			throw new ApiException(ErrorCode.STALE, JobReason.STATE_CHANGED);
		}
		if (!transition.from().contains(job.state().name())) {
			throw new ApiException(ErrorCode.STALE, JobReason.STATE_CHANGED);
		}
		if (transition.frozenBlocks() && job.frozenAt() != null) {
			throw new ApiException(ErrorCode.CONFLICT, JobReason.JOB_FROZEN);
		}
		if (transition.machine() != Transition.Machine.JOB) {
			throw new IllegalArgumentException(transition + " moves a Milestone: apply it in a cascade");
		}
		Applying applying = new Applying(transition, job, actor, trigger, this.ids.newId(), this.clock.instant(), input,
				job.state().name(), null, false);
		step.check(applying);
		step.apply(applying);
		write(applying);
		step.afterWrite(applying);
		this.auditLog.record(AuditEntry.of(actor.audit(), transition.auditAction(), AuditObject.of("job.job", jobId))
			.forJob(jobId));
		this.events.publishEvent(new JobTransitioned(this.ids.newId(), applying.transitionId(), jobId, transition.name(),
				trigger.name(), job.state().name(), applying.to(), applying.version(), job.agreementVersionNo(),
				applying.facts));
		return new Applied(applying.transitionId(), applying.version(), applying.to());
	}

	private void write(Applying applying) {
		StringBuilder sql = new StringBuilder("UPDATE job.job SET state = :state, version = :version, updated_at = :now");
		Map<String, Object> params = new HashMap<>();
		int index = 0;
		for (Map.Entry<String, Object> column : applying.columns.entrySet()) {
			String param = "c" + index++;
			sql.append(", ").append(column.getKey()).append(" = ");
			sql.append(applying.jsonColumns.contains(column.getKey()) ? "CAST(:" + param + " AS JSONB)" : ":" + param);
			params.put(param, column.getValue());
		}
		sql.append(" WHERE id = :id AND version = :previous");
		params.put("state", applying.to());
		params.put("version", applying.version());
		params.put("now", Timestamp.from(applying.now()));
		params.put("id", applying.job().id());
		params.put("previous", applying.job().version());
		int updated = this.jdbc.sql(sql.toString()).params(params).update();
		if (updated != 1) {
			throw new IllegalStateException("The locked Job " + applying.job().id() + " changed during the apply");
		}
	}

}
