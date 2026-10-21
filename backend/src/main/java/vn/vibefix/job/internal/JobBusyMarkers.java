package vn.vibefix.job.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.job.JobBusy;
import vn.vibefix.job.internal.Jobs.Job;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;

/**
 * {@link JobBusy} on {@code job.job}'s busy columns (AD-28). Reserving locks the Job row and checks the
 * version, the purpose's states, the freeze, and any other marker; both calls are audited and leave
 * {@code Job.version} alone. {@link #isBusy} is what {@link JobLifecycle} and Change Requests read.
 */
@Component
class JobBusyMarkers implements JobBusy {

	/** The states each purpose runs in (AD-5, AD-11). */
	private static final Map<Purpose, Set<JobState>> STATES = Map.of(Purpose.JOB_SETUP, Set.of(JobState.STARTING),
			Purpose.JOB_VERIFY, Set.of(JobState.STARTING), Purpose.ACCEPTANCE, Set.of(JobState.ACTIVE), Purpose.SETTLEMENT,
			Set.of(JobState.ACTIVE, JobState.CLOSING), Purpose.FINAL_MERGE, Set.of(JobState.CLOSING));

	private final Jobs jobs;

	private final JdbcClient jdbc;

	private final AuditLog auditLog;

	private final Clock clock;

	JobBusyMarkers(Jobs jobs, JdbcClient jdbc, AuditLog auditLog, Clock clock) {
		this.jobs = jobs;
		this.jdbc = jdbc;
		this.auditLog = auditLog;
		this.clock = clock;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<BusyJob> job(UUID jobId) {
		return this.jdbc.sql("SELECT " + Jobs.COLUMNS + " FROM job.job WHERE id = :id")
			.param("id", jobId)
			.query(Jobs::job)
			.optional()
			.map(job -> new BusyJob(job.id(), job.productId(), job.customerId(), job.mentorId(), job.title(),
					job.state().name(), job.version(), job.code(), job.frozenAt() != null, busy(job.id())));
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void reserve(UUID jobId, Purpose purpose, @Nullable String subjectToken, long expectedVersion, String operationKey) {
		Job job = this.jobs.lock(jobId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		Optional<Busy> busy = busy(jobId);
		if (busy.isPresent()) {
			if (busy.get().operationKey().equals(operationKey)) {
				return;
			}
			throw new ApiException(ErrorCode.JOB_BUSY);
		}
		if (job.version() != expectedVersion) {
			throw new ApiException(ErrorCode.STALE);
		}
		if (!STATES.get(purpose).contains(job.state())) {
			throw new ApiException(ErrorCode.STALE, JobReason.STATE_CHANGED);
		}
		if (job.frozenAt() != null) {
			throw new ApiException(ErrorCode.CONFLICT, JobReason.JOB_FROZEN);
		}
		this.jdbc.sql("""
				UPDATE job.job SET busy_operation_key = :key, busy_purpose = :purpose, busy_subject = :subject, busy_since = :now
				WHERE id = :id
				""")
			.param("key", operationKey)
			.param("purpose", purpose.name())
			.param("subject", subjectToken)
			.param("now", Timestamp.from(this.clock.instant()))
			.param("id", jobId)
			.update();
		this.auditLog.record(AuditEntry.of(AuditActor.system(), "job.busy_reserved", AuditObject.of("job.job", jobId))
			.forJob(jobId));
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public boolean release(UUID jobId, String operationKey) {
		boolean released = this.jdbc.sql("""
				UPDATE job.job SET busy_operation_key = NULL, busy_purpose = NULL, busy_subject = NULL, busy_since = NULL
				WHERE id = :id AND busy_operation_key = :key
				""").param("id", jobId).param("key", operationKey).update() == 1;
		if (released) {
			this.auditLog.record(AuditEntry.of(AuditActor.system(), "job.busy_released", AuditObject.of("job.job", jobId))
				.forJob(jobId));
		}
		return released;
	}

	/** Whether the Job has a marker, read on the row the caller locked. */
	static boolean isBusy(JdbcClient jdbc, UUID jobId) {
		return jdbc.sql("SELECT busy_operation_key IS NOT NULL FROM job.job WHERE id = :id")
			.param("id", jobId)
			.query(Boolean.class)
			.optional()
			.orElse(false);
	}

	private Optional<Busy> busy(UUID jobId) {
		return this.jdbc.sql("""
				SELECT busy_operation_key, busy_purpose, busy_subject, busy_since FROM job.job
				WHERE id = :id AND busy_operation_key IS NOT NULL
				""")
			.param("id", jobId)
			.query((rs, n) -> new Busy(Purpose.valueOf(rs.getString("busy_purpose")), rs.getString("busy_operation_key"),
					rs.getString("busy_subject"), rs.getTimestamp("busy_since").toInstant()))
			.optional();
	}

}
