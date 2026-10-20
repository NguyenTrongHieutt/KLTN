package vn.vibefix.work.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.vibefix.files.StoredFiles;
import vn.vibefix.shared.ErasureBlocker;
import vn.vibefix.shared.PersonalDataParticipant;
import vn.vibefix.shared.PersonalDataSection;

/**
 * {@code work}'s part in exports, erasure, and Product deletion (AD-24): an account exports the Work
 * Items it created or is assigned, its history entries with their notes, the Attachments it added, and
 * the Page versions it wrote; erasing it clears it as creator, assignee, history actor, Attachment author,
 * and version author (the entries, Attachments, and versions stay, without a person), and deletes an
 * Administrator's read grants; deleting a Product deletes its Boards with their Work Items, history,
 * Attachments and their files, Pages, and grants. A running Job blocks erasure through {@code job}.
 */
@Component
class WorkPersonalData implements PersonalDataParticipant {

	private final JdbcClient jdbc;

	private final StoredFiles files;

	WorkPersonalData(JdbcClient jdbc, StoredFiles files) {
		this.jdbc = jdbc;
		this.files = files;
	}

	@Override
	public Set<String> tables() {
		return Set.of("work.board", "work.work_item", "work.work_item_history", "work.attachment", "work.admin_read_grant",
				"work.page", "work.page_version");
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
				SELECT b.code, w.number, h.action, h.changes::text AS changes, h.comment, h.at
				FROM work.work_item_history h JOIN work.work_item w ON w.id = h.work_item_id JOIN work.board b ON b.job_id = w.job_id
				WHERE h.actor_account_id = :id ORDER BY h.seq
				""").param("id", accountId).query((rs, n) -> {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("key", rs.getString("code") + "-" + rs.getInt("number"));
			entry.put("action", rs.getString("action"));
			entry.put("changes", rs.getString("changes"));
			if (rs.getString("comment") != null) {
				entry.put("comment", rs.getString("comment"));
			}
			entry.put("at", rs.getTimestamp("at").toInstant().toString());
			return entry;
		}).list();
		List<Map<String, Object>> attachments = this.jdbc.sql("""
				SELECT b.code, w.number, a.kind, a.file_name, a.url, a.label, a.added_at, a.removed_at
				FROM work.attachment a JOIN work.work_item w ON w.id = a.work_item_id JOIN work.board b ON b.job_id = w.job_id
				WHERE a.author_account_id = :id ORDER BY a.seq
				""").param("id", accountId).query((rs, n) -> {
			Map<String, Object> attachment = new LinkedHashMap<>();
			attachment.put("key", rs.getString("code") + "-" + rs.getInt("number"));
			attachment.put("kind", rs.getString("kind"));
			attachment.put("name", rs.getString("file_name") != null ? rs.getString("file_name") : rs.getString("url"));
			if (rs.getString("label") != null) {
				attachment.put("label", rs.getString("label"));
			}
			attachment.put("at", rs.getTimestamp("added_at").toInstant().toString());
			attachment.put("removed", rs.getTimestamp("removed_at") != null);
			return attachment;
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
		if (items.isEmpty() && history.isEmpty() && attachments.isEmpty() && versions.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(new PersonalDataSection("work",
				Map.of("workItems", items, "history", history, "attachments", attachments, "pageVersions", versions)));
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
		this.jdbc.sql("UPDATE work.attachment SET author_account_id = NULL WHERE author_account_id = :id")
			.param("id", accountId)
			.update();
		this.jdbc.sql("UPDATE work.attachment SET removed_by = NULL WHERE removed_by = :id")
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
		this.jdbc.sql("SELECT file_id FROM work.attachment WHERE file_id IS NOT NULL AND job_id IN (" + jobs + ")")
			.param("id", productId)
			.query(UUID.class)
			.list()
			.forEach(this.files::delete);
		this.jdbc.sql("DELETE FROM work.work_item_history WHERE job_id IN (" + jobs + ")").param("id", productId).update();
		this.jdbc.sql("DELETE FROM work.attachment WHERE job_id IN (" + jobs + ")").param("id", productId).update();
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
