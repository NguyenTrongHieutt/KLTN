package vn.vibefix.reputation.internal;

import java.sql.Timestamp;
import java.time.Clock;

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

/**
 * {@value #KEY} (FR-78, AD-24): private feedback is deleted 24 months after its Rating Window
 * {@code [ASSUMPTION]}, the deletion audited; the erasure journal records it once it exists.
 */
@Component
class FeedbackPurge implements DeadlineHandler {

	static final String KEY = "reputation.private_feedback_purge";

	static final DeadlineKind KIND = new DeadlineKind(KEY, false, true);

	private final JdbcClient jdbc;

	private final AuditLog auditLog;

	private final Clock clock;

	FeedbackPurge(JdbcClient jdbc, AuditLog auditLog, Clock clock) {
		this.jdbc = jdbc;
		this.auditLog = auditLog;
		this.clock = clock;
	}

	@Override
	public DeadlineKind kind() {
		return KIND;
	}

	@Override
	public DeadlineOutcome handle(DueDeadline deadline) {
		int deleted = this.jdbc.sql("""
				DELETE FROM reputation.private_feedback WHERE job_id = :job AND delete_after <= :now
				""").param("job", deadline.subjectId()).param("now", Timestamp.from(this.clock.instant())).update();
		if (deleted > 0) {
			this.auditLog.record(AuditEntry.of(AuditActor.system(), "reputation.private_feedback_purged",
					AuditObject.of("job.job", deadline.subjectId())).forJob(deadline.subjectId()));
		}
		return DeadlineOutcome.FIRED;
	}

}
