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
 * Items it created or is assigned and its history entries; erasing it clears it as creator, assignee,
 * and history actor (the entries stay, without a person), and deletes an Administrator's read grants;
 * deleting a Product deletes its Boards with their Work Items, history, and grants. A running Job
 * blocks erasure through {@code job}.
 */
@Component
class WorkPersonalData implements PersonalDataParticipant {

	private final JdbcClient jdbc;

	WorkPersonalData(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Set<String> tables() {
		return Set.of("work.board", "work.work_item", "work.work_item_history", "work.admin_read_grant");
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
		if (items.isEmpty() && history.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(new PersonalDataSection("work", Map.of("workItems", items, "history", history)));
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
		this.jdbc.sql("DELETE FROM work.admin_read_grant WHERE admin_account_id = :id").param("id", accountId).update();
	}

	@Override
	public void deleteProduct(UUID productId) {
		String jobs = "SELECT job_id FROM work.board WHERE product_id = :id";
		this.jdbc.sql("DELETE FROM work.work_item_history WHERE job_id IN (" + jobs + ")").param("id", productId).update();
		this.jdbc.sql("DELETE FROM work.work_item WHERE job_id IN (" + jobs + ")").param("id", productId).update();
		this.jdbc.sql("DELETE FROM work.admin_read_grant WHERE job_id IN (" + jobs + ")").param("id", productId).update();
		this.jdbc.sql("DELETE FROM work.board WHERE product_id = :id").param("id", productId).update();
	}

	@Override
	public List<ErasureBlocker> erasureBlockers(UUID accountId) {
		return List.of();
	}

}
