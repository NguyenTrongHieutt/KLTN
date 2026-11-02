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
import vn.vibefix.notification.Todos;
import vn.vibefix.shared.ErasureBlocker;
import vn.vibefix.shared.PersonalDataParticipant;
import vn.vibefix.shared.PersonalDataSection;

/**
 * {@code repo}'s part in exports, erasure, and Product deletion (AD-24): a person exports their
 * data-deletion confirmations, their access grants, and the Revocation Checklist rows naming them after they left; erasing
 * them deletes the first two (the Audit Log keeps when each grant was made) and anonymizes the rows, whose to-dos close;
 * deleting a Product deletes its Jobs' rows and Setups, requests the deletion of
 * the platform repositories still there at once, without a ZIP, and deletes any final ZIP (FR-21, NFR-10).
 */
@Component
class RepoPersonalData implements PersonalDataParticipant {

	private final JdbcClient jdbc;

	/** Looked up on use: the deletion requests an operation, whose handlers come back to {@code repo}. */
	private final ObjectProvider<RepositoryDeletions> deletions;

	private final ObjectProvider<StoredFiles> files;

	private final ObjectProvider<Todos> todos;

	RepoPersonalData(JdbcClient jdbc, ObjectProvider<RepositoryDeletions> deletions, ObjectProvider<StoredFiles> files,
			ObjectProvider<Todos> todos) {
		this.jdbc = jdbc;
		this.deletions = deletions;
		this.files = files;
		this.todos = todos;
	}

	@Override
	public Set<String> tables() {
		return Set.of("repo.data_deletion_confirmation", "repo.job_grant", "repo.final_merge_authorization",
				"repo.revocation_item");
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
				SELECT job_id, repository_name, github_login, github_user_id, status, invited_at, accepted_at, write_at,
				       revoked_at
				FROM repo.job_grant
				WHERE account_id = :id ORDER BY created_at, id
				""").param("id", accountId).query((rs, n) -> {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("jobId", rs.getObject("job_id", UUID.class).toString());
			row.put("repository", rs.getString("repository_name"));
			row.put("githubLogin", rs.getString("github_login"));
			if (rs.getObject("github_user_id") != null) {
				row.put("githubUserId", rs.getLong("github_user_id"));
			}
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
		List<Map<String, Object>> revocations = this.jdbc.sql("""
				SELECT job_id, system, member_login, status, outcome, created_at FROM repo.revocation_item
				WHERE member_account_id = :id ORDER BY created_at, access_id
				""").param("id", accountId).query((rs, n) -> {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("jobId", rs.getObject("job_id", UUID.class).toString());
			row.put("system", rs.getString("system"));
			row.put("githubLogin", rs.getString("member_login"));
			row.put("status", rs.getString("status"));
			row.put("outcome", rs.getString("outcome"));
			row.put("at", rs.getTimestamp("created_at").toInstant().toString());
			return row;
		}).list();
		if (confirmations.isEmpty() && grants.isEmpty() && revocations.isEmpty()) {
			return Optional.empty();
		}
		Map<String, Object> data = new LinkedHashMap<>();
		if (!confirmations.isEmpty()) {
			data.put("dataDeletionConfirmations", confirmations);
		}
		if (!grants.isEmpty()) {
			data.put("repositoryGrants", grants);
		}
		if (!revocations.isEmpty()) {
			data.put("revocationItems", revocations);
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
		FinalMerges.erase(this.jdbc, accountId);
		this.jdbc.sql("""
				UPDATE repo.revocation_item SET customer_account_id = NULL, identifiers = '[]' WHERE customer_account_id = :id
				""").param("id", accountId).update();
		// R15: a leaver's rows stay, naming nobody; with no name to read, the Customer's to-dos for them close.
		this.jdbc.sql("""
				SELECT access_id, crew_row_id FROM repo.revocation_item WHERE member_account_id = :id AND status = 'PENDING'
				""").param("id", accountId).query((rs, n) -> RevocationChecklists.memberSubject(rs.getObject("access_id", UUID.class),
						rs.getObject("crew_row_id", UUID.class))).list()
			.forEach(subject -> this.todos.getObject().closeAll(RevocationChecklists.MEMBER_TODO, subject));
		this.jdbc.sql("""
				UPDATE repo.revocation_item SET member_account_id = NULL, member_login = NULL, identifiers = '[]'
				WHERE member_account_id = :id
				""").param("id", accountId).update();
	}

	@Override
	public void deleteProduct(UUID productId) {
		closeRevocationTodos(productId);
		RepositoryDeletions deletions = this.deletions.getObject();
		deletions.ofProduct(productId).forEach(pending -> deletions.request(pending, null, false));
		deletions.archivesOfProduct(productId).forEach(this.files.getObject()::delete);
		this.jdbc.sql("DELETE FROM repo.data_deletion_confirmation WHERE product_id = :id").param("id", productId).update();
		this.jdbc.sql("DELETE FROM repo.job_grant WHERE product_id = :id").param("id", productId).update();
		this.jdbc.sql("DELETE FROM repo.zip_fallback WHERE product_id = :id").param("id", productId).update();
		this.jdbc.sql("DELETE FROM repo.revocation_item WHERE product_id = :id").param("id", productId).update();
		this.jdbc.sql("DELETE FROM repo.final_merge WHERE product_id = :id").param("id", productId).update();
		this.jdbc.sql("DELETE FROM repo.final_merge_authorization WHERE product_id = :id").param("id", productId).update();
		this.jdbc.sql("DELETE FROM repo.job_setup WHERE product_id = :id").param("id", productId).update();
	}

	/** NFR-10: the final ZIPs and their ZIP Fallback rows go, with the Revocation Checklist. */
	@Override
	public void retireJobData(UUID productId) {
		closeRevocationTodos(productId);
		this.deletions.getObject().archivesOfProduct(productId).forEach(this.files.getObject()::delete);
		this.jdbc.sql("DELETE FROM repo.zip_fallback WHERE product_id = :id").param("id", productId).update();
		this.jdbc.sql("UPDATE repo.job_setup SET archive_file_id = NULL WHERE product_id = :id").param("id", productId).update();
		this.jdbc.sql("DELETE FROM repo.revocation_item WHERE product_id = :id").param("id", productId).update();
	}

	/** The to-dos of the Revocation Checklist rows about to go, which nobody could tick without their rows. */
	private void closeRevocationTodos(UUID productId) {
		this.jdbc.sql("SELECT access_id, crew_row_id FROM repo.revocation_item WHERE product_id = :id AND status = 'PENDING'")
			.param("id", productId)
			.query((rs, n) -> Map.entry(rs.getObject("access_id", UUID.class), rs.getObject("crew_row_id", UUID.class)))
			.list()
			.forEach(row -> {
				if (RevocationChecklists.JOB_END.equals(row.getValue())) {
					this.todos.getObject().closeAll(RevocationChecklists.TODO, row.getKey());
				}
				else {
					this.todos.getObject()
						.closeAll(RevocationChecklists.MEMBER_TODO, RevocationChecklists.memberSubject(row.getKey(), row.getValue()));
				}
			});
	}

	@Override
	public List<ErasureBlocker> erasureBlockers(UUID accountId) {
		return List.of();
	}

}
