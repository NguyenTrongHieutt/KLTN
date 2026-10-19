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
 * {@link JobTransitioned}. Milestone transitions arrive with the Agreement (Epic 4).
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

		private Applying(Transition transition, Job job, Actor actor, Trigger trigger, UUID transitionId, Instant now) {
			this.transition = transition;
			this.job = job;
			this.actor = actor;
			this.trigger = trigger;
			this.transitionId = transitionId;
			this.now = now;
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
		return run(transition, jobId, actor, trigger, null);
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
			return Optional.of(run(transition, jobId, actor, trigger, expectedVersion));
		}
		catch (ApiException refused) {
			return Optional.empty();
		}
	}

	private Applied run(Transition transition, UUID jobId, Actor actor, Trigger trigger, @Nullable Long expectedVersion) {
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
		Applying applying = new Applying(transition, job, actor, trigger, this.ids.newId(), this.clock.instant());
		step.check(applying);
		step.apply(applying);
		write(applying);
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
