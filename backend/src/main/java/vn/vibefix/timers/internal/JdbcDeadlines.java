package vn.vibefix.timers.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
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
		this.jdbc.sql("""
				UPDATE timers.deadline
				SET status = 'PAUSED', due_at = NULL,
				    remaining_ms = GREATEST(0, (EXTRACT(EPOCH FROM (due_at - CAST(:now AS TIMESTAMPTZ))) * 1000)::BIGINT)
				WHERE job_id = :job AND pausable AND status = 'PENDING'
				""").param("job", jobId).param("now", Timestamp.from(now)).update();
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void unfreeze(UUID jobId) {
		Instant now = this.clock.instant();
		// Resumes every paused row of the Job, also one a handler paused (REPAUSE) without a recorded freeze.
		this.jdbc.sql("DELETE FROM timers.frozen_job WHERE job_id = :job").param("job", jobId).update();
		this.jdbc.sql("""
				UPDATE timers.deadline
				SET status = 'PENDING', due_at = CAST(:now AS TIMESTAMPTZ) + remaining_ms * INTERVAL '1 millisecond', remaining_ms = NULL
				WHERE job_id = :job AND status = 'PAUSED'
				""").param("job", jobId).param("now", Timestamp.from(now)).update();
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

	/** Paused with the time it had when its Job froze, or none when no freeze is recorded. */
	void repause(DueDeadline deadline) {
		long remaining = deadline.jobId() == null ? 0
				: frozenAt(deadline.jobId()).map(frozen -> remainingMillis(deadline.dueAt(), frozen)).orElse(0L);
		this.jdbc.sql("UPDATE timers.deadline SET status = 'PAUSED', due_at = NULL, remaining_ms = :remaining WHERE id = :id")
			.param("id", deadline.id())
			.param("remaining", remaining)
			.update();
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
