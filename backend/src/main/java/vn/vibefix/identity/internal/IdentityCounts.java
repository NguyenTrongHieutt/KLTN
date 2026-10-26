package vn.vibefix.identity.internal;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.shared.PlatformCounts;

/**
 * ADM-9 (FR-62): users by role, Students by verification, Mentors by approval, applications by decision.
 * A Google account has no role (Story 19.1) and counts under {@code NONE}.
 */
@Component
class IdentityCounts implements PlatformCounts {

	private final JdbcClient jdbc;

	private final Clock clock;

	IdentityCounts(JdbcClient jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Override
	@Transactional(readOnly = true)
	public List<Count> counts() {
		List<Count> counts = new ArrayList<>();
		grouped(counts, "usersByRole", """
				SELECT coalesce(role, 'NONE') AS key, count(*) AS value FROM identity.account
				WHERE kind = 'USER' AND erased_at IS NULL GROUP BY 1
				""");
		this.jdbc.sql("""
				SELECT CASE WHEN v.valid_until >= :today THEN 'VERIFIED' ELSE 'UNVERIFIED' END AS key, count(*) AS value
				FROM identity.account a
				LEFT JOIN identity.verification v ON v.account_id = a.id AND v.kind = 'STUDENT' AND v.ended_at IS NULL
				WHERE a.role = 'STUDENT' AND a.erased_at IS NULL GROUP BY 1
				""")
			.param("today", LocalDate.ofInstant(this.clock.instant(), StudentVerifications.VIETNAM))
			.query((rs, n) -> counts.add(new Count("studentsByVerification", rs.getString("key"), rs.getLong("value"))))
			.list();
		grouped(counts, "mentorsByApproval", """
				SELECT mentor_approval_status AS key, count(*) AS value FROM identity.account
				WHERE role = 'MENTOR' AND erased_at IS NULL GROUP BY mentor_approval_status
				""");
		grouped(counts, "mentorApplicationsByDecision", """
				SELECT decision AS key, count(*) AS value FROM identity.mentor_decision GROUP BY decision
				""");
		grouped(counts, "lockedAccounts", """
				SELECT 'LOCKED' AS key, count(*) AS value FROM identity.account
				WHERE locked_at IS NOT NULL AND erased_at IS NULL HAVING count(*) > 0
				""");
		return counts;
	}

	private void grouped(List<Count> counts, String group, String sql) {
		this.jdbc.sql(sql).query((rs, n) -> counts.add(new Count(group, rs.getString("key"), rs.getLong("value")))).list();
	}

}
