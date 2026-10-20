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
 * Items assigned to it; erasing it clears it as assignee; deleting a Product deletes its Boards with
 * their Work Items. A running Job blocks erasure through {@code job}.
 */
@Component
class WorkPersonalData implements PersonalDataParticipant {

	private final JdbcClient jdbc;

	WorkPersonalData(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Set<String> tables() {
		return Set.of("work.board", "work.work_item");
	}

	@Override
	public Optional<PersonalDataSection> export(UUID accountId) {
		List<Map<String, Object>> items = this.jdbc.sql("""
				SELECT b.code, w.number, w.job_id, w.title, w.status
				FROM work.work_item w JOIN work.board b ON b.job_id = w.job_id
				WHERE w.assignee_account_id = :id ORDER BY w.job_id, w.number
				""").param("id", accountId).query((rs, n) -> {
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("jobId", rs.getObject("job_id", UUID.class).toString());
			item.put("key", rs.getString("code") + "-" + rs.getInt("number"));
			item.put("title", rs.getString("title"));
			item.put("status", rs.getString("status"));
			return item;
		}).list();
		if (items.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(new PersonalDataSection("work", Map.of("assignedWorkItems", items)));
	}

	@Override
	public void eraseAccount(UUID accountId) {
		this.jdbc.sql("UPDATE work.work_item SET assignee_account_id = NULL WHERE assignee_account_id = :id")
			.param("id", accountId)
			.update();
	}

	@Override
	public void deleteProduct(UUID productId) {
		this.jdbc.sql("DELETE FROM work.work_item WHERE job_id IN (SELECT job_id FROM work.board WHERE product_id = :id)")
			.param("id", productId)
			.update();
		this.jdbc.sql("DELETE FROM work.board WHERE product_id = :id").param("id", productId).update();
	}

	@Override
	public List<ErasureBlocker> erasureBlockers(UUID accountId) {
		return List.of();
	}

}
