package vn.vibefix.dispute.internal;

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
 * {@code dispute}'s part in exports, erasure, and Product deletion (AD-24): a person exports the Disputes
 * they opened; erasure clears them as the opener; deleting a Product deletes its Disputes.
 */
@Component
class DisputePersonalData implements PersonalDataParticipant {

	private final JdbcClient jdbc;

	DisputePersonalData(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Set<String> tables() {
		return Set.of("dispute.dispute", "dispute.decision", "dispute.execution");
	}

	@Override
	public Optional<PersonalDataSection> export(UUID accountId) {
		List<Map<String, Object>> disputes = this.jdbc.sql("""
				SELECT job_id, reason, status, opened_at FROM dispute.dispute WHERE opener_account_id = :id ORDER BY opened_at, id
				""").param("id", accountId).query((rs, n) -> {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("jobId", rs.getObject("job_id", UUID.class).toString());
			row.put("reason", rs.getString("reason"));
			row.put("status", rs.getString("status"));
			row.put("openedAt", rs.getTimestamp("opened_at").toInstant().toString());
			return row;
		}).list();
		return disputes.isEmpty() ? Optional.empty()
				: Optional.of(new PersonalDataSection("dispute", Map.of("disputesOpened", disputes)));
	}

	@Override
	public void eraseAccount(UUID accountId) {
		this.jdbc.sql("UPDATE dispute.dispute SET opener_account_id = NULL WHERE opener_account_id = :id")
			.param("id", accountId)
			.update();
		this.jdbc.sql("UPDATE dispute.decision SET admin_account_id = NULL WHERE admin_account_id = :id")
			.param("id", accountId)
			.update();
	}

	@Override
	public void deleteProduct(UUID productId) {
		for (String table : List.of("dispute.execution", "dispute.decision")) {
			this.jdbc.sql("DELETE FROM %s WHERE dispute_id IN (SELECT id FROM dispute.dispute WHERE product_id = :id)"
				.formatted(table)).param("id", productId).update();
		}
		this.jdbc.sql("DELETE FROM dispute.dispute WHERE product_id = :id").param("id", productId).update();
	}

	@Override
	public List<ErasureBlocker> erasureBlockers(UUID accountId) {
		return List.of();
	}

}
