package vn.vibefix.delivery.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.job.JobTransitioned;
import vn.vibefix.job.MilestoneHandovers;
import vn.vibefix.notification.Todos;
import vn.vibefix.repo.HandoverBranches;
import vn.vibefix.repo.WorkingHeadMoved;
import vn.vibefix.shared.ProcessedEvents;

/**
 * Voiding a Code Job's handover once its branch moves (FR-32, AD-11, AD-12, AD-28). A new Working head
 * that {@code repo} read back, or the 10-minute look at the head it recorded last, requests M3 for the
 * Job's {@code SUBMITTED} handover when that head is not its Handover Commit. M3 refused because the Job
 * is busy or frozen changes nothing, and the next look asks again. M3's consequence here: the handover is
 * {@code VOIDED} and the Customer's review to-do closes; {@link DeliveryListeners} reopens the Mentor's
 * {@code delivery.hand_over}, and Auto-acceptance and its reminders end through their generation check.
 */
@Component
class HandoverVoids {

	private static final Logger log = LoggerFactory.getLogger(HandoverVoids.class);

	/** The Job's handover waiting for the Customer, with its pinned commit. */
	private record Submitted(UUID id, UUID jobId, UUID milestoneId, String commit) {
	}

	private final JdbcClient jdbc;

	private final MilestoneHandovers jobs;

	private final HandoverBranches branches;

	private final Todos todos;

	private final AuditLog auditLog;

	private final ProcessedEvents processedEvents;

	private final Clock clock;

	HandoverVoids(JdbcClient jdbc, MilestoneHandovers jobs, HandoverBranches branches, Todos todos, AuditLog auditLog,
			ProcessedEvents processedEvents, Clock clock) {
		this.jdbc = jdbc;
		this.jobs = jobs;
		this.branches = branches;
		this.todos = todos;
		this.auditLog = auditLog;
		this.processedEvents = processedEvents;
		this.clock = clock;
	}

	@ApplicationModuleListener
	void onWorkingHeadMoved(WorkingHeadMoved event) {
		moved(event);
	}

	/** What the head listener does, in the caller's transaction. Replay-safe. */
	@Transactional
	void moved(WorkingHeadMoved event) {
		if (!this.processedEvents.markProcessed("delivery.HandoverVoids.onWorkingHeadMoved", event.eventId())) {
			return;
		}
		voidIfMoved(event.jobId(), event.sha());
	}

	/** Every submitted Code handover against the head {@code repo} recorded last; GitHub is not read. */
	@Transactional
	void sweep() {
		for (Submitted handover : this.jdbc.sql("""
				SELECT id, job_id, milestone_id, handover_commit FROM delivery.handover
				WHERE status = 'SUBMITTED' AND handover_commit IS NOT NULL ORDER BY created_at, id
				""").query((rs, n) -> new Submitted(rs.getObject("id", UUID.class), rs.getObject("job_id", UUID.class),
						rs.getObject("milestone_id", UUID.class), rs.getString("handover_commit"))).list()) {
			this.branches.recordedHead(handover.jobId())
				.filter(head -> !head.equals(handover.commit()))
				.ifPresent(head -> request(handover));
		}
	}

	private void voidIfMoved(UUID jobId, String head) {
		this.jdbc.sql("""
				SELECT id, job_id, milestone_id, handover_commit FROM delivery.handover
				WHERE job_id = :job AND status = 'SUBMITTED' AND handover_commit IS NOT NULL
				""").param("job", jobId).query((rs, n) -> new Submitted(rs.getObject("id", UUID.class),
						rs.getObject("job_id", UUID.class), rs.getObject("milestone_id", UUID.class), rs.getString("handover_commit")))
			.list()
			.stream()
			.filter(handover -> !handover.commit().equals(head))
			.forEach(this::request);
	}

	private void request(Submitted handover) {
		if (!this.jobs.voidHandover(handover.jobId(), handover.milestoneId(), handover.id())) {
			log.info("Handover {} of Job {} moved but was not voided now; the next look asks again", handover.id(),
					handover.jobId());
		}
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

	@Component
	@ConditionalOnBooleanProperty(name = "vibefix.scheduling.enabled", matchIfMissing = true)
	static class Sweep {

		private final HandoverVoids voids;

		Sweep(HandoverVoids voids) {
			this.voids = voids;
		}

		@Scheduled(fixedDelayString = "${vibefix.delivery.void-sweep:PT10M}", initialDelayString = "PT4M")
		void run() {
			this.voids.sweep();
		}

	}

}
