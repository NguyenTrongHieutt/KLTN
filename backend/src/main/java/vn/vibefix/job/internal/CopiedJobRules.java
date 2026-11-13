package vn.vibefix.job.internal;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.job.JobRules;
import vn.vibefix.timers.Deadlines;

/** {@link JobRules} on {@code job.job.rules}, the values copied at publication (FR-61), and FR-40's Starting stall. */
@Component
class CopiedJobRules implements JobRules {

	private final JdbcClient jdbc;

	/** Looked up on use: {@code timers} collects the Deadline handlers, which come back to {@code job}. */
	private final ObjectProvider<Deadlines> deadlines;

	CopiedJobRules(JdbcClient jdbc, ObjectProvider<Deadlines> deadlines) {
		this.jdbc = jdbc;
		this.deadlines = deadlines;
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

	@Override
	public Optional<Instant> startingDeadline(UUID jobId) {
		return this.deadlines.getObject().dueAt(StartingDeadline.KEY, jobId);
	}

}
