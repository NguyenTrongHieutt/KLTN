package vn.vibefix.work.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.vibefix.shared.ErasureBlocker;
import vn.vibefix.shared.PersonalDataParticipant;
import vn.vibefix.shared.PersonalDataSection;

/**
 * {@code work}'s part in exports, erasure, and Product deletion (AD-24): an account exports the Work
 * Items it created or is assigned, its history entries, and the Page versions it wrote; erasing it clears
 * it as creator, assignee, history actor, and version author (the entries and versions stay, without a
 * person), and deletes an Administrator's read grants; deleting a Product deletes its Boards with their
 * Work Items, history, Pages, and grants. A running Job blocks erasure through {@code job}.
 */
@Component
class WorkPersonalData implements PersonalDataParticipant {

	private final JdbcClient jdbc;

	WorkPersonalData(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Set<String> tables() {
		return Set.of("work.board", "work.work_item", "work.work_item_history", "work.admin_read_grant", "work.page",
				"work.page_version");
	}

	@Override
	public Optional<PersonalDataSection> export(UUID accountId) {
		List<Map<String, Object>> items = this.jdbc.sql("""
				SELECT b.code, w.number, w.job_id, w.title, w.status, w.created_by_account_id = :id AS created
				FROM work.work_item w JOIN work.board b ON b.job_id = w.job_id
				WHERE w.assignee_account_id = :id OR w.created_by_account_id = :id ORDER BY w.job_id, w.number
				""").param("id", accountId).query((rs, n) -> {
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("jobId", rs.getObject("job_id", UUID.class).toString());
			item.put("key", rs.getString("code") + "-" + rs.getInt("number"));
			item.put("title", rs.getString("title"));
			item.put("status", rs.getString("status"));
			item.put("created", rs.getBoolean("created"));
			return item;
		}).list();
		List<Map<String, Object>> history = this.jdbc.sql("""
				SELECT b.code, w.number, h.action, h.changes::text AS changes, h.at
				FROM work.work_item_history h JOIN work.work_item w ON w.id = h.work_item_id JOIN work.board b ON b.job_id = w.job_id
				WHERE h.actor_account_id = :id ORDER BY h.seq
				""").param("id", accountId).query((rs, n) -> {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("key", rs.getString("code") + "-" + rs.getInt("number"));
			entry.put("action", rs.getString("action"));
			entry.put("changes", rs.getString("changes"));
			entry.put("at", rs.getTimestamp("at").toInstant().toString());
			return entry;
		}).list();
		List<Map<String, Object>> versions = this.jdbc.sql("""
				SELECT v.page_id, v.version_no, v.title, v.body, v.created_at FROM work.page_version v
				WHERE v.author_account_id = :id ORDER BY v.created_at, v.page_id, v.version_no
				""").param("id", accountId).query((rs, n) -> {
			Map<String, Object> version = new LinkedHashMap<>();
			version.put("pageId", rs.getObject("page_id", UUID.class).toString());
			version.put("versionNo", rs.getInt("version_no"));
			version.put("title", rs.getString("title"));
			version.put("body", rs.getString("body"));
			version.put("at", rs.getTimestamp("created_at").toInstant().toString());
			return version;
		}).list();
		if (items.isEmpty() && history.isEmpty() && versions.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(new PersonalDataSection("work",
				Map.of("workItems", items, "history", history, "pageVersions", versions)));
	}

	@Override
	public void eraseAccount(UUID accountId) {
		this.jdbc.sql("UPDATE work.work_item SET assignee_account_id = NULL WHERE assignee_account_id = :id")
			.param("id", accountId)
			.update();
		this.jdbc.sql("UPDATE work.work_item SET created_by_account_id = NULL WHERE created_by_account_id = :id")
			.param("id", accountId)
			.update();
		this.jdbc.sql("UPDATE work.work_item_history SET actor_account_id = NULL WHERE actor_account_id = :id")
			.param("id", accountId)
			.update();
		this.jdbc.sql("UPDATE work.work_item_history SET assigned_to = NULL WHERE assigned_to = :id")
			.param("id", accountId)
			.update();
		this.jdbc.sql("UPDATE work.page_version SET author_account_id = NULL WHERE author_account_id = :id")
			.param("id", accountId)
			.update();
		this.jdbc.sql("UPDATE work.page SET created_by_account_id = NULL WHERE created_by_account_id = :id")
			.param("id", accountId)
			.update();
		this.jdbc.sql("DELETE FROM work.admin_read_grant WHERE admin_account_id = :id").param("id", accountId).update();
	}

	@Override
	public void deleteProduct(UUID productId) {
		String jobs = "SELECT job_id FROM work.board WHERE product_id = :id";
		this.jdbc.sql("DELETE FROM work.work_item_history WHERE job_id IN (" + jobs + ")").param("id", productId).update();
		this.jdbc.sql("DELETE FROM work.work_item WHERE job_id IN (" + jobs + ")").param("id", productId).update();
		this.jdbc.sql("DELETE FROM work.admin_read_grant WHERE job_id IN (" + jobs + ")").param("id", productId).update();
		this.jdbc.sql("DELETE FROM work.page_version WHERE page_id IN (SELECT id FROM work.page WHERE job_id IN (" + jobs + "))")
			.param("id", productId)
			.update();
		this.jdbc.sql("UPDATE work.page SET parent_id = NULL WHERE job_id IN (" + jobs + ")").param("id", productId).update();
		this.jdbc.sql("DELETE FROM work.page WHERE job_id IN (" + jobs + ")").param("id", productId).update();
		this.jdbc.sql("DELETE FROM work.board WHERE product_id = :id").param("id", productId).update();
	}

	@Override
	public List<ErasureBlocker> erasureBlockers(UUID accountId) {
		return List.of();
	}

}
