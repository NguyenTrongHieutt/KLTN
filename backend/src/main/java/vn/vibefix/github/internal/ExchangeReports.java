package vn.vibefix.github.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * {@code github.exchange_report} (AD-11, AD-44, FR-32, Story 23.8a): the one path report a
 * {@code handover-copy} run posts, kept as the run's own gzipped bytes. One row per correlation, so a second
 * post finds it and is a replay; each row goes with its correlation, which the revocation poller purges 30 days
 * after its expiry. Nothing here logs a path. The callers hold the transactions.
 */
@Component
class ExchangeReports {

	/** One row: the flag, the compressed NUL-separated list, and how many paths it holds. */
	record Row(UUID correlationId, boolean workflowsChanged, byte[] pathsGzip, int count, Instant reportedAt) {
	}

	private final JdbcClient jdbc;

	ExchangeReports(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Stores the correlation's one report.
	 * @return whether it was stored now; {@code false} when the correlation already has one
	 */
	boolean insert(Row row) {
		return this.jdbc.sql("""
				INSERT INTO github.exchange_report (correlation_id, workflows_changed, paths_gzip, path_count, reported_at)
				VALUES (:id, :workflows, :paths, :count, :at)
				ON CONFLICT (correlation_id) DO NOTHING
				""")
			.param("id", row.correlationId())
			.param("workflows", row.workflowsChanged())
			.param("paths", row.pathsGzip())
			.param("count", row.count())
			.param("at", Timestamp.from(row.reportedAt()))
			.update() == 1;
	}

	Optional<Row> find(UUID correlationId) {
		return this.jdbc.sql("""
				SELECT correlation_id, workflows_changed, paths_gzip, path_count, reported_at FROM github.exchange_report
				WHERE correlation_id = :id
				""")
			.param("id", correlationId)
			.query((rs, n) -> new Row(rs.getObject("correlation_id", UUID.class), rs.getBoolean("workflows_changed"),
					rs.getBytes("paths_gzip"), rs.getInt("path_count"), rs.getTimestamp("reported_at").toInstant()))
			.optional();
	}

	/** Whether the correlation already reported. */
	boolean exists(UUID correlationId) {
		return this.jdbc.sql("SELECT count(*) FROM github.exchange_report WHERE correlation_id = :id")
			.param("id", correlationId)
			.query(Integer.class)
			.single() > 0;
	}

}
