package vn.vibefix.job.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.job.JobTransitioned;
import vn.vibefix.shared.PersonalData;
import vn.vibefix.shared.ProcessedEvents;
import vn.vibefix.timers.DeadlineHandler;
import vn.vibefix.timers.DeadlineKind;
import vn.vibefix.timers.DeadlineOutcome;
import vn.vibefix.timers.DeadlineRequest;
import vn.vibefix.timers.Deadlines;
import vn.vibefix.timers.DueDeadline;

/**
 * NFR-10's retention (AD-24): when a Job reaches Closed or Cancelled, {@value #KEY} is scheduled for its
 * Product 12 months later, named by that Job. A Job on the Product that leaves Draft supersedes it lazily:
 * when it fires, another Job past Draft and not ended, a later end (whose own Deadline counts), or a gone
 * Product leaves it {@code OBSOLETE}. Otherwise every participant retires the Product's Job data, the run
 * is recorded and audited, and the Product itself stays. Not pausable: it belongs to a Product, not a Job.
 * Collaborators are looked up on use.
 */
@Component
class JobRetention implements DeadlineHandler {

	static final String KEY = "job.retention";

	static final DeadlineKind KIND = new DeadlineKind(KEY, false, false);

	static final int MONTHS = 12;

	private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

	private final JdbcClient jdbc;

	private final ObjectProvider<Deadlines> deadlines;

	private final ObjectProvider<PersonalData> personalData;

	private final ObjectProvider<AuditLog> auditLog;

	private final ObjectProvider<ProcessedEvents> processedEvents;

	private final Clock clock;

	JobRetention(JdbcClient jdbc, ObjectProvider<Deadlines> deadlines, ObjectProvider<PersonalData> personalData,
			ObjectProvider<AuditLog> auditLog, ObjectProvider<ProcessedEvents> processedEvents, Clock clock) {
		this.jdbc = jdbc;
		this.deadlines = deadlines;
		this.personalData = personalData;
		this.auditLog = auditLog;
		this.processedEvents = processedEvents;
		this.clock = clock;
	}

	@Override
	public DeadlineKind kind() {
		return KIND;
	}

	@ApplicationModuleListener
	void onJobTransitioned(JobTransitioned event) {
		boolean ended = "CLOSED".equals(event.to()) || "CANCELLED".equals(event.to());
		if (ended && this.processedEvents.getObject().markProcessed("job.JobRetention.onJobTransitioned", event.eventId())) {
			schedule(event.jobId());
		}
	}

	/** The retention of the Job's Product, counted from now. */
	@Transactional
	void schedule(UUID jobId) {
		UUID product = this.jdbc.sql("SELECT product_id FROM job.job WHERE id = :id")
			.param("id", jobId)
			.query(UUID.class)
			.optional()
			.orElse(null);
		if (product == null) {
			return;
		}
		Instant due = this.clock.instant().atZone(VIETNAM).plusMonths(MONTHS).toInstant();
		this.deadlines.getObject().schedule(new DeadlineRequest(KEY, product, jobId.toString(), null, due));
	}

	@Override
	@Transactional
	public DeadlineOutcome handle(DueDeadline deadline) {
		UUID product = deadline.subjectId();
		UUID ending = UUID.fromString(deadline.generation());
		boolean running = this.jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM job.job WHERE product_id = :product AND state NOT IN ('DRAFT', 'CLOSED', 'CANCELLED'))
				""").param("product", product).query(Boolean.class).single();
		UUID last = this.jdbc.sql("""
				SELECT id FROM job.job WHERE product_id = :product AND state IN ('CLOSED', 'CANCELLED')
				ORDER BY coalesce(closed_at, cancelled_at) DESC NULLS LAST, id DESC LIMIT 1
				""").param("product", product).query(UUID.class).optional().orElse(null);
		if (running || last == null || !last.equals(ending)) {
			return DeadlineOutcome.OBSOLETE;
		}
		int recorded = this.jdbc.sql("""
				INSERT INTO job.product_retention (product_id, job_id, retired_at) VALUES (:product, :job, :now)
				ON CONFLICT DO NOTHING
				""").param("product", product).param("job", ending).param("now", Timestamp.from(this.clock.instant())).update();
		if (recorded == 0) {
			return DeadlineOutcome.OBSOLETE;
		}
		this.personalData.getObject().retireJobData(product);
		this.auditLog.getObject()
			.record(AuditEntry.of(AuditActor.system(), "job.retention_deleted", AuditObject.of("product.product", product)));
		return DeadlineOutcome.FIRED;
	}

}
