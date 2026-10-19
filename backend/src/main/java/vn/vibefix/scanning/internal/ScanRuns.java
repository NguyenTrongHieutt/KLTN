package vn.vibefix.scanning.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.vibefix.scanning.ScanPurpose;
import vn.vibefix.scanning.ScanStatus;

/**
 * The scan runs as the operation sees them. Each change is one statement outside any transaction,
 * and none touches a finished run, so a late attempt never rewrites a result.
 */
@Component
class ScanRuns {

	/** One run with what its scan and its notifications need. */
	record Run(UUID id, UUID productId, UUID customerId, String productName, String repositoryFullName,
			ScanPurpose purpose, ScanStatus status) {
	}

	private final JdbcClient jdbc;

	ScanRuns(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	Optional<Run> find(UUID id) {
		return this.jdbc.sql("""
				SELECT id, product_id, customer_account_id, product_name, repository_full_name, purpose, status
				FROM scanning.scan_run WHERE id = :id
				""")
			.param("id", id)
			.query((rs, n) -> new Run(rs.getObject("id", UUID.class), rs.getObject("product_id", UUID.class),
					rs.getObject("customer_account_id", UUID.class), rs.getString("product_name"),
					rs.getString("repository_full_name"), ScanPurpose.valueOf(rs.getString("purpose")),
					ScanStatus.valueOf(rs.getString("status"))))
			.optional();
	}

	/** An attempt starts; a run an Administrator retries after it failed starts again. */
	void started(UUID id, Instant now) {
		this.jdbc.sql("""
				UPDATE scanning.scan_run
				SET status = 'RUNNING', attempts = attempts + 1, started_at = coalesce(started_at, :now),
				    finished_at = NULL, error_code = NULL
				WHERE id = :id AND status <> 'SUCCEEDED'
				""").param("id", id).param("now", Timestamp.from(now)).update();
	}

	/** The attempt failed; the operation tries again. */
	void retrying(UUID id, String code) {
		this.jdbc.sql("""
				UPDATE scanning.scan_run SET status = 'RETRYING', error_code = :code
				WHERE id = :id AND status IN ('PENDING', 'RUNNING', 'RETRYING')
				""").param("id", id).param("code", code).update();
	}

	/** No attempt is left, or none can help. */
	void failed(UUID id, String code, Instant now) {
		this.jdbc.sql("""
				UPDATE scanning.scan_run SET status = 'FAILED', error_code = :code, finished_at = :now
				WHERE id = :id AND status IN ('PENDING', 'RUNNING', 'RETRYING')
				""").param("id", id).param("code", code).param("now", Timestamp.from(now)).update();
	}

}
