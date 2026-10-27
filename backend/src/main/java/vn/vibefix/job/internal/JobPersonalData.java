package vn.vibefix.job.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.vibefix.files.StoredFiles;
import vn.vibefix.identity.GitHubLinkBlocker;
import vn.vibefix.identity.GitHubLinkParticipant;
import vn.vibefix.shared.ErasureBlocker;
import vn.vibefix.shared.PersonalDataParticipant;
import vn.vibefix.shared.PersonalDataSection;

/**
 * {@code job}'s part in exports, erasure, and Product deletion (AD-24): the Customer's Jobs, and the External
 * Access identifiers a Mentor or a Crew member entered (FR-66). Erasure and Product deletion delete the Jobs
 * and their screenshot files, and erasure deletes the person's identifiers; a Job from J3 to Closing blocks the
 * erasure of its Customer and its Mentor.
 * <p>
 * It also answers for the GitHub link (FR-80, R14): a role in a Job that is not Closed or Cancelled keeps it,
 * since the Job's repositories and grants know the person by their GitHub ID.
 */
@Component
class JobPersonalData implements PersonalDataParticipant, GitHubLinkParticipant {

	private final JdbcClient jdbc;

	private final StoredFiles files;

	JobPersonalData(JdbcClient jdbc, StoredFiles files) {
		this.jdbc = jdbc;
		this.files = files;
	}

	@Override
	public Set<String> tables() {
		return Set.of("job.job", "job.job_screenshot", "job.agreement_draft", "job.milestone", "job.acceptance_criterion",
				"job.external_access", "job.agreement_version", "job.crew_row", "job.crew_terms_version", "job.crew_terms_share",
				"job.crew_terms_confirmation", "job.agreement_comment", "job.agreement_change_request", "job.agreement_approval",
				"job.confirmed_agreement", "job.closure_proposal", "job.change_request", "job.cancellation_proposal",
				"job.external_access_identifier", "job.admin_read_grant");
	}

	@Override
	public Optional<PersonalDataSection> export(UUID accountId) {
		List<Map<String, Object>> jobs = this.jdbc.sql("""
				SELECT title, state, problem, expected, app_users, done_check, array_to_string(job_types, ',') AS types,
				       array_to_string(skill_names, ', ') AS skills, budget, deadline, created_at, updated_at
				FROM job.job WHERE customer_account_id = :id ORDER BY created_at, id
				""").param("id", accountId).query((rs, n) -> {
			Map<String, Object> job = new LinkedHashMap<>();
			job.put("title", rs.getString("title"));
			job.put("state", rs.getString("state"));
			job.put("problem", rs.getString("problem"));
			job.put("expected", rs.getString("expected"));
			job.put("appUsers", rs.getString("app_users"));
			job.put("doneCheck", rs.getString("done_check"));
			job.put("jobTypes", rs.getString("types"));
			job.put("skills", rs.getString("skills"));
			job.put("budget", rs.getString("budget"));
			job.put("deadline", rs.getDate("deadline") == null ? null : rs.getDate("deadline").toString());
			job.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
			job.put("updatedAt", rs.getTimestamp("updated_at").toInstant().toString());
			return job;
		}).list();
		List<Map<String, Object>> identifiers = this.jdbc.sql("""
				SELECT i.job_id, a.system, i.identifier, i.updated_at FROM job.external_access_identifier i
				JOIN job.external_access a ON a.id = i.access_id
				WHERE i.account_id = :id ORDER BY i.updated_at, i.access_id
				""").param("id", accountId).query((rs, n) -> {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("jobId", rs.getObject("job_id", UUID.class).toString());
			row.put("system", rs.getString("system"));
			row.put("identifier", rs.getString("identifier"));
			row.put("updatedAt", rs.getTimestamp("updated_at").toInstant().toString());
			return row;
		}).list();
		if (jobs.isEmpty() && identifiers.isEmpty()) {
			return Optional.empty();
		}
		Map<String, Object> data = new LinkedHashMap<>();
		if (!jobs.isEmpty()) {
			data.put("jobs", jobs);
		}
		if (!identifiers.isEmpty()) {
			data.put("externalAccessIdentifiers", identifiers);
		}
		return Optional.of(new PersonalDataSection("job", data));
	}

	@Override
	public void eraseAccount(UUID accountId) {
		this.jdbc.sql("DELETE FROM job.external_access_identifier WHERE account_id = :id").param("id", accountId).update();
		this.jdbc.sql("DELETE FROM job.admin_read_grant WHERE admin_account_id = :id").param("id", accountId).update();
		this.jdbc.sql("UPDATE job.closure_proposal SET proposer_account_id = NULL WHERE proposer_account_id = :id")
			.param("id", accountId)
			.update();
		this.jdbc.sql("UPDATE job.closure_proposal SET declined_by = NULL WHERE declined_by = :id").param("id", accountId).update();
		for (String column : List.of("proposer_account_id", "confirmed_by")) {
			this.jdbc.sql("UPDATE job.cancellation_proposal SET %s = NULL WHERE %s = :id".formatted(column, column))
				.param("id", accountId)
				.update();
		}
		for (String column : List.of("proposer_account_id", "customer_account_id", "mentor_account_id")) {
			this.jdbc.sql("UPDATE job.change_request SET %s = NULL WHERE %s = :id".formatted(column, column))
				.param("id", accountId)
				.update();
		}
		deleteJobs("customer_account_id", accountId);
	}

	@Override
	public void deleteProduct(UUID productId) {
		deleteJobs("product_id", productId);
	}

	/**
	 * NFR-10: the Job Post screenshots, the External Access identifiers, and the Agreement comments go; the
	 * Job, its Milestones, its Agreement versions, and the Crew Terms stay, as {@code payment} and
	 * {@code reputation} refer to them.
	 */
	@Override
	public void retireJobData(UUID productId) {
		this.jdbc.sql("""
				SELECT s.file_id FROM job.job_screenshot s JOIN job.job j ON j.id = s.job_id WHERE j.product_id = :id
				""").param("id", productId).query(UUID.class).list().forEach(this.files::delete);
		for (String table : List.of("job.job_screenshot", "job.external_access_identifier", "job.agreement_comment")) {
			this.jdbc.sql("DELETE FROM %s WHERE job_id IN (SELECT id FROM job.job WHERE product_id = :id)".formatted(table))
				.param("id", productId)
				.update();
		}
	}

	private void deleteJobs(String column, UUID id) {
		this.jdbc.sql("""
				SELECT s.file_id FROM job.job_screenshot s JOIN job.job j ON j.id = s.job_id WHERE j.%s = :id
				""".formatted(column)).param("id", id).query(UUID.class).list().forEach(this.files::delete);
		for (String table : List.of("job.admin_read_grant", "job.cancellation_proposal", "job.change_request",
				"job.closure_proposal",
				"job.agreement_comment",
				"job.agreement_change_request",
				"job.confirmed_agreement",
				"job.agreement_approval", "job.acceptance_criterion", "job.milestone", "job.external_access_identifier",
				"job.external_access",
				"job.agreement_version", "job.agreement_draft", "job.crew_terms_confirmation", "job.crew_terms_share",
				"job.crew_terms_version", "job.crew_row")) {
			this.jdbc.sql("DELETE FROM %s WHERE job_id IN (SELECT id FROM job.job WHERE %s = :id)".formatted(table, column))
				.param("id", id)
				.update();
		}
		this.jdbc.sql("DELETE FROM job.job WHERE %s = :id".formatted(column)).param("id", id).update();
	}

	/** A Job from J3 to Closing keeps its Customer, its Mentor, and its running Crew from being erased (AD-24). */
	@Override
	public List<ErasureBlocker> erasureBlockers(UUID accountId) {
		return this.jdbc.sql("""
				SELECT id FROM job.job WHERE (customer_account_id = :id OR mentor_account_id = :id
				    OR id IN (SELECT job_id FROM job.crew_row WHERE member_account_id = :id AND status <> 'RELEASED'))
				  AND state IN ('SELECTING', 'AGREEMENT', 'STARTING', 'ACTIVE', 'CLOSING') ORDER BY id
				""")
			.param("id", accountId)
			.query(UUID.class)
			.list()
			.stream()
			.map(job -> new ErasureBlocker("job", "JOB_RUNNING", job))
			.toList();
	}

	/** Each Job, not Closed or Cancelled, where the account is the Customer, the Mentor, or an unreleased Crew member. */
	@Override
	public List<GitHubLinkBlocker> githubLinkBlockers(UUID accountId) {
		return this.jdbc.sql("""
				SELECT id, title FROM job.job WHERE (customer_account_id = :id OR mentor_account_id = :id
				    OR id IN (SELECT job_id FROM job.crew_row WHERE member_account_id = :id AND status <> 'RELEASED'))
				  AND state NOT IN ('CLOSED', 'CANCELLED') ORDER BY created_at, id
				""")
			.param("id", accountId)
			.query((rs, n) -> new GitHubLinkBlocker("job", "JOB_ROLE", rs.getObject("id", UUID.class), rs.getString("title")))
			.list();
	}

}
