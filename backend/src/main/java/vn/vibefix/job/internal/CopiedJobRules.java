package vn.vibefix.job.internal;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.job.JobRules;

/** {@link JobRules} on {@code job.job.rules}, the values copied at publication (FR-61). */
@Component
class CopiedJobRules implements JobRules {

	private final JdbcClient jdbc;

	CopiedJobRules(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	@Transactional(readOnly = true)
	public int value(UUID jobId, String rule, int fallback) {
		Integer value = this.jdbc.sql("SELECT (rules ->> :rule)::int FROM job.job WHERE id = :id")
			.param("rule", rule)
			.param("id", jobId)
			.query(Integer.class)
			.optional()
			.orElse(null);
		return value == null ? fallback : value;
	}

}
