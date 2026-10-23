package vn.vibefix.admin.internal;

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
 * {@code admin}'s part in exports, erasure, and Product deletion (AD-24): a person exports the Abuse Reports
 * they filed; erasure clears them as the reporter, the reported author, the assignee, or the decider, and a
 * reported user's name; deleting a Product deletes the reports on its content.
 */
@Component
class AdminPersonalData implements PersonalDataParticipant {

	private final JdbcClient jdbc;

	AdminPersonalData(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Set<String> tables() {
		return Set.of("admin.abuse_report");
	}

	@Override
	public Optional<PersonalDataSection> export(UUID accountId) {
		List<Map<String, Object>> reports = this.jdbc.sql("""
				SELECT kind, label, reason, status, outcome, created_at FROM admin.abuse_report WHERE reporter_account_id = :id
				ORDER BY created_at, id
				""").param("id", accountId).query((rs, n) -> {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("kind", rs.getString("kind"));
			row.put("label", rs.getString("label"));
			row.put("reason", rs.getString("reason"));
			row.put("status", rs.getString("status"));
			row.put("outcome", rs.getString("outcome"));
			row.put("filedAt", rs.getTimestamp("created_at").toInstant().toString());
			return row;
		}).list();
		return reports.isEmpty() ? Optional.empty()
				: Optional.of(new PersonalDataSection("admin", Map.of("abuseReportsFiled", reports)));
	}

	@Override
	public void eraseAccount(UUID accountId) {
		this.jdbc.sql("UPDATE admin.abuse_report SET label = '—' WHERE kind = 'USER' AND author_account_id = :id")
			.param("id", accountId)
			.update();
		for (String column : List.of("reporter_account_id", "author_account_id", "assigned_admin_id", "decided_by_admin_id")) {
			this.jdbc.sql("UPDATE admin.abuse_report SET %s = NULL WHERE %s = :id".formatted(column, column))
				.param("id", accountId)
				.update();
		}
	}

	@Override
	public void deleteProduct(UUID productId) {
		this.jdbc.sql("DELETE FROM admin.abuse_report WHERE product_id = :id").param("id", productId).update();
	}

	@Override
	public List<ErasureBlocker> erasureBlockers(UUID accountId) {
		return List.of();
	}

}
