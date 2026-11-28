package vn.vibefix.job.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.notification.Todos;

/**
 * Ends a Self-mentored Job's open cancellation proposal when the Milestone it was about stops being held (AD-47
 * exception 6, Story 25.7): the members agreed, or were asked to agree, to a cancellation that refunds a Held Milestone
 * in full, so once that Milestone is accepted (M5) or settled (M8) their answers are about another state of the money
 * and must not apply. The proposal ends {@code WITHDRAWN}, by the System, and its to-dos close; the Customer may propose
 * again, which then cancels at once if nothing is held. It runs in the transition's apply, under the Job row lock, and
 * depends on nothing of the lifecycle, so a step may call it.
 */
@Component
class CancellationMoot {

	private final JdbcClient jdbc;

	private final Todos todos;

	private final AuditLog auditLog;

	private final Clock clock;

	CancellationMoot(JdbcClient jdbc, Todos todos, AuditLog auditLog, Clock clock) {
		this.jdbc = jdbc;
		this.todos = todos;
		this.auditLog = auditLog;
		this.clock = clock;
	}

	/** Nothing in a normal Job, whose proposal is the other side's to confirm on the Job version it loads. */
	void heldChanged(Jobs.Job job) {
		if (!job.selfMentored()) {
			return;
		}
		List<UUID> ended = this.jdbc.sql("""
				UPDATE job.cancellation_proposal SET status = 'WITHDRAWN', decided_at = :now
				WHERE job_id = :job AND status = 'OPEN' RETURNING id
				""").param("now", Timestamp.from(this.clock.instant())).param("job", job.id()).query(UUID.class).list();
		for (UUID proposal : ended) {
			this.todos.closeAll(Cancellations.ANSWER, proposal);
			this.auditLog.record(AuditEntry.of(AuditActor.system(), "job.cancellation_moot",
					AuditObject.of("job.cancellation_proposal", proposal)).forJob(job.id()));
		}
	}

}
