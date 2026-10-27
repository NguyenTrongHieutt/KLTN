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
 * ADM-9 (FR-62, UX-DR110): users by Verification and locked accounts; nobody is approved since Story 19.7. An account holds no role (Story 20.3): {@code job} counts users by the roles they
 * hold per Job. {@code usersByVerification} counts each unerased person's account once under every Verification
 * it holds now, by the rules of {@code identity.capabilities}: {@code GITHUB} linked, {@code LINKEDIN} (nobody
 * until Story 19.6), and a {@code STUDENT} verification valid today in Vietnam.
 */
@Component
class IdentityCounts implements PlatformCounts {

	static final String USERS_BY_VERIFICATION = "usersByVerification";

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
		LocalDate today = LocalDate.ofInstant(this.clock.instant(), StudentVerifications.VIETNAM);
		this.jdbc.sql("SELECT count(*) FILTER (WHERE " + Accounts.GITHUB_LINKED + ") AS github, " + """
				       count(*) FILTER (WHERE v.valid_until >= :today) AS student
				FROM identity.account a
				LEFT JOIN identity.verification v ON v.account_id = a.id AND v.kind = 'STUDENT' AND v.ended_at IS NULL
				WHERE a.kind = 'USER' AND a.erased_at IS NULL
				""").param("today", today).query(rs -> {
			counts.add(new Count(USERS_BY_VERIFICATION, "GITHUB", rs.getLong("github")));
			counts.add(new Count(USERS_BY_VERIFICATION, "LINKEDIN", 0));
			counts.add(new Count(USERS_BY_VERIFICATION, "STUDENT", rs.getLong("student")));
		});
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
