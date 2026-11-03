package vn.vibefix.job.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.job.MilestoneSplits.EvidenceVersion;
import vn.vibefix.shared.AdminReadGrant;
import vn.vibefix.shared.AdminReadGrants;
import vn.vibefix.shared.AdminReadItem;
import vn.vibefix.shared.AdminReadScope;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;

/**
 * {@code job}'s side of the Administrator read-grant contract for Milestone Splits (AD-20, AD-40): a Split Resolution pushes
 * its grant down here, and one split's versions, with their edits and confirmations, are read by an Administrator only while
 * a grant names that split; each such read is audited {@code job.admin_read} (AD-8). It stores every grant pushed to
 * {@code job}, one row per grant. The rows stay on a Job's bare row until its last split is final ({@link JobPersonalData}).
 */
@Component
class SplitVersionGrants implements AdminReadGrants {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final JdbcClient jdbc;

	private final SplitStore store;

	private final AuditLog auditLog;

	private final Clock clock;

	SplitVersionGrants(JdbcClient jdbc, SplitStore store, AuditLog auditLog, Clock clock) {
		this.jdbc = jdbc;
		this.store = store;
		this.auditLog = auditLog;
		this.clock = clock;
	}

	@Override
	@Transactional
	public void grantAdminRead(UUID grantId, UUID adminId, UUID jobId, Set<AdminReadItem> items, UUID reasonId) {
		AdminReadGrant grant = AdminReadGrant.open(grantId, adminId, jobId, items, reasonId);
		List<Map<String, String>> stored = new ArrayList<>();
		for (AdminReadItem item : grant.items()) {
			stored.add(Map.of("scope", item.scope().name(), "objectId", item.objectId() == null ? "" : item.objectId().toString(),
					"accountId", item.accountId() == null ? "" : item.accountId().toString()));
		}
		this.jdbc.sql("""
				INSERT INTO job.admin_read_grant (grant_id, admin_account_id, job_id, items, reason_id)
				VALUES (:grant, :admin, :job, CAST(:items AS JSONB), :reason)
				ON CONFLICT (grant_id) DO NOTHING
				""")
			.param("grant", grantId)
			.param("admin", adminId)
			.param("job", jobId)
			.param("items", JSON.writeValueAsString(stored))
			.param("reason", reasonId)
			.update();
	}

	@Override
	@Transactional
	public void endAdminRead(UUID grantId, Instant at) {
		this.jdbc.sql("""
				UPDATE job.admin_read_grant SET ends_at = CASE WHEN ends_at IS NULL OR :at < ends_at THEN :at ELSE ends_at END
				WHERE grant_id = :grant
				""").param("grant", grantId).param("at", Timestamp.from(at)).update();
	}

	/**
	 * The split's versions, oldest first, read under a grant and audited.
	 * @throws ApiException {@code NOT_FOUND} unless an open grant of the Administrator names the split
	 */
	@Transactional
	List<EvidenceVersion> versions(UUID adminId, UUID jobId, UUID splitId) {
		Instant now = this.clock.instant();
		boolean permitted = grants(adminId, jobId).stream()
			.anyMatch(grant -> grant.permits(adminId, jobId, AdminReadScope.SPLIT_VERSIONS, splitId, List.of(), now));
		if (!permitted) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		this.auditLog.record(AuditEntry.of(AuditActor.user(adminId), "job.admin_read", AuditObject.of("job.milestone_split", splitId))
			.forJob(jobId));
		return this.store.evidence(splitId);
	}

	/** The Administrator's grants on the Job, open or not. */
	private List<AdminReadGrant> grants(UUID adminId, UUID jobId) {
		return this.jdbc.sql("""
				SELECT grant_id, items::text AS items, reason_id, ends_at FROM job.admin_read_grant
				WHERE admin_account_id = :admin AND job_id = :job
				""").param("admin", adminId).param("job", jobId).query((rs, n) -> {
			Set<AdminReadItem> items = new HashSet<>();
			for (JsonNode item : JSON.readTree(rs.getString("items"))) {
				String object = item.path("objectId").asString();
				String account = item.path("accountId").asString();
				items.add(new AdminReadItem(AdminReadScope.valueOf(item.path("scope").asString()),
						object.isEmpty() ? null : UUID.fromString(object), account.isEmpty() ? null : UUID.fromString(account)));
			}
			Timestamp ends = rs.getTimestamp("ends_at");
			return new AdminReadGrant(rs.getObject("grant_id", UUID.class), adminId, jobId, items,
					rs.getObject("reason_id", UUID.class), ends == null ? null : ends.toInstant());
		}).list();
	}

}
