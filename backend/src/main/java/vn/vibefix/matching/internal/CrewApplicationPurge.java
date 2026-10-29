package vn.vibefix.matching.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;

/**
 * X3 (NFR-10, AD-24, Story 21.4): a Crew Application that ended unpicked (declined, lapsed or withdrawn) is deleted
 * with its proof, rows and files together, once its {@code purge_due_at} passes, 90 days after the decision; so is a
 * proof upload no application took within 90 days. Each deletion gets an audit entry, which stands in for the erasure
 * journal until the Epic 13 story that builds it. Runs every 6 hours, off when scheduling is off.
 */
@Component
class CrewApplicationPurge {

	static final String PURGED = "matching.crew_application_purged";

	private final JdbcClient jdbc;

	private final CrewApplicationProofs proofs;

	private final AuditLog auditLog;

	private final Clock clock;

	CrewApplicationPurge(JdbcClient jdbc, CrewApplicationProofs proofs, AuditLog auditLog, Clock clock) {
		this.jdbc = jdbc;
		this.proofs = proofs;
		this.auditLog = auditLog;
		this.clock = clock;
	}

	/** @return how many applications and waiting uploads were deleted */
	@Transactional
	int purge() {
		Instant now = this.clock.instant();
		List<Due> applications = this.jdbc.sql("""
				SELECT id, job_id FROM matching.crew_application WHERE purge_due_at <= :now ORDER BY id
				""").param("now", Timestamp.from(now)).query(CrewApplicationPurge::due).list();
		int deleted = 0;
		for (Due application : applications) {
			this.proofs.deleteWhere("crew_application_id = :application", Map.of("application", application.id()));
			deleted += this.jdbc.sql("DELETE FROM matching.crew_application WHERE id = :id")
				.param("id", application.id())
				.update();
			this.auditLog.record(AuditEntry.of(AuditActor.system(), PURGED,
					AuditObject.of("matching.crew_application", application.id())).forJob(application.jobId()));
		}
		List<Due> uploads = this.jdbc.sql("""
				SELECT id, job_id FROM matching.crew_application_proof
				WHERE crew_application_id IS NULL AND added_at <= :cutoff ORDER BY id
				""").param("cutoff", Timestamp.from(now.minus(BidProofs.RETENTION))).query(CrewApplicationPurge::due).list();
		for (Due upload : uploads) {
			deleted += this.proofs.deleteWhere("id = :id", Map.of("id", upload.id()));
			this.auditLog.record(AuditEntry.of(AuditActor.system(), PURGED,
					AuditObject.of("matching.crew_application_proof", upload.id())).forJob(upload.jobId()));
		}
		return deleted;
	}

	/** An application, or an upload no application took, that is due, with its Job. */
	private record Due(UUID id, UUID jobId) {
	}

	private static Due due(ResultSet rs, int n) throws SQLException {
		return new Due(rs.getObject("id", UUID.class), rs.getObject("job_id", UUID.class));
	}

	@Component
	@ConditionalOnBooleanProperty(name = "vibefix.scheduling.enabled", matchIfMissing = true)
	static class Scheduler {

		private final CrewApplicationPurge purge;

		Scheduler(CrewApplicationPurge purge) {
			this.purge = purge;
		}

		@Scheduled(fixedDelayString = "PT6H", initialDelayString = "PT20M")
		void run() {
			this.purge.purge();
		}

	}

}
