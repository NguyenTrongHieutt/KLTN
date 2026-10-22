package vn.vibefix.dispute.internal;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.operations.ExternalOperations;
import vn.vibefix.operations.OperationRequest;

/**
 * SG-23's fourth hold case (AD-40), beside {@code payment}'s ledger check: a Team Issue, escalated or not,
 * still open past its copied answer and escalation periods plus 7 days goes to the failed-operations queue
 * as a {@value TeamIssueOverdueOperation#TYPE} operation, once a day.
 */
@Component
class TeamIssueCheck {

	/** The slack after both periods (AD-40). */
	static final int SLACK_DAYS = 7;

	private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

	private final JdbcClient jdbc;

	private final ExternalOperations operations;

	private final Clock clock;

	TeamIssueCheck(JdbcClient jdbc, ExternalOperations operations, Clock clock) {
		this.jdbc = jdbc;
		this.operations = operations;
		this.clock = clock;
	}

	@Scheduled(cron = "${vibefix.dispute.team-issue-check-cron:0 10 3 * * *}", zone = "Asia/Ho_Chi_Minh")
	void daily() {
		check();
	}

	/**
	 * Runs the check now.
	 * @return the overdue Team Issues
	 */
	@Transactional
	List<UUID> check() {
		List<UUID> overdue = this.jdbc.sql("""
				SELECT id FROM dispute.team_issue
				WHERE phase <> 'ENDED' AND raised_at + make_interval(days => answer_days + escalation_days + :slack) < :now
				ORDER BY raised_at, id
				""")
			.param("slack", SLACK_DAYS)
			.param("now", Timestamp.from(this.clock.instant()))
			.query(UUID.class)
			.list();
		LocalDate today = LocalDate.now(this.clock.withZone(VIETNAM));
		for (UUID id : overdue) {
			this.operations.request(new OperationRequest(TeamIssueOverdueOperation.TYPE,
					UUID.nameUUIDFromBytes((id + ":" + today).getBytes(StandardCharsets.UTF_8)),
					Map.of("teamIssueId", id.toString(), "day", today.toString()), null, null, 0));
		}
		return overdue;
	}

}
