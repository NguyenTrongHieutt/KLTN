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
import vn.vibefix.shared.ErasureBlocker;
import vn.vibefix.shared.PersonalDataParticipant;
import vn.vibefix.shared.PersonalDataSection;

/**
 * {@code job}'s part in exports, erasure, and Product deletion (AD-24): the Customer's Jobs, which are
 * Drafts until Story 3.2. Erasure and Product deletion delete the Jobs and their screenshot files;
 * running Jobs will block erasure once the lifecycle exists.
 */
@Component
class JobPersonalData implements PersonalDataParticipant {

	private final JdbcClient jdbc;

	private final StoredFiles files;

	JobPersonalData(JdbcClient jdbc, StoredFiles files) {
		this.jdbc = jdbc;
		this.files = files;
	}

	@Override
	public Set<String> tables() {
		return Set.of("job.job", "job.job_screenshot");
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
		return jobs.isEmpty() ? Optional.empty() : Optional.of(new PersonalDataSection("job", Map.of("jobs", jobs)));
	}

	@Override
	public void eraseAccount(UUID accountId) {
		deleteJobs("customer_account_id", accountId);
	}

	@Override
	public void deleteProduct(UUID productId) {
		deleteJobs("product_id", productId);
	}

	private void deleteJobs(String column, UUID id) {
		this.jdbc.sql("""
				SELECT s.file_id FROM job.job_screenshot s JOIN job.job j ON j.id = s.job_id WHERE j.%s = :id
				""".formatted(column)).param("id", id).query(UUID.class).list().forEach(this.files::delete);
		this.jdbc.sql("DELETE FROM job.job WHERE %s = :id".formatted(column)).param("id", id).update();
	}

	@Override
	public List<ErasureBlocker> erasureBlockers(UUID accountId) {
		return List.of();
	}

}
