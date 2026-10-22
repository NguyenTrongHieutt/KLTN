package vn.vibefix.job.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.shared.AdminReadGrant;
import vn.vibefix.shared.AdminReadGrants;
import vn.vibefix.shared.AdminReadItem;
import vn.vibefix.shared.AdminReadScope;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;

/**
 * {@code job}'s side of the Administrator read-grant contract (AD-20, AD-40): an escalated Team Issue pushes
 * its grant down here, and the Crew Terms versions are read by an Administrator only while one covers
 * {@code CREW_TERMS} on the Job; each such read is audited {@code job.admin_read} (AD-8).
 */
@Component
class CrewTermsGrants implements AdminReadGrants {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** One party's share in a version, as the Team Evidence Package shows it. */
	record Share(UUID accountId, int percent, @Nullable Instant confirmedAt) {
	}

	/** One Crew Terms version with its shares. */
	record Version(int versionNo, String status, Instant createdAt, List<Share> shares) {
	}

	private final JdbcClient jdbc;

	private final AuditLog auditLog;

	private final Clock clock;

	CrewTermsGrants(JdbcClient jdbc, AuditLog auditLog, Clock clock) {
		this.jdbc = jdbc;
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
	 * The Job's Crew Terms versions with every party's share, oldest first, read under a grant and audited.
	 * @throws ApiException {@code NOT_FOUND} unless an open grant of the Administrator covers them
	 */
	@Transactional
	List<Version> crewTerms(UUID adminId, UUID jobId) {
		Instant now = this.clock.instant();
		boolean permitted = grants(adminId, jobId).stream()
			.anyMatch(grant -> grant.permits(adminId, jobId, AdminReadScope.CREW_TERMS, jobId, List.of(), now));
		if (!permitted) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		Map<Integer, Version> versions = new LinkedHashMap<>();
		this.jdbc.sql("SELECT version_no, status, created_at FROM job.crew_terms_version WHERE job_id = :job ORDER BY version_no")
			.param("job", jobId)
			.query(rs -> {
				versions.put(rs.getInt("version_no"), new Version(rs.getInt("version_no"), rs.getString("status"),
						rs.getTimestamp("created_at").toInstant(), new ArrayList<>()));
			});
		this.jdbc.sql("""
				SELECT s.version_no, s.party_account_id, s.percent, c.confirmed_at FROM job.crew_terms_share s
				LEFT JOIN job.crew_terms_confirmation c
				  ON c.job_id = s.job_id AND c.version_no = s.version_no AND c.crew_row_id = s.crew_row_id
				WHERE s.job_id = :job ORDER BY s.version_no, s.percent DESC, s.party_account_id
				""").param("job", jobId).query(rs -> {
			Timestamp confirmed = rs.getTimestamp("confirmed_at");
			Version version = versions.get(rs.getInt("version_no"));
			if (version != null) {
				version.shares()
					.add(new Share(rs.getObject("party_account_id", UUID.class), rs.getInt("percent"),
							confirmed == null ? null : confirmed.toInstant()));
			}
		});
		this.auditLog.record(AuditEntry.of(AuditActor.user(adminId), "job.admin_read", AuditObject.of("job.crew_terms_version", jobId))
			.forJob(jobId));
		return List.copyOf(versions.values());
	}

	/** The Administrator's grants on the Job, open or not. */
	@Transactional(readOnly = true)
	List<AdminReadGrant> grants(UUID adminId, UUID jobId) {
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
