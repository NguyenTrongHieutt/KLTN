package vn.vibefix.matching.internal;

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
 * X3 (NFR-10, AD-24): the proof of a bid that ended unselected (closed, not selected, withdrawn or lapsed) is deleted
 * once its {@code proof_due_at} passes, 90 days after the decision, rows and files together, and so is an upload no
 * bid took within 90 days. Each purged bid gets an audit entry, which stands in for the erasure journal until the
 * Epic 13 story that builds it. Runs every 6 hours, off when scheduling is off.
 */
@Component
class BidProofPurge {

	static final String PURGED = "matching.bid_proof_purged";

	private final JdbcClient jdbc;

	private final BidProofs proofs;

	private final AuditLog auditLog;

	private final Clock clock;

	BidProofPurge(JdbcClient jdbc, BidProofs proofs, AuditLog auditLog, Clock clock) {
		this.jdbc = jdbc;
		this.proofs = proofs;
		this.auditLog = auditLog;
		this.clock = clock;
	}

	/** @return how many proof rows were deleted */
	@Transactional
	int purge() {
		Instant now = this.clock.instant();
		List<Due> bids = this.jdbc.sql("""
				SELECT DISTINCT a.id, a.job_id FROM matching.application a JOIN matching.bid_proof p ON p.application_id = a.id
				WHERE a.proof_due_at <= :now ORDER BY a.id
				""").param("now", Timestamp.from(now)).query(BidProofPurge::due).list();
		int deleted = 0;
		for (Due bid : bids) {
			deleted += this.proofs.deleteWhere("application_id = :application", Map.of("application", bid.id()));
			this.auditLog.record(AuditEntry.of(AuditActor.system(), PURGED, AuditObject.of("matching.application", bid.id()))
				.forJob(bid.jobId()));
		}
		List<Due> uploads = this.jdbc.sql("""
				SELECT id, job_id FROM matching.bid_proof WHERE application_id IS NULL AND added_at <= :cutoff ORDER BY id
				""").param("cutoff", Timestamp.from(now.minus(BidProofs.RETENTION))).query(BidProofPurge::due).list();
		for (Due upload : uploads) {
			deleted += this.proofs.deleteWhere("id = :id", Map.of("id", upload.id()));
			this.auditLog.record(AuditEntry.of(AuditActor.system(), PURGED, AuditObject.of("matching.bid_proof", upload.id()))
				.forJob(upload.jobId()));
		}
		return deleted;
	}

	/** A bid, or an upload no bid took, whose proof is due, with its Job. */
	private record Due(UUID id, UUID jobId) {
	}

	private static Due due(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
		return new Due(rs.getObject("id", UUID.class), rs.getObject("job_id", UUID.class));
	}

	@Component
	@ConditionalOnBooleanProperty(name = "vibefix.scheduling.enabled", matchIfMissing = true)
	static class Scheduler {

		private final BidProofPurge purge;

		Scheduler(BidProofPurge purge) {
			this.purge = purge;
		}

		@Scheduled(fixedDelayString = "PT6H", initialDelayString = "PT15M")
		void run() {
			this.purge.purge();
		}

	}

}
