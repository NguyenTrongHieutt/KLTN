package vn.vibefix.job.internal;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.shared.PlatformCounts;

/**
 * ADM-9's users by the roles they hold per Job (FR-62, UX-DR110, Story 20.3), on {@code job}'s own tables by
 * the relations {@code job.roleOf} reads (AD-43): {@code CUSTOMER} is the Customer of any Job, {@code MENTOR} the
 * Mentor recorded on any Job, and {@code CREW} a member of any Job's Crew whose row was activated. A user is
 * counted once in each role they hold in any Job, so one person may count under several keys; an account holds
 * no role of its own.
 */
@Component
class JobRoleCounts implements PlatformCounts {

	static final String USERS_BY_JOB_ROLE = "usersByJobRole";

	private final JdbcClient jdbc;

	JobRoleCounts(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	@Transactional(readOnly = true)
	public List<Count> counts() {
		return this.jdbc.sql("""
				SELECT (SELECT count(DISTINCT customer_account_id) FROM job.job) AS customers,
				       (SELECT count(DISTINCT mentor_account_id) FROM job.job WHERE mentor_account_id IS NOT NULL) AS mentors,
				       (SELECT count(DISTINCT member_account_id) FROM job.crew_row WHERE activated_at IS NOT NULL) AS crew
				""")
			.query((rs, n) -> List.of(new Count(USERS_BY_JOB_ROLE, "CUSTOMER", rs.getLong("customers")),
					new Count(USERS_BY_JOB_ROLE, "MENTOR", rs.getLong("mentors")),
					new Count(USERS_BY_JOB_ROLE, "CREW", rs.getLong("crew"))))
			.single();
	}

}
