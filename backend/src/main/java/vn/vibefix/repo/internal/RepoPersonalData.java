package vn.vibefix.repo.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.vibefix.files.StoredFiles;
import vn.vibefix.shared.ErasureBlocker;
import vn.vibefix.shared.PersonalDataParticipant;
import vn.vibefix.shared.PersonalDataSection;

/**
 * {@code repo}'s part in exports, erasure, and Product deletion (AD-24): a person exports their
 * data-deletion confirmations and their access grants; erasing them deletes those rows (the Audit Log keeps
 * when each grant was made); deleting a Product deletes its Jobs' rows and Setups, requests the deletion of
 * the platform repositories still there at once, without a ZIP, and deletes any final ZIP (FR-21, NFR-10).
 */
@Component
class RepoPersonalData implements PersonalDataParticipant {

	private final JdbcClient jdbc;

	/** Looked up on use: the deletion requests an operation, whose handlers come back to {@code repo}. */
	private final ObjectProvider<RepositoryDeletions> deletions;

	private final ObjectProvider<StoredFiles> files;

	RepoPersonalData(JdbcClient jdbc, ObjectProvider<RepositoryDeletions> deletions, ObjectProvider<StoredFiles> files) {
		this.jdbc = jdbc;
		this.deletions = deletions;
		this.files = files;
	}

	@Override
	public Set<String> tables() {
		return Set.of("repo.data_deletion_confirmation", "repo.job_grant", "repo.final_merge_authorization");
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
		List<Map<String, Object>> grants = this.jdbc.sql("""
				SELECT job_id, repository_name, github_login, status, invited_at, accepted_at, write_at, revoked_at
				FROM repo.job_grant
				WHERE account_id = :id ORDER BY created_at, id
				""").param("id", accountId).query((rs, n) -> {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("jobId", rs.getObject("job_id", UUID.class).toString());
			row.put("repository", rs.getString("repository_name"));
			row.put("githubLogin", rs.getString("github_login"));
			row.put("status", rs.getString("status"));
			Map<String, String> times = Map.of("invitedAt", "invited_at", "acceptedAt", "accepted_at", "writeAt", "write_at",
					"revokedAt", "revoked_at");
			times.forEach((name, column) -> {
				try {
					java.sql.Timestamp at = rs.getTimestamp(column);
					if (at != null) {
						row.put(name, at.toInstant().toString());
					}
				}
				catch (java.sql.SQLException ex) {
					throw new IllegalStateException(ex);
				}
			});
			return row;
		}).list();
		if (confirmations.isEmpty() && grants.isEmpty()) {
			return Optional.empty();
		}
		Map<String, Object> data = new LinkedHashMap<>();
		if (!confirmations.isEmpty()) {
			data.put("dataDeletionConfirmations", confirmations);
		}
		if (!grants.isEmpty()) {
			data.put("repositoryGrants", grants);
		}
		return Optional.of(new PersonalDataSection("repo", data));
	}

	@Override
	public void eraseAccount(UUID accountId) {
		this.jdbc.sql("DELETE FROM repo.data_deletion_confirmation WHERE account_id = :id").param("id", accountId).update();
		this.jdbc.sql("DELETE FROM repo.job_grant WHERE account_id = :id").param("id", accountId).update();
		this.jdbc.sql("UPDATE repo.final_merge_authorization SET customer_account_id = NULL WHERE customer_account_id = :id")
			.param("id", accountId)
			.update();
	}

	@Override
	public void deleteProduct(UUID productId) {
		RepositoryDeletions deletions = this.deletions.getObject();
		deletions.ofProduct(productId).forEach(pending -> deletions.request(pending, null, false));
		deletions.archivesOfProduct(productId).forEach(this.files.getObject()::delete);
		this.jdbc.sql("DELETE FROM repo.data_deletion_confirmation WHERE product_id = :id").param("id", productId).update();
		this.jdbc.sql("DELETE FROM repo.job_grant WHERE product_id = :id").param("id", productId).update();
		this.jdbc.sql("DELETE FROM repo.final_merge_authorization WHERE product_id = :id").param("id", productId).update();
		this.jdbc.sql("DELETE FROM repo.job_setup WHERE product_id = :id").param("id", productId).update();
	}

	@Override
	public List<ErasureBlocker> erasureBlockers(UUID accountId) {
		return List.of();
	}

}
