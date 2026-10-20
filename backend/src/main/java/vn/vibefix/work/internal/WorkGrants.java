package vn.vibefix.work.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
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
import vn.vibefix.shared.AdminReadGrant;
import vn.vibefix.shared.AdminReadGrants;
import vn.vibefix.shared.AdminReadItem;
import vn.vibefix.shared.AdminReadScope;

/**
 * {@code work}'s side of the Administrator read-grant contract (AD-20, Story 1.3): a case module
 * pushes grants down here, and a Work Item read by an Administrator passes only when one permits it.
 */
@Component
class WorkGrants implements AdminReadGrants {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final JdbcClient jdbc;

	WorkGrants(JdbcClient jdbc) {
		this.jdbc = jdbc;
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
				INSERT INTO work.admin_read_grant (grant_id, admin_account_id, job_id, items, reason_id)
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
				UPDATE work.admin_read_grant SET ends_at = CASE WHEN ends_at IS NULL OR :at < ends_at THEN :at ELSE ends_at END
				WHERE grant_id = :grant
				""").param("grant", grantId).param("at", Timestamp.from(at)).update();
	}

	/** The Administrator's grants on the Job, open or not. */
	@Transactional(readOnly = true)
	List<AdminReadGrant> grants(UUID adminId, UUID jobId) {
		return this.jdbc.sql("""
				SELECT grant_id, items::text AS items, reason_id, ends_at FROM work.admin_read_grant
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

	/** Whether one of {@code grants} lets the Administrator read the Work Item now. */
	static boolean permits(List<AdminReadGrant> grants, UUID adminId, UUID jobId, UUID workItemId,
			Collection<UUID> involved, Instant at) {
		return grants.stream()
			.anyMatch(grant -> grant.permits(adminId, jobId, AdminReadScope.WORK_ITEMS, workItemId, involved, at));
	}

}
