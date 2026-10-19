package vn.vibefix.identity.internal;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.timers.DeadlineHandler;
import vn.vibefix.timers.DeadlineKind;
import vn.vibefix.timers.DeadlineOutcome;
import vn.vibefix.timers.DueDeadline;
import vn.vibefix.timers.DeadlineRequest;

/**
 * {@value #KEY} (AD-24, AD-19): an erased account's person-link signals stay 24 months
 * {@code [ASSUMPTION]}, so a person cannot erase one account and come back to sign the other side
 * of a Job at once; then they are deleted. An account that is not erased leaves it {@code OBSOLETE}.
 */
@Component
class PersonSignalPurge implements DeadlineHandler {

	static final String KEY = "identity.person_signal_purge";

	static final DeadlineKind KIND = new DeadlineKind(KEY, false, false);

	/** How long the signals outlive the erasure. */
	static final int MONTHS_KEPT = 24;

	private final JdbcClient jdbc;

	private final JdbcPersonSignals signals;

	private final AuditLog auditLog;

	PersonSignalPurge(JdbcClient jdbc, JdbcPersonSignals signals, AuditLog auditLog) {
		this.jdbc = jdbc;
		this.signals = signals;
		this.auditLog = auditLog;
	}

	/** The purge of {@code accountId}'s signals, 24 months after its erasure at {@code erasedAt}. */
	static DeadlineRequest after(UUID accountId, Instant erasedAt) {
		return new DeadlineRequest(KEY, accountId, erasedAt.toString(), null,
				erasedAt.atOffset(ZoneOffset.UTC).plusMonths(MONTHS_KEPT).toInstant());
	}

	@Override
	public DeadlineKind kind() {
		return KIND;
	}

	@Override
	public DeadlineOutcome handle(DueDeadline deadline) {
		boolean erased = this.jdbc.sql("SELECT erased_at IS NOT NULL FROM identity.account WHERE id = :id")
			.param("id", deadline.subjectId())
			.query(Boolean.class)
			.optional()
			.orElse(false);
		if (!erased) {
			return DeadlineOutcome.OBSOLETE;
		}
		this.signals.purge(deadline.subjectId());
		this.auditLog.record(AuditEntry.of(AuditActor.system(), "identity.person_signals_purged",
				AuditObject.of("identity.account", deadline.subjectId())));
		return DeadlineOutcome.FIRED;
	}

}
