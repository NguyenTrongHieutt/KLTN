package vn.vibefix.job.internal;

import java.sql.Date;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.job.JobPosts;
import vn.vibefix.job.internal.Jobs.Job;

/**
 * Open Job Posts as approved Mentors browse them (FR-12, AD-38): Open and not hidden, newest first,
 * 20 a page, narrowed by Job Type, Skills, budget range, and deadline. Every filter given applies at
 * once.
 */
@Component
class JobPostQueries implements JobPosts {

	static final int PAGE_SIZE = 20;

	private static final String VISIBLE = "state = 'OPEN' AND hidden_at IS NULL";

	/**
	 * What MEN-2 narrows by; an empty set or {@code null} leaves its filter out.
	 * @param skillIds the Skills to match any of, or {@code null} without a Skill filter; an empty set
	 * matches nothing
	 * @param dueBy the last deadline kept
	 */
	record Filter(Collection<JobType> types, @Nullable Collection<UUID> skillIds, Collection<Budget> budgets,
			@Nullable LocalDate dueBy) {
	}

	record Page(List<Job> items, int page, long total) {
	}

	private final JdbcClient jdbc;

	JobPostQueries(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional(readOnly = true)
	Page list(Filter filter, int page) {
		if (filter.skillIds() != null && filter.skillIds().isEmpty()) {
			return new Page(List.of(), page, 0);
		}
		StringBuilder where = new StringBuilder(VISIBLE);
		Map<String, Object> params = new HashMap<>();
		if (!filter.types().isEmpty()) {
			where.append(" AND job_types && CAST(:types AS TEXT[])");
			params.put("types", filter.types().stream().map(Enum::name).toArray(String[]::new));
		}
		if (filter.skillIds() != null) {
			where.append(" AND skill_ids && CAST(:skills AS UUID[])");
			params.put("skills", filter.skillIds().toArray(UUID[]::new));
		}
		if (!filter.budgets().isEmpty()) {
			where.append(" AND budget IN (:budgets)");
			params.put("budgets", filter.budgets().stream().map(Enum::name).toList());
		}
		if (filter.dueBy() != null) {
			where.append(" AND deadline <= :dueBy");
			params.put("dueBy", Date.valueOf(filter.dueBy()));
		}
		long total = this.jdbc.sql("SELECT count(*) FROM job.job WHERE " + where)
			.params(params)
			.query(Long.class)
			.single();
		params.put("limit", PAGE_SIZE);
		params.put("offset", (long) page * PAGE_SIZE);
		List<Job> items = this.jdbc.sql("SELECT " + Jobs.COLUMNS + " FROM job.job WHERE " + where
				+ " ORDER BY opened_at DESC, id LIMIT :limit OFFSET :offset")
			.params(params)
			.query(Jobs::job)
			.list();
		return new Page(items, page, total);
	}

	/** An Open Job Post that is not hidden. */
	@Transactional(readOnly = true)
	Optional<Job> find(UUID jobId) {
		return this.jdbc.sql("SELECT " + Jobs.COLUMNS + " FROM job.job WHERE id = :id AND " + VISIBLE)
			.param("id", jobId)
			.query(Jobs::job)
			.optional();
	}

	@Override
	public Optional<UUID> openCustomer(UUID jobId) {
		return find(jobId).map(Job::customerId);
	}

	/** Whether the screenshot belongs to an Open Job Post that is not hidden. */
	@Transactional(readOnly = true)
	boolean isOpenScreenshot(UUID fileId) {
		return this.jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM job.job_screenshot s JOIN job.job j ON j.id = s.job_id
				               WHERE s.file_id = :file AND j.state = 'OPEN' AND j.hidden_at IS NULL)
				""").param("file", fileId).query(Boolean.class).single();
	}

}
