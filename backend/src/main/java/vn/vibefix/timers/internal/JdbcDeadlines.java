package vn.vibefix.timers.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.shared.IdGenerator;
import vn.vibefix.timers.DeadlineKind;
import vn.vibefix.timers.DeadlineRequest;
import vn.vibefix.timers.Deadlines;
import vn.vibefix.timers.DueDeadline;

/**
 * {@link Deadlines} in {@code timers.deadline}, and the row changes the poller makes. Business time
 * comes from the injected {@link Clock}, never from SQL {@code now()} (Conventions, Time).
 */
@Component
class JdbcDeadlines implements Deadlines {

	private static final Logger log = LoggerFactory.getLogger(JdbcDeadlines.class);

	private final JdbcClient jdbc;

	private final DeadlineHandlers handlers;

	private final IdGenerator ids;

	private final Clock clock;

	JdbcDeadlines(JdbcClient jdbc, DeadlineHandlers handlers, IdGenerator ids, Clock clock) {
		this.jdbc = jdbc;
		this.handlers = handlers;
		this.ids = ids;
		this.clock = clock;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public UUID schedule(DeadlineRequest request) {
		DeadlineKind kind = this.handlers.require(request.kind()).kind();
		if (kind.jobScoped() && request.jobId() == null) {
			throw new IllegalArgumentException("A Deadline of kind " + kind.key() + " belongs to a Job");
		}
		Instant now = this.clock.instant();
		boolean paused = kind.pausable() && frozenAt(request.jobId()).isPresent();
		UUID id = this.ids.newId();
		this.jdbc.sql("""
				INSERT INTO timers.deadline (id, kind, owner, subject_id, generation, job_id, pausable, status,
				                             due_at, remaining_ms, created_at)
				VALUES (:id, :kind, :owner, :subject, :generation, :job, :pausable, :status, :due, :remaining, :now)
				""")
			.param("id", id)
			.param("kind", kind.key())
			.param("owner", kind.owner())
			.param("subject", request.subjectId())
			.param("generation", request.generation())
			.param("job", request.jobId())
			.param("pausable", kind.pausable())
			.param("status", paused ? "PAUSED" : "PENDING")
			.param("due", paused ? null : Timestamp.from(request.dueAt()))
			.param("remaining", paused ? remainingMillis(request.dueAt(), now) : null)
			.param("now", Timestamp.from(now))
			.update();
		return id;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void freeze(UUID jobId) {
		Instant now = this.clock.instant();
		int frozen = this.jdbc.sql("""
				INSERT INTO timers.frozen_job (job_id, frozen_at) VALUES (:job, :now) ON CONFLICT (job_id) DO NOTHING
				""").param("job", jobId).param("now", Timestamp.from(now)).update();
		if (frozen == 0) {
			return;
		}
		// AD-4: the caller holds the Job row, and the poller may be waiting for it with a claimed row, so this
		// never waits on a Deadline row. A row it skips is being handled now, and its handler answers REPAUSE, or
		// is held by a failed run's retry transaction, which pauses it or makes it due at the next poll.
		this.jdbc.sql("""
				UPDATE timers.deadline
				SET status = 'PAUSED', due_at = NULL,
				    remaining_ms = GREATEST(0, (EXTRACT(EPOCH FROM (due_at - CAST(:now AS TIMESTAMPTZ))) * 1000)::BIGINT)
				WHERE id IN (
				    SELECT id FROM timers.deadline
				    WHERE job_id = :job AND pausable AND status = 'PENDING'
				    FOR UPDATE SKIP LOCKED)
				""").param("job", jobId).param("now", Timestamp.from(now)).update();
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void unfreeze(UUID jobId) {
		Instant now = this.clock.instant();
		lockThaw(jobId);
		this.jdbc.sql("DELETE FROM timers.frozen_job WHERE job_id = :job").param("job", jobId).update();
		this.jdbc.sql("""
				UPDATE timers.deadline
				SET status = 'PENDING', due_at = CAST(:now AS TIMESTAMPTZ) + remaining_ms * INTERVAL '1 millisecond', remaining_ms = NULL
				WHERE job_id = :job AND status = 'PAUSED'
				""").param("job", jobId).param("now", Timestamp.from(now)).update();
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<Instant> dueAt(String kind, UUID subjectId) {
		return this.jdbc.sql("""
				SELECT due_at FROM timers.deadline WHERE kind = :kind AND subject_id = :subject AND status = 'PENDING'
				ORDER BY due_at LIMIT 1
				""")
			.param("kind", kind)
			.param("subject", subjectId)
			.query(Timestamp.class)
			.optional()
			.map(Timestamp::toInstant);
	}

	/** Claims the earliest due Deadline in the current transaction, skipping rows another poller holds. */
	Optional<DueDeadline> claimDue(Instant now) {
		return this.jdbc.sql("""
				SELECT id, kind, subject_id, generation, job_id, due_at FROM timers.deadline
				WHERE status = 'PENDING' AND due_at <= :now
				ORDER BY due_at, id LIMIT 1
				FOR UPDATE SKIP LOCKED
				""")
			.param("now", Timestamp.from(now))
			.query((rs, n) -> new DueDeadline(rs.getObject("id", UUID.class), rs.getString("kind"),
					rs.getObject("subject_id", UUID.class), rs.getString("generation"),
					rs.getObject("job_id", UUID.class), rs.getTimestamp("due_at").toInstant()))
			.optional();
	}

	/** {@code FIRED} or {@code OBSOLETE}. */
	void finish(UUID id, String status, Instant now) {
		this.jdbc.sql("UPDATE timers.deadline SET status = :status, due_at = NULL, finished_at = :now WHERE id = :id")
			.param("id", id)
			.param("status", status)
			.param("now", Timestamp.from(now))
			.update();
	}

	/** Due again at {@code at}, if it is still pending. */
	void dueAgain(UUID id, Instant at) {
		this.jdbc.sql("UPDATE timers.deadline SET due_at = :at WHERE id = :id AND status = 'PENDING'")
			.param("id", id)
			.param("at", Timestamp.from(at))
			.update();
	}

	/**
	 * Paused, if it is still pending, with the time it had when its Job froze. The time counts from
	 * {@code due_at} as the poller claimed it: a failure on the frozen Job pushes nothing first, while a
	 * failure before the freeze has moved {@code due_at} already, by at most one poll interval. With no freeze
	 * recorded no thaw would ever resume the row, so it stays pending, due again at {@code retryAt}.
	 */
	void repause(DueDeadline deadline, Instant retryAt) {
		Instant frozen = frozenAt(deadline.jobId()).orElse(null);
		if (frozen == null) {
			log.warn("Deadline {} of kind {} answered REPAUSE, but Job {} has no freeze record; it stays pending",
					deadline.id(), deadline.kind(), deadline.jobId());
			dueAgain(deadline.id(), retryAt);
			return;
		}
		this.jdbc.sql("""
				UPDATE timers.deadline SET status = 'PAUSED', due_at = NULL, remaining_ms = :remaining
				WHERE id = :id AND status = 'PENDING'
				""")
			.param("id", deadline.id())
			.param("remaining", remainingMillis(deadline.dueAt(), frozen))
			.update();
	}

	/**
	 * A failed run, in a transaction of its own once the rollback released the row: a pausable Deadline whose
	 * Job is frozen pauses, and any other is due again at {@code retryAt}. The thaw lock makes reading the
	 * freeze and writing the row atomic against {@link #unfreeze}, which passes over a pending row and would
	 * leave it paused with nothing to resume it. The row comes first, holding nothing else: another poller
	 * may hold it while it waits for the Job row a thaw holds.
	 */
	void afterFailure(DueDeadline failed, Instant retryAt) {
		boolean pending = this.jdbc.sql("SELECT id FROM timers.deadline WHERE id = :id AND status = 'PENDING' FOR UPDATE")
			.param("id", failed.id())
			.query(UUID.class)
			.optional()
			.isPresent();
		if (!pending) {
			return;
		}
		if (failed.jobId() != null) {
			lockThaw(failed.jobId());
		}
		boolean frozen = this.jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM timers.deadline d JOIN timers.frozen_job f ON f.job_id = d.job_id
				               WHERE d.id = :id AND d.pausable)
				""").param("id", failed.id()).query(Boolean.class).single();
		if (frozen) {
			repause(failed, retryAt);
		}
		else {
			dueAgain(failed.id(), retryAt);
		}
	}

	/** Serializes a thaw and a failed run's choice between pausing and pushing, until the transaction ends. */
	void lockThaw(UUID jobId) {
		this.jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
			.param("key", "timers.thaw:" + jobId)
			.query((rs, n) -> 1)
			.single();
	}

	/** Deletes Deadlines that finished at least {@code kept} ago; @return how many */
	int purgeFinished(Instant now, Duration kept) {
		return this.jdbc.sql("DELETE FROM timers.deadline WHERE finished_at <= :cutoff")
			.param("cutoff", Timestamp.from(now.minus(kept)))
			.update();
	}

	private Optional<Instant> frozenAt(@Nullable UUID jobId) {
		if (jobId == null) {
			return Optional.empty();
		}
		return this.jdbc.sql("SELECT frozen_at FROM timers.frozen_job WHERE job_id = :job")
			.param("job", jobId)
			.query(Timestamp.class)
			.optional()
			.map(Timestamp::toInstant);
	}

	private static long remainingMillis(Instant due, Instant from) {
		return Math.max(0, Duration.between(from, due).toMillis());
	}

}
