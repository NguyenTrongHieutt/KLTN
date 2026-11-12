package vn.vibefix.delivery.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.job.JobTransitioned;
import vn.vibefix.notification.Todos;
import vn.vibefix.shared.ProcessedEvents;

/**
 * M3's consequence in {@code delivery} (FR-33, AD-11, AD-28): the handover is {@code VOIDED} and the Customer's
 * review to-do closes. Heads are compared only at acceptance (D9): {@link AcceptanceMerges} applies M3 when the
 * acceptance merge answers 409, and a push to the Working Repository voids nothing. {@link DeliveryListeners}
 * reopens the Mentor's {@code delivery.hand_over}, {@link HandoverPullCloses} closes the handover's pull
 * request, and Auto-acceptance and its reminders end through their generation check.
 */
@Component
class HandoverVoids {

	private final JdbcClient jdbc;

	private final Todos todos;

	private final AuditLog auditLog;

	private final ProcessedEvents processedEvents;

	private final Clock clock;

	HandoverVoids(JdbcClient jdbc, Todos todos, AuditLog auditLog, ProcessedEvents processedEvents, Clock clock) {
		this.jdbc = jdbc;
		this.todos = todos;
		this.auditLog = auditLog;
		this.processedEvents = processedEvents;
		this.clock = clock;
	}

	@ApplicationModuleListener
	void onJobTransitioned(JobTransitioned event) {
		voided(event);
	}

	/** M3's consequence, in the caller's transaction. Replay-safe. */
	@Transactional
	void voided(JobTransitioned event) {
		String handover = event.facts().get("handoverId");
		if (!"M3".equals(event.transition()) || handover == null
				|| !this.processedEvents.markProcessed("delivery.HandoverVoids.onJobTransitioned", event.eventId())) {
			return;
		}
		UUID id = UUID.fromString(handover);
		int updated = this.jdbc.sql("""
				UPDATE delivery.handover SET status = 'VOIDED', voided_at = :now WHERE id = :id AND status = 'SUBMITTED'
				""").param("now", Timestamp.from(this.clock.instant())).param("id", id).update();
		if (updated == 0) {
			return;
		}
		this.todos.closeAll(Handovers.REVIEW_HANDOVER, id);
		this.auditLog.record(AuditEntry.of(AuditActor.system(), "delivery.handover_voided", AuditObject.of("delivery.handover", id))
			.forJob(event.jobId()));
	}

}
