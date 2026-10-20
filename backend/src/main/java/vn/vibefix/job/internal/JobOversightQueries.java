package vn.vibefix.job.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.job.JobOversight;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;

/** {@link JobOversight} over {@code job.job} and {@code job.milestone}. */
@Component
class JobOversightQueries implements JobOversight {

	private static final String OVERDUE = """
			EXISTS (SELECT 1 FROM job.milestone m WHERE m.job_id = j.id AND m.removed_at IS NULL AND m.overdue_since IS NOT NULL
			    AND m.state IN ('FUNDED', 'REVISION_REQUESTED'))""";

	private static final String COLUMNS = "j.id, j.title, j.state, j.customer_account_id, j.mentor_account_id, j.frozen_at, "
			+ "j.updated_at, " + OVERDUE + " AS overdue";

	private final JdbcClient jdbc;

	JobOversightQueries(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	@Transactional(readOnly = true)
	public Page search(@Nullable String state, @Nullable Flag flag, int page) {
		StringBuilder where = new StringBuilder();
		Map<String, Object> params = new HashMap<>();
		if (state != null) {
			try {
				JobState.valueOf(state);
			}
			catch (IllegalArgumentException unknown) {
				throw new ApiException(ErrorCode.VALIDATION);
			}
			where.append(" WHERE j.state = :state");
			params.put("state", state);
		}
		else {
			where.append(" WHERE j.state <> 'DRAFT'");
		}
		if (flag == Flag.OVERDUE) {
			where.append(" AND ").append(OVERDUE);
		}
		else if (flag == Flag.FROZEN) {
			where.append(" AND j.frozen_at IS NOT NULL");
		}
		long total = this.jdbc.sql("SELECT count(*) FROM job.job j" + where).params(params).query(Long.class).single();
		int current = Math.max(0, page);
		params.put("size", PAGE_SIZE);
		params.put("offset", current * PAGE_SIZE);
		List<JobLine> jobs = this.jdbc.sql("SELECT " + COLUMNS + " FROM job.job j" + where
				+ " ORDER BY j.updated_at DESC, j.id DESC LIMIT :size OFFSET :offset")
			.params(params)
			.query(JobOversightQueries::line)
			.list();
		return new Page(withMilestones(jobs), total, current, PAGE_SIZE);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<JobLine> job(UUID jobId) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM job.job j WHERE j.id = :id")
			.param("id", jobId)
			.query(JobOversightQueries::line)
			.optional()
			.map(line -> withMilestones(List.of(line)).getFirst());
	}

	private List<JobLine> withMilestones(List<JobLine> jobs) {
		if (jobs.isEmpty()) {
			return jobs;
		}
		Map<UUID, List<MilestoneLine>> milestones = new HashMap<>();
		this.jdbc.sql("""
				SELECT id, job_id, title, state, due_on, overdue_since,
				       row_number() OVER (PARTITION BY job_id ORDER BY position, created_at, id) AS n
				FROM job.milestone WHERE job_id IN (:jobs) AND removed_at IS NULL ORDER BY job_id, n
				""").param("jobs", jobs.stream().map(JobLine::id).toList()).query((rs, n) -> {
			Timestamp overdue = rs.getTimestamp("overdue_since");
			milestones.computeIfAbsent(rs.getObject("job_id", UUID.class), id -> new ArrayList<>())
				.add(new MilestoneLine(rs.getObject("id", UUID.class), rs.getInt("n"), rs.getString("title"),
						rs.getString("state"), rs.getObject("due_on", LocalDate.class),
						overdue == null ? null : overdue.toInstant()));
			return null;
		}).list();
		return jobs.stream()
			.map(job -> new JobLine(job.id(), job.title(), job.state(), job.customerId(), job.mentorId(), job.frozen(),
					job.overdue(), job.updatedAt(), List.copyOf(milestones.getOrDefault(job.id(), List.of()))))
			.toList();
	}

	private static JobLine line(ResultSet rs, int n) throws SQLException {
		Instant updated = rs.getTimestamp("updated_at").toInstant();
		return new JobLine(rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("state"),
				rs.getObject("customer_account_id", UUID.class), rs.getObject("mentor_account_id", UUID.class),
				rs.getTimestamp("frozen_at") != null, rs.getBoolean("overdue"), updated, List.of());
	}

}
