package vn.vibefix.repo.internal;

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
 * {@code repo}'s part in exports, erasure, and Product deletion (AD-24): a person exports their
 * data-deletion confirmations; erasing them deletes those rows; deleting a Product deletes its Jobs'.
 */
@Component
class RepoPersonalData implements PersonalDataParticipant {

	private final JdbcClient jdbc;

	RepoPersonalData(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Set<String> tables() {
		return Set.of("repo.data_deletion_confirmation");
	}

	@Override
	public Optional<PersonalDataSection> export(UUID accountId) {
		List<Map<String, Object>> confirmations = this.jdbc.sql("""
				SELECT job_id, item, confirmed_at FROM repo.data_deletion_confirmation WHERE account_id = :id
				ORDER BY confirmed_at, item
				""").param("id", accountId).query((rs, n) -> {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("jobId", rs.getObject("job_id", UUID.class).toString());
			row.put("item", rs.getString("item"));
			row.put("at", rs.getTimestamp("confirmed_at").toInstant().toString());
			return row;
		}).list();
		return confirmations.isEmpty() ? Optional.empty()
				: Optional.of(new PersonalDataSection("repo", Map.of("dataDeletionConfirmations", confirmations)));
	}

	@Override
	public void eraseAccount(UUID accountId) {
		this.jdbc.sql("DELETE FROM repo.data_deletion_confirmation WHERE account_id = :id").param("id", accountId).update();
	}

	@Override
	public void deleteProduct(UUID productId) {
		this.jdbc.sql("DELETE FROM repo.data_deletion_confirmation WHERE product_id = :id").param("id", productId).update();
	}

	@Override
	public List<ErasureBlocker> erasureBlockers(UUID accountId) {
		return List.of();
	}

}
