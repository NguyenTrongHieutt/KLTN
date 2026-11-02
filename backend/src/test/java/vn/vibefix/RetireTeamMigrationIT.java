package vn.vibefix;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import vn.vibefix.support.PostgresTestDatabase;

/**
 * Story 21.7's forward migrations on a database that still holds Teams and the rows that would otherwise break: the
 * four tables are gone and the empty schema stays (AD-37, SG-63); no {@code team.request_lapse} Deadline is left for
 * the poller to fail on forever; no {@code team.*} or {@code identity.verify_student} row reaches a screen as a raw
 * key, open or closed; and no queued or retryable email aborts with {@code TEMPLATE_MISSING}. Everything else stays,
 * and so does the audit trail (AD-8).
 */
class RetireTeamMigrationIT {

	private static final PostgresTestDatabase database = PostgresTestDatabase.instance();

	private static final String BEFORE = "202611021420";

	private static final String AFTER = "202611021530";

	private static final String ACCOUNT = "00000000-0000-7000-8000-00000000c001";

	private static final String TEAM = "00000000-0000-7000-8000-00000000c101";

	private static final String MEMBERSHIP = "00000000-0000-7000-8000-00000000c102";

	@Test
	void theTablesAndTheRowsThatNeedThemGoAndTheAuditTrailStays() throws Exception {
		String db = database.createScratchDatabase();
		flyway(db, BEFORE).migrate();
		execute(db, """
				INSERT INTO team.team (id, mentor_account_id, status, created_at) VALUES ('%s', '%s', 'OPEN', now())
				""".formatted(TEAM, ACCOUNT), """
				INSERT INTO team.membership (id, team_id, student_account_id, joined_at)
				VALUES ('%s', '%s', gen_random_uuid(), now())
				""".formatted(MEMBERSHIP, TEAM), """
				INSERT INTO team.team_request (id, team_id, student_account_id, kind, status, created_at, lapses_at)
				VALUES (gen_random_uuid(), '%s', gen_random_uuid(), 'JOIN_REQUEST', 'PENDING', now(),
				        now() + INTERVAL '14 days')
				""".formatted(TEAM), """
				INSERT INTO team.crew_reservation (crew_row_id, membership_id, job_id, reserved_at)
				VALUES (gen_random_uuid(), '%s', gen_random_uuid(), now())
				""".formatted(MEMBERSHIP), """
				INSERT INTO timers.deadline (id, kind, owner, subject_id, generation, pausable, status, due_at, created_at,
				    finished_at)
				VALUES (gen_random_uuid(), 'team.request_lapse', 'team', '%1$s', 'g', FALSE, 'PENDING', now(), now(), NULL),
				       (gen_random_uuid(), 'team.request_lapse', 'team', '%1$s', 'h', FALSE, 'FIRED', NULL, now(), now()),
				       (gen_random_uuid(), 'identity.verification_expiry', 'identity', '%1$s', 'g', FALSE, 'PENDING', now(),
				        now(), NULL)
				""".formatted(ACCOUNT), """
				INSERT INTO notification.notification (id, account_id, type, causing_record_id, params, link, created_at,
				    read_at)
				VALUES (gen_random_uuid(), '%1$s', 'team.invitation_received', gen_random_uuid(), '{}', '/', now(), NULL),
				       (gen_random_uuid(), '%1$s', 'team.invitation_answered', gen_random_uuid(), '{}', '/', now(), now()),
				       (gen_random_uuid(), '%1$s', 'team.join_request_received', gen_random_uuid(), '{}', '/', now(), NULL),
				       (gen_random_uuid(), '%1$s', 'team.join_request_answered', gen_random_uuid(), '{}', '/', now(), NULL),
				       (gen_random_uuid(), '%1$s', 'team.released', gen_random_uuid(), '{}', '/', now(), NULL),
				       (gen_random_uuid(), '%1$s', 'team.member_left', gen_random_uuid(), '{}', '/', now(), now()),
				       (gen_random_uuid(), '%1$s', 'team.member_removed', gen_random_uuid(), '{}', '/', now(), NULL),
				       (gen_random_uuid(), '%1$s', 'identity.verify_student', gen_random_uuid(), '{}', '/', now(), NULL),
				       (gen_random_uuid(), '%1$s', 'identity.account_locked', gen_random_uuid(), '{}', '/', now(), NULL),
				       (gen_random_uuid(), '%1$s', 'dispute.team_issue_raised', gen_random_uuid(), '{}', '/', now(), NULL)
				""".formatted(ACCOUNT), """
				INSERT INTO notification.todo (id, todo_key, account_id, subject_id, tier, params, link, opened_at, closed_at)
				VALUES (gen_random_uuid(), 'identity.verify_student', '%1$s', '%1$s', 3, '{}', '/', now(), NULL),
				       (gen_random_uuid(), 'identity.verify_student', '%1$s', '%1$s', 3, '{}', '/', now(), now()),
				       (gen_random_uuid(), 'identity.link_github', '%1$s', '%1$s', 1, '{}', '/', now(), NULL)
				""".formatted(ACCOUNT), """
				INSERT INTO operations.external_operation (id, operation_key, type, lane, priority, status, payload,
				    next_attempt_at, created_at, completed_at, lease_until)
				VALUES (gen_random_uuid(), 'k1', 'notification.email', 'EMAIL', 0, 'PENDING',
				        '{"template":"team.invitation_received"}', now(), now(), NULL, NULL),
				       (gen_random_uuid(), 'k2', 'notification.email', 'EMAIL', 0, 'PENDING',
				        '{"template":"team.join_request_received"}', now(), now(), NULL, NULL),
				       (gen_random_uuid(), 'k3', 'notification.email', 'EMAIL', 0, 'PENDING',
				        '{"template":"team.join_request_answered"}', now(), now(), NULL, NULL),
				       (gen_random_uuid(), 'k4', 'notification.email', 'EMAIL', 0, 'RUNNING',
				        '{"template":"team.released"}', now(), now(), NULL, now() + INTERVAL '1 minute'),
				       (gen_random_uuid(), 'k5', 'notification.email', 'EMAIL', 0, 'PENDING',
				        '{"template":"team.member_removed"}', now(), now(), NULL, NULL),
				       (gen_random_uuid(), 'k6', 'notification.email', 'EMAIL', 0, 'SUCCEEDED',
				        '{"template":"team.released"}', now(), now(), now(), NULL),
				       (gen_random_uuid(), 'k7', 'notification.email', 'EMAIL', 0, 'PENDING',
				        '{"template":"identity.account_locked"}', now(), now(), NULL, NULL),
				       (gen_random_uuid(), 'k8', 'notification.email', 'EMAIL', 0, 'FAILED',
				        '{"template":"team.member_removed"}', now(), now(), now(), NULL)
				""", """
				INSERT INTO audit.audit_entry (id, occurred_at, actor_type, actor_account_id, action)
				VALUES (gen_random_uuid(), now(), 'USER', '%s', 'team.member_left')
				""".formatted(ACCOUNT));

		flyway(db, AFTER).migrate();

		assertThat(rows(db, """
				SELECT coalesce(to_regclass('team.team')::text, 'gone') || ' '
				       || coalesce(to_regclass('team.membership')::text, 'gone') || ' '
				       || coalesce(to_regclass('team.team_request')::text, 'gone') || ' '
				       || coalesce(to_regclass('team.crew_reservation')::text, 'gone')
				""")).containsExactly("gone gone gone gone");
		assertThat(rows(db, "SELECT schema_name FROM information_schema.schemata WHERE schema_name = 'team'"))
			.as("the empty schema stays")
			.containsExactly("team");
		assertThat(rows(db, "SELECT table_name FROM information_schema.tables WHERE table_schema = 'team'")).isEmpty();
		assertThat(rows(db, "SELECT kind FROM timers.deadline ORDER BY kind"))
			.containsExactly("identity.verification_expiry");
		assertThat(rows(db, "SELECT type FROM notification.notification ORDER BY type"))
			.containsExactly("dispute.team_issue_raised", "identity.account_locked");
		assertThat(rows(db, "SELECT todo_key FROM notification.todo ORDER BY todo_key"))
			.containsExactly("identity.link_github");
		assertThat(rows(db, "SELECT operation_key FROM operations.external_operation ORDER BY operation_key"))
			.as("a finished email is history, and an email of a live template still goes")
			.containsExactly("k6", "k7");
		assertThat(rows(db, "SELECT action FROM audit.audit_entry WHERE action LIKE 'team.%'"))
			.containsExactly("team.member_left");
	}

	private static Flyway flyway(String db, String target) {
		return Flyway.configure()
			.dataSource(database.jdbcUrl(db), PostgresTestDatabase.MIGRATOR, database.migratorPassword())
			.schemas("platform")
			.defaultSchema("platform")
			.createSchemas(true)
			.locations("classpath:db/migration")
			.placeholders(Map.of("runtime_role", PostgresTestDatabase.APP, "environment", "dev"))
			.target(target)
			.load();
	}

	private static void execute(String db, String... statements) throws SQLException {
		try (Connection connection = database.migratorConnection(db); Statement statement = connection.createStatement()) {
			for (String sql : statements) {
				statement.execute(sql);
			}
		}
	}

	private static List<String> rows(String db, String sql) throws SQLException {
		List<String> values = new ArrayList<>();
		try (Connection connection = database.superuserConnection(db); Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery(sql)) {
			while (result.next()) {
				values.add(result.getString(1));
			}
		}
		return values;
	}

}
